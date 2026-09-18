package io.hearth.content;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How often each rewrite has fired, counted in memory and written out on a timer.
 *
 * <b>The number is what says whether a redirect is still earning its place.</b> One nobody has
 * followed in a year is one somebody can delete; one that fires every day is holding a link on
 * somebody else's site that this server cannot see and will never be told about. Without it the
 * list only grows, because nothing about a row says whether it still matters.
 *
 * <b>Counted in memory because a redirect is the fastest thing this server does.</b> An UPDATE on
 * the way past would make the cheapest response the one that writes to disk, and it would do it on
 * exactly the addresses a crawler hammers. Buffering is the same trade `PushLedger` makes for the
 * same reason: losing a few counts in a restart costs nothing, and a count is not a fact anybody
 * acts on immediately.
 *
 * Bounded, because the keys come from rows rather than from a request -- but a site at the ceiling
 * with every rewrite firing is still a map worth having a number on.
 */
public class RewriteHits {
  /** how many rewrites are counted at once; past this the oldest counts are simply not held */
  private static final int MAX_TRACKED = 4000;

  private final ConcurrentHashMap<Long, AtomicLong> counts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Long> lastSeen = new ConcurrentHashMap<>();

  /** called on the request path, and it does one map lookup and one increment */
  public void fired(long id, long atMillis) {
    if (counts.size() >= MAX_TRACKED && !counts.containsKey(id)) {
      return;
    }
    counts.computeIfAbsent(id, key -> new AtomicLong()).incrementAndGet();
    lastSeen.put(id, atMillis);
  }

  /**
   * Take everything counted so far, leaving the buffer empty.
   *
   * Drained rather than read, so a flush that succeeds cannot double-count and a flush that throws
   * loses only what it was holding -- which is the right way round for a number nobody is deciding
   * anything on this minute.
   */
  public Map<Long, long[]> drain() {
    HashMap<Long, long[]> out = new HashMap<>();
    for (Long id : counts.keySet().toArray(new Long[0])) {
      AtomicLong counter = counts.remove(id);
      Long when = lastSeen.remove(id);
      if (counter == null) {
        continue;
      }
      long times = counter.get();
      if (times > 0) {
        out.put(id, new long[]{times, when == null ? System.currentTimeMillis() : when});
      }
    }
    return out;
  }

  public boolean any() {
    return !counts.isEmpty();
  }

  /** write what is held into the table, and say nothing if there is nothing */
  public void flush(Rewrites rewrites) throws java.sql.SQLException {
    for (Map.Entry<Long, long[]> entry : drain().entrySet()) {
      rewrites.used(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
    }
  }
}
