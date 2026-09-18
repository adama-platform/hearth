package io.hearth.content;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Addresses that answer with another address, and the proposals waiting to become one.
 *
 * <b>A published address is a promise.</b> Somebody bookmarked it, somebody linked to it, and a
 * search engine holds it with whatever standing the page earned over however long it has been
 * there. Moving the page and leaving a 404 behind throws all of that away without telling anybody:
 * the link still exists, it simply stops working, and the only person who finds out is the one who
 * followed it.
 *
 * <b>A rewrite is consulted after every real answer, never before.</b> Content wins, then a
 * directory listing, then this. That ordering is what makes recreating a page at an old address
 * simply work -- the page answers and the rewrite becomes dead weight rather than a redirect that
 * now shadows a real page. The screen says when one is in that state; nothing has to be deleted for
 * the site to be correct.
 *
 * <b>Chains are collapsed rather than followed.</b> If `/a` points at `/b` and `/b` then moves to
 * `/c`, accepting that second move re-points `/a` at `/c` as well. A chain costs a round trip per
 * hop, bleeds ranking at each one, and browsers give up after a handful -- and the alternative,
 * following the chain at request time, is the same cost paid on every request for ever instead of
 * once when somebody presses a button.
 *
 * <b>Nothing here is automatic.</b> Moving a published page proposes a rewrite; a person accepts
 * it. An automatic redirect is right about nine moves in ten and silently wrong about the tenth --
 * the one where somebody fixed a typo in an address nobody had used yet -- and a redirect nobody
 * decided on is one nobody remembers making.
 */
public class Rewrites {
  /** how many rewrites one site may have; past this it is a routing table rather than a tidy-up */
  public static final int MAX = 2000;
  /** how far a chain is walked when collapsing one, which is a loop guard rather than a feature */
  private static final int MAX_CHAIN = 16;

  private final Store store;

  public Rewrites(Store store) {
    this.store = store;
  }

  /**
   * What a rewrite answers with.
   *
   * <b>301 is the default and that is an SEO decision rather than a technical one.</b> Every
   * crawler, every audit tool and every CDN has understood 301 as "this content moved, move the
   * ranking with it" for twenty-five years. Google treats 308 identically today, and the rest of
   * the ecosystem does not -- so the safe answer for a page that moved is the one everything
   * agrees about.
   *
   * <b>That is deliberately not what {@link io.hearth.web.Canonical} uses.</b> It answers 308,
   * because it is redirecting a *request* -- possibly a POST, with a body -- from the wrong host to
   * the right one, and 301 permits a browser to turn that into a GET. A content move is a GET by
   * construction: a page is a thing you read, and the addresses that accept a POST are somewhere
   * else entirely. Different question, different answer, and worth writing down because the
   * inconsistency is the first thing a reader notices.
   */
  public enum Status {
    /** the content moved, and the ranking should move with it. The answer for a page that moved */
    moved(301, "Moved permanently", "The page moved. Search engines move its standing to the new"
        + " address. This is the right answer almost always."),
    /**
     * The same, preserving the method.
     *
     * Only matters if something POSTs to the old address, which for a page it does not. Here
     * because a person who knows they want it should not have to go round the software.
     */
    movedStrict(308, "Moved permanently, same method", "As above, and a POST stays a POST."
        + " Rarely what a page needs."),
    /** it is somewhere else for now. No ranking moves, which is the point */
    temporary(302, "Temporarily elsewhere", "For now. Nothing moves its standing, so this is right"
        + " for a page that is coming back."),
    temporaryStrict(307, "Temporarily elsewhere, same method", "As above, and a POST stays a"
        + " POST."),
    /**
     * There is nothing here and there will not be again.
     *
     * <b>Better than a 404 when it is true.</b> A crawler treats 404 as "possibly a mistake" and
     * comes back for months; 410 is "this is deliberate" and it drops the address quickly. It is
     * the only status with no target, because the answer is that there is nowhere to go.
     */
    gone(410, "Gone for good", "There is nothing here any more and nothing to send anybody to."
        + " A search engine drops it far faster than it drops a 404.");

    public final int code;
    public final String label;
    public final String explanation;

    Status(int code, String label, String explanation) {
      this.code = code;
      this.label = label;
      this.explanation = explanation;
    }

    public boolean needsTarget() {
      return this != gone;
    }

    public boolean isPermanent() {
      return this == moved || this == movedStrict;
    }

    /** null rather than an exception: a code in a row this software stopped offering is data */
    public static Status of(int code) {
      for (Status status : values()) {
        if (status.code == code) {
          return status;
        }
      }
      return null;
    }
  }

