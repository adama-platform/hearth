package io.hearth.smtp;

/**
 * What SPF, DKIM and DMARC each said, and what that adds up to.
 *
 * The statuses are the RFCs' own vocabulary, and the distinctions in them are the whole point:
 * `fail` means the domain owner published a policy this message breaks, `none` means they published
 * nothing, and `temperror` means we could not find out. Collapsing those into a boolean is how a
 * mail system ends up rejecting everything from a domain whose DNS was briefly unreachable.
 */
public record AuthResult(Status spf, String spfDomain, Status dkim, String dkimDomain,
                         Status dmarc, String dmarcDomain, String dmarcPolicy) {

  public enum Status {
    /** the check passed */
    pass,
    /** the domain owner's policy says this message is not theirs */
    fail,
    /** SPF `~all`: the owner suspects it is not theirs but is not sure enough to say so */
    softfail,
    /** SPF `?all`: the owner is explicitly making no claim */
    neutral,
    /** DNS did not answer. Not a failure -- an absence of information, and worth retrying */
    temperror,
    /** the record exists and is malformed; retrying will not help */
    permerror,
    /** no record published at all, which is the common case and not suspicious by itself */
    none
  }

  public static AuthResult nothingChecked() {
    return new AuthResult(Status.none, null, Status.none, null, Status.none, null, "none");
  }

  public static AuthResult spfOnly(Status spf, String domain) {
    return new AuthResult(spf, domain, Status.none, null, Status.none, null, "none");
  }

  public AuthResult withDkim(Status status, String domain) {
    return new AuthResult(spf, spfDomain, status, domain, dmarc, dmarcDomain, dmarcPolicy);
  }

  public AuthResult withDmarc(Status status, String domain, String policy) {
    return new AuthResult(spf, spfDomain, dkim, dkimDomain, status, domain, policy);
  }

  /** did the domain owner ask for this to be rejected, and did it fail? */
  public boolean dmarcSaysReject() {
    return dmarc == Status.fail && "reject".equals(dmarcPolicy);
  }

  public boolean dmarcSaysQuarantine() {
    return dmarc == Status.fail && "quarantine".equals(dmarcPolicy);
  }

  /**
   * The header, in the shape RFC 8601 defines.
   *
   * Written onto the front of every message that arrives, whatever the outcome, because the point
   * of these checks is not only to refuse things -- it is that whatever handles the mail later can
   * see what was known at the moment it arrived rather than guessing afterwards.
   */
  public String toHeader(String hostname) {
    StringBuilder out = new StringBuilder();
    out.append("Authentication-Results: ").append(hostname);
    out.append("; spf=").append(spf.name());
    if (spfDomain != null && !spfDomain.isBlank()) {
      out.append(" smtp.mailfrom=").append(spfDomain);
    }
    out.append("; dkim=").append(dkim.name());
    if (dkimDomain != null && !dkimDomain.isBlank()) {
      out.append(" header.d=").append(dkimDomain);
    }
    out.append("; dmarc=").append(dmarc.name());
    if (dmarcDomain != null && !dmarcDomain.isBlank()) {
      out.append(" header.from=").append(dmarcDomain);
    }
    if (dmarcPolicy != null && !"none".equals(dmarcPolicy)) {
      out.append(" (p=").append(dmarcPolicy).append(')');
    }
    return out.toString();
  }

  /** one line for a terminal or a log */
  public String summary() {
    return "spf=" + spf + " dkim=" + dkim + " dmarc=" + dmarc
        + (dmarcPolicy == null || "none".equals(dmarcPolicy) ? "" : " p=" + dmarcPolicy);
  }
}
