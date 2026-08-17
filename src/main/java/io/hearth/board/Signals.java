package io.hearth.board;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What people think of something, and what they want somebody to look at.
 *
 * Two jobs that share a shape, and are worth keeping apart in the head:
 *
 * <b>A vote is an opinion about a thing.</b> Up and down, one per person, changeable. Its whole
 * purpose is to be a signal the community produces without anybody being asked -- a thread with nine
 * ups is worth reading and a comment with six downs is usually somebody having a bad day at
 * somebody else. Neither number decides anything: nothing is hidden, sorted or removed by score.
 * A community where votes bury things is a community that has handed its judgement to whoever votes
 * most, and this product is one where a person decides.
 *
 * <b>A flag is a request for a person.</b> "Somebody should look at this", with a reason, and it
 * goes into a queue rather than doing anything. That is the whole of "content triage": the software
 * gathers the signal and a human keeps the judgement, which is the only division of labour that
 * works for the thing this feature exists for -- the message that traumatises whoever reads it
 * cold.
 *
 * <b>Attributed, never anonymous.</b> Every row knows who cast it. That is what lets somebody change
 * their mind, and it is what makes a flag answerable: four flags on one comment is a different fact
 * depending on whether it is four people or one person with three tabs open. It is shown to
 * moderators and to nobody else.
 */
public class Signals {
  private static final String COLUMNS =
      "id, subject_kind, subject_id, user_id, kind, reason, cleared_at, cleared_by, created_at";

  private final Store store;

  public Signals(Store store) {
    this.store = store;
  }

  /** what somebody said about something */
  public enum Kind {
    up, down, flag;

    public static Kind of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }

  /** one opinion */
  public record Signal(long id, Subject subject, long userId, Kind kind, String reason,
                       Timestamp clearedAt, Long clearedBy, Timestamp createdAt) {
    public boolean open() {
      return clearedAt == null;
    }
  }

  /** the counts a page needs, and what this reader has already said */
  public record Tally(int up, int down, int flags, Kind mine) {
    public static final Tally NONE = new Tally(0, 0, 0, null);

    public int score() {
      return up - down;
    }

    public boolean iVotedUp() {
      return mine == Kind.up;
    }

    public boolean iVotedDown() {
      return mine == Kind.down;
    }
  }

  /**
   * Cast, change, or take back.
   *
   * Pressing up twice takes the vote back, which is what every product that has one does and what
   * everybody expects; pressing down after up replaces it, because nobody holds both opinions. A
   * flag is separate from a vote and can sit alongside one: disliking something and thinking
   * somebody should look at it are different statements.
   *
   * @return what the person now holds for that subject, or null if they took it back
   */
  public Kind cast(Subject subject, long userId, Kind kind, String reason) throws SQLException {
    if (kind == null) {
      return null;
    }
    Kind existing = mine(subject, userId);
    boolean sameVote = kind != Kind.flag && existing == kind;
    if (sameVote || kind == Kind.flag && held(subject, userId, Kind.flag)) {
      remove(subject, userId, kind);
      store.changed(Schema.SIGNALS, subject.id(), MutationEvent.Kind.delete, userId);
      return kind == Kind.flag ? mine(subject, userId) : null;
    }
    if (kind != Kind.flag && existing != null) {
      // one opinion at a time: swapping is a delete and an insert rather than an update, because
      // the unique key is over the kind and an update would collide with itself
      remove(subject, userId, existing);
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.SIGNALS + " (subject_kind, subject_id, user_id, kind, reason)"
                 + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      statement.setLong(3, userId);
      statement.setString(4, kind.name());
      statement.setString(5, reason == null ? "" : reason.trim());
      statement.executeUpdate();
    }
    store.changed(Schema.SIGNALS, subject.id(), MutationEvent.Kind.insert, userId);
    return kind;
  }

