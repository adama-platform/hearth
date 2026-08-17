package io.hearth.smtp;

import io.hearth.common.Verbose;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPF, DKIM and DMARC, against a nameserver that says what the test tells it to.
 *
 * The DKIM tests sign for real -- a keypair is generated, a message is signed with it, and the
 * public half is published in the fake DNS. Asserting against a hand-written expected signature
 * would be asserting that this code agrees with itself, which is the property a wrong
 * implementation also has; signing and verifying proves the canonicalization is at least
 * self-consistent, and the tampering tests prove it is actually checking something.
 */
public class SenderCheckTests {
  private static final Verbose QUIET = Verbose.capturing().verbose;

  private static InetAddress ip(String text) throws Exception {
    return InetAddress.getByName(text);
  }

  // ---- SPF ---------------------------------------------------------------------------------

  @Test
  public void aListedAddressPasses() throws Exception {
    FakeDns dns = new FakeDns().txt("sender.example", "v=spf1 ip4:198.51.100.7 -all");
    assertEquals(AuthResult.Status.pass,
        Spf.check(ip("198.51.100.7"), "sender.example", "helo.example", dns));
  }

  @Test
  public void anUnlistedAddressFailsWhenTheRecordSaysMinusAll() throws Exception {
    FakeDns dns = new FakeDns().txt("sender.example", "v=spf1 ip4:198.51.100.7 -all");
    assertEquals(AuthResult.Status.fail,
        Spf.check(ip("203.0.113.9"), "sender.example", "helo.example", dns));
  }

  @Test
  public void theQualifierIsWhatDecidesHowBadAMissIs() throws Exception {
    assertEquals("~all is the owner being unsure, not accusing", AuthResult.Status.softfail,
        Spf.check(ip("203.0.113.9"), "s.example", "h",
            new FakeDns().txt("s.example", "v=spf1 ip4:198.51.100.7 ~all")));
    assertEquals("?all is the owner declining to say", AuthResult.Status.neutral,
        Spf.check(ip("203.0.113.9"), "s.example", "h",
            new FakeDns().txt("s.example", "v=spf1 ip4:198.51.100.7 ?all")));
    assertEquals("no all at all is neutral by the RFC, not fail", AuthResult.Status.neutral,
        Spf.check(ip("203.0.113.9"), "s.example", "h",
            new FakeDns().txt("s.example", "v=spf1 ip4:198.51.100.7")));
  }

  @Test
  public void noRecordIsNoneRatherThanFail() throws Exception {
    assertEquals("most domains publish nothing, and that is not suspicious",
        AuthResult.Status.none,
        Spf.check(ip("203.0.113.9"), "nothing.example", "h", new FakeDns()));
  }

  @Test
  public void aCidrRangeIsHonoured() throws Exception {
    FakeDns dns = new FakeDns().txt("s.example", "v=spf1 ip4:198.51.100.0/24 -all");
    assertEquals(AuthResult.Status.pass, Spf.check(ip("198.51.100.200"), "s.example", "h", dns));
    assertEquals(AuthResult.Status.fail, Spf.check(ip("198.51.101.1"), "s.example", "h", dns));
  }

  @Test
  public void anIpv4SenderIsNotCoveredByAnIpv6Mechanism() throws Exception {
    FakeDns dns = new FakeDns().txt("s.example", "v=spf1 ip6:2001:db8::/32 -all");
    assertEquals(AuthResult.Status.fail, Spf.check(ip("198.51.100.7"), "s.example", "h", dns));
    assertEquals(AuthResult.Status.pass, Spf.check(ip("2001:db8::1"), "s.example", "h", dns));
  }

  @Test
  public void aAndMxMechanismsResolve() throws Exception {
    FakeDns dns = new FakeDns()
        .txt("s.example", "v=spf1 a mx -all")
        .address("s.example", "198.51.100.7")
        .mx("s.example", "mail.s.example")
        .address("mail.s.example", "198.51.100.8");
    assertEquals(AuthResult.Status.pass, Spf.check(ip("198.51.100.7"), "s.example", "h", dns));
    assertEquals(AuthResult.Status.pass, Spf.check(ip("198.51.100.8"), "s.example", "h", dns));
    assertEquals(AuthResult.Status.fail, Spf.check(ip("198.51.100.9"), "s.example", "h", dns));
  }

