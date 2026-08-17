package io.hearth.people;

import io.hearth.auth.Tokens;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Invitations, and whether they worked.
 *
 * The number worth having is not how many were sent. It is how many became somebody who signed in,
 * and -- for the ones that did not -- whether the message was even opened, because "never opened"
 * and "opened and ignored" are different problems with different fixes. So an invitation carries a
 * funnel: sent, opened, converted.
 *
 * **What the open tracking is and is not.** A one pixel image with a token in its path, fetched when
 * a mail client loads remote images. That makes it as good as most read receipts and no better:
 * clients that block remote images by default -- which is most of them now -- will never report an
 * open for a message somebody read carefully. So an unopened invitation means "no evidence", not
 * "unread", and the admin screen says exactly that rather than implying a number it does not have.
 */
public class Invites {
  private static final String COLUMNS =
      "id, email, token, note, created_at, created_by, created_by_email, created_by_name,"
          + " sent_at, send_detail,"
          + " opened_at, last_opened_at, opens, clicked_at, last_clicked_at, clicks,"
          + " converted_at, converted_user, revoked_at,"
          + " touches, last_touch_at, next_touch_at";

  private final Store store;

  public Invites(Store store) {
    this.store = store;
  }

  /** one invitation and everything that happened to it */
  public record Invite(long id, String email, String token, String note, Timestamp createdAt,
                       Long createdBy, String createdByEmail, String createdByName,
                       Timestamp sentAt, String sendDetail,
                       Timestamp openedAt, Timestamp lastOpenedAt, int opens,
                       Timestamp clickedAt, Timestamp lastClickedAt, int clicks,
                       Timestamp convertedAt, Long convertedUser, Timestamp revokedAt,
                       int touches, Timestamp lastTouchAt, Timestamp nextTouchAt) {
    public boolean sent() {
      return sentAt != null;
    }

    public boolean opened() {
      return openedAt != null;
    }

    /** somebody followed the link -- a person, rather than a mail client fetching an image */
    public boolean clicked() {
      return clickedAt != null;
    }

    public boolean converted() {
      return convertedAt != null;
    }

    public boolean revoked() {
      return revokedAt != null;
    }

    /** still worth another message: sent, not opened into an account, not called off */
    public boolean outstanding() {
      return sent() && !converted() && !revoked();
    }

    /** where this one got to, as one word for a listing */
    public String stage() {
      if (revoked()) {
        return "revoked";
      }
      if (converted()) {
        return "joined";
      }
      if (clicked()) {
        return "clicked";
      }
      if (opened()) {
        return "opened";
      }
      return sent() ? "sent" : "not sent";
    }
  }

  /** the funnel, for the top of the screen */
  public record Funnel(int total, int sent, int opened, int clicked, int converted, int revoked) {
    public int openRate() {
      return sent == 0 ? 0 : opened * 100 / sent;
    }

    /** the honest one of the three rates: a machine cannot click a link on somebody's behalf */
    public int clickRate() {
      return sent == 0 ? 0 : clicked * 100 / sent;
    }

    public int conversionRate() {
      return sent == 0 ? 0 : converted * 100 / sent;
    }

    /** the ones still worth chasing: sent, not revoked, not joined */
    public int outstanding() {
      return sent - converted - revoked;
    }
  }

  // ---- writing --------------------------------------------------------------------------------

  /**
   * Write an invitation for an address.
   *
   * The token is a session-strength random string because it appears in a URL somebody may forward
   * -- it identifies the invitation to the pixel and to the conversion check, so guessing one would
   * let somebody mark another person's invitation as opened.
   */
  public Invite create(String normalizedEmail, String note, Long actor, String actorEmail)
      throws SQLException {
    return create(normalizedEmail, note, actor, actorEmail, "");
  }

