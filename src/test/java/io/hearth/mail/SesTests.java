package io.hearth.mail;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import io.hearth.common.Verbose;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sending real email, and the signature that makes it possible.
 *
 * SigV4 fails closed and silently: a mis-signed request comes back as a flat 403 with no hint about
 * which of a dozen canonical strings was wrong, and no amount of squinting at the code tells you
 * which. So the signing is checked against AWS's own published example, which is the only way to
 * know it is right without an account.
 */
public class SesTests {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static SesConfig config(String json) throws Exception {
    return new SesConfig(new ConfigObject((ObjectNode) JSON.readTree(json), "ses"));
  }

  // ---- the signature ----------------------------------------------------------------------------

  @Test
  public void theSignatureMatchesAmazonsWorkedExample() {
    // From the AWS documentation's SigV4 test suite: a GET with these exact credentials, headers and
    // clock has one correct answer. If this drifts, every send starts failing with a bare 403.
    Instant when = ZonedDateTime.of(2015, 8, 30, 12, 36, 0, 0, ZoneOffset.UTC).toInstant();
    Map<String, String> signed = new SignatureV4(
        "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        "us-east-1", "service", "GET", "example.amazonaws.com", "/", when)
        .withBody(new byte[0])
        .sign();

    String authorization = signed.get("Authorization");
    assertNotNull(authorization);
    assertTrue(authorization, authorization.startsWith(
        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request"));
    assertTrue("the signed header list is part of what is hashed",
        authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
    assertEquals("and the date is the one that was signed", "20150830T123600Z", signed.get("X-Amz-Date"));
  }

  @Test
  public void theEmptyBodyHashIsTheKnownConstant() {
    // every AWS example uses it, and getting it wrong breaks only the requests without a body
    assertEquals("e3b0c44298fc1c14 9afbf4c8996fb924 27ae41e4649b934c a495991b7852b855".replace(" ", ""),
        SignatureV4.hex(SignatureV4.sha256(new byte[0])));
  }

  @Test
  public void theSignatureChangesWithEverythingItShould() {
    Instant when = Instant.parse("2026-01-01T00:00:00Z");
    String base = signatureOf("key", "secret", "us-east-1", when, "body".getBytes());
    assertFalse("a different secret", base.equals(signatureOf("key", "other", "us-east-1", when, "body".getBytes())));
    assertFalse("a different region", base.equals(signatureOf("key", "secret", "eu-west-1", when, "body".getBytes())));
    assertFalse("a different body", base.equals(signatureOf("key", "secret", "us-east-1", when, "other".getBytes())));
    assertFalse("a different minute",
        base.equals(signatureOf("key", "secret", "us-east-1", when.plusSeconds(60), "body".getBytes())));
  }

  private static String signatureOf(String id, String secret, String region, Instant when, byte[] body) {
    return new SignatureV4(id, secret, region, "ses", "POST",
        "email." + region + ".amazonaws.com", "/v2/email/outbound-emails", when)
        .withHeader("Content-Type", "application/json")
        .withBody(body)
        .sign()
        .get("Authorization");
  }

  @Test
  public void theHostFollowsTheRegion() throws Exception {
    // adama's SES caller passes the region into the URL and a hardcoded us-east-2 into the
    // signature, so it works in one region and fails opaquely everywhere else. Not copied.
    assertEquals("email.eu-west-1.amazonaws.com",
        config("{\"enabled\":false,\"region\":\"eu-west-1\"}").host());
    assertEquals("email.us-east-1.amazonaws.com", SesConfig.off().host());
  }

  // ---- the config ------------------------------------------------------------------------------

  @Test
  public void offIsTheDefaultAndNeedsNothing() throws Exception {
    SesConfig ses = config("{}");
    assertFalse(ses.enabled);
    assertTrue(ses.describe().contains("terminal"));
  }

  @Test
  public void enablingItRequiresEverythingASendNeeds() throws Exception {
    // checked at boot rather than at the moment somebody is waiting for a sign-in code
    for (String missing : new String[]{
        "{\"enabled\":true}",
        "{\"enabled\":true,\"access-key-id\":\"a\"}",
        "{\"enabled\":true,\"access-key-id\":\"a\",\"secret-access-key\":\"s\"}"}) {
      try {
        config(missing);
        fail("expected a refusal for " + missing);
      } catch (ConfigException ex) {
        assertTrue(ex.getMessage(), ex.getMessage().contains("required"));
      }
    }
  }

  @Test
  public void aSenderThatIsNotAnAddressIsRefused() {
    try {
      config("{\"enabled\":true,\"access-key-id\":\"a\",\"secret-access-key\":\"s\",\"from\":\"nope\"}");
      fail("expected a refusal");
    } catch (Exception ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("verified"));
    }
  }

  @Test
  public void aRegionThatIsNotOneIsRefused() {
    try {
      config("{\"enabled\":true,\"access-key-id\":\"a\",\"secret-access-key\":\"s\","
          + "\"from\":\"a@b.test\",\"region\":\"the-cloud\"}");
      fail("expected a refusal");
    } catch (Exception ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("region"));
    }
  }

  @Test
  public void repliesGoToTheSenderUnlessToldOtherwise() throws Exception {
    SesConfig ses = config("{\"enabled\":true,\"access-key-id\":\"a\",\"secret-access-key\":\"s\","
        + "\"from\":\"no-reply@example.org\"}");
    assertEquals("no-reply@example.org", ses.replyToOr());

    SesConfig elsewhere = config("{\"enabled\":true,\"access-key-id\":\"a\",\"secret-access-key\":\"s\","
        + "\"from\":\"no-reply@example.org\",\"reply-to\":\"hello@example.org\"}");
    assertEquals("hello@example.org", elsewhere.replyToOr());
  }

  @Test
  public void anUnknownKeyIsRefusedLikeEverywhereElse() {
    try {
      config("{\"enabled\":true,\"acess-key-id\":\"typo\"}");
      fail("expected a refusal");
    } catch (Exception ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("unknown key"));
    }
  }

  // ---- dispatch --------------------------------------------------------------------------------

  @Test
  public void aDomainWithoutSesFallsBackToTheTerminal() {
    // the normal state of a box where one community is live and another is being set up
    Mailers mailers = new Mailers(new DevBoxMailer());
    assertTrue(mailers.forDomain("anything.test") instanceof DevBoxMailer);
    assertEquals(0, mailers.realCount());
  }

  @Test
  public void sendingWithoutConfigurationFailsRatherThanPretending() {
    AmazonSes ses = new AmazonSes(SesConfig.off(), Verbose.OFF);
    Mailer.Outcome outcome = ses.sendLoginCode(
        new Mailer.Envelope("example.org", "Example", "you@example.com", null), "123456");
    assertFalse(outcome.delivered());
    assertTrue(outcome.detail(), outcome.detail().contains("not configured"));
  }

  @Test
  public void anAwsErrorBodyIsReducedToTheUsefulSentence() {
    assertEquals("Email address is not verified.",
        AmazonSes.summarize("{\"message\":\"Email address is not verified.\"}"));
    assertEquals("Email address is not verified.",
        AmazonSes.summarize("{\"Message\":\"Email address is not verified.\"}"));
    assertEquals("no detail", AmazonSes.summarize(""));
    assertTrue(AmazonSes.summarize("not json at all").contains("not json"));
  }
}
