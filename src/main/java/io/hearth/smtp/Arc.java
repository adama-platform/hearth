package io.hearth.smtp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * ARC, RFC 8617: the forwarder saying "this passed when I got it, and here is my signature on that".
 *
 * <b>This is the header set that makes forwarding to a large provider work at all.</b> A forwarded
 * message arrives at Google failing SPF -- the connecting machine is this box, not the sender's --
 * and possibly failing DKIM if any hop touched it. Both failures are artefacts of the forward
 * rather than evidence of anything, and a receiver has no way to tell that apart from a forgery.
 * ARC is how it is told: this server records what it saw at the moment of arrival, signs that
 * record, and Google honours a chain it can verify.
 *
 * Three headers, prepended together:
 *
 * <ul>
 *   <li><b>ARC-Authentication-Results</b> -- what SPF, DKIM and DMARC said here, unsigned on its own
 *       and made trustworthy by the seal below.</li>
 *   <li><b>ARC-Message-Signature</b> -- a DKIM signature over the message as it stands, so the
 *       receiver can tell whether anything changed after this hop.</li>
 *   <li><b>ARC-Seal</b> -- a signature over the whole chain so far, which is what makes it a chain
 *       rather than three headers anybody could add.</li>
 * </ul>
 *
 * <b>A chain is only ever started, never extended, and that is a deliberate limitation.</b>
 * Extending somebody else's chain means validating every seal and signature in it and then
 * asserting `cv=pass`. This server does not verify inbound chains, so the honest thing to do when
 * one is present is to leave it alone: adding `cv=pass` would be vouching for arithmetic nobody
 * did, and adding `cv=fail` would be reporting a failure nobody observed. Either is a lie about a
 * message that is probably fine, and this codebase already has a name for that rule -- an uncertain
 * result is never reported as a certain one. Mail arriving straight from a sender has no chain, so
 * the common case is covered and the uncommon one is left honest and logged.
 */
public final class Arc {
  /** what the chain says about itself when this is the first hop to seal it */
  private static final String NO_CHAIN = "none";

  private Arc() {
  }

  /** the three headers, ready to prepend, and what happened */
  public record Sealed(List<String> headers, String state) {
    public boolean any() {
      return !headers.isEmpty();
    }
  }

  /** this message already carries somebody else's chain */
  public static boolean hasChain(byte[] message) {
    for (String[] header : Dkim.parseHeaders(DkimSigner.split(message).headers())) {
      if (header[0].trim().equalsIgnoreCase("arc-seal")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Start a chain over this message.
   *
   * The order the three are built in is the order they are signed in and the order they must be
   * prepended in, and getting that wrong produces a chain that verifies for nobody.
   *
   * @param authserv the name this server calls itself in an authentication result; it has to be the
   *                 same string the plain `Authentication-Results` header uses, or a receiver
   *                 reading both sees two different servers agreeing about one message.
   */
  public static Sealed seal(byte[] message, AuthResult result, String authserv, String domain,
                            MailKeys keys) {
    if (hasChain(message)) {
      // see the class note: extending a chain would mean asserting a verdict on arithmetic this
      // server did not do
      return new Sealed(List.of(), "left alone: this message already carries a chain");
    }
    long now = System.currentTimeMillis() / 1000;
    String authResults = "i=1; " + stripLabel(result.toHeader(authserv));

    // The signature covers the message plus our own authentication results, so a receiver that
    // trusts the seal can trust what the results say. Adding the AAR to h= is what ties them
    // together; without it the results are an unsigned header anybody could have written.
    List<String> signedHeaders = new ArrayList<>(
        DkimSigner.presentFrom(message, DkimSigner.SIGNED));
    if (signedHeaders.isEmpty()) {
      return new Sealed(List.of(), "not sealed: the message has no From header to sign");
    }

    String messageTags = "i=1; a=rsa-sha256; c=relaxed/relaxed; d=" + domain
        + "; s=" + keys.selector() + "; t=" + now
        + "; h=" + String.join(":", signedHeaders)
        + "; bh=" + DkimSigner.bodyHash(message) + "; b=";
    String messageSignature = DkimSigner.signOver("ARC-Message-Signature", messageTags, message,
        signedHeaders, keys.privateKey());
    if (messageSignature == null) {
      return new Sealed(List.of(), "not sealed: the message signature could not be made");
    }
    String ams = "ARC-Message-Signature: " + messageTags + messageSignature;
    String aar = "ARC-Authentication-Results: " + authResults;

    // cv=none, because this is instance 1 and there was nothing before it. The RFC is explicit that
    // the first seal in a chain says none rather than pass -- a chain of one has verified nothing.
    String sealTags = "i=1; a=rsa-sha256; cv=" + NO_CHAIN + "; d=" + domain
        + "; s=" + keys.selector() + "; t=" + now + "; b=";
    String seal = sealOver(List.of(aar, ams), sealTags, keys);
    if (seal == null) {
      return new Sealed(List.of(), "not sealed: the seal could not be made");
    }

    return new Sealed(List.of(
        DkimSigner.fold(aar),
        DkimSigner.fold(ams),
        DkimSigner.fold("ARC-Seal: " + sealTags + seal)), "sealed i=1 cv=" + NO_CHAIN);
  }

  /**
   * The seal signs the chain, not the message.
   *
   * Every ARC header of every instance in ascending order, relaxed-canonicalized, then this seal
   * with its own `b=` empty and no trailing CRLF. The distinction from the message signature is the
   * point of having two: one says "the message is as I found it", the other says "the record of
   * what I found is as I wrote it".
   */
  private static String sealOver(List<String> chain, String tags, MailKeys keys) {
    StringBuilder canonical = new StringBuilder();
    for (String header : chain) {
      canonical.append(Dkim.canonicalizeHeader(header, "relaxed")).append("\r\n");
    }
    canonical.append(Dkim.canonicalizeHeader("ARC-Seal: " + tags, "relaxed"));
    try {
      java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
      signer.initSign(keys.privateKey());
      signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * `Authentication-Results: host; spf=...` becomes `host; spf=...`.
   *
   * The ARC version carries the same content under a different name, and reusing
   * {@link AuthResult#toHeader} rather than formatting it twice is what keeps the two from drifting
   * -- a chain whose recorded verdict disagrees with the plain header on the same message is worse
   * than either alone.
   */
  private static String stripLabel(String header) {
    int colon = header.indexOf(':');
    return colon < 0 ? header : header.substring(colon + 1).trim();
  }

  /** what a message says about its own chain, for the log */
  public static String describeChain(byte[] message) {
    int seals = 0;
    for (String[] header : Dkim.parseHeaders(DkimSigner.split(message).headers())) {
      if (header[0].trim().toLowerCase(Locale.ROOT).equals("arc-seal")) {
        seals++;
      }
    }
    return seals == 0 ? "" : seals + " prior seal(s)";
  }
}