  @Test
  public void includePullsInAnotherRecord() throws Exception {
    FakeDns dns = new FakeDns()
        .txt("s.example", "v=spf1 include:provider.example -all")
        .txt("provider.example", "v=spf1 ip4:198.51.100.7 -all");
    assertEquals(AuthResult.Status.pass, Spf.check(ip("198.51.100.7"), "s.example", "h", dns));
  }

  @Test
  public void aFailureInsideAnIncludeDoesNotFailTheOuterRecord() throws Exception {
    // the rule everybody gets backwards: include contributes a pass, never a fail
    FakeDns dns = new FakeDns()
        .txt("s.example", "v=spf1 include:provider.example ip4:203.0.113.9 -all")
        .txt("provider.example", "v=spf1 ip4:198.51.100.7 -all");
    assertEquals(AuthResult.Status.pass, Spf.check(ip("203.0.113.9"), "s.example", "h", dns));
  }

  @Test
  public void aRecordThatIncludesItselfCannotSpinForever() throws Exception {
    // an unbounded include is amplification: every message would make this server hammer
    // somebody else's nameserver on the sender's behalf
    FakeDns dns = new FakeDns().txt("loop.example", "v=spf1 include:loop.example -all");
    assertEquals(AuthResult.Status.permerror,
        Spf.check(ip("198.51.100.7"), "loop.example", "h", dns));
    assertTrue("and the budget is spent, not exceeded wildly",
        dns.lookups() <= Spf.MAX_LOOKUPS + 2);
  }

  @Test
  public void twoRecordsAreAPermanentErrorRatherThanAGuess() throws Exception {
    FakeDns dns = new FakeDns()
        .txt("s.example", "v=spf1 ip4:198.51.100.7 -all", "v=spf1 -all");
    assertEquals("choosing between them is the ambiguity an attacker wants us to resolve",
        AuthResult.Status.permerror, Spf.check(ip("198.51.100.7"), "s.example", "h", dns));
  }

  @Test
  public void anEmptySenderIsCheckedAgainstTheHeloName() throws Exception {
    FakeDns dns = new FakeDns().txt("relay.example", "v=spf1 ip4:198.51.100.7 -all");
    assertEquals("which is the only way a bounce can be checked at all",
        AuthResult.Status.pass, Spf.check(ip("198.51.100.7"), "", "relay.example", dns));
  }

  @Test
  public void redirectReplacesTheResult() throws Exception {
    FakeDns dns = new FakeDns()
        .txt("s.example", "v=spf1 redirect=other.example")
        .txt("other.example", "v=spf1 ip4:198.51.100.7 -all");
    assertEquals(AuthResult.Status.pass, Spf.check(ip("198.51.100.7"), "s.example", "h", dns));
    assertEquals(AuthResult.Status.fail, Spf.check(ip("203.0.113.1"), "s.example", "h", dns));
  }

  // ---- DKIM ---------------------------------------------------------------------------------

  @Test
  public void aRealSignatureVerifies() throws Exception {
    Signed signed = sign("example.org", "sel", "relaxed/relaxed");
    Dkim.Verified result = Dkim.verify(signed.message, signed.dns);
    assertEquals(result.toString(), AuthResult.Status.pass, result.status());
    assertEquals("example.org", result.domain());
  }

  @Test
  public void simpleCanonicalizationVerifiesToo() throws Exception {
    Signed signed = sign("example.org", "sel", "simple/simple");
    assertEquals(AuthResult.Status.pass, Dkim.verify(signed.message, signed.dns).status());
  }

  @Test
  public void changingTheBodyBreaksTheSignature() throws Exception {
    Signed signed = sign("example.org", "sel", "relaxed/relaxed");
    String tampered = new String(signed.message, StandardCharsets.UTF_8)
        .replace("Are you coming?", "Send money instead.");
    Dkim.Verified result =
        Dkim.verify(tampered.getBytes(StandardCharsets.UTF_8), signed.dns);
    assertEquals("this is the entire point of the exercise",
        AuthResult.Status.fail, result.status());
  }

  @Test
  public void changingASignedHeaderBreaksTheSignature() throws Exception {
    Signed signed = sign("example.org", "sel", "relaxed/relaxed");
    String tampered = new String(signed.message, StandardCharsets.UTF_8)
        .replace("Subject: Dinner", "Subject: Urgent");
    assertEquals(AuthResult.Status.fail,
        Dkim.verify(tampered.getBytes(StandardCharsets.UTF_8), signed.dns).status());
  }

