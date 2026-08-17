package io.hearth.people;

import io.hearth.auth.Accounts;
import io.hearth.auth.Tokens;
import io.hearth.mail.InviteMail;
import io.hearth.mail.Mailer;
import io.hearth.vhost.DomainConfig;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Putting an invitation in the post.
 *
 * One path for all three callers -- an admin sending one, a member inviting a friend, and the
 * reminder loop sending the second and third messages. They differ only in who is asking, and
 * having one place means the refusals (already a member, banned, over the daily limit) cannot be
 * enforced in two ways that eventually disagree.
 *
 * The three messages are the same invitation, not three invitations. One row, one token, one link,
 * a `touches` count and a due date -- so an open recorded against the third message is an open of
 * the invitation, and somebody who joins after the second is one conversion.
 */
public class Invitations {
  /** the most a bulk paste will accept at once; past this it is a mailing list, not an invitation */
  public static final int MAX_BULK = 200;

  private final Mailer mailer;

  public Invitations(Mailer mailer) {
    this.mailer = mailer;
  }

  /** what happened to one address */
  public record Result(String email, boolean ok, String detail) {
  }

  /**
   * Write one and, optionally, send it.
   *
   * `by` is the person asking. A null actor is the reminder loop, which is never subject to the
   * daily limit -- it is finishing something a person already started.
   */
  public Result invite(DomainConfig config, Accounts accounts, String rawEmail, String note,
                       Long by, String byEmail, boolean send, boolean enforceLimit)
      throws SQLException {
    String email = Tokens.normalizeEmail(rawEmail);
    if (!Tokens.looksLikeEmail(email)) {
      return new Result(String.valueOf(rawEmail), false, "does not look like an email address");
    }
    if (accounts.users.byEmail(email) != null) {
      return new Result(email, false, "already has an account here");
    }
    if (accounts.bans.isBanned(email)) {
      return new Result(email, false, "banned; lift the ban first");
    }
    if (enforceLimit && by != null && config.invites.memberDailyLimit > 0
        && accounts.invites.sentTodayBy(by) >= config.invites.memberDailyLimit) {
      return new Result(email, false,
          "you have written " + config.invites.memberDailyLimit + " today, which is the limit");
    }
    // An invitation says who is asking, and it has to say a name.
    //
    // "ana@example.com invited you to join" is an address, not a person, and it is the single
    // worst line in an invitation from a community whose whole argument is that there are people
    // at the end of it. So a name is what makes an invitation sendable, which also means the
    // first thing this server asks of anybody is the one thing every message it sends depends on.
    String byName = nameOf(accounts, by);
    if (by != null && byName.isBlank()) {
      return new Result(email, false,
          "add your name to your profile first -- an invitation says who is asking");
    }
    Invites.Invite invite = accounts.invites.create(email, note, by, byEmail, byName);
    if (!send) {
      return new Result(email, true, "written, not sent");
    }
    return sendTouch(config, accounts, invite);
  }

  /** what the message calls them: the name they had when they sent it, never their address */
  private static String inviterOf(Invites.Invite invite) {
    String name = invite.createdByName();
    return name == null || name.isBlank() ? "" : name;
  }

  private static String nameOf(Accounts accounts, Long userId) {
    if (userId == null) {
      return "";
    }
    try {
      io.hearth.people.ProfileRecord profile = accounts.people.profileOf(userId);
      return profile == null || profile.displayName() == null ? "" : profile.displayName().trim();
    } catch (java.sql.SQLException ex) {
      return "";
    }
  }

  /**
   * Send whichever of the three messages is next for this invitation.
   *
   * The touch is decided from the row rather than passed in, so the same call sends a welcome the
   * first time and a reminder the second, and nothing upstream has to keep count.
   */
  public Result sendTouch(DomainConfig config, Accounts accounts, Invites.Invite invite)
      throws SQLException {
    InviteMail.Touch touch = InviteMail.Touch.forCount(invite.touches());
    String link = "https://" + config.domain + config.urls.register + "?invite=" + invite.token();
    String pixel = InvitePixel.urlFor(config.domain, invite.token(), true);
    InviteMail.Invitation invitation = new InviteMail.Invitation(config.name, config.domain, touch,
        link, pixel, invite.note(), inviterOf(invite), config.invites);

    Mailer.Outcome outcome = mailer.sendInvite(
        Mailer.Envelope.to(config, accounts, invite.email(), null), invitation);

    // Stamped whatever the mailer said. A failure that left the row untouched would be retried by
    // the loop every minute forever, which turns one bad address into a reputation problem.
    int next = config.invites.daysUntilTouch(invite.touches() + 1);
    accounts.invites.markTouched(invite.id(), outcome.detail(), LocalDate.now(), next);
    return new Result(invite.email(), outcome.delivered(),
        outcome.delivered() ? touch + " sent" : outcome.detail());
  }

  /**
   * A pasted list of addresses.
   *
   * Deduplicated, because a list pasted out of a spreadsheet has the same person on it twice more
   * often than not, and two invitations to one address is how a community introduces itself as
   * careless. Every address gets its own result: a bulk operation that reports only "12 sent"
   * hides the three that did not.
   */
  public List<Result> bulk(DomainConfig config, Accounts accounts, String pasted, String note,
                           Long by, String byEmail, boolean send) throws SQLException {
    ArrayList<Result> results = new ArrayList<>();
    for (String email : addressesIn(pasted)) {
      results.add(invite(config, accounts, email, note, by, byEmail, send, false));
    }
    return results;
  }

  /**
   * Pull addresses out of whatever somebody pasted.
   *
   * Commas, semicolons, newlines, tabs, and "Name &lt;addr@example.org&gt;" -- because that is what
   * comes out of a mail client, and telling somebody to reformat their list is telling them to use
   * a spreadsheet instead of this.
   */
  public static List<String> addressesIn(String pasted) {
    LinkedHashSet<String> found = new LinkedHashSet<>();
    if (pasted == null) {
      return new ArrayList<>();
    }
    for (String chunk : pasted.split("[,;\\n\\r\\t]+")) {
      String piece = chunk.trim();
      if (piece.isEmpty()) {
        continue;
      }
      int open = piece.indexOf('<');
      int close = piece.indexOf('>', open + 1);
      if (open >= 0 && close > open) {
        piece = piece.substring(open + 1, close).trim();
      }
      // a bare word with no @ is a name that lost its address, not an address
      if (!piece.contains("@")) {
        continue;
      }
      String normalized = Tokens.normalizeEmail(piece);
      if (normalized != null && !normalized.isEmpty()) {
        found.add(normalized);
      }
      if (found.size() >= MAX_BULK) {
        break;
      }
    }
    return new ArrayList<>(found);
  }

  /**
   * The reminder loop for one community.
   *
   * Returns how many went out. `now` is a parameter so a test does not have to wait three days.
   */
  public int remind(DomainConfig config, Accounts accounts, Timestamp now, int limit)
      throws SQLException {
    if (!config.invites.enabled || !config.invites.remindersEnabled) {
      return 0;
    }
    int sent = 0;
    for (Invites.Invite invite : accounts.invites.due(now, limit)) {
      if (invite.touches() >= 3) {
        // three is the whole sequence. A fourth is nagging, and the third already said it was the
        // last one -- going back on that is how a community earns a spam complaint.
        accounts.invites.stopReminders(invite.id());
        continue;
      }
      sendTouch(config, accounts, invite);
      sent++;
    }
    return sent;
  }
}