  /** proposed and waiting for somebody, or live */
  public enum State {
    /** this server suggested it when a published page moved; it does nothing until accepted */
    proposed,
    /** it fires */
    active;

    static State of(String raw) {
      if (raw == null) {
        return active;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        // a state this software does not understand is treated as waiting for a person, which is
        // the direction that does nothing rather than the direction that redirects traffic
        return proposed;
      }
    }
  }

  public record Record(long id, String fromUri, String toUri, int status, State state,
                       boolean enabled, String note, Long contentId, long hits,
                       Timestamp lastUsedAt, Timestamp createdAt, Timestamp updatedAt,
                       Long createdBy) {

    public Status statusOr() {
      Status known = Status.of(status);
      return known == null ? Status.moved : known;
    }

    public boolean isGone() {
      return statusOr() == Status.gone;
    }

    public boolean fires() {
      return state == State.active && enabled;
    }

    public String describe() {
      return isGone() ? fromUri + " is gone" : fromUri + " -> " + toUri;
    }
  }

  // ---- the request path ----------------------------------------------------------------------

  /**
   * What answers this address, or null.
   *
   * <b>One query, on a path that has already missed everything else.</b> This runs immediately
   * before a 404, so the cost is paid by requests that were going to be refused anyway -- a
   * redirect is not on the hot path by construction, because anything on the hot path found a page.
   */
  public Record forUri(String uri) throws SQLException {
    if (uri == null || uri.isBlank() || uri.length() > 512) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.REWRITES
                 + " WHERE from_uri = ? AND state = 'active' AND enabled = TRUE")) {
      statement.setString(1, normalize(uri));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  /**
   * Record that a rewrite fired.
   *
   * Called from a buffer on a timer rather than per request, for the reason the schema gives: a
   * redirect is the fastest thing this server does and a write on it would be the slowest part.
   */
  public void used(long id, long times, long whenMillis) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.REWRITES + " SET hits = hits + ?, last_used_at = ? WHERE id = ?")) {
      statement.setLong(1, times);
      statement.setTimestamp(2, new Timestamp(whenMillis));
      statement.setLong(3, id);
      statement.executeUpdate();
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  public List<Record> all() throws SQLException {
    return query("SELECT * FROM " + Schema.REWRITES + " ORDER BY state, from_uri");
  }

  public List<Record> active() throws SQLException {
    return query("SELECT * FROM " + Schema.REWRITES + " WHERE state = 'active' ORDER BY from_uri");
  }

  public List<Record> proposals() throws SQLException {
    return query("SELECT * FROM " + Schema.REWRITES
        + " WHERE state = 'proposed' ORDER BY created_at DESC");
  }

  public int proposalCount() throws SQLException {
    return count("SELECT COUNT(*) FROM " + Schema.REWRITES + " WHERE state = 'proposed'");
  }

  public int total() throws SQLException {
    return count("SELECT COUNT(*) FROM " + Schema.REWRITES);
  }

  public Record byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.REWRITES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  public Record byFrom(String uri) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.REWRITES + " WHERE from_uri = ?")) {
      statement.setString(1, normalize(uri));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  /** everything pointing at an address, which is what a move has to re-point */
  public List<Record> pointingAt(String uri) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.REWRITES + " WHERE to_uri = ?")) {
      statement.setString(1, normalize(uri));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(read(found));
        }
      }
    }
    return rows;
  }

  // ---- writing -------------------------------------------------------------------------------

  /**
   * Create or replace one, by id.
   *
   * Every write announces itself from here rather than from a handler, for the reason invariant 45
   * gives: a caller can forget, and the event has to be tied to the write actually landing.
   */
  public long save(long id, String fromUri, String toUri, Status status, State state,
                   boolean enabled, String note, Long contentId, Long actor) throws SQLException {
    long saved;
    String from = normalize(fromUri);
    String to = status.needsTarget() ? normalize(toUri) : "";
    try (Connection connection = store.connection()) {
      if (id > 0) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + Schema.REWRITES + " SET from_uri = ?, to_uri = ?, status = ?, state = ?,"
                + " enabled = ?, note = ?, updated_at = ? WHERE id = ?")) {
          statement.setString(1, from);
          statement.setString(2, to);
          statement.setInt(3, status.code);
          statement.setString(4, state.name());
          statement.setBoolean(5, enabled);
          statement.setString(6, cut(note));
          statement.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
          statement.setLong(8, id);
          statement.executeUpdate();
        }
        saved = id;
      } else {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + Schema.REWRITES + " (from_uri, to_uri, status, state, enabled, note,"
                + " content_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS)) {
          statement.setString(1, from);
          statement.setString(2, to);
          statement.setInt(3, status.code);
          statement.setString(4, state.name());
          statement.setBoolean(5, enabled);
          statement.setString(6, cut(note));
          if (contentId == null) {
            statement.setNull(7, java.sql.Types.BIGINT);
          } else {
            statement.setLong(7, contentId);
          }
          if (actor == null) {
            statement.setNull(8, java.sql.Types.BIGINT);
          } else {
            statement.setLong(8, actor);
          }
          statement.executeUpdate();
          try (ResultSet keys = statement.getGeneratedKeys()) {
            saved = keys.next() ? keys.getLong(1) : 0;
          }
        }
      }
    }
    store.changed(Schema.REWRITES, saved,
        id > 0 ? MutationEvent.Kind.update : MutationEvent.Kind.insert, actor);
    return saved;
  }

  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.REWRITES + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.REWRITES, id, MutationEvent.Kind.delete, actor);
  }

  /**
   * Turn a proposal into a redirect, collapsing anything that was pointing at the old address.
   *
   * <b>The collapse is the reason accepting is a method rather than a flag flip.</b> A page that
   * has moved twice leaves `/a -> /b` and `/b -> /c`, and a browser asking for `/a` then pays two
   * round trips to arrive -- with a little ranking lost at each hop and a hard limit of about
   * twenty before it simply gives up. Re-pointing them all at the destination costs one query at
   * the moment somebody presses a button, instead of an extra request for every visitor for ever.
   *
   * Returns how many other rewrites moved with it, because somebody accepting a proposal should be
   * told the blast radius rather than discovering it.
   */
  public int accept(long id, Long actor) throws SQLException {
    Record proposal = byId(id);
    if (proposal == null || proposal.state() != State.proposed) {
      return 0;
    }
    save(id, proposal.fromUri(), proposal.toUri(), proposal.statusOr(), State.active,
        true, proposal.note(), proposal.contentId(), actor);
    return collapseTo(proposal.fromUri(), proposal.toUri(), actor);
  }

  /**
   * Point everything that was aimed at `oldUri` at `newUri` instead.
   *
   * <b>Bounded and loop-aware.</b> Following `to_uri` is following data somebody typed, and a cycle
   * -- `/a -> /b`, `/b -> /a` -- is two forms away. The visited set is what stops this being an
   * infinite loop; the cap is what stops a long chain being a long transaction.
   */
  private int collapseTo(String oldUri, String newUri, Long actor) throws SQLException {
    if (oldUri.equals(newUri) || newUri.isEmpty()) {
      return 0;
    }
    int moved = 0;
    Set<String> seen = new LinkedHashSet<>();
    seen.add(oldUri);
    List<Record> pointing = pointingAt(oldUri);
    for (int hop = 0; hop < MAX_CHAIN && !pointing.isEmpty(); hop++) {
      ArrayList<Record> next = new ArrayList<>();
      for (Record one : pointing) {
        if (one.fromUri().equals(newUri)) {
          // Re-pointing this would make it point at itself, which means the page has come back to
          // an address it already had. Deleted rather than left: the pair `/home -> /house` and
          // `/house -> /home` is a loop a browser follows until it gives up, and leaving one half
          // of it is leaving the loop.
          delete(one.id(), actor);
          continue;
        }
        if (!seen.add(one.fromUri())) {
          // a cycle somebody built by hand; stop walking rather than going round it
          continue;
        }
        save(one.id(), one.fromUri(), newUri, one.statusOr(), one.state(), one.enabled(),
            one.note(), one.contentId(), actor);
        moved++;
        next.addAll(pointingAt(one.fromUri()));
      }
      pointing = next;
    }
    // Anything that now points at itself is deleted rather than kept.
    //
    // It can only arise from a move that came back round to where it started, and what it would do
    // if it fired is send a browser to the address it just asked for -- a loop, and one that a
    // person reading the list would have to work out for themselves.
    removeSelfPointing(actor);
    return moved;
  }

  private void removeSelfPointing(Long actor) throws SQLException {
    ArrayList<Long> doomed = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT id FROM " + Schema.REWRITES + " WHERE from_uri = to_uri");
         ResultSet found = statement.executeQuery()) {
      while (found.next()) {
        doomed.add(found.getLong("id"));
      }
    }
    for (Long id : doomed) {
      delete(id, actor);
    }
  }

  /**
   * Point proposals aimed at an old address at the new one.
   *
   * Proposals only. An active rewrite is what the site does today, and changing that without
   * anybody pressing anything is the thing this whole feature is built to avoid -- those are
   * re-pointed by {@link #accept}, at the moment a person decides.
   */
  private void repointProposals(String oldUri, String newUri, Long actor) throws SQLException {
    if (oldUri.equals(newUri)) {
      return;
    }
    for (Record one : pointingAt(oldUri)) {
      if (one.state() != State.proposed || one.fromUri().equals(newUri)) {
        continue;
      }
      save(one.id(), one.fromUri(), newUri, one.statusOr(), State.proposed, one.enabled(),
          one.note(), one.contentId(), actor);
    }
  }

  /** a proposal somebody does not want; the move still happened, they simply want no redirect */
  public void reject(long id, Long actor) throws SQLException {
    Record proposal = byId(id);
    if (proposal != null && proposal.state() == State.proposed) {
      delete(id, actor);
    }
  }

  // ---- proposing -------------------------------------------------------------------------------

  /**
   * A published page has moved from one address to another: suggest a redirect.
   *
   * <b>Published only, and that is the whole of what "meaningful" means here.</b> A draft's address
   * has never been anywhere -- nobody has linked to it, nothing has indexed it, and proposing a
   * redirect for it produces a queue of suggestions about addresses that were never real. What
   * matters is not how different the two addresses are but whether the old one was ever a promise.
   *
   * <b>Proposed rather than created.</b> A person decides: the software cannot tell a page moving
   * to a better home from somebody correcting a typo they made ten seconds ago, and guessing wrong
   * in the second case leaves a redirect nobody remembers agreeing to.
   *
   * Returns the id, or 0 when there is nothing worth proposing.
   */
  public long proposeMove(long contentId, String oldUri, String newUri, boolean wasPublished,
                          Long actor) throws SQLException {
    if (!wasPublished) {
      return 0;
    }
    String from = normalize(oldUri);
    String to = normalize(newUri);
    if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
      return 0;
    }
    if (total() >= MAX) {
      return 0;
    }
    // Something already answers for this address, so there is nothing to propose.
    //
    // Either somebody has already made this redirect, or the address moved twice and the existing
    // row is about to be collapsed -- and in both cases a second row for the same `from` would be
    // refused by the unique constraint anyway.
    Record existing = byFrom(from);
    long id;
    if (existing != null) {
      // the page moved again before anybody accepted; the proposal that is already sitting there
      // should point at where it has actually ended up rather than at where it paused
      if (existing.state() != State.proposed) {
        return 0;
      }
      save(existing.id(), from, to, existing.statusOr(), State.proposed, existing.enabled(),
          existing.note(), contentId, actor);
      id = existing.id();
    } else {
      id = save(0, from, to, Status.moved, State.proposed, true,
          "proposed when this page moved", contentId, actor);
    }
    // Any *proposal* aimed at the address this page has just left is re-aimed at where it went.
    //
    // A page that moves twice before anybody looks leaves `/a -> /b` waiting and then produces
    // `/b -> /c`. Accepting those in the order they were made would build exactly the chain that
    // accepting is supposed to collapse -- and accepting them in the other order would leave `/a`
    // pointing at an address that is itself only a suggestion. An active rewrite is deliberately
    // left alone: re-pointing one would change what the site does without anybody deciding to.
    repointProposals(from, to, actor);
    return id;
  }

  /**
   * A published page has been deleted: suggest telling crawlers it is gone.
   *
   * 410 rather than a redirect, because there is nowhere honest to send anybody. A crawler treats a
   * 404 as possibly a mistake and comes back for months; 410 says it was deliberate.
   */
  public long proposeGone(long contentId, String uri, boolean wasPublished, Long actor)
      throws SQLException {
    if (!wasPublished) {
      return 0;
    }
    String from = normalize(uri);
    if (from.isEmpty() || byFrom(from) != null || total() >= MAX) {
      return 0;
    }
    return save(0, from, "", Status.gone, State.proposed, true,
        "proposed when this page was deleted", contentId, actor);
  }

  // ---- validation ------------------------------------------------------------------------------

  /**
   * Everything that makes a rewrite storable, in one place so the form and a proposal agree.
   *
   * @param pageExists whether the content table answers for the destination. Not a refusal: a
   *                   redirect to an address that does not exist yet is a perfectly ordinary thing
   *                   to set up before publishing the page, and refusing it would mean doing the
   *                   two in an order the software chose.
   */
  public static String check(String fromUri, String toUri, Status status, boolean pageExists) {
    String from = normalize(fromUri);
    if (from.isEmpty()) {
      return "a rewrite needs an address to answer for";
    }
    String bad = checkUri(from);
    if (bad != null) {
      return "the address it answers for: " + bad;
    }
    if (!status.needsTarget()) {
      return null;
    }
    String to = normalize(toUri);
    if (to.isEmpty()) {
      return "a redirect needs somewhere to send people; use 'gone for good' if there is nowhere";
    }
    // An off-site destination is allowed and an off-site *scheme* is not.
    //
    // Sending somebody to another domain is a real thing to want -- a page that moved to a shop, a
    // docs site somebody else runs. What is refused is anything that is not an http(s) address,
    // because a `javascript:` or `data:` target in a Location header is an open redirect with a
    // payload on the end of it.
    if (to.startsWith("http://") || to.startsWith("https://")) {
      return to.length() > 512 ? "that address is too long" : null;
    }
    String badTo = checkUri(to);
    if (badTo != null) {
      return "where it sends people: " + badTo;
    }
    if (from.equals(to)) {
      return "that sends people to the address they just asked for";
    }
    return null;
  }

  /** the same shape a page's address has to have */
  public static String checkUri(String uri) {
    if (uri == null || uri.isBlank()) {
      return "an address is needed";
    }
    if (!uri.startsWith("/") || uri.length() > 512) {
      return "an absolute path like /about, at most 512 characters";
    }
    if (uri.contains("?") || uri.contains("#")) {
      return "a path: no query string and no fragment";
    }
    // Nothing that would end the Location header early, and nothing that would leave this site.
    //
    // A `//` at the start of a path is protocol-relative and goes to somebody else's server, which
    // is the open redirect this refuses by shape rather than by inspection.
    if (uri.startsWith("//")) {
      return "a path starting // goes to another server; write the whole https:// address instead";
    }
    for (int k = 0; k < uri.length(); k++) {
      char ch = uri.charAt(k);
      if (ch < 0x21 || ch > 0x7E) {
        return "a path this server can put in a header: no spaces, no control characters";
      }
    }
    return null;
  }

  /**
   * Follow a chain to where it actually ends, for the screen that has to say so.
   *
   * Never used to answer a request -- a request follows exactly one hop, which is what the collapse
   * exists to guarantee is enough. This is for the listing, which has to be able to say "this one
   * is a chain" about a row somebody made by hand.
   */
  public String endOf(String fromUri) throws SQLException {
    String at = normalize(fromUri);
    Set<String> seen = new LinkedHashSet<>();
    for (int hop = 0; hop < MAX_CHAIN; hop++) {
      if (!seen.add(at)) {
        return at;
      }
      Record next = byFrom(at);
      if (next == null || !next.fires() || next.isGone() || next.toUri().startsWith("http")) {
        return at;
      }
      at = next.toUri();
    }
    return at;
  }

  public static String normalize(String uri) {
    if (uri == null) {
      return "";
    }
    String clean = uri.trim();
    // A trailing slash is the same address to a person and a different one to a router, and a
    // rewrite exists precisely to catch the address somebody actually typed -- so it is kept as
    // written rather than tidied into something that would not match.
    return clean.length() > 512 ? clean.substring(0, 512) : clean;
  }

  private static String cut(String note) {
    String clean = note == null ? "" : note.replace('\r', ' ').replace('\n', ' ').trim();
    return clean.length() > 512 ? clean.substring(0, 512) : clean;
  }

  private List<Record> query(String sql) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet found = statement.executeQuery()) {
      while (found.next()) {
        rows.add(read(found));
      }
    }
    return rows;
  }

  private int count(String sql) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet found = statement.executeQuery()) {
      return found.next() ? found.getInt(1) : 0;
    }
  }

  private static Record read(ResultSet found) throws SQLException {
    long content = found.getLong("content_id");
    boolean noContent = found.wasNull();
    long by = found.getLong("created_by");
    boolean nobody = found.wasNull();
    return new Record(found.getLong("id"), found.getString("from_uri"), found.getString("to_uri"),
        found.getInt("status"), State.of(found.getString("state")), found.getBoolean("enabled"),
        found.getString("note"), noContent ? null : content, found.getLong("hits"),
        found.getTimestamp("last_used_at"), found.getTimestamp("created_at"),
        found.getTimestamp("updated_at"), nobody ? null : by);
  }
}
