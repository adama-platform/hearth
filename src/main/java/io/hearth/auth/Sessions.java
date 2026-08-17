package io.hearth.auth;

import io.hearth.common.Verbose;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session tokens: issue, check, revoke, and keep the whole thing fast.
 *
 * The cache is write-through, not write-back. Every mutation -- a new session, a revocation, a
 * whole-account sign-out -- hits the database first and the map second. That ordering is the point:
 * a revocation that lands in memory and then loses a race with a crash is a token that still works,
 * and "log me out everywhere" has to mean it.
 *
 * Reads are the opposite shape. A hit answers from a ConcurrentHashMap with no lock and no I/O; a
 * miss falls through to one indexed lookup and populates. For a thousand people online, the steady
 * state is a thousand map entries and essentially zero database traffic on the request path, which
 * is what makes checking a cookie cheap enough to do on every single request.
 *
 * last_seen_at is the one thing that is deliberately NOT written through. Touching a row on every
 * request would turn a read-mostly workload into a write-heavy one to maintain a field nobody reads
 * in real time, so it is updated in memory and flushed by the reaper.
 */
public class Sessions {
  private static final Logger LOG = LoggerFactory.getLogger(Sessions.class);
  private final Store store;
  /** for the one sweep that is about an account rather than a session */
  private final Users users;
  private final LoginSecurity security;
  private final Verbose verbose;
  private final ConcurrentHashMap<String, Entry> cache;
  private final ScheduledExecutorService reaper;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private volatile boolean running;

  /**
   * How stale last_seen_at is allowed to get before a live session writes it down.
   *
   * The live channel turned this from a detail into a decision: every open tab now touches its
   * session every twenty seconds forever, so a one-minute rule would have made "somebody left a
   * browser open" the single largest source of writes in the server. Ten minutes on confirmed
   * activity keeps the answer useful and the write rate flat.
   */
  static final long ACTIVITY_MILLIS = 600_000L;

  private static final String COLUMNS =
      "id, token_hash, user_id, created_at, last_seen_at, expires_at, revoked_at, robot, agent";

