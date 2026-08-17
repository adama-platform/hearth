package io.hearth.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.Verbose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One signed, encrypted POST to a push service.
 *
 * The payload is small on purpose. A push message travels through Google's or Mozilla's or Apple's
 * infrastructure, and although it is encrypted end to end, the *fact* of it is not -- so what goes
 * in is a title, a line, and where to go, never the contents of a support-group post. Anything
 * private stays behind the session, which is why the notification's job is to bring somebody back
 * rather than to tell them the thing.
 */
public class WebPush {
  private static final Logger LOG = LoggerFactory.getLogger(WebPush.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  /** push services cap the payload; this leaves room for the encryption overhead */
  private static final int MAX_PAYLOAD = 3000;

  private final HttpClient http;
  private final Verbose verbose;

  public WebPush(Verbose verbose) {
    this.verbose = verbose;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * What a browser will show, where clicking it goes, and who it is for.
   *
   * @param userId who this is for.
   *
   * A browser can hold subscriptions for two people -- a shared laptop, somebody who signed out and
   * in as somebody else -- and a service worker has no session to ask. Without this, a notification
   * meant for one of them can be tapped by the other and open a page that is not theirs. It is an
   * id and nothing else: it says who, never what.
   */
  public record Message(String title, String body, String url, String tag, long userId) {
    public Message(String title, String body, String url, String tag) {
      this(title, body, url, tag, 0);
    }
  }

  /** what happened, and whether the subscription is worth keeping */
  public record Outcome(boolean delivered, boolean gone, String detail) {
  }

  public Outcome send(PushSubs.Sub sub, Message message, String subject) {
    try {
      ObjectNode payload = JSON.createObjectNode();
      payload.put("title", message.title());
      payload.put("body", message.body());
      payload.put("url", message.url());
      // the tag is what makes a notification replace rather than stack: three replies in one thread
      // should be one line on a lock screen, not three
      payload.put("tag", message.tag() == null ? "hearth" : message.tag());
      payload.put("for", message.userId());
      byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
      if (plaintext.length > MAX_PAYLOAD) {
        return new Outcome(false, false, "payload too large");
      }

      byte[] body = PushCrypto.encrypt(plaintext, PushCrypto.unb64(sub.p256dh()),
          PushCrypto.unb64(sub.auth()), sub.keys(), PushCrypto.randomBytes(16));

      HttpRequest request = HttpRequest.newBuilder(URI.create(sub.endpoint()))
          .timeout(Duration.ofSeconds(15))
          .header("TTL", "86400")
          .header("Content-Encoding", "aes128gcm")
          .header("Content-Type", "application/octet-stream")
          .header("Urgency", "normal")
          .header("Authorization",
              Vapid.authorization(sub.endpoint(), subject, sub.keys(), System.currentTimeMillis()))
          .POST(HttpRequest.BodyPublishers.ofByteArray(body))
          .build();

      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status / 100 == 2) {
        verbose.detail(() -> "push: delivered to " + host(sub.endpoint()));
        return new Outcome(true, false, "delivered");
      }
      // 404 and 410 are the push service saying this browser is gone for good. Everything else --
      // a 429, a 500, a bad afternoon at Google -- is worth another try later.
      boolean gone = status == 404 || status == 410;
      String detail = status + (response.body() == null || response.body().isBlank()
          ? "" : ": " + response.body().substring(0, Math.min(120, response.body().length())));
      verbose.detail(() -> "push: " + host(sub.endpoint()) + " said " + detail);
      return new Outcome(false, gone, detail);
    } catch (Exception ex) {
      LOG.warn("push-failed endpoint={}", host(sub.endpoint()), ex);
      return new Outcome(false, false, "could not reach the push service: " + ex.getMessage());
    }
  }

  /** the host only, because an endpoint is a capability and does not belong in a log */
  private static String host(String endpoint) {
    try {
      return URI.create(endpoint).getHost();
    } catch (Exception ex) {
      return "unknown";
    }
  }
}
