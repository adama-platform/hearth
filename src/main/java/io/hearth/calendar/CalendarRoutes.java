package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.content.Markdown;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Cookies;
import io.hearth.web.Forms;
import io.hearth.web.Icons;
import io.hearth.web.Landing;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calendar a member sees.
 *
 * Two paths and a POST, following the same rule as the board: the list is a page, one event is a
 * page, and answering is a POST that redirects.
 *
 * <pre>
 *   /events           what is coming up, and what already happened
 *   /events/&lt;id&gt;      one event, its guest list, and the box where you answer
 *   POST /events      going / maybe / no / take it back
 * </pre>
 *
 * Only admins create events, so there is nothing here that writes one. What this class owns is the
 * answering, and the one rule that matters in it: the page shows what the server decided, never
 * what the person clicked. Somebody who clicks "going" for a full event is on the waitlist, and the
 * page has to say so plainly rather than showing a tick.
 */
public class CalendarRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(CalendarRoutes.class);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM");
  private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM");
  private static final int LIST_SIZE = 60;

  private final Templates templates;
  private final Verbose verbose;

  public CalendarRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  /** the calendar owns its path and everything under it, so an event can have its own url */
  public static boolean owns(DomainConfig config, String path) {
    String root = config.urls.calendar;
    return path.equals(root) || path.startsWith(root + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      // The file itself, before the sign-in check: an event a community said anybody may come to is
      // one somebody can put in their calendar without joining first, which is most of what "open
      // to the public" can mean when the answer comes back by email.
      String asked = Forms.path(req.uri());
      if (!HttpMethod.POST.equals(req.method()) && asked.endsWith(".ics")
          && asked.startsWith(config.urls.calendar + "/")) {
        ics(config, accounts, ctx, req, me, asked, recorder);
        return;
      }
      if (me == null) {
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                Landing.carry(config.urls.login, Landing.here(req))});
        return;
      }
      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, recorder);
        return;
      }
      String path = Forms.path(req.uri());
      String root = config.urls.calendar;
      if (path.equals(root)) {
        list(config, accounts, ctx, req, me, recorder);
        return;
      }
      one(config, accounts, ctx, req, me, idOf(path.substring(root.length() + 1)), recorder);
    } catch (SQLException ex) {
      LOG.error("calendar-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = base(config, accounts, req, "Something went wrong", null);
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  /**
   * One event as a calendar file.
   *
   * The point of it is the calendar every member already keeps. An invitation reaches everybody who
   * was a member on the day it went out; this reaches whoever is looking at the page now -- somebody
   * who joined last week, somebody whose client threw the invitation away, somebody reading it on a
   * phone that is not their mail client.
   *
   * A member may take any event they can already read. Anybody else may take one the community said
   * is open, and nothing else -- which is the same rule the page itself follows, checked here rather
   * than inherited, because a file is exactly as public as whoever can fetch it.
   */
  private void ics(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, String path, WebHandler.Recorder recorder)
      throws SQLException {
    String tail = path.substring(config.urls.calendar.length() + 1);
    long id = idOf(tail.substring(0, tail.length() - 4));
    Calendar.Event event = id <= 0 ? null : accounts.calendar.byId(id);
    boolean member = me != null && !me.disabled() && accounts.access.isApproved(me);
    if (event == null || !event.published() || event.suggested() || event.declined()
        || (!member && !event.openToPublic())) {
      // a 404 rather than a redirect: a calendar program following a link has nowhere to sign in,
      // and telling it the path exists but is guarded teaches it nothing it can use
      recorder.status(404);
      Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
          "no such event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return;
    }
    String uid = event.uid() == null || event.uid().isBlank()
        ? Ics.uidFor(event.id(), event.createdAt() == null ? 0L : event.createdAt().getTime(),
            config.domain)
        : event.uid();
    String file = Ics.publish(event, uid, event.sequence(), Invitations.replyTo(config),
        Invitations.replyName(config), config.name, Invitations.url(config, event),
        Invitations.description(config, event));
    verbose.detail(() -> "calendar: " + event.title() + " downloaded as a file");
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/calendar; charset=utf-8",
        file.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        new String[]{"content-disposition",
            "attachment; filename=\"event-" + event.id() + ".ics\""});
  }

  // ---- answering -------------------------------------------------------------------------------

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.calendar;
    if (form.bodyTooLarge()
        || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD), Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, where);
      return;
    }
    String what = String.valueOf(form.get("action"));
    if (what.equals("suggest")) {
      suggest(config, accounts, ctx, req, me, form, recorder);
      return;
    }
    if (what.endsWith("comment")) {
      comment(config, accounts, ctx, req, me, form, recorder);
      return;
    }
    long eventId = idOf(form.get("event"));
    Calendar.Event event = accounts.calendar.byId(eventId);
    if (event == null || !event.published()) {
      redirect(ctx, req, recorder, where);
      return;
    }
    where = config.urls.calendar + "/" + eventId;

    String action = String.valueOf(form.get("action"));
    if (action.equals("withdraw")) {
      accounts.calendar.withdraw(eventId, me.id());
      verbose.detail(() -> "calendar: " + me.email() + " withdrew from " + eventId);
      redirect(ctx, req, recorder, where);
      return;
    }
    // Attendance, marked by somebody who was there.
    //
    // On the event page rather than in the admin section because that is where the guest list is,
    // and the person who knows is the person reading it the morning after.
    // taking somebody's suggested day, from the page where the suggestion is
    if (action.equals("take_proposal")) {
      if (!accounts.access.can(me, io.hearth.auth.Permission.calendar_write)) {
        redirect(ctx, req, recorder, where);
        return;
      }
      long who = idOf(form.get("user"));
      Calendar.Rsvp rsvp = who <= 0 ? null : accounts.calendar.rsvpFor(eventId, who);
      if (rsvp != null && rsvp.proposedOn() != null) {
        long span = event.startsOn().until(event.endsOn()).getDays();
        accounts.calendar.reschedule(eventId, rsvp.proposedOn(),
            rsvp.proposedOn().plusDays(Math.max(0, span)), form.get("keep_answers") != null,
            me.id());
        accounts.calendar.propose(eventId, who, null, "");
        accounts.calendar.bumpSequence(eventId, me.id());
        verbose.say("calendar: " + me.email() + " moved " + event.title() + " to "
            + rsvp.proposedOn());
      }
      redirect(ctx, req, recorder, where);
      return;
    }
    if (action.equals("no_show") || action.equals("attended")) {
      if (!accounts.access.can(me, io.hearth.auth.Permission.calendar_write)) {
        redirect(ctx, req, recorder, where);
        return;
      }
      long who = idOf(form.get("user"));
      if (who > 0) {
        accounts.calendar.markNoShow(eventId, who, action.equals("no_show"), me.id());
      }
      redirect(ctx, req, recorder, where);
      return;
    }
    if (!action.equals("rsvp")) {
      redirect(ctx, req, recorder, where);
      return;
    }
    // an event that has been called off, or is already over, takes no more answers -- the page
    // hides the box, and this is the same rule enforced where it cannot be skipped
    if (event.cancelled() || event.over(LocalDate.now(config.zone))) {
      redirect(ctx, req, recorder, where);
      return;
    }
    Calendar.Answer wanted = Calendar.Answer.of(form.get("answer"));
    int party = (int) Math.max(1, idOf(form.get("party")));
    String note = form.text("note");
    if (form.tooLong() != null) {
      redirect(ctx, req, recorder, where);
      return;
    }
    Calendar.Rsvp settled =
        accounts.calendar.answer(eventId, me.id(), me.email(), wanted, party, note);
    verbose.detail(() -> "calendar: " + me.email() + " said " + wanted + " to " + eventId
        + (settled != null && settled.answer() != wanted ? " (seated as " + settled.answer() + ")" : ""));
    redirect(ctx, req, recorder, where);
  }

  /**
   * Somebody putting an event forward.
   *
   * It goes into the queue rather than onto the calendar, which is what makes opening this up safe:
   * a suggestion costs a reviewer a screen to look at rather than control of the front page. The
   * form asks for the least that makes a suggestion answerable -- what, when, roughly where -- and
   * whoever accepts it can fill in the rest.
   */
  private void suggest(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, UserRecord me, Forms form,
                       WebHandler.Recorder recorder) throws SQLException {
    String where = config.urls.calendar;
    if (!config.calendar.suggestions) {
      redirect(ctx, req, recorder, where);
      return;
    }
    String title = form.get("title");
    LocalDate starts = dateOf(form.get("starts_on"));
    String body = form.text("body");
    if (title == null || title.isBlank() || starts == null || form.tooLong() != null) {
      redirect(ctx, req, recorder, where + "?suggested=no");
      return;
    }
    LocalDate ends = dateOf(form.get("ends_on"));
    Long placeId = form.get("place") == null || form.get("place").isBlank()
        ? null : idOf(form.get("place"));
    accounts.calendar.create(title, orEmpty(body), orEmpty(form.get("location")), placeId,
        Calendar.State.suggested, starts, ends == null ? starts : ends,
        orEmpty(form.get("start_time")), null, false, me.id(), me.email());
    verbose.detail(() -> "calendar: " + me.email() + " suggested " + title);
    redirect(ctx, req, recorder, where + "?suggested=yes");
  }

  /**
   * Something said under an event.
   *
   * The same machinery the board uses, because it is the same thing: what people said, in reading
   * order, with the author able to edit their own and a moderator able to take one down. What
   * differs is only which permission moderates here -- keeping the calendar tidy is a job somebody
   * was given for the calendar.
   */
  private void comment(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, UserRecord me, Forms form,
                       WebHandler.Recorder recorder) throws SQLException {
    long eventId = idOf(form.get("event"));
    Calendar.Event event = accounts.calendar.byId(eventId);
    String where = config.urls.calendar;
    if (event == null || !event.live()) {
      redirect(ctx, req, recorder, where);
      return;
    }
    where = config.urls.calendar + "/" + eventId + "#comments";
    io.hearth.board.CommentBox.act(accounts, io.hearth.board.Subject.event(eventId), me, form,
        accounts.access.can(me, io.hearth.auth.Permission.calendar_moderate));
    redirect(ctx, req, recorder, where);
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static LocalDate dateOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  // ---- showing ---------------------------------------------------------------------------------

  private void list(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    String csrf = Cookies.stableToken(req);
    LocalDate today = LocalDate.now(config.zone);
    Map<String, Object> model = base(config, accounts, req, config.name + " calendar", csrf);

    ArrayList<Map<String, Object>> upcoming = new ArrayList<>();
    for (Calendar.Event event : accounts.calendar.upcoming(today, LIST_SIZE)) {
      upcoming.add(row(config, accounts, event, me, today));
    }
    ArrayList<Map<String, Object>> past = new ArrayList<>();
    for (Calendar.Event event : accounts.calendar.past(today, 12)) {
      past.add(row(config, accounts, event, me, today));
    }
    model.put("upcoming", upcoming);
    model.put("anyUpcoming", !upcoming.isEmpty());
    model.put("past", past);
    model.put("anyPast", !past.isEmpty());
    model.put("mine", accounts.calendar.forUser(me.id(), today, 10).size());
    boolean mayCreate = accounts.access.can(me, io.hearth.auth.Permission.calendar_write);
    model.put("mayCreate", mayCreate);
    model.put("newUrl", config.urls.admin + "/calendar/new");
    // anybody approved may suggest when the community said so; the queue is what makes that safe
    model.put("maySuggest", config.calendar.suggestions && !mayCreate);
    model.put("suggestedYes", "yes".equals(Forms.query(req.uri(), "suggested")));
    model.put("suggestedNo", "no".equals(Forms.query(req.uri(), "suggested")));
    ArrayList<Map<String, Object>> placeRows = new ArrayList<>();
    if (config.has(io.hearth.vhost.Surface.places)) {
      for (io.hearth.places.Places.Place place : accounts.places.all(200)) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", place.id());
        row.put("name", place.name());
        placeRows.add(row);
      }
    }
    model.put("places", placeRows);
    model.put("anyPlaces", !placeRows.isEmpty());
    ArrayList<Map<String, Object>> suggested = new ArrayList<>();
    for (Calendar.Event event : accounts.calendar.suggestions(50)) {
      if (event.createdBy() != null && event.createdBy() == me.id()) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("title", event.title());
        row.put("when", DAY.format(event.startsOn()));
        suggested.add(row);
      }
    }
    model.put("waiting", suggested);
    model.put("anyWaiting", !suggested.isEmpty());

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("calendar", model));
  }

  private void one(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, long eventId,
                   WebHandler.Recorder recorder) throws SQLException {
    String csrf = Cookies.stableToken(req);
    Calendar.Event event = accounts.calendar.byId(eventId);
    boolean admin = accounts.access.isAdmin(me);
    if (event == null || (!event.published() && !admin)) {
      Map<String, Object> model = base(config, accounts, req, "Not here", csrf);
      model.put("heading", "That event is not here");
      model.put("message", "It may have been removed, or it may not be announced yet.");
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, templates.render("message", model));
      return;
    }

    LocalDate today = LocalDate.now(config.zone);
    Map<String, Object> model = base(config, accounts, req, event.title(), csrf);
    model.putAll(row(config, accounts, event, me, today));
    // The member's renderer, not the operator's.
    //
    // Invariant 91 picks by who held the pen, and an event body is not reliably an operator's:
    // `calendar.suggestions` is on by default and lets any approved member put one forward, body
    // and all, and accepting a suggestion changes a word on the row rather than rewriting the text.
    // The same body also arrives from IcsRequests, out of an email. So this is read by an
    // administrator who is obliged to open it and then by every member, which is exactly the shape
    // the stored-injection fix exists for.
    //
    // The consequence worth knowing: the member safelist drops <img>, so an event body cannot
    // carry a picture. Putting same-origin attachment images back is a deliberate change to what a
    // member may write, not something to slip in behind a security fix.
    model.put("bodyHtml", Markdown.toSafeHtml(event.body()));
    model.put("action", config.urls.calendar);
    model.put("backUrl", config.urls.calendar);
    model.put("draft", !event.published());
    // comments, on the same machinery the board uses. A page nobody can answer on is a notice
    // board; a page they can is where the "can I bring my sister" and the "I will drive" happen,
    // which is the half of an event that actually organises it.
    io.hearth.board.CommentBox.render(model, accounts, io.hearth.board.Subject.event(event.id()),
        me, config.urls.calendar, accounts.access.can(me,
            io.hearth.auth.Permission.calendar_moderate), event);
    model.put("commentPhase", io.hearth.board.CommentPhase.of(event,
        new java.sql.Timestamp(System.currentTimeMillis())).label());
    model.put("commentSubjectField", "event");
    model.put("commentSubjectId", event.id());

    // The guest list is names, not a count. A community event is people deciding whether to go
    // based on who else is, and a bare number answers a question nobody asked.
    ArrayList<Map<String, Object>> going = new ArrayList<>();
    ArrayList<Map<String, Object>> maybe = new ArrayList<>();
    ArrayList<Map<String, Object>> waiting = new ArrayList<>();
    ArrayList<Map<String, Object>> no = new ArrayList<>();
    java.util.HashSet<Long> answered = new java.util.HashSet<>();
    // ...and it is names. A guest list of email addresses is a mailing list handed to everybody who
    // opens the page, which is not what anybody meant by "who else is coming".
    io.hearth.people.Names names = io.hearth.people.Names.of(accounts);
    for (Calendar.Rsvp rsvp : accounts.calendar.guestList(event.id())) {
      LinkedHashMap<String, Object> guest = new LinkedHashMap<>();
      guest.put("who", names.of(rsvp.userId()));
      // how the answer arrived, because "answered from their calendar" is a different person to
      // follow up with than "clicked a button here" -- they may never have opened the site
      guest.put("byEmail", rsvp.fromEmail());
      guest.put("noShow", rsvp.noShow());
      guest.put("proposed", rsvp.proposesATime() ? String.valueOf(rsvp.proposedOn()) : null);
      guest.put("party", rsvp.party());
      guest.put("plus", rsvp.party() > 1 ? " +" + (rsvp.party() - 1) : "");
      guest.put("note", rsvp.note());
      guest.put("me", rsvp.userId() == me.id());
      guest.put("userId", rsvp.userId());
      guest.put("eventId", event.id());
      answered.add(rsvp.userId());
      switch (rsvp.answer()) {
        case going -> going.add(guest);
        case maybe -> maybe.add(guest);
        case waitlist -> waiting.add(guest);
        case no -> no.add(guest);
        default -> {
        }
      }
    }

    // Who has not said anything, by name.
    //
    // This is the list the whole reminder loop exists for, and putting it on the page rather than
    // only in a nudge is deliberate: "we have not heard from six people" is a thing somebody at the
    // event can act on by asking one of them in person, which is the entire point of the product.
    ArrayList<String> silent = new ArrayList<>();
    if (!event.over(java.time.LocalDate.now(config.zone)) && !event.cancelled()) {
      for (io.hearth.auth.UserRecord member : accounts.users.recent(500)) {
        if (member.disabled() || !accounts.access.isApproved(member)
            || answered.contains(member.id())) {
          continue;
        }
        silent.add(names.of(member.id()));
      }
    }
    model.put("silent", silent);
    model.put("anySilent", !silent.isEmpty());
    model.put("remindOn", !config.calendar.remindDaysBefore.isEmpty());
    model.put("remindDays", config.calendar.remindDaysBefore.stream()
        .map(String::valueOf).collect(java.util.stream.Collectors.joining(" and ")));
    model.put("no", no);
    model.put("anyNo", !no.isEmpty());

    // days somebody's calendar suggested instead, which nothing has acted on
    ArrayList<Map<String, Object>> proposals = new ArrayList<>();
    for (Calendar.Rsvp rsvp : accounts.calendar.guestList(event.id())) {
      if (!rsvp.proposesATime()) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("who", names.of(rsvp.userId()));
      row.put("userId", rsvp.userId());
      row.put("proposed", String.valueOf(rsvp.proposedOn()));
      proposals.add(row);
    }
    model.put("proposals", proposals);
    model.put("anyProposals", !proposals.isEmpty());

    // People from outside, by name only.
    //
    // They are coming to the same evening, so the people who are also coming should know they
    // exist -- but an address is not a name here any more than it is anywhere else, and somebody
    // whose calendar sent no name is "a guest" rather than the part of their address before the @.
    // The addresses, and the decision about whether to invite any of them, are in the admin
    // section, where a decision about an address is what is being made.
    ArrayList<Map<String, Object>> outside = new ArrayList<>();
    for (Calendar.Outsider guest : accounts.calendar.outsiders(event.id())) {
      if (guest.converted() || guest.answer() == Calendar.Answer.no) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("who", guest.name() == null || guest.name().isBlank() ? "a guest" : guest.name());
      row.put("plus", guest.party() > 1 ? " +" + (guest.party() - 1) : "");
      row.put("maybe", guest.answer() == Calendar.Answer.maybe);
      outside.add(row);
    }
    model.put("outside", outside);
    model.put("anyOutside", !outside.isEmpty());
    model.put("canMove", accounts.access.can(me, io.hearth.auth.Permission.calendar_write));
    // marking somebody absent is a statement about a person, so it needs the permission that
    // covers keeping the calendar -- and only makes sense once the thing has happened
    model.put("canMarkAttendance", event.over(java.time.LocalDate.now(config.zone))
        && accounts.access.can(me, io.hearth.auth.Permission.calendar_write));
    model.put("eventId", event.id());
    model.put("going", going);
    model.put("anyGoing", !going.isEmpty());
    model.put("maybe", maybe);
    model.put("anyMaybe", !maybe.isEmpty());
    model.put("waiting", waiting);
    model.put("anyWaiting", !waiting.isEmpty());

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("event", model));
  }

  /** everything about one event that both the list and the page need */
  private Map<String, Object> row(DomainConfig config, Accounts accounts, Calendar.Event event,
                                  UserRecord me, LocalDate today) throws SQLException {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", event.id());
    row.put("url", config.urls.calendar + "/" + event.id());
    row.put("title", event.title());
    row.put("location", event.location());
    row.put("anyLocation", !event.location().isBlank());
    // the address book entry, when it has one: its name, and a link to everything the community
    // wrote down about it. A location that is a place is worth more than a location that is a
    // string, and the string is still there for "back room".
    if (event.placeId() != null && config.has(io.hearth.vhost.Surface.places)) {
      io.hearth.places.Places.Place place = accounts.places.byId(event.placeId());
      if (place != null) {
        row.put("placeName", place.name());
        row.put("placeAddress", place.address());
        row.put("placeUrl", config.urls.places + "/" + place.typeSlug() + "/" + place.slug());
        row.put("anyPlace", true);
      }
    }
    row.put("startTime", event.startTime());
    row.put("anyTime", !event.startTime().isBlank());
    row.put("when", event.spansDays()
        ? SHORT.format(event.startsOn()) + " to " + DAY.format(event.endsOn())
        : DAY.format(event.startsOn()));
    row.put("day", event.startsOn().getDayOfMonth());
    row.put("month", event.startsOn().getMonth().getDisplayName(
        java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()));
    row.put("spansDays", event.spansDays());
    row.put("cancelled", event.cancelled());
    row.put("today", event.today(today));
    row.put("over", event.over(today));
    row.put("goingCount", event.goingCount());
    row.put("maybeCount", event.maybeCount());
    row.put("waitlistCount", event.waitlistCount());
    row.put("limited", event.limited());
    row.put("capacity", event.capacity());
    row.put("seatsLeft", event.seatsLeft());
    row.put("full", event.full());

    Calendar.Rsvp mine = accounts.calendar.rsvpFor(event.id(), me.id());
    row.put("answered", mine != null);
    // what the server decided, never what they clicked: somebody who asked to come to a full event
    // is waiting, and a page that showed them a tick would be lying about a seat
    row.put("myAnswer", mine == null ? "" : mine.answer().name());
    row.put("myParty", mine == null ? 1 : mine.party());
    row.put("myNote", mine == null ? "" : mine.note());
    row.put("iAmGoing", mine != null && mine.answer() == Calendar.Answer.going);
    row.put("iAmMaybe", mine != null && mine.answer() == Calendar.Answer.maybe);
    row.put("iSaidNo", mine != null && mine.answer() == Calendar.Answer.no);
    row.put("iAmWaiting", mine != null && mine.answer() == Calendar.Answer.waitlist);
    row.put("canAnswer", !event.cancelled() && !event.over(today));
    row.put("icsUrl", config.urls.calendar + "/" + event.id() + ".ics");
    row.put("openToPublic", event.openToPublic());
    return row;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req,
                                   String title, String csrf) {
    Map<String, Object> model = new HashMap<>();
    io.hearth.web.Chrome.site(model, config, accounts, req);
    model.put("title", title);
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("csrf", csrf);
    return model;
  }

  private void send(ChannelHandlerContext ctx, FullHttpRequest req, Accounts accounts, String csrf,
                    byte[] html) {
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", html,
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }

  private void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                        WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  private static long idOf(String raw) {
    try {
      return raw == null ? -1 : Long.parseLong(raw.trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }
}