  public Sessions(Store store, LoginSecurity security, Verbose verbose) {
    this.store = store;
    this.users = new Users(store);
    this.security = security;
    this.verbose = verbose;
    this.cache = new ConcurrentHashMap<>(Math.max(16, security.cacheMaxSessions));
    this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "session-reaper-" + store.databaseDomain);
      thread.setDaemon(true);
      return thread;
    });
    this.running = false;
  }

  /** start the background sweep; separate from the constructor so tests can drive sweep() by hand */
  public void start() {
    if (running) {
      return;
    }
    running = true;
    long interval = security.reaperIntervalSeconds;
    reaper.scheduleWithFixedDelay(() -> {
      try {
        sweep();
      } catch (Exception ex) {
        LOG.error("session-reaper-failed", ex);
      }
    }, interval, interval, TimeUnit.SECONDS);
  }

  public void shutdown() {
    running = false;
    reaper.shutdownNow();
  }

  // ---- issuing -----------------------------------------------------------------------------

  /** mint a session for a person and hand back the token; only its hash is stored */
  public Issued create(long userId, String ip, String userAgent) throws SQLException {
    return mint(userId, ip, userAgent, false, null, security.sessionLifetimeSeconds);
  }

  /**
   * Mint a token for an agent acting as somebody.
   *
   * The same row in the same table, deliberately: an agent token has to be revocable, reapable and
   * resolvable exactly like a login, and giving it a parallel implementation would mean two places
   * that have to agree about what "still valid" means. What differs is the bit, which is what makes
   * "who did this" answerable afterwards, and the cap, which agents sit outside of -- somebody
   * signing in on a fifth laptop must not silently disconnect their assistant, and an assistant
   * reconnecting must not sign anybody out of anything.
   */
  public Issued createForAgent(long userId, String agent, long lifetimeSeconds) throws SQLException {
    return mint(userId, null, agent, true, agent, lifetimeSeconds);
  }

  private Issued mint(long userId, String ip, String userAgent, boolean robot, String agent,
                      long lifetimeSeconds) throws SQLException {
    String token = Tokens.newSessionToken();
    String tokenHash = Tokens.hash(token);
    long now = System.currentTimeMillis();
    long expiresAt = lifetimeSeconds <= 0 ? SessionRecord.NEVER : now + lifetimeSeconds * 1000L;

    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.SESSIONS + " (token_hash, user_id, created_at, last_seen_at,"
                 + " expires_at, ip, user_agent, robot, agent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, tokenHash);
      statement.setLong(2, userId);
      statement.setTimestamp(3, new Timestamp(now));
      statement.setTimestamp(4, new Timestamp(now));
      statement.setTimestamp(5, expiresAt == SessionRecord.NEVER ? null : new Timestamp(expiresAt));
      statement.setString(6, trim(ip, 64));
      statement.setString(7, trim(userAgent, 256));
      statement.setBoolean(8, robot);
      statement.setString(9, trim(agent, 128));
      statement.executeUpdate();
      long id;
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
      SessionRecord record =
          new SessionRecord(id, tokenHash, userId, now, now, expiresAt, SessionRecord.NEVER, robot, agent);
      put(record, now);
      if (!robot) {
        // a new session is exactly when the cap should bite, so enforce it now rather than waiting
        // up to a reaper interval for the surplus to disappear
        enforceCap(userId, now);
      }
      return new Issued(token, record);
    }
  }

  /** how many live tokens a named connector is holding, across everybody */
  public int agentTokensFor(String agent) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.SESSIONS
                 + " WHERE revoked_at IS NULL AND robot = TRUE AND agent = ?")) {
      statement.setString(1, agent);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  /**
   * Cut off every token a connector holds.
   *
   * Matched by the agent label rather than a foreign key, because the label is what was written on
   * the token when it was issued -- and a token has to stay revocable even after its registration
   * row is gone, which is exactly the order the disconnect button does this in.
   */
  public int revokeAgentsOf(String agent) throws SQLException {
    ArrayList<String> doomed = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT token_hash FROM " + Schema.SESSIONS
                 + " WHERE revoked_at IS NULL AND robot = TRUE AND agent = ?")) {
      statement.setString(1, agent);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          doomed.add(rows.getString(1));
        }
      }
    }
    for (String tokenHash : doomed) {
      revokeHash(tokenHash);
    }
    return doomed.size();
  }

  /** every agent token this person has handed out, newest first */
  public List<SessionRecord> agentsFor(long userId) throws SQLException {
    ArrayList<SessionRecord> agents = new ArrayList<>();
    for (SessionRecord session : activeFor(userId)) {
      if (session.robot()) {
        agents.add(session);
      }
    }
    return agents;
  }

  // ---- checking ----------------------------------------------------------------------------

  /**
   * Resolve a cookie value to a live session, or null.
   *
   * The hot path: hash, map lookup, three long comparisons. No allocation beyond the hash and no
   * I/O when the session is cached.
   */
  /**
   * Confirmed activity: a person with the page open, rather than a request that might be anything.
   *
   * Resolution already touches a session on every request, which includes a crawler with a stolen
   * cookie and a prefetch nobody looked at. This is called from the live channel, where the other
   * end is a browser somebody is sitting in front of -- and it is the same coarse rule, so calling
   * it costs a map write and nothing else.
   */
  public void active(SessionRecord session) {
    if (session == null) {
      return;
    }
    Entry entry = cache.get(session.tokenHash());
    if (entry != null) {
      entry.touch(System.currentTimeMillis());
    }
  }

  public SessionRecord resolve(String token) {
    if (token == null || token.isEmpty()) {
      return null;
    }
    String tokenHash = Tokens.hash(token);
    long now = System.currentTimeMillis();
    Entry entry = cache.get(tokenHash);
    if (entry != null) {
      hits.incrementAndGet();
      SessionRecord record = entry.record;
      if (!record.isLive(now, security.sessionIdleSeconds)) {
        cache.remove(tokenHash, entry);
        return null;
      }
      entry.touch(now);
      return record;
    }
    misses.incrementAndGet();
    SessionRecord record = load(tokenHash);
    if (record == null || !record.isLive(now, security.sessionIdleSeconds)) {
      return null;
    }
    put(record, now);
    return record;
  }

  private SessionRecord load(String tokenHash) {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.SESSIONS + " WHERE token_hash = ?")) {
      statement.setString(1, tokenHash);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    } catch (SQLException ex) {
      LOG.error("session-load-failed", ex);
      return null;
    }
  }

  // ---- revoking ----------------------------------------------------------------------------

  /** end one session; the database first, then the cache */
  /**
   * Sign out: the row goes, not just a flag on it.
   *
   * Revoking leaves a row a reaper deletes a day later, which was fine when a session was only a
   * cookie. It is not fine now that a session owns a push subscription: for that day the server
   * still holds a key that can make a notification appear on a device somebody has just told it to
   * forget. So signing out deletes, and the id is returned so the caller can take everything hanging
   * off it with the same click.
   *
   * Revocation stays for the cases it is right for -- an admin disabling an account, a password
   * change invalidating everything at once -- where a row saying *when* and *why* is worth keeping
   * for a day.
   */
  public Long delete(String token) throws SQLException {
    if (token == null) {
      return null;
    }
    String hash = Tokens.hash(token);
    Long id = null;
    try (Connection connection = store.connection();
         PreparedStatement find = connection.prepareStatement(
             "SELECT id FROM " + Schema.SESSIONS + " WHERE token_hash = ?")) {
      find.setString(1, hash);
      try (ResultSet rows = find.executeQuery()) {
        if (rows.next()) {
          id = rows.getLong(1);
        }
      }
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SESSIONS + " WHERE token_hash = ?")) {
      statement.setString(1, hash);
      statement.executeUpdate();
    }
    cache.remove(hash);
    return id;
  }

  /** one session by its id; used where the token is not to hand, and by tests */
  public SessionRecord byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.SESSIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /** remove a session by id, the way expiry and the reaper do */
  public void deleteById(long id) throws SQLException {
    String hash = null;
    SessionRecord record = byId(id);
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SESSIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    if (record != null) {
      cache.remove(record.tokenHash());
    }
  }

  public void revoke(String token) throws SQLException {
    revokeHash(Tokens.hash(token));
  }

  public void revokeHash(String tokenHash) throws SQLException {
    long now = System.currentTimeMillis();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.SESSIONS + " SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")) {
      statement.setTimestamp(1, new Timestamp(now));
      statement.setString(2, tokenHash);
      statement.executeUpdate();
    }
    cache.remove(tokenHash);
  }

  /** end every session for one person -- the "sign me out everywhere" button */
  /**
   * Delete every session somebody has, rather than marking them dead.
   *
   * For erasure, where a revoked row that lingers for a day is a row holding a person this server
   * has been asked to forget. Signing out already deletes for the same reason -- a revoked session
   * still owns a push subscription, and a key that can reach a device.
   */
  public int deleteAllFor(long userId) throws SQLException {
    int count;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SESSIONS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      count = statement.executeUpdate();
    }
    cache.values().removeIf(entry -> entry.record.userId() == userId);
    return count;
  }

  public int revokeAllFor(long userId) throws SQLException {
    long now = System.currentTimeMillis();
    int count;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.SESSIONS + " SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
      statement.setTimestamp(1, new Timestamp(now));
      statement.setLong(2, userId);
      count = statement.executeUpdate();
    }
    cache.values().removeIf(entry -> entry.record.userId() == userId);
    return count;
  }

  // ---- the reaper --------------------------------------------------------------------------

  /**
   * One sweep: flush last_seen_at, drop dead rows, evict cold cache entries, apply the cap.
   *
   * Runs on a timer and is safe to call by hand, which is how the tests drive it without waiting
   * on a schedule.
   */
  /** set by Accounts so the reaper can take a session's push subscription with it */
  private volatile io.hearth.push.PushSubs pushSubs;

  public void cascadeTo(io.hearth.push.PushSubs subs) {
    this.pushSubs = subs;
  }

  public Swept sweep() {
    long now = System.currentTimeMillis();
    int flushed = flushLastSeen(now);
    int deleted = deleteDead(now);
    int evicted = evictCache(now);
    int capped = enforceCapForEveryone(now);
    if (pushSubs != null) {
      try {
        // belt to the delete's braces: a session can also go by expiring or being reaped, and a
        // subscription outliving its session is a browser we could still reach on behalf of a
        // login that no longer exists
        int orphans = pushSubs.sweepOrphans();
        if (orphans > 0) {
          verbose.detail(() -> "sessions: " + orphans + " orphaned push subscription(s) removed");
        }
      } catch (SQLException ex) {
        LOG.error("push-orphan-sweep-failed", ex);
      }
    }
    forgetOldSignupIps(now);
    Swept swept = new Swept(flushed, deleted, evicted, capped, cache.size());
    if (swept.didAnything()) {
      verbose.say(() -> store.databaseDomain + ": reaper " + swept);
    }
    return swept;
  }

  /**
   * Age out the sign-up IP addresses, on the same sweep as everything else.
   *
   * Here rather than in a thread of its own because it is the same job -- things that stop being
   * worth keeping -- and a second timer would be a second thing to start, stop and get wrong.
   */
  private void forgetOldSignupIps(long now) {
    if (security.signupIpDays <= 0) {
      return;
    }
    try {
      int forgotten = users.forgetOldSignupIps(now - security.signupIpDays * 86_400_000L);
      if (forgotten > 0) {
        verbose.detail(() -> "sessions: forgot " + forgotten + " sign-up IP address(es)");
      }
    } catch (SQLException ex) {
      LOG.error("signup-ip-sweep-failed", ex);
    }
  }

  /** push the in-memory last_seen_at back to disk in one statement per touched session */
  private int flushLastSeen(long now) {
    ArrayList<Entry> dirty = new ArrayList<>();
    for (Entry entry : cache.values()) {
      if (entry.dirty) {
        dirty.add(entry);
      }
    }
    if (dirty.isEmpty()) {
      return 0;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.SESSIONS + " SET last_seen_at = ? WHERE id = ?")) {
      for (Entry entry : dirty) {
        statement.setTimestamp(1, new Timestamp(entry.record.lastSeenAt()));
        statement.setLong(2, entry.record.id());
        statement.addBatch();
        entry.dirty = false;
      }
      statement.executeBatch();
      return dirty.size();
    } catch (SQLException ex) {
      LOG.error("session-flush-failed", ex);
      return 0;
    }
  }

  /**
   * Remove rows that can never authenticate again.
   *
   * Revoked rows are kept for a day so that an operator looking into an incident can still see that
   * a session existed and when it ended; after that they are noise.
   */
  private int deleteDead(long now) {
    try (Connection connection = store.connection()) {
      int deleted = 0;
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.SESSIONS + " WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
        statement.setTimestamp(1, new Timestamp(now));
        deleted += statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.SESSIONS + " WHERE revoked_at IS NOT NULL AND revoked_at <= ?")) {
        statement.setTimestamp(1, new Timestamp(now - TimeUnit.DAYS.toMillis(1)));
        deleted += statement.executeUpdate();
      }
      if (security.sessionIdleSeconds > 0) {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + Schema.SESSIONS + " WHERE last_seen_at <= ?")) {
          statement.setTimestamp(1, new Timestamp(now - security.sessionIdleSeconds * 1000L));
          deleted += statement.executeUpdate();
        }
      }
      return deleted;
    } catch (SQLException ex) {
      LOG.error("session-delete-failed", ex);
      return 0;
    }
  }

  /**
   * Trim the cache: anything untouched past the TTL, then the coldest entries if still over the
   * ceiling. The database row survives either way, so an evicted session still works -- it just
   * costs one lookup the next time it is seen.
   */
  private int evictCache(long now) {
    int evicted = 0;
    long ttl = security.cacheTtlSeconds * 1000L;
    for (java.util.Map.Entry<String, Entry> entry : cache.entrySet()) {
      Entry value = entry.getValue();
      if (now - value.lastAccess >= ttl || !value.record.isLive(now, security.sessionIdleSeconds)) {
        if (value.dirty) {
          continue; // let the next flush write it out before we forget it
        }
        cache.remove(entry.getKey(), value);
        evicted++;
      }
    }
    int over = cache.size() - security.cacheMaxSessions;
    if (over > 0) {
      ArrayList<java.util.Map.Entry<String, Entry>> byAge = new ArrayList<>(cache.entrySet());
      byAge.sort(Comparator.comparingLong(candidate -> candidate.getValue().lastAccess));
      for (int k = 0; k < over && k < byAge.size(); k++) {
        cache.remove(byAge.get(k).getKey(), byAge.get(k).getValue());
        evicted++;
      }
    }
    return evicted;
  }

  private int enforceCapForEveryone(long now) {
    if (security.maxActiveSessions <= 0) {
      return 0;
    }
    ArrayList<Long> users = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT user_id FROM " + Schema.SESSIONS + " WHERE revoked_at IS NULL"
                 + " GROUP BY user_id HAVING COUNT(*) > ?")) {
      statement.setInt(1, security.maxActiveSessions);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          users.add(rows.getLong(1));
        }
      }
    } catch (SQLException ex) {
      LOG.error("session-cap-scan-failed", ex);
      return 0;
    }
    int revoked = 0;
    for (long userId : users) {
      revoked += enforceCap(userId, now);
    }
    return revoked;
  }

  /**
   * Keep at most maxActiveSessions per person, but only ever take away sessions that have been
   * around longer than the grace window.
   *
   * That asymmetry is what makes "infinite sessions, but only four that stick" work. Signing in on
   * a fifth device does not immediately kill a session somebody is using; the surplus is collected
   * once the newcomer has settled, oldest first.
   */
  int enforceCap(long userId, long now) {
    if (security.maxActiveSessions <= 0) {
      return 0;
    }
    long cutoff = now - security.sessionCapGraceSeconds * 1000L;
    ArrayList<String> doomed = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT token_hash, created_at FROM " + Schema.SESSIONS
                 + " WHERE user_id = ? AND revoked_at IS NULL AND robot = FALSE"
                 + " ORDER BY created_at DESC")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        int rank = 0;
        while (rows.next()) {
          rank++;
          String tokenHash = rows.getString(1);
          long createdAt = rows.getTimestamp(2).getTime();
          if (rank > security.maxActiveSessions && createdAt <= cutoff) {
            doomed.add(tokenHash);
          }
        }
      }
    } catch (SQLException ex) {
      LOG.error("session-cap-failed", ex);
      return 0;
    }
    for (String tokenHash : doomed) {
      try {
        revokeHash(tokenHash);
      } catch (SQLException ex) {
        LOG.error("session-cap-revoke-failed", ex);
      }
    }
    if (!doomed.isEmpty()) {
      verbose.detail("session cap: revoked " + doomed.size() + " session(s) for user " + userId);
    }
    return doomed.size();
  }

  // ---- inspection --------------------------------------------------------------------------

  public int cacheSize() {
    return cache.size();
  }

  public long cacheHits() {
    return hits.get();
  }

  public long cacheMisses() {
    return misses.get();
  }

  /** live sessions for one person, newest first, for a "where am I signed in" page */
  public List<SessionRecord> activeFor(long userId) throws SQLException {
    ArrayList<SessionRecord> sessions = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.SESSIONS
                 + " WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          sessions.add(read(rows));
        }
      }
    }
    return sessions;
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.SESSIONS + " WHERE revoked_at IS NULL");
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private void put(SessionRecord record, long now) {
    cache.put(record.tokenHash(), new Entry(record, now));
  }

  private static SessionRecord read(ResultSet rows) throws SQLException {
    Timestamp expires = rows.getTimestamp("expires_at");
    Timestamp revoked = rows.getTimestamp("revoked_at");
    return new SessionRecord(
        rows.getLong("id"),
        rows.getString("token_hash"),
        rows.getLong("user_id"),
        rows.getTimestamp("created_at").getTime(),
        rows.getTimestamp("last_seen_at").getTime(),
        expires == null ? SessionRecord.NEVER : expires.getTime(),
        revoked == null ? SessionRecord.NEVER : revoked.getTime(),
        rows.getBoolean("robot"),
        rows.getString("agent"));
  }

  private static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /** a cached session plus the bookkeeping the cache needs and the record shouldn't carry */
  private static final class Entry {
    volatile SessionRecord record;
    volatile long lastAccess;
    volatile boolean dirty;

    Entry(SessionRecord record, long now) {
      this.record = record;
      this.lastAccess = now;
      this.dirty = false;
    }

    /**
     * Mark the session seen. Deliberately coarse: last_seen_at only moves if it is
     * {@link #ACTIVITY_MILLIS} stale, so a person clicking around a site, or a live channel
     * beating every twenty seconds, produces one pending write every ten minutes rather than one
     * per request. The value is only ever read as "roughly when were they last here", and ten
     * minutes is well inside what that question means.
     */
    void touch(long now) {
      lastAccess = now;
      if (now - record.lastSeenAt() > ACTIVITY_MILLIS) {
        record = record.touched(now);
        dirty = true;
      }
    }
  }

  /** the token to hand out, and the row that backs it */
  public record Issued(String token, SessionRecord record) {
  }

  /** what one reaper pass did */
  public record Swept(int flushed, int deleted, int evicted, int capped, int cached) {
    public boolean didAnything() {
      return flushed > 0 || deleted > 0 || evicted > 0 || capped > 0;
    }

    @Override
    public String toString() {
      return "flushed=" + flushed + " deleted=" + deleted + " evicted=" + evicted
          + " capped=" + capped + " cached=" + cached;
    }
  }
}
