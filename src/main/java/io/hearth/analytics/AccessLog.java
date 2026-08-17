package io.hearth.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The last few thousand requests, in memory, with the queries an operator actually asks.
 *
 * A ring buffer rather than a table, for the same reason the event bus is one: this is for looking
 * at, not for keeping. Five thousand requests is a day or two for a community of a few hundred,
 * which is the window in which somebody notices something odd and goes looking. Anything that needs
 * to survive a restart belongs in a real log, and the file appender already has it.
 *
 * Every query walks the buffer. That is a few thousand iterations over an array of records with no
 * allocation per entry, which is far cheaper than maintaining a dozen running aggregates that would
 * all need to be correct under concurrent writes.
 */
public class AccessLog {
  public static final int DEFAULT_CAPACITY = 5000;

  private final Hit[] ring;
  private final AtomicLong sequence = new AtomicLong();
  private final UserAgents agents = new UserAgents();
  private int writeAt;

  /** the one path prefix whose traffic is a heartbeat rather than a visit */
  private static final String LIVE = io.hearth.live.LiveRoutes.ROOT + "/";
  private final java.util.concurrent.atomic.AtomicLong livePings =
      new java.util.concurrent.atomic.AtomicLong();

  public AccessLog() {
    this(DEFAULT_CAPACITY);
  }

  public AccessLog(int capacity) {
    this.ring = new Hit[Math.max(1, capacity)];
    this.writeAt = 0;
  }

  public UserAgents agents() {
    return agents;
  }

  public int capacity() {
    return ring.length;
  }

  /**
   * How many times a browser has asked the live channel whether anything happened.
   *
   * Counted rather than logged. An open tab polls or reconnects every few seconds forever, so
   * inside an hour the live channel is nine tenths of every request -- which pushes every page
   * anybody actually reads off the top-pages list and makes the error rate, the busiest hours and
   * the per-member counts all describe a heartbeat instead of a community. One number says
   * everything that traffic has to say.
   */
  public long livePings() {
    return livePings.get();
  }

  public long total() {
    return sequence.get();
  }

  /**
   * Record a request; the user agent is classified here so the query side never re-parses it.
   *
   * The live channel is the one exception, and it is not a filter on the query side: it never
   * enters the ring at all. A filter would still let a heartbeat push a page somebody read out of
   * the five thousand this holds, which is the same damage arriving more slowly.
   */
  public Hit record(long atMillis, String domain, String method, String uri, int status,
                    long durationMicros, String ip, Long userId, String userAgent, String referer) {
    if (uri != null && uri.startsWith(LIVE)) {
      if (!uri.endsWith(".js")) {
        // the two shipped scripts are fetched once and cached; only the asking is a ping
        livePings.incrementAndGet();
      }
      return null;
    }
    Hit hit = new Hit(sequence.incrementAndGet(), atMillis, domain, method, trim(uri, 512), status,
        durationMicros, ip, userId, agents.classify(userAgent), trim(referer, 256));
    synchronized (this) {
      ring[writeAt] = hit;
      writeAt = (writeAt + 1) % ring.length;
    }
    return hit;
  }

  private static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /**
   * Forget every request one person made.
   *
   * The ring holds an IP address and a user id per entry, which is personal data by any reading, so
   * an erasure that left it in memory would be a promise kept everywhere except the one place an
   * operator would not think to look. Cheap: this buffer is a few thousand entries and erasing
   * somebody happens once.
   */
  public synchronized int forgetUser(long userId) {
    int cleared = 0;
    for (int k = 0; k < ring.length; k++) {
      Hit hit = ring[k];
      if (hit != null && hit.userId() != null && hit.userId() == userId) {
        ring[k] = null;
        cleared++;
      }
    }
    return cleared;
  }

  /** everything the buffer holds, newest first */
  public synchronized List<Hit> recent(int limit) {
    ArrayList<Hit> out = new ArrayList<>(Math.min(limit, ring.length));
    for (int k = 1; k <= ring.length && out.size() < limit; k++) {
      Hit hit = ring[(writeAt - k + ring.length) % ring.length];
      if (hit == null) {
        break;
      }
      out.add(hit);
    }
    return out;
  }

  public List<Hit> recent() {
    return recent(ring.length);
  }

  /**
   * The searchable log.
   *
   * One free-text term matched against path, ip, agent and status, plus optional filters. Written as
   * a single pass with a predicate rather than a query language, because the whole point is that an
   * operator types "404" or "/about" or "curl" into one box and gets the obvious answer.
   */
  public List<Hit> search(Query query) {
    ArrayList<Hit> out = new ArrayList<>();
    for (Hit hit : recent()) {
      if (query.matches(hit)) {
        out.add(hit);
        if (out.size() >= query.limit()) {
          break;
        }
      }
    }
    return out;
  }

