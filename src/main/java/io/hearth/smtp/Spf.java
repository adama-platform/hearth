package io.hearth.smtp;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * SPF, RFC 7208: does the domain in MAIL FROM permit this machine to send for it?
 *
 * Adapted from adama's `SpfValidator`, turned inside out from callbacks into a straight recursion
 * because this runs on a worker thread rather than an event loop.
 *
 * <b>The ten-lookup limit is not a performance choice, it is the security property.</b> `include:`
 * and `redirect=` are recursive, and a record that includes itself -- or two that include each
 * other -- is an unbounded amplification: every message would make this server hammer somebody
 * else's DNS on the sender's behalf. RFC 7208 caps it at ten and so does this, counted across the
 * whole evaluation rather than per level, which is the part that is easy to get subtly wrong.
 *
 * What SPF actually proves is narrow, and worth stating because it is routinely overestimated: that
 * the machine connecting is one the *envelope sender's* domain listed. It says nothing about the
 * `From:` header a person reads, and it **breaks on forwarding** -- a mailing list or a
 * `.forward` re-sends from its own machine, which the original domain never listed. That is why an
 * SPF failure alone is a poor reason to refuse a message, and why DMARC exists.
 */
public final class Spf {
  /** RFC 7208 section 4.6.4; the number is the protection, not a tuning knob */
  static final int MAX_LOOKUPS = 10;

  private Spf() {
  }

  /**
   * Evaluate for one connection.
   *
   * An empty MAIL FROM (a bounce) is checked against the HELO name instead, which is what the RFC
   * says to do and the only way a bounce can be checked at all.
   */
  public static AuthResult.Status check(InetAddress client, String mailFromDomain, String helo,
                                        SmtpDns dns) {
    String domain = mailFromDomain == null || mailFromDomain.isBlank() ? helo : mailFromDomain;
    if (domain == null || domain.isBlank() || client == null) {
      return AuthResult.Status.none;
    }
    return checkHost(client, domain.toLowerCase(Locale.ROOT), new int[]{0}, dns, 0);
  }

  /** the recursion; `lookups` is shared so the budget spans the whole evaluation */
  static AuthResult.Status checkHost(InetAddress client, String domain, int[] lookups, SmtpDns dns,
                                     int depth) {
    if (depth > MAX_LOOKUPS || lookups[0] >= MAX_LOOKUPS) {
      return AuthResult.Status.permerror;
    }
    lookups[0]++;
    String record = null;
    for (String txt : dns.txt(domain)) {
      String trimmed = txt == null ? "" : txt.trim();
      if (trimmed.equalsIgnoreCase("v=spf1") || trimmed.toLowerCase(Locale.ROOT).startsWith("v=spf1 ")) {
        if (record != null) {
          // two records is a permanent error by the RFC, and guessing between them is exactly the
          // ambiguity an attacker would like us to resolve in their favour
          return AuthResult.Status.permerror;
        }
        record = trimmed;
      }
    }
    if (record == null) {
      return AuthResult.Status.none;
    }

    String[] terms = record.split("\\s+");
    String redirect = null;
    for (int k = 1; k < terms.length; k++) {
      String term = terms[k];
      if (term.isEmpty()) {
        continue;
      }
      String lower = term.toLowerCase(Locale.ROOT);
      if (lower.startsWith("redirect=")) {
        redirect = term.substring("redirect=".length());
        continue;
      }
      if (lower.startsWith("exp=")) {
        // an explanation string for a failure; it changes no outcome
        continue;
      }

      AuthResult.Status qualifier = AuthResult.Status.pass;
      String mechanism = term;
      char first = term.charAt(0);
      if (first == '+' || first == '-' || first == '~' || first == '?') {
        mechanism = term.substring(1);
        qualifier = switch (first) {
          case '-' -> AuthResult.Status.fail;
          case '~' -> AuthResult.Status.softfail;
          case '?' -> AuthResult.Status.neutral;
          default -> AuthResult.Status.pass;
        };
      }
      Boolean hit = matches(client, domain, mechanism, lookups, dns, depth);
      if (hit == null) {
        return AuthResult.Status.permerror;
      }
      if (hit) {
        return qualifier;
      }
    }

    if (redirect != null) {
      // redirect replaces the result entirely, unlike include which only contributes a match
      return checkHost(client, redirect.toLowerCase(Locale.ROOT), lookups, dns, depth + 1);
    }
    // no mechanism matched and no `all`: the RFC's default is neutral, not fail
    return AuthResult.Status.neutral;
  }

