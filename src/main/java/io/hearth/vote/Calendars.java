package io.hearth.vote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.PublicAddress;
import io.hearth.store.Schema;
import io.hearth.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Calendars this server fetches, keeps for an hour, and reduces to busy windows.
 *
 * <b>Fetching somebody's calendar is a serious thing to do and every guard here is about that.</b>
 * The url is member-supplied, which is invariant 150 exactly: https only, resolved and refused if it
 * points anywhere private, no redirects, a timeout and a ceiling on the body. Relaxing any one of
 * those turns "paste your calendar link" into a way to make this server fetch things on the internal
 * network on somebody else's behalf.
 *
 * <b>Only the windows are kept.</b> {@link Ics} throws away the summaries; this stores what it
 * returns and nothing else. What is on disk is a list of "busy from here to here", which is what a
 * scheduler needs and is not a record of who anybody met.
 *
 * <b>An hour, because five people and a conversation is five fetches.</b> Long enough that an agent
 * working out an evening does not hammer anybody's server; short enough that something booked this
 * morning is visible this afternoon. Refreshing is lazy -- the first read after the hour pays for
 * it -- because a background poller for a handful of calendars is a scheduled job to supervise for
 * no benefit.
 *
 * <b>A broken link is visible.</b> What went wrong is stored and shown, because a calendar that
 * silently returns nothing looks exactly like a calendar with nothing in it, and the difference
 * decides whether an evening is free.
 */
public class Calendars {
  private static final Logger LOG = LoggerFactory.getLogger(Calendars.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** how long a fetched calendar is trusted */
  public static final long TTL_MILLIS = 60 * 60 * 1000L;

  private final Store store;
  private final HttpClient http;

  public Calendars(Store store) {
    this(store, HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(8))
        .build());
  }

  /** the seam a test uses */
  public Calendars(Store store, HttpClient http) {
    this.store = store;
    this.http = http;
  }

  public record Cached(long userId, String url, List<Ics.Busy> busy, Timestamp fetchedAt,
                       String trouble) {
    public boolean isFresh(long now) {
      return fetchedAt != null && now - fetchedAt.getTime() < TTL_MILLIS;
    }

    public boolean hasTrouble() {
      return trouble != null && !trouble.isBlank();
    }
  }

  /**
   * Somebody's busy windows, fetching if what is held has gone stale.
   *
   * Returns what is cached even when a refresh fails, because an hour-old answer is worth far more
   * than none -- the trouble rides along so a screen can say the link is broken while still using
   * what it last saw.
   */
  public Cached of(long userId, String url, ZoneId zone) throws SQLException {
    Cached held = read(userId);
    long now = System.currentTimeMillis();
    if (url == null || url.isBlank()) {
      return new Cached(userId, "", List.of(), held == null ? null : held.fetchedAt(), "");
    }
    boolean sameUrl = held != null && url.equals(held.url());
    if (sameUrl && held.isFresh(now)) {
      return held;
    }
    Fetched fetched = fetch(url, zone);
    if (fetched.trouble() != null) {
      // keep what we had; an hour-old calendar beats nothing, and the trouble is carried
      save(userId, url, sameUrl && held != null ? held.busy() : List.of(),
          sameUrl && held != null ? held.fetchedAt() : null, fetched.trouble());
      return read(userId);
    }
    save(userId, url, fetched.busy(), new Timestamp(now), "");
    return read(userId);
  }

  private record Fetched(List<Ics.Busy> busy, String trouble) {
  }

  /**
   * One fetch, with every guard invariant 150 asks for.
   *
   * The address is resolved and refused if it is private *after* resolution, because a name that
   * resolves to 10.x is the whole trick -- checking the string would catch nothing.
   */
  private Fetched fetch(String url, ZoneId zone) {
    String clean = url.trim();
    if (!clean.startsWith("https://") || clean.length() > 1024) {
      return new Fetched(List.of(), "a calendar link has to be an https url");
    }
    URI uri;
    try {
      uri = URI.create(clean);
    } catch (RuntimeException ex) {
      return new Fetched(List.of(), "that is not a url this server can read");
    }
    String refusal = PublicAddress.refuse(uri.getHost());
    if (refusal != null) {
      return new Fetched(List.of(), refusal);
    }
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(uri)
          .timeout(Duration.ofSeconds(15))
          .header("Accept", "text/calendar, text/plain")
          .GET()
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        // including redirects: a redirect is a request to somewhere nobody vetted, and the address
        // that was checked is not the one that would be fetched
        return new Fetched(List.of(), "that calendar answered " + response.statusCode());
      }
      String body = response.body();
      if (body == null || body.length() > Ics.MAX_BYTES) {
        return new Fetched(List.of(), body == null ? "that calendar was empty"
            : "that calendar is too large to read");
      }
      if (!body.toUpperCase(java.util.Locale.ROOT).contains("BEGIN:VCALENDAR")) {
        return new Fetched(List.of(), "that link did not answer with a calendar");
      }
      return new Fetched(Ics.busy(body, zone), null);
    } catch (java.io.IOException ex) {
      return new Fetched(List.of(), "that calendar could not be reached");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return new Fetched(List.of(), "that fetch was interrupted");
    }
  }

  private Cached read(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.CALENDARS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        if (!found.next()) {
          return null;
        }
        ArrayList<Ics.Busy> busy = new ArrayList<>();
        try {
          JsonNode node = JSON.readTree(found.getString("busy"));
          for (JsonNode one : node) {
            // `f` defaults to true for a row written before firmness existed, which is the
            // conservative reading of an old cache and self-corrects within the hour
            busy.add(new Ics.Busy(one.path("s").asLong(), one.path("e").asLong(),
                one.path("f").asBoolean(true)));
          }
        } catch (Exception ex) {
          LOG.warn("calendar-cache-unreadable user={}", userId);
        }
        return new Cached(userId, found.getString("url"), busy,
            found.getTimestamp("fetched_at"), found.getString("trouble"));
      }
    }
  }

  private void save(long userId, String url, List<Ics.Busy> busy, Timestamp when, String trouble)
      throws SQLException {
    ArrayNode node = JSON.createArrayNode();
    for (Ics.Busy window : busy) {
      ObjectNode one = node.addObject();
      one.put("s", window.start());
      one.put("e", window.end());
      // Firmness has to survive the cache, and it did not: without this every maybe came back as a
      // certainty on the next read, which is precisely the failure the whole design forbids -- a
      // standing Tuesday call silently deleting Tuesday, an hour after it was fetched.
      one.put("f", window.firm());
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.CALENDARS + " (user_id, url, busy, fetched_at, trouble)"
                 + " KEY (user_id) VALUES (?, ?, ?, ?, ?)")) {
      statement.setLong(1, userId);
      statement.setString(2, url);
      statement.setString(3, node.toString());
      statement.setTimestamp(4, when);
      statement.setString(5, trouble == null ? "" : trouble);
      statement.executeUpdate();
    }
  }

  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CALENDARS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }
}
