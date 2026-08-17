package io.hearth.web;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.board.Board;
import io.hearth.calendar.Calendar;
import io.hearth.common.Verbose;
import io.hearth.people.ProfileRecord;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.Surface;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The page a member lands on: what is waiting for them, what is being said, what is coming up.
 *
 * <b>This is not the front page, and that distinction is the whole reason it exists.</b> `/` is a
 * community's own website -- whatever they wrote there, for whoever arrives -- and most communities
 * will put something at it that is aimed at somebody who is not a member yet. A signed-in member
 * needs a different page entirely, and giving them the same one meant either a home page that
 * greeted strangers with a dashboard or a dashboard nobody could find.
 *
 * Three things, in the order somebody can act on them:
 *
 * <ol>
 *   <li><b>What is waiting for you.</b> The welcome if it was never finished, questions with nobody's
 *       answer on them, replies in the inbox, an event this week that has not been answered. Each is
 *       a link to the one screen that clears it, and the section disappears when it is empty --
 *       a permanent "nothing to do" panel is a panel people stop reading.</li>
 *   <li><b>Conversations you are in.</b> Threads somebody started or joined, most recently active
 *       first, because those are the ones with a reply in them that might be to you. The rest of the
 *       board is underneath rather than absent, so a quiet member still sees what the community is
 *       talking about.</li>
 *   <li><b>The next week.</b> Not the whole calendar -- seven days is what somebody can actually do
 *       something about, and an event two months out on a dashboard is decoration.</li>
 * </ol>
 *
 * Everything here is a read of what other pages own. There is no dashboard state, no dashboard
 * table, and nothing on it that cannot be reached another way.
 */
