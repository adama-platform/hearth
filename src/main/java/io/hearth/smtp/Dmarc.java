package io.hearth.smtp;

import java.util.Locale;
import java.util.Map;

/**
 * DMARC, RFC 7489: does the domain a *person* would read agree with what was authenticated?
 *
 * Adapted from adama's `DmarcValidator`. This is the check that makes the other two mean something,
 * and the reason is alignment.
 *
 * SPF authenticates the envelope sender, which nobody sees. DKIM authenticates whichever domain
 * chose to sign, which need not be the one in the message. Either can pass for a message whose
 * visible `From:` says something else entirely -- a spammer publishes an SPF record for their own
 * domain, sends from it, passes SPF, and puts your bank in the From header. DMARC closes that by
 * requiring the authenticated domain to *line up* with the From domain, and by letting the domain
 * owner say what to do when it does not.
 *
 * Two ways to line up, and either is enough:
 * <ul>
 *   <li><b>strict</b> (`adkim=s`, `aspf=s`): the domains must be identical.</li>
 *   <li><b>relaxed</b> (the default): the organizational domains must match, so `mail.example.org`
 *       aligns with `example.org`.</li>
 * </ul>
 */
public final class Dmarc {
  private Dmarc() {
  }

  public record Policy(AuthResult.Status status, String domain, String policy) {
  }

  /**
   * Evaluate for one message.
   *
   * The From domain is the subject of the whole exercise. Without one there is nothing to align
   * against, and a message with no readable From is broken in a way DMARC has no opinion about.
   */
  public static Policy check(String fromDomain, AuthResult.Status spf, String spfDomain,
                             AuthResult.Status dkim, String dkimDomain, SmtpDns dns) {
    if (fromDomain == null || fromDomain.isBlank()) {
      return new Policy(AuthResult.Status.none, null, "none");
    }
    String domain = fromDomain.toLowerCase(Locale.ROOT);

    String[] records = dns.txt("_dmarc." + domain);
    String record = pick(records);
    boolean viaOrganizational = false;
    if (record == null) {
      // Falling back to the organizational domain is what lets one record at example.org cover
      // every subdomain, which is how almost everybody publishes it.
      String organizational = organizationalDomain(domain);
      if (!organizational.equals(domain)) {
        record = pick(dns.txt("_dmarc." + organizational));
        viaOrganizational = record != null;
        if (record != null) {
          domain = organizational;
        }
      }
    }
    if (record == null) {
      return new Policy(AuthResult.Status.none, fromDomain, "none");
    }

    Map<String, String> tags = Dkim.parseTags(record);
    // sp= is the policy for subdomains, and applies only when we got here via the parent
    String policy = viaOrganizational && tags.containsKey("sp")
        ? tags.get("sp") : tags.getOrDefault("p", "none");
    policy = policy == null ? "none" : policy.toLowerCase(Locale.ROOT);
    String aspf = tags.getOrDefault("aspf", "r").toLowerCase(Locale.ROOT);
    String adkim = tags.getOrDefault("adkim", "r").toLowerCase(Locale.ROOT);

    boolean spfAligned = spf == AuthResult.Status.pass
        && aligned(fromDomain, spfDomain, "s".equals(aspf));
    boolean dkimAligned = dkim == AuthResult.Status.pass
        && aligned(fromDomain, dkimDomain, "s".equals(adkim));

    // Either is enough, and that is the design rather than a leniency: DKIM survives forwarding
    // and SPF survives a message with no signature, so requiring both would fail most real mail.
    if (spfAligned || dkimAligned) {
      return new Policy(AuthResult.Status.pass, fromDomain, policy);
    }
    // A DNS problem underneath should not read as a forgery.
    if (spf == AuthResult.Status.temperror || dkim == AuthResult.Status.temperror) {
      return new Policy(AuthResult.Status.temperror, fromDomain, policy);
    }
    return new Policy(AuthResult.Status.fail, fromDomain, policy);
  }

  private static String pick(String[] records) {
    String found = null;
    for (String record : records) {
      String trimmed = record == null ? "" : record.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("v=dmarc1")) {
        if (found != null) {
          // two records: the RFC says treat it as none rather than choosing
          return null;
        }
        found = trimmed;
      }
    }
    return found;
  }

  static boolean aligned(String fromDomain, String authenticated, boolean strict) {
    if (fromDomain == null || authenticated == null || authenticated.isBlank()) {
      return false;
    }
    String a = fromDomain.toLowerCase(Locale.ROOT);
    String b = authenticated.toLowerCase(Locale.ROOT);
    if (strict) {
      return a.equals(b);
    }
    return organizationalDomain(a).equals(organizationalDomain(b));
  }

  /**
   * The registrable domain, approximately.
   *
   * A correct answer needs the Public Suffix List, which is a downloaded file that changes weekly
   * -- a dependency this project will not take for one check. So this handles the shape that
   * covers almost everything (`example.org`, and two-part suffixes like `co.uk`), and is
   * deliberately *conservative*: when it guesses wrong it makes alignment harder rather than
   * easier, so the failure is a message marked unaligned rather than a forgery marked fine.
   */
  static String organizationalDomain(String domain) {
    if (domain == null || domain.isBlank()) {
      return "";
    }
    String clean = domain.toLowerCase(Locale.ROOT);
    if (clean.endsWith(".")) {
      clean = clean.substring(0, clean.length() - 1);
    }
    String[] parts = clean.split("\\.");
    if (parts.length <= 2) {
      return clean;
    }
    String last = parts[parts.length - 1];
    String secondLast = parts[parts.length - 2];
    // the common two-part public suffixes; not the whole list, and not pretending to be
    boolean twoPart = (last.length() == 2 && SHORT_SECOND.contains("," + secondLast + ","))
        || secondLast.equals("com") && last.length() == 2;
    if (twoPart && parts.length >= 3) {
      return parts[parts.length - 3] + "." + secondLast + "." + last;
    }
    return secondLast + "." + last;
  }

  private static final String SHORT_SECOND = ",co,com,net,org,ac,gov,edu,ltd,plc,me,sch,nhs,";
}
