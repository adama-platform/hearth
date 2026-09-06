package io.hearth.hevy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Hevy's public API, from this server, on somebody's behalf.
 *
 * <b>One host, hard-coded, and that is the security story.</b> Invariant 137 is about a
 * member-supplied url being an instruction to make a request; this is the opposite case and stays
 * that way by construction -- the base is a constant, the path comes from a closed set of methods
 * on this class, and nothing a caller passes can move the request to another host. There is no
 * `get(url)` here and there should never be one.
 *
 * <b>The key is fetched per call and never held.</b> {@link UserKeys} reads it at the moment a call
 * is made and it goes no further than the header. No field, no cache, no log line: a credential in
 * a stack trace is a credential in a bug report.
 *
 * <b>Hevy's own words about this API, which is why the disclaimer is in the code:</b> "we make no
 * guarantees that we won't completely change the structure or abandon the project entirely so use
 * it at your own risk." So every response is treated as data that might be shaped differently
 * tomorrow -- parsed leniently, never mapped onto records this server would then have to keep in
 * step, and handed on as JSON. A schema of our own here would be a second thing to break when
 * theirs moves.
 *
 * <b>Redirects are not followed.</b> Same reason as everywhere else: a redirect is a request to a
 * place nobody vetted, and there is no legitimate reason for a JSON API to send one.
 */
