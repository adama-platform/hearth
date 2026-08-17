package io.hearth.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Session tokens and emailed codes.
 *
 * The rule that shapes this file: what goes in the database is never what goes to the person. A
 * session token exists in the cookie and in memory; the sessions table holds its SHA-256. Somebody
 * who walks off with the database file gets a list of hashes they cannot turn back into logins.
 *
 * SHA-256 with no salt or stretching is the right call here and the wrong call for passwords. A
 * token is 256 bits of output from a CSPRNG, so there is no dictionary to run and no work factor
 * worth paying; the lookup has to be a single indexed hit on every request.
 */
public class Tokens {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Tokens() {
  }

  /** a fresh session token: 32 random bytes, URL-safe, for the cookie */
  public static String newSessionToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** the form stored in the sessions table */
  public static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] out = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(out.length * 2);
      for (byte b : out) {
        sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required and missing", ex);
    }
  }

  /**
   * A numeric code for email. Uniform over the range -- rejection sampling rather than a modulo,
   * which would quietly make low digits more likely.
   */
  public static String newCode(int digits) {
    StringBuilder sb = new StringBuilder(digits);
    for (int k = 0; k < digits; k++) {
      sb.append((char) ('0' + RANDOM.nextInt(10)));
    }
    return sb.toString();
  }

  /** an opaque handle for a flow in progress, safe to put in a URL or a hidden form field */
  public static String newHandle() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /**
   * Compare two secrets without letting the clock say how much of the guess was right.
   *
   * Codes are short enough that a timing oracle plus a few thousand tries is a real attack, which
   * is exactly the shape of attack an emailed six digit code invites.
   */
  public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    byte[] left = a.getBytes(StandardCharsets.UTF_8);
    byte[] right = b.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(left, right);
  }

  /**
   * The canonical form of an email address for storage and lookup.
   *
   * Lowercased and trimmed, and nothing else. Stripping dots or plus-tags -- treating
   * someone+community@gmail.com as someone@gmail.com -- is a Gmail convention, not a rule of email, and
   * applying it would merge two addresses that some other provider considers different people.
   */
  public static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Is this plausibly an email address?
   *
   * Deliberately loose. The only test that proves an address works is sending to it and having
   * somebody read it, which is what the code flow does; a strict regex here would reject valid
   * addresses and prove nothing about the rest.
   */
  public static boolean looksLikeEmail(String email) {
    if (email == null || email.length() < 3 || email.length() > 320) {
      return false;
    }
    int at = email.indexOf('@');
    if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
      return false;
    }
    String domain = email.substring(at + 1);
    if (domain.indexOf('.') < 0 || domain.startsWith(".") || domain.endsWith(".")) {
      return false;
    }
    for (int k = 0; k < email.length(); k++) {
      char ch = email.charAt(k);
      if (ch <= ' ' || ch == '<' || ch == '>' || ch == ',' || ch == ';' || ch == '"' || ch == '\\') {
        return false;
      }
    }
    return true;
  }
}
