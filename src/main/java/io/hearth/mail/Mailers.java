package io.hearth.mail;

import io.hearth.common.Verbose;
import io.hearth.vhost.DomainConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which mailer sends for which community.
 *
 * Credentials are per domain, so delivery has to be too: a community that has set SES up gets real
 * email, and one on the same server that has not still gets its codes printed to the terminal. That
 * mixture is the normal state of a box where one community is live and another is being set up, and
 * it should not require two servers.
 *
 * This implements {@link Mailer} itself and dispatches on the envelope's domain, so nothing upstream
 * has to know that mailers are plural -- {@link io.hearth.web.AccountRoutes} still takes one mailer
 * and still calls one method.
 */
public class Mailers implements Mailer {
  private final Map<String, Mailer> byDomain = new TreeMap<>();
  private final Mailer fallback;

  public Mailers(Mailer fallback) {
    this.fallback = fallback;
  }

  /** build from the domain tree, giving anything with SES configured a real mailer */
  public static Mailers of(Map<String, DomainConfig> domains, Verbose verbose) {
    Mailers mailers = new Mailers(new DevBoxMailer());
    for (Map.Entry<String, DomainConfig> entry : domains.entrySet()) {
      SesConfig ses = entry.getValue().ses;
      if (ses != null && ses.enabled) {
        mailers.byDomain.put(entry.getKey(), new AmazonSes(ses, verbose));
      }
    }
    return mailers;
  }

  public Mailer forDomain(String domain) {
    Mailer mailer = byDomain.get(domain);
    return mailer == null ? fallback : mailer;
  }

  /** what the boot report shows, one line per domain */
  public Map<String, String> describe(Map<String, DomainConfig> domains) {
    LinkedHashMap<String, String> said = new LinkedHashMap<>();
    for (Map.Entry<String, DomainConfig> entry : domains.entrySet()) {
      SesConfig ses = entry.getValue().ses;
      said.put(entry.getKey(), ses == null ? "terminal" : ses.describe());
    }
    return said;
  }

  /** how many communities send real email; the rest print to the terminal */
  public int realCount() {
    return byDomain.size();
  }

  @Override
  public Outcome sendRegistrationCode(Envelope envelope, String code) {
    return forDomain(envelope.domain()).sendRegistrationCode(envelope, code);
  }

  @Override
  public Outcome sendLoginCode(Envelope envelope, String code) {
    return forDomain(envelope.domain()).sendLoginCode(envelope, code);
  }

  @Override
  public Outcome sendPasswordReset(Envelope envelope, String code, String link) {
    return forDomain(envelope.domain()).sendPasswordReset(envelope, code, link);
  }

  @Override
  public Outcome sendTwoFactorCode(Envelope envelope, String code) {
    return forDomain(envelope.domain()).sendTwoFactorCode(envelope, code);
  }

  @Override
  public Outcome sendPasswordChanged(Envelope envelope) {
    return forDomain(envelope.domain()).sendPasswordChanged(envelope);
  }

  @Override
  public Outcome sendInvite(Envelope envelope, InviteMail.Invitation invitation) {
    return forDomain(envelope.domain()).sendInvite(envelope, invitation);
  }

  @Override
  public Outcome sendBoardNotice(Envelope envelope, Notice notice) {
    return forDomain(envelope.domain()).sendBoardNotice(envelope, notice);
  }

  @Override
  public Outcome sendDigest(Envelope envelope, Digest digest) {
    return forDomain(envelope.domain()).sendDigest(envelope, digest);
  }

  @Override
  public Outcome sendEventInvite(Envelope envelope, EventInvite invite) {
    return forDomain(envelope.domain()).sendEventInvite(envelope, invite);
  }
}
