package io.hearth.board;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * How somebody wants to hear about the board.
 *
 * Two settings, because there are two different events and one answer cannot be right for both. A
 * reply aimed at you is a conversation waiting on an answer and defaults to arriving straight away;
 * activity in a thread you happen to be watching is news, and defaults to a daily summary. Somebody
 * who wants everything immediately says so, and somebody who wants none of it says that.
 *
 * A person with no row has {@link #DEFAULTS}. Writing a row for every account at sign-up would put
 * a table full of defaults on disk that nobody has ever read, and would mean the defaults could
 * never be changed for existing members without a migration.
 *
 * The digest watermarks live here rather than being derived from the notifications themselves. A
 * "have I sent today's summary" computed from row timestamps is a question whose answer changes
 * when rows expire, and the failure mode is sending the same summary twice.
 */
public class NotifyPrefs {
  private static final String COLUMNS =
      "id, user_id, reply_mode, response_mode, email, sms, phone, last_daily_at, last_weekly_at,"
          + " updated_at";

  /** what somebody gets before they have ever opened the settings */
  public static final Prefs DEFAULTS =
      new Prefs(0, Mode.daily, Mode.immediate, true, false, null, null, null);

  private final Store store;

  public NotifyPrefs(Store store) {
    this.store = store;
  }

  /** how soon, if at all */
  public enum Mode {
    /** never leaves the inbox */
    off,
    /** as soon as the notifier next runs, which is within a minute */
    immediate,
    /** gathered into one message a day */
    daily,
    /** gathered into one message a week */
    weekly;

    public static Mode of(String raw, Mode fallback) {
      if (raw == null) {
        return fallback;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        // an unreadable value is somebody's row, not a reason to fail a page load
        return fallback;
      }
    }

    public boolean digested() {
      return this == daily || this == weekly;
    }
  }

  public record Prefs(long userId, Mode replyMode, Mode responseMode, boolean email, boolean sms,
                      String phone, Timestamp lastDailyAt, Timestamp lastWeeklyAt) {
    /** the mode that governs one notification, which is the whole point of having two of them */
    public Mode modeFor(Inbox.Kind kind) {
      return kind == Inbox.Kind.response ? responseMode : replyMode;
    }

    /** whether anything at all is meant to leave the inbox */
    public boolean anyDelivery() {
      return email && (replyMode != Mode.off || responseMode != Mode.off);
    }

    public boolean wants(Mode mode) {
      return replyMode == mode || responseMode == mode;
    }
  }

  /** what to apply to somebody, whether or not they have ever saved anything */
  public Prefs forUser(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.NOTIFY_PREFS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          return read(rows);
        }
      }
    }
    return withUser(DEFAULTS, userId);
  }

  /**
   * Save what somebody chose.
   *
   * An upsert rather than an insert-then-update, because the row may or may not exist and the
   * difference is not something the settings page should have to know.
   */
  public void save(long userId, Mode replyMode, Mode responseMode, boolean email, boolean sms,
                   String phone) throws SQLException {
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.NOTIFY_PREFS + " SET reply_mode = ?, response_mode = ?, email = ?,"
                 + " sms = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?")) {
      statement.setString(1, replyMode.name());
      statement.setString(2, responseMode.name());
      statement.setBoolean(3, email);
      statement.setBoolean(4, sms);
      statement.setString(5, cap(phone));
      statement.setLong(6, userId);
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.NOTIFY_PREFS + " (user_id, reply_mode, response_mode, email,"
                   + " sms, phone) VALUES (?, ?, ?, ?, ?, ?)")) {
        statement.setLong(1, userId);
        statement.setString(2, replyMode.name());
        statement.setString(3, responseMode.name());
        statement.setBoolean(4, email);
        statement.setBoolean(5, sms);
        statement.setString(6, cap(phone));
        statement.executeUpdate();
      }
    }
    store.changed(Schema.NOTIFY_PREFS, userId, MutationEvent.Kind.update, userId);
  }

  /**
   * Stamp a digest as sent.
   *
   * Written before anything is mailed rather than after. Sending twice is somebody's inbox with two
   * copies of the same summary; not sending is one missed day, and the next run picks up everything
   * that is still unsent anyway.
   */
  public void markDigested(long userId, Mode mode, Timestamp when) throws SQLException {
    String column = mode == Mode.weekly ? "last_weekly_at" : "last_daily_at";
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.NOTIFY_PREFS + " SET " + column + " = ? WHERE user_id = ?")) {
      statement.setTimestamp(1, when);
      statement.setLong(2, userId);
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      // somebody on the defaults, which include a daily digest, has no row until now
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.NOTIFY_PREFS + " (user_id, reply_mode, response_mode, email,"
                   + " sms, " + column + ") VALUES (?, ?, ?, ?, ?, ?)")) {
        statement.setLong(1, userId);
        statement.setString(2, DEFAULTS.replyMode().name());
        statement.setString(3, DEFAULTS.responseMode().name());
        statement.setBoolean(4, DEFAULTS.email());
        statement.setBoolean(5, DEFAULTS.sms());
        statement.setTimestamp(6, when);
        statement.executeUpdate();
      }
    }
  }

  /** everything belonging to somebody who is leaving */
  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.NOTIFY_PREFS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  private static Prefs withUser(Prefs prefs, long userId) {
    return new Prefs(userId, prefs.replyMode(), prefs.responseMode(), prefs.email(), prefs.sms(),
        prefs.phone(), prefs.lastDailyAt(), prefs.lastWeeklyAt());
  }

  private static Prefs read(ResultSet rows) throws SQLException {
    return new Prefs(rows.getLong("user_id"),
        Mode.of(rows.getString("reply_mode"), DEFAULTS.replyMode()),
        Mode.of(rows.getString("response_mode"), DEFAULTS.responseMode()),
        rows.getBoolean("email"), rows.getBoolean("sms"), rows.getString("phone"),
        rows.getTimestamp("last_daily_at"), rows.getTimestamp("last_weekly_at"));
  }

  private static String cap(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32);
  }
}