  @Test
  public void noSignatureIsNoneRatherThanFail() {
    String message = "From: a@example.org\r\nSubject: hi\r\n\r\nbody\r\n";
    assertEquals(AuthResult.Status.none,
        Dkim.verify(message.getBytes(StandardCharsets.UTF_8), new FakeDns()).status());
  }

  @Test
  public void noPublishedKeyIsTemporaryRatherThanAForgery() throws Exception {
    Signed signed = sign("example.org", "sel", "relaxed/relaxed");
    assertEquals("treating an unreachable nameserver as a forgery bounces real mail",
        AuthResult.Status.temperror, Dkim.verify(signed.message, new FakeDns()).status());
  }

  @Test
  public void anEmptyPublicKeyIsARevocationAndFails() throws Exception {
    Signed signed = sign("example.org", "sel", "relaxed/relaxed");
    FakeDns dns = new FakeDns().txt("sel._domainkey.example.org", "v=DKIM1; k=rsa; p=");
    assertEquals(AuthResult.Status.fail, Dkim.verify(signed.message, dns).status());
  }

  @Test
  public void aSignatureThatDoesNotCoverFromIsRefused() throws Exception {
    Signed signed = sign("example.org", "sel", "relaxed/relaxed", "subject:date");
    assertEquals("a signature not covering From is worth nothing and looks like everything",
        AuthResult.Status.permerror, Dkim.verify(signed.message, signed.dns).status());
  }

  @Test
  public void blankingTheSignatureDoesNotEatTheBodyHash() {
    String header = " v=1; a=rsa-sha256; d=example.org; s=sel;"
        + " h=from:subject; bh=AAAABBBBCCCC=; b=SIGNATUREHERE==";
    String blanked = Dkim.emptySignature(header);
    assertTrue("bh= survives, or every signature fails in a way that looks like tampering",
        blanked.contains("bh=AAAABBBBCCCC="));
    assertTrue(blanked.contains("b=;") || blanked.endsWith("b="));
    assertFalse(blanked.contains("SIGNATUREHERE"));
  }

  // ---- DMARC --------------------------------------------------------------------------------

  @Test
  public void alignmentIsWhatMakesTheOtherChecksMeanSomething() {
    FakeDns dns = new FakeDns().txt("_dmarc.bank.example", "v=DMARC1; p=reject");
    // the attack DMARC exists to stop: their own domain passes SPF, and the From says the bank
    Dmarc.Policy policy = Dmarc.check("bank.example", AuthResult.Status.pass, "spammer.example",
        AuthResult.Status.none, null, dns);
    assertEquals(AuthResult.Status.fail, policy.status());
    assertEquals("reject", policy.policy());
  }

  @Test
  public void anAlignedSpfPassIsEnough() {
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject");
    assertEquals(AuthResult.Status.pass, Dmarc.check("example.org", AuthResult.Status.pass,
        "example.org", AuthResult.Status.none, null, dns).status());
  }

  @Test
  public void anAlignedDkimPassIsEnoughEvenWhenSpfFailed() {
    // exactly what happens to a message that went through a mailing list
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject");
    assertEquals("which is why forwarding does not break DMARC", AuthResult.Status.pass,
        Dmarc.check("example.org", AuthResult.Status.fail, "list.example",
            AuthResult.Status.pass, "example.org", dns).status());
  }

  @Test
  public void relaxedAlignmentAllowsASubdomain() {
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject");
    assertEquals(AuthResult.Status.pass, Dmarc.check("example.org", AuthResult.Status.pass,
        "mail.example.org", AuthResult.Status.none, null, dns).status());
  }

  @Test
  public void strictAlignmentDoesNot() {
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject; aspf=s");
    assertEquals(AuthResult.Status.fail, Dmarc.check("example.org", AuthResult.Status.pass,
        "mail.example.org", AuthResult.Status.none, null, dns).status());
  }

  @Test
  public void aPolicyAtTheOrganizationalDomainCoversSubdomains() {
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject");
    Dmarc.Policy policy = Dmarc.check("mail.example.org", AuthResult.Status.fail, "elsewhere.example",
        AuthResult.Status.none, null, dns);
    assertEquals("which is how almost everybody publishes it", AuthResult.Status.fail,
        policy.status());
    assertEquals("reject", policy.policy());
  }

