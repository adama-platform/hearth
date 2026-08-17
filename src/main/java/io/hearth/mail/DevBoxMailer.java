package io.hearth.mail;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The mailer for a machine with no email provider: it prints the message to the terminal.
 *
 * This exists because the alternative -- wiring up SES before you can test a signup form -- is how
 * a login flow goes untested for a month. The code is printed on its own line, spaced out and
 * unadorned, so it can be double-clicked and pasted straight into the form.
 *
 * It refuses to be anything other than a development tool. There is no configuration that makes it
 * quiet, because a server that thinks it is sending email while printing secrets to stdout is worse
 * than one that obviously is not sending email.
 */
public class DevBoxMailer implements Mailer {
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final String RESET = "\u001B[0m";
  private static final String BOLD = "\u001B[1m";
  private static final String DIM = "\u001B[2m";
  private static final String YELLOW = "\u001B[33m";
  private static final String CYAN = "\u001B[36m";

  private final PrintStream out;
  private final boolean color;

  public DevBoxMailer() {
    this(System.out, System.console() != null && System.getenv("NO_COLOR") == null);
  }

  public DevBoxMailer(PrintStream out, boolean color) {
    this.out = out;
    this.color = color;
  }

  @Override
  public Outcome sendRegistrationCode(Envelope envelope, String code) {
    return print(envelope, "register", "Confirm your address to finish signing up.", code, null);
  }

  @Override
  public Outcome sendLoginCode(Envelope envelope, String code) {
    return print(envelope, "sign in", "Your sign-in code.", code, null);
  }

  @Override
  public Outcome sendPasswordReset(Envelope envelope, String code, String link) {
    return print(envelope, "reset password", "Use this code to choose a new password.", code, link);
  }

  @Override
  public Outcome sendTwoFactorCode(Envelope envelope, String code) {
    return print(envelope, "two factor", "Your second factor code.", code, null);
  }

  @Override
  public Outcome sendPasswordChanged(Envelope envelope) {
    return print(envelope, "password changed", "Your password was just changed. If that wasn't you, come find an admin.", null, null);
  }

  @Override
  public Outcome sendInvite(Envelope envelope, InviteMail.Invitation invitation) {
    // the pixel is not printed: on a terminal it is a URL nobody will fetch, and showing it would
    // suggest the open tracking works here when it cannot
    return print(envelope, "invitation (" + invitation.touch() + ")",
        InviteMail.subjectFor(invitation), null, invitation.link());
  }

  @Override
  public Outcome sendBoardNotice(Envelope envelope, Notice notice) {
    return print(envelope, "board notice", notice.actor() + " " + notice.heading(), null,
        notice.link());
  }

  @Override
  public Outcome sendDigest(Envelope envelope, Digest digest) {
    // one line per item rather than the print() shape: a digest whose whole content is a count is
    // a digest that has not been tested against anything real
    StringBuilder lines = new StringBuilder();
    lines.append(digest.count()).append(" thing(s) ").append(digest.period());
    for (Notice item : digest.items()) {
      lines.append("\n  | ").append("  - ").append(item.actor()).append(' ')
          .append(item.heading());
    }
    return print(envelope, "digest", lines.toString(), null, digest.link());
  }

  @Override
  public Outcome sendEventInvite(Envelope envelope, EventInvite invite) {
    // the calendar file itself is printed, because the thing most likely to be wrong about an
    // invitation is the file, and an operator watching a terminal can paste it into a validator
    StringBuilder lines = new StringBuilder();
    lines.append(switch (invite.note()) {
          case cancelled -> "CANCELLED: ";
          case changed -> "CHANGED: ";
          case reminder -> "REMINDER: ";
          case invitation -> "";
        })
        .append(invite.title()).append(" -- ").append(invite.when());
    if (invite.where() != null && !invite.where().isBlank()) {
      lines.append(" at ").append(invite.where());
    }
    lines.append("\n  | replies to: ").append(invite.replyTo());
    for (String line : invite.ics().split("\r\n")) {
      lines.append("\n  |   ").append(line);
    }
    return print(envelope, "event-invite (" + invite.method() + ")", lines.toString(), null,
        invite.url());
  }

  private Outcome print(Envelope envelope, String flow, String line, String code, String link) {
    StringBuilder sb = new StringBuilder();
    sb.append('\n');
    sb.append(paint(CYAN, "  +-- email ------------------------------------------------")).append('\n');
    sb.append(paint(DIM, "  | " + LocalTime.now().format(CLOCK) + "  " + flow)).append('\n');
    sb.append(paint(DIM, "  | to:   ")).append(envelope.email()).append('\n');
    sb.append(paint(DIM, "  | from: ")).append(envelope.communityName()).append(" <no-reply@").append(envelope.domain()).append(">").append('\n');
    sb.append(paint(DIM, "  |")).append('\n');
    sb.append(paint(DIM, "  | ")).append(line).append('\n');
    if (code != null) {
      sb.append(paint(DIM, "  |")).append('\n');
      // on its own line, nothing around it: this is the bit that gets copied
      sb.append(paint(DIM, "  |     ")).append(paint(BOLD + YELLOW, code)).append('\n');
    }
    if (link != null) {
      sb.append(paint(DIM, "  |")).append('\n');
      sb.append(paint(DIM, "  |     ")).append(link).append('\n');
    }
    // the real message carries this in its footer, so the terminal shows it too -- a developer who
    // never sees the footer is a developer who never notices it is pointing at the wrong domain
    sb.append(paint(DIM, "  |")).append('\n');
    sb.append(paint(DIM, "  | terms: " + envelope.brand().termsUrl())).append('\n');
    sb.append(paint(CYAN, "  +---------------------------------------------------------")).append('\n');
    out.print(sb);
    out.flush();
    return Outcome.ok("printed to the terminal");
  }

  private String paint(String code, String text) {
    return color ? code + text + RESET : text;
  }
}
