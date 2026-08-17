package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.auth.UserRecord;
import io.hearth.mail.Mailer;
import io.hearth.people.Names;
import io.hearth.vhost.DomainConfig;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sending an event to everybody's calendar, and chasing the people who have not answered.
 *
 * <b>One path, like the other invitations.</b> Publishing an event, changing one, calling one off
 * and nudging somebody a week out are four moments that all end in the same message with a different
 * word in it -- and writing them separately is how a community ends up sending a reminder about an
 * event that moved.
 *
 * <b>It refuses to send when a reply would go nowhere.</b> Every invitation says "answer from your
 * calendar" and a calendar answers by email, to this server. With inbound mail off, pressing accept
 * sends a message into the void: the person believes they answered, the guest list never hears, and
 * the reminder loop chases somebody who did reply. That is worse than sending nothing, so
 * `calendar.invites` being on is not the same as it being possible, and the admin screen says which
 * half is missing.
 *
 * <b>Nudges are for silence, never for an answer.</b> Somebody who said no is not chased, somebody
 * who said yes is not thanked twice, and the whole loop is two messages -- a week out, when a plan
 * can still change, and the day before, when people notice they never replied. A third would be
 * nagging, and nagging is how a community teaches its members to filter its mail.
 */
public class Invitations {
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM");

  private final Mailer mailer;

  public Invitations(Mailer mailer) {
    this.mailer = mailer;
  }

  /** what happened, so a screen can say it rather than claim it */
  public record Sent(int invited, int skipped, String detail) {
    public boolean anything() {
      return invited > 0;
    }
  }

  /**
   * Where a calendar reply comes back to.
   *
   * The events address on the community's own domain, because a reply is a message this server has
   * to *receive* -- which is a different address from the one it sends as, and a different question
   * from whether sending works at all.
   */
  public static String replyTo(DomainConfig config) {
    return config.calendar.eventsAddressOr(config.ses.from, config.domain);
  }

  /** and what a mail client shows in the From line: a community, not an address */
  public static String replyName(DomainConfig config) {
    return config.calendar.eventsNameOr(config.name);
  }

  /** can invitations actually work here, and if not, what is missing */
  public static String whyNot(DomainConfig config, boolean inboundMail) {
    if (!config.calendar.enabled) {
      return "the calendar is switched off for this community";
    }
    if (!config.calendar.invites) {
      return "calendar invitations are switched off in this community's config";
    }
    if (!inboundMail) {
      return "this server is not receiving mail, so nobody's answer could get back --"
          + " turn on the smtp block in config.cfg";
    }
    return null;
  }

  /**
   * Invite everybody who is a member of this community.
   *
   * Approved members only, because an invitation is a statement that somebody is welcome and
   * approval is where that decision is made. Their existing answer rides along in the file as
   * `PARTSTAT`, so a reschedule does not silently reset a room full of yeses back to no answer.
   */
  public Sent invite(DomainConfig config, Accounts accounts, Calendar.Event event, boolean inbound)
      throws SQLException {
    String why = whyNot(config, inbound);
    if (why != null) {
      return new Sent(0, 0, why);
    }
    if (!event.live()) {
      return new Sent(0, 0, "only a published, accepted event is worth anybody's calendar");
    }
    Calendar.Event current = withUid(config, accounts, event);
    List<UserRecord> members = accounts.users.recent(2000);
    Names names = Names.of(accounts);
    List<Ics.Attendee> attendees = attendees(accounts, members, names, current.id());
    if (attendees.isEmpty()) {
      return new Sent(0, 0, "there is nobody approved to invite yet");
    }

    String ics = Ics.request(current, current.uid(), current.sequence(),
        replyTo(config), replyName(config), attendees, config.name, url(config, current),
        description(config, current));
    int sent = 0;
    int failed = 0;
    for (UserRecord member : members) {
      if (!accounts.access.isApproved(member) || member.disabled()) {
        continue;
      }
      Mailer.Outcome outcome = mailer.sendEventInvite(
          Mailer.Envelope.to(config, accounts, member.email(), null),
          new Mailer.EventInvite(current.title(), when(current), current.location(),
              description(config, current), url(config, current), ics, "REQUEST",
              replyTo(config),
              current.sequence() > 0 ? Mailer.Note.changed : Mailer.Note.invitation));
      if (outcome.delivered()) {
        sent++;
      } else {
        failed++;
      }
    }
    accounts.calendar.markInvited(current.id());
    return new Sent(sent, failed, sent + " invitation(s) sent"
        + (failed > 0 ? ", " + failed + " could not be delivered" : ""));
  }

  /** the same event, called off, to everybody who was told about it in the first place */
  public Sent cancel(DomainConfig config, Accounts accounts, Calendar.Event event, boolean inbound)
      throws SQLException {
    String why = whyNot(config, inbound);
    if (why != null) {
      return new Sent(0, 0, why);
    }
    if (event.uid() == null || event.uid().isBlank()) {
      // nobody's calendar has ever heard of it, so there is nothing to withdraw
      return new Sent(0, 0, "no invitations had gone out");
    }
    Calendar.Event bumped = accounts.calendar.bumpSequence(event.id(), null);
    Names names = Names.of(accounts);
    List<UserRecord> members = accounts.users.recent(2000);
    List<Ics.Attendee> attendees = attendees(accounts, members, names, bumped.id());
    String ics = Ics.cancel(bumped, bumped.uid(), bumped.sequence(),
        replyTo(config), replyName(config), attendees, config.name, url(config, bumped),
        description(config, bumped));
    int sent = 0;
    for (UserRecord member : members) {
      if (!accounts.access.isApproved(member) || member.disabled()) {
        continue;
      }
      Mailer.Outcome outcome = mailer.sendEventInvite(
          Mailer.Envelope.to(config, accounts, member.email(), null),
          new Mailer.EventInvite(bumped.title(), when(bumped), bumped.location(),
              description(config, bumped), url(config, bumped), ics, "CANCEL",
              replyTo(config), Mailer.Note.cancelled));
      if (outcome.delivered()) {
        sent++;
      }
    }
    accounts.calendar.markInvited(bumped.id());
    return new Sent(sent, 0, sent + " cancellation(s) sent");
  }

