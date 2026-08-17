package io.hearth.people;

import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps "how many questions are left for you" correct, off the request path.
 *
 * The reason this is asynchronous is the question set. One person answering is cheap and could be
 * counted inline; an admin adding a question invalidates the count for everybody, and doing that in
 * the request that saved the question would make asking a question get slower as the community
 * grows. So the work moves to a single background thread and the request returns immediately.
 *
 * It is driven entirely by the event bus:
 *
 *   answers/{user}    -> recount that one person
 *   questions/{any}   -> recount everybody
 *
 * Work is coalesced. Ten questions added in a minute produce one sweep, not ten, because a pending
 * full sweep already covers everything a later one would do. That matters because the natural way
 * to build a survey is to sit there adding questions.
 *
 * The counts it writes are a cache in a column: derivable from the answer blobs and the question
 * set, and rebuilt from scratch by {@link #reindexEverybody} if they ever drift.
 */
public class SurveyIndexer {
  private static final Logger LOG = LoggerFactory.getLogger(SurveyIndexer.class);

  private final String domain;
  private final PeopleStore people;
  private final Verbose verbose;
  private final ExecutorService worker;
  /** users with a recount pending; a set, so asking twice costs once */
  private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<>();
  private final AtomicBoolean sweepPending = new AtomicBoolean();
  private final AtomicLong indexed = new AtomicLong();
  private final AtomicLong sweeps = new AtomicLong();
  /** the bubble, so reading it never touches the database */
  private final ConcurrentHashMap<Long, Integer> remaining = new ConcurrentHashMap<>();
  /**
   * How many people have answered each question.
   *
   * The admin listing wants this next to every question, and computing it there would mean walking
   * every answer blob on every page load. The sweep already walks them all, so it counts on the way
   * past -- a cached query kept honest by the same events that drive everything else.
   */
  private final ConcurrentHashMap<Long, Integer> answersPerQuestion = new ConcurrentHashMap<>();
  /**
   * Which questions each person has actually answered.
   *
   * Kept so that re-counting one person can move the per-question tallies by a delta rather than by
   * re-reading every sheet in the community. Without it, an answer event could only update that
   * person's bubble, and the counts on the survey page would sit stale until the next question was
   * asked. At this scale it is a few hundred small sets, which is the cheap answer -- and the sweep
   * rebuilds both structures from the blobs if they ever drift.
   */
  private final ConcurrentHashMap<Long, java.util.Set<Long>> answeredBy = new ConcurrentHashMap<>();
  private volatile boolean running;

  public SurveyIndexer(String domain, PeopleStore people, EventBus events, Verbose verbose) {
    this.domain = domain;
    this.people = people;
    this.verbose = verbose;
    this.worker = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "survey-indexer-" + domain);
      thread.setDaemon(true);
      return thread;
    });
    events.subscribe(this::onMutation);
  }

  public void start() {
    running = true;
    // on boot the question set may have changed while the process was down
    requestSweep();
  }

  public void shutdown() {
    running = false;
    worker.shutdownNow();
  }

  private void onMutation(MutationEvent event) {
    if (!event.domain().equals(domain)) {
      return;
    }
    if (event.touches(Schema.QUESTIONS)) {
      requestSweep();
      return;
    }
    if (event.touches(Schema.ANSWERS)) {
      try {
        requestFor(Long.parseLong(event.key()));
      } catch (NumberFormatException ex) {
        requestSweep();
      }
    }
  }

  /** recount one person, soon */
  public void requestFor(long userId) {
    if (!running) {
      return;
    }
    pending.put(userId, Boolean.TRUE);
    submit(() -> {
      if (pending.remove(userId) != null) {
        indexOne(userId);
      }
    });
  }

  /**
   * Recount everybody, soon.
   *
   * Coalesced: while a sweep is already queued, another request is a no-op, because the queued one
   * will see whatever the later change wrote by the time it runs.
   */
  public void requestSweep() {
    if (!running || !sweepPending.compareAndSet(false, true)) {
      return;
    }
    submit(() -> {
      sweepPending.set(false);
      reindexEverybody();
    });
  }

  private void submit(Runnable task) {
    try {
      worker.submit(() -> {
        try {
          task.run();
        } catch (RuntimeException ex) {
          LOG.error("survey-index-failed", ex);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException ex) {
      // shutting down; a stale count is fixed by the sweep on next boot
    }
  }

  /** count one person against the current question set and write the result back */
  public void indexOne(long userId) {
    try {
      List<Question> questions = people.publishedQuestions();
      AnswerSheet sheet = people.answersOf(userId);
      AnswerSheet counted = sheet.countedAgainst(questions);
      people.recordCounts(userId, counted.answered(), counted.remaining());
      remaining.put(userId, counted.remaining());
      applyDelta(userId, questions, sheet);
      indexed.incrementAndGet();
      verbose.detail(() -> "survey: user " + userId + " has " + counted.remaining() + " left");
    } catch (SQLException ex) {
      LOG.error("survey-index-one-failed", ex);
    }
  }

  /** count everybody; the question set is read once for the whole pass */
  public int reindexEverybody() {
    try {
      List<Question> questions = people.publishedQuestions();
      // the question set moved, so every cached bubble is suspect -- including those of people with
      // no answers row, who cannot be enumerated here and will recompute on their next page
      remaining.clear();
      List<Long> users = people.everybodyWithAnswers();
      ConcurrentHashMap<Long, Integer> perQuestion = new ConcurrentHashMap<>();
      for (Question question : questions) {
        perQuestion.put(question.id(), 0);
      }
      for (long userId : users) {
        AnswerSheet sheet = people.answersOf(userId);
        AnswerSheet counted = sheet.countedAgainst(questions);
        people.recordCounts(userId, counted.answered(), counted.remaining());
        remaining.put(userId, counted.remaining());
        // counted on the way past, since this pass already has the blob open
        java.util.Set<Long> answered = ConcurrentHashMap.newKeySet();
        for (Question question : questions) {
          if (question.accepts(sheet.answerTo(question.id()))) {
            perQuestion.merge(question.id(), 1, Integer::sum);
            answered.add(question.id());
          }
        }
        answeredBy.put(userId, answered);
        indexed.incrementAndGet();
      }
      answeredBy.keySet().retainAll(users);
      answersPerQuestion.clear();
      answersPerQuestion.putAll(perQuestion);
      sweeps.incrementAndGet();
      verbose.say(() -> "survey: re-indexed " + users.size() + " answer sheet(s) against "
          + questions.size() + " question(s) on " + domain);
      return users.size();
    } catch (SQLException ex) {
      LOG.error("survey-sweep-failed", ex);
      return 0;
    }
  }

  /**
   * Move the per-question tallies by what this one person just changed.
   *
   * Answering adds one, clearing an answer takes one away, and re-wording an existing answer moves
   * nothing. Doing it as a delta is what keeps a single answer from costing a read of every sheet
   * in the community.
   */
  private void applyDelta(long userId, List<Question> questions, AnswerSheet sheet) {
    java.util.Set<Long> before = answeredBy.getOrDefault(userId, java.util.Set.of());
    java.util.Set<Long> after = ConcurrentHashMap.newKeySet();
    for (Question question : questions) {
      if (question.accepts(sheet.answerTo(question.id()))) {
        after.add(question.id());
      }
    }
    for (long id : after) {
      if (!before.contains(id)) {
        answersPerQuestion.merge(id, 1, Integer::sum);
      }
    }
    for (long id : before) {
      if (!after.contains(id)) {
        answersPerQuestion.merge(id, -1, (a, b) -> Math.max(0, a + b));
      }
    }
    answeredBy.put(userId, after);
  }

  /**
   * The notification bubble.
   *
   * Read from memory when the indexer has seen this person, and from the database otherwise -- which
   * happens exactly once per person per process, on their first page after a restart.
   */
  public int remainingFor(long userId) {
    Integer cached = remaining.get(userId);
    if (cached != null) {
      return cached;
    }
    try {
      // Counted rather than read. Somebody who has never answered anything has no answers row at
      // all, so the stored count does not exist -- and they are exactly the person the bubble is
      // for. Computing it here costs one query per person per process.
      int left = people.answersOf(userId).countedAgainst(people.publishedQuestions()).remaining();
      remaining.put(userId, left);
      return left;
    } catch (SQLException ex) {
      LOG.error("survey-bubble-failed", ex);
      return 0;
    }
  }

  /** drop a cached bubble, for the path that just wrote an answer and wants the truth now */
  public void forget(long userId) {
    remaining.remove(userId);
  }

  /** block until the queue drains; tests use it, nothing on the request path does */
  public boolean settle(long millis) {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      if (pending.isEmpty() && !sweepPending.get()) {
        // one more hop through the worker to be sure the last task finished, not just dequeued
        try {
          worker.submit(() -> {
          }).get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
          return true;
        } catch (Exception ex) {
          return false;
        }
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** how many people have answered each question; empty until the first sweep */
  public java.util.Map<Long, Integer> answersPerQuestion() {
    return java.util.Map.copyOf(answersPerQuestion);
  }

  public int answersFor(long questionId) {
    return answersPerQuestion.getOrDefault(questionId, 0);
  }

  public long indexedCount() {
    return indexed.get();
  }

  public long sweepCount() {
    return sweeps.get();
  }
}
