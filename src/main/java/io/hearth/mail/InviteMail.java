package io.hearth.mail;

import io.hearth.people.InviteConfig;

/**
 * The invitation, as HTML that survives contact with email clients.
 *
 * Email is not the web. Outlook renders with Word's engine, Gmail strips `<style>` blocks and
 * anything it does not recognise, and every client disagrees about defaults. So this is written the
 * way email has been written for twenty years, and every rule below is a rule because something
 * breaks without it:
 *
 * <ul>
 *   <li><b>Tables for layout, not divs.</b> Outlook's engine has no meaningful float or flexbox.</li>
 *   <li><b>Inline styles only.</b> Gmail discards a stylesheet, so a class-based design arrives
 *       unstyled.</li>
 *   <li><b>A bulletproof button</b> -- a table cell with a background colour and padding, with the
 *       link filling it. A styled `&lt;a&gt;` collapses to blue underlined text in Outlook, and a
 *       button image does not exist for the majority of people who block images.</li>
 *   <li><b>600 pixels wide, and fluid below that.</b> The width every client has agreed on since
 *       before responsive design, with `max-width` so a phone does not scroll sideways.</li>
 *   <li><b>Colours that work on both.</b> Dark-mode clients invert backgrounds unpredictably, so
 *       nothing here depends on a light background being light: the text is dark on a light card
 *       that is explicitly set, and the button's text colour is set on the link itself.</li>
 *   <li><b>The link in text as well as in the button.</b> Some clients disable links entirely, and
 *       somebody who cannot click needs something to copy.</li>
 *   <li><b>A plain-text alternative</b>, always. It is what a screen reader and a text client get,
 *       and sending HTML alone is a deliverability problem as well as an accessibility one.</li>
 * </ul>
 *
 * The pixel goes last, one by one, with empty alt text so a client that blocks it shows nothing
 * rather than a broken-image icon where the sign-off should be.
 */
public final class InviteMail {
  /** the width every mail client has agreed on for two decades */
  private static final int WIDTH = 600;

  private InviteMail() {
  }

  /** which of the three messages this is */
  public enum Touch {
    welcome, reminder, apology;

    public static Touch forCount(int touchesAlreadySent) {
      return switch (touchesAlreadySent) {
        case 0 -> welcome;
        case 1 -> reminder;
        default -> apology;
      };
    }
  }

  /** everything the three messages share, gathered so a caller passes one thing */
  public record Invitation(String community, String domain, Touch touch, String link, String pixel,
                           String note, String inviter, InviteConfig config) {
  }

  public static String subjectFor(Invitation invitation) {
    String community = invitation.community();
    return switch (invitation.touch()) {
      case welcome -> invitation.inviter() == null || invitation.inviter().isBlank()
          ? "You are invited to " + community
          : invitation.inviter() + " invited you to " + community;
      case reminder -> "Still a place for you at " + community;
      case apology -> "Last note from " + community;
    };
  }

  /** the sentence at the top, which is the only part that changes much between the three */
  static String leadFor(Invitation invitation) {
    String community = invitation.community();
    String who = invitation.inviter() == null || invitation.inviter().isBlank()
        ? "Somebody" : invitation.inviter();
    return switch (invitation.touch()) {
      case welcome -> who + " invited you to join " + community + ".";
      case reminder -> "A little while ago you were invited to " + community + ". The invitation "
          + "is still open -- this is just in case it got buried.";
      case apology -> "Sorry to write again. This is the last one, and then we will leave you "
          + "alone: your invitation to " + community + " is still there if you want it.";
    };
  }

  static String closingFor(Invitation invitation) {
    return switch (invitation.touch()) {
      case welcome -> "";
      case reminder -> "If this is not for you, you can ignore it -- nothing else happens.";
      case apology -> "You will not hear from us about this again. Ignoring this is enough; there "
          + "is nothing to unsubscribe from, because you were never signed up to anything.";
    };
  }