  /**
   * One nudge to everybody who has not said anything, for events that are the right number of days
   * away.
   *
   * Driven from the event's own date rather than from a stamp on a row, so a restart cannot send
   * Tuesday's reminder twice and a server that was off for a day does not send yesterday's. The
   * answer to "has this person been reminded" is "is today one of the days, and have they still not
   * answered" -- which is true exactly once per day per event, and the pass runs once a day.
   */
  public Sent remind(DomainConfig config, Accounts accounts, LocalDate today, boolean inbound)
      throws SQLException {
    if (!config.calendar.enabled || config.calendar.remindDaysBefore.isEmpty()) {
      return new Sent(0, 0, "reminders are switched off");
    }
    int sent = 0;
    for (Calendar.Event event : accounts.calendar.upcoming(today, 200)) {
      if (event.cancelled() || !event.live()) {
        continue;
      }
      long days = Ics.daysBetween(today, event.startsOn());
      if (!config.calendar.remindDaysBefore.contains((int) days)) {
        continue;
      }
      for (UserRecord member : accounts.users.recent(2000)) {
        if (!accounts.access.isApproved(member) || member.disabled()) {
          continue;
        }
        if (accounts.calendar.rsvpFor(event.id(), member.id()) != null) {
          // an answer is an answer, whichever way it went. Chasing somebody who said no is how a
          // community teaches people that saying no does not work.
          continue;
        }
        Mailer.Outcome outcome = mailer.sendEventInvite(
            Mailer.Envelope.to(config, accounts, member.email(), null),
            new Mailer.EventInvite(event.title(), when(event), event.location(),
                nudge(event, days) + "\n\n" + description(config, event), url(config, event),
                inbound ? reminderIcs(config, accounts, event) : "", "REQUEST",
                replyTo(config), Mailer.Note.reminder));
        if (outcome.delivered()) {
          sent++;
        }
      }
    }
    return new Sent(sent, 0, sent + " reminder(s) sent");
  }

  private String reminderIcs(DomainConfig config, Accounts accounts, Calendar.Event event)
      throws SQLException {
    Calendar.Event current = withUid(config, accounts, event);
    Names names = Names.of(accounts);
    return Ics.request(current, current.uid(), current.sequence(),
        replyTo(config), replyName(config),
        attendees(accounts, accounts.users.recent(2000), names, current.id()), config.name,
        url(config, current), description(config, current));
  }

  /** the sentence at the top of a nudge, which is the only thing that changes about it */
  private static String nudge(Calendar.Event event, long days) {
    if (days <= 1) {
      return "This is tomorrow and we have not heard from you. A no is as useful as a yes --"
          + " it is how anybody knows how many chairs to put out.";
    }
    return "This is in " + days + " days and we have not heard from you yet.";
  }

  private Calendar.Event withUid(DomainConfig config, Accounts accounts, Calendar.Event event)
      throws SQLException {
    if (event.uid() != null && !event.uid().isBlank()) {
      return event;
    }
    return accounts.calendar.stampUid(event.id(),
        Ics.uidFor(event.id(), event.createdAt().getTime(), config.domain));
  }

  private static List<Ics.Attendee> attendees(Accounts accounts, List<UserRecord> members,
                                              Names names, long eventId) throws SQLException {
    ArrayList<Ics.Attendee> out = new ArrayList<>();
    for (UserRecord member : members) {
      if (!accounts.access.isApproved(member) || member.disabled()) {
        continue;
      }
      Calendar.Rsvp rsvp = accounts.calendar.rsvpFor(eventId, member.id());
      out.add(new Ics.Attendee(member.email(), names.of(member.id()),
          Ics.Part.forAnswer(rsvp == null ? null : rsvp.answer())));
    }
    return out;
  }

  /** the day, in the words a person would use */
  public static String when(Calendar.Event event) {
    String day = DAY.format(event.startsOn());
    if (event.spansDays()) {
      day = day + " to " + DAY.format(event.endsOn());
    }
    return event.startTime() == null || event.startTime().isBlank()
        ? day : day + ", " + event.startTime();
  }

  /**
   * What the calendar entry says underneath the title.
   *
   * The event's own words, and nothing else. What the community wants to say on every invitation --
   * "we eat at seven, bring a chair" -- belongs in the message rather than in the calendar entry,
   * and lives with every other piece of wording at /admin/messages. It was briefly a key in the
   * config file, which meant one paragraph of this product's prose living somewhere no admin could
   * reach and nothing else did.
   */
  public static String description(DomainConfig config, Calendar.Event event) {
    StringBuilder out = new StringBuilder();
    if (event.startTime() != null && !event.startTime().isBlank()) {
      out.append(event.startTime()).append('\n').append('\n');
    }
    if (event.body() != null && !event.body().isBlank()) {
      out.append(event.body()).append('\n').append('\n');
    }
    return out.toString().trim();
  }

  public static String url(DomainConfig config, Calendar.Event event) {
    return "https://" + config.domain + config.urls.calendar + "/" + event.id();
  }
}
