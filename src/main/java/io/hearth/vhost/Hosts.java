package io.hearth.vhost;

/**
 * Turns a Host header into a canonical domain, or nothing at all.
 *
 * The Host header is attacker-controlled and it is the key we look virtual hosts up by, so this
 * is a security boundary. Anything ambiguous is rejected rather than guessed at: no IP literals,
 * no uppercase, no trailing dots, no underscores, no empty labels. If two different byte strings
 * could name the same host, only one spelling survives here.
 */
public class Hosts {
  public static final int MAX_DOMAIN_LENGTH = 253;
  public static final int MAX_LABEL_LENGTH = 63;

  /** canonical form of a Host header value, or null if it can't name a virtual host */
  public static String normalize(String hostHeader) {
    if (hostHeader == null) {
      return null;
    }
    String host = hostHeader.trim();
    if (host.isEmpty()) {
      return null;
    }
    // bracketed IPv6 literal; never a virtual host
    if (host.charAt(0) == '[') {
      return null;
    }
    int colon = host.indexOf(':');
    if (colon >= 0) {
      // a second colon means a bare IPv6 literal, which we also refuse
      if (host.indexOf(':', colon + 1) >= 0) {
        return null;
      }
      host = host.substring(0, colon);
    }
    // one trailing dot is legal DNS ("example.com.") and means the same host; normalize it away
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    host = host.toLowerCase(java.util.Locale.ROOT);
    if (!isValidDomain(host)) {
      return null;
    }
    if (isIPv4(host)) {
      return null;
    }
    return host;
  }

  /** a domain we are willing to use as a lookup key: lowercase dotted labels, nothing exotic */
  public static boolean isValidDomain(String domain) {
    if (domain == null || domain.isEmpty() || domain.length() > MAX_DOMAIN_LENGTH) {
      return false;
    }
    int start = 0;
    while (true) {
      int dot = domain.indexOf('.', start);
      String label = dot < 0 ? domain.substring(start) : domain.substring(start, dot);
      if (!isValidLabel(label)) {
        return false;
      }
      if (dot < 0) {
        return true;
      }
      start = dot + 1;
    }
  }

  /**
   * One DNS label as we accept it on disk and on the wire: lowercase letters, digits, and inner
   * hyphens. Uppercase is rejected instead of folded so that a directory named "Example" can
   * never quietly become a second spelling of an existing host.
   */
  public static boolean isValidLabel(String label) {
    if (label == null || label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
      return false;
    }
    if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
      return false;
    }
    for (int k = 0; k < label.length(); k++) {
      char ch = label.charAt(k);
      boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  /** dotted-quad detection; we don't virtual host by address */
  public static boolean isIPv4(String host) {
    int digits = 0;
    for (int k = 0; k < host.length(); k++) {
      char ch = host.charAt(k);
      if (ch >= '0' && ch <= '9') {
        digits++;
      } else if (ch != '.') {
        return false;
      }
    }
    return digits > 0;
  }

  /** the number of labels in a domain; used for the specificity report */
  public static int labelCount(String domain) {
    int count = 1;
    for (int k = 0; k < domain.length(); k++) {
      if (domain.charAt(k) == '.') {
        count++;
      }
    }
    return count;
  }
}
