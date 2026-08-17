package io.hearth.events;

import io.hearth.common.Verbose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-process bus: a ring buffer you can look at, and a list of listeners.
 *
 * Listeners are notified on the calling thread. That is the right trade while everything is one
 * process -- a cache invalidation is a map removal, and doing it inline means the next read after a
 * write is already correct, with no window where a stale entry can be served. It also means a
 * listener that blocks blocks a write, which is why {@link EventListener} says not to.
 *
 * The ring buffer is fixed size and overwrites. It is a debugging aid, not a log: losing the oldest
 * event when the thousand-and-first arrives is the intended behaviour, and nothing may depend on an
 * event still being in it.
 */
public class LocalEventBus implements EventBus {
  private static final Logger LOG = LoggerFactory.getLogger(LocalEventBus.class);
  public static final int DEFAULT_CAPACITY = 1000;

  private final MutationEvent[] ring;
  private final CopyOnWriteArrayList<EventListener> listeners = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong();
  private final Verbose verbose;
  private int writeAt;

  public LocalEventBus(Verbose verbose) {
    this(verbose, DEFAULT_CAPACITY);
  }

  public LocalEventBus(Verbose verbose, int capacity) {
    this.ring = new MutationEvent[Math.max(1, capacity)];
    this.verbose = verbose;
    this.writeAt = 0;
  }

  @Override
  public MutationEvent emit(String domain, String table, String key, MutationEvent.Kind kind, Long actor) {
    MutationEvent event = new MutationEvent(sequence.incrementAndGet(), System.currentTimeMillis(),
        domain, table, key, kind, actor);
    emit(event);
    return event;
  }

  @Override
  public void emit(MutationEvent event) {
    record(event);
    verbose.detail(() -> "event " + event);
    for (EventListener listener : listeners) {
      try {
        listener.onMutation(event);
      } catch (RuntimeException ex) {
        // a broken listener must not break the write that triggered it
        LOG.error("event-listener-failed", ex);
      }
    }
  }

  private synchronized void record(MutationEvent event) {
    ring[writeAt] = event;
    writeAt = (writeAt + 1) % ring.length;
  }

  @Override
  public void subscribe(EventListener listener) {
    listeners.add(listener);
  }

  public int listenerCount() {
    return listeners.size();
  }

  @Override
  public synchronized List<MutationEvent> recent(int limit) {
    ArrayList<MutationEvent> out = new ArrayList<>(Math.min(limit, ring.length));
    for (int k = 1; k <= ring.length && out.size() < limit; k++) {
      MutationEvent event = ring[(writeAt - k + ring.length) % ring.length];
      if (event == null) {
        break;
      }
      out.add(event);
    }
    return out;
  }

  @Override
  public List<MutationEvent> recent() {
    return recent(ring.length);
  }

  @Override
  public long emitted() {
    return sequence.get();
  }

  @Override
  public int capacity() {
    return ring.length;
  }
}
