package io.hearth.attach;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The recently-served bytes, held in memory so a spike is not a disk storm.
 *
 * <b>What this is for is one photograph and two hundred people.</b> Somebody posts a picture of
 * Saturday, a notification goes out, and forty browsers ask for the same file inside a minute --
 * which without this is forty reads of the same blob off the same disk, interleaved with everything
 * else the server is doing. With it, it is one read.
 *
 * <b>Bounded by bytes, not by entries.</b> A cap of "500 things" is a cap that means four megabytes
 * on a day of thumbnails and four gigabytes on a day of video. What an operator has is a memory
 * budget, so that is what the setting is, and one blob larger than a quarter of the budget is never
 * cached at all -- admitting it would evict everything else to hold one file that is probably being
 * streamed once.
 *
 * <b>Most recently used wins.</b> A `LinkedHashMap` in access order, evicting from the cold end
 * when the budget is exceeded: the entries that survive are the ones being asked for, which during
 * a spike is exactly the handful everybody is looking at. Nothing here is clever, and the reason is
 * that a cache whose behaviour is hard to predict is a cache nobody can reason about when a page is
 * slow.
 */
public class BlobCache {
  /** the largest share of the budget one blob may take */
  private static final int BIGGEST_SHARE = 4;

  private final long budget;
  private final LinkedHashMap<String, byte[]> entries;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private long held;

  public BlobCache(long budgetBytes) {
    this.budget = Math.max(0, budgetBytes);
    // access order, so reading an entry moves it to the warm end
    this.entries = new LinkedHashMap<>(64, 0.75f, true);
  }

  public byte[] get(long id, String extension) {
    if (budget == 0) {
      misses.incrementAndGet();
      return null;
    }
    synchronized (entries) {
      byte[] found = entries.get(key(id, extension));
      if (found == null) {
        misses.incrementAndGet();
        return null;
      }
      hits.incrementAndGet();
      return found;
    }
  }

  public void put(long id, String extension, byte[] bytes) {
    if (budget == 0 || bytes == null || bytes.length > budget / BIGGEST_SHARE) {
      return;
    }
    synchronized (entries) {
      byte[] had = entries.put(key(id, extension), bytes);
      held += bytes.length - (had == null ? 0 : had.length);
      evictWhileOver();
    }
  }

  /** drop one; called when an attachment is deleted or replaced */
  public void invalidate(long id, String extension) {
    synchronized (entries) {
      byte[] gone = entries.remove(key(id, extension));
      if (gone != null) {
        held -= gone.length;
      }
    }
  }

  public void clear() {
    synchronized (entries) {
      entries.clear();
      held = 0;
    }
  }

  private void evictWhileOver() {
    java.util.Iterator<Map.Entry<String, byte[]>> cold = entries.entrySet().iterator();
    while (held > budget && cold.hasNext()) {
      Map.Entry<String, byte[]> entry = cold.next();
      held -= entry.getValue().length;
      cold.remove();
    }
  }

  private static String key(long id, String extension) {
    return id + "." + Kinds.clean(extension);
  }

  /** what the settings and caching screens show */
  public record Stats(int entries, long heldBytes, long budgetBytes, long hits, long misses) {
    public int hitRate() {
      long total = hits + misses;
      return total == 0 ? 0 : (int) Math.round(100.0 * hits / total);
    }
  }

  public Stats stats() {
    synchronized (entries) {
      return new Stats(entries.size(), held, budget, hits.get(), misses.get());
    }
  }
}
