package io.hearth.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * What each flow actually says, in both halves, for every mailer.
 *
 * The wording lives here rather than inside {@link AmazonSes} because it is not a property of the
 * transport. A community's sign-in code should read the same whether it went out through Amazon or
 * was printed to a terminal, and the version that only exists in the provider implementation is the
 * one that quietly drifts -- which is how the terminal mailer ends up being the only place somebody
 * ever tested the wording.
 *
 * Every message here goes through {@link MailLayout}, so every message carries the community's
 * colours, says why it arrived, and links to the terms. That last one is not decoration: a person
 * receiving mail from a community is interacting with it, and this is the only place most of them
 * will ever be shown what that means.
 */
public final class Messages {
  /** a finished message: what it is called, and both halves of it */
  public record Built(String subject, String text, String html) {
  }

  private Messages() {
  }

  /**
   * The community's words for one flow, with the values put in.
   *
   * Everything below builds its message out of this rather than out of string literals: the subject,
   * the opening line and the paragraphs are the community's, and the shape around them is not. A
   * flow that skipped this would be a message an administrator could not change, which is the whole
   * thing this exists to stop.
   */
  private static Said say(Mailer.Envelope envelope, SystemTemplate template,
                          java.util.Map<String, String> extra) {
    MailBrand brand = envelope.brand();
    java.util.Map<String, String> values = SystemTemplates.common(brand);
    if (extra != null) {
      values.putAll(extra);
    }
    SystemTemplates.Wording wording = envelope.wording(template);
    return new Said(SystemTemplates.fill(wording.subject(), values),
        SystemTemplates.fill(wording.lead(), values),
        SystemTemplates.fill(wording.body(), values));
  }

  /** the three parts a community controls, with the values already in them */
  private record Said(String subject, String lead, String body) {
    boolean anyBody() {
      return body != null && !body.isBlank();
    }
  }

  private static java.util.Map<String, String> values(String... pairs) {
    java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
    for (int k = 0; k + 1 < pairs.length; k += 2) {
      out.put(pairs[k], pairs[k + 1] == null ? "" : pairs[k + 1]);
    }
    return out;
  }

  public static Built registrationCode(Mailer.Envelope envelope, String code, long minutes) {
    return withCode(envelope, SystemTemplate.register_code, code, minutes,
        "You are receiving this because somebody asked to create an account at "
            + envelope.brand().domain() + " with this address.");
  }

  /**
   * The three code messages, which are one message with a different sentence in it.
   *
   * They differ in what the community says and in nothing else -- the code block, the note about
   * expiry and the footer are the same three things in the same order, and writing them out three
   * times is how the third one ends up missing the line about ignoring it.
   */
  private static Built withCode(Mailer.Envelope envelope, SystemTemplate template, String code,
                                long minutes, String why) {
    MailBrand brand = envelope.brand();
    Said said = say(envelope, template, values("code", code, "minutes", Long.toString(minutes)));
    MailLayout layout = new MailLayout(brand, said.subject(), "Your code is " + code)
        .because(why)
        .lead(said.lead());
    if (said.anyBody()) {
      layout.paragraph(said.body());
    }
    layout.code(code);
    return new Built(said.subject(),
        said.lead() + "\n\n" + (said.anyBody() ? said.body() + "\n\n" : "")
            + "  " + code + "\n"
            + MailLayout.textFooter(brand, why),
        layout.html(null));
  }

  public static Built loginCode(Mailer.Envelope envelope, String code, long minutes) {
    return withCode(envelope, SystemTemplate.login_code, code, minutes,
        "You are receiving this because somebody asked to sign in at "
            + envelope.brand().domain() + " with this address.");
  }

