package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.smtp.Envelope;
import io.hearth.smtp.MimeParts;

import java.sql.SQLException;
import java.util.List;

/**
 * An answer that arrived by email, turned into an RSVP.
 *
 * Somebody presses Accept in Outlook. Outlook sends a message to the organiser address with a
 * `text/calendar; method=REPLY` part naming the UID and their `PARTSTAT`. This is the code that
 * makes that mean something here -- and it is the half of "first class calendar support" that most
 * software skips, which is why most software's invitations are a one-way announcement with buttons
 * that go nowhere.
 *
 * <b>Everything it will not take an answer from.</b> A reply changes what a community believes about
 * who is coming, so the bar is deliberately higher than "it parsed":
 *
 * <ul>
 *   <li><b>The envelope sender must be a member.</b> No account, no answer. An address nobody here
 *       knows cannot RSVP on behalf of anybody.</li>
 *   <li><b>The `ATTENDEE` in the file must be the same person as the envelope.</b> This is the one
 *       that matters: without it, anybody who can send email could accept on somebody else's
 *       behalf, which is a forged guest list.</li>
 *   <li><b>The UID must name an event here</b>, and one that is still on.</li>
 *   <li><b>The sequence must not be older than the event's.</b> A stale reply is somebody's client
 *       answering an invitation that has since been rescheduled, and taking it would put them down
 *       for a day that no longer exists.</li>
 *   <li><b>Sender checks must not have failed.</b> SPF, DKIM and DMARC already ran on the way in,
 *       and their verdict rides on the message. A reply is a claim about identity, so a message
 *       that failed authentication is exactly the message not to believe.</li>
 * </ul>
 *
 * Everything it refuses, it refuses quietly and accepts the message: bouncing a calendar reply
 * teaches somebody's mail client that this address is broken, and there is nothing they could do
 * about it anyway.
 */
public final class IcsReplies {
  private IcsReplies() {
  }

  /** what happened to one reply, in the words a terminal or a log wants */
  public record Result(boolean applied, String detail) {
    static Result no(String why) {
      return new Result(false, why);
    }
  }

  /**
   * Find the calendar part, check everything, and apply it.
   *
   * @param authenticated what SPF/DKIM/DMARC decided, as stamped on the message. A community that
   *     has switched the checks off passes true, because "we are not checking" is a decision an
   *     operator made rather than a reason to distrust this particular message.
   */
  public static Result apply(Accounts accounts, Envelope envelope, boolean authenticated,
                             Verbose verbose) throws SQLException {
    if (!authenticated) {
      return Result.no("the message did not pass sender authentication");
    }
    Ics.Incoming incoming = calendarIn(envelope);
    if (incoming == null) {
      return Result.no("no calendar part");
    }
    if (incoming.method() != Ics.Method.REPLY && incoming.method() != Ics.Method.COUNTER) {
      return Result.no("a " + incoming.method() + " is not something to act on here");
    }

    // who the *message* is from, which is the only identity the mail system vouched for
    String sender = MimeParts.addressIn(envelope.headers().get("from"));
    if (sender == null) {
      sender = envelope.from() == null ? null : envelope.from().trim().toLowerCase();
    }
    if (sender == null || sender.isBlank()) {
      return Result.no("no usable sender");
    }
    UserRecord member = accounts.users.byEmail(sender);
    // ...and who the *file* says is answering. These being the same person is the whole security
    // property: without it, anybody who can send an email can accept on anybody's behalf. It is
    // checked before the sender is known to be a member, because it is the same claim either way.
    String claimed = incoming.attendeeEmail();
    if (claimed == null || !claimed.equalsIgnoreCase(sender)) {
      return Result.no("the reply is for " + claimed + " but came from " + sender);
    }

    Calendar.Event event = accounts.calendar.byUid(incoming.uid());
    if (event == null) {
      return Result.no("no event with that uid");
    }
    if (event.cancelled()) {
      return Result.no("that event is cancelled");
    }
    if (incoming.sequence() < event.sequence()) {
      return Result.no("an answer to an older version of that event");
    }

    if (member == null || member.disabled() || !accounts.access.isApproved(member)) {
      return fromOutside(accounts, event, incoming, sender, verbose);
    }

    if (incoming.method() == Ics.Method.COUNTER) {
      // a suggestion, never a change. The organiser can take it, and taking it is a reschedule
      // like any other -- which is what stops one attendee moving everybody else's evening.
      accounts.calendar.answer(event.id(), member.id(), member.email(), Calendar.Answer.maybe, 1,
          "", "email");
      accounts.calendar.propose(event.id(), member.id(), incoming.proposedStart(), "");
      verbose.detail(() -> "calendar: " + member.email() + " suggested another day for "
          + event.title());
      return new Result(true, member.email() + " suggested "
          + (incoming.proposedStart() == null ? "another time" : incoming.proposedStart()));
    }

    Calendar.Answer answer = incoming.part().answer();
    if (answer == null) {
      return Result.no("the reply does not say whether they are coming");
    }
    Calendar.Rsvp existing = accounts.calendar.rsvpFor(event.id(), member.id());
    // a calendar client has no idea about guests, so a reply keeps whatever party size they set
    // here rather than silently resetting a family of four to one
    int party = existing == null ? 1 : existing.party();
    accounts.calendar.answer(event.id(), member.id(), member.email(), answer, party,
        existing == null ? "" : existing.note(), "email");
    verbose.say("calendar: " + member.email() + " said " + answer + " to " + event.title()
        + " from their calendar");
    return new Result(true, member.email() + " -> " + answer);
  }