public class Hevy {
  private static final Logger LOG = LoggerFactory.getLogger(Hevy.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** the one host this talks to */
  public static final String BASE = "https://api.hevyapp.com";

  /** what Hevy asks callers to be told, in their words */
  public static final String DISCLAIMER =
      "Hevy's API is theirs, not this server's, and they say of it: \"we make no guarantees that we"
          + " won't completely change the structure or abandon the project entirely so use it at"
          + " your own risk.\" It is only available to Hevy Pro accounts.";

  /** where a person gets a key */
  public static final String KEY_PAGE = "https://hevy.com/settings?developer";

  private final HttpClient http;
  private final UserKeys keys;

  public Hevy(UserKeys keys) {
    this(keys, HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build());
  }

  /** the seam a test uses: a client that answers without a network */
  public Hevy(UserKeys keys, HttpClient http) {
    this.keys = keys;
    this.http = http;
  }

  /** a call that did not happen, in words somebody can act on */
  public static class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  public boolean hasKey(long userId) throws java.sql.SQLException {
    return keys.has(userId, UserKeys.Service.hevy);
  }

  // ---- reads -------------------------------------------------------------------------------

  public JsonNode workouts(long userId, int page, int pageSize) throws Refused {
    return get(userId, "/v1/workouts?page=" + page + "&pageSize=" + clamp(pageSize, 10));
  }

  public JsonNode workout(long userId, String workoutId) throws Refused {
    return get(userId, "/v1/workouts/" + encode(workoutId));
  }

  public JsonNode workoutCount(long userId) throws Refused {
    return get(userId, "/v1/workouts/count");
  }

  public JsonNode routines(long userId, int page, int pageSize) throws Refused {
    return get(userId, "/v1/routines?page=" + page + "&pageSize=" + clamp(pageSize, 10));
  }

  public JsonNode exerciseTemplates(long userId, int page, int pageSize) throws Refused {
    return get(userId, "/v1/exercise_templates?page=" + page + "&pageSize=" + clamp(pageSize, 100));
  }

  public JsonNode exerciseTemplate(long userId, String templateId) throws Refused {
    return get(userId, "/v1/exercise_templates/" + encode(templateId));
  }

  public JsonNode routineFolders(long userId, int page, int pageSize) throws Refused {
    return get(userId, "/v1/routine_folders?page=" + page + "&pageSize=" + clamp(pageSize, 10));
  }

  public JsonNode exerciseHistory(long userId, String templateId) throws Refused {
    return get(userId, "/v1/exercise_history/" + encode(templateId));
  }

  // ---- writes ------------------------------------------------------------------------------

  public JsonNode createExercise(long userId, Object body) throws Refused {
    return post(userId, "/v1/exercise_templates", body);
  }

  public JsonNode createRoutine(long userId, Object body) throws Refused {
    return post(userId, "/v1/routines", body);
  }

  public JsonNode updateRoutine(long userId, String routineId, Object body) throws Refused {
    return send(userId, "PUT", "/v1/routines/" + encode(routineId), body);
  }

  public JsonNode createRoutineFolder(long userId, Object body) throws Refused {
    return post(userId, "/v1/routine_folders", body);
  }

  // ---- the one place a request is actually made ------------------------------------------------

  private JsonNode get(long userId, String path) throws Refused {
    return send(userId, "GET", path, null);
  }

  private JsonNode post(long userId, String path, Object body) throws Refused {
    return send(userId, "POST", path, body);
  }

  /**
   * Every call, and there is deliberately only one of these.
   *
   * The path is built by the methods above from a closed set; nothing a caller hands in reaches the
   * URL except values that went through {@link #encode}. A refusal says which of the three things
   * went wrong -- no key, Hevy said no, or the network -- because "it did not work" sends somebody
   * looking in the wrong place.
   */
  private JsonNode send(long userId, String method, String path, Object body) throws Refused {
    String key;
    try {
      key = keys.secretFor(userId, UserKeys.Service.hevy);
    } catch (java.sql.SQLException ex) {
      throw new Refused("this server could not read your Hevy key");
    }
    if (key == null) {
      throw new Refused("no Hevy key is set for this account. Get one at " + KEY_PAGE
          + " (Hevy Pro only) and save it on your own page.");
    }
    HttpRequest.BodyPublisher payload;
    try {
      payload = body == null
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    } catch (Exception ex) {
      throw new Refused("that request could not be encoded");
    }
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(BASE + path))
        .timeout(Duration.ofSeconds(20))
        .header("api-key", key)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .method(method, payload)
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException ex) {
      // deliberately not including the exception's message: it can carry the URL, and the URL is
      // not interesting while the header is the only secret in the request
      throw new Refused("Hevy could not be reached");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new Refused("that call was interrupted");
    }
    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new Refused("Hevy refused the key on this account. It may have been revoked, or the"
          + " account may no longer be Hevy Pro.");
    }
    if (status == 404) {
      throw new Refused("Hevy has nothing at that address");
    }
    if (status >= 400) {
      LOG.warn("hevy-refused status={} path={}", status, path);
      throw new Refused("Hevy answered " + status + ": " + shorten(response.body()));
    }
    if (response.body() == null || response.body().isBlank()) {
      return JSON.createObjectNode().put("ok", true);
    }
    try {
      return JSON.readTree(response.body());
    } catch (Exception ex) {
      throw new Refused("Hevy's answer was not JSON this server could read");
    }
  }

  /** their error text, capped: it goes to a model, and a wall of HTML would be a wasted turn */
  private static String shorten(String body) {
    if (body == null) {
      return "";
    }
    String clean = body.strip().replaceAll("\\s+", " ");
    return clean.length() <= 300 ? clean : clean.substring(0, 297) + "...";
  }

  /**
   * A path segment somebody else chose.
   *
   * Hevy's ids are hex-ish and uuid-ish, so this refuses anything else outright rather than
   * escaping it. An id is not a place to be permissive: a slash that survives encoding is a request
   * to a different endpoint.
   */
  private static String encode(String id) {
    if (id == null || id.isBlank() || id.length() > 64
        || !id.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("that is not an id");
    }
    return id;
  }

  private static int clamp(int value, int fallback) {
    return value < 1 || value > 100 ? fallback : value;
  }

  /** the closed lists Hevy accepts, so a tool can tell a model what is allowed */
  public static final List<String> EXERCISE_TYPES = List.of(
      "weight_reps", "reps_only", "bodyweight_reps", "bodyweight_assisted_reps",
      "duration", "weight_duration", "distance_duration", "short_distance_weight");

  public static final List<String> EQUIPMENT = List.of(
      "none", "barbell", "dumbbell", "kettlebell", "machine", "plate",
      "resistance_band", "suspension", "other");

  public static final List<String> MUSCLE_GROUPS = List.of(
      "abdominals", "shoulders", "biceps", "triceps", "forearms", "quadriceps", "hamstrings",
      "calves", "glutes", "abductors", "adductors", "lats", "upper_back", "traps", "lower_back",
      "chest", "cardio", "neck", "full_body", "other");

  public static final List<String> SET_TYPES = List.of("warmup", "normal", "failure", "dropset");

  public static boolean isOneOf(List<String> allowed, String value) {
    return value != null && allowed.contains(value.trim().toLowerCase(Locale.ROOT));
  }
}
