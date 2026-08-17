package io.hearth.smtp;

import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainTree;
import io.hearth.vhost.Hosts;

/**
 * Which community a message is for -- and, far more importantly, which ones it is not.
 *
 * <b>This server is not a relay and must never become one.</b> An SMTP server that accepts mail for
 * a domain it does not serve will be found within days and used to send spam in somebody else's
 * name, which ends with the machine's address on every blocklist there is and the community's own
 * mail undeliverable. So the rule is one line and has no exceptions: a recipient is accepted only
 * if the domain after the `@` is one this server has a config file for.
 *
 * That reuses `DomainTree`, which is the same resolution the web side does -- one place that knows
 * which domains exist, rather than a second list that could drift. The difference is that mail does
 * *not* honour a wildcard: `wildcard: true` on `org` means somebody's browser asking for
 * `anything.org` gets served, which is a reasonable thing to want for a website and a catastrophic
 * one for mail, because it would accept for every domain under that suffix. So a recipient must
 * land on a config that names its exact domain.
 */
public final class SmtpRouting {
  private final DomainTree tree;

  public SmtpRouting(DomainTree tree) {
    this.tree = tree;
  }

  /** where a recipient goes, or null if it goes nowhere here */
  public DomainConfig routeFor(String address) {
    String domain = domainOf(address);
    if (domain == null) {
      return null;
    }
    DomainConfig config = tree.resolve(domain);
    if (config == null || !config.enabled) {
      return null;
    }
    // Exact, or a subdomain the config named. A wildcard is a decision about serving web pages and
    // reading it as permission to receive mail for every domain under a suffix is how an open relay
    // gets built by accident; a written-down list is neither a wildcard nor an accident.
    if (!domain.equals(config.domain) && !tree.hostnames().contains(domain)) {
      return null;
    }
    if (!config.acceptsMail) {
      return null;
    }
    return config;
  }

  public boolean accepts(String address) {
    return routeFor(address) != null;
  }

  /**
   * The domain part, normalized the way a Host header is.
   *
   * Going through `Hosts.normalize` rather than lowercasing by hand means the mail path and the web
   * path agree about what a domain *is* -- trailing dots, uppercase, anything ambiguous. Two
   * spellings of one domain is how a check gets passed by the wrong one.
   */
  public static String domainOf(String address) {
    if (address == null) {
      return null;
    }
    String clean = address.trim();
    int at = clean.lastIndexOf('@');
    if (at <= 0 || at == clean.length() - 1) {
      return null;
    }
    return Hosts.normalize(clean.substring(at + 1));
  }

  /**
   * Is this a usable address at all?
   *
   * Adapted from adama's check, and kept strict on purpose: this decides what gets a 250, and
   * anything odd enough to need a special case here is something we would rather refuse than
   * puzzle over later. Quoted local parts and bracketed IP literals are both legal and both
   * refused, because neither has any business arriving at a community server.
   */
  public static boolean looksLikeAddress(String address) {
    if (address == null || address.isEmpty() || address.length() > 320) {
      return false;
    }
    int at = address.indexOf('@');
    if (at <= 0 || at >= address.length() - 1) {
      return false;
    }
    if (address.indexOf('@', at + 1) >= 0) {
      return false;
    }
    String local = address.substring(0, at);
    String domain = address.substring(at + 1);
    if (local.length() > 64 || domain.length() > 255) {
      return false;
    }
    if (!domain.contains(".") || domain.contains("..")) {
      return false;
    }
    if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
      return false;
    }
    for (int k = 0; k < local.length(); k++) {
      char ch = local.charAt(k);
      boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
          || "!#$%&'*+-/=?^_`{|}~.".indexOf(ch) >= 0;
      if (!ok) {
        return false;
      }
    }
    for (int k = 0; k < domain.length(); k++) {
      char ch = domain.charAt(k);
      boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
          || ch == '-' || ch == '.';
      if (!ok) {
        return false;
      }
    }
    return !domain.startsWith("-") && !domain.startsWith(".") && !domain.endsWith(".");
  }

  /**
   * Pull the address out of `MAIL FROM:<a@b>` and friends.
   *
   * ESMTP parameters trail the address (`SIZE=`, `BODY=`), and angle brackets are optional in
   * practice however much the RFC would like otherwise. An empty `<>` is legal and meaningful: it
   * is what a bounce uses as its sender, and refusing it would mean never learning that a message
   * failed.
   */
  public static String extractAddress(String argument) {
    if (argument == null) {
      return null;
    }
    String text = argument.trim();
    int end = -1;
    boolean inAngle = false;
    for (int k = 0; k < text.length(); k++) {
      char ch = text.charAt(k);
      if (ch == '<') {
        inAngle = true;
      } else if (ch == '>') {
        inAngle = false;
      } else if (ch == ' ' && !inAngle) {
        end = k;
        break;
      }
    }
    if (end > 0) {
      text = text.substring(0, end);
    }
    if (text.startsWith("<") && text.endsWith(">")) {
      text = text.substring(1, text.length() - 1);
    }
    return text.trim();
  }
}
