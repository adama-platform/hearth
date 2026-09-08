package io.hearth.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * One person's calendar: what is in it, and the URL a phone subscribes to.
 *
 * <b>Keyed on the iCalendar UID, and that is what makes an update an update.</b> An organizer who
 * moves a meeting sends the same UID with a higher SEQUENCE; a calendar that matched on anything
 * else -- a summary, a start time, an id of its own -- would show the old time and the new one side
 * by side, which is the failure that makes people stop trusting a calendar entirely. The pair
 * (person, uid) is unique in the schema for exactly that reason.
 *
 * <b>A lower sequence arriving later is ignored.</b> Mail is not ordered: a REQUEST and its
 * correction can arrive in either order, and taking the most recent delivery as the truth means a
 * message delayed twenty minutes silently undoes a change everybody has already seen.
 *
 * <b>The feed token is stored as a hash</b>, like a session token and for the same reason: the URL
 * goes into a phone's settings and stays there for years, so a stolen database file must not be a
 * list of working calendar subscriptions.
 */
public class Events {
  private static final ObjectMapper JSON = new ObjectMapper();
  /** how far a feed reaches back; a phone does not need a meeting from 2019 */
  public static final int FEED_DAYS_BACK = 90;
  /** and forward, which has to cover a repeat that runs for a year */
  public static final int FEED_DAYS_FORWARD = 400;

  private final Store store;

  public Events(Store store) {
    this.store = store;
  }

  public record Record(long id, long userId, String uid, int sequence, String summary,
                       String description, String location, Timestamp startsAt, Timestamp endsAt,
                       boolean allDay, String rrule, String exdates, String status,
                       String organizer, String attendeesJson, String myAnswer, String source,
                       Long fromMessage, Timestamp updatedAt) {

    public boolean cancelled() {
      return "CANCELLED".equalsIgnoreCase(status);
    }

    public boolean repeats() {
      return rrule != null && !rrule.isBlank();
    }

    public boolean invited() {
      return organizer != null && !organizer.isBlank();
    }

    public boolean answered() {
      return !"NEEDS-ACTION".equalsIgnoreCase(myAnswer);
    }

    public long startMillis() {
      return startsAt == null ? 0 : startsAt.getTime();
    }

    public long endMillis() {
      return endsAt == null ? startMillis() : endsAt.getTime();
    }

    public List<IcsFile.Attendee> attendees() {
      ArrayList<IcsFile.Attendee> out = new ArrayList<>();
      try {
        for (JsonNode node : JSON.readTree(
            attendeesJson == null || attendeesJson.isBlank() ? "[]" : attendeesJson)) {
          out.add(new IcsFile.Attendee(node.path("address").asText(), node.path("name").asText(""),
              node.path("partstat").asText("NEEDS-ACTION"),
              node.path("role").asText("REQ-PARTICIPANT")));
        }
      } catch (Exception ex) {
        return List.of();
      }
      return out;
    }

    /** the shape {@link IcsFile} writes out */
    public IcsFile.Event asIcs() {
      return new IcsFile.Event(uid, sequence, summary, description, location, startMillis(),
          endMillis(), allDay, rrule == null ? "" : rrule, exdates == null ? "" : exdates,
          status, organizer == null ? "" : organizer, attendees());
    }
  }

  // ---- writing -------------------------------------------------------------------------------

  /** what a save did, so the screen can say "added" rather than "saved" */
  public enum Outcome {
    added, updated, ignoredAsStale, cancelled
  }

