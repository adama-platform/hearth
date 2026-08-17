package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.auth.Permission;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.places.Geocoder;
import io.hearth.places.Places;
import io.hearth.smtp.Envelope;
import io.hearth.smtp.MimeParts;
import io.hearth.vhost.DomainConfig;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Somebody emails an invitation to the calendar address, and it becomes an event here.
 *
 * <b>This is the shortest path there is from "we are doing this" to a community knowing about it.</b>
 * Whoever organises things already made the entry in their own calendar; adding the community's
 * address as a guest is one keystroke, and it costs nobody a visit to a website or a form. For the
 * person who does most of the organising -- who is the person most likely to stop -- that is the
 * difference between the calendar being current and being three weeks out of date.
 *
 * <b>Who may do it is the whole question.</b> An event on a community's calendar is a claim on
 * everybody's evening, so this needs the same permission the admin screen needs: `calendar_write`
 * for an event that lands accepted, and an ordinary approved member with the community's
 * suggestions open gets exactly what they would get from the site -- a suggestion in the queue.
 * Anybody else is ignored. And as with a reply, the message has to have passed sender
 * authentication, because "from" is a claim rather than a fact.
 *
 * <b>The location is matched before it is created.</b> An address book that gains a second "The
 * Oak" every time somebody mails in an event is an address book nobody trusts, so the name and the
 * address are compared against what is already written down, then -- if geocoding is on -- the
 * coordinates are compared against everywhere within a few hundred metres. Only when neither finds
 * anything does a new place appear, and it appears unpublished, because a place created by a
 * machine from one line of an email is a draft rather than a decision.
 */
public final class IcsRequests {
  /**
   * How close two coordinates have to be to be the same place.
   *
   * Three hundred metres. A geocoder asked for "The Oak, Ashford" and "The Oak Inn, High Street"
   * will land within a street of itself, and a village hall and the pub across the road are further
   * apart than this. It errs toward creating a duplicate, which somebody can merge, rather than
   * toward putting an event at the wrong address, which nobody notices until they arrive there.
   */
  public static final double SAME_PLACE_KM = 0.3;

  private IcsRequests() {
  }

  /** what happened to one mailed-in invitation */
  public record Result(boolean created, String detail) {
    static Result no(String why) {
      return new Result(false, why);
    }
  }

  public static Result apply(DomainConfig config, Accounts accounts, Geocoder geocoder,
                             Envelope envelope, boolean authenticated, Verbose verbose)
      throws SQLException {
    if (!authenticated) {
      return Result.no("the message did not pass sender authentication");
    }
    Ics.Incoming incoming = IcsReplies.calendarIn(envelope);
    if (incoming == null || incoming.method() != Ics.Method.REQUEST) {
      return Result.no("not an invitation");
    }
    Ics.Event details = Ics.eventIn(incoming.raw());
    if (details == null || details.title().isBlank() || details.startsOn() == null) {
      return Result.no("an invitation needs at least a name and a day");
    }

    String sender = MimeParts.addressIn(envelope.headers().get("from"));
    if (sender == null) {
      sender = envelope.from() == null ? null : envelope.from().trim().toLowerCase();
    }
    UserRecord member = sender == null ? null : accounts.users.byEmail(sender);
    if (member == null || member.disabled() || !accounts.access.isApproved(member)) {
      return Result.no(sender + " is not somebody who can put an event on this calendar");
    }
    boolean mayPublish = accounts.access.can(member, Permission.calendar_write);
    if (!mayPublish && !config.calendar.suggestions) {
      return Result.no(sender + " is a member, and this community does not take suggestions");
    }

    // already here? A mail client that sends the same invitation twice, or a reschedule of one it
    // sent before, carries the same UID -- and two events with one uid is two rows nobody can tell
    // apart and a reply that could land on either.
    Calendar.Event existing = accounts.calendar.byUid(incoming.uid());
    if (existing != null) {
      accounts.calendar.update(existing.id(), details.title(), details.description(),
          details.location(), existing.placeId(), details.startsOn(), details.endsOn(),
          existing.startTime(), existing.capacity(), existing.published(), member.id());
      accounts.calendar.bumpSequence(existing.id(), member.id());
      verbose.say("calendar: " + sender + " updated " + details.title() + " by email");
      return new Result(true, "updated " + details.title());
    }

    Long placeId = placeFor(accounts, geocoder, details, verbose);
    Calendar.Event made = accounts.calendar.create(details.title(), details.description(),
        details.location(), placeId,
        mayPublish ? Calendar.State.accepted : Calendar.State.suggested,
        details.startsOn(), details.endsOn(), "", null, mayPublish, member.id(), member.email());
    // the uid it arrived with, so a later update or a reply from the same client finds it again
    accounts.calendar.stampUid(made.id(), incoming.uid());
    verbose.say("calendar: " + sender + " added " + details.title() + " by email"
        + (mayPublish ? "" : ", as a suggestion"));
    return new Result(true, (mayPublish ? "added " : "suggested ") + details.title());
  }

  /**
   * The place this event is at: one we already have, or a new one.
   *
   * Three passes, cheapest first. The name and the address are compared against what is written
   * down; then, if there is a geocoder and it can find the address, everywhere within a few hundred
   * metres; and only then is anything created.
   */
  static Long placeFor(Accounts accounts, Geocoder geocoder, Ics.Event details, Verbose verbose)
      throws SQLException {
    String location = details.location();
    if (location == null || location.isBlank()) {
      return null;
    }
    Places.Place match = accounts.places.matching(location, location);
    if (match != null) {
      verbose.detail(() -> "calendar: '" + location + "' is " + match.name());
      return match.id();
    }
    Geocoder.Point point = geocoder.findQuietly(location);
    if (point != null) {
      List<Places.Nearby> nearby =
          accounts.places.near(point.latitude(), point.longitude(), SAME_PLACE_KM);
      if (!nearby.isEmpty()) {
        Places.Nearby closest = nearby.get(0);
        verbose.detail(() -> "calendar: '" + location + "' is " + closest.place().name()
            + ", " + Math.round(closest.km() * 1000) + "m away");
        return closest.place().id();
      }
    }

    // nothing matched, so write it down -- unpublished, because a place a machine made out of one
    // line of an email is a draft somebody should look at rather than a decision
    // the untethered kind, which is seeded at boot and exists so that something can always be
    // written down without anybody having invented a heading for it first
    String slug = Places.slugify(location);
    if (slug.isEmpty()) {
      return null;
    }
    if (accounts.places.bySlug(Places.DEFAULT_TYPE, slug) != null) {
      slug = slug + "-" + System.currentTimeMillis() % 10000;
    }
    Places.Place made = accounts.places.save(new Places.Place(0, Places.DEFAULT_TYPE, slug,
        location, point == null ? location : point.label(), "", "", "", "",
        point == null ? null : point.latitude(), point == null ? null : point.longitude(),
        "", "", "", "{}", "", false, false, null, null, null), null);
    verbose.say("calendar: wrote down a new place, '" + location + "'"
        + (point == null ? " with no coordinates" : ""));
    return made.id();
  }
}
