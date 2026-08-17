package io.hearth.content;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * The version history, looking forwards.
 *
 * A proposal is the same canonical document a version is, written by somebody who may not be
 * allowed to publish it, waiting for somebody who is. Storing the whole document rather than a
 * patch is what makes approving one a plain save: the reviewer sees exactly what the page will
 * become, and afterwards the history records it like any other edit -- an approved suggestion is
 * indistinguishable from an edit somebody made directly, which is correct, because it *is* one.
 *
 * The base version is the part that matters. A suggestion is written against the page as it stood;
 * if somebody else edits it in the meantime, applying the suggestion silently reverts their work
 * while looking like it worked. So a stale proposal is *flagged* rather than blocked -- the
 * reviewer is the one who can tell whether the two edits conflict, and refusing outright would mean
 * a busy page could never accept a suggestion at all.
 *
 * Declining keeps the row. Somebody spent time on it, and "no, because" is a thing they should be
 * able to read; deleting it would make the queue a place where work quietly disappears.
 */
public class Proposals {
  private static final String COLUMNS =
      "id, content_id, uri, title, document, base_version, note, state, proposed_by,"
          + " proposed_by_email, created_at, decided_at, decided_by, decided_by_email,"
          + " decision_note";

  private final Store store;
  private final ContentStore content;

  public Proposals(Store store, ContentStore content) {
    this.store = store;
    this.content = content;
  }

  public enum State {
    open, approved, declined, withdrawn;

    public static State of(String raw) {
      if (raw == null) {
        return open;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return open;
      }
    }
  }

  public record Proposal(long id, Long contentId, String uri, String title, String document,
                         int baseVersion, String note, State state, Long proposedBy,
                         String proposedByEmail, Timestamp createdAt, Timestamp decidedAt,
                         Long decidedBy, String decidedByEmail, String decisionNote) {
    public boolean isOpen() {
      return state == State.open;
    }

    public boolean isNewPage() {
      return contentId == null;
    }

    public String who() {
      return proposedByEmail == null || proposedByEmail.isBlank() ? "somebody" : proposedByEmail;
    }
  }

