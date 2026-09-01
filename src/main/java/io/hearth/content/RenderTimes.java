package io.hearth.content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long each page takes to build, for the last {@link #KEPT} times it was built.
 *
 * <b>Every kind, not just the dynamic one.</b> A number is only useful next to its neighbours: "this
 * page takes 40ms" means nothing until the markdown page beside it turns out to take 0.3ms. So
 * markdown, HTML, whole-document pages and JavaScript are all timed through the same call, and the
 * content listing prints one column for all of them.
 *
 * <b>Fifty, in memory, per page.</b> The same shape as the access log and for the same reason: this
 * is for answering "is that page slow" while looking at it, not for a time series. A restart loses
 * it. Fifty samples is enough to make a p99 mean something without pretending it is a percentile
 * over a population -- with fifty samples the p99 is the slowest one, which is exactly the number
 * somebody wants when they ask how bad it gets.
 *
 * <b>Bounded by pages as well as by samples.</b> A site with more pages than {@link #MAX_PAGES}
 * stops adding new ones rather than growing without limit; a request for a page that is not tracked
 * simply has no timing, which the listing shows as a dash.
 */
public final class RenderTimes {
  /** how many executions are remembered per page */
  public static final int KEPT = 50;

  /** a ceiling on distinct pages tracked, so this cannot become the biggest thing in the process */
  private static final int MAX_PAGES = 5000;

  private final ConcurrentHashMap<String, Ring> byUri = new ConcurrentHashMap<>();

  /** record one build of one page, in nanoseconds */
  public void record(String uri, long nanos) {
    if (uri == null) {
      return;
    }
    Ring ring = byUri.get(uri);
    if (ring == null) {
      if (byUri.size() >= MAX_PAGES) {
        return;
      }
      ring = byUri.computeIfAbsent(uri, key -> new Ring());
    }
    ring.add(nanos);
  }

  /** what is known about one page, or null if it has not been built since the last restart */
  public Stat of(String uri) {
    Ring ring = uri == null ? null : byUri.get(uri);
    return ring == null ? null : ring.snapshot();
  }

  /** every page with a timing, for the admin listing */
  public Map<String, Stat> all() {
    ConcurrentHashMap<String, Stat> out = new ConcurrentHashMap<>();
    byUri.forEach((uri, ring) -> {
      Stat stat = ring.snapshot();
      if (stat != null) {
        out.put(uri, stat);
      }
    });
    return out;
  }

  /** a page was deleted or renamed; its old timings describe an address nothing answers */
  public void forget(String uri) {
    if (uri != null) {
      byUri.remove(uri);
    }
  }

  public void clear() {
    byUri.clear();
  }

  /**
   * The last {@link #KEPT} durations for one page.
   *
   * Synchronized rather than lock-free: this is written once per page render and read once per
   * admin listing, so contention is not a thing that happens, and a ring somebody can read in one
   * pass is worth more here than a clever one.
   */
  private static final class Ring {
    private final long[] nanos = new long[KEPT];
    private int count;
    private int next;

    synchronized void add(long value) {
      nanos[next] = value;
      next = (next + 1) % KEPT;
      if (count < KEPT) {
        count++;
      }
    }

    synchronized Stat snapshot() {
      if (count == 0) {
        return null;
      }
      long[] sorted = Arrays.copyOf(nanos, count);
      Arrays.sort(sorted);
      long total = 0;
      for (long value : sorted) {
        total += value;
      }
      return new Stat(count, sorted[0], sorted[sorted.length - 1],
          total / count, percentile(sorted, 0.50), percentile(sorted, 0.99));
    }

    /**
     * The nearest-rank percentile.
     *
     * Nearest-rank rather than interpolated, because with fifty samples an interpolated p99 is a
     * number that appears in no execution that ever happened, and the question being asked is "how
     * slow does this actually get".
     */
    private static long percentile(long[] sorted, double fraction) {
      int rank = (int) Math.ceil(fraction * sorted.length);
      return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
    }
  }

  /** one page's timings, in nanoseconds; the screen turns them into milliseconds */
  public record Stat(int samples, long fastest, long slowest, long mean, long p50, long p99) {
    public double p99Millis() {
      return p99 / 1_000_000.0;
    }

    public double p50Millis() {
      return p50 / 1_000_000.0;
    }

    public double meanMillis() {
      return mean / 1_000_000.0;
    }

    public double slowestMillis() {
      return slowest / 1_000_000.0;
    }

    /** two significant figures below 10ms, none above: 0.42ms, 4.1ms, 87ms */
    public static String show(double millis) {
      if (millis >= 10) {
        return Math.round(millis) + "ms";
      }
      if (millis >= 1) {
        return String.format("%.1fms", millis);
      }
      return String.format("%.2fms", millis);
    }

    public String p99Shown() {
      return show(p99Millis());
    }

    public String p50Shown() {
      return show(p50Millis());
    }

    /** slowest first, for a listing that wants the worst pages at the top */
    public static List<Map.Entry<String, Stat>> slowestFirst(Map<String, Stat> stats) {
      ArrayList<Map.Entry<String, Stat>> rows = new ArrayList<>(stats.entrySet());
      rows.sort((left, right) -> Long.compare(right.getValue().p99(), left.getValue().p99()));
      return rows;
    }
  }
}