  /**
   * @param actorName what the message will call them. Written down here rather than joined at send
   *     time, because the name in an invitation should be the one they had when they sent it -- a
   *     rename afterwards must not rewrite a message that has already gone out.
   */
  public Invite create(String normalizedEmail, String note, Long actor, String actorEmail,
                       String actorName) throws SQLException {
    String token = Tokens.newSessionToken();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.INVITES + " (email, token, note, created_by, created_by_email,"
                 + " created_by_name) VALUES (?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, normalizedEmail);
      statement.setString(2, token);
      statement.setString(3, cap(note, 512));
      if (actor == null) {
        statement.setNull(4, java.sql.Types.BIGINT);
      } else {
        statement.setLong(4, actor);
      }
      statement.setString(5, cap(actorEmail == null ? "" : actorEmail, 320));
      statement.setString(6, cap(actorName == null ? "" : actorName, 128));
      statement.executeUpdate();
    }
    Invite invite = byToken(token);
    store.changed(Schema.INVITES, invite.id(), MutationEvent.Kind.insert, actor);
    return invite;
  }

  /**
   * Record that a message actually went out, and when the next one is due.
   *
   * The count and the due date live on the row rather than being derived from `sent_at` and a
   * cadence, because the cadence is configurable: changing it must not retroactively decide that
   * somebody was owed a reminder last Tuesday and send it immediately. `next_touch_at` is the whole
   * schedule, written at the moment the previous message left.
   */
  public void markTouched(long id, String detail, java.time.LocalDate today, int nextInDays)
      throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET sent_at = COALESCE(sent_at, CURRENT_TIMESTAMP),"
                 + " send_detail = ?, touches = touches + 1,"
                 + " last_touch_at = CURRENT_TIMESTAMP, next_touch_at = ? WHERE id = ?")) {
      statement.setString(1, detail == null ? "" : detail);
      if (nextInDays <= 0) {
        statement.setNull(2, java.sql.Types.TIMESTAMP);
      } else {
        statement.setTimestamp(2,
            Timestamp.valueOf(today.plusDays(nextInDays).atTime(9, 0)));
      }
      statement.setLong(3, id);
      statement.executeUpdate();
    }
    store.changed(Schema.INVITES, id, MutationEvent.Kind.update, null);
  }

  /**
   * Invitations owed another message.
   *
   * Sent, not yet a member, not called off, and past their due date. Converted and revoked ones are
   * excluded in the query rather than filtered afterwards, because the one thing this must never do
   * is send a reminder to somebody who already joined.
   */
  public List<Invite> due(Timestamp now, int limit) throws SQLException {
    ArrayList<Invite> list = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.INVITES + " WHERE next_touch_at IS NOT NULL"
                 + " AND next_touch_at <= ? AND converted_at IS NULL AND revoked_at IS NULL"
                 + " AND sent_at IS NOT NULL ORDER BY next_touch_at ASC "
                 + store.dialect().limit(limit))) {
      statement.setTimestamp(1, now);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          list.add(read(rows));
        }
      }
    }
    return list;
  }

  /** stop the sequence without revoking the invitation itself */
  public void stopReminders(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET next_touch_at = NULL WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  /** how many one person has written today, so one enthusiast cannot burn the domain */
  public int sentTodayBy(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.INVITES + " WHERE created_by = ?"
                 + " AND created_at >= ?")) {
      statement.setLong(1, userId);
      statement.setTimestamp(2,
          Timestamp.valueOf(java.time.LocalDate.now().atStartOfDay()));
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  /** record that the message actually went out, and what the mailer said */
  public void markSent(long id, String detail, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET sent_at = CURRENT_TIMESTAMP, send_detail = ?"
                 + " WHERE id = ?")) {
      statement.setString(1, cap(detail, 512));
      statement.setLong(2, id);
      statement.executeUpdate();
    }
    store.changed(Schema.INVITES, id, MutationEvent.Kind.update, actor);
  }

  /**
   * The pixel was fetched.
   *
   * First and last are both kept: the first says the message was opened at all, the last says
   * whether anybody has looked at it since. The count is incremented rather than set, so a person
   * scrolling past the message ten times is visible as ten rather than as one.
   *
   * Deliberately emits no mutation event. A pixel is fetched by mail clients at times nobody
   * controls, and an event per fetch would be a stream of noise on the bus for something no cache
   * depends on.
   */
  /**
   * The link was followed.
   *
   * The counterpart to the pixel, and the more trustworthy half of the pair. An open can be Apple
   * Mail fetching images before anybody has looked at anything; a click is somebody deciding to
   * find out more, which is the last thing that happens before a sign-up form either works or does
   * not. Recorded on the register page rather than on a redirect of our own, because a link that
   * bounced through a tracker would be one more thing between a person and the form.
   *
   * Eventless for the same reason as an open: nothing caches this, and a write per fetch would be
   * noise on the bus. Counted rather than set, so somebody coming back three times is visible.
   */
  public void markClicked(String token) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET clicks = clicks + 1,"
                 + " clicked_at = COALESCE(clicked_at, CURRENT_TIMESTAMP),"
                 + " last_clicked_at = CURRENT_TIMESTAMP WHERE token = ?")) {
      statement.setString(1, token);
      statement.executeUpdate();
    }
  }

  public void markOpened(String token) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET opens = opens + 1,"
                 + " opened_at = COALESCE(opened_at, CURRENT_TIMESTAMP),"
                 + " last_opened_at = CURRENT_TIMESTAMP WHERE token = ?")) {
      statement.setString(1, token);
      statement.executeUpdate();
    }
  }

  /**
   * Somebody the invitation was for made an account.
   *
   * Matched by address, and only the oldest outstanding invitation for it is claimed -- if an
   * address was invited three times, one invitation converted and the others did not, which is the
   * honest reading and keeps the rate from being inflated by resends.
   */
  public Invite convert(String normalizedEmail, long userId) throws SQLException {
    Invite pending = oldestOutstandingFor(normalizedEmail);
    if (pending == null) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET converted_at = CURRENT_TIMESTAMP,"
                 + " converted_user = ? WHERE id = ?")) {
      statement.setLong(1, userId);
      statement.setLong(2, pending.id());
      statement.executeUpdate();
    }
    store.changed(Schema.INVITES, pending.id(), MutationEvent.Kind.update, userId);
    return byId(pending.id());
  }

  public void revoke(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.INVITES + " SET revoked_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ? AND converted_at IS NULL")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.INVITES, id, MutationEvent.Kind.update, actor);
  }

  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.INVITES + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.INVITES, id, MutationEvent.Kind.delete, actor);
  }

  // ---- reading --------------------------------------------------------------------------------

  public Invite byToken(String token) throws SQLException {
    return one("token = ?", statement -> statement.setString(1, token));
  }

  public Invite byId(long id) throws SQLException {
    return one("id = ?", statement -> statement.setLong(1, id));
  }

  /** the invitation that brought somebody in, or null */
  public Invite forUser(long userId) throws SQLException {
    return one("converted_user = ?", statement -> statement.setLong(1, userId));
  }

  private Invite oldestOutstandingFor(String email) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.INVITES
                 + " WHERE email = ? AND converted_at IS NULL AND revoked_at IS NULL"
                 + " ORDER BY created_at " + store.dialect().limit(1))) {
      statement.setString(1, email);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public List<Invite> all(int limit) throws SQLException {
    ArrayList<Invite> invites = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.INVITES
                 + " ORDER BY created_at DESC " + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          invites.add(read(rows));
        }
      }
    }
    return invites;
  }

  /** the counts the screen leads with */
  public Funnel funnel() throws SQLException {
    int total = 0;
    int sent = 0;
    int opened = 0;
    int clicked = 0;
    int converted = 0;
    int revoked = 0;
    for (Invite invite : all(5000)) {
      total++;
      if (invite.sent()) {
        sent++;
      }
      if (invite.opened()) {
        opened++;
      }
      if (invite.clicked()) {
        clicked++;
      }
      if (invite.converted()) {
        converted++;
      }
      if (invite.revoked()) {
        revoked++;
      }
    }
    return new Funnel(total, sent, opened, clicked, converted, revoked);
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.INVITES);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private Invite one(String where, Binder binder) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.INVITES + " WHERE " + where)) {
      binder.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  private static Invite read(ResultSet rows) throws SQLException {
    Long createdBy = rows.getLong("created_by");
    if (rows.wasNull()) {
      createdBy = null;
    }
    Long convertedUser = rows.getLong("converted_user");
    if (rows.wasNull()) {
      convertedUser = null;
    }
    return new Invite(rows.getLong("id"), rows.getString("email"), rows.getString("token"),
        rows.getString("note"), rows.getTimestamp("created_at"), createdBy,
        rows.getString("created_by_email"), rows.getString("created_by_name"),
        rows.getTimestamp("sent_at"),
        rows.getString("send_detail"), rows.getTimestamp("opened_at"),
        rows.getTimestamp("last_opened_at"), rows.getInt("opens"),
        rows.getTimestamp("clicked_at"), rows.getTimestamp("last_clicked_at"),
        rows.getInt("clicks"),
        rows.getTimestamp("converted_at"), convertedUser, rows.getTimestamp("revoked_at"),
        rows.getInt("touches"), rows.getTimestamp("last_touch_at"),
        rows.getTimestamp("next_touch_at"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
