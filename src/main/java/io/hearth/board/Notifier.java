package io.hearth.board;

import io.hearth.auth.Accounts;
import io.hearth.auth.AuthSystem;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.mail.Mailer;
import io.hearth.sms.NoSms;
import io.hearth.sms.Sms;
import io.hearth.vhost.DomainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place anything is sent about the board.
 *
 * Delivery is a background thread rather than part of the click that caused it. A reply in a thread
 * with forty watchers is forty messages, each a signed HTTPS request to Amazon, and doing that
 * inside the POST would make the reply box get slower exactly as a thread got popular -- the same
 * failure the board's caching exists to avoid, arriving through a different door. So "immediate"
 * means the next pass, which is within a minute, and a person cannot tell the difference between a
 * mail that took two seconds and one that took fifty.
 *
 * The queue is a query, not a queue: {@link Inbox#undelivered} is "rows with no notified_at", which
 * is true whenever it is asked. An in-memory queue loses everything on restart and a durable one
 * has to be reconciled with the rows it describes; a watermark column on the row that already
 * exists has neither problem.
 *
 * The watermark is stamped for everything the pass considered, including notifications belonging to
 * somebody who wants no email at all. Leaving those unstamped would grow a queue of rows nothing
 * will ever act on, and every pass would cost more than the last.
 *
 * Digests are not batches of notices. Somebody on a daily summary accumulates unstamped rows until
 * their window comes round, and then gets one message; the stamp goes on before the send, because
 * two copies of Tuesday's summary is worse than missing it -- and nothing is lost either way, since
 * the inbox on the site is the record and this is only the reminder.
 *
 * One pass covers every community on the box, and a database shared by two domains is delivered for
 * once, under the domain that owns it. The alternative is two mails for one reply, addressed from
 * two different communities, which is the same bug that made shared databases take their policy
 * from the owning domain.
 */
public class Notifier {
  private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);
  /** how many notifications one pass will look at; a backlog drains over several passes */
  private static final int BATCH = 500;
  /** the most lines a digest will carry before it says "and more" */
  private static final int DIGEST_LINES = 25;

  private final AuthSystem auth;
  private final Map<String, DomainConfig> domains;
  private final Mailer mailer;
  private final io.hearth.people.Invitations invitations;
  private final io.hearth.push.WebPush push;
  private final AtomicLong pushed = new AtomicLong();
  private final Sms sms;
  private final Verbose verbose;
  private final ScheduledExecutorService worker;
  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong digests = new AtomicLong();
  private final AtomicLong reminders = new AtomicLong();
  /** the calendar's own invitations: a file rather than a message */
  private final io.hearth.calendar.Invitations eventInvitations;
  /** whether a calendar answer could get back here at all; see calendar.Invitations */
  private final boolean inboundMail;
  /**
   * The day the calendar nudges last ran.
   *
   * A reminder is "this event is N days away and you have not answered", which is true for every
   * pass on that day -- and the pass runs every minute. One day-stamp turns that into once.
   */
  private final AtomicLong calendarDay = new AtomicLong(-1);

  private static long today(Timestamp now) {
    return now.getTime() / 86_400_000L;
  }
  private final long periodSeconds;
  private volatile boolean running;

  public Notifier(AuthSystem auth, Map<String, DomainConfig> domains, Mailer mailer,
                  Verbose verbose) {
    this(auth, domains, mailer, NoSms.INSTANCE, verbose, 60, false);
  }

  public Notifier(AuthSystem auth, Map<String, DomainConfig> domains, Mailer mailer, Sms sms,
                  Verbose verbose, long periodSeconds) {
    this(auth, domains, mailer, sms, verbose, periodSeconds, false);
  }

  public Notifier(AuthSystem auth, Map<String, DomainConfig> domains, Mailer mailer, Sms sms,
                  Verbose verbose, long periodSeconds, boolean inboundMail) {
    this.eventInvitations = new io.hearth.calendar.Invitations(mailer);
    this.inboundMail = inboundMail;
    this.auth = auth;
    this.domains = domains;
    this.mailer = mailer;
    this.invitations = new io.hearth.people.Invitations(mailer);
    this.push = new io.hearth.push.WebPush(verbose);
    this.sms = sms;
    this.verbose = verbose;
    this.periodSeconds = periodSeconds;
    this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "notifier");
      thread.setDaemon(true);
      return thread;
    });
  }

  public void start() {
    if (domains.isEmpty()) {
      return;
    }
    running = true;
    worker.scheduleWithFixedDelay(this::safeSweep, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    verbose.say("notifier: delivering every " + periodSeconds + "s"
        + (sms.available() ? "" : " (email only; sms " + sms.describe() + ")"));
  }

  public void shutdown() {
    running = false;
    worker.shutdownNow();
  }

  /**
   * Write the push stamps down, at most every few minutes.
   *
   * On this pass rather than a thread of its own, and on a timer rather than per event: the
   * numbers being a little stale costs nobody anything, and the writes it saves are on the path
   * that fires whenever anything happens on the board.
   */
  private void flushLedgers(long now) throws SQLException {
    for (String domain : domains.keySet()) {
      Accounts accounts = auth.forDomain(domain);
      if (accounts != null && accounts.pushLedger.due(now)) {
        accounts.pushLedger.flush(now);
      }
    }
  }

  /** how much has left the building, for the admin overview */
  public String describe() {
    return sent.get() + " notice(s), " + digests.get() + " digest(s), " + reminders.get()
        + " invitation reminder(s), " + pushed.get() + " push(es)";
  }

  public long sentCount() {
    return sent.get();
  }

  public long digestCount() {
    return digests.get();
  }

  public long reminderCount() {
    return reminders.get();
  }

  /**
   * What else rides this pass.
   *
   * The machine sampler, because a second thread waking every minute to read two files would be a
   * second thread. Set after construction, since the admin screen owns the samples and is built
   * later than this is.
   */
  /**
   * Other work that wants a heartbeat, and it is a list because it was a field.
   *
   * This used to be one Runnable and a setter. The second caller replaced the first silently: the
   * machine sampler was registered, then the geocoding sweep was registered, and from that commit
   * the Machine graph quietly stopped being sampled. Nothing failed, nothing logged, and the only
   * symptom was a screen that was always empty. A setter named `alsoEachPass` is a trap, so it
   * adds.
   */
  private final java.util.List<Runnable> alsoEachPass = new java.util.concurrent.CopyOnWriteArrayList<>();

  public void alsoEachPass(Runnable work) {
    if (work != null) {
      alsoEachPass.add(work);
    }
  }

  private void safeSweep() {
    try {
      flushLedgers(System.currentTimeMillis());
    } catch (Exception ex) {
      LOG.error("push-ledger-flush-failed", ex);
    }
    try {
      // each on its own, so one passenger throwing does not stop the rest
      for (Runnable work : alsoEachPass) {
        try {
          work.run();
        } catch (Exception ex) {
          LOG.error("notify-passenger-failed", ex);
        }
      }
    } catch (Exception ex) {
      LOG.error("notify-passenger-failed", ex);
    }
    try {
      sweep(new Timestamp(System.currentTimeMillis()));
    } catch (Exception ex) {
      // a scheduled task that throws is a scheduled task that never runs again
      LOG.error("notify-sweep-failed", ex);
    }
  }

  /**
   * One pass over every community.
   *
   * `now` is a parameter so a test can move time without moving the clock: a weekly digest is not
   * something anybody should have to wait a week to see work.
   */
  public int sweep(Timestamp now) {
    int delivered = 0;
    Set<String> databasesDone = new HashSet<>();
    for (Map.Entry<String, DomainConfig> entry : domains.entrySet()) {
      DomainConfig config = entry.getValue();
      if (!config.enabled) {
        continue;
      }
      Accounts accounts = auth.forDomain(entry.getKey());
      if (accounts == null || !databasesDone.add(accounts.databaseDomain)) {
        // a database shared by two domains is delivered for once, by whichever owns it
        continue;
      }
      if (!accounts.databaseDomain.equals(entry.getKey())) {
        continue;
      }
      try {
        // the board gate belongs to the board's half only -- a community with the board switched
        // off still sends invitations, and folding the two checks into one silently stopped its
        // reminder sequence
        delivered += deliverFor(entry.getKey(), config, accounts, now);
        // the invitation sequence rides the same pass. It is the same job -- something a person
        // started that finishes itself later -- and a second thread to do it would be a second
        // place to get "did this already go out" wrong.
        int nudged = invitations.remind(config, accounts, now, 100);
        if (nudged > 0) {
          reminders.addAndGet(nudged);
          verbose.detail(() -> "notifier: " + nudged + " invitation reminder(s) on "
              + entry.getKey());
        }
        // ...and so do the calendar's nudges, for the same reason. Whether somebody is due one is
        // decided from the event's own date and their own silence rather than from a stamp, so a
        // pass that runs twice in a day sends nothing twice and a server that was off for one does
        // not send yesterday's.
        if (calendarDay.getAndSet(today(now)) != today(now)) {
          io.hearth.calendar.Invitations.Sent chased =
              eventInvitations.remind(config, accounts, now.toLocalDateTime().toLocalDate(),
                  inboundMail);
          if (chased.anything()) {
            reminders.addAndGet(chased.invited());
            verbose.say("notifier: " + chased.detail() + " for events on " + entry.getKey());
          }
        }
      } catch (SQLException ex) {
        LOG.error("notify-failed domain={}", entry.getKey(), ex);
      }
    }
    return delivered;
  }

  private int deliverFor(String domain, DomainConfig config, Accounts accounts, Timestamp now)
      throws SQLException {
    List<Inbox.Note> queue = accounts.inbox.undelivered(BATCH);
    if (queue.isEmpty()) {
      return 0;
    }

    // group by person first: somebody with six unread replies gets one decision, not six
    LinkedHashMap<Long, List<Inbox.Note>> byUser = new LinkedHashMap<>();
    for (Inbox.Note note : queue) {
      byUser.computeIfAbsent(note.userId(), key -> new ArrayList<>()).add(note);
    }

    int delivered = 0;
    for (Map.Entry<Long, List<Inbox.Note>> entry : byUser.entrySet()) {
      delivered += deliverTo(domain, config, accounts, entry.getKey(), entry.getValue(), now);
    }
    return delivered;
  }

  private int deliverTo(String domain, DomainConfig config, Accounts accounts, long userId,
                        List<Inbox.Note> notes, Timestamp now) throws SQLException {
    UserRecord user = accounts.users.byId(userId);
    NotifyPrefs.Prefs prefs = accounts.notifyPrefs.forUser(userId);
    if (user == null || !prefs.email()) {
      // nobody to send to, or nothing they want sent; stamp so the queue does not grow forever
      accounts.inbox.markNotified(ids(notes));
      return 0;
    }

    ArrayList<Long> handled = new ArrayList<>();
    ArrayList<Inbox.Note> waiting = new ArrayList<>();
    int delivered = 0;
    for (Inbox.Note note : notes) {
      NotifyPrefs.Mode mode = prefs.modeFor(note.kind());
      if (mode == NotifyPrefs.Mode.off) {
        handled.add(note.id());
        continue;
      }
      if (mode == NotifyPrefs.Mode.immediate) {
        if (send(domain, config, accounts, user, note)) {
          delivered++;
        }
        // A push goes to every browser this person is still signed in on, alongside the email
        // rather than instead of it: a notification is a tap away from the thing, and an email is
        // what is still there tomorrow. Failing to push never stops the mail.
        pushTo(domain, config, accounts, user, note);
        handled.add(note.id());
        continue;
      }
      waiting.add(note);
    }

    // whatever is left is for a digest, and only if this person's window has come round
    if (!waiting.isEmpty()) {
      NotifyPrefs.Mode window = windowFor(prefs, waiting);
      if (due(prefs, window, now)) {
        // stamped before the send: two copies of Tuesday's summary is worse than missing it, and
        // the inbox on the site is the record either way
        accounts.notifyPrefs.markDigested(userId, window, now);
        handled.addAll(ids(waiting));
        if (digest(domain, config, accounts, user, window, waiting)) {
          delivered++;
        }
      }
    }

    accounts.inbox.markNotified(handled);
    return delivered;
  }

  /**
   * Which digest a batch belongs in when somebody has one of each.
   *
   * Daily wins: putting a reply that was due today into next Sunday's summary would make the daily
   * setting mean nothing for anybody who also set weekly.
   */
  private NotifyPrefs.Mode windowFor(NotifyPrefs.Prefs prefs, List<Inbox.Note> waiting) {
    for (Inbox.Note note : waiting) {
      if (prefs.modeFor(note.kind()) == NotifyPrefs.Mode.daily) {
        return NotifyPrefs.Mode.daily;
      }
    }
    return NotifyPrefs.Mode.weekly;
  }

  private boolean due(NotifyPrefs.Prefs prefs, NotifyPrefs.Mode window, Timestamp now) {
    Timestamp last = window == NotifyPrefs.Mode.weekly ? prefs.lastWeeklyAt() : prefs.lastDailyAt();
    if (last == null) {
      // never had one; the notification that triggered this is the reason to start
      return true;
    }
    long every = window == NotifyPrefs.Mode.weekly
        ? TimeUnit.DAYS.toMillis(7) : TimeUnit.DAYS.toMillis(1);
    return now.getTime() - last.getTime() >= every;
  }

  /**
   * Ring every browser this person is still signed in on.
   *
   * The payload is a title, a line and a path -- never the contents. A push travels through
   * somebody else's infrastructure, and although it is encrypted, what is on a lock screen is
   * visible to whoever is holding the phone. The job is to bring them back, not to tell them the
   * thing.
   */
  private void pushTo(String domain, DomainConfig config, Accounts accounts, UserRecord user,
                      Inbox.Note note) {
    try {
      for (io.hearth.push.PushSubs.Sub sub : accounts.pushSubs.forUser(user.id())) {
        io.hearth.push.WebPush.Message message = new io.hearth.push.WebPush.Message(
            config.name,
            note.actorName() + (note.kind() == Inbox.Kind.response ? " replied to you" : " replied"),
            note.postId() == null ? config.urls.board : config.urls.board + "/" + note.postId(),
            note.postId() == null ? "hearth" : "thread-" + note.postId(),
            user.id());
        io.hearth.push.WebPush.Outcome outcome =
            push.send(sub, message, "mailto:no-reply@" + domain);
        if (outcome.delivered()) {
          pushed.incrementAndGet();
          accounts.pushSubs.recordSuccess(sub.id());
          // when it went out, so the delay between a phone buzzing and a person arriving is
          // answerable. Buffered in memory: this fires whenever anything happens on the board.
          accounts.pushLedger.sent(user.id(), System.currentTimeMillis());
        } else {
          accounts.pushSubs.recordFailure(sub.id(), outcome.gone(), outcome.detail());
        }
      }
    } catch (SQLException ex) {
      // a push that fails must never stop the email; they are two ways of saying the same thing
      LOG.warn("push-sweep-failed user={}", user.id(), ex);
    }
  }

  private boolean send(String domain, DomainConfig config, Accounts accounts, UserRecord user,
                       Inbox.Note note) {
    Mailer.Envelope envelope = Mailer.Envelope.to(config, accounts, user.email(), null);
    Mailer.Outcome outcome = mailer.sendBoardNotice(envelope, notice(config, note));
    if (!outcome.delivered()) {
      // logged rather than retried: a bounce is not something the next pass will fix, and the
      // notification is still in the inbox on the site where it always was
      verbose.detail(() -> "notifier: " + user.email() + " not reached (" + outcome.detail() + ")");
      return false;
    }
    sent.incrementAndGet();
    return true;
  }

  private boolean digest(String domain, DomainConfig config, Accounts accounts, UserRecord user,
                         NotifyPrefs.Mode window, List<Inbox.Note> notes) {
    ArrayList<Mailer.Notice> items = new ArrayList<>();
    for (Inbox.Note note : notes) {
      if (items.size() >= DIGEST_LINES) {
        items.add(new Mailer.Notice("and " + (notes.size() - DIGEST_LINES) + " more", "",
            null, link(config, null)));
        break;
      }
      items.add(notice(config, note));
    }
    Mailer.Digest digest = new Mailer.Digest(
        window == NotifyPrefs.Mode.weekly ? "this week" : "today", items,
        link(config, null), config.urls.self + "?tab=notifications");
    Mailer.Envelope envelope = Mailer.Envelope.to(config, accounts, user.email(), null);
    Mailer.Outcome outcome = mailer.sendDigest(envelope, digest);
    if (!outcome.delivered()) {
      verbose.detail(() -> "notifier: digest for " + user.email() + " not reached ("
          + outcome.detail() + ")");
      return false;
    }
    digests.incrementAndGet();
    return true;
  }

  private Mailer.Notice notice(DomainConfig config, Inbox.Note note) {
    // the text was written when the thing happened, which is what makes a notification about a
    // since-removed comment still say what it said
    String heading = note.kind() == Inbox.Kind.response ? "replied to you" : "replied";
    return new Mailer.Notice(heading, note.actorName(), note.text(), link(config, note.postId()));
  }

  private String link(DomainConfig config, Long postId) {
    String base = "https://" + config.domain + config.urls.board;
    return postId == null ? base : base + "/" + postId;
  }

  private static List<Long> ids(List<Inbox.Note> notes) {
    ArrayList<Long> ids = new ArrayList<>(notes.size());
    for (Inbox.Note note : notes) {
      ids.add(note.id());
    }
    return ids;
  }

  public boolean running() {
    return running;
  }
}
