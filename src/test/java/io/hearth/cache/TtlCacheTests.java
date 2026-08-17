package io.hearth.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TtlCacheTests {
  private static TtlCache<String, String> cache(long ttlSeconds, int max) {
    return new TtlCache<>("test", new CachePolicy(true, ttlSeconds, max));
  }

  @Test
  public void putThenGet() {
    TtlCache<String, String> cache = cache(60, 10);
    cache.put("a", "one");
    assertEquals("one", cache.get("a"));
    assertNull(cache.get("b"));
    assertEquals(1, cache.stats().hits());
    assertEquals(1, cache.stats().misses());
  }

  @Test
  public void anExpiredEntryIsNotServed() throws Exception {
    // a zero TTL means "never expires", so use the smallest real one and sleep past it
    TtlCache<String, String> cache = cache(1, 10);
    cache.put("a", "one");
    assertNotNull(cache.get("a"));
    Thread.sleep(1100);
    assertNull("a stale value must never be served, even before a sweep", cache.get("a"));
  }

  @Test
  public void zeroTtlMeansNoExpiry() {
    TtlCache<String, String> cache = cache(0, 10);
    cache.put("a", "one");
    assertEquals("one", cache.get("a"));
    assertEquals(0, cache.sweep());
  }

  @Test
  public void theCeilingDropsTheColdest() {
    TtlCache<String, String> cache = cache(60, 3);
    cache.put("a", "1");
    cache.put("b", "2");
    cache.put("c", "3");
    cache.get("a");
    cache.get("b");
    cache.get("c");
    cache.put("d", "4");
    assertTrue("should be back under the ceiling", cache.size() <= 3);
  }

  @Test
  public void invalidateRemovesExactlyOne() {
    TtlCache<String, String> cache = cache(60, 10);
    cache.put("a", "1");
    cache.put("b", "2");
    cache.invalidate("a");
    assertNull(cache.get("a"));
    assertEquals("2", cache.get("b"));
    assertEquals(1, cache.stats().invalidations());
  }

  @Test
  public void invalidateIfIsTheCascade() {
    TtlCache<String, String> cache = cache(60, 10);
    cache.put("a", "uses:site");
    cache.put("b", "uses:site");
    cache.put("c", "uses:other");
    int dropped = cache.invalidateIf(value -> value.equals("uses:site"));
    assertEquals(2, dropped);
    assertNull(cache.get("a"));
    assertNull(cache.get("b"));
    assertNotNull(cache.get("c"));
  }

  @Test
  public void aDisabledCacheNeverHoldsAnything() {
    TtlCache<String, String> cache = new TtlCache<>("test", CachePolicy.DISABLED);
    cache.put("a", "1");
    assertNull(cache.get("a"));
    assertEquals(0, cache.size());
  }

  @Test
  public void nullsAreNotStored() {
    TtlCache<String, String> cache = cache(60, 10);
    cache.put("a", null);
    assertEquals(0, cache.size());
  }

  @Test
  public void statsDescribeWhatIsHappening() {
    TtlCache<String, String> cache = cache(60, 10);
    cache.put("a", "1");
    cache.get("a");
    cache.get("a");
    cache.get("missing");
    TtlCache.Stats stats = cache.stats();
    assertEquals("test", stats.name());
    assertEquals(2, stats.hits());
    assertEquals(1, stats.misses());
    assertEquals(66, stats.hitRate());
  }

  @Test
  public void clearDropsEverything() {
    TtlCache<String, String> cache = cache(60, 10);
    cache.put("a", "1");
    cache.put("b", "2");
    assertEquals(2, cache.clear());
    assertEquals(0, cache.size());
  }
}
