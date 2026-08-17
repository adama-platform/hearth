package io.hearth.push;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Which browsers we can reach, and on whose behalf.
 *
 * One row per subscribed browser, owned by a session. Everything about the lifetime follows from
 * that: signing out deletes the session and the subscription with it, the reaper takes both, and
 * "sign me out everywhere" silences every device rather than merely forgetting the cookie on one.
 *
 * A subscription that a push service says is gone (404 or 410) is deleted rather than marked --
 * there is nothing on the other end to come back, and keeping it means retrying forever against an
 * endpoint that will never answer.
 */
public class PushSubs {
  private static final String COLUMNS =
      "id, session_id, user_id, endpoint, p256dh, auth, vapid_public, vapid_private, created_at,"
          + " last_push_at, failures, last_detail";
  /** how many refusals before we stop trying; the first can be a bad afternoon at the push service */
  public static final int MAX_FAILURES = 3;

  private final Store store;

  public PushSubs(Store store) {
    this.store = store;
  }

  public record Sub(long id, long sessionId, long userId, String endpoint, String p256dh,
                    String auth, String vapidPublic, String vapidPrivate, Timestamp createdAt,
                    Timestamp lastPushAt, int failures, String lastDetail) {
    public KeyPair keys() throws Exception {
      return new KeyPair(PushCrypto.publicKeyFrom(PushCrypto.unb64(vapidPublic)),
          PushCrypto.privateKeyFrom(PushCrypto.unb64(vapidPrivate)));
    }
  }

  /**
   * Mint the keypair this session will use, before the browser has subscribed to anything.
   *
   * The browser needs the public half to call `pushManager.subscribe`, and the subscription it gets
   * back is bound to that key -- so the pair has to exist first and has to be the same one we sign
   * with later. Called again for a session that already has one, it returns what is there rather
   * than minting a second: a new key would silently invalidate the subscription already registered.
   */
  public String publicKeyFor(long sessionId, long userId) throws SQLException {
    Sub existing = forSession(sessionId);
    if (existing != null && !existing.vapidPublic().isEmpty()) {
      return existing.vapidPublic();
    }
    KeyPair keys = PushCrypto.generateKeyPair();
    String pub = PushCrypto.b64(PushCrypto.publicKeyBytes(keys.getPublic()));
    String priv = PushCrypto.b64(PushCrypto.privateKeyBytes(keys.getPrivate()));
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.PUSH_SUBS + " (session_id, user_id, endpoint, vapid_public,"
                 + " vapid_private) VALUES (?, ?, '', ?, ?)")) {
      statement.setLong(1, sessionId);
      statement.setLong(2, userId);
      statement.setString(3, pub);
      statement.setString(4, priv);
      statement.executeUpdate();
    }
    return pub;
  }

  /**
   * The browser came back with where to reach it.
   *
   * Re-entrant by construction: a service worker may call this on every page load, and a browser
   * that re-subscribes gets the same endpoint back from its push service. So this updates the row
   * this session already has rather than accumulating one per visit -- and the endpoint is stored on
   * the row that already holds the matching VAPID pair, because the two only work together.
   */
  public void subscribe(long sessionId, long userId, String endpoint, String p256dh, String auth)
      throws SQLException {
    // An endpoint that turns up under a different session of the *same person* belongs to a browser
    // that signed in again; the old session's claim on it is stale and would mean two pushes for one
    // device.
    //
    // `user_id` in that condition is the whole of it. Without it, posting somebody
    // else's endpoint here silently unsubscribed their device -- a different person's browser is
    // never "the same browser signing in again", and an endpoint is not a credential this server
    // should treat as proof of anything.
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PUSH_SUBS
                 + " WHERE endpoint = ? AND user_id = ? AND session_id <> ?")) {
      statement.setString(1, endpoint);
      statement.setLong(2, userId);
      statement.setLong(3, sessionId);
      statement.executeUpdate();
    }
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PUSH_SUBS + " SET endpoint = ?, p256dh = ?, auth = ?, failures = 0,"
                 + " last_detail = '' WHERE session_id = ?")) {
      statement.setString(1, endpoint);
      statement.setString(2, p256dh);
      statement.setString(3, auth);
      statement.setLong(4, sessionId);
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      // no keypair was minted first, which means somebody posted here without asking for one
      return;
    }
    store.changed(Schema.PUSH_SUBS, sessionId, MutationEvent.Kind.update, userId);
  }

  public Sub forSession(long sessionId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.PUSH_SUBS + " WHERE session_id = ?")) {
      statement.setLong(1, sessionId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /** every browser one person can be reached on; an empty endpoint is a key with no subscription */
  public List<Sub> forUser(long userId) throws SQLException {
    ArrayList<Sub> subs = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.PUSH_SUBS + " WHERE user_id = ?"
                 + " AND endpoint <> '' AND failures < " + MAX_FAILURES)) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          subs.add(read(rows));
        }
      }
    }
    return subs;
  }

  public void recordSuccess(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PUSH_SUBS + " SET last_push_at = CURRENT_TIMESTAMP, failures = 0,"
                 + " last_detail = 'ok' WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  /** a push service saying the browser is gone is the truth; anything else is a strike */
  public void recordFailure(long id, boolean gone, String detail) throws SQLException {
    if (gone) {
      delete(id);
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PUSH_SUBS + " SET failures = failures + 1, last_detail = ?"
                 + " WHERE id = ?")) {
      statement.setString(1, detail == null ? "" : detail.substring(0, Math.min(256, detail.length())));
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  public void delete(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PUSH_SUBS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  /** what signing out does; the session is going, and this goes with it */
  public int forgetSession(long sessionId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PUSH_SUBS + " WHERE session_id = ?")) {
      statement.setLong(1, sessionId);
      return statement.executeUpdate();
    }
  }

  public int forgetUser(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PUSH_SUBS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      return statement.executeUpdate();
    }
  }

  /**
   * Anything whose session is no longer there.
   *
   * The belt to the delete's braces. Sessions are removed by the reaper as well as by signing out,
   * and a subscription outliving its session is a browser we could still reach on behalf of a login
   * that no longer exists -- which is precisely what binding them together was meant to prevent.
   */
  public int sweepOrphans() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PUSH_SUBS + " WHERE session_id NOT IN"
                 + " (SELECT id FROM " + Schema.SESSIONS + ")")) {
      return statement.executeUpdate();
    }
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.PUSH_SUBS + " WHERE endpoint <> ''")) {
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static Sub read(ResultSet rows) throws SQLException {
    return new Sub(rows.getLong("id"), rows.getLong("session_id"), rows.getLong("user_id"),
        rows.getString("endpoint"), rows.getString("p256dh"), rows.getString("auth"),
        rows.getString("vapid_public"), rows.getString("vapid_private"),
        rows.getTimestamp("created_at"), rows.getTimestamp("last_push_at"),
        rows.getInt("failures"), rows.getString("last_detail"));
  }
}
