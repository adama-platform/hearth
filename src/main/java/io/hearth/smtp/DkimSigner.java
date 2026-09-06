package io.hearth.smtp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Signing, which is {@link Dkim} run backwards and shares its canonicalization exactly.
 *
 * <b>The two halves must agree byte for byte, so they are one implementation.</b> A signer with its
 * own idea of relaxed canonicalization produces signatures that verify nowhere, and the symptom --
 * "our mail is marked as failing DKIM" -- points at DNS, at the key, at the selector, at everything
 * except the one line where a trailing space was handled differently. This calls into {@code Dkim}
 * for every rule about what the signed bytes are.
 *
 * <b>relaxed/relaxed, always.</b> `simple` canonicalization breaks if any hop rewraps a header,
 * which is what a mailing list, a virus scanner and half of Exchange do. The choice exists in the
 * RFC for historical reasons and there is one right answer for mail that is going to be forwarded.
 *
 * <b>What gets signed is what is there.</b> A signer that names a header the message does not carry
 * is making a claim about its absence, which is a real feature and not one this needs; naming one
 * and getting the count wrong is a signature that fails. So the header list is intersected with the
 * message first, and `From` is always in it because a signature that does not cover the visible
 * sender is worth nothing.
 */
public final class DkimSigner {
  /**
   * The headers worth covering, in the order they are signed.
   *
   * Everything a reader sees and everything that identifies the message. Deliberately absent:
   * `Received`, which every hop adds, and anything else that legitimately changes in transit --
   * signing those would mean the signature breaks at the first relay, which is the opposite of the
   * point.
   */
  static final List<String> SIGNED = List.of("from", "to", "cc", "subject", "date", "message-id",
      "in-reply-to", "references", "mime-version", "content-type",
      "content-transfer-encoding");

  private DkimSigner() {
  }

  /** the header section, the body, and where the split was */
  record Split(String headers, byte[] body) {
  }

