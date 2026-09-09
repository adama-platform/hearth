package io.hearth.calendar;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.inbox.Outgoing;
import io.hearth.inbox.Postman;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Cookies;
import io.hearth.web.Flash;
import io.hearth.web.Forms;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Somebody's calendar, and the URL their phone subscribes to.
 *
 * <b>An agenda rather than a grid.</b> A month grid is what a calendar looks like and an agenda is
 * what a person reads: seven cells of a grid are empty on any given week, and the two things
 * actually being asked are "what is today" and "what is coming". So this draws the next few weeks
 * as a list of days with something in them, which is also the shape that works on a phone without
 * a second layout.
 *
 * <b>The feed is the point.</b> Replacing a hosted calendar does not mean writing a better calendar
 * -- it means the phone, the laptop and the watch all showing the same events. A subscribable ICS
 * URL does that with no protocol nobody has implemented and no app to install: every calendar
 * client on earth reads one. What it does <b>not</b> do is let those clients write back; that needs
 * CalDAV, which is a different specification and is not here. Said out loud rather than discovered.
 *
 * <b>An invitation is answered by mail, because that is what an invitation is.</b> The organizer
 * gets an iTIP REPLY carrying exactly one attendee -- the person answering -- and their calendar
 * updates itself. Sending the whole attendee list back is a common mistake and a real one: the
 * organizer's software takes a REPLY as authoritative, so answering for yourself would reset what
 * everybody else had said.
 */
