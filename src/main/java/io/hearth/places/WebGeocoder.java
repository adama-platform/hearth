package io.hearth.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.common.Verbose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The three geocoding services this server will talk to, and the manners each one asks for.
 *
 * One class rather than three because the difference between them is a URL and where the numbers
 * sit in the answer -- and three near-identical files would be three places to forget the rate
 * limiter.
 *
 * <b>The rate limiter is not politeness, it is the terms.</b> Nominatim's acceptable use policy is
 * an absolute maximum of one request a second with a User-Agent that identifies you, and clients
 * that ignore it get blocked -- not throttled, blocked, and the community finds out when nothing
 * geocodes any more. So the limiter applies to every service: nobody is annoyed by a server that
 * asks slowly, and this is a few dozen lookups in the lifetime of a community.
 */
public class WebGeocoder implements Geocoder {
  private static final Logger LOG = LoggerFactory.getLogger(WebGeocoder.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  /** one a second, which is the strictest of the three policies applied to all of them */
  private static final long MIN_GAP_MILLIS = 1100;

  /** which service, and what each one needs from an operator */
  public enum Service {
    /**
     * OpenStreetMap's own. No key, and an acceptable use policy instead: one request a second, a
     * User-Agent that names the application and a way to reach whoever runs it, results cached
     * rather than re-asked, and no bulk work. All four are things this server does anyway.
     */
    nominatim,
    /**
     * A key, and the clearest terms of the three: results may be kept permanently, even after you
     * stop paying. No requirement to show them on anybody's map.
     */
    opencage,
    /** A key, a larger free allowance, and attribution required on the free plan. */
    geoapify;

    public static Service of(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }

    public boolean needsKey() {
      return this != nominatim;
    }

    /** what an operator has to know before choosing it */
    public String terms() {
      return switch (this) {
        case nominatim -> "free, no key. One request a second, and you must say who you are --"
            + " which is what the contact address below is for. Right for a community geocoding a"
            + " few dozen places; not for anything bulk.";
        case opencage -> "a key, a free tier for testing, paid from there. The only one whose terms"
            + " say plainly that you may keep the results permanently, which is what this server"
            + " does with them.";
        case geoapify -> "a key and a larger free allowance. Attribution to Geoapify is required on"
            + " the free plan.";
      };
    }

    public String where() {
      return switch (this) {
        case nominatim -> "https://operations.osmfoundation.org/policies/nominatim/";
        case opencage -> "https://opencagedata.com/";
        case geoapify -> "https://www.geoapify.com/";
      };
    }
  }

  private final Service service;
  private final String key;
  private final String contact;
  private final Verbose verbose;
  private final HttpClient client;
  private final AtomicLong lastCall = new AtomicLong();

  public WebGeocoder(Service service, String key, String contact, Verbose verbose) {
    this.service = service;
    this.key = key == null ? "" : key.trim();
    this.contact = contact == null || contact.isBlank() ? "an operator who did not say" : contact;
    this.verbose = verbose;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Override
  public String name() {
    return service.name();
  }

  @Override
  public String describe() {
    return service.name() + (service.needsKey() ? " (key configured)" : " (no key needed)")
        + ", one lookup a second at most";
  }

  @Override
  public Point find(String query) throws Unavailable {
    if (query == null || query.isBlank()) {
      return null;
    }
    // A second pacer behind the queue's, which is not redundant: the queue is what everything
    // *should* go through, and inbound mail still asks directly. A floor here means the policy
    // holds even for the caller that forgot.
    pace();
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url(query)))
          .timeout(Duration.ofSeconds(10))
          // Nominatim's policy asks for an application name and a way to reach somebody; the
          // others do not mind, and a server that identifies itself everywhere is easier to be
          // on the right side of than one that remembers per service.
          .header("User-Agent", "Hearth community server (" + contact + ")")
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        verbose.detail(() -> "geocode: " + service + " said " + response.statusCode());
        // 429 and 403 are the two that matter: over the limit, and blocked. Both want the caller
        // to wait rather than to try the next address a second and a half later.
        throw new Unavailable(service + " answered " + response.statusCode());
      }
      return read(JSON.readTree(response.body()));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new Unavailable("interrupted");
    } catch (Unavailable ex) {
      throw ex;
    } catch (Exception ex) {
      LOG.warn("geocode-failed service={}", service, ex);
      throw new Unavailable(service + " could not be reached: " + ex.getMessage(), ex);
    }
  }

  /** one a second, measured across everything this server asks */
  private void pace() {
    long now = System.currentTimeMillis();
    long previous = lastCall.getAndSet(now);
    long wait = MIN_GAP_MILLIS - (now - previous);
    if (wait > 0) {
      try {
        Thread.sleep(wait);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      lastCall.set(System.currentTimeMillis());
    }
  }

  private String url(String query) {
    String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
    return switch (service) {
      case nominatim -> "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q="
          + encoded;
      case opencage -> "https://api.opencagedata.com/geocode/v1/json?limit=1&no_annotations=1&q="
          + encoded + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
      case geoapify -> "https://api.geoapify.com/v1/geocode/search?limit=1&text=" + encoded
          + "&apiKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    };
  }

  /** where each service keeps the two numbers; visible for testing without a network */
  static Point read(Service service, JsonNode body) {
    if (body == null) {
      return null;
    }
    return switch (service) {
      case nominatim -> {
        JsonNode first = body.isArray() && !body.isEmpty() ? body.get(0) : null;
        yield first == null ? null : point(first.path("lat").asText(null),
            first.path("lon").asText(null), first.path("display_name").asText(""));
      }
      case opencage -> {
        JsonNode first = body.path("results").isArray() && !body.path("results").isEmpty()
            ? body.path("results").get(0) : null;
        yield first == null ? null
            : point(first.path("geometry").path("lat").asText(null),
                first.path("geometry").path("lng").asText(null),
                first.path("formatted").asText(""));
      }
      case geoapify -> {
        JsonNode first = body.path("features").isArray() && !body.path("features").isEmpty()
            ? body.path("features").get(0) : null;
        if (first == null) {
          yield null;
        }
        JsonNode coordinates = first.path("geometry").path("coordinates");
        // GeoJSON is longitude first, which is the opposite of every other service here and the
        // single easiest thing in this file to get backwards
        yield coordinates.size() < 2 ? null
            : point(coordinates.get(1).asText(null), coordinates.get(0).asText(null),
                first.path("properties").path("formatted").asText(""));
      }
    };
  }

  private Point read(JsonNode body) {
    return read(service, body);
  }

  private static Point point(String latitude, String longitude, String label) {
    if (latitude == null || longitude == null) {
      return null;
    }
    try {
      double lat = Double.parseDouble(latitude);
      double lon = Double.parseDouble(longitude);
      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        return null;
      }
      return new Point(lat, lon, label);
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
