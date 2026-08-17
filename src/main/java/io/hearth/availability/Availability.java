package io.hearth.availability;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * When each person could come, and what their calendar says about the weeks ahead.
 *
 * Three things live here and they answer three different questions. A **window** is what somebody
 * would like -- Tuesday evenings, most Saturdays -- and it is true for years. A **link** is the
 * calendar they already keep, which is where the exceptions come from without anybody maintaining a
 * second diary. The **cache** is what that calendar said the last time it was asked, because the
 * alternative is a page whose speed depends on somebody else's server having a good day.
 *
 * <b>Nothing here holds what anybody is doing.</b> The cache stores busy blocks -- two numbers --
 * and never a title, a location or a guest. That somebody cannot come on Thursday is the
 * community's business; what they are doing instead is not, and a table that held it would be a
 * table somebody would eventually put on a screen.
 */
public class Availability {
  /** the most windows one person can draw; past this it is a diary rather than a shape */
  public static final int MAX_WINDOWS = 40;
  private static final String WINDOW_COLUMNS =
      "id, user_id, day_of_week, starts_at, ends_at, note, created_at, updated_at";
  private static final String LINK_COLUMNS =
      "id, user_id, url, label, active, created_at, updated_at";
  private static final String CACHE_COLUMNS =
      "id, user_id, url_hash, fetched_at, expires_at, status, detail, busy, blocks";

  private final Store store;

  public Availability(Store store) {
    this.store = store;
  }

  /** one stretch of a week somebody would like to be free */
  public record Window(long id, long userId, DayOfWeek day, int startsAt, int endsAt, String note,
                       Timestamp createdAt, Timestamp updatedAt) {
    /** does this window cover the given minute of that day? */
    public boolean covers(DayOfWeek onDay, int minute) {
      return day == onDay && minute >= startsAt && minute < endsAt;
    }

    public String from() {
      return clock(startsAt);
    }

    public String to() {
      return clock(endsAt);
    }
  }

  /** a calendar somebody pointed us at */
  public record Link(long id, long userId, String url, String label, boolean active,
                     Timestamp createdAt, Timestamp updatedAt) {
    public String display() {
      return label == null || label.isBlank() ? shortUrl() : label;
    }

    /** the host and nothing else: the path of one of these is frequently a secret */
    public String shortUrl() {
      try {
        return java.net.URI.create(url).getHost();
      } catch (RuntimeException ex) {
        return "a calendar";
      }
    }
  }

  /** what one calendar said, and when */
  public record Cached(long id, long userId, String urlHash, Timestamp fetchedAt,
                       Timestamp expiresAt, String status, String detail, String busy, int blocks) {
    public boolean ok() {
      return "ok".equals(status);
    }

    public boolean fresh(long now) {
      return expiresAt != null && expiresAt.getTime() > now;
    }
  }

  public static String clock(int minutes) {
    return String.format("%02d:%02d", Math.floorDiv(minutes, 60), Math.floorMod(minutes, 60));
  }

  /** minutes from midnight, from an "HH:MM" a browser sent, or -1 */
  public static int minutesOf(String raw) {
    if (raw == null) {
      return -1;
    }
    String[] parts = raw.trim().split(":");
    if (parts.length < 2) {
      return -1;
    }
    try {
      int hour = Integer.parseInt(parts[0]);
      int minute = Integer.parseInt(parts[1]);
      if (hour < 0 || hour > 24 || minute < 0 || minute > 59) {
        return -1;
      }
      return hour * 60 + minute;
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  // ---- the windows -------------------------------------------------------------------------------

  public List<Window> windowsFor(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + WINDOW_COLUMNS + " FROM " + Schema.AVAILABILITY
                 + " WHERE user_id = ? ORDER BY day_of_week, starts_at")) {
      statement.setLong(1, userId);
      return readWindows(statement);
    }
  }