public class CalendarRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(CalendarRoutes.class);
  /** the public feed lives outside the account paths, because a phone has no session */
  public static final String FEED_PREFIX = "/calendar/";
  private static final String FEED_SUFFIX = ".ics";
  /** 24 random bytes as url-safe base64 with no padding; see Events.mintFeedToken */
  private static final int TOKEN_CHARS = 32;
  /** how far ahead the agenda looks */
  private static final int AGENDA_DAYS = 60;
  private static final DateTimeFormatter DAY_LABEL =
      DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
  private static final DateTimeFormatter FIELD =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US);

  private final Templates templates;
  private final Postman postman;
  private final Flash flash;
  private final Verbose verbose;

  public CalendarRoutes(Templates templates, Postman postman, Verbose verbose) {
    this.templates = templates;
    this.postman = postman;
    this.flash = new Flash();
    this.verbose = verbose;
  }

  /** the screens, which need a session */
  public static boolean owns(DomainConfig config, String path) {
    String root = config.urls.self + "/calendar";
    return path.equals(root) || path.startsWith(root + "/");
  }

  /**
   * The subscription feed, which deliberately does not.
   *
   * A calendar client has no cookie jar and no way to sign in; the token in the URL is the whole
   * credential, which is why it is long, random, hashed at rest and revocable.
   */
  public static boolean isFeed(String path) {
    if (!path.startsWith(FEED_PREFIX) || !path.endsWith(FEED_SUFFIX)) {
      return false;
    }
    // The segment has to look like a token this server minted, not merely sit at this address.
    //
    // Without the shape check, `/calendar/anything.ics` is claimed by this route and a community
    // with a page at that address finds it answering 404 for ever, with nothing on any screen
    // saying why. A token is 32 characters of url-safe base64, so requiring that costs nothing and
    // hands every other spelling back to the site.
    String token = path.substring(FEED_PREFIX.length(), path.length() - FEED_SUFFIX.length());
    return token.length() == TOKEN_CHARS && token.matches("[A-Za-z0-9_-]+");
  }

  // ---- the feed ------------------------------------------------------------------------------

  public void feed(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, WebHandler.Recorder recorder) {
    String path = Forms.path(req.uri());
    String token = path.substring(FEED_PREFIX.length(), path.length() - FEED_SUFFIX.length());
    try {
      Long userId = accounts.events.userForFeed(token);
      if (userId == null) {
        // A wrong token and a revoked one answer identically, and neither says "no such feed".
        //
        // This is the one URL on the server anybody on the internet can guess at, so it must not
        // confirm anything: not that a token nearly worked, not that somebody has a calendar.
        recorder.status(404);
        Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
            "no calendar here".getBytes(StandardCharsets.UTF_8));
        return;
      }
      // The token says whose calendar; the account says whether they may still have one.
      //
      // <b>Turning an account off revokes its sessions, and this URL has no session.</b> Without
      // this the one credential a disabled person keeps is the feed they minted while they were
      // still a member -- and it goes on serving their calendar, from a phone, for years. The same
      // 404 as a wrong token: whether an account is disabled is not something an unauthenticated
      // request should be able to find out.
      io.hearth.auth.UserRecord owner = accounts.users.byId(userId);
      if (owner == null || !accounts.access.isApproved(owner) || owner.disabled()) {
        recorder.status(404);
        Responses.send(ctx, req, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
            "no calendar here".getBytes(StandardCharsets.UTF_8));
        return;
      }
      String body = accounts.events.feed(userId, Events.feedName(config.name), accounts.zone());
      accounts.events.feedRead(userId);
      recorder.status(200);
      Responses.send(ctx, req, HttpResponseStatus.OK, "text/calendar; charset=utf-8",
          body.getBytes(StandardCharsets.UTF_8), new String[]{
              // a calendar client polls this every fifteen minutes forever; a shared cache holding
              // one person's appointments is the one thing this header exists to prevent
              "Cache-Control", "private, no-store",
              "Content-Disposition", "inline; filename=\"calendar.ics\""});
    } catch (SQLException ex) {
      LOG.error("calendar-feed-failed", ex);
      recorder.status(500);
      Responses.send(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "text/plain; charset=utf-8", "not right now".getBytes(StandardCharsets.UTF_8));
    }
  }

  // ---- the screens -----------------------------------------------------------------------------

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null) {
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                io.hearth.web.Landing.carry(config.urls.login, io.hearth.web.Landing.here(req))});
        return;
      }
      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, session, recorder);
        return;
      }
      String root = config.urls.self + "/calendar";
      String path = Forms.path(req.uri());
      String rest = path.equals(root) ? "" : path.substring(root.length() + 1);
      if (rest.equals("new") || rest.startsWith("edit/")) {
        form(config, accounts, ctx, req, me, session, recorder,
            rest.equals("new") ? 0 : longOr(rest.substring(5)));
        return;
      }
      agenda(config, accounts, ctx, req, me, session, recorder);
    } catch (SQLException | RuntimeException ex) {
      LOG.error("calendar-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = new LinkedHashMap<>();
      Chrome.admin(model, accounts);
      model.put("title", "Something went wrong");
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      model.put("nav", List.of());
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void agenda(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                      FullHttpRequest req, UserRecord me, SessionRecord session,
                      WebHandler.Recorder recorder) throws SQLException {
    ZoneId zone = accounts.zone();
    String csrf = Cookies.stableToken(req);
    LocalDate from = LocalDate.now(zone);
    long start = from.atStartOfDay(zone).toInstant().toEpochMilli();
    long end = from.plusDays(AGENDA_DAYS).atStartOfDay(zone).toInstant().toEpochMilli();

    List<Occurrence> occurrences = occurrencesBetween(accounts, me.id(), start, end, zone);
    Map<String, Object> model = shell(config, accounts, me, csrf, session);
    model.put("days", daysOf(occurrences, zone, config));
    model.put("any", !occurrences.isEmpty());
    model.put("horizon", AGENDA_DAYS);
    model.put("newUrl", config.urls.self + "/calendar/new");

    // invitations still waiting, which is the only thing a calendar should nag about
    ArrayList<Map<String, Object>> waiting = new ArrayList<>();
    for (Events.Record event : accounts.events.unanswered(me.id())) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("uid", event.uid());
      row.put("summary", event.summary());
      row.put("when", label(event.startMillis(), zone, event.allDay()));
      row.put("organizer", event.organizer());
      row.put("location", event.location());
      waiting.add(row);
    }
    model.put("invitations", waiting);
    model.put("anyInvitations", !waiting.isEmpty());
    model.put("canReply", postman != null);

    boolean hasFeed = accounts.events.hasFeed(me.id());
    model.put("hasFeed", hasFeed);
    model.put("total", accounts.events.count(me.id()));
    send(ctx, req, recorder, accounts, csrf, templates.render("calendar", model));
  }

  /** the create-or-edit form for one event */
  private void form(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, SessionRecord session,
                    WebHandler.Recorder recorder, long id) throws SQLException {
    Events.Record event = id <= 0 ? null : accounts.events.byId(id, me.id());
    if (id > 0 && event == null) {
      recorder.status(404);
      Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, notFound(accounts));
      return;
    }
    ZoneId zone = accounts.zone();
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = shell(config, accounts, me, csrf, session);
    model.put("editing", event != null);
    model.put("heading", event == null ? "A new event" : event.summary());
    model.put("form_id", event == null ? "" : String.valueOf(event.id()));
    model.put("form_summary", event == null ? "" : event.summary());
    model.put("form_description", event == null ? "" : event.description());
    model.put("form_location", event == null ? "" : event.location());
    model.put("form_allDay", event != null && event.allDay());
    model.put("form_rrule", event == null ? "" : event.rrule());
    long start = event == null
        ? LocalDate.now(zone).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        : event.startMillis();
    long finish = event == null ? start + 3_600_000L : event.endMillis();
    model.put("form_starts", FIELD.format(ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(start), zone)));
    model.put("form_ends", FIELD.format(ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(finish), zone)));
    model.put("invited", event != null && event.invited());
    model.put("organizer", event == null ? "" : event.organizer());
    model.put("backUrl", config.urls.self + "/calendar");
    model.put("zone", zone.getId());
    send(ctx, req, recorder, accounts, csrf, templates.render("calendar_form", model));
  }

  // ---- doing things ------------------------------------------------------------------------------

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, SessionRecord session,
                   WebHandler.Recorder recorder) throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.self + "/calendar";
    String outcome;
    boolean problem = false;

    if (form.bodyTooLarge()) {
      outcome = "That was too long to save. Nothing was changed.";
      problem = true;
    } else if (!Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
        Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      outcome = "That form expired. Please try again.";
      problem = true;
    } else {
      switch (String.valueOf(form.get("action"))) {
        case "save" -> {
          Saved saved = save(accounts, me, form);
          outcome = saved.message;
          problem = saved.problem;
        }
        case "delete" -> {
          long id = longOr(form.get("id"));
          outcome = accounts.events.delete(id, me.id()) ? "That event is gone."
              : "That event is not here.";
          problem = !outcome.startsWith("That event is gone");
        }
        case "answer" -> {
          Saved answered = answer(config, accounts, me, form);
          outcome = answered.message;
          problem = answered.problem;
        }
        case "feed" -> {
          // Through the flash's secret channel, which exists for exactly this.
          //
          // The row holds a hash, so this is the only moment the URL exists in a readable form. It
          // must not go in the redirect, the history or the access log, and a refresh must not
          // mint a second one -- which is the whole of what that channel already does.
          String token = accounts.events.mintFeedToken(me.id());
          flash.set(Flash.keyFor(session), "Here is your subscription link. It is shown once.",
              false, "https://" + config.domain + FEED_PREFIX + token + FEED_SUFFIX);
          recorder.status(303);
          Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
              new String[]{HttpHeaderNames.LOCATION.toString(), where});
          return;
        }
        case "revoke" -> {
          accounts.events.revokeFeed(me.id());
          outcome = "That link stops working now. Anything subscribed to it will stop updating.";
        }
        default -> {
          outcome = "That is not something this page can do.";
          problem = true;
        }
      }
    }
    flash.set(Flash.keyFor(session), outcome, problem);
    verbose.detail("calendar: " + me.email() + " -> " + outcome);
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  private record Saved(String message, boolean problem) {
  }

  private Saved save(Accounts accounts, UserRecord me, Forms form) throws SQLException {
    String summary = orEmpty(form.get("summary")).trim();
    if (summary.isBlank()) {
      return new Saved("An event needs a name.", true);
    }
    if (form.tooLong() != null) {
      return new Saved("'" + form.tooLong() + "' is too long. Nothing was changed.", true);
    }
    ZoneId zone = accounts.zone();
    boolean allDay = form.get("allDay") != null;
    Long start = parseLocal(orEmpty(form.get("starts")), zone, allDay, false);
    Long end = parseLocal(orEmpty(form.get("ends")), zone, allDay, true);
    if (start == null) {
      return new Saved("That start time could not be read.", true);
    }
    if (end == null || end < start) {
      // an event that ends before it starts draws as nothing at all, which looks like it was not
      // saved -- so it is refused rather than quietly corrected
      return new Saved("An event cannot end before it starts.", true);
    }
    long id = longOr(form.get("id"));
    Events.Record existing = id <= 0 ? null : accounts.events.byId(id, me.id());
    if (id > 0 && existing == null) {
      return new Saved("That event is not here.", true);
    }
    // Editing keeps the UID and raises the SEQUENCE.
    //
    // That is what makes an edit propagate to a subscriber rather than appearing beside the old
    // version: every calendar client keys on UID and takes the higher sequence as the newer.
    String uid = existing != null ? existing.uid()
        : java.util.UUID.randomUUID() + "@" + me.id() + ".hearth";
    int sequence = existing == null ? 0 : existing.sequence() + 1;

    IcsFile.Event event = new IcsFile.Event(uid, sequence, summary,
        orEmpty(form.text("description")), orEmpty(form.get("location")), start, end, allDay,
        orEmpty(form.get("rrule")).trim(), existing == null ? "" : existing.exdates(),
        "CONFIRMED", existing == null ? "" : existing.organizer(),
        existing == null ? List.of() : existing.attendees());
    Events.Outcome outcome = accounts.events.merge(me.id(), event, "typed",
        existing == null ? null : existing.fromMessage(), null);
    return new Saved(outcome == Events.Outcome.added ? "Added." : "Saved.", false);
  }

  /**
   * Answer an invitation, and tell the organizer.
   *
   * The answer is recorded whether or not the mail goes out. A calendar that refuses to remember
   * "I am not going" because a mail server was busy is a calendar that lies to the person holding
   * it -- so the record is the truth and the message is best effort, and the screen says which of
   * those happened.
   */
  private Saved answer(DomainConfig config, Accounts accounts, UserRecord me, Forms form)
      throws SQLException {
    String uid = orEmpty(form.get("uid"));
    String partstat = orEmpty(form.get("partstat")).toUpperCase(Locale.ROOT);
    if (!Events.answers().contains(partstat)) {
      return new Saved("That is not an answer.", true);
    }
    Events.Record event = accounts.events.byUid(me.id(), uid);
    if (event == null) {
      return new Saved("That invitation is not here.", true);
    }
    accounts.events.answer(me.id(), uid, partstat);
    String said = "You are down as " + Events.answerLabel(partstat) + ".";
    if (postman == null || event.organizer().isBlank()) {
      return new Saved(said + " Nobody was told: this server cannot send mail.",
          postman != null && !event.organizer().isBlank());
    }
    // The REPLY goes out from the address the invitation arrived at, for the same reason a reply to
    // a message does: it is the address the organizer's calendar has against this person's name.
    String from = replyAddressFor(accounts, me, event, config);
    if (from == null) {
      return new Saved(said + " The organizer was not told, because there is no address here to"
          + " send it from.", true);
    }
    String name = accounts.people.profileOf(me.id()).nameOr("");
    String ics = IcsFile.reply(event.asIcs(), from, name, partstat);
    Outgoing.Written written = Outgoing.fresh(from, name, List.of(event.organizer()), List.of(),
        Events.answerLabel(partstat) + ": " + event.summary(),
        name + " has answered " + Events.answerLabel(partstat) + ".\n\n" + ics, accounts.zone());
    Postman.Sent sent = postman.send(from, written);
    return new Saved(said + (sent.anyDelivered() ? " The organizer has been told."
        : " The organizer was not told: " + sent.describe()), !sent.anyDelivered());
  }

  /**
   * Which of this person's addresses to answer an invitation from.
   *
   * The one the organizer invited, when it is one of theirs -- that is the address in the ATTENDEE
   * line and the one the organizer's software will match a REPLY against. Otherwise the message
   * this arrived in decides, and if there is no such message there is nothing honest to send from.
   */
  private String replyAddressFor(Accounts accounts, UserRecord me, Events.Record event,
                                 DomainConfig config) throws SQLException {
    java.util.Set<String> mine = io.hearth.inbox.InboxRoutes.addressesOf(accounts, me);
    for (IcsFile.Attendee attendee : event.attendees()) {
      if (mine.contains(attendee.address())) {
        return Postman.canSendAs(attendee.address(), java.util.Set.of(config.domain))
            ? attendee.address() : null;
      }
    }
    if (event.fromMessage() != null) {
      io.hearth.inbox.Messages.Record message =
          accounts.inbox.byId(event.fromMessage(), me.id());
      if (message != null
          && Postman.canSendAs(message.deliveredTo(), java.util.Set.of(config.domain))) {
        return message.deliveredTo();
      }
    }
    return null;
  }

  // ---- the agenda ------------------------------------------------------------------------------

  /** one appearance of an event on the calendar; a repeat has many */
  record Occurrence(Events.Record event, long start, long end) {
  }

  /**
   * Every appearance of every event in a window, repeats expanded.
   *
   * The expansion is {@link io.hearth.vote.Ics#occurrences}, which is the same code the scheduler
   * uses to work out when somebody is busy -- one reading of RRULE, not two. An occurrence outside
   * the window is dropped here rather than in the query, because a repeat's stored start is almost
   * always outside it.
   */
  static List<Occurrence> occurrencesBetween(Accounts accounts, long userId, long from, long to,
                                             ZoneId zone) throws SQLException {
    ArrayList<Occurrence> out = new ArrayList<>();
    for (Events.Record event : accounts.events.between(userId, from, to)) {
      if (event.cancelled()) {
        // still in the feed, with its status, and not drawn on the agenda: a cancelled meeting
        // should stop occupying a morning
        continue;
      }
      long length = Math.max(0, event.endMillis() - event.startMillis());
      if (!event.repeats()) {
        out.add(new Occurrence(event, event.startMillis(), event.endMillis()));
        continue;
      }
      // Expanded over the window being drawn rather than over the event's own first hundred and
      // twenty days: a weekly standup set up two years ago has a start far behind today, and the
      // other reading makes it vanish from the calendar of everybody who has been attending it.
      // The window is widened by the event's length so one that started before it still shows.
      for (long start : io.hearth.vote.Ics.occurrencesIn(event.startMillis(), event.rrule(), zone,
          from - length, to)) {
        if (start < to && start + length > from) {
          out.add(new Occurrence(event, start, start + length));
        }
      }
    }
    out.sort((left, right) -> Long.compare(left.start(), right.start()));
    return out;
  }

  private List<Map<String, Object>> daysOf(List<Occurrence> occurrences, ZoneId zone,
                                           DomainConfig config) {
    LinkedHashMap<LocalDate, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
    for (Occurrence occurrence : occurrences) {
      LocalDate day = java.time.Instant.ofEpochMilli(occurrence.start()).atZone(zone)
          .toLocalDate();
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      Events.Record event = occurrence.event();
      row.put("id", event.id());
      row.put("summary", event.summary());
      row.put("location", event.location());
      row.put("hasLocation", !event.location().isBlank());
      row.put("allDay", event.allDay());
      row.put("time", event.allDay() ? "all day"
          : TIME.format(java.time.Instant.ofEpochMilli(occurrence.start()).atZone(zone))
              + "–" + TIME.format(java.time.Instant.ofEpochMilli(occurrence.end()).atZone(zone)));
      row.put("repeats", event.repeats());
      row.put("invited", event.invited());
      row.put("answer", Events.answerLabel(event.myAnswer()));
      row.put("answered", event.answered());
      row.put("editUrl", config.urls.self + "/calendar/edit/" + event.id());
      byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(row);
    }
    LocalDate today = LocalDate.now(zone);
    ArrayList<Map<String, Object>> days = new ArrayList<>();
    for (Map.Entry<LocalDate, List<Map<String, Object>>> entry : byDay.entrySet()) {
      LinkedHashMap<String, Object> day = new LinkedHashMap<>();
      day.put("label", entry.getKey().format(DAY_LABEL));
      day.put("today", entry.getKey().equals(today));
      day.put("tomorrow", entry.getKey().equals(today.plusDays(1)));
      day.put("events", entry.getValue());
      days.add(day);
    }
    return days;
  }

  // ---- plumbing ----------------------------------------------------------------------------------

  /**
   * A local date-time from a form field, in this calendar's zone.
   *
   * <b>Never `ZoneId.systemDefault()`</b> -- invariant 9. "Nine o'clock" is a fact about where the
   * person is, and a box rented in another continent would otherwise put every appointment a few
   * hours out.
   */
  static Long parseLocal(String value, ZoneId zone, boolean allDay, boolean isEnd) {
    String clean = value == null ? "" : value.trim();
    if (clean.isEmpty()) {
      return null;
    }
    try {
      if (allDay || clean.length() == 10) {
        LocalDate date = LocalDate.parse(clean.length() >= 10 ? clean.substring(0, 10) : clean);
        // an all-day event's end is the start of the next day, which is what every client expects
        // and is the difference between a one-day event and one that renders as nothing
        return (isEnd ? date.plusDays(1) : date).atStartOfDay(zone).toInstant().toEpochMilli();
      }
      LocalDateTime at = LocalDateTime.parse(clean.length() > 16 ? clean.substring(0, 16) : clean);
      return at.atZone(zone).toInstant().toEpochMilli();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String label(long millis, ZoneId zone, boolean allDay) {
    ZonedDateTime at = java.time.Instant.ofEpochMilli(millis).atZone(zone);
    return allDay ? at.format(DAY_LABEL) : at.format(DAY_LABEL) + " at " + at.format(TIME);
  }

  private Map<String, Object> shell(DomainConfig config, Accounts accounts, UserRecord me,
                                    String csrf, SessionRecord session) throws SQLException {
    Map<String, Object> model = new LinkedHashMap<>();
    Chrome.admin(model, accounts);
    model.put("csrf", csrf);
    model.put("community", config.name);
    model.put("selfUrl", config.urls.self);
    model.put("mailUrl", config.urls.self + "/mail");
    model.put("calendarUrl", config.urls.self + "/calendar");
    model.put("action", config.urls.self + "/calendar");
    model.put("flash", flash.take(Flash.keyFor(session)));
    model.put("unread", accounts.inbox.unreadCount(me.id()));
    model.put("title", "Calendar");
    model.put("nav", List.of());
    return model;
  }

  private void send(ChannelHandlerContext ctx, FullHttpRequest req, WebHandler.Recorder recorder,
                    Accounts accounts, String csrf, byte[] body) {
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", body,
        new String[]{HttpHeaderNames.SET_COOKIE.toString(),
            Cookies.csrf(accounts.security, csrf)});
  }

  private byte[] notFound(Accounts accounts) {
    Map<String, Object> model = new LinkedHashMap<>();
    Chrome.admin(model, accounts);
    model.put("title", "Not found");
    model.put("heading", "Not found");
    model.put("message", "There is nothing here.");
    model.put("nav", List.of());
    return templates.render("message", model);
  }

  static long longOr(Object value) {
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (RuntimeException ex) {
      return 0;
    }
  }

  static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
