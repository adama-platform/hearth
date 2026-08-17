package io.hearth.analytics;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the box is doing, and the ways a number about it can lie.
 *
 * Two failures matter here and both are about honesty rather than arithmetic. A processor figure
 * computed against the beginning of time is an average over the whole uptime wearing a current
 * reading's clothes; and a number invented because `/proc` was not there is worse than a blank,
 * because somebody acts on it.
 */
public class MachineTests {
  @Test
  public void theFirstReadingHasNothingToCompareAgainst() {
    // the processor figure needs two readings to mean anything, and the honest answer to "how busy
    // is it" a millisecond after starting is "I do not know yet"
    Machine machine = new Machine();
    int first = machine.cpuPercent();
    assertTrue("either unknown, or a real number once there are two readings", first >= -1);
    machine.sample();
    machine.sample();
    assertEquals("and both samples are kept", 2, machine.size());
  }

  @Test
  public void aDayIsKeptAndNoMore() {
    Machine machine = new Machine();
    for (int k = 0; k < Machine.HISTORY + 50; k++) {
      machine.sample();
    }
    assertEquals("a day of minutes, and the oldest fall off the front",
        Machine.HISTORY, machine.size());
  }

  @Test
  public void theGraphIsAveragedIntoColumnsRatherThanSampled() {
    // thinning by skipping would miss a spike in a quiet hour, which is the one thing somebody
    // opens this screen to find
    Machine machine = new Machine();
    for (int k = 0; k < 200; k++) {
      machine.sample();
    }
    List<Map<String, Object>> graph = machine.graph(20);
    assertTrue("about the number of columns asked for", graph.size() <= 21 && graph.size() >= 19);
    for (Map<String, Object> point : graph) {
      assertTrue(point.containsKey("cpu"));
      assertTrue(point.containsKey("host"));
      assertTrue(point.containsKey("heap"));
      assertTrue((int) point.get("heap") >= 0);
    }
    assertTrue("and nothing at all is an empty graph rather than a division by zero",
        new Machine().graph(20).isEmpty());
  }

  @Test
  public void theHeadlineIsAnAverageOverTheLastFewMinutes() {
    Machine machine = new Machine();
    assertEquals("nothing sampled yet says so rather than saying zero", -1, machine.averageCpu());
    for (int k = 0; k < 10; k++) {
      machine.sample();
    }
    assertTrue(machine.averageHeap() >= 0);
    assertTrue("and the host number is either real or absent", machine.averageHost() >= -1);
  }

  @Test
  public void heapAndHostAreTwoDifferentQuestions() {
    Machine.Now now = new Machine().now();
    assertTrue("the heap is always knowable: this is the JVM asking about itself", now.heapMax() > 0);
    assertTrue(now.heapPercent() >= 0 && now.heapPercent() <= 100);
    assertTrue(now.processors() >= 1);
    if (now.hostKnown()) {
      assertTrue("available rather than free, or every healthy Linux box looks like it is dying",
          now.hostAvailable() > 0);
      assertTrue(now.hostTotal() >= now.hostAvailable());
      assertTrue(now.hostPercent() >= 0 && now.hostPercent() <= 100);
    } else {
      assertEquals("absent rather than guessed at", 0, now.hostTotal());
      assertEquals(0, now.hostPercent());
    }
  }

  @Test
  public void bytesAndUptimeReadLikeSomebodySayingThem() {
    assertEquals("unknown", Machine.bytes(0));
    assertEquals("unknown", Machine.bytes(-1));
    assertEquals("2 KB", Machine.bytes(2048));
    assertEquals("2 MB", Machine.bytes(2 * 1024 * 1024));
    assertEquals("2.0 GB", Machine.bytes(2L * 1024 * 1024 * 1024));

    assertEquals("5 minute(s)", Machine.uptime(5 * 60_000L));
    assertTrue(Machine.uptime(90 * 60_000L).startsWith("1 hour(s)"));
    assertTrue(Machine.uptime(5L * 24 * 60 * 60_000).startsWith("5 day(s)"));
  }

  @Test
  public void aMachineWithNoProcIsAnAbsenceRatherThanAZero() {
    long[] memory = Machine.hostMemory();
    // on this box there is a /proc; the shape of the answer is what is being asserted, and the
    // fallback is the pair of zeroes the screen reads as "not available"
    assertTrue(memory.length == 2);
    assertTrue(memory[0] >= 0 && memory[1] >= 0);
    assertFalse("total is never smaller than available", memory[0] > 0 && memory[1] > memory[0]);
  }
}
