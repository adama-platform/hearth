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
import java.util.Collection;
import java.util.List;

/**
 * What happened while you were away.
 *
 * Notifications expire like the threads they are about. An inbox that accumulates forever is one
 * nobody opens, and the fiftieth unread badge is worth exactly as much as the first was worth
 * ignoring -- so a notification about a thread that has aged out ages out with it.
 *
 * The text is written when the thing happens rather than rendered on read. A notification about a
 * comment that has since been removed should still say what it said; resolving it later would mean
 * either a blank line in somebody's inbox or a join per row.
 *
 * This is the record of what happened, and the queue of what to send about it -- but it decides
 * neither. `notified_at` is the watermark {@link Notifier} works from; who wants what is {@link
 * NotifyPrefs}. Keeping the three apart means the inbox works whether or not anything is ever sent,
 * which is exactly the state a community running on the terminal mailer is in.
 */
public class Inbox {
  private static final String COLUMNS =
      "id, user_id, kind, post_id, comment_id, subject, actor_name, text, created_at, read_at,"
          + " expires_at, notified_at";

  private final Store store;

  public Inbox(Store store) {
    this.store = store;
  }

  /** why somebody is being told */
  public enum Kind {
    /** somebody replied in a thread they are watching */
    reply,
    /** somebody replied directly to them */
    response,
    /** a new post on a board they follow */
    post
  }

  public record Note(long id, long userId, Kind kind, Long postId, Long commentId, String subject,
                     String actorName, String text, Timestamp createdAt, Timestamp readAt,
                     Timestamp expiresAt, Timestamp notifiedAt) {
    public boolean unread() {
      return readAt == null;
    }

    /** whether anything has been sent about this yet, whatever the outcome was */
    public boolean delivered() {
      return notifiedAt != null;
    }
  }

  /**
   * Tell everybody watching, except the person who did it.
   *
   * Excluding the actor is not a nicety: a board that tells you about your own comment is one whose
   * unread count means nothing within a day.
   */
  public int notifyWatchers(Collection<Long> watchers, long actorId, Kind kind, long postId,
                            Long commentId, String actorName, String text, Timestamp expiresAt)
      throws SQLException {
    int told = 0;
    for (Long userId : watchers) {
      if (userId == null || userId == actorId) {
        continue;
      }
      add(userId, kind, postId, commentId, actorName, text, expiresAt);
      told++;
    }
    return told;
  }

  public void add(long userId, Kind kind, Long postId, Long commentId, String actorName,
                  String text, Timestamp expiresAt) throws SQLException {
    add(userId, kind, postId, commentId, "", actorName, text, expiresAt);
  }

