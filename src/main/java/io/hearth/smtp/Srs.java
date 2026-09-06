package io.hearth.smtp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sender Rewriting Scheme: the reason a forwarded message still passes SPF.
 *
 * <b>Forwarding breaks SPF, always, and this is the only fix.</b> SPF asks "is the machine
 * connecting allowed to send for the domain in MAIL FROM". A message from `someone@gmail.com`
 * forwarded by this box arrives at Google from an address Google never listed, so it fails -- not
 * because anything is wrong, but because the question SPF asks has the wrong answer for every
 * forwarder that has ever existed. Passing the envelope through unchanged means every forwarded
 * message fails SPF at the far end, and for a domain publishing `p=reject` that is a message
 * deleted rather than delivered.
 *
 * So the envelope sender is rewritten to an address at *this* domain, which does list this box:
 *
 * <pre>
 *   SRS0=HHHH=TT=gmail.com=someone@ourdomain.example
 * </pre>
 *
 * <b>The header From is not touched, and that is the whole discipline.</b> What a person reads is
 * unchanged, DKIM still covers it, and DMARC still aligns against it. Only the return path -- which
 * nobody reads and which exists to carry bounces -- is rewritten. A forwarder that rewrote the
 * visible sender would be a forwarder that broke every signature it touched.
 *
 * <b>The hash is what stops this being an open relay.</b> Without it anybody could post to
 * `SRS0=x=y=anywhere.com=anybody@ourdomain` and have this server dutifully relay a bounce to a
 * stranger. The MAC is over the timestamp, the original domain and the original local part, keyed
 * with a secret only this box has, so an address this server did not write does not reverse.
 *
 * <b>The timestamp bounds the damage.</b> An SRS address stops reversing after a few weeks, because
 * a return path that works forever is a forwarding address a spammer harvests once and uses for
 * years.
 */
public final class Srs {
  /** how many days an SRS address keeps working; longer than any real bounce, shorter than forever */
  public static final int VALID_DAYS = 21;
  /** the timestamp alphabet, base32 as the SRS drafts define it */
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  /** how much of the MAC rides in the address; four characters is what every implementation uses */
  private static final int HASH_CHARS = 4;
  private static final String FORWARD = "SRS0=";
  private static final String RELAY = "SRS1=";

  private Srs() {
  }

  /**
   * Rewrite an envelope sender so it belongs to this domain.
   *
   * <b>The null sender is returned untouched, and that is not an oversight.</b> `MAIL FROM:&lt;&gt;`
   * is what a bounce uses, and rewriting it would produce a bounce that could itself bounce -- the
   * loop the empty sender exists to prevent.
   *
   * <b>An address this server already wrote is rewound rather than wrapped again.</b> Mail that goes
   * round twice would otherwise grow an envelope sender per hop until it passed the 320-character
   * limit, at which point delivery fails for a reason nobody could read off the wire.
   */
  public static String forward(String envelopeFrom, String ourDomain, String secret) {
    if (envelopeFrom == null || envelopeFrom.isBlank()) {
      return "";
    }
    String clean = envelopeFrom.trim();
    int at = clean.lastIndexOf('@');
    if (at <= 0 || at == clean.length() - 1) {
      return clean;
    }
    String local = clean.substring(0, at);
    String domain = clean.substring(at + 1).toLowerCase(Locale.ROOT);

    if (domain.equalsIgnoreCase(ourDomain) && local.regionMatches(true, 0, FORWARD, 0, 5)) {
      // ours already: re-stamp it rather than wrapping, so a message that comes back through here
      // has one envelope sender however many times it passes
      String reversed = reverse(clean, ourDomain, secret);
      return reversed == null ? clean : forward(reversed, ourDomain, secret);
    }
    if (local.regionMatches(true, 0, FORWARD, 0, 5)) {
      // somebody else's SRS0 address. The scheme's own answer is SRS1, which names the hop that
      // wrote the SRS0 and carries its payload untouched -- so a bounce walks back one hop at a
      // time rather than every forwarder having to understand every other forwarder's secret.
      String payload = local.substring(5);
      return sign(RELAY, domain + "=" + payload, ourDomain, secret, false);
    }
    return sign(FORWARD, domain + "=" + local, ourDomain, secret, true);
  }

