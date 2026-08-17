package io.hearth.async;

import io.hearth.common.Verbose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Work that somebody else's server has to answer, done slowly and out of the way.
 *
 * <b>Why a queue rather than a call.</b> A geocode is one request to a service whose acceptable use
 * policy is one request a second, and the load this server produces is not smooth: an admin pastes
 * in forty addresses on a Sunday afternoon, or fifty members fill their profiles in the week after
 * an invitation goes out. Doing it inline meant either breaking the policy during a spike or making
 * somebody's save wait a minute and a half for a stranger's turn. Neither is a real option, so the
 * ask is written down and answered later -- which is also the only shape in which a rate limit can
 * be honoured across a whole box rather than per request.
 *
 * <b>Deliberately slower than allowed.</b> One every {@value #GAP_MILLIS} milliseconds rather than
 * the permitted one a second. Nobody minds a server that asks slowly, and the queue is what buys
 * that: with somewhere to wait, the difference between 1.0 and 1.5 seconds is a few minutes on a
 * batch nobody is watching, against a real risk of being blocked -- which is not throttling, it is
 * a block, and it is found out days later when nothing has geocoded.
 *
 * <b>A thousand, and then it says no.</b> An unbounded queue is a memory leak with a schedule: at
 * this pace a thousand items is already the better part of half an hour, and anything that produced
 * more than that in one go is a mistake rather than a workload. Refusing is recorded and visible on
 * the Async screen, so the answer to "why has that address not resolved" is on a page rather than in
 * somebody's head.
 *
 * <b>Backoff is for the service, not for us.</b> A failure means somebody else is having a bad
 * afternoon, and the wrong response is to keep asking at full speed -- which is how a temporary
 * problem becomes a block. Ten seconds, then double, to a ceiling; reset the moment anything
 * succeeds. A job that has failed {@value #ATTEMPTS} times is given up on, because at that point it
 * is the job rather than the service.
 *
 * <b>An empty answer is not a failure.</b> "The service has never heard of that address" is a
 * complete, correct, successful answer, and treating it as an error would put the whole queue into
 * backoff because somebody typed a street name wrong.
 */
public class AsyncQueue implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AsyncQueue.class);

  /** how many asks may be waiting before the next one is refused */
  public static final int CAPACITY = 1000;
  /** the gap between two asks, across everything this box does */
  public static final long GAP_MILLIS = 1500;
  /** what a first failure costs */
  public static final long BACKOFF_START_MILLIS = 10_000;
  /** and how bad it is allowed to get */
  public static final long BACKOFF_CEILING_MILLIS = 10 * 60_000;
  /** after this many failures it is the job that is wrong, not the service */
  public static final int ATTEMPTS = 5;
  /** how many finished items the screen can show */
  public static final int REMEMBERED = 200;

  /**
   * One piece of work.
   *
   * @return true when something came back, false for "asked, and there was no answer" -- which is a
   *     success as far as the service is concerned and must never start a backoff. Throwing is the
   *     way to say the request itself did not work.
   */
  public interface Job {
    boolean run() throws Exception;
  }

  /** how a piece of work ended */
  public enum Outcome {
    /** something came back and was written down */
    answered,
    /** the service answered and had nothing; nothing is wrong */
    nothing,
    /** the request failed and it will be tried again */
    failed,
    /** it failed too many times and has been dropped */
    abandoned,
    /** the queue was full when it was asked for */
    refused
  }

  /** one piece of work, waiting */
  private record Waiting(long id, String domain, String label, long queuedAt, int attempts,
                         Job job) {
  }

  /** one piece of work, finished, for the screen */
  public record Finished(long id, String domain, String label, Outcome outcome, long finishedAt,
                         long millis, int attempts, String detail) {
  }

  private final Verbose verbose;
  private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();
  private final ArrayDeque<Finished> finished = new ArrayDeque<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final Object lock = new Object();

  private volatile Thread worker;
  private volatile boolean running;
  private volatile long lastRunAt;
  private volatile long backoffUntil;
  private volatile long backoff = BACKOFF_START_MILLIS;
  private final long backoffStart;
  private volatile String lastProblem;
  private volatile String inFlight;

  private long accepted;
  private long refused;
  private long answered;
  private long nothing;
  private long failures;
  private long abandoned;

  private final long gap;

  public AsyncQueue(Verbose verbose) {
    this(verbose, GAP_MILLIS);
  }

  /**
   * The same, at a pace of the caller's choosing.
   *
   * For tests, and for nothing else. The pace is a promise this server makes to somebody else's
   * service, so there is no configuration key for it -- but a test is not talking to Nominatim, and
   * a suite that waited a second and a half per queued job would be a suite nobody runs.
   */
  public AsyncQueue(Verbose verbose, long gapMillis) {
    this(verbose, gapMillis, BACKOFF_START_MILLIS);
  }

  /** and with a shorter first backoff, so that giving up is reachable inside a test's patience */
  public AsyncQueue(Verbose verbose, long gapMillis, long backoffStartMillis) {
    this.verbose = verbose == null ? Verbose.OFF : verbose;
    this.gap = Math.max(0, gapMillis);
    this.backoffStart = Math.max(1, backoffStartMillis);
    this.backoff = this.backoffStart;
  }

  /**
   * Start the one thread.
   *
   * One for the whole box, like the notifier's, and for the same reason: the pacing is a property of
   * this server as somebody else's client, so two threads would be two clients ignoring one policy.
   */
  public void start() {
    if (running) {
      return;
    }
    running = true;
    worker = new Thread(this::loop, "hearth-async");
    worker.setDaemon(true);
    worker.start();
    verbose.say("async: one worker, one every " + gap + "ms, up to " + CAPACITY + " waiting");
  }

  /**
   * Ask for something to be done later.
   *
   * @return false when the queue is full, which the caller may want to say out loud. It is never an
   *     exception: a save that failed because a geocoding queue was busy would be absurd.
   */
  public boolean submit(String domain, String label, Job job) {
    synchronized (lock) {
      if (waiting.size() >= CAPACITY) {
        refused++;
        record(new Finished(nextId.getAndIncrement(), domain, label, Outcome.refused,
            System.currentTimeMillis(), 0, 0,
            "the queue was full (" + CAPACITY + " waiting); ask again later"));
        lock.notifyAll();
        return false;
      }
      accepted++;
      waiting.add(new Waiting(nextId.getAndIncrement(), domain, label,
          System.currentTimeMillis(), 0, job));
      lock.notifyAll();
      return true;
    }
  }

  private void loop() {
    while (running) {
      synchronized (lock) {
        while (running && waiting.isEmpty()) {
          try {
            lock.wait(5000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        if (!running) {
          return;
        }
      }
      // Wait first, take second.
      //
      // The other way round means the worker holds a job for the whole of a ten-minute backoff and
      // the screen says nothing is waiting -- which is the one moment an operator is looking at
      // that number and the one moment it would be a lie.
      if (!pace()) {
        return;
      }
      Waiting next;
      synchronized (lock) {
        next = waiting.poll();
      }
      if (next != null) {
        run(next);
      }
    }
  }

  /**
   * Wait for whichever is longer: the gap between two asks, or the rest of a backoff.
   *
   * @return false if the wait was interrupted, which means shutdown.
   */
  private boolean pace() {
    while (true) {
      long now = System.currentTimeMillis();
      long until = Math.max(lastRunAt + gap, backoffUntil);
      long wait = until - now;
      if (wait <= 0) {
        return true;
      }
      try {
        Thread.sleep(Math.min(wait, 1000));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
      if (!running) {
        return false;
      }
    }
  }

  private void run(Waiting job) {
    long started = System.currentTimeMillis();
    lastRunAt = started;
    inFlight = job.label();
    try {
      boolean got = job.job().run();
      long took = System.currentTimeMillis() - started;
      synchronized (lock) {
        if (got) {
          answered++;
        } else {
          nothing++;
        }
        record(new Finished(job.id(), job.domain(), job.label(),
            got ? Outcome.answered : Outcome.nothing, System.currentTimeMillis(), took,
            job.attempts() + 1, got ? "" : "no answer for that"));
      }
      // an answer of any kind means the service is well, including an answer of "never heard of it"
      backoff = backoffStart;
      backoffUntil = 0;
      lastProblem = null;
    } catch (Exception ex) {
      long took = System.currentTimeMillis() - started;
      String detail = ex.getClass().getSimpleName()
          + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      lastProblem = detail;
      int attempts = job.attempts() + 1;
      boolean again = attempts < ATTEMPTS;
      long wait = backoff;
      backoffUntil = System.currentTimeMillis() + wait;
      backoff = Math.min(backoff * 2, BACKOFF_CEILING_MILLIS);
      synchronized (lock) {
        failures++;
        if (!again) {
          abandoned++;
        }
        record(new Finished(job.id(), job.domain(), job.label(),
            again ? Outcome.failed : Outcome.abandoned, System.currentTimeMillis(), took, attempts,
            detail + (again ? "; trying again in " + (wait / 1000) + "s"
                : "; given up after " + ATTEMPTS + " tries")));
        if (again) {
          // the tail rather than the front: when a service is down everything fails, and a job that
          // keeps its place at the head is one bad address holding up nine hundred good ones
          waiting.add(new Waiting(job.id(), job.domain(), job.label(), job.queuedAt(), attempts,
              job.job()));
        }
      }
      LOG.warn("async-job-failed label={} attempt={}", job.label(), attempts, ex);
      verbose.detail(() -> "async: " + job.label() + " failed, waiting " + (wait / 1000) + "s");
    } finally {
      inFlight = null;
    }
  }

  /** caller holds the lock */
  private void record(Finished item) {
    finished.addFirst(item);
    while (finished.size() > REMEMBERED) {
      finished.removeLast();
    }
  }

  // ---- what the screen asks ---------------------------------------------------------------------

  public int depth() {
    synchronized (lock) {
      return waiting.size();
    }
  }

  /** how much room is left before an ask is refused */
  public int room() {
    return CAPACITY - depth();
  }

  public boolean isRunning() {
    return running;
  }

  /** what is being asked right now, or null between jobs */
  public String inFlight() {
    return inFlight;
  }

  /** milliseconds until the next ask may go out, or 0 */
  public long waitingFor() {
    long now = System.currentTimeMillis();
    return Math.max(0, Math.max(lastRunAt + gap, backoffUntil) - now);
  }

  /** the pace this one is running at, which is the constant everywhere but a test */
  public long gap() {
    return gap;
  }

  /**
   * Wait until nothing is waiting and nothing is being asked.
   *
   * For tests. Nothing on a request path may ever wait on this queue -- the whole reason it exists
   * is that somebody else's server decides how long it takes.
   */
  public boolean settle(long millis) {
    long until = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < until) {
      if (depth() == 0 && inFlight == null && waitingFor() == 0) {
        return true;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return depth() == 0 && inFlight == null;
  }

  /** is it in backoff, and for how much longer */
  public long backoffLeft() {
    return Math.max(0, backoffUntil - System.currentTimeMillis());
  }

  /** what the next failure would cost */
  public long backoffNext() {
    return backoff;
  }

  public String lastProblem() {
    return lastProblem;
  }

  public Counts counts() {
    synchronized (lock) {
      return new Counts(accepted, refused, answered, nothing, failures, abandoned);
    }
  }

  public record Counts(long accepted, long refused, long answered, long nothing, long failed,
                       long abandoned) {
  }

  /**
   * The last few, for one community.
   *
   * Filtered by domain because the queue is one for the whole box and an admin screen belongs to one
   * community: showing another community's addresses on it would be a small but real leak between
   * two groups who have nothing to do with each other.
   */
  public List<Finished> recentFor(String domain) {
    ArrayList<Finished> out = new ArrayList<>();
    synchronized (lock) {
      for (Finished item : finished) {
        if (domain == null || domain.equals(item.domain())) {
          out.add(item);
        }
      }
    }
    return out;
  }

  /** drop everything waiting; for an operator who queued a mistake */
  public int clear(String domain) {
    synchronized (lock) {
      int before = waiting.size();
      waiting.removeIf(job -> domain == null || domain.equals(job.domain()));
      return before - waiting.size();
    }
  }

  @Override
  public void close() {
    running = false;
    synchronized (lock) {
      lock.notifyAll();
    }
    Thread thread = worker;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
