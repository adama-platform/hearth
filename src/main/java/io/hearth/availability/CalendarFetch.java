package io.hearth.availability;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Fetching a calendar somebody pasted in, which is a request this server makes on their say-so.
 *
 * <b>This is the one place in Hearth where a member's typing becomes an outbound request, and that
 * makes it the one place server-side request forgery can happen.</b> A url is not a document, it is
 * an instruction to connect: `http://169.254.169.254/latest/meta-data/` is a cloud provider's
 * credential service, `http://localhost:8080/admin/...` is this server's own admin section reached
 * from inside the machine where nothing is asking for a cookie, and `http://192.168.1.1/` is
 * somebody's router. Every one of those is a real, published attack, and every one of them is a
 * member pasting a "calendar link".
 *
 * So the rules here are deliberately strict and deliberately dull:
 *
 * <ul>
 *   <li><b>https only.</b> A calendar address is published over TLS by every service that offers
 *       one, and plain http would let anything on the path read a secret feed address.</li>
 *   <li><b>Public addresses only</b>, checked after the name is resolved rather than by looking at
 *       the text -- a name that resolves to 127.0.0.1 is the whole trick.</li>
 *   <li><b>No redirects</b>, because a redirect is a second url that nobody checked, and following
 *       one is how the address checks above get bypassed.</li>
 *   <li><b>A timeout and a size ceiling</b>, so a slow or enormous feed costs one member's slot in
 *       the nightly pass rather than the pass itself.</li>
 * </ul>
 *
 * <b>It is a seam first.</b> `Fetcher` is an interface so every test of the nightly pass is a test
 * of what happens when a calendar says a particular thing, without a network -- the same reason
 * `Acme`, `SmtpDns` and `Geocoder` are seams.
 */
public final class CalendarFetch {
  /** the most bytes one feed may be; a year of a busy calendar is well under this */
  public static final int MAX_BYTES = 4 * 1024 * 1024;

  private CalendarFetch() {
  }

  /** what came back, or why nothing did */
  public record Fetched(boolean ok, String body, String problem) {
    public static Fetched no(String problem) {
      return new Fetched(false, null, problem);
    }

    public static Fetched of(String body) {
      return new Fetched(true, body, null);
    }
  }

  /** the seam: everything above the network, so a test can answer without one */
  public interface Fetcher {
    Fetched get(String url, int timeoutSeconds);
  }

  /** the one that refuses, for a community that has not turned this on */
  public static final Fetcher NONE = (url, timeout) ->
      Fetched.no("this server is not fetching calendars");

  /**
   * Turn what somebody pasted into a url this server is willing to ask for, or say why not.
   *
   * `webcal://` is the scheme every calendar application hands out and no HTTP client speaks; it is
   * https with a different word in front, and refusing it would mean refusing the address people
   * actually have in their hands.
   */
  public static String clean(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    if (value.regionMatches(true, 0, "webcal://", 0, 9)) {
      value = "https://" + value.substring(9);
    }
    return value.isEmpty() ? null : value;
  }

  /** is this something we are willing to fetch at all? Returns null when it is fine. */
  public static String refuse(String url) {
    if (url == null || url.isBlank()) {
      return "paste the address of a calendar";
    }
    if (url.length() > 1024) {
      return "that address is too long to be a calendar";
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException ex) {
      return "that is not an address this server can read";
    }
    if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
      return "the address has to start with https:// (or webcal://) -- a calendar address is"
          + " usually a secret, and plain http shows it to everything between here and there";
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return "that address has no host in it";
    }
    return null;
  }

  /**
   * Is this address on the public internet?
   *
   * Resolved first, and every address it resolves to is checked: a name under somebody's control
   * can point at 127.0.0.1, and a check on the text would never notice. A name that will not
   * resolve is refused rather than attempted, because the answer would be the same and the error is
   * more useful now.
   */
  public static String refusePrivate(String host) {
    // the ranges themselves live in PublicAddress, because the push endpoint a browser hands over
    // is the same question asked one seam away and used to have a different answer
    String refused = io.hearth.common.PublicAddress.refuse(host);
    if (refused == null) {
      return null;
    }
    return refused.equals("that address is on a private network")
        ? refused + ", and this server only fetches calendars from the public internet"
        : refused;
  }

  /** the real one: one request, no redirects, bounded in time and in size */
  public static Fetcher overHttps() {
    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    return (url, timeoutSeconds) -> {
      String problem = refuse(url);
      if (problem != null) {
        return Fetched.no(problem);
      }
      URI uri = URI.create(url);
      String privateAddress = refusePrivate(uri.getHost());
      if (privateAddress != null) {
        return Fetched.no(privateAddress);
      }
      try {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .header("Accept", "text/calendar, text/plain;q=0.5")
            .header("User-Agent", "Hearth/1.0 (community calendar availability)")
            .GET()
            .build();
        HttpResponse<byte[]> response =
            client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 301 || response.statusCode() == 302
            || response.statusCode() == 307 || response.statusCode() == 308) {
          return Fetched.no("that address redirects, and a redirect is a second address nobody"
              + " checked -- use the one it points at");
        }
        if (response.statusCode() != 200) {
          return Fetched.no("that calendar answered " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length > MAX_BYTES) {
          return Fetched.no("that calendar is larger than this server will read");
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.contains("BEGIN:VCALENDAR")) {
          return Fetched.no("that address answered, but with something that is not a calendar");
        }
        return Fetched.of(text);
      } catch (IOException | InterruptedException ex) {
        if (ex instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return Fetched.no("could not reach that calendar: " + ex.getClass().getSimpleName());
      }
    };
  }

  /** every check a link has to pass before it is written down, in one call */
  public static String check(String url) {
    String problem = refuse(url);
    if (problem != null) {
      return problem;
    }
    return refusePrivate(URI.create(url).getHost());
  }

  /** the hosts a community is most likely to paste, for the help text */
  public static List<String> examples() {
    return List.of("Google Calendar -- Settings, then \"Secret address in iCal format\"",
        "Apple Calendar -- share a calendar publicly and copy the webcal:// address",
        "Outlook -- Settings, Calendar, Shared calendars, publish as ICS");
  }
}