  /** true, false, or null for "this record is malformed" */
  static Boolean matches(InetAddress client, String domain, String mechanism, int[] lookups,
                         SmtpDns dns, int depth) {
    String lower = mechanism.toLowerCase(Locale.ROOT);
    if (lower.equals("all")) {
      return true;
    }
    if (lower.startsWith("ip4:")) {
      return inNetwork(client, mechanism.substring(4), 32);
    }
    if (lower.startsWith("ip6:")) {
      return inNetwork(client, mechanism.substring(4), 128);
    }
    if (lower.equals("a") || lower.startsWith("a:") || lower.startsWith("a/")) {
      if (lookups[0]++ >= MAX_LOOKUPS) {
        return null;
      }
      Target target = target(lower, "a", domain);
      for (InetAddress address : dns.addresses(target.host)) {
        if (sameNetwork(client, address, target.prefix)) {
          return true;
        }
      }
      return false;
    }
    if (lower.equals("mx") || lower.startsWith("mx:") || lower.startsWith("mx/")) {
      if (lookups[0]++ >= MAX_LOOKUPS) {
        return null;
      }
      Target target = target(lower, "mx", domain);
      for (String host : dns.mx(target.host)) {
        for (InetAddress address : dns.addresses(host)) {
          if (sameNetwork(client, address, target.prefix)) {
            return true;
          }
        }
      }
      return false;
    }
    if (lower.startsWith("include:")) {
      String included = lower.substring("include:".length());
      if (included.isEmpty()) {
        return null;
      }
      // include contributes only a *pass*: a fail inside an included record does not fail the
      // outer one, which is the rule everybody gets backwards
      AuthResult.Status inner = checkHost(client, included, lookups, dns, depth + 1);
      if (inner == AuthResult.Status.permerror || inner == AuthResult.Status.none) {
        return null;
      }
      return inner == AuthResult.Status.pass;
    }
    if (lower.startsWith("exists:")) {
      if (lookups[0]++ >= MAX_LOOKUPS) {
        return null;
      }
      return !dns.addresses(lower.substring("exists:".length())).isEmpty();
    }
    if (lower.startsWith("ptr")) {
      // The RFC says this "SHOULD NOT be published" and validating it means a reverse lookup the
      // sender controls. Treated as no match rather than supported.
      return false;
    }
    return null;
  }

  private record Target(String host, int prefix) {
  }

  /** `a:example.org/24` and friends, defaulting to the current domain and a host-sized prefix */
  private static Target target(String mechanism, String name, String domain) {
    String rest = mechanism.length() > name.length() ? mechanism.substring(name.length()) : "";
    if (rest.startsWith(":")) {
      rest = rest.substring(1);
    }
    int slash = rest.indexOf('/');
    String host = slash < 0 ? rest : rest.substring(0, slash);
    int prefix = -1;
    if (slash >= 0) {
      try {
        prefix = Integer.parseInt(rest.substring(slash + 1).trim());
      } catch (NumberFormatException ex) {
        prefix = -1;
      }
    }
    return new Target(host.isEmpty() ? domain : host, prefix);
  }

  /** does the client fall inside `1.2.3.0/24`? */
  static Boolean inNetwork(InetAddress client, String spec, int fullPrefix) {
    String text = spec == null ? "" : spec.trim();
    int slash = text.indexOf('/');
    int prefix = fullPrefix;
    if (slash >= 0) {
      try {
        prefix = Integer.parseInt(text.substring(slash + 1).trim());
      } catch (NumberFormatException ex) {
        return null;
      }
      text = text.substring(0, slash);
    }
    try {
      InetAddress network = InetAddress.getByName(text);
      return sameNetwork(client, network, prefix);
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * The bitwise comparison, which is the one piece here that is arithmetic rather than parsing.
   *
   * A prefix of -1 means "the whole address", which is what a mechanism with no `/` means. Two
   * addresses of different families never match, because an IPv4 sender is not covered by an IPv6
   * mechanism however similar the numbers look.
   */
  static boolean sameNetwork(InetAddress client, InetAddress network, int prefix) {
    if (client == null || network == null) {
      return false;
    }
    boolean clientV4 = client instanceof Inet4Address;
    boolean networkV4 = network instanceof Inet4Address;
    if (clientV4 != networkV4) {
      return false;
    }
    byte[] a = client.getAddress();
    byte[] b = network.getAddress();
    if (a.length != b.length) {
      return false;
    }
    int bits = prefix < 0 ? a.length * 8 : Math.min(prefix, a.length * 8);
    int whole = bits / 8;
    for (int k = 0; k < whole; k++) {
      if (a[k] != b[k]) {
        return false;
      }
    }
    int remainder = bits % 8;
    if (remainder == 0) {
      return true;
    }
    int mask = 0xff << (8 - remainder);
    return (a[whole] & mask) == (b[whole] & mask);
  }
}
