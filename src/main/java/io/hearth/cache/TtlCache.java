package io.hearth.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A small cache with a time to live, a ceiling, and precise invalidation.
 *
 * Deliberately not a library. What is needed here is: read fast under concurrency, expire on a
 * clock, drop the coldest when full, and -- the part a general cache does not do -- remove exactly
 * the entries a mutation event says are stale. That last one is why {@link #invalidateIf} exists and
 * why this is forty lines rather than a dependency.
 *
 * Eviction is lazy plus a sweep. A read past the TTL removes the entry rather than returning it, so
 * a stale value can never be served even if the sweep has not run.
 */
public class TtlCache<K, V> {
  private final String name;
  private final CachePolicy policy;
  private final ConcurrentHashMap<K, Entry<V>> entries;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong invalidations = new AtomicLong();

  public TtlCache(String name, CachePolicy policy) {
    this.name = name;
    this.policy = policy;
    this.entries = new ConcurrentHashMap<>(Math.min(1024, Math.max(16, policy.maxEntries())));
  }

  public String name() {
    return name;
  }

  public CachePolicy policy() {
    return policy;
  }

  /** the cached value, or null; an expired entry is removed rather than returned */
  public V get(K key) {
    if (!policy.enabled()) {
      misses.incrementAndGet();
      return null;
    }
    Entry<V> entry = entries.get(key);
    if (entry == null) {
      misses.incrementAndGet();
      return null;
    }
    long now = System.currentTimeMillis();
    if (entry.isExpired(now, policy.ttlMillis())) {
      entries.remove(key, entry);
      misses.incrementAndGet();
      return null;
    }
    entry.lastRead = now;
    hits.incrementAndGet();
    return entry.value;
  }

  public void put(K key, V value) {
    if (!policy.enabled() || value == null) {
      return;
    }
    long now = System.currentTimeMillis();
    entries.put(key, new Entry<>(value, now));
    if (entries.size() > policy.maxEntries()) {
      trim();
    }
  }

  /** exactly one key, because an event named exactly one row */
  public void invalidate(K key) {
    if (entries.remove(key) != null) {
      invalidations.incrementAndGet();
    }
  }

  /**
   * Everything matching, for a cascade.
   *
   * A template changing invalidates every page that used it, and the cache is the only place that
   * knows which pages those were -- so the predicate runs here rather than the caller trying to
   * reconstruct the set from the database.
   */
  public int invalidateIf(Predicate<V> doomed) {
    int removed = 0;
    for (Map.Entry<K, Entry<V>> entry : entries.entrySet()) {
      if (doomed.test(entry.getValue().value)) {
        if (entries.remove(entry.getKey(), entry.getValue())) {
          removed++;
        }
      }
    }
    invalidations.addAndGet(removed);
    return removed;
  }

  /**
   * Drop every entry whose *key* matches, which is the other half of {@link #invalidateIf}.
   *
   * The value predicate is right when the cache knows what it holds -- a rendered page carries the
   * template it used, so "every page using this template" is answerable from the value. It is no
   * use at all when the thing that changed is identified by the key and the value is an anonymous
   * list of rows: the user tables cache keys entries by the question that produced them, so
   * "everything about table `signups`" is a prefix and nothing else.
   *
   * Without this the only expressible sweep was `value -> true`, which clears the whole cache for
   * every write to any table.
   */
  public int invalidateKeysIf(Predicate<K> doomed) {
    int removed = 0;
    for (Map.Entry<K, Entry<V>> entry : entries.entrySet()) {
      if (doomed.test(entry.getKey())) {
        if (entries.remove(entry.getKey(), entry.getValue())) {
          removed++;
        }
      }
    }
    invalidations.addAndGet(removed);
    return removed;
  }

  public int clear() {
    int size = entries.size();
    entries.clear();
    invalidations.addAndGet(size);
    return size;
  }

  /** drop expired entries, then the coldest until back under the ceiling */
  public int sweep() {
    long now = System.currentTimeMillis();
    int removed = 0;
    for (Map.Entry<K, Entry<V>> entry : entries.entrySet()) {
      if (entry.getValue().isExpired(now, policy.ttlMillis())) {
        if (entries.remove(entry.getKey(), entry.getValue())) {
          removed++;
        }
      }
    }
    return removed + trim();
  }

  private int trim() {
    int over = entries.size() - policy.maxEntries();
    if (over <= 0) {
      return 0;
    }
    ArrayList<Map.Entry<K, Entry<V>>> byAge = new ArrayList<>(entries.entrySet());
    byAge.sort(Comparator.comparingLong(candidate -> candidate.getValue().lastRead));
    int removed = 0;
    for (int k = 0; k < over && k < byAge.size(); k++) {
      if (entries.remove(byAge.get(k).getKey(), byAge.get(k).getValue())) {
        removed++;
      }
    }
    return removed;
  }

  public int size() {
    return entries.size();
  }

  public Stats stats() {
    return new Stats(name, policy.describe(), entries.size(), hits.get(), misses.get(), invalidations.get());
  }

  /** what a cache is doing, for the admin page */
  public record Stats(String name, String policy, int size, long hits, long misses, long invalidations) {
    public long total() {
      return hits + misses;
    }

    public int hitRate() {
      return total() == 0 ? 0 : (int) (hits * 100 / total());
    }
  }

  private static final class Entry<V> {
    final V value;
    final long storedAt;
    volatile long lastRead;

    Entry(V value, long now) {
      this.value = value;
      this.storedAt = now;
      this.lastRead = now;
    }

    boolean isExpired(long now, long ttlMillis) {
      return ttlMillis > 0 && now - storedAt >= ttlMillis;
    }
  }
}
