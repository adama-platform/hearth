package io.hearth.analytics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the box itself is doing: processor, memory, and a day of both.
 *
 * <b>Read from `/proc`, because that is where the truth is on the machine this runs on.</b> Java's
 * own answers are either about the JVM alone or, in the case of system load, a number most people
 * misread -- load average is runnable processes, not a percentage, and a load of 4 on four cores is
 * a busy machine while a load of 4 on thirty-two is nothing. So the processor figure here is
 * computed the way `top` computes it: two readings of `/proc/stat`, and the share of the difference
 * that was not idle.
 *
 * <b>Both halves of memory, because they answer different questions.</b> The JVM's heap says
 * whether *this program* is under pressure; the host's available memory says whether the machine
 * is, which is the number that decides whether the next thing to arrive on this box gets killed.
 * An operator staring at a slow server needs to know which of those it is, and one number cannot
 * say.
 *
 * <b>A day of history, in memory, at one sample a minute.</b> 1440 samples of two small numbers is
 * a rounding error of heap, and it means the question "was it like this an hour ago" has an answer
 * without a database table, a metrics stack, or anything to configure. It is lost on a restart,
 * which is the honest trade: this is a window on the last day, not a record.
 *
 * <b>Anything it cannot read is absent rather than guessed.</b> On a machine with no `/proc` -- a
 * Mac, a container built strangely -- the host numbers are simply missing and the screen says so.
 * A made-up number on an operations screen is worse than a blank one.
 */
public class Machine {
  /** how often a sample is taken */
  public static final int SAMPLE_SECONDS = 60;
  /** how many are kept: a day of minutes */
  public static final int HISTORY = 24 * 60;
  /** the window the headline number is averaged over */
  public static final int AVERAGE_MINUTES = 5;

  private static final Path STAT = Path.of("/proc/stat");
  private static final Path MEMINFO = Path.of("/proc/meminfo");

  /** one minute: when, how busy the processor was, and how much memory was in use */
  public record Sample(long at, int cpuPercent, int heapPercent, int hostPercent) {
  }

  /** the numbers as they are right now */
  public record Now(int cpuPercent, boolean cpuKnown, long heapUsed, long heapMax, long heapCommitted,
                    long hostTotal, long hostAvailable, boolean hostKnown, int processors,
                    double loadAverage, long uptimeMillis) {
    public int heapPercent() {
      return heapMax <= 0 ? 0 : (int) Math.round(100.0 * heapUsed / heapMax);
    }

    public int hostPercent() {
      return hostTotal <= 0 ? 0 : (int) Math.round(100.0 * (hostTotal - hostAvailable) / hostTotal);
    }
  }

  private final ArrayList<Sample> samples = new ArrayList<>();
  private long[] lastCpu;
  private volatile int lastCpuPercent = -1;

  /**
   * Take one reading.
   *
   * Called on a timer, and the processor figure needs two readings to mean anything -- the first
   * one after a start returns "unknown" rather than a number computed against the beginning of
   * time, which would be an average over the whole uptime dressed up as a current reading.
   */
  public synchronized Sample sample() {
    Now now = now();
    Sample taken = new Sample(System.currentTimeMillis(), now.cpuKnown() ? now.cpuPercent() : -1,
        now.heapPercent(), now.hostKnown() ? now.hostPercent() : -1);
    samples.add(taken);
    while (samples.size() > HISTORY) {
      samples.remove(0);
    }
    return taken;
  }