  /** everybody's, for the grid; one query rather than one per member */
  public List<Window> allWindows(int limit) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + WINDOW_COLUMNS + " FROM " + Schema.AVAILABILITY
                 + " ORDER BY user_id, day_of_week, starts_at "
                 + store.dialect().limit(Math.max(1, limit)))) {
      return readWindows(statement);
    }
  }

  /**
   * Add one, unless it is nonsense.
   *
   * A window that ends before it starts is a typo, and one that covers no minutes is a row that
   * would quietly count for nothing on a grid somebody is trusting.
   */
  public Window addWindow(long userId, DayOfWeek day, int startsAt, int endsAt, String note)
      throws SQLException {
    if (day == null || startsAt < 0 || endsAt > 24 * 60 || endsAt <= startsAt) {
      return null;
    }
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.AVAILABILITY
                 + " (user_id, day_of_week, starts_at, ends_at, note) VALUES (?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, userId);
      statement.setInt(2, day.getValue());
      statement.setInt(3, startsAt);
      statement.setInt(4, endsAt);
      statement.setString(5, cap(note, 256));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.AVAILABILITY, id, MutationEvent.Kind.insert, userId);
    return windowById(id);
  }

  public Window windowById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + WINDOW_COLUMNS + " FROM " + Schema.AVAILABILITY + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readWindow(rows) : null;
      }
    }
  }

  /** remove one of this person's own; an id belonging to somebody else removes nothing */
  public boolean removeWindow(long userId, long id) throws SQLException {
    int gone;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.AVAILABILITY + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      gone = statement.executeUpdate();
    }
    if (gone > 0) {
      store.changed(Schema.AVAILABILITY, id, MutationEvent.Kind.delete, userId);
    }
    return gone > 0;
  }

  public int countWindows(long userId) throws SQLException {
    return countOf(Schema.AVAILABILITY, userId);
  }

  // ---- the links ---------------------------------------------------------------------------------

  public List<Link> linksFor(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + LINK_COLUMNS + " FROM " + Schema.CALENDAR_LINKS
                 + " WHERE user_id = ? ORDER BY created_at")) {
      statement.setLong(1, userId);
      return readLinks(statement);
    }
  }

  /** every live one, for the daily pull */
  public List<Link> allLinks(int limit) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + LINK_COLUMNS + " FROM " + Schema.CALENDAR_LINKS
                 + " WHERE active = TRUE ORDER BY user_id "
                 + store.dialect().limit(Math.max(1, limit)))) {
      return readLinks(statement);
    }
  }

  public Link addLink(long userId, String url, String label) throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.CALENDAR_LINKS + " (user_id, url, label) VALUES (?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, userId);
      statement.setString(2, cap(url, 1024));
      statement.setString(3, cap(label, 128));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.CALENDAR_LINKS, id, MutationEvent.Kind.insert, userId);
    return linkById(id);
  }

  public Link linkById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + LINK_COLUMNS + " FROM " + Schema.CALENDAR_LINKS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readLink(rows) : null;
      }
    }
  }

  /**
   * Take one away, and what it told us with it.
   *
   * The cache row goes in the same breath: a calendar somebody unlinked must stop affecting the
   * grid immediately rather than at the next nightly pass, because "I took that off" and "it is
   * still counting me as busy" is the kind of gap that makes people stop trusting a screen.
   */
  public boolean removeLink(long userId, long id) throws SQLException {
    Link link = linkById(id);
    if (link == null || link.userId() != userId) {
      return false;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDAR_LINKS + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
    forgetCached(userId, hashOf(link.url()));
    store.changed(Schema.CALENDAR_LINKS, id, MutationEvent.Kind.delete, userId);
    return true;
  }

  public int countLinks(long userId) throws SQLException {
    return countOf(Schema.CALENDAR_LINKS, userId);
  }

  // ---- what the calendars said -------------------------------------------------------------------

  /** the sha-256 of a url; the url itself is often a secret address with a token in it */
  public static String hashOf(String url) {
    return io.hearth.auth.Tokens.hash(url == null ? "" : url.trim());
  }

  public Cached cachedFor(long userId, String urlHash) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CACHE_COLUMNS + " FROM " + Schema.CALENDAR_CACHE
                 + " WHERE user_id = ? AND url_hash = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, urlHash);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readCached(rows) : null;
      }
    }
  }

  public List<Cached> cachedFor(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CACHE_COLUMNS + " FROM " + Schema.CALENDAR_CACHE + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      return readCacheRows(statement);
    }
  }

  public List<Cached> allCached(int limit) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CACHE_COLUMNS + " FROM " + Schema.CALENDAR_CACHE + " ORDER BY user_id "
                 + store.dialect().limit(Math.max(1, limit)))) {
      return readCacheRows(statement);
    }
  }

  /**
   * Write down what a calendar said, and when to stop believing it.
   *
   * One row per person per url, replaced rather than appended: this is a cache, and a history of
   * what somebody's calendar used to say is a record of their movements that nobody asked for.
   */
  public void remember(long userId, String urlHash, String status, String detail, String busy,
                       int blocks, long expiresAtMillis) throws SQLException {
    Cached existing = cachedFor(userId, urlHash);
    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.CALENDAR_CACHE + " (user_id, url_hash, fetched_at,"
                   + " expires_at, status, detail, busy, blocks) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
        statement.setLong(1, userId);
        statement.setString(2, urlHash);
        statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
        statement.setTimestamp(4, new Timestamp(expiresAtMillis));
        statement.setString(5, status);
        statement.setString(6, cap(detail, 512));
        statement.setString(7, busy);
        statement.setInt(8, blocks);
        statement.executeUpdate();
      }
    } else {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.CALENDAR_CACHE + " SET fetched_at = ?, expires_at = ?,"
                   + " status = ?, detail = ?, busy = ?, blocks = ?,"
                   + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
        statement.setTimestamp(2, new Timestamp(expiresAtMillis));
        statement.setString(3, status);
        statement.setString(4, cap(detail, 512));
        statement.setString(5, busy);
        statement.setInt(6, blocks);
        statement.setLong(7, existing.id());
        statement.executeUpdate();
      }
    }
    store.changed(Schema.CALENDAR_CACHE, userId, MutationEvent.Kind.update, userId);
  }

  public void forgetCached(long userId, String urlHash) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDAR_CACHE + " WHERE user_id = ? AND url_hash = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, urlHash);
      statement.executeUpdate();
    }
    store.changed(Schema.CALENDAR_CACHE, userId, MutationEvent.Kind.delete, userId);
  }

  /** everything one person contributed; erasure calls this */
  public void forget(long userId) throws SQLException {
    for (String table : new String[]{Schema.AVAILABILITY, Schema.CALENDAR_LINKS,
        Schema.CALENDAR_CACHE}) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "DELETE FROM " + table + " WHERE user_id = ?")) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
    }
  }

  /** the busy blocks in one cached row, as instants */
  public static List<long[]> blocksIn(String busy) {
    ArrayList<long[]> blocks = new ArrayList<>();
    if (busy == null || busy.isBlank()) {
      return blocks;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode node =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(busy);
      if (!node.isArray()) {
        return blocks;
      }
      for (com.fasterxml.jackson.databind.JsonNode pair : node) {
        if (pair.isArray() && pair.size() == 2) {
          blocks.add(new long[]{pair.get(0).asLong(), pair.get(1).asLong()});
        }
      }
    } catch (Exception ex) {
      // an unreadable blob is a calendar we know nothing about, never a page that fails
    }
    return blocks;
  }

  /** and the other way, for the writer */
  public static String blocksOut(List<long[]> blocks) {
    StringBuilder out = new StringBuilder("[");
    for (int k = 0; k < blocks.size(); k++) {
      if (k > 0) {
        out.append(',');
      }
      out.append('[').append(blocks.get(k)[0]).append(',').append(blocks.get(k)[1]).append(']');
    }
    return out.append(']').toString();
  }

  public static Instant at(long epochSecond) {
    return Instant.ofEpochSecond(epochSecond);
  }

  // ---- plumbing ----------------------------------------------------------------------------------

  private int countOf(String table, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static List<Window> readWindows(PreparedStatement statement) throws SQLException {
    ArrayList<Window> windows = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        windows.add(readWindow(rows));
      }
    }
    return windows;
  }

  private static Window readWindow(ResultSet rows) throws SQLException {
    int day = rows.getInt("day_of_week");
    return new Window(rows.getLong("id"), rows.getLong("user_id"),
        DayOfWeek.of(day < 1 || day > 7 ? 1 : day), rows.getInt("starts_at"),
        rows.getInt("ends_at"), rows.getString("note"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

  private static List<Link> readLinks(PreparedStatement statement) throws SQLException {
    ArrayList<Link> links = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        links.add(readLink(rows));
      }
    }
    return links;
  }

  private static Link readLink(ResultSet rows) throws SQLException {
    return new Link(rows.getLong("id"), rows.getLong("user_id"), rows.getString("url"),
        rows.getString("label"), rows.getBoolean("active"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

  private static List<Cached> readCacheRows(PreparedStatement statement) throws SQLException {
    ArrayList<Cached> rows = new ArrayList<>();
    try (ResultSet found = statement.executeQuery()) {
      while (found.next()) {
        rows.add(readCached(found));
      }
    }
    return rows;
  }

  private static Cached readCached(ResultSet rows) throws SQLException {
    return new Cached(rows.getLong("id"), rows.getLong("user_id"), rows.getString("url_hash"),
        rows.getTimestamp("fetched_at"), rows.getTimestamp("expires_at"), rows.getString("status"),
        rows.getString("detail"), rows.getString("busy"), rows.getInt("blocks"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
