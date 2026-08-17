package io.hearth.auth;

import com.lambdaworks.crypto.SCryptUtil;

/**
 * Password hashing, via scrypt.
 *
 * scrypt is memory-hard, so the gap between a defender's laptop and an attacker's GPU farm stays
 * small. The parameters below cost roughly 100ms per hash on ordinary hardware, which is invisible
 * to a person signing in and ruinous to somebody working through a leaked table.
 *
 * The salt and parameters live inside the returned string, so a future parameter bump verifies old
 * hashes correctly and {@link #needsRehash} says which ones to upgrade on next sign-in.
 */
public class Passwords {
  /** CPU/memory cost; 2^15 blocks */
  static final int N = 1 << 15;
  /** block size */
  static final int R = 8;
  /** parallelization */
  static final int P = 1;

  private Passwords() {
  }

  public static String hash(String password) {
    return SCryptUtil.scrypt(password, N, R, P);
  }

  /**
   * Constant-time in the part that matters: scrypt derives the candidate and compares digests, so
   * this does not leak how much of the password was right.
   */
  public static boolean verify(String password, String hash) {
    if (password == null || hash == null || hash.isEmpty()) {
      return false;
    }
    try {
      return SCryptUtil.check(password, hash);
    } catch (IllegalArgumentException ex) {
      return false; // a hash we can't parse is not a hash that matches
    }
  }

  /** true when a stored hash was made with weaker parameters than we use now */
  public static boolean needsRehash(String hash) {
    if (hash == null || hash.isEmpty()) {
      return false;
    }
    return !hash.startsWith("$s0$" + Integer.toHexString(log2(N)) + Integer.toHexString(R) + Integer.toHexString(P) + "$");
  }

  private static int log2(int value) {
    return 31 - Integer.numberOfLeadingZeros(value);
  }

  /**
   * Is this password acceptable? Length only.
   *
   * Composition rules -- one capital, one digit, one symbol -- push people toward Password1! and
   * toward writing it down. Length is the part that actually costs an attacker anything.
   */
  public static String reject(String password, LoginSecurity security) {
    if (password == null || password.isEmpty()) {
      return "a password is required";
    }
    if (password.length() < security.passwordMinLength) {
      return "passwords must be at least " + security.passwordMinLength + " characters";
    }
    if (password.length() > 256) {
      return "passwords must be at most 256 characters";
    }
    return null;
  }
}