  /** everything as it stands, without disturbing the history */
  public Now now() {
    Runtime runtime = Runtime.getRuntime();
    long heapMax = runtime.maxMemory();
    long heapUsed = runtime.totalMemory() - runtime.freeMemory();
    long[] host = hostMemory();
    int cpu = cpuPercent();
    return new Now(Math.max(cpu, 0), cpu >= 0, heapUsed, heapMax, runtime.totalMemory(),
        host[0], host[1], host[0] > 0, runtime.availableProcessors(), loadAverage(),
        java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
  }

  /**
   * The share of the last interval the processor was not idle.
   *
   * The same arithmetic `top` does: the difference between two readings of the jiffy counters, with
   * idle and iowait on one side and everything else on the other. Between calls rather than since
   * boot, which is the difference between "busy now" and "busy on average since Tuesday".
   */
  synchronized int cpuPercent() {
    long[] reading = readCpu();
    if (reading == null) {
      return -1;
    }
    long[] previous = lastCpu;
    lastCpu = reading;
    if (previous == null) {
      // nothing to compare against yet; the last computed answer is better than a made-up one
      return lastCpuPercent;
    }
    long idle = (reading[3] + reading[4]) - (previous[3] + previous[4]);
    long total = 0;
    for (int k = 0; k < reading.length; k++) {
      total += reading[k] - previous[k];
    }
    if (total <= 0) {
      return lastCpuPercent;
    }
    lastCpuPercent = (int) Math.round(100.0 * (total - idle) / total);
    return lastCpuPercent;
  }

  /** user, nice, system, idle, iowait, irq, softirq, steal -- or null when there is no /proc */
  private static long[] readCpu() {
    try {
      for (String line : Files.readAllLines(STAT, StandardCharsets.UTF_8)) {
        if (!line.startsWith("cpu ")) {
          continue;
        }
        String[] parts = line.trim().split("\\s+");
        long[] values = new long[Math.max(0, parts.length - 1)];
        for (int k = 1; k < parts.length; k++) {
          values[k - 1] = Long.parseLong(parts[k]);
        }
        return values.length >= 5 ? values : null;
      }
    } catch (IOException | RuntimeException ex) {
      // no /proc, or something there this code has never seen: the answer is "unknown"
    }
    return null;
  }

  /** total and available bytes, or two zeroes */
  static long[] hostMemory() {
    long total = 0;
    long available = 0;
    try {
      for (String line : Files.readAllLines(MEMINFO, StandardCharsets.UTF_8)) {
        if (line.startsWith("MemTotal:")) {
          total = kilobytesIn(line);
        } else if (line.startsWith("MemAvailable:")) {
          // MemAvailable rather than MemFree: free memory on Linux is nearly always small because
          // the kernel uses the rest for cache, and reporting it would make every healthy machine
          // look like it is about to fall over
          available = kilobytesIn(line);
        }
      }
    } catch (IOException | RuntimeException ex) {
      return new long[]{0, 0};
    }
    return new long[]{total * 1024, available * 1024};
  }

  private static long kilobytesIn(String line) {
    String[] parts = line.trim().split("\\s+");
    return parts.length >= 2 ? Long.parseLong(parts[1]) : 0;
  }

  private static double loadAverage() {
    double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        .getSystemLoadAverage();
    return load < 0 ? 0 : load;
  }

  /** the headline: the average over the last few minutes, or -1 when nothing is known */
  public synchronized int averageCpu() {
    return averageOf(AVERAGE_MINUTES, Sample::cpuPercent);
  }

  public synchronized int averageHost() {
    return averageOf(AVERAGE_MINUTES, Sample::hostPercent);
  }

  public synchronized int averageHeap() {
    return averageOf(AVERAGE_MINUTES, Sample::heapPercent);
  }

  private int averageOf(int minutes, java.util.function.ToIntFunction<Sample> of) {
    int total = 0;
    int counted = 0;
    for (int k = samples.size() - 1; k >= 0 && counted < minutes; k--) {
      int value = of.applyAsInt(samples.get(k));
      if (value >= 0) {
        total += value;
        counted++;
      }
    }
    return counted == 0 ? -1 : Math.round((float) total / counted);
  }

  /** the whole day, oldest first */
  public synchronized List<Sample> history() {
    return List.copyOf(samples);
  }

  public synchronized int size() {
    return samples.size();
  }

  /**
   * The history as points for a drawn graph, thinned to a readable number of columns.
   *
   * Averaged into buckets rather than sampled, so a spike in a quiet hour still shows rather than
   * being skipped over by whichever minute the thinning happened to land on.
   */
  public synchronized List<Map<String, Object>> graph(int columns) {
    ArrayList<Map<String, Object>> points = new ArrayList<>();
    if (samples.isEmpty()) {
      return points;
    }
    int perColumn = Math.max(1, samples.size() / Math.max(1, columns));
    for (int start = 0; start < samples.size(); start += perColumn) {
      int end = Math.min(samples.size(), start + perColumn);
      int cpu = 0;
      int cpuCount = 0;
      int host = 0;
      int hostCount = 0;
      int heap = 0;
      for (int k = start; k < end; k++) {
        Sample sample = samples.get(k);
        if (sample.cpuPercent() >= 0) {
          cpu += sample.cpuPercent();
          cpuCount++;
        }
        if (sample.hostPercent() >= 0) {
          host += sample.hostPercent();
          hostCount++;
        }
        heap += sample.heapPercent();
      }
      LinkedHashMap<String, Object> point = new LinkedHashMap<>();
      point.put("cpu", cpuCount == 0 ? 0 : cpu / cpuCount);
      point.put("host", hostCount == 0 ? 0 : host / hostCount);
      point.put("heap", heap / (end - start));
      point.put("at", samples.get(end - 1).at());
      points.add(point);
    }
    return points;
  }

  /** bytes as somebody would say them */
  public static String bytes(long value) {
    if (value <= 0) {
      return "unknown";
    }
    if (value < 1024L * 1024) {
      return Math.round(value / 1024.0) + " KB";
    }
    if (value < 1024L * 1024 * 1024) {
      return Math.round(value / (1024.0 * 1024)) + " MB";
    }
    return String.format("%.1f GB", value / (1024.0 * 1024 * 1024));
  }

  /** how long this process has been up, in the words a person uses */
  public static String uptime(long millis) {
    long minutes = millis / 60000;
    if (minutes < 60) {
      return minutes + " minute(s)";
    }
    long hours = minutes / 60;
    return hours < 48 ? hours + " hour(s), " + (minutes % 60) + " minute(s)"
        : (hours / 24) + " day(s), " + (hours % 24) + " hour(s)";
  }
}
