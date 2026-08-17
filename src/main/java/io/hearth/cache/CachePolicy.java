package io.hearth.cache;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

/**
 * How long something may be held, and how much of it.
 *
 * One shape for every cache in the process, configured once as a catch-all and overridden where a
 * particular cache needs different numbers. That uniformity is the point: "what is cached here and
 * for how long" should be answerable from the config file rather than by reading the code, and a
 * cache that invented its own private timeout would be a cache nobody could reason about.
 *
 * The TTL is a backstop, not the invalidation mechanism. Content and templates are invalidated
 * precisely by the event bus the moment they change, so the hour is what covers the case where an
 * event was missed -- a bug today, another process tomorrow.
 */
public record CachePolicy(boolean enabled, long ttlSeconds, int maxEntries) {
  public static final CachePolicy DISABLED = new CachePolicy(false, 0, 0);

  /** the catch-all: an hour, and enough room for a small community's whole site */
  public static CachePolicy defaults() {
    return new CachePolicy(true, 3600, 1000);
  }

  public static CachePolicy of(ConfigObject config, CachePolicy fallback) throws ConfigException {
    boolean enabled = config.boolOf("enabled", fallback.enabled());
    int ttl = config.intOf("ttl-seconds", (int) fallback.ttlSeconds());
    int max = config.intOf("max-entries", fallback.maxEntries());
    config.assertKnownKeys();
    if (ttl < 0) {
      throw new ConfigException("cache ttl-seconds must be zero or more");
    }
    if (max <= 0) {
      throw new ConfigException("cache max-entries must be greater than zero");
    }
    return new CachePolicy(enabled, ttl, max);
  }

  public long ttlMillis() {
    return ttlSeconds * 1000L;
  }

  public String describe() {
    if (!enabled) {
      return "off";
    }
    return (ttlSeconds == 0 ? "no expiry" : ttlSeconds + "s") + ", up to " + maxEntries + " entries";
  }
}
