package io.hearth.smtp;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DKIM, RFC 6376: is this message cryptographically signed by the domain it claims?
 *
 * Adapted from adama's `DkimValidator`. The shape is the same and so are the two canonicalization
 * algorithms, because there is exactly one right answer for those and it is written down.
 *
 * What makes DKIM worth having, and SPF not enough on its own: a signature travels with the
 * message. A mailing list or a `.forward` re-sends from a machine the original domain never
 * listed, so SPF fails -- but the signature still verifies, provided nothing rewrote the signed
 * headers or the body. That is why DMARC accepts *either*.
 *
 * The part that is fiddly and unforgiving is canonicalization. Both sides have to agree byte for
 * byte on what was signed, and every rule below exists because a mail system somewhere folds a
 * header or adds a trailing space:
 *
 * <ul>
 *   <li><b>relaxed headers</b>: lowercase the name, unfold continuations, collapse whitespace runs
 *       to one space, trim.</li>
 *   <li><b>relaxed body</b>: collapse whitespace within lines, strip trailing whitespace, drop
 *       trailing empty lines, end with exactly one CRLF.</li>
 *   <li><b>simple</b>: touch nothing, except trailing empty lines in the body.</li>
 *   <li>The DKIM-Signature header is signed too, with its own `b=` value emptied -- and the
 *       negative lookbehind there matters, because `bh=` also ends in `b=` and blanking the body
 *       hash makes every signature fail.</li>
 * </ul>
 */
public final class Dkim {
  /** a public key big enough to be worth having; anything smaller is not a signature */
  private static final int MIN_KEY_BITS = 1024;

  private Dkim() {
  }

  /** the outcome, and which domain signed it -- DMARC needs the domain, not just the verdict */
  public record Verified(AuthResult.Status status, String domain) {
  }

  /**
   * Verify the first DKIM signature that verifies.
   *
   * A message may carry several -- a sender and a list, say. One that verifies is enough, which is
   * what the RFC says and what makes forwarding survivable.
   */
  public static Verified verify(byte[] message, SmtpDns dns) {
    if (message == null || message.length == 0) {
      return new Verified(AuthResult.Status.none, null);
    }
    String text = new String(message, StandardCharsets.UTF_8);
    int split = text.indexOf("\r\n\r\n");
    int skip = 4;
    if (split < 0) {
      split = text.indexOf("\n\n");
      skip = 2;
    }
    if (split < 0) {
      return new Verified(AuthResult.Status.permerror, null);
    }
    String headerSection = text.substring(0, split);
    byte[] body = text.substring(Math.min(text.length(), split + skip))
        .getBytes(StandardCharsets.UTF_8);

    List<String[]> headers = parseHeaders(headerSection);
    List<String> signatures = new ArrayList<>();
    for (String[] header : headers) {
      if (header[0].equalsIgnoreCase("dkim-signature")) {
        signatures.add(header[1]);
      }
    }
    if (signatures.isEmpty()) {
      return new Verified(AuthResult.Status.none, null);
    }

    AuthResult.Status worst = AuthResult.Status.permerror;
    String worstDomain = null;
    for (String signature : signatures) {
      Verified one = verifyOne(headerSection, body, signature, dns);
      if (one.status() == AuthResult.Status.pass) {
        return one;
      }
      // remember something to report, preferring a temporary problem so a DNS blip does not read
      // as a forgery
      if (worstDomain == null || one.status() == AuthResult.Status.temperror) {
        worst = one.status();
        worstDomain = one.domain();
      }
    }
    return new Verified(worst, worstDomain);
  }

