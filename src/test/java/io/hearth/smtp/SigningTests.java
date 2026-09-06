package io.hearth.smtp;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Signing, checked against this repository's own verifier.
 *
 * <b>That is the point of the test rather than a shortcut.</b> A signer with its own idea of
 * canonicalization produces signatures that verify nowhere, and the symptom -- "our forwarded mail
 * is marked as failing DKIM" -- points at DNS, at the key, at the selector, at anything except the
 * one line where a trailing space was handled differently. Running the shipped signature through
 * the shipped verifier is the only cheap way to know the two halves agree, and {@link Dkim} was
 * written against the RFC before this existed rather than to match it.
 */
public class SigningTests {
  private static final String MESSAGE =
      "From: Someone <someone@gmail.com>\r\n"
          + "To: jeff@ranch.example.org\r\n"
          + "Subject: about Thursday\r\n"
          + "Date: Tue, 8 Sep 2026 09:00:00 +0000\r\n"
          + "Message-ID: <abc123@gmail.com>\r\n"
          + "MIME-Version: 1.0\r\n"
          + "Content-Type: text/plain; charset=utf-8\r\n"
          + "\r\n"
          + "Can you do the ninth?\r\n";

  private MailKeys keys;
  private File dir;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("hearth-signing").toFile();
    keys = MailKeys.open(new File(dir, "dkim.key"), "hearth");
  }

  private FakeDns publishing() {
    return new FakeDns().txt("hearth._domainkey.ranch.example.org", keys.dnsRecord());
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] prepend(String header, byte[] message) {
    byte[] front = bytes(header + "\r\n");
    byte[] out = new byte[front.length + message.length];
    System.arraycopy(front, 0, out, 0, front.length);
    System.arraycopy(message, 0, out, front.length, message.length);
    return out;
  }

  // ---- DKIM ------------------------------------------------------------------------------------

  @Test
  public void aSignatureThisServerMakesVerifiesWithThisServersVerifier() {
    String signature = DkimSigner.sign(bytes(MESSAGE), "ranch.example.org", keys);
    assertNotNull(signature);
    byte[] signed = prepend(DkimSigner.fold(signature), bytes(MESSAGE));
    Dkim.Verified verified = Dkim.verify(signed, publishing());
    assertEquals(AuthResult.Status.pass, verified.status());
    assertEquals("ranch.example.org", verified.domain());
  }

  /**
   * Change one byte of the body and the signature stops verifying.
   *
   * This is the property the whole no-modification rule rests on: a forwarder that appends a footer
   * breaks the sender's signature exactly like this, and the receiver cannot tell that apart from
   * tampering.
   */
  @Test
  public void oneAlteredByteBreaksIt() {
    String signature = DkimSigner.sign(bytes(MESSAGE), "ranch.example.org", keys);
    byte[] signed = prepend(DkimSigner.fold(signature),
        bytes(MESSAGE.replace("the ninth?", "the tenth?")));
    assertEquals(AuthResult.Status.fail, Dkim.verify(signed, publishing()).status());
  }

  @Test
  public void addingAHeaderAboveTheSignatureDoesNotBreakIt() {
    // exactly what forwarding does: Received and the ARC set go on the front, and none of them is
    // signed, so the signature has to survive them
    String signature = DkimSigner.sign(bytes(MESSAGE), "ranch.example.org", keys);
    byte[] signed = prepend("Received: from somewhere by somewhere-else; Tue, 8 Sep 2026 09:00:01 +0000",
        prepend(DkimSigner.fold(signature), bytes(MESSAGE)));
    assertEquals(AuthResult.Status.pass, Dkim.verify(signed, publishing()).status());
  }

  @Test
  public void theSignatureCoversTheFromHeader() {
    String signature = DkimSigner.sign(bytes(MESSAGE), "ranch.example.org", keys);
    assertTrue(signature, signature.contains("h=from:"));
    assertTrue(signature, signature.contains("c=relaxed/relaxed"));
    assertTrue(signature, signature.contains("s=hearth"));
  }

  @Test
  public void aMessageWithNoFromIsNotSignedAtAll() {
    // a signature that does not cover the visible sender says nothing about who sent it, so there
    // is nothing worth producing here
    assertEquals(null, DkimSigner.sign(bytes("Subject: nothing\r\n\r\nbody\r\n"),
        "ranch.example.org", keys));
  }

  @Test
  public void foldingSurvivesTheRoundTrip() {
    // a base64 signature is 344 characters; a header that long is refused by some receivers and
    // silently wrapped -- and thereby broken -- by others
    String signature = DkimSigner.sign(bytes(MESSAGE), "ranch.example.org", keys);
    String folded = DkimSigner.fold(signature);
    assertTrue("it actually wrapped", folded.contains("\r\n "));
    for (String line : folded.split("\r\n")) {
      assertTrue("line of " + line.length() + ": " + line, line.length() <= 80);
    }
    assertFalse("bh= is signed, so a fold inside it would break the signature",
        folded.replaceAll("(?s); b=.*", "").matches("(?s).*bh=[A-Za-z0-9+/=]*\r\n.*"));
    assertEquals(AuthResult.Status.pass,
        Dkim.verify(prepend(folded, bytes(MESSAGE)), publishing()).status());
  }

  // ---- ARC -------------------------------------------------------------------------------------

  @Test
  public void aChainIsStartedWithTheThreeHeadersInOrder() {
    AuthResult result = new AuthResult(AuthResult.Status.pass, "gmail.com",
        AuthResult.Status.pass, "gmail.com", AuthResult.Status.pass, "gmail.com", "reject");
    Arc.Sealed sealed = Arc.seal(bytes(MESSAGE), result, "mail.ranch.example.org",
        "ranch.example.org", keys);
    assertTrue(sealed.state(), sealed.any());
    assertEquals(3, sealed.headers().size());
    assertTrue(sealed.headers().get(0).startsWith("ARC-Authentication-Results: i=1;"));
    assertTrue(sealed.headers().get(1).startsWith("ARC-Message-Signature: i=1;"));
    assertTrue(sealed.headers().get(2).startsWith("ARC-Seal: i=1;"));
  }

  /**
   * The first seal says `cv=none`, which is the RFC's rule and reads wrong until you see why.
   *
   * A chain of one has verified nothing. `cv=pass` on the first link would be this server vouching
   * for a chain that did not exist a moment ago.
   */
  @Test
  public void theFirstSealSaysThereWasNoChainBefore() {
    Arc.Sealed sealed = Arc.seal(bytes(MESSAGE), AuthResult.nothingChecked(), "mail.example.org",
        "ranch.example.org", keys);
    assertTrue(sealed.headers().get(2), sealed.headers().get(2).contains("cv=none"));
  }

  @Test
  public void whatTheChainRecordsIsWhatTheCheckersActuallySaid() {
    AuthResult result = new AuthResult(AuthResult.Status.fail, "gmail.com",
        AuthResult.Status.pass, "gmail.com", AuthResult.Status.pass, "gmail.com", "none");
    Arc.Sealed sealed = Arc.seal(bytes(MESSAGE), result, "mail.ranch.example.org",
        "ranch.example.org", keys);
    String recorded = sealed.headers().get(0);
    assertTrue(recorded, recorded.contains("spf=fail"));
    assertTrue(recorded, recorded.contains("dkim=pass"));
    assertTrue(recorded, recorded.contains("dmarc=pass"));
    assertTrue("the same name the plain header uses, or a reader sees two servers",
        recorded.contains("mail.ranch.example.org"));
  }

  /**
   * Somebody else's chain is left exactly as it was.
   *
   * Extending it means validating every seal in it and then asserting a verdict. This server does
   * not verify inbound chains, so `cv=pass` would be vouching for arithmetic nobody did and
   * `cv=fail` would be reporting a failure nobody observed.
   */
  @Test
  public void anExistingChainIsLeftAloneRatherThanExtendedOnFaith() {
    byte[] alreadySealed = prepend("ARC-Seal: i=1; a=rsa-sha256; cv=none; d=elsewhere.example;"
        + " s=sel; b=AAAA", bytes(MESSAGE));
    assertTrue(Arc.hasChain(alreadySealed));
    Arc.Sealed sealed = Arc.seal(alreadySealed, AuthResult.nothingChecked(), "mail.example.org",
        "ranch.example.org", keys);
    assertFalse(sealed.any());
    assertTrue(sealed.state(), sealed.state().contains("already carries a chain"));
    assertEquals("1 prior seal(s)", Arc.describeChain(alreadySealed));
  }

  @Test
  public void theMessageSignatureInAChainVerifiesAsADkimSignatureWould() {
    // an ARC-Message-Signature is a DKIM signature under another name, so the same body hash and
    // the same canonicalization have to produce it
    Arc.Sealed sealed = Arc.seal(bytes(MESSAGE), AuthResult.nothingChecked(), "mail.example.org",
        "ranch.example.org", keys);
    String ams = sealed.headers().get(1);
    assertTrue(ams, ams.contains("bh=" + DkimSigner.bodyHash(bytes(MESSAGE))));
    assertTrue(ams, ams.contains("d=ranch.example.org"));
  }

  // ---- the key ---------------------------------------------------------------------------------

  @Test
  public void theSameKeyComesBackTheSecondTime() throws Exception {
    // regenerating would silently invalidate the DNS record somebody has already published
    String first = keys.dnsRecord();
    MailKeys again = MailKeys.open(new File(dir, "dkim.key"), "hearth");
    assertEquals(first, again.dnsRecord());
  }

  @Test
  public void theRecordIsOneLineReadyToPaste() {
    String record = keys.dnsRecord();
    assertTrue(record, record.startsWith("v=DKIM1; k=rsa; p="));
    assertFalse("a control panel that meets a newline here produces a key that verifies nowhere",
        record.contains("\n"));
    assertEquals("hearth._domainkey.ranch.example.org", keys.dnsName("ranch.example.org"));
  }

  /**
   * A key anybody on the box can read is refused rather than quietly used.
   *
   * It is the ability to sign mail as this domain. A file with the wrong mode has usually been
   * copied from somewhere, and the copy it came from is the thing that needs looking at.
   */
  @Test
  public void aKeyOtherPeopleCanReadIsRefused() throws Exception {
    File loose = new File(dir, "loose.key");
    MailKeys.open(loose, "hearth");
    try {
      java.nio.file.Files.setPosixFilePermissions(loose.toPath(),
          java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
              java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
    } catch (UnsupportedOperationException ex) {
      return;
    }
    try {
      MailKeys.open(loose, "hearth");
      org.junit.Assert.fail("expected a refusal");
    } catch (java.io.IOException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("read by somebody other than its owner"));
    }
  }
}
