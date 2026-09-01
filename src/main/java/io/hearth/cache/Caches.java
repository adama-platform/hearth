package io.hearth.cache;

import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cache policies for one domain: a catch-all, plus per-cache overrides.
 *
 * ```json
 * "cache": {
 *   "ttl-seconds": 3600,
 *   "content":  { "ttl-seconds": 600 },
 *   "rendered": { "max-entries": 200 }
 * }
 * ```
 *
 * Named caches inherit from the catch-all and change only what they name, so an operator who wants
 * everything shorter changes one number.
 */
public class Caches {
  /** page bodies as stored, keyed by uri */
  public static final String CONTENT = "content";
  /** fully rendered pages, keyed by content id */
  public static final String RENDERED = "rendered";
  /** compiled templates, keyed by name */
  public static final String TEMPLATES = "templates";
  // `board-feed`, `board-threads` and `feeds` were here, naming caches whose features are gone.
  // A cache name nobody uses is worse than none: it is a name an operator can still put in a
  // config file and tune, and get no error and no effect.

  private final CachePolicy catchAll;
  private final Map<String, CachePolicy> named;

  private Caches(CachePolicy catchAll, Map<String, CachePolicy> named) {
    this.catchAll = catchAll;
    this.named = named;
  }

  public static Caches defaults() {
    return new Caches(CachePolicy.defaults(), Map.of());
  }

  public static Caches of(ConfigObject config) throws ConfigException {
    CachePolicy catchAll = new CachePolicy(
        config.boolOf("enabled", true),
        config.intOf("ttl-seconds", 3600),
        config.intOf("max-entries", 1000));
    LinkedHashMap<String, CachePolicy> named = new LinkedHashMap<>();
    for (String name : new String[]{CONTENT, RENDERED, TEMPLATES}) {
      named.put(name, CachePolicy.of(config.child(name), catchAll));
    }
    config.assertKnownKeys();
    if (catchAll.ttlSeconds() < 0) {
      throw new ConfigException("cache.ttl-seconds must be zero or more");
    }
    if (catchAll.maxEntries() <= 0) {
      throw new ConfigException("cache.max-entries must be greater than zero");
    }
    return new Caches(catchAll, named);
  }

  public CachePolicy forName(String name) {
    return named.getOrDefault(name, catchAll);
  }

  public CachePolicy catchAll() {
    return catchAll;
  }

  public String describe() {
    return "catch-all " + catchAll.describe();
  }
}