  static Verified verifyOne(String headerSection, byte[] body, String signatureHeader,
                            SmtpDns dns) {
    Map<String, String> tags = parseTags(signatureHeader);
    String domain = tags.get("d");
    String selector = tags.get("s");
    String signedHeaders = tags.get("h");
    String bodyHash = tags.get("bh");
    String signature = tags.get("b");
    String algorithm = tags.getOrDefault("a", "rsa-sha256").toLowerCase(Locale.ROOT);
    if (domain == null || selector == null || signedHeaders == null || bodyHash == null
        || signature == null) {
      return new Verified(AuthResult.Status.permerror, domain);
    }
    // The From header must be signed. Without it a signature covers a message whose visible sender
    // anybody could rewrite, which is worth nothing and looks like everything.
    if (!signedHeaders.toLowerCase(Locale.ROOT).matches("(^|.*:)\\s*from\\s*(:.*|$)")) {
      return new Verified(AuthResult.Status.permerror, domain);
    }

    String[] canon = tags.getOrDefault("c", "simple/simple").split("/");
    String headerCanon = canon[0].isEmpty() ? "simple" : canon[0];
    String bodyCanon = canon.length > 1 ? canon[1] : "simple";

    String digestName = algorithm.endsWith("sha1") ? "SHA-1" : "SHA-256";
    byte[] canonicalBody = canonicalizeBody(body, bodyCanon, tags.get("l"));
    byte[] computed;
    try {
      computed = MessageDigest.getInstance(digestName).digest(canonicalBody);
    } catch (Exception ex) {
      return new Verified(AuthResult.Status.permerror, domain);
    }
    byte[] expected;
    try {
      expected = Base64.getDecoder().decode(bodyHash.replaceAll("\\s", ""));
    } catch (Exception ex) {
      return new Verified(AuthResult.Status.permerror, domain);
    }
    // Checked before the DNS lookup on purpose: a body that was altered fails here, and there is no
    // reason to ask somebody else's nameserver about a message we already know was changed.
    if (!MessageDigest.isEqual(computed, expected)) {
      return new Verified(AuthResult.Status.fail, domain);
    }

    String[] records = dns.txt(selector + "._domainkey." + domain);
    if (records.length == 0) {
      // no key published, or DNS did not answer. Temporary, because treating an unreachable
      // nameserver as a forgery would bounce real mail whenever somebody's DNS hiccuped.
      return new Verified(AuthResult.Status.temperror, domain);
    }

    for (String record : records) {
      Map<String, String> key = parseTags(record);
      String material = key.get("p");
      if (material == null) {
        continue;
      }
      if (material.isBlank()) {
        // an empty p= is how a domain revokes a selector; that is a definite no
        return new Verified(AuthResult.Status.fail, domain);
      }
      String keyType = key.getOrDefault("k", "rsa").toLowerCase(Locale.ROOT);
      try {
        byte[] canonicalHeaders =
            canonicalizeHeaders(headerSection, signedHeaders, signatureHeader, headerCanon);
        byte[] raw = Base64.getDecoder().decode(material.replaceAll("\\s", ""));
        byte[] signatureBytes = Base64.getDecoder().decode(signature.replaceAll("\\s", ""));

        PublicKey publicKey;
        Signature verifier;
        if (keyType.equals("ed25519")) {
          publicKey = KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(raw));
          verifier = Signature.getInstance("Ed25519");
        } else {
          publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(raw));
          if (publicKey instanceof java.security.interfaces.RSAPublicKey rsa
              && rsa.getModulus().bitLength() < MIN_KEY_BITS) {
            // a key too small to mean anything is worse than none, because it still says "pass"
            return new Verified(AuthResult.Status.permerror, domain);
          }
          verifier = Signature.getInstance(
              algorithm.endsWith("sha1") ? "SHA1withRSA" : "SHA256withRSA");
        }
        verifier.initVerify(publicKey);
        verifier.update(canonicalHeaders);
        if (verifier.verify(signatureBytes)) {
          return new Verified(AuthResult.Status.pass, domain);
        }
      } catch (Exception ex) {
        // a malformed key or signature is this record's problem; try the next one
      }
    }
    return new Verified(AuthResult.Status.fail, domain);
  }

  // ---- canonicalization ---------------------------------------------------------------------

  /**
   * The body, as the signer saw it.
   *
   * `l=` truncates the signed portion, which is a real tag and a real hazard: everything past the
   * length is unsigned and can be appended by anybody. It is honoured because messages use it, and
   * anything relying on this should know that only the first `l` bytes are covered.
   */
  static byte[] canonicalizeBody(byte[] body, String method, String length) {
    String text = new String(body, StandardCharsets.UTF_8);
    if (length != null) {
      try {
        int limit = Integer.parseInt(length.trim());
        if (limit >= 0 && limit < text.length()) {
          text = text.substring(0, limit);
        }
      } catch (NumberFormatException ex) {
        // a malformed l= is ignored rather than fatal; the hash below will simply not match
      }
    }
    if ("relaxed".equals(method)) {
      String[] lines = text.split("\r\n", -1);
      StringBuilder out = new StringBuilder();
      for (String line : lines) {
        String reduced = line.replaceAll("[ \t]+", " ").replaceAll("[ \t]+$", "");
        out.append(reduced).append("\r\n");
      }
      String result = out.toString();
      while (result.endsWith("\r\n\r\n")) {
        result = result.substring(0, result.length() - 2);
      }
      if (result.equals("\r\n") && text.isBlank()) {
        result = "";
      }
      return result.getBytes(StandardCharsets.UTF_8);
    }
    String result = text;
    while (result.endsWith("\r\n\r\n")) {
      result = result.substring(0, result.length() - 2);
    }
    if (!result.endsWith("\r\n")) {
      result = result + "\r\n";
    }
    return result.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The signed headers, in the order `h=` names them.
   *
   * The order is the signer's, not the message's, and a name may appear twice -- which means "the
   * next one up from the bottom", because that is how a header added later by a relay is excluded.
   * Walking the message's own order instead produces a byte stream that differs from the signer's
   * for exactly the messages that have been through a list.
   */
  static byte[] canonicalizeHeaders(String headerSection, String signedHeaders,
                                    String signatureHeader, String method) {
    List<String[]> headers = parseHeaders(headerSection);
    Map<String, Integer> used = new HashMap<>();
    StringBuilder out = new StringBuilder();

    for (String rawName : signedHeaders.split(":")) {
      String name = rawName.trim();
      if (name.isEmpty()) {
        continue;
      }
      String lower = name.toLowerCase(Locale.ROOT);
      int already = used.getOrDefault(lower, 0);
      int seen = 0;
      String found = null;
      for (int k = headers.size() - 1; k >= 0; k--) {
        if (headers.get(k)[0].equalsIgnoreCase(name)) {
          if (seen == already) {
            found = headers.get(k)[0] + ":" + headers.get(k)[1];
            break;
          }
          seen++;
        }
      }
      used.put(lower, already + 1);
      if (found == null) {
        // a signed header the message does not have contributes nothing, which is the RFC's
        // "null string" and how a signer proves a header was absent
        continue;
      }
      out.append(canonicalizeHeader(found, method)).append("\r\n");
    }

    // and the signature header itself, with b= emptied and no trailing CRLF
    String blanked = "DKIM-Signature:" + emptySignature(signatureHeader);
    out.append(canonicalizeHeader(blanked, method));
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String canonicalizeHeader(String header, String method) {
    if (!"relaxed".equals(method)) {
      return header;
    }
    int colon = header.indexOf(':');
    if (colon < 0) {
      return header;
    }
    String name = header.substring(0, colon).toLowerCase(Locale.ROOT).trim();
    String value = header.substring(colon + 1)
        .replaceAll("\r\n[ \t]+", " ")
        .replaceAll("[ \t]+", " ")
        .trim();
    return name + ":" + value;
  }

  /**
   * Blank the `b=` value, and only that one.
   *
   * The lookbehind is what stops this eating `bh=`. Blanking the body hash makes every signature
   * fail, in a way that looks exactly like a message having been tampered with -- which is the
   * worst possible false positive, because it is indistinguishable from the thing it is checking.
   */
  static String emptySignature(String header) {
    return header.replaceAll("(?<![a-zA-Z0-9])b=([^;]*)", "b=");
  }

  // ---- parsing --------------------------------------------------------------------------------

  static List<String[]> parseHeaders(String section) {
    ArrayList<String[]> headers = new ArrayList<>();
    String name = null;
    StringBuilder value = new StringBuilder();
    for (String line : section.split("\r?\n", -1)) {
      if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) {
        // folded: kept with its CRLF, because simple canonicalization signs it exactly as it lies
        value.append("\r\n").append(line);
        continue;
      }
      if (name != null) {
        headers.add(new String[]{name, value.toString()});
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        name = null;
        continue;
      }
      name = line.substring(0, colon);
      value = new StringBuilder(line.substring(colon + 1));
    }
    if (name != null) {
      headers.add(new String[]{name, value.toString()});
    }
    return headers;
  }

  /** `v=1; a=rsa-sha256; d=example.org; ...`, with folding whitespace removed from values */
  static Map<String, String> parseTags(String input) {
    LinkedHashMap<String, String> tags = new LinkedHashMap<>();
    if (input == null) {
      return tags;
    }
    for (String part : input.split(";")) {
      int equals = part.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String key = part.substring(0, equals).trim();
      String value = part.substring(equals + 1).trim();
      if (!key.isEmpty()) {
        // base64 in a header is folded across lines; the whitespace is formatting, not content
        tags.putIfAbsent(key, value.replaceAll("[\r\n\t ]", ""));
      }
    }
    // h= is a colon list whose spacing matters to nobody but whose content does, and stripping all
    // whitespace above is right for it too
    return tags;
  }
}