  public static Built passwordReset(Mailer.Envelope envelope, String code, String link,
                                    long minutes) {
    MailBrand brand = envelope.brand();
    String why = "You are receiving this because somebody asked to reset a password at "
        + brand.domain() + ".";
    Said said = say(envelope, SystemTemplate.password_reset,
        values("code", code, "link", link, "minutes", Long.toString(minutes)));
    MailLayout layout = new MailLayout(brand, said.subject(), "Your code is " + code)
        .because(why)
        .lead(said.lead())
        .code(code);
    if (link != null && !link.isBlank()) {
      layout.button("Choose a new password", link).linkAsText(link);
    }
    if (said.anyBody()) {
      layout.note(said.body());
    }
    StringBuilder text = new StringBuilder();
    text.append(said.lead()).append("\n\n").append("  ").append(code).append("\n\n");
    if (link != null && !link.isBlank()) {
      text.append("Or open:\n").append(link).append("\n\n");
    }
    if (said.anyBody()) {
      text.append(said.body()).append('\n');
    }
    text.append(MailLayout.textFooter(brand, why));
    return new Built(said.subject(), text.toString(), layout.html(null));
  }

  public static Built twoFactorCode(Mailer.Envelope envelope, String code, long minutes) {
    return withCode(envelope, SystemTemplate.two_factor, code, minutes,
        "You are receiving this because your password was accepted at "
            + envelope.brand().domain() + " and this community asks for a second step.");
  }

  public static Built passwordChanged(Mailer.Envelope envelope) {
    MailBrand brand = envelope.brand();
    String why = "You are receiving this because the password on your account at " + brand.domain()
        + " was changed.";
    Said said = say(envelope, SystemTemplate.password_changed, null);
    MailLayout layout = new MailLayout(brand, said.subject(), "This is a notification.")
        .because(why)
        .lead(said.lead());
    if (said.anyBody()) {
      layout.note(said.body());
    }
    return new Built(said.subject(),
        said.lead() + "\n\n" + (said.anyBody() ? said.body() + "\n" : "")
            + MailLayout.textFooter(brand, why),
        layout.html(null));
  }

  public static Built boardNotice(Mailer.Envelope envelope, Mailer.Notice notice) {
    MailBrand brand = envelope.brand();
    String why = "You are receiving this because you are following that conversation at "
        + brand.domain() + ". Change what is worth an email, or stop them, at "
        + brand.settingsUrl() + " -- your inbox on the site keeps everything either way.";
    Said said = say(envelope, SystemTemplate.board_notice,
        values("who", notice.actor(), "what", notice.heading(), "excerpt", notice.excerpt(),
            "link", notice.link()));
    String headline = said.subject();
    MailLayout layout = new MailLayout(brand, headline, headline)
        .because(why)
        .lead(said.lead());
    if (notice.excerpt() != null && !notice.excerpt().isBlank()) {
      layout.quote(notice.excerpt());
    }
    if (said.anyBody()) {
      layout.paragraph(said.body());
    }
    layout.button("Read it", notice.link()).linkAsText(notice.link());
    StringBuilder text = new StringBuilder(said.lead() + "\n\n");
    if (notice.excerpt() != null && !notice.excerpt().isBlank()) {
      text.append(notice.excerpt()).append("\n\n");
    }
    text.append("Read it here:\n").append(notice.link()).append('\n')
        .append(MailLayout.textFooter(brand, why));
    return new Built(headline, text.toString(), layout.html(null));
  }