  /** what a dashboard shows, computed in one pass */
  public Summary summarize(String domain, int topN) {
    List<Hit> hits = recent();
    HashMap<String, long[]> byPath = new HashMap<>();
    HashMap<String, long[]> byIp = new HashMap<>();
    HashMap<String, long[]> byAgent = new HashMap<>();
    HashMap<Long, long[]> byUser = new HashMap<>();
    HashMap<Integer, long[]> byStatus = new HashMap<>();
    long total = 0;
    long errors = 0;
    long people = 0;
    long signedIn = 0;
    long micros = 0;
    long oldest = Long.MAX_VALUE;
    long newest = 0;

    for (Hit hit : hits) {
      if (domain != null && !domain.equals(hit.domain())) {
        continue;
      }
      total++;
      micros += hit.durationMicros();
      oldest = Math.min(oldest, hit.atMillis());
      newest = Math.max(newest, hit.atMillis());
      if (hit.isError()) {
        errors++;
      }
      if (UserAgents.isPerson(hit.agent())) {
        people++;
      }
      if (hit.bySomebodyKnown()) {
        signedIn++;
        bump(byUser, hit.userId());
      }
      bump(byPath, hit.path());
      if (hit.ip() != null) {
        bump(byIp, hit.ip());
      }
      bump(byAgent, hit.agent());
      bump(byStatus, hit.status());
    }
    return new Summary(total, errors, people, signedIn,
        total == 0 ? 0 : micros / total / 1000,
        oldest == Long.MAX_VALUE ? 0 : oldest, newest,
        top(byPath, topN), top(byIp, topN), top(byAgent, topN),
        topLong(byUser, topN), topInt(byStatus, topN));
  }

  private static <K> void bump(Map<K, long[]> counts, K key) {
    counts.computeIfAbsent(key, k -> new long[1])[0]++;
  }

  private static List<Count> top(Map<String, long[]> counts, int limit) {
    ArrayList<Count> out = new ArrayList<>();
    for (Map.Entry<String, long[]> entry : counts.entrySet()) {
      out.add(new Count(entry.getKey(), entry.getValue()[0]));
    }
    out.sort(Comparator.comparingLong(Count::count).reversed());
    return out.subList(0, Math.min(limit, out.size()));
  }

  private static List<Count> topLong(Map<Long, long[]> counts, int limit) {
    LinkedHashMap<String, long[]> asText = new LinkedHashMap<>();
    for (Map.Entry<Long, long[]> entry : counts.entrySet()) {
      asText.put(Long.toString(entry.getKey()), entry.getValue());
    }
    return top(asText, limit);
  }

  private static List<Count> topInt(Map<Integer, long[]> counts, int limit) {
    LinkedHashMap<String, long[]> asText = new LinkedHashMap<>();
    for (Map.Entry<Integer, long[]> entry : counts.entrySet()) {
      asText.put(Integer.toString(entry.getKey()), entry.getValue());
    }
    return top(asText, limit);
  }

  /** a label and how often it appeared */
  public record Count(String label, long count) {
  }

  /** the dashboard numbers */
  public record Summary(long total, long errors, long people, long signedIn, long medianMillis,
                        long oldestAt, long newestAt, List<Count> topPaths, List<Count> topIps,
                        List<Count> topAgents, List<Count> topUsers, List<Count> statuses) {
    public long windowMinutes() {
      return newestAt <= oldestAt ? 0 : (newestAt - oldestAt) / 60000;
    }

    public int errorRate() {
      return total == 0 ? 0 : (int) (errors * 100 / total);
    }
  }

  /** a search over the buffer */
  public record Query(String domain, String text, Integer status, Long userId, Boolean errorsOnly, int limit) {
    public static Query of(String domain, String text, Integer status, Long userId, Boolean errorsOnly, int limit) {
      return new Query(domain, text, status, userId, errorsOnly, Math.max(1, Math.min(limit, 5000)));
    }

    public boolean matches(Hit hit) {
      if (domain != null && !domain.equals(hit.domain())) {
        return false;
      }
      if (status != null && hit.status() != status) {
        return false;
      }
      if (userId != null && !userId.equals(hit.userId())) {
        return false;
      }
      if (Boolean.TRUE.equals(errorsOnly) && !hit.isError()) {
        return false;
      }
      if (text == null || text.isBlank()) {
        return true;
      }
      String needle = text.toLowerCase(Locale.ROOT);
      return contains(hit.uri(), needle)
          || contains(hit.ip(), needle)
          || contains(hit.agent(), needle)
          || contains(hit.method(), needle)
          || contains(hit.referer(), needle)
          || Integer.toString(hit.status()).contains(needle);
    }

    private static boolean contains(String value, String needle) {
      return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
  }
}
