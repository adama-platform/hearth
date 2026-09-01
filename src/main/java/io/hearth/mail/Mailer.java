package io.hearth.mail;

/**
 * Everything this server needs to say to somebody by email.
 *
 * Deliberately a short, closed list of flows rather than a generic send(subject, body). Each method
 * is a decision the auth system already made, so a real provider implementation is a template per
 * method and nothing else -- and, more importantly, nothing in a request handler can invent a new
 * kind of email without this interface growing a method and somebody noticing.
 *
 * Implementations must not block for long on the request path and must not throw for a delivery
 * failure a person could retry; return the outcome and let the caller decide what to tell them.
 *
 * {@link DevBoxMailer} is the only implementation today: it prints to the terminal so a developer
 * can copy the code out. An SES or SMTP implementation drops in beside it.
 */
public interface Mailer {
  /** prove a new address before an account exists */
  Outcome sendRegistrationCode(Envelope envelope, String code);

  /** passwordless sign-in: the code IS the credential */
  Outcome sendLoginCode(Envelope envelope, String code);

  /**
   * Forgot password. Stubbed at the flow level, not here: the link is generated and delivered, and
   * a real provider would render it into a template.
   */
  Outcome sendPasswordReset(Envelope envelope, String code, String link);

  /** second factor, for accounts that carry a password and something else */
  Outcome sendTwoFactorCode(Envelope envelope, String code);

  /** told after the fact, so somebody who did not do it knows to come find you */
  Outcome sendPasswordChanged(Envelope envelope);

  /**
   * Who the mail is for, which community is sending it, and what that community looks like.
   *
   * The brand rides on the envelope rather than being looked up by the mailer, because the mailer
   * is a transport: it knows how to reach Amazon, not which colours a community chose or where its
   * terms are. Callers that have an {@link io.hearth.auth.Accounts} in hand pass the real one; the
   * four-argument form is for the test-email walkthrough and for tests, where there is no community
   * to ask and the defaults are the honest answer.
   */
  record Envelope(String domain, String communityName, String email, String ip, MailBrand brand,
                  SystemTemplates words) {
    public Envelope {
      brand = brand == null ? MailBrand.standard(domain, communityName) : brand;
    }

    public Envelope(String domain, String communityName, String email, String ip) {
      this(domain, communityName, email, ip, null, null);
    }

    public Envelope(String domain, String communityName, String email, String ip, MailBrand brand) {
      this(domain, communityName, email, ip, brand, null);
    }

    /**
     * What this community says for one flow, or what the software ships.
     *
     * The wording rides on the envelope for the same reason the colours do: the mailer knows how to
     * reach Amazon and nothing about which words a community chose, and looking them up down there
     * would mean a database handle in the one class that should not have one.
     */
    public SystemTemplates.Wording wording(SystemTemplate template) {
      if (words != null) {
        return words.of(template);
      }
      return new SystemTemplates.Wording(template, template.subject, template.lead, template.body,
          false, null, null);
    }

    /** the envelope for somebody at this community, wearing the colours it chose */
    public static Envelope to(io.hearth.vhost.DomainConfig config,
                              io.hearth.auth.Accounts accounts, String email, String ip) {
      return new Envelope(config.domain, config.name, email, ip, brandOf(config, accounts),
          accounts == null ? null : accounts.messages);
    }

    public static MailBrand brandOf(io.hearth.vhost.DomainConfig config,
                                    io.hearth.auth.Accounts accounts) {
      if (accounts == null) {
        return MailBrand.standard(config.domain, config.name);
      }
      return new MailBrand(config.domain, config.name,
          accounts.themes.of(io.hearth.theme.Theme.Scope.site).light);
    }
  }

  /** what happened; never an exception for something the person could just try again */
  record Outcome(boolean delivered, String detail) {
    public static Outcome ok() {
      return new Outcome(true, "delivered");
    }

    public static Outcome ok(String detail) {
      return new Outcome(true, detail);
    }

    public static Outcome failed(String detail) {
      return new Outcome(false, detail);
    }
  }
}
