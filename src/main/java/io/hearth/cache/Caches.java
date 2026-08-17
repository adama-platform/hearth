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
  /** the board's feed rows; one entry, rebuilt by one query */
  public static final String BOARD_FEED = "board-feed";
  /** a thread's comments with the markdown already rendered, keyed by post id */
  public static final String BOARD_THREADS = "board-threads";
  /** a rendered feed page, keyed by who is looking and what they asked for */
  public static final String FEEDS = "feeds";

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
    for (String name : new String[]{CONTENT, RENDERED, TEMPLATES, BOARD_FEED,
        BOARD_THREADS, FEEDS}) {
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
