package io.hearth.availability;

import io.hearth.auth.Accounts;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.vhost.DomainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The grid, kept current: pulled nightly, rebuilt on every change.
 *
 * <b>Two clocks, because the two halves cost different things.</b> Reading everybody's calendar is
 * a request to somebody else's server per link, so it happens once a day at a quiet hour and every
 * page after that reads the cache. Adding up what is already stored is a few hundred rows of
 * arithmetic, so it happens whenever anything changes -- which is what makes the screen feel like
 * it belongs to the person who has just typed their evenings into it.
 *
 * <b>The pull is driven by the clock, not by a stamp.</b> "Has today's pass run" is asked of a date
 * rather than of a row, so a server that was off overnight does yesterday's work when it comes back
 * and a server restarted four times in an evening still fetches once. Same rule as the calendar
 * nudges, for the same reason.
 *
 * <b>Nothing here ever runs on a request path.</b> One thread for the whole community; a page that
 * asks for the grid gets whatever was last built, and the first build happens at boot before the
 * socket opens has anything to serve.
 */
public class AvailabilityIndexer {
  private static final Logger LOG = LoggerFactory.getLogger(AvailabilityIndexer.class);
  /** how long the thread sleeps between looks at the clock */
  private static final long TICK_SECONDS = 300;
  /** the ceiling on everything this reads; the scale target is a design input */
  private static final int CEILING = 5000;

  private final String domain;
  private final DomainConfig config;
  private final Accounts accounts;
  private final CalendarFetch.Fetcher fetcher;
  private final Verbose verbose;
  private final AtomicReference<Heatmap.Grid> grid = new AtomicReference<>();
  private final AtomicReference<LocalDate> pulled = new AtomicReference<>();
  private final AtomicBoolean dirty = new AtomicBoolean(true);
  private final AtomicLong fetches = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private volatile Thread thread;
  private volatile boolean running;

  public AvailabilityIndexer(DomainConfig config, Accounts accounts,
                             CalendarFetch.Fetcher fetcher, EventBus events, Verbose verbose) {
    this.domain = config.databaseDomain();
    this.config = config;
    this.accounts = accounts;
    this.fetcher = fetcher;
    this.verbose = verbose;
    events.subscribe(this::onMutation);
  }

  /**
   * Anything that could move a cell marks the grid stale.
   *
   * Deliberately broad and deliberately cheap: approving somebody changes who is counted, and a
   * window or a link changes what they contribute. Marking is a flag; the rebuild happens on the
   * thread, so a write is never slowed down by arithmetic somebody else will read later.
   */
  private void onMutation(MutationEvent event) {
    if (!event.domain().equals(domain)) {
      return;
    }
    if (event.touches(Schema.AVAILABILITY) || event.touches(Schema.CALENDAR_LINKS)
        || event.touches(Schema.CALENDAR_CACHE) || event.touches(Schema.EMAILS)) {
      dirty.set(true);
    }
  }

  public void start() {
    if (!config.has(io.hearth.vhost.Surface.availability)) {
      return;
    }
    running = true;
    // built once before anything can ask for it, so the first person to open the screen is not the
    // person who pays for the first build
    rebuild();
    thread = new Thread(this::loop, "availability-" + domain);
    thread.setDaemon(true);
    thread.start();
  }

  public void shutdown() {
    running = false;
    Thread current = thread;
    if (current != null) {
      current.interrupt();
    }
  }