  /**
   * Put an event in somebody's calendar, or bring the one that is there up to date.
   *
   * A CANCEL is not a delete: the row stays with `STATUS:CANCELLED`, which is what lets a screen
   * say "this was cancelled" rather than having the appointment silently vanish from a day somebody
   * has already planned around.
   */
  public Outcome merge(long userId, IcsFile.Event event, String source, Long fromMessage,
                       String myAnswer) throws SQLException {
    Record existing = byUid(userId, event.uid());
    if (existing != null && event.sequence() < existing.sequence()) {
      // mail is not ordered; an older revision arriving later is a stale copy
      return Outcome.ignoredAsStale;
    }
    ArrayNode attendees = JSON.createArrayNode();
    for (IcsFile.Attendee attendee : event.attendees()) {
      ObjectNode node = attendees.addObject();
      node.put("address", attendee.address());
      node.put("name", attendee.name() == null ? "" : attendee.name());
      node.put("partstat", attendee.partstat());
      node.put("role", attendee.role());
    }
    // What this person already said survives an update from the organizer.
    //
    // An organizer's REQUEST names everybody's PARTSTAT as they last heard it, which for a change
    // of room is out of date the moment it is sent. Taking it as the truth would quietly un-accept
    // a meeting somebody accepted.
    String answer = myAnswer != null ? myAnswer
        : existing != null ? existing.myAnswer() : "NEEDS-ACTION";

    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.CALENDAR_EVENTS + " (user_id, uid, sequence_number,"
                   + " summary, description, location, starts_at, ends_at, all_day, rrule,"
                   + " exdates, status, organizer, attendees, my_answer, source, from_message)"
                   + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        bind(statement, userId, event, attendees.toString(), answer, source, fromMessage);
        statement.executeUpdate();
      }
      store.changed(Schema.CALENDAR_EVENTS, event.uid(), MutationEvent.Kind.insert, userId);
      return Outcome.added;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR_EVENTS + " SET sequence_number = ?, summary = ?,"
                 + " description = ?, location = ?, starts_at = ?, ends_at = ?, all_day = ?,"
                 + " rrule = ?, exdates = ?, status = ?, organizer = ?, attendees = ?,"
                 + " my_answer = ?, updated_at = ? WHERE user_id = ? AND uid = ?")) {
      int at = 1;
      statement.setInt(at++, event.sequence());
      statement.setString(at++, cut(event.summary(), 1024));
      statement.setString(at++, cut(event.description(), 65_536));
      statement.setString(at++, cut(event.location(), 1024));
      statement.setTimestamp(at++, new Timestamp(event.startsAt()));
      statement.setTimestamp(at++, new Timestamp(event.endsAt()));
      statement.setBoolean(at++, event.allDay());
      statement.setString(at++, cut(event.rrule(), 1024));
      statement.setString(at++, cut(event.exdates(), 8192));
      statement.setString(at++, event.status());
      statement.setString(at++, cut(event.organizer(), 320));
      statement.setString(at++, cut(attendees.toString(), 16_384));
      statement.setString(at++, answer);
      statement.setTimestamp(at++, new Timestamp(System.currentTimeMillis()));
      statement.setLong(at++, userId);
      statement.setString(at, event.uid());
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR_EVENTS, event.uid(), MutationEvent.Kind.update, userId);
    return "CANCELLED".equalsIgnoreCase(event.status()) ? Outcome.cancelled : Outcome.updated;
  }

  private void bind(PreparedStatement statement, long userId, IcsFile.Event event,
                    String attendees, String answer, String source, Long fromMessage)
      throws SQLException {
    int at = 1;
    statement.setLong(at++, userId);
    statement.setString(at++, cut(event.uid(), 255));
    statement.setInt(at++, event.sequence());
    statement.setString(at++, cut(event.summary(), 1024));
    statement.setString(at++, cut(event.description(), 65_536));
    statement.setString(at++, cut(event.location(), 1024));
    statement.setTimestamp(at++, new Timestamp(event.startsAt()));
    statement.setTimestamp(at++, new Timestamp(event.endsAt()));
    statement.setBoolean(at++, event.allDay());
    statement.setString(at++, cut(event.rrule(), 1024));
    statement.setString(at++, cut(event.exdates(), 8192));
    statement.setString(at++, event.status());
    statement.setString(at++, cut(event.organizer(), 320));
    statement.setString(at++, cut(attendees, 16_384));
    statement.setString(at++, answer);
    statement.setString(at++, source);
    if (fromMessage == null) {
      statement.setNull(at, java.sql.Types.BIGINT);
    } else {
      statement.setLong(at, fromMessage);
    }
  }

  /** what this person said about an invitation, which is what a REPLY carries back */
  public void answer(long userId, String uid, String partstat) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR_EVENTS + " SET my_answer = ?, updated_at = ?"
                 + " WHERE user_id = ? AND uid = ?")) {
      statement.setString(1, partstat);
      statement.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
      statement.setLong(3, userId);
      statement.setString(4, uid);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR_EVENTS, uid, MutationEvent.Kind.update, userId);
  }

  public boolean delete(long id, long userId) throws SQLException {
    int gone;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDAR_EVENTS + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      gone = statement.executeUpdate();
    }
    if (gone > 0) {
      store.changed(Schema.CALENDAR_EVENTS, id, MutationEvent.Kind.delete, userId);
    }
    return gone > 0;
  }

  public int forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDAR_EVENTS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      return statement.executeUpdate();
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  public Record byUid(long userId, String uid) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.CALENDAR_EVENTS + " WHERE user_id = ? AND uid = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, uid);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  public Record byId(long id, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.CALENDAR_EVENTS + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  /**
   * Everything that could touch a window of time.
   *
   * <b>A repeating event is fetched whatever its start.</b> A weekly meeting set up two years ago
   * has a `starts_at` far outside any window somebody is looking at, and filtering on it alone
   * makes every repeat vanish from the calendar. So repeats come back in full and the caller
   * expands them; only one-off events are filtered by date, which is where the row count is.
   */
  public List<Record> between(long userId, long from, long to) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.CALENDAR_EVENTS + " WHERE user_id = ?"
                 + " AND (rrule <> '' OR (starts_at < ? AND ends_at > ?))"
                 + " ORDER BY starts_at")) {
      statement.setLong(1, userId);
      statement.setTimestamp(2, new Timestamp(to));
      statement.setTimestamp(3, new Timestamp(from));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(read(found));
        }
      }
    }
    return rows;
  }

  /** invitations nobody has answered yet, which is the only calendar thing an inbox should nag about */
  public List<Record> unanswered(long userId) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.CALENDAR_EVENTS + " WHERE user_id = ?"
                 + " AND my_answer = 'NEEDS-ACTION' AND organizer <> ''"
                 + " AND status <> 'CANCELLED' AND ends_at > ? ORDER BY starts_at "
                 + store.dialect().limit(50))) {
      statement.setLong(1, userId);
      statement.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(read(found));
        }
      }
    }
    return rows;
  }

  public int count(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.CALENDAR_EVENTS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? found.getInt(1) : 0;
      }
    }
  }

  // ---- the subscription feed -------------------------------------------------------------------

  /**
   * Mint a feed token, replacing whatever was there.
   *
   * Returns the token once, in the clear, because that is the only moment it exists in a readable
   * form -- the row holds its hash. Somebody who loses the URL mints a new one, which revokes the
   * old by overwriting it.
   */
  public String mintFeedToken(long userId) throws SQLException {
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             store.dialect().upsert(Schema.CALENDAR_FEEDS,
                 new String[]{"user_id", "token_hash", "created_at"}, new String[]{"user_id"}))) {
      statement.setLong(1, userId);
      statement.setString(2, hash(token));
      statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR_FEEDS, userId, MutationEvent.Kind.update, userId);
    return token;
  }

  /** whose feed this token is, or null. The token is never compared in the clear against a column. */
  public Long userForFeed(String token) throws SQLException {
    if (token == null || token.isBlank() || token.length() > 128) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT user_id FROM " + Schema.CALENDAR_FEEDS + " WHERE token_hash = ?")) {
      statement.setString(1, hash(token));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? found.getLong("user_id") : null;
      }
    }
  }

  public boolean hasFeed(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT 1 FROM " + Schema.CALENDAR_FEEDS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        return found.next();
      }
    }
  }

  public void revokeFeed(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDAR_FEEDS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR_FEEDS, userId, MutationEvent.Kind.delete, userId);
  }

  /** so an operator can see a feed is being read without the reads being a write per fetch */
  public void feedRead(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CALENDAR_FEEDS + " SET last_read_at = ? WHERE user_id = ?")) {
      statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
  }

  static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(64);
      for (byte b : digest) {
        out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return out.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static String cut(String value, int limit) {
    String clean = value == null ? "" : value;
    return clean.length() <= limit ? clean : clean.substring(0, limit);
  }

  private static Record read(ResultSet found) throws SQLException {
    long message = found.getLong("from_message");
    boolean noMessage = found.wasNull();
    return new Record(found.getLong("id"), found.getLong("user_id"), found.getString("uid"),
        found.getInt("sequence_number"), found.getString("summary"),
        found.getString("description"), found.getString("location"),
        found.getTimestamp("starts_at"), found.getTimestamp("ends_at"),
        found.getBoolean("all_day"), found.getString("rrule"), found.getString("exdates"),
        found.getString("status"), found.getString("organizer"), found.getString("attendees"),
        found.getString("my_answer"), found.getString("source"), noMessage ? null : message,
        found.getTimestamp("updated_at"));
  }

  /** the whole calendar as a file, for a phone that subscribes to it */
  public String feed(long userId, String name, java.time.ZoneId zone) throws SQLException {
    long now = System.currentTimeMillis();
    long from = now - FEED_DAYS_BACK * 86_400_000L;
    long to = now + FEED_DAYS_FORWARD * 86_400_000L;
    ArrayList<IcsFile.Event> events = new ArrayList<>();
    for (Record record : between(userId, from, to)) {
      // A cancelled event is still written out, with its status.
      //
      // Dropping it makes it disappear from a subscriber's calendar, which reads as "that meeting
      // never existed" rather than "that meeting was called off" -- and the second is the thing
      // somebody needs to know on the morning they were going to it.
      events.add(record.asIcs());
    }
    return IcsFile.write(null, events, name);
  }

  /** the same normalization every caller uses when it puts a name on a feed */
  public static String feedName(String community) {
    String clean = community == null || community.isBlank() ? "Calendar" : community.trim();
    return clean.length() > 60 ? clean.substring(0, 60) : clean;
  }

  /** the answers an invitation can be given, in the order a screen offers them */
  public static List<String> answers() {
    return List.of("ACCEPTED", "TENTATIVE", "DECLINED");
  }

  public static String answerLabel(String partstat) {
    return switch (partstat == null ? "" : partstat.toUpperCase(Locale.ROOT)) {
      case "ACCEPTED" -> "going";
      case "TENTATIVE" -> "maybe";
      case "DECLINED" -> "not going";
      default -> "not answered";
    };
  }
}
