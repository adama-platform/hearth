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




  /** the invitation, which builds its own body because it has a shape nothing else has */
}
