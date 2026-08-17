package io.hearth.mail;

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
import java.util.Map;

/**
 * Sending real email through Amazon SES.
 *
 * Adapted from adama's service, cut down to what a community server needs: one endpoint, one
 * operation, no async framework and no AWS SDK. The SDK is forty megabytes of dependency to make one
 * signed POST, which is not a trade a single-jar server should make -- so the signing is
 * {@link SignatureV4} and the transport is the JDK's HTTP client.
 *
 * Every message goes out as both text and HTML. A sign-in code that arrives as an unreadable wall
 * in a plain-text client is a person who cannot sign in, and a code that arrives as HTML-only is one
 * a screen reader has to fight with.
 *
 * **Sending is synchronous and on the request thread**, which is a deliberate limit rather than an
 * oversight. At this scale it is a handful of messages a day, and the alternative -- a queue, a
 * worker, retries, a dead letter list -- is a great deal of machinery to make a rare event slightly
 * faster. If it ever matters, the seam is here: this class is one implementation of {@link Mailer}.
 */
public class AmazonSes implements Mailer {
  private static final Logger LOG = LoggerFactory.getLogger(AmazonSes.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PATH = "/v2/email/outbound-emails";

  private final SesConfig config;
  private final Verbose verbose;
  private final HttpClient http;

  public AmazonSes(SesConfig config, Verbose verbose) {
    this.config = config;
    this.verbose = verbose;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public SesConfig config() {
    return config;
  }

  @Override
  public Outcome sendRegistrationCode(Envelope envelope, String code) {
    return send(envelope, Messages.registrationCode(envelope, code, minutes(code)));
  }

  @Override
  public Outcome sendLoginCode(Envelope envelope, String code) {
    return send(envelope, Messages.loginCode(envelope, code, minutes(code)));
  }

  @Override
  public Outcome sendPasswordReset(Envelope envelope, String code, String link) {
    return send(envelope, Messages.passwordReset(envelope, code, link, minutes(code)));
  }

  @Override
  public Outcome sendTwoFactorCode(Envelope envelope, String code) {
    return send(envelope, Messages.twoFactorCode(envelope, code, minutes(code)));
  }

  @Override
  public Outcome sendPasswordChanged(Envelope envelope) {
    return send(envelope, Messages.passwordChanged(envelope));
  }

  @Override
  public Outcome sendInvite(Envelope envelope, InviteMail.Invitation invitation) {
    return send(envelope, Messages.invite(envelope, invitation));
  }

  @Override
  public Outcome sendBoardNotice(Envelope envelope, Notice notice) {
    return send(envelope, Messages.boardNotice(envelope, notice));
  }

  @Override
  public Outcome sendDigest(Envelope envelope, Digest digest) {
    return send(envelope, Messages.digest(envelope, digest));
  }

  @Override
  public Outcome sendEventInvite(Envelope envelope, EventInvite invite) {
    if (!config.enabled) {
      return Outcome.failed("SES is not configured for " + envelope.domain());
    }
    Messages.Built message = Messages.eventInvite(envelope, invite);
    try {
      // Raw rather than Simple, because this message has a shape: `text/calendar` as a third
      // alternative is what turns an email into accept/maybe/decline buttons, and the simple API
      // can express a subject and two bodies and nothing else.
      byte[] mime = Mime.withCalendar(fromLine(envelope), envelope.email(), message.subject(),
          message.text(), message.html(), invite.ics(), invite.method(), "invite.ics",
          io.hearth.auth.Tokens.newHandle());
      ObjectNode request = JSON.createObjectNode();
      request.put("FromEmailAddress", fromLine(envelope));
      // the reply address is the one answers come back to, and for an invitation that is an address
      // this server *receives* at rather than the one it sends from
      request.putArray("ReplyToAddresses").add(invite.replyTo());
      request.putObject("Destination").putArray("ToAddresses").add(envelope.email());
      request.putObject("Content").putObject("Raw")
          .put("Data", java.util.Base64.getEncoder().encodeToString(mime));
      return post(envelope, message.subject(),
          request.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      LOG.error("ses-send-failed", ex);
      return Outcome.failed("could not reach SES: " + ex.getMessage());
    }
  }

  /**
   * The one place a message actually leaves.
   *
   * Every flow arrives here already built, in both halves. That is the whole reason {@link Messages}
   * exists: this class knows how to sign a request to Amazon, and nothing about what a community's
   * sign-in code should say.
   */
  private Outcome send(Envelope envelope, Messages.Built message) {
    if (!config.enabled) {
      return Outcome.failed("SES is not configured for " + envelope.domain());
    }
    try {
      return post(envelope, message.subject(),
          payload(envelope, message.subject(), message.text(), message.html()));
    } catch (Exception ex) {
      LOG.error("ses-send-failed", ex);
      return Outcome.failed("could not reach SES: " + ex.getMessage());
    }
  }

  /** the one place a signed request actually goes out */
  private Outcome post(Envelope envelope, String subject, byte[] body) {
    try {
      Map<String, String> headers = new SignatureV4(config.accessKeyId, config.secretAccessKey,
          config.region, "ses", "POST", config.host(), PATH)
          .withHeader("Content-Type", "application/json")
          .withBody(body)
          .sign();

      HttpRequest.Builder request = HttpRequest.newBuilder(
              URI.create("https://" + config.host() + PATH))
          .timeout(Duration.ofSeconds(20))
          .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      for (Map.Entry<String, String> header : headers.entrySet()) {
        if (header.getKey().equalsIgnoreCase("Host")) {
          // the JDK client sets Host itself and refuses to have it set; it is signed either way
          continue;
        }
        request.header(header.getKey(), header.getValue());
      }

      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 == 2) {
        verbose.say("ses: sent '" + subject + "' to " + envelope.email());
        return Outcome.ok("sent via SES");
      }
      // SES says why in the body, and that sentence is the whole diagnostic -- an unverified
      // sending address and a wrong key look identical without it
      String why = summarize(response.body());
      LOG.warn("ses-refused status={} body={}", response.statusCode(), response.body());
      verbose.say("ses: refused with " + response.statusCode() + " -- " + why);
      return Outcome.failed("SES said " + response.statusCode() + ": " + why);
    } catch (Exception ex) {
      LOG.error("ses-send-failed", ex);
      return Outcome.failed("could not reach SES: " + ex.getMessage());
    }
  }

  /**
   * How long a code lasts, in minutes, for the sentence that says so.
   *
   * Ten, which is what `code-lifetime-seconds` defaults to. It is passed rather than looked up
   * because this class knows how to sign a request to Amazon and nothing about login policy, and a
   * message that said the wrong number would be worse than one that said nothing.
   */
  private static long minutes(String code) {
    return 10;
  }

  /** the JSON SES wants: a subject, a text half and an HTML half, all UTF-8 */
  private byte[] payload(Envelope envelope, String subject, String text, String html) {
    ObjectNode request = JSON.createObjectNode();
    request.put("FromEmailAddress", fromLine(envelope));
    request.putArray("ReplyToAddresses").add(config.replyToOr());
    request.putObject("Destination").putArray("ToAddresses").add(envelope.email());
    ObjectNode content = request.putObject("Content").putObject("Simple");
    ObjectNode subjectNode = content.putObject("Subject");
    subjectNode.put("Data", subject);
    subjectNode.put("Charset", "UTF-8");
    ObjectNode bodyNode = content.putObject("Body");
    ObjectNode textNode = bodyNode.putObject("Text");
    textNode.put("Data", text);
    textNode.put("Charset", "UTF-8");
    ObjectNode htmlNode = bodyNode.putObject("Html");
    htmlNode.put("Data", html);
    htmlNode.put("Charset", "UTF-8");
    return request.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** "Example Community <no-reply@example.org>", with anything header-shaped taken out of the name */
  private String fromLine(Envelope envelope) {
    String name = config.fromName == null || config.fromName.isBlank()
        ? envelope.communityName() : config.fromName;
    String clean = name.replaceAll("[\\r\\n\"<>]", "").trim();
    return clean.isEmpty() ? config.from : clean + " <" + config.from + ">";
  }

  /** the useful sentence out of an AWS error body */
  static String summarize(String body) {
    if (body == null || body.isBlank()) {
      return "no detail";
    }
    try {
      var node = JSON.readTree(body);
      String message = node.path("message").asText(node.path("Message").asText(""));
      if (!message.isBlank()) {
        return message;
      }
    } catch (Exception ex) {
      // not JSON; the raw body is better than nothing
    }
    return body.length() > 200 ? body.substring(0, 200) + "…" : body;
  }
}
