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
   * An invitation to join.
   *
   * The only flow that carries a tracking pixel, and the only one where an open is worth knowing:
   * everything else here is sent because somebody asked for it seconds earlier, so whether they
   * opened it is answered by whether they signed in.
   */
  Outcome sendInvite(Envelope envelope, InviteMail.Invitation invitation);

  /**
   * One thing happened on the board and somebody asked to hear about it straight away.
   *
   * No pixel and no tracking. An invitation is sent to somebody who has not agreed to anything and
   * whether it arrived is a real question; this goes to a member who asked for it, and the answer
   * to "did they see it" is whether they turn up.
   */
  Outcome sendBoardNotice(Envelope envelope, Notice notice);

  /**
   * Everything that happened since the last one, as a single message.
   *
   * A digest is not a batch of notices -- it is one message with a shape, and the shape is what
   * makes it readable at breakfast. The list is already ordered and already deduplicated by the
   * time it gets here.
   */
  Outcome sendDigest(Envelope envelope, Digest digest);

  /**
   * An event, as a real calendar invitation.
   *
   * The one flow that sends a message a program reads rather than a person: `text/calendar` inside
   * `multipart/alternative`, which is what makes accept/maybe/decline appear as buttons above the
   * message in every mail client that matters. The person reads the other half.
   */
  Outcome sendEventInvite(Envelope envelope, EventInvite invite);

  /**
   * Everything the message around an invitation needs.
   *
   * @param ics the calendar file itself, already built by {@link io.hearth.calendar.Ics}
   * @param method REQUEST for an invitation or a change, CANCEL for one that is not happening.
   *     It goes on the content type as well as inside the file, because a client decides whether
   *     this is an invitation from the header before it parses anything.
   * @param replyTo the address answers come back to. Not the community's from-address: an answer
   *     is a message this server has to *receive*, which is a different thing from one it sends.
   */
  record EventInvite(String title, String when, String where, String body, String url,
                     String ics, String method, String replyTo, Note note) {
    public boolean cancelled() {
      return note == Note.cancelled;
    }

    public boolean changed() {
      return note == Note.changed;
    }
  }

  /**
   * Which of the four things this message is.
   *
   * An enum rather than a pair of booleans, because the pair had a fourth state nobody meant and no
   * room for the one that actually exists: a nudge sent to somebody who has not answered is not an
   * invitation, and giving it the invitation's wording is how a community ends up telling somebody
   * "you are invited" for the third time.
   */
  enum Note {
    invitation, changed, cancelled, reminder
  }

  /** what to say about one thing that happened, and where to go to see it */
  record Notice(String heading, String actor, String excerpt, String link) {
  }

  /** a day or a week of the above, gathered */
  record Digest(String period, java.util.List<Notice> items, String link, String settingsLink) {
    public int count() {
      return items.size();
    }
  }

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