public class HomeRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(HomeRoutes.class);
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");
  /** how far ahead "coming up" reaches */
  private static final int DAYS_AHEAD = 7;
  private static final int MINE = 8;
  private static final int ELSEWHERE = 5;

  private final Templates templates;
  private final Verbose verbose;

  public HomeRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    SessionRecord session = AccountRoutes.currentSession(accounts, req);
    if (session == null) {
      // a dashboard about nobody is nothing; the landing page is what a stranger gets
      recorder.status(303);
      Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
          new String[]{HttpHeaderNames.LOCATION.toString(),
              Landing.carry(config.urls.login, Landing.here(req))});
      return;
    }
    try {
      UserRecord me = accounts.users.byId(session.userId());
      if (me == null) {
        // a live session whose account has gone: the same answer as signed out, including the way
        // back, because from where somebody is standing that is exactly what happened
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                Landing.carry(config.urls.login, Landing.here(req))});
        return;
      }
      show(config, accounts, ctx, req, me, recorder);
    } catch (SQLException ex) {
      LOG.error("home-failed", ex);
      recorder.status(500);
      Map<String, Object> model = new HashMap<>();
      Chrome.site(model, config, accounts, req);
      model.put("title", "Something went wrong");
      model.put("community", config.name);
      model.put("nav", Navigation.forRequest(config, accounts, req));
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    String csrf = Cookies.stableToken(req);
    model.put("title", config.name);
    model.put("community", config.name);
    model.put("csrf", csrf);
    model.put("nav", Navigation.forRequest(config, accounts, req));

    ProfileRecord profile = accounts.people.profileOf(me.id());
    model.put("name", profile.nameOr("there"));

    List<Map<String, Object>> todo = waiting(config, accounts, me, profile);
    model.put("todo", todo);
    model.put("anyTodo", !todo.isEmpty());

    if (config.has(Surface.board)) {
      conversations(config, accounts, me, model);
    }
    model.put("hasBoard", config.has(Surface.board));
    model.put("boardUrl", config.urls.board);

    if (config.has(Surface.calendar)) {
      week(config, accounts, me, model);
    }
    model.put("hasCalendar", config.has(Surface.calendar));
    model.put("calendarUrl", config.urls.calendar);
    model.put("membersUrl", config.urls.members);
    model.put("hasMembers", config.has(Surface.members));

    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("home", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(),
            Cookies.csrf(accounts.security, csrf)});
  }

  /**
   * The things somebody can clear, and nothing else.
   *
   * Ordered by how much of a loose end each one is: an unfinished welcome is the community not
   * knowing who they are, a reply is somebody waiting on an answer, an event this week is a seat
   * that has to be counted by Thursday, and the questions are the standing ask.
   */
  private List<Map<String, Object>> waiting(DomainConfig config, Accounts accounts, UserRecord me,
                                            ProfileRecord profile) throws SQLException {
    ArrayList<Map<String, Object>> todo = new ArrayList<>();
    if (!profile.oriented()) {
      todo.add(item(profile.orientationStep() == 0
              ? "Say what we should call you" : "Finish setting up your account",
          "It takes a minute, and it is what everybody else sees.",
          config.urls.orientation, "Pick up where you left off"));
    }
    int unread = accounts.inbox.unreadCount(me.id());
    if (unread > 0) {
      todo.add(item(unread == 1 ? "A reply is waiting for you" : unread + " replies are waiting",
          "Somebody said something in a conversation you are part of.",
          config.urls.self + "?tab=inbox", "Read them"));
    }
    if (config.has(Surface.calendar)) {
      int unanswered = unansweredThisWeek(config, accounts, me);
      if (unanswered > 0) {
        todo.add(item(unanswered == 1 ? "An event this week needs an answer"
                : unanswered + " events this week need an answer",
            "Saying whether you are coming is how anybody knows how many chairs to put out.",
            config.urls.calendar, "Have a look"));
      }
    }
    if (config.has(Surface.survey)) {
      int remaining = accounts.survey.remainingFor(me.id());
      if (remaining > 0) {
        todo.add(item(remaining == 1 ? "One question is waiting"
                : remaining + " questions are waiting",
            "This is how the people running things know what the community wants.",
            config.urls.survey, "Answer them"));
      }
    }
    return todo;
  }

  private static int unansweredThisWeek(DomainConfig config, Accounts accounts,
                                        UserRecord me) throws SQLException {
    LocalDate today = LocalDate.now(config.zone);
    int count = 0;
    for (Calendar.Event event : accounts.calendar.upcoming(today, 50)) {
      if (event.cancelled() || event.startsOn().isAfter(today.plusDays(DAYS_AHEAD))) {
        continue;
      }
      if (accounts.calendar.rsvpFor(event.id(), me.id()) == null) {
        count++;
      }
    }
    return count;
  }

  private static Map<String, Object> item(String title, String why, String href, String action) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("title", title);
    row.put("why", why);
    row.put("href", href);
    row.put("action", action);
    return row;
  }

  /**
   * Threads this person is in, and then the board.
   *
   * "In" means they started it or they are watching it, which on this board means they said
   * something in it -- there is no subscribe button, so joining a conversation is what makes you a
   * watcher. One pass over the feed rather than a query per post: the feed is a few dozen live
   * threads at this scale, and it is already the query the board itself runs.
   */
  private void conversations(DomainConfig config, Accounts accounts, UserRecord me,
                             Map<String, Object> model) throws SQLException {
    long now = System.currentTimeMillis();
    io.hearth.people.Names names = io.hearth.people.Names.of(accounts);
    ArrayList<Map<String, Object>> mine = new ArrayList<>();
    ArrayList<Map<String, Object>> rest = new ArrayList<>();
    for (Board.Post post : accounts.board.feed(80)) {
      if (post.removed()) {
        continue;
      }
      boolean involved = post.authorId() == me.id() || post.isWatchedBy(me.id());
      if (involved && mine.size() < MINE) {
        mine.add(thread(config, post, now, true, names));
      } else if (!involved && rest.size() < ELSEWHERE) {
        rest.add(thread(config, post, now, false, names));
      }
    }
    model.put("mine", mine);
    model.put("anyMine", !mine.isEmpty());
    model.put("rest", rest);
    model.put("anyRest", !rest.isEmpty());
  }

  private static Map<String, Object> thread(DomainConfig config, Board.Post post, long now,
                                            boolean mine, io.hearth.people.Names names) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", post.id());
    row.put("title", post.title());
    row.put("href", config.urls.board + "/" + post.id());
    row.put("comments", post.commentCount());
    row.put("anyComments", post.commentCount() > 0);
    row.put("who", names.of(post.authorId()));
    row.put("when", stamp(post.lastActivityAt()));
    row.put("pinned", post.pinned());
    row.put("mine", mine);
    row.put("ageing", post.expires() && post.daysLeft(now) <= 2);
    return row;
  }

  /**
   * The next seven days, with what this person said about each.
   *
   * Seven rather than a month because a dashboard is for what somebody can do something about this
   * week; the calendar itself is one link away and holds everything.
   */
  private void week(DomainConfig config, Accounts accounts, UserRecord me,
                    Map<String, Object> model) throws SQLException {
    LocalDate today = LocalDate.now(config.zone);
    LocalDate horizon = today.plusDays(DAYS_AHEAD);
    ArrayList<Map<String, Object>> events = new ArrayList<>();
    for (Calendar.Event event : accounts.calendar.upcoming(today, 50)) {
      if (event.startsOn().isAfter(horizon)) {
        break;
      }
      Calendar.Rsvp rsvp = accounts.calendar.rsvpFor(event.id(), me.id());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("title", event.title());
      row.put("href", config.urls.calendar + "/" + event.id());
      row.put("day", event.today(today) ? "Today" : DAY.format(event.startsOn()));
      row.put("time", event.startTime());
      row.put("where", event.location());
      row.put("cancelled", event.cancelled());
      // what the server decided, never what was clicked: somebody on the waitlist is told they are
      // on the waitlist rather than shown a tick they do not have
      row.put("answer", rsvp == null ? null : rsvp.answer().name());
      row.put("answered", rsvp != null);
      events.add(row);
    }
    model.put("week", events);
    model.put("anyWeek", !events.isEmpty());
  }

  private static String stamp(Timestamp when) {
    return when == null ? "" : WHEN.format(Instant.ofEpochMilli(when.getTime()));
  }
}