  /** what somebody suggests the page should say */
  public Proposal propose(Long contentId, String uri, String title, String document,
                          int baseVersion, String note, Long by, String byEmail)
      throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.PROPOSALS + " (content_id, uri, title, document,"
                 + " base_version, note, proposed_by, proposed_by_email)"
                 + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      if (contentId == null) {
        statement.setNull(1, java.sql.Types.BIGINT);
      } else {
        statement.setLong(1, contentId);
      }
      statement.setString(2, cap(uri, 512));
      statement.setString(3, cap(title, 256));
      statement.setString(4, document == null ? "" : document);
      statement.setInt(5, baseVersion);
      statement.setString(6, cap(note, 1024));
      if (by == null) {
        statement.setNull(7, java.sql.Types.BIGINT);
      } else {
        statement.setLong(7, by);
      }
      statement.setString(8, cap(byEmail, 320));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.PROPOSALS, id, MutationEvent.Kind.insert, by);
    return byId(id);
  }

  /**
   * Say yes, and make it so.
   *
   * The save goes through {@link ContentStore}, so it emits its event, drops the caches and records
   * a version exactly like a direct edit. The proposal is marked before the save rather than after:
   * a proposal that applied and then failed to be marked would be approved twice by the next
   * reviewer who looked.
   */
  public Proposal approve(long id, Long actor, String actorEmail, String note) throws SQLException {
    Proposal proposal = byId(id);
    if (proposal == null || !proposal.isOpen()) {
      return proposal;
    }
    decide(id, State.approved, actor, actorEmail, note);

    ContentRecord current = proposal.contentId() == null ? null : content.byId(proposal.contentId());
    ContentRecord proposed = ContentVersions.recordFrom(
        current == null ? 0 : current.id(), proposal.document());
    // the uri comes from the page as it stands, for the same reason restoring a version does not
    // move it: the suggestion is about the words
    String uri = current == null ? proposal.uri() : current.uri();
    ContentRecord toSave = new ContentRecord(current == null ? 0 : current.id(), uri,
        proposed.title(), proposed.kind(), proposed.templateName(), proposed.navFolder(),
        proposed.fields(), proposed.body(), proposed.published(), proposed.humanOnly(),
        null, null, actor);
    content.save(toSave, actor, actorEmail);
    return byId(id);
  }

  public void decline(long id, Long actor, String actorEmail, String note) throws SQLException {
    decide(id, State.declined, actor, actorEmail, note);
  }

  /** the person who wrote it changing their mind, which is not the same as being refused */
  public void withdraw(long id, Long actor) throws SQLException {
    decide(id, State.withdrawn, actor, null, "");
  }

  private void decide(long id, State state, Long actor, String actorEmail, String note)
      throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROPOSALS + " SET state = ?, decided_at = CURRENT_TIMESTAMP,"
                 + " decided_by = ?, decided_by_email = ?, decision_note = ?"
                 + " WHERE id = ? AND state = 'open'")) {
      statement.setString(1, state.name());
      if (actor == null) {
        statement.setNull(2, java.sql.Types.BIGINT);
      } else {
        statement.setLong(2, actor);
      }
      statement.setString(3, cap(actorEmail, 320));
      statement.setString(4, cap(note, 1024));
      statement.setLong(5, id);
      statement.executeUpdate();
    }
    store.changed(Schema.PROPOSALS, id, MutationEvent.Kind.update, actor);
  }

  public Proposal byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.PROPOSALS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /** what is waiting, oldest first, because a queue people jump is a queue nobody trusts */
  public List<Proposal> open(int limit) throws SQLException {
    return query("WHERE state = 'open' ORDER BY created_at ASC", limit);
  }

  public List<Proposal> recent(int limit) throws SQLException {
    return query("ORDER BY created_at DESC", limit);
  }

  public List<Proposal> forContent(long contentId, int limit) throws SQLException {
    ArrayList<Proposal> list = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.PROPOSALS + " WHERE content_id = ?"
                 + " ORDER BY created_at DESC " + store.dialect().limit(limit))) {
      statement.setLong(1, contentId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          list.add(read(rows));
        }
      }
    }
    return list;
  }

  public int openCount() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.PROPOSALS + " WHERE state = 'open'")) {
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  /** everything belonging to somebody who is leaving */
  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PROPOSALS + " WHERE proposed_by = ? AND state = 'open'")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  /**
   * Has the page moved since this was written?
   *
   * Not a refusal -- a warning. The reviewer can see both and is the only one who can tell whether
   * the two edits are the same change or opposite ones.
   */
  public boolean isStale(Proposal proposal) throws SQLException {
    if (proposal == null || proposal.contentId() == null) {
      return false;
    }
    return content.versions().latestVersion(proposal.contentId()) > proposal.baseVersion();
  }

  private List<Proposal> query(String tail, int limit) throws SQLException {
    ArrayList<Proposal> list = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.PROPOSALS + " " + tail + " "
                 + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          list.add(read(rows));
        }
      }
    }
    return list;
  }

  private static Proposal read(ResultSet rows) throws SQLException {
    Long contentId = rows.getLong("content_id");
    if (rows.wasNull()) {
      contentId = null;
    }
    Long by = rows.getLong("proposed_by");
    if (rows.wasNull()) {
      by = null;
    }
    Long decidedBy = rows.getLong("decided_by");
    if (rows.wasNull()) {
      decidedBy = null;
    }
    return new Proposal(rows.getLong("id"), contentId, rows.getString("uri"),
        rows.getString("title"), rows.getString("document"), rows.getInt("base_version"),
        rows.getString("note"), State.of(rows.getString("state")), by,
        rows.getString("proposed_by_email"), rows.getTimestamp("created_at"),
        rows.getTimestamp("decided_at"), decidedBy, rows.getString("decided_by_email"),
        rows.getString("decision_note"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