  private void remove(Subject subject, long userId, Kind kind) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SIGNALS + " WHERE subject_kind = ? AND subject_id = ?"
                 + " AND user_id = ? AND kind = ?")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      statement.setLong(3, userId);
      statement.setString(4, kind.name());
      statement.executeUpdate();
    }
  }

  private boolean held(Subject subject, long userId, Kind kind) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.SIGNALS + " WHERE subject_kind = ?"
                 + " AND subject_id = ? AND user_id = ? AND kind = ?")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      statement.setLong(3, userId);
      statement.setString(4, kind.name());
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1) > 0;
      }
    }
  }

  /** which way this person voted, ignoring any flag they also raised */
  public Kind mine(Subject subject, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT kind FROM " + Schema.SIGNALS + " WHERE subject_kind = ? AND subject_id = ?"
                 + " AND user_id = ? AND kind <> 'flag'")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      statement.setLong(3, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Kind.of(rows.getString(1)) : null;
      }
    }
  }

  /** the counts for one thing, and what this reader said about it */
  public Tally tally(Subject subject, long userId) throws SQLException {
    return tallies(subject.kind(), List.of(subject.id()), userId)
        .getOrDefault(subject.id(), Tally.NONE);
  }

  /**
   * The counts for a page of things, in one query.
   *
   * A feed of sixty posts asking for its own tally sixty times is the shape that makes a board
   * slower exactly as a community gets busier, which is the thing the board's caching exists to
   * avoid. One grouped query and a map is what a listing wants.
   */
  public Map<Long, Tally> tallies(Subject.Kind kind, List<Long> ids, long userId)
      throws SQLException {
    LinkedHashMap<Long, Tally> out = new LinkedHashMap<>();
    if (ids == null || ids.isEmpty()) {
      return out;
    }
    LinkedHashMap<Long, int[]> counts = new LinkedHashMap<>();
    LinkedHashMap<Long, Kind> mine = new LinkedHashMap<>();
    String places = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT subject_id, kind, user_id FROM " + Schema.SIGNALS
                 + " WHERE subject_kind = ? AND subject_id IN (" + places + ")")) {
      statement.setString(1, kind.name());
      for (int k = 0; k < ids.size(); k++) {
        statement.setLong(k + 2, ids.get(k));
      }
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          long id = rows.getLong("subject_id");
          Kind each = Kind.of(rows.getString("kind"));
          int[] tally = counts.computeIfAbsent(id, key -> new int[3]);
          if (each == Kind.up) {
            tally[0]++;
          } else if (each == Kind.down) {
            tally[1]++;
          } else if (each == Kind.flag) {
            tally[2]++;
          }
          if (rows.getLong("user_id") == userId && each != Kind.flag) {
            mine.put(id, each);
          }
        }
      }
    }
    for (long id : ids) {
      int[] tally = counts.getOrDefault(id, new int[3]);
      out.put(id, new Tally(tally[0], tally[1], tally[2], mine.get(id)));
    }
    return out;
  }

  /** everything somebody has asked a person to look at, oldest first */
  public List<Signal> openFlags(int limit) throws SQLException {
    ArrayList<Signal> out = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.SIGNALS
                 + " WHERE kind = 'flag' AND cleared_at IS NULL ORDER BY created_at ASC "
                 + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          out.add(read(rows));
        }
      }
    }
    return out;
  }

  public int openFlagCount() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.SIGNALS
                 + " WHERE kind = 'flag' AND cleared_at IS NULL");
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /** every flag on one thing, so a moderator sees whether it is four people or one */
  public List<Signal> flagsOn(Subject subject) throws SQLException {
    ArrayList<Signal> out = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.SIGNALS + " WHERE subject_kind = ?"
                 + " AND subject_id = ? AND kind = 'flag' ORDER BY created_at")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          out.add(read(rows));
        }
      }
    }
    return out;
  }

  /**
   * Somebody looked at it.
   *
   * The rows stay. What was reported, by how many people, and that a moderator decided it was
   * fine is the record worth having -- deleting it means the next flag on the same comment arrives
   * with no history behind it.
   */
  public int clear(Subject subject, long actor) throws SQLException {
    int cleared;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.SIGNALS + " SET cleared_at = CURRENT_TIMESTAMP, cleared_by = ?"
                 + " WHERE subject_kind = ? AND subject_id = ? AND kind = 'flag'"
                 + " AND cleared_at IS NULL")) {
      statement.setLong(1, actor);
      statement.setString(2, subject.kind().name());
      statement.setLong(3, subject.id());
      cleared = statement.executeUpdate();
    }
    store.changed(Schema.SIGNALS, subject.id(), MutationEvent.Kind.update, actor);
    return cleared;
  }

  /** everything about one thing goes when the thing does */
  public void forgetSubject(Subject subject) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SIGNALS + " WHERE subject_kind = ? AND subject_id = ?")) {
      statement.setString(1, subject.kind().name());
      statement.setLong(2, subject.id());
      statement.executeUpdate();
    }
  }

  /** and everything one person ever said, when they are erased */
  public int forgetUser(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SIGNALS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      return statement.executeUpdate();
    }
  }

  private static Signal read(ResultSet rows) throws SQLException {
    Long clearedBy = rows.getLong("cleared_by");
    if (rows.wasNull()) {
      clearedBy = null;
    }
    return new Signal(rows.getLong("id"),
        new Subject(Subject.Kind.of(rows.getString("subject_kind")), rows.getLong("subject_id")),
        rows.getLong("user_id"), Kind.of(rows.getString("kind")), rows.getString("reason"),
        rows.getTimestamp("cleared_at"), clearedBy, rows.getTimestamp("created_at"));
  }
}
