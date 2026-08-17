package io.hearth.availability;

import io.hearth.auth.Accounts;
import io.hearth.auth.Permission;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Cookies;
import io.hearth.web.Forms;
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
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `/when`: the hours somebody could come, and -- for whoever plans things -- the hours everybody
 * could.
 *
 * <b>One page, two audiences, and the split is a permission rather than a path.</b> A member sees
 * their own windows, their own calendars and what those calendars are doing; somebody who keeps the
 * calendar also sees the grid underneath. Making the grid a second address would have meant a link
 * an ordinary member could not open, which is the thing invariant 149 exists to stop.
 *
 * <b>Nobody sees anybody else's calendar, ever.</b> The grid is counts -- how many people, not
 * which -- because "who is free on Thursday" is a question about individuals that nobody agreed to
 * answer, and a screen that answered it would be a screen people stop putting their calendars into.
 * What an organiser needs is how many, and the numbers are what tells them.
 */
public class AvailabilityRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(AvailabilityRoutes.class);

  private final Templates templates;
  private final Availabilities grids;
  private final Verbose verbose;

  public AvailabilityRoutes(Templates templates, Availabilities grids, Verbose verbose) {
    this.templates = templates;
    this.grids = grids;
    this.verbose = verbose;
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
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
      show(config, accounts, ctx, req, me, recorder, null);
    } catch (SQLException ex) {
      LOG.error("availability-route-failed", ex);
      recorder.status(500);
      Map<String, Object> model = new HashMap<>();
      Chrome.site(model, config, accounts, req);
      model.put("title", "Something went wrong");
      model.put("community", config.name);
      model.put("nav", List.of());
      model.put("heading", "Something went wrong");
      model.put("message", "That did not work, and it is our fault rather than yours.");
      Responses.sendHtml(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR,
          templates.render("message", model));
    }
  }

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Forms form = Forms.of(req);
    if (form.bodyTooLarge() || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
        Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, config.urls.availability);
      return;
    }
    String action = String.valueOf(form.get("action"));
    String problem = switch (action) {
      case "add_window" -> addWindow(accounts, me, form);
      case "remove_window" -> {
        Long id = longOf(form.get("window"));
        yield id != null && accounts.availability.removeWindow(me.id(), id)
            ? null : "That is not one of yours.";
      }
      case "add_link" -> addLink(config, accounts, me, form);
      case "remove_link" -> {
        Long id = longOf(form.get("link"));
        yield id != null && accounts.availability.removeLink(me.id(), id)
            ? null : "That is not one of yours.";
      }
      default -> "That is not something this page can do.";
    };
    if (problem != null) {
      show(config, accounts, ctx, req, me, recorder, problem);
      return;
    }
    redirect(ctx, req, recorder, config.urls.availability);
  }

  private String addWindow(Accounts accounts, UserRecord me, Forms form) throws SQLException {
    if (accounts.availability.countWindows(me.id()) >= Availability.MAX_WINDOWS) {
      return "That is as many as one person can draw. Take one away first -- past this it is a"
          + " diary rather than a shape.";
    }
    DayOfWeek day = dayOf(form.get("day"));
    int from = Availability.minutesOf(form.get("from"));
    int to = Availability.minutesOf(form.get("to"));
    if (day == null) {
      return "Pick a day.";
    }
    if (from < 0 || to < 0) {
      return "A time looks like 19:00.";
    }
    if (to <= from) {
      return "The end has to come after the start.";
    }
    return accounts.availability.addWindow(me.id(), day, from, to, form.get("note")) == null
        ? "That did not look like a stretch of a day." : null;
  }

  private String addLink(DomainConfig config, Accounts accounts, UserRecord me, Forms form)
      throws SQLException {
    if (accounts.availability.countLinks(me.id()) >= config.availability.maxLinks) {
      return "That is as many calendars as this community reads for one person.";
    }
    String url = CalendarFetch.clean(form.get("url"));
    // checked here rather than at fetch time so the person who pasted it finds out now, while they
    // still have the right address on their clipboard
    String refused = CalendarFetch.check(url);
    if (refused != null) {
      return refused;
    }
    accounts.availability.addLink(me.id(), url, form.get("label"));
    verbose.detail(() -> "availability: " + me.email() + " added a calendar");
    return null;
  }

  private void show(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                    FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder,
                    String problem) throws SQLException {
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", "When you can come");
    model.put("community", config.name);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("csrf", csrf);
    model.put("action", config.urls.availability);
    model.put("problem", problem);
    model.put("days", dayOptions());
    model.put("maxLinks", config.availability.maxLinks);
    model.put("horizonDays", config.availability.horizonDays);
    model.put("refreshHour", String.format("%02d:00", config.availability.refreshHour));
    model.put("examples", CalendarFetch.examples());

    List<Availability.Window> mine = accounts.availability.windowsFor(me.id());
    ArrayList<Map<String, Object>> windows = new ArrayList<>();
    for (Availability.Window window : mine) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", window.id());
      row.put("day", window.day().getDisplayName(java.time.format.TextStyle.FULL,
          java.util.Locale.getDefault()));
      row.put("from", window.from());
      row.put("to", window.to());
      row.put("note", window.note());
      windows.add(row);
    }
    model.put("windows", windows);
    model.put("anyWindows", !windows.isEmpty());
    // what this server is assuming about somebody who has said nothing, said out loud rather than
    // left for them to discover from a grid that already counts them
    model.put("assumed", windows.isEmpty());
    model.put("assumedEvening", AvailabilityConfig.ASSUMED_EVENING_HOUR + ":00");
    model.put("assumedNight", AvailabilityConfig.ASSUMED_NIGHT_HOUR + ":00");
    model.put("assumedWeekend", AvailabilityConfig.ASSUMED_WEEKEND_HOUR + ":00");

    ArrayList<Map<String, Object>> links = new ArrayList<>();
    for (Availability.Link link : accounts.availability.linksFor(me.id())) {
      Availability.Cached cached =
          accounts.availability.cachedFor(me.id(), Availability.hashOf(link.url()));
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", link.id());
      row.put("label", link.display());
      row.put("host", link.shortUrl());
      row.put("read", cached == null || cached.fetchedAt() == null ? "not yet"
          : cached.fetchedAt().toLocalDateTime().toLocalDate().toString());
      row.put("blocks", cached == null ? 0 : cached.blocks());
      // a link that quietly stopped working is a member this grid starts lying about
      row.put("problem", cached != null && !cached.ok() ? cached.detail() : null);
      links.add(row);
    }
    model.put("links", links);
    model.put("anyLinks", !links.isEmpty());

    boolean planner = accounts.access.can(me, Permission.calendar_write);
    model.put("planner", planner);
    if (planner) {
      AvailabilityIndexer indexer = grids.forDomain(config.domain);
      if (indexer != null) {
        grid(model, indexer.grid());
      }
    }
    recorder.status(200);
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8",
        templates.render("availability", model),
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }

  /**
   * The grid, as rows a template can draw.
   *
   * Hours nobody has claimed anywhere in the week are left out. A 24-row table is mostly empty
   * nights, and on a phone it is a wall -- the hours worth arguing about are the ones somebody has
   * already said they could do.
   */
  static void grid(Map<String, Object> model, Heatmap.Grid grid) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (int hour = 0; hour < Heatmap.HOURS; hour++) {
      int across = 0;
      ArrayList<Map<String, Object>> cells = new ArrayList<>();
      for (DayOfWeek day : DayOfWeek.values()) {
        Heatmap.Cell cell = grid.at(day, hour);
        across += cell.ideal();
        LinkedHashMap<String, Object> one = new LinkedHashMap<>();
        one.put("day", day.getDisplayName(java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault()));
        one.put("ideal", cell.ideal());
        one.put("clear", cell.clear());
        one.put("any", cell.ideal() > 0);
        // five steps rather than a gradient: a colour somebody has to compare against a key is a
        // colour nobody reads, and this is a table people glance at
        one.put("heat", grid.best() == 0 ? 0
            : Math.min(5, 1 + (int) Math.floor(4.0 * cell.clear() / Math.max(1, grid.best()))));
        one.put("lost", cell.ideal() - cell.clear());
        cells.add(one);
      }
      if (across == 0) {
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("hour", String.format("%02d:00", hour));
      row.put("cells", cells);
      rows.add(row);
    }
    ArrayList<Map<String, Object>> best = new ArrayList<>();
    for (Heatmap.Cell cell : Heatmap.bestHours(grid, 3)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("when", Heatmap.describe(cell));
      row.put("clear", cell.clear());
      row.put("ideal", cell.ideal());
      best.add(row);
    }
    ArrayList<Map<String, Object>> headings = new ArrayList<>();
    for (DayOfWeek day : DayOfWeek.values()) {
      headings.add(Map.of("day", day.getDisplayName(java.time.format.TextStyle.SHORT,
          java.util.Locale.getDefault())));
    }
    model.put("headings", headings);
    model.put("rows", rows);
    model.put("anyRows", !rows.isEmpty());
    model.put("best", best);
    model.put("anyBest", !best.isEmpty());
    model.put("people", grid.people());
    model.put("said", grid.said());
    model.put("assumedPeople", grid.assumed());
    model.put("linkedPeople", grid.linked());
    model.put("from", grid.from().toString());
    model.put("to", grid.to().toString());
    // the honest health line: a grid built mostly from assumptions is worth less than one built
    // from what people said, and an organiser should be able to see which they are looking at
    model.put("healthy", grid.said() > 0 && grid.said() * 2 >= grid.people());
  }

  private static List<Map<String, Object>> dayOptions() {
    ArrayList<Map<String, Object>> options = new ArrayList<>();
    for (DayOfWeek day : DayOfWeek.values()) {
      options.add(Map.of("value", day.name(),
          "label", day.getDisplayName(java.time.format.TextStyle.FULL,
              java.util.Locale.getDefault())));
    }
    return options;
  }

  private static DayOfWeek dayOf(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return DayOfWeek.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static Long longOf(String raw) {
    try {
      return raw == null ? null : Long.parseLong(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                               WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }
}