  public void add(long userId, Kind kind, Long postId, Long commentId, String subject,
                  String actorName, String text, Timestamp expiresAt) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.NOTIFICATIONS + " (user_id, kind, post_id, comment_id,"
                 + " subject, actor_name, text, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setLong(1, userId);
      statement.setString(2, kind.name());
      setLongOrNull(statement, 3, postId);
      setLongOrNull(statement, 4, commentId);
      statement.setString(5, cap(subject, 80));
      statement.setString(6, cap(actorName, 320));
      statement.setString(7, cap(text, 512));
      if (expiresAt == null) {
        statement.setNull(8, java.sql.Types.TIMESTAMP);
      } else {
        statement.setTimestamp(8, expiresAt);
      }
      statement.executeUpdate();
    }
    store.changed(Schema.NOTIFICATIONS, userId, MutationEvent.Kind.insert, null);
  }

  /**
   * Is there already an unread note of this kind about this thing?
   *
   * The coalescing rule for chat: one row per person per room until they have looked. Without it,
   * an evening in a busy channel writes a notification per member per message, and the fiftieth is
   * worth exactly what the first was worth ignoring.
   */
  public boolean hasUnread(long userId, Kind kind, String subject) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT 1 FROM " + Schema.NOTIFICATIONS + " WHERE user_id = ? AND kind = ?"
                 + " AND subject = ? AND read_at IS NULL"
                 + " AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")) {
      statement.setLong(1, userId);
      statement.setString(2, kind.name());
      statement.setString(3, subject);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  /**
   * Clear every unread note about one room.
   *
   * This is what "the person cleared the bell" means, and it is what stops the fuse: the delivery
   * pass skips anything already read, so opening the room within five minutes is the difference
   * between a notification and none.
   */
  public int markReadFor(long userId, Kind kind, String subject) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.NOTIFICATIONS + " SET read_at = CURRENT_TIMESTAMP"
                 + " WHERE user_id = ? AND kind = ? AND subject = ? AND read_at IS NULL")) {
      statement.setLong(1, userId);
      statement.setString(2, kind.name());
      statement.setString(3, subject);
      int marked = statement.executeUpdate();
      if (marked > 0) {
        store.changed(Schema.NOTIFICATIONS, userId, MutationEvent.Kind.update, userId);
      }
      return marked;
    }
  }

  /** somebody's inbox, newest first, with anything aged out already gone */
  public List<Note> forUser(long userId, int limit) throws SQLException {
    ArrayList<Note> notes = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.NOTIFICATIONS
                 + " WHERE user_id = ? AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)"
                 + " ORDER BY created_at DESC " + store.dialect().limit(limit))) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          notes.add(read(rows));
        }
      }
    }
    return notes;
  }

  /** the number on the badge */
  public int unreadCount(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.NOTIFICATIONS + " WHERE user_id = ?"
                 + " AND read_at IS NULL"
                 + " AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  /**
   * Mark everything read.
   *
   * All at once rather than per notification, because that is what opening an inbox means. A
   * per-item read state would be a lot of machinery for a distinction nobody makes.
   */
  public int markAllRead(long userId) throws SQLException {
    int marked;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.NOTIFICATIONS + " SET read_at = CURRENT_TIMESTAMP"
                 + " WHERE user_id = ? AND read_at IS NULL")) {
      statement.setLong(1, userId);
      marked = statement.executeUpdate();
    }
    if (marked > 0) {
      store.changed(Schema.NOTIFICATIONS, userId, MutationEvent.Kind.update, userId);
    }
    return marked;
  }

  /**
   * What nothing has been sent about yet, oldest first.
   *
   * This is the delivery queue, and it is a query rather than a queue on purpose: a restart loses
   * an in-memory queue and duplicates a durable one, whereas "the rows with no notified_at" is
   * true whenever it is asked. Expired notifications are excluded -- mailing somebody about a
   * thread that has already aged out is worse than saying nothing.
   */
  public List<Note> undelivered(int limit) throws SQLException {
    ArrayList<Note> notes = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.NOTIFICATIONS + " WHERE notified_at IS NULL"
                 + " AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)"
                 + " ORDER BY created_at ASC " + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          notes.add(read(rows));
        }
      }
    }
    return notes;
  }

  /**
   * Stamp rows as handled.
   *
   * "Handled" rather than "sent": a notification belonging to somebody who wants no email is
   * stamped too. Otherwise the queue grows forever with rows nothing will ever do anything about,
   * and every pass through it costs more than the last.
   */
  public int markNotified(Collection<Long> ids) throws SQLException {
    if (ids.isEmpty()) {
      return 0;
    }
    int marked = 0;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.NOTIFICATIONS + " SET notified_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ? AND notified_at IS NULL")) {
      for (Long id : ids) {
        if (id == null) {
          continue;
        }
        statement.setLong(1, id);
        marked += statement.executeUpdate();
      }
    }
    return marked;
  }

  /** drop what has aged out; called by the same sweep that reaps sessions */
  public int sweep() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.NOTIFICATIONS
                 + " WHERE expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP")) {
      return statement.executeUpdate();
    }
  }

  /** everything belonging to somebody who is leaving */
  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.NOTIFICATIONS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  private static void setLongOrNull(PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, java.sql.Types.BIGINT);
    } else {
      statement.setLong(index, value);
    }
  }

  private static Note read(ResultSet rows) throws SQLException {
    Long postId = rows.getLong("post_id");
    if (rows.wasNull()) {
      postId = null;
    }
    Long commentId = rows.getLong("comment_id");
    if (rows.wasNull()) {
      commentId = null;
    }
    Kind kind;
    try {
      kind = Kind.valueOf(rows.getString("kind"));
    } catch (IllegalArgumentException ex) {
      kind = Kind.reply;
    }
    return new Note(rows.getLong("id"), rows.getLong("user_id"), kind, postId, commentId,
        rows.getString("subject"),
        rows.getString("actor_name"), rows.getString("text"), rows.getTimestamp("created_at"),
        rows.getTimestamp("read_at"), rows.getTimestamp("expires_at"),
        rows.getTimestamp("notified_at"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