  private void loop() {
    while (running) {
      try {
        Thread.sleep(TICK_SECONDS * 1000L);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      if (!running) {
        return;
      }
      try {
        pass();
      } catch (Exception ex) {
        // a pass that fails is a grid that is a day old, never a server that stops
        LOG.error("availability-pass-failed domain={}", domain, ex);
      }
    }
  }

  /** one look at the clock: is today's pull owed, and is the grid stale? */
  public void pass() {
    if (isPullDue(ZonedDateTime.now(zone()))) {
      pullEverything();
    }
    if (dirty.getAndSet(false)) {
      rebuild();
    }
  }

  /**
   * Is the nightly pull owed?
   *
   * True when the hour has arrived and today's has not run. Asking the date rather than a stamp is
   * what makes a server that was off overnight do the work when it comes back, and what stops four
   * restarts in an evening from being four passes.
   */
  boolean isPullDue(ZonedDateTime now) {
    LocalDate last = pulled.get();
    if (last != null && !last.isBefore(now.toLocalDate())) {
      return false;
    }
    return !now.toLocalTime().isBefore(LocalTime.of(config.availability.refreshHour, 0));
  }

  /**
   * Read every calendar, once.
   *
   * The refusals matter more than the successes. A link that cannot be fetched is written down with
   * the reason and shown to the person whose calendar it is -- a link that silently stopped working
   * is a member this grid quietly starts lying about, which is worse than one that says "this has
   * been failing since Tuesday".
   */
  public int pullEverything() {
    pulled.set(LocalDate.now(zone()));
    int done = 0;
    try {
      LocalDate today = LocalDate.now(zone());
      LocalDate horizon = today.plusDays(config.availability.horizonDays);
      for (Availability.Link link : accounts.availability.allLinks(CEILING)) {
        if (!running && done > 0) {
          break;
        }
        done += pull(link, today, horizon) ? 1 : 0;
      }
      if (done > 0) {
        dirty.set(true);
      }
      verbose.say("availability: read " + done + " calendar(s) for " + domain);
    } catch (SQLException ex) {
      LOG.error("availability-pull-failed domain={}", domain, ex);
    }
    return done;
  }

  private boolean pull(Availability.Link link, LocalDate today, LocalDate horizon) {
    String url = CalendarFetch.clean(link.url());
    String hash = Availability.hashOf(link.url());
    long expires = expiryAfter(today);
    CalendarFetch.Fetched fetched = fetcher.get(url, config.availability.fetchTimeoutSeconds);
    fetches.incrementAndGet();
    try {
      if (!fetched.ok()) {
        failures.incrementAndGet();
        // the last good answer is kept: one bad night should not make somebody look free for a
        // fortnight they are away
        Availability.Cached had = accounts.availability.cachedFor(link.userId(), hash);
        accounts.availability.remember(link.userId(), hash, "error", fetched.problem(),
            had == null ? "[]" : had.busy(), had == null ? 0 : had.blocks(), expires);
        verbose.detail(() -> "availability: " + link.shortUrl() + " -- " + fetched.problem());
        return false;
      }
      List<BusyCalendar.Block> blocks =
          BusyCalendar.read(fetched.body(), today, horizon, zone());
      ArrayList<long[]> pairs = new ArrayList<>();
      for (BusyCalendar.Block block : blocks) {
        pairs.add(new long[]{block.from(), block.to()});
      }
      accounts.availability.remember(link.userId(), hash, "ok", "",
          Availability.blocksOut(pairs), pairs.size(), expires);
      verbose.detail(() -> "availability: " + link.shortUrl() + " -- " + pairs.size() + " block(s)");
      return true;
    } catch (SQLException ex) {
      LOG.error("availability-cache-failed", ex);
      return false;
    }
  }

  /** the cache dies at the next refresh hour, so "expired" and "a day old" are the same thing */
  long expiryAfter(LocalDate today) {
    ZonedDateTime next = today.plusDays(1)
        .atTime(config.availability.refreshHour, 0).atZone(zone());
    return next.toInstant().toEpochMilli();
  }

  /** add up what is stored; no network, no request path */
  public void rebuild() {
    try {
      LocalDate today = LocalDate.now(zone());
      ArrayList<Heatmap.Person> people = new ArrayList<>();
      for (io.hearth.auth.UserRecord user : accounts.users.recent(CEILING)) {
        if (user.disabled() || !accounts.access.isApproved(user)) {
          continue;
        }
        ArrayList<BusyCalendar.Block> busy = new ArrayList<>();
        for (Availability.Cached cached : accounts.availability.cachedFor(user.id())) {
          for (long[] pair : Availability.blocksIn(cached.busy())) {
            busy.add(new BusyCalendar.Block(pair[0], pair[1]));
          }
        }
        people.add(Heatmap.personOf(user, accounts.availability.windowsFor(user.id()), busy));
      }
      grid.set(Heatmap.of(people, today, config.availability.horizonDays, zone()));
    } catch (SQLException ex) {
      LOG.error("availability-rebuild-failed domain={}", domain, ex);
    }
  }

  /**
   * The grid as it stands.
   *
   * Never null once the server has started, and never computed here: a page asking for this gets
   * whatever the thread last built, which is the difference between a screen that opens instantly
   * and one that adds up five hundred people while somebody waits.
   */
  public Heatmap.Grid grid() {
    Heatmap.Grid built = grid.get();
    if (built == null) {
      rebuild();
      built = grid.get();
    }
    return built == null
        ? Heatmap.of(List.of(), LocalDate.now(zone()), config.availability.horizonDays, zone())
        : built;
  }

  /** for a test, and for the admin screen's "read them now" button */
  public void settle() {
    if (dirty.getAndSet(false)) {
      rebuild();
    }
  }

  public long fetches() {
    return fetches.get();
  }

  public long failures() {
    return failures.get();
  }

  public LocalDate lastPull() {
    return pulled.get();
  }

  /**
   * The community's own clock, not the machine's.
   *
   * Everything here is timezone-shaped: an hour on the grid, an all-day entry read out of somebody's
   * calendar, and the day the nightly pull believes it is. A supper club in Bristol running on a
   * rented box in Virginia would otherwise have its evenings five hours out and nothing to point at.
   */
  private ZoneId zone() {
    return config.zone;
  }
}