  /** the plain-text half, which is what a screen reader and a text client actually get */
  public static String text(MailBrand brand, Invitation invitation) {
    return text(brand, invitation, leadFor(invitation), "");
  }

  /**
   * @param lead and @param extra come from this community's own wording, so an invitation sounds
   *     like the people sending it rather than like this program. The shape around them does not
   *     move: an invitation is the one message here read by somebody who has never seen the site.
   */
  public static String text(MailBrand brand, Invitation invitation, String lead, String extra) {
    StringBuilder body = new StringBuilder();
    body.append(invitation.community()).append('\n');
    if (!invitation.config().taglineFor(invitation.community()).isBlank()) {
      body.append(invitation.config().taglineFor(invitation.community())).append('\n');
    }
    body.append('\n').append(lead).append("\n\n");
    if (extra != null && !extra.isBlank()) {
      body.append(extra).append("\n\n");
    }
    if (invitation.note() != null && !invitation.note().isBlank()) {
      body.append('"').append(invitation.note().trim()).append("\"\n\n");
    }
    if (invitation.touch() == Touch.welcome
        && !invitation.config().aboutFor(invitation.community()).isBlank()) {
      body.append(invitation.config().aboutFor(invitation.community())).append("\n\n");
    }
    body.append(invitation.config().callToAction).append(":\n")
        .append(invitation.link()).append("\n\n");
    String closing = closingFor(invitation);
    if (!closing.isEmpty()) {
      body.append(closing).append("\n\n");
    }
    if (!invitation.config().signOff.isBlank()) {
      body.append(invitation.config().signOff).append('\n');
    }
    body.append(MailLayout.textFooter(brand, whyFor(invitation)));
    return body.toString();
  }

  /** what the footer says about how this arrived; an invitation is the one that needs explaining */
  static String whyFor(Invitation invitation) {
    String who = invitation.inviter() == null || invitation.inviter().isBlank()
        ? "Somebody at " + invitation.domain() : invitation.inviter();
    return "You are receiving this because " + who + " invited this address to "
        + invitation.community() + ". You do not have an account there and nothing has been"
        + " created for you.";
  }

  /**
   * The HTML half, on the same layout as every other message.
   *
   * It used to build its own document, which is how it came to be the only mail this server sent
   * that looked designed -- and, once communities could choose colours, the only one that would
   * have ignored them. What is left here is the parts an invitation has and nothing else does: a
   * tagline, the note whoever invited them wrote, and the sentence about what the community is.
   */
  public static String html(MailBrand brand, Invitation invitation) {
    return html(brand, invitation, leadFor(invitation), "");
  }

  public static String html(MailBrand brand, Invitation invitation, String lead, String extra) {
    InviteConfig config = invitation.config();
    MailLayout layout = new MailLayout(brand, subjectFor(invitation), lead)
        .because(whyFor(invitation));
    if (!config.taglineFor(invitation.community()).isBlank()) {
      layout.note(config.taglineFor(invitation.community()));
    }
    layout.lead(lead);
    if (invitation.note() != null && !invitation.note().isBlank()) {
      layout.quote(invitation.note().trim());
    }
    if (extra != null && !extra.isBlank()) {
      layout.paragraph(extra);
    } else if (invitation.touch() == Touch.welcome
        && !config.aboutFor(invitation.community()).isBlank()) {
      layout.paragraph(config.aboutFor(invitation.community()));
    }
    layout.button(config.callToAction, invitation.link());
    layout.linkAsText(invitation.link());
    String closing = closingFor(invitation);
    if (!closing.isEmpty()) {
      layout.note(closing);
    }
    if (!config.signOff.isBlank()) {
      layout.note(config.signOff);
    }
    return layout.html(invitation.pixel());
  }

  /** HTML escaping, including quotes, since half of this is inside attributes */
  static String esc(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int k = 0; k < value.length(); k++) {
      char ch = value.charAt(k);
      switch (ch) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(ch);
      }
    }
    return out.toString();
  }
}