  /**
   * Turn an SRS address back into the one it was written for, or null if this server did not write it.
   *
   * Null is the important return. A bounce arriving at an address that does not reverse is a bounce
   * addressed to a forgery, and relaying it would make this server the delivery mechanism for
   * whatever the forger wanted delivered.
   */
  public static String reverse(String address, String ourDomain, String secret) {
    if (address == null) {
      return null;
    }
    int at = address.lastIndexOf('@');
    if (at <= 0 || !address.substring(at + 1).equalsIgnoreCase(ourDomain)) {
      return null;
    }
    String local = address.substring(0, at);
    if (local.regionMatches(true, 0, RELAY, 0, 5)) {
      // SRS1=hash=firsthop=SRS0payload -> hand the whole SRS0 address back to the hop that made it
      String[] parts = local.substring(5).split("=", 3);
      if (parts.length < 3 || !verify(parts[0], parts[1] + "=" + parts[2], secret)) {
        return null;
      }
      return FORWARD + parts[2] + "@" + parts[1];
    }
    if (!local.regionMatches(true, 0, FORWARD, 0, 5)) {
      return null;
    }
    // SRS0=hash=timestamp=domain=local
    String[] parts = local.substring(5).split("=", 4);
    if (parts.length < 4) {
      return null;
    }
    String hash = parts[0];
    String stamp = parts[1];
    String payload = parts[2] + "=" + parts[3];
    if (!verify(hash, stamp + "=" + payload, secret)) {
      return null;
    }
    if (!stampIsRecent(stamp)) {
      return null;
    }
    return parts[3] + "@" + parts[2];
  }

  /** is this address one of ours, whatever it reverses to? */
  public static boolean looksLikeOurs(String address, String ourDomain) {
    if (address == null) {
      return false;
    }
    int at = address.lastIndexOf('@');
    if (at <= 0 || !address.substring(at + 1).equalsIgnoreCase(ourDomain)) {
      return false;
    }
    String local = address.substring(0, at);
    return local.regionMatches(true, 0, FORWARD, 0, 5)
        || local.regionMatches(true, 0, RELAY, 0, 5);
  }

  // ---- the pieces --------------------------------------------------------------------------------

  private static String sign(String prefix, String payload, String ourDomain, String secret,
                             boolean stamped) {
    String body = stamped ? today() + "=" + payload : payload;
    return prefix + mac(body, secret) + "=" + body + "@" + ourDomain;
  }

  private static boolean verify(String offered, String body, String secret) {
    String expected = mac(body, secret);
    if (offered == null || offered.length() != expected.length()) {
      return false;
    }
    // constant time, because this is a MAC comparison and the usual reason to be careless about it
    // -- "it is only four characters" -- is exactly the case where guessing is cheapest
    int difference = 0;
    for (int k = 0; k < expected.length(); k++) {
      difference |= Character.toUpperCase(offered.charAt(k)) ^ Character.toUpperCase(expected.charAt(k));
    }
    return difference == 0;
  }

  private static String mac(String body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(HASH_CHARS);
      for (int k = 0; k < HASH_CHARS; k++) {
        out.append(BASE32.charAt(digest[k] & 31));
      }
      return out.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC-SHA256 is not available", ex);
    }
  }

  /** two base32 characters of "days since the epoch", which wraps every 2.8 years */
  static String today() {
    return stampOf(System.currentTimeMillis() / 86_400_000L);
  }

  static String stampOf(long days) {
    long value = days % 1024;
    return "" + BASE32.charAt((int) (value / 32)) + BASE32.charAt((int) (value % 32));
  }

  /**
   * Is this timestamp within the window?
   *
   * The arithmetic is modular because the stamp is only ten bits, so "before now" and "1024 days
   * from now" are the same characters. Anything not in the last {@link #VALID_DAYS} is refused,
   * which makes a stamp from the far side of the wrap read as expired rather than as valid -- the
   * safe direction, because the cost is a bounce that does not get relayed and the alternative is
   * an address that works for years.
   */
  static boolean stampIsRecent(String stamp) {
    if (stamp == null || stamp.length() != 2) {
      return false;
    }
    int high = BASE32.indexOf(Character.toUpperCase(stamp.charAt(0)));
    int low = BASE32.indexOf(Character.toUpperCase(stamp.charAt(1)));
    if (high < 0 || low < 0) {
      return false;
    }
    long then = high * 32L + low;
    long now = (System.currentTimeMillis() / 86_400_000L) % 1024;
    long age = (now - then + 1024) % 1024;
    return age <= VALID_DAYS;
  }
}