  /**
   * The words around a calendar invitation.
   *
   * A person and a program both read this message, and they read different halves. The calendar part
   * is what draws the buttons; this is what tells somebody what they are being asked to, in the two
   * sentences they will actually read before deciding.
   *
   * A community can replace the subject, the opening line and the paragraph under it, at
   * /admin/messages like every other message. What it cannot replace is the shape: the title, the
   * day, the calendar part and the way back are what make the message answerable, and wording that
   * could lose them would eventually lose them.
   */
  public static Built eventInvite(Mailer.Envelope envelope, Mailer.EventInvite invite) {
    MailBrand brand = envelope.brand();
    String why = invite.cancelled()
        ? "You are receiving this because you were invited to this, at " + brand.domain() + "."
        : "You are receiving this because you are a member of " + brand.nameOr()
            + ". Answering from your calendar is enough -- it comes straight back here.";
    SystemTemplate which = switch (invite.note()) {
      case cancelled -> SystemTemplate.event_cancelled;
      case changed -> SystemTemplate.event_changed;
      case reminder -> SystemTemplate.event_reminder;
      case invitation -> SystemTemplate.event_invite;
    };
    Said said = say(envelope, which, values("title", invite.title(), "when", invite.when(),
        "where", invite.where(), "details", invite.body(), "link", invite.url()));
    String subject = said.subject();
    String lead = said.lead();

    MailLayout layout = new MailLayout(brand, subject, lead).because(why).lead(lead);
    if (invite.where() != null && !invite.where().isBlank()) {
      layout.paragraph("Where: " + invite.where());
    }
    if (invite.body() != null && !invite.body().isBlank()) {
      layout.quote(invite.body());
    }
    if (said.anyBody()) {
      layout.note(said.body());
    }
    layout.button(invite.cancelled() ? "See what else is on" : "See it on the site", invite.url())
        .linkAsText(invite.url());

    StringBuilder text = new StringBuilder(lead + "\n\n");
    if (invite.where() != null && !invite.where().isBlank()) {
      text.append("Where: ").append(invite.where()).append('\n');
    }
    if (invite.body() != null && !invite.body().isBlank()) {
      text.append('\n').append(invite.body()).append('\n');
    }
    if (said.anyBody()) {
      text.append('\n').append(said.body()).append('\n');
    }
    text.append("\n").append(invite.url()).append('\n')
        .append(MailLayout.textFooter(brand, why));
    return new Built(subject, text.toString(), layout.html(null));
  }

  public static Built digest(Mailer.Envelope envelope, Mailer.Digest digest) {
    MailBrand brand = envelope.brand();
    String why = "You are receiving this because you asked for a summary rather than a message"
        + " each time. Change it, or stop these, at " + brand.settingsUrl() + ".";
    Said said = say(envelope, SystemTemplate.digest,
        values("count", Integer.toString(digest.count()), "period", digest.period(),
            "link", digest.link()));
    String subject = said.subject();
    List<String> lines = new ArrayList<>();
    for (Mailer.Notice item : digest.items()) {
      lines.add(item.actor() + " " + item.heading());
    }
    MailLayout layout = new MailLayout(brand, subject, subject)
        .because(why)
        .lead(said.lead())
        .items(lines)
        .button("Open the board", digest.link())
        .linkAsText(digest.link());
    StringBuilder text = new StringBuilder(said.lead() + "\n\n");
    for (String line : lines) {
      text.append("- ").append(line).append('\n');
    }
    text.append("\nThe board:\n").append(digest.link()).append('\n');
    if (digest.settingsLink() != null) {
      layout.note("You can change how often you hear from us: " + digest.settingsLink());
      text.append("\nChange how often you hear from us:\n").append(digest.settingsLink())
          .append('\n');
    }
    text.append(MailLayout.textFooter(brand, why));
    return new Built(subject, text.toString(), layout.html(null));
  }

  /** the invitation, which builds its own body because it has a shape nothing else has */
  /**
   * An invitation, which keeps its own designed layout and takes its words from the same place.
   *
   * The only flow with markup of its own -- a bulletproof button, a tagline, the community's
   * argument for itself -- because it goes to somebody who has never seen this site and has three
   * seconds of attention. The words it puts in that markup are the community's like everywhere
   * else.
   */
  public static Built invite(Mailer.Envelope envelope, InviteMail.Invitation invitation) {
    MailBrand brand = envelope.brand();
    SystemTemplate which = switch (invitation.touch()) {
      case welcome -> SystemTemplate.invite_welcome;
      case reminder -> SystemTemplate.invite_reminder;
      case apology -> SystemTemplate.invite_apology;
    };
    Said said = say(envelope, which, values("inviter", invitation.inviter(),
        "about", invitation.config().about, "tagline", invitation.config().tagline,
        "link", invitation.link()));
    return new Built(said.subject(), InviteMail.text(brand, invitation, said.lead(), said.body()),
        InviteMail.html(brand, invitation, said.lead(), said.body()));
  }
}
