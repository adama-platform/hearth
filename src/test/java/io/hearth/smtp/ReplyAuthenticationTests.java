package io.hearth.smtp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What "this message really is from who it says" has to mean before an answer moves a guest list.
 *
 * A calendar reply is a claim about identity arriving over SMTP, and it is the least trusted input
 * this server takes. Invariant 157 is what stands between that and fiction: the `ATTENDEE` in the
 * file must be the sender, and the message must have passed sender authentication. The second half
 * is only worth anything if "passed" means something.
 *
 * It did not. The fallback for a domain with no DMARC record read
 * {@code spf=pass || dkim=pass || contains("=none")}, and the stamped header always carries
 * {@code dmarc=none} for exactly those domains — so the `=none` clause was true in precisely the
 * case the other two were there to decide, and SPF and DKIM were ignored. A forged `From:` on any
 * domain without a DMARC record was authenticated, whatever SPF said about it.
 *
 * The header is read from the message, which is worth being careful about, so the last case here
 * checks that a sender cannot bring their own.
 */
public class ReplyAuthenticationTests {
  private static String stamp(String spf, String dkim, String dmarc) {
    return "mx.example.org; spf=" + spf + " smtp.mailfrom=sender.example"
        + "; dkim=" + dkim + " header.d=sender.example"
        + "; dmarc=" + dmarc + " header.from=sender.example";
  }

  private static Envelope envelopeOf(String message) {
    return new Envelope("bounce@sender.example", java.util.List.of("events@example.org"),
        message.getBytes(java.nio.charset.StandardCharsets.UTF_8), "127.0.0.1",
        "sender.example", "example.org", 0L);
  }

  private static boolean authenticated(String results) {
    return CommunityMailReceiver.authenticated(envelopeOf(
        "Authentication-Results: " + results + "\r\nFrom: someone@sender.example\r\n\r\nbody\r\n"));
  }

  /** the finding: no DMARC record must not mean SPF and DKIM stop counting */
  @Test
  public void aFailingMessageFromADomainWithNoDmarcRecordIsNotAuthenticated() {
    assertFalse("spf failed and there is no DKIM signature -- nothing vouched for this address",
        authenticated(stamp("fail", "none", "none")));
    assertFalse(authenticated(stamp("fail", "fail", "none")));
    assertFalse(authenticated(stamp("softfail", "none", "none")));
  }

  /** and nothing at all vouching for it is not "fine because nobody objected" */
  @Test
  public void aMessageNothingVouchedForIsNotAuthenticated() {
    assertFalse("no SPF record, no signature, no policy is an unknown sender, not a trusted one",
        authenticated(stamp("none", "none", "none")));
    assertFalse("neutral is the owner explicitly making no claim",
        authenticated(stamp("neutral", "none", "none")));
  }

  /**
   * A DNS failure is temporary, never a forgery (invariant 84) — but it is also not a reason to
   * move a guest list. The honest outcome is that the answer does not register and the nudge asks
   * again, which is invariant 158 working as intended.
   */
  @Test
  public void aTemporaryDnsFailureDoesNotAuthenticate() {
    assertFalse(authenticated(stamp("temperror", "none", "temperror")));
  }

  // ---- what must still work ----------------------------------------------------------------------

  @Test
  public void theOrdinaryCasesStillPass() {
    assertTrue("DMARC passing is the strongest thing we can say",
        authenticated(stamp("pass", "pass", "pass")));
    assertTrue("a personal domain with SPF and no DMARC record is the common case",
        authenticated(stamp("pass", "none", "none")));
    assertTrue("a signature is enough on its own, and survives forwarding where SPF does not",
        authenticated(stamp("fail", "pass", "none")));
  }

  @Test
  public void dmarcFailingBeatsAnythingElsePassing() {
    assertFalse("the domain owner aligned it and said no",
        authenticated(stamp("pass", "pass", "fail")));
  }

  /**
   * With the checks switched off there is no header, and that is an operator's decision rather than
   * a reason to distrust one message. It stays as it was.
   */
  @Test
  public void noHeaderAtAllIsTheOperatorsDecision() {
    assertTrue(CommunityMailReceiver.authenticated(
        envelopeOf("From: someone@sender.example\r\n\r\nbody\r\n")));
  }

  /**
   * A sender cannot bring their own verdict.
   *
   * The server stamps its finding onto the front of the message and the header map keeps the first
   * occurrence of a name, so a forged one further down is never the one read. Worth a test rather
   * than a comment, because "first wins" is one `put` away from "last wins".
   */
  @Test
  public void aForgedHeaderFromTheSenderIsNotTheOneRead() {
    assertFalse("the server's own stamp is first and is the one that counts",
        CommunityMailReceiver.authenticated(envelopeOf(
            "Authentication-Results: " + stamp("fail", "fail", "fail") + "\r\n"
                + "Authentication-Results: mx.example.org; spf=pass; dkim=pass; dmarc=pass\r\n"
                + "From: someone@sender.example\r\n\r\nbody\r\n")));
  }
}
