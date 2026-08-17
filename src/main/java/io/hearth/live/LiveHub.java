package io.hearth.live;

import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live channel: one sequence of "something moved" per community, and the two things that only
 * ever live in memory.
 *
 * <b>The sequence is in memory and starts at one on every boot.</b> That is not a shortcut. A
 * cursor into this stream is only ever used to answer "what have I missed since a moment ago", and
 * after a restart the honest answer is "everything, refetch" -- which is exactly what a client that
 * presents an unknown cursor is told. Persisting it would buy the ability to replay across a
 * restart, which nothing wants, at the cost of a write per message.
 *
 * <b>Presence never touches the disk.</b> It is a fact about the last few seconds, it is wrong the
 * moment it is written down, and it would otherwise be among the highest-volume writes in the
 * server. A restart forgets who was online, and within a heartbeat everybody who still is says so
 * again.
 *
 * <b>Everything rides the event bus.</b> A mutation event already names the table and the row,
 * which is everything a "re-fetch that thread" signal needs -- so a write from anywhere, including
 * a model or an importer nobody has written yet, shows up live without that code knowing this
 * exists. Nothing publishes to this hub by hand.
 */
public class LiveHub {
  /** how many signals are kept for clients that blinked; past this they are told to refetch */
  private static final int RING = 512;
  /** somebody is here if we have heard from them within this long */
  public static final long PRESENCE_WINDOW_MILLIS = 75_000L;
  /** presence is re-announced no more often than this, however many beats arrive */
  private static final long PRESENCE_ANNOUNCE_MILLIS = 45_000L;

  private final String domain;
  private final Verbose verbose;
  private final AtomicLong sequence = new AtomicLong();
  private final ArrayList<Signal> ring = new ArrayList<>(RING);
  private final CopyOnWriteArrayList<Waiter> waiters = new CopyOnWriteArrayList<>();
  /** user id -> when we last heard from them */
  private final ConcurrentHashMap<Long, Long> present = new ConcurrentHashMap<>();
  /** user id -> when their presence was last announced, so a beat is not a broadcast */
  private final ConcurrentHashMap<Long, Long> announced = new ConcurrentHashMap<>();

  public LiveHub(String domain, Verbose verbose) {
    this.domain = domain;
    this.verbose = verbose;
  }

  /** anything waiting on the stream; an SSE connection is one of these for its whole life */
  public interface Waiter {
    /** called on the publishing thread, so it must not block */
    void wake(Signal signal);

    /** which member is behind this connection; what presence and the access log are keyed by */
    long userId();
  }

  /**
   * The tables whose changes are worth re-rendering for.
   *
   * Deliberately a short allow-list rather than "everything": a signal for every row written
   * anywhere would wake every browser in the community whenever an admin saved a page.
   */
  private static final Set<String> LIVE_TABLES =
      Set.of(Schema.POSTS, Schema.COMMENTS, Schema.CALENDAR, Schema.RSVPS, Schema.PLACES);

  /** wire this hub to the event bus, once, before the socket opens */
  public void listenTo(EventBus events) {
    events.subscribe(event -> {
      if (!domain.equals(event.domain())) {
        return;
      }
      if (LIVE_TABLES.contains(event.table())) {
        // one signal per change, naming the table and the row. The client decides whether it is
        // looking at something that depends on it and re-fetches the page it is on -- which is why
        // a comment can carry its own id rather than its subject's, and why adding a table here is
        // the whole of making a new page live.
        publish(Signal.Kind.updated, event.table() + ":" + event.key(), null);
      }
    });
  }

  // ---- publishing ----------------------------------------------------------------------------------

  public long publish(Signal.Kind kind, String scope, String meta) {
    long seq = sequence.incrementAndGet();
    Signal signal = new Signal(seq, kind, scope, meta);
    synchronized (ring) {
      ring.add(signal);
      if (ring.size() > RING) {
        ring.subList(0, ring.size() - RING).clear();
      }
    }
    for (Waiter waiter : waiters) {
      try {
        waiter.wake(signal);
      } catch (RuntimeException ex) {
        // a dead connection must never stop the next one being told
        verbose.detail("live: waiter failed on " + domain + ": " + ex.getMessage());
      }
    }
    return seq;
  }

  public long head() {
    return sequence.get();
  }

  /** the oldest signal still in the ring; a cursor below this means "you missed too much" */
  public long floor() {
    synchronized (ring) {
      return ring.isEmpty() ? 0 : ring.get(0).seq() - 1;
    }
  }

  /**
   * What this person has missed.
   *
   * A cursor older than the ring returns the whole ring rather than an error, and the caller learns
   * it fell behind by comparing what it asked for with the `floor` in the reply. Refetching is
   * cheap here precisely because a signal carries nothing.
   */
  public List<Signal> since(long cursor) {
    ArrayList<Signal> found = new ArrayList<>();
    synchronized (ring) {
      for (Signal signal : ring) {
        if (signal.seq() > cursor) {
          found.add(signal);
        }
      }
    }
    return found;
  }

  public void addWaiter(Waiter waiter) {
    waiters.add(waiter);
  }

  public void removeWaiter(Waiter waiter) {
    waiters.remove(waiter);
  }

  public int connections() {
    return waiters.size();
  }

  // ---- presence ------------------------------------------------------------------------------------

  /**
   * Heard from somebody.
   *
   * Called on every live poll and every SSE heartbeat, so it is on a hot path and does nothing but
   * a map write. The broadcast is rate limited separately: a beat every twenty seconds must not be
   * a fan-out every twenty seconds, because the only interesting presence events are the edges.
   */
  public void beat(long userId) {
    long now = System.currentTimeMillis();
    Long before = present.put(userId, now);
    boolean arrived = before == null || now - before > PRESENCE_WINDOW_MILLIS;
    Long lastSaid = announced.get(userId);
    if (arrived || lastSaid == null || now - lastSaid > PRESENCE_ANNOUNCE_MILLIS) {
      announced.put(userId, now);
      publish(Signal.Kind.presence, null, Long.toString(userId));
    }
  }

  /** signing out, or closing the last tab */
  public void gone(long userId) {
    if (present.remove(userId) != null) {
      announced.remove(userId);
      publish(Signal.Kind.presence, null, Long.toString(userId));
    }
  }

  public boolean isOnline(long userId) {
    Long at = present.get(userId);
    return at != null && System.currentTimeMillis() - at <= PRESENCE_WINDOW_MILLIS;
  }

  /** everybody currently here, for the green dots */
  public List<Long> online() {
    long cutoff = System.currentTimeMillis() - PRESENCE_WINDOW_MILLIS;
    ArrayList<Long> found = new ArrayList<>();
    present.forEach((userId, at) -> {
      if (at > cutoff) {
        found.add(userId);
      }
    });
    Collections.sort(found);
    return found;
  }

  /** drop what has aged out; called from the notification pass */
  public void sweep() {
    long now = System.currentTimeMillis();
    present.entrySet().removeIf(entry -> now - entry.getValue() > PRESENCE_WINDOW_MILLIS * 4);
    announced.keySet().removeIf(userId -> !present.containsKey(userId));
  }

  /** what the admin dashboard shows about this hub */
  public Map<String, Object> stats() {
    LinkedHashMap<String, Object> stats = new LinkedHashMap<>();
    stats.put("domain", domain);
    stats.put("sequence", sequence.get());
    stats.put("connections", waiters.size());
    stats.put("online", online().size());
    return stats;
  }
}