  /**
   * An answer from somebody with no account here.
   *
   * <b>Only for an event whose organisers said anybody may come.</b> Everywhere else this is
   * ignored, because an answer from an unknown address to a members' event is either a forwarded
   * invitation or a mistake, and writing either down would put strangers on a guest list nobody
   * agreed to.
   *
   * Where it is allowed, it goes somewhere of its own rather than onto the guest list: it counts
   * nobody into the room, and what it is really for is the list an administrator reads afterwards.
   * Somebody who found out about a thing, said they were coming, and has never been asked to join
   * is the strongest lead a small community gets.
   */
  private static Result fromOutside(Accounts accounts, Calendar.Event event, Ics.Incoming incoming,
                                    String sender, Verbose verbose) throws SQLException {
    if (!event.openToPublic()) {
      return Result.no(sender + " is not a member here");
    }
    if (incoming.method() != Ics.Method.REPLY) {
      // a stranger proposing another day for somebody else's evening is not a conversation this
      // community asked to have
      return Result.no("a " + incoming.method() + " from outside is not something to act on");
    }
    Calendar.Answer answer = incoming.part().answer();
    if (answer == null) {
      return Result.no("the reply does not say whether they are coming");
    }
    Calendar.Outsider existing = accounts.calendar.outsiderFor(event.id(), sender);
    accounts.calendar.answerPublicly(event.id(), sender, incoming.attendeeName(), answer,
        existing == null ? 1 : existing.party(), existing == null ? "" : existing.note(), "email");
    verbose.say("calendar: " + sender + " said " + answer + " to " + event.title()
        + " from outside the community");
    return new Result(true, sender + " (not a member) -> " + answer);
  }

  /**
   * The calendar part of a message, wherever it is hiding.
   *
   * Some clients send it as an alternative, some as an attachment, some as both, and at least one
   * sends `application/octet-stream` with a `.ics` name. The first part that parses into something
   * with a METHOD and a UID wins.
   */
  static Ics.Incoming calendarIn(Envelope envelope) {
    List<MimeParts.Part> parts = MimeParts.of(envelope.data());
    for (MimeParts.Part part : parts) {
      if (!part.isCalendar()) {
        continue;
      }
      Ics.Incoming incoming = Ics.read(part.text());
      if (incoming != null) {
        return incoming;
      }
    }
    // a message with no multipart at all: some clients send the calendar file as the whole body
    for (MimeParts.Part part : parts) {
      if (part.text().contains("BEGIN:VCALENDAR")) {
        Ics.Incoming incoming = Ics.read(part.text());
        if (incoming != null) {
          return incoming;
        }
      }
    }
    return null;
  }
}