  /**
   * Cut a message into headers and body.
   *
   * A message with no blank line has no body, which is legal and rare; treating the whole thing as
   * headers is right, because that is what a receiver's parser will do with it too.
   */
  static Split split(byte[] message) {
    String text = new String(message, StandardCharsets.UTF_8);
    int at = text.indexOf("\r\n\r\n");
    int skip = 4;
    if (at < 0) {
      at = text.indexOf("\n\n");
      skip = 2;
    }
    if (at < 0) {
      return new Split(text, new byte[0]);
    }
    return new Split(text.substring(0, at),
        text.substring(at + skip).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A DKIM-Signature header for this message, ready to prepend, or null if it cannot be made.
   *
   * Null rather than an exception: a message that cannot be signed is still a message worth
   * forwarding, and the caller records that it went unsigned. Refusing to forward because our own
   * signing failed would turn a cosmetic problem into lost mail.
   */
  public static String sign(byte[] message, String domain, MailKeys keys) {
    List<String> headers = presentFrom(message, SIGNED);
    if (headers.isEmpty()) {
      return null;
    }
    String tags = "v=1; a=rsa-sha256; c=relaxed/relaxed; d=" + domain
        + "; s=" + keys.selector() + "; t=" + (System.currentTimeMillis() / 1000)
        + "; h=" + String.join(":", headers)
        + "; bh=" + bodyHash(message) + "; b=";
    String signature = signOver("DKIM-Signature", tags, message, headers, keys.privateKey());
    return signature == null ? null : "DKIM-Signature: " + tags + signature;
  }

  /**
   * Which of these headers the message actually has, `from` first and always.
   *
   * The order here is the order the verifier will walk, so it is fixed rather than the message's
   * own -- two messages with the same headers in a different order have to produce the same `h=`.
   */
  static List<String> presentFrom(byte[] message, List<String> wanted) {
    Split parts = split(message);
    ArrayList<String> present = new ArrayList<>();
    boolean hasFrom = false;
    for (String[] header : Dkim.parseHeaders(parts.headers())) {
      String name = header[0].trim().toLowerCase(Locale.ROOT);
      if (name.equals("from")) {
        hasFrom = true;
      }
      if (wanted.contains(name) && !present.contains(name)) {
        present.add(name);
      }
    }
    if (!hasFrom) {
      // a signature that does not cover the visible sender says nothing about who sent it
      return List.of();
    }
    present.sort(java.util.Comparator.comparingInt(wanted::indexOf));
    return present;
  }

  /** base64 of the sha256 of the relaxed-canonicalized body */
  static String bodyHash(byte[] message) {
    byte[] canonical = Dkim.canonicalizeBody(split(message).body(), "relaxed", null);
    return Base64.getEncoder().encodeToString(sha256(canonical));
  }

  /**
   * Sign the named headers plus this header itself with its `b=` empty.
   *
   * The last part is the bit that reads like a trick and is the whole scheme: the signature covers
   * the header carrying it, minus the signature. That is what stops anybody editing `d=` or `h=`
   * after the fact, and it is why the value is built with `b=` already at the end.
   */
  static String signOver(String headerName, String tags, byte[] message, List<String> headers,
                         PrivateKey key) {
    StringBuilder canonical = new StringBuilder();
    List<String[]> all = Dkim.parseHeaders(split(message).headers());
    for (String name : headers) {
      String found = lastOf(all, name);
      if (found != null) {
        canonical.append(Dkim.canonicalizeHeader(found, "relaxed")).append("\r\n");
      }
    }
    // no trailing CRLF on this one, which is the RFC's rule and a classic off-by-two
    canonical.append(Dkim.canonicalizeHeader(headerName + ": " + tags, "relaxed"));
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key);
      signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * The bottom-most instance of a header.
   *
   * Bottom rather than top, because headers are prepended as a message travels: the original
   * `From` is the one furthest down, and the one at the top is whatever the most recent hop added.
   */
  private static String lastOf(List<String[]> headers, String name) {
    for (int k = headers.size() - 1; k >= 0; k--) {
      if (headers.get(k)[0].trim().equalsIgnoreCase(name)) {
        return headers.get(k)[0] + ":" + headers.get(k)[1];
      }
    }
    return null;
  }

  static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  /** where a signature's own base64 begins; everything after it may be broken anywhere */
  private static final String SIGNATURE_TAG = "; b=";
  /** RFC 5322's soft limit, which is what every mail system wraps at */
  private static final int LINE = 78;

  /**
   * Fold a long header the way a mail system does.
   *
   * <b>Two rules, and the second one is why this is not three lines.</b> Before the signature, the
   * line may only be broken where there is already a space: relaxed canonicalization turns a fold
   * into a single space, so a break inserted anywhere else changes the bytes the verifier hashes
   * and the signature fails. Inside the signature itself it may be broken anywhere, because the
   * `b=` value is emptied by both sides before either hashes anything -- which is exactly why the
   * base64 can be wrapped at all, and it has to be: it is 344 characters with no space in it, so
   * folding at whitespace alone leaves a line four times the limit.
   *
   * The tempting simplification -- break long runs anywhere -- passes this repository's own
   * verifier and fails at the first receiver, because it would also break `bh=`, which is signed.
   */
  public static String fold(String header) {
    if (header.length() <= LINE) {
      return header;
    }
    int signature = header.lastIndexOf(SIGNATURE_TAG);
    int foldable = signature < 0 ? header.length() : signature + SIGNATURE_TAG.length();

    StringBuilder out = new StringBuilder(header.length() + 32);
    int lineStart = 0;
    int lastSpace = -1;
    for (int k = 0; k < foldable; k++) {
      if (header.charAt(k) == ' ') {
        lastSpace = k;
      }
      if (k - lineStart >= LINE && lastSpace > lineStart) {
        out.append(header, lineStart, lastSpace).append("\r\n ");
        lineStart = lastSpace + 1;
        lastSpace = -1;
      }
    }
    out.append(header, lineStart, foldable);
    for (int k = foldable; k < header.length(); k += LINE - 1) {
      out.append("\r\n ").append(header, k, Math.min(header.length(), k + LINE - 1));
    }
    return out.toString();
  }
}