  @Test
  public void aSubdomainPolicyIsUsedWhenWeFellBackToTheParent() {
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject; sp=none");
    assertEquals("none", Dmarc.check("mail.example.org", AuthResult.Status.fail, "elsewhere.example",
        AuthResult.Status.none, null, dns).policy());
  }

  @Test
  public void noPolicyIsNone() {
    assertEquals(AuthResult.Status.none, Dmarc.check("example.org", AuthResult.Status.fail,
        "elsewhere.example", AuthResult.Status.none, null, new FakeDns()).status());
  }

  @Test
  public void aDnsProblemUnderneathIsTemporaryRatherThanAForgery() {
    FakeDns dns = new FakeDns().txt("_dmarc.example.org", "v=DMARC1; p=reject");
    assertEquals(AuthResult.Status.temperror, Dmarc.check("example.org",
        AuthResult.Status.temperror, "example.org", AuthResult.Status.none, null, dns).status());
  }

  @Test
  public void theOrganizationalDomainIsConservativeWhenItGuesses() {
    assertEquals("example.org", Dmarc.organizationalDomain("mail.example.org"));
    assertEquals("example.org", Dmarc.organizationalDomain("example.org"));
    assertEquals("example.co.uk", Dmarc.organizationalDomain("mail.example.co.uk"));
  }

  // ---- what the From header actually says ------------------------------------------------------

  @Test
  public void theFromDomainIsReadFromTheAngleBracketsAndNotTheDisplayName() {
    String message = "From: \"billing@bank.example\" <spammer@evil.example>\r\n"
        + "Subject: hi\r\n\r\nbody\r\n";
    assertEquals("a display name shaped like an address is a real trick",
        "evil.example", SenderCheck.headerFromDomain(message.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void aPlainFromHeaderWorksToo() {
    String message = "From: somebody@example.org\r\nSubject: hi\r\n\r\nbody\r\n";
    assertEquals("example.org",
        SenderCheck.headerFromDomain(message.getBytes(StandardCharsets.UTF_8)));
  }

  // ---- the judgement -----------------------------------------------------------------------

  @Test
  public void aMessageFailingAPublishedRejectPolicyIsRefused() throws Exception {
    FakeDns dns = new FakeDns()
        .txt("evil.example", "v=spf1 ip4:198.51.100.7 -all")
        .txt("_dmarc.bank.example", "v=DMARC1; p=reject");
    String message = "From: billing@bank.example\r\nSubject: Urgent\r\n\r\nSend money\r\n";

    SenderCheck.Judgement judgement = check(true).judge(ip("198.51.100.7"),
        "spammer@evil.example", "evil.example", message.getBytes(StandardCharsets.UTF_8), dns);
    assertFalse("the domain owner asked the world to refuse this", judgement.deliver());
    assertTrue(judgement.reason(), judgement.reason().contains("p=reject"));
  }

  @Test
  public void anSpfFailureAloneIsDeliveredAndMarked() throws Exception {
    // refusing on SPF alone rejects every message that came through a mailing list
    FakeDns dns = new FakeDns().txt("sender.example", "v=spf1 ip4:198.51.100.7 -all");
    String message = "From: ana@sender.example\r\nSubject: hi\r\n\r\nhello\r\n";

    SenderCheck.Judgement judgement = check(true).judge(ip("203.0.113.9"),
        "ana@sender.example", "sender.example", message.getBytes(StandardCharsets.UTF_8), dns);
    assertTrue("a community whose mail stops working is worse off", judgement.deliver());
    assertEquals(AuthResult.Status.fail, judgement.result().spf());
  }

  @Test
  public void enforcementCanBeTurnedOffWithoutTurningOffTheChecks() throws Exception {
    FakeDns dns = new FakeDns()
        .txt("evil.example", "v=spf1 ip4:198.51.100.7 -all")
        .txt("_dmarc.bank.example", "v=DMARC1; p=reject");
    String message = "From: billing@bank.example\r\nSubject: Urgent\r\n\r\nSend money\r\n";

    SenderCheck.Judgement judgement = check(false).judge(ip("198.51.100.7"),
        "spammer@evil.example", "evil.example", message.getBytes(StandardCharsets.UTF_8), dns);
    assertTrue(judgement.deliver());
    assertEquals("but it still says what it found", AuthResult.Status.fail,
        judgement.result().dmarc());
  }

  @Test
  public void everyMessageIsStampedWithWhatWasFound() {
    AuthResult result = new AuthResult(AuthResult.Status.pass, "sender.example",
        AuthResult.Status.pass, "example.org", AuthResult.Status.pass, "example.org", "reject");
    byte[] stamped = SenderCheck.stamp("From: a@example.org\r\n\r\nbody\r\n"
        .getBytes(StandardCharsets.UTF_8), result, "mx.hearth.example");
    String text = new String(stamped, StandardCharsets.UTF_8);

    assertTrue("prepended, where every mail system puts its own findings",
        text.startsWith("Authentication-Results: mx.hearth.example"));
    assertTrue(text.contains("spf=pass"));
    assertTrue(text.contains("dkim=pass"));
    assertTrue(text.contains("dmarc=pass"));
    assertTrue("and the original message is untouched behind it",
        text.contains("From: a@example.org"));
  }

  @Test
  public void aBrokenCheckDeliversRatherThanEatingTheMail() throws Exception {
    SmtpDns broken = new SmtpDns() {
      @Override
      public String[] txt(String name) {
        throw new IllegalStateException("the resolver exploded");
      }

      @Override
      public String[] mx(String name) {
        throw new IllegalStateException("the resolver exploded");
      }

      @Override
      public java.util.List<InetAddress> addresses(String name) {
        throw new IllegalStateException("the resolver exploded");
      }
    };
    SenderCheck.Judgement judgement = check(true).judge(ip("198.51.100.7"), "a@sender.example",
        "sender.example", "From: a@sender.example\r\n\r\nhi\r\n".getBytes(StandardCharsets.UTF_8),
        broken);
    assertTrue("an outage in our validation is our problem, not the sender's",
        judgement.deliver());
  }

  @Test
  public void turningTheChecksOffSkipsThemEntirely() throws Exception {
    FakeDns dns = new FakeDns();
    SmtpConfig config = SmtpTests.configWith("\"check-senders\":false");
    SenderCheck.Judgement judgement = new SenderCheck(config, QUIET).judge(ip("198.51.100.7"),
        "a@sender.example", "h", "From: a@b.example\r\n\r\nhi\r\n".getBytes(StandardCharsets.UTF_8),
        dns);
    assertTrue(judgement.deliver());
    assertEquals("and asks nothing", 0, dns.lookups());
  }

  // ---- signing, so the DKIM tests are real ---------------------------------------------------

  private record Signed(byte[] message, FakeDns dns) {
  }

  private static SenderCheck check(boolean enforce) throws Exception {
    return new SenderCheck(SmtpTests.configWith("\"enforce-dmarc\":" + enforce), QUIET);
  }

  /** build and sign a message the way a real sender would, so verification has something to do */
  private static Signed sign(String domain, String selector, String canonicalization)
      throws Exception {
    return sign(domain, selector, canonicalization, "from:subject");
  }

  private static Signed sign(String domain, String selector, String canonicalization,
                             String signedHeaders) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();

    String headers = "From: Ana <ana@" + domain + ">\r\n"
        + "Subject: Dinner on Tuesday\r\n"
        + "Date: Tue, 5 Aug 2026 19:00:00 +0000\r\n";
    String body = "Are you coming?\r\n";

    String[] canon = canonicalization.split("/");
    byte[] canonicalBody =
        Dkim.canonicalizeBody(body.getBytes(StandardCharsets.UTF_8), canon[1], null);
    String bodyHash = Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(canonicalBody));

    String signatureValue = " v=1; a=rsa-sha256; c=" + canonicalization + "; d=" + domain
        + "; s=" + selector + "; h=" + signedHeaders + "; bh=" + bodyHash + "; b=";
    String withSignature = "DKIM-Signature:" + signatureValue + "\r\n" + headers;

    byte[] toSign = Dkim.canonicalizeHeaders(withSignature.trim(), signedHeaders, signatureValue,
        canon[0]);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keys.getPrivate());
    signer.update(toSign);
    String signature = Base64.getEncoder().encodeToString(signer.sign());

    String message = "DKIM-Signature:" + signatureValue + signature + "\r\n" + headers
        + "\r\n" + body;
    FakeDns dns = new FakeDns().txt(selector + "._domainkey." + domain,
        "v=DKIM1; k=rsa; p=" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    assertNotNull(message);
    return new Signed(message.getBytes(StandardCharsets.UTF_8), dns);
  }
}
