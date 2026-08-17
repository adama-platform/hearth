package io.hearth.async;

import io.hearth.common.Verbose;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The queue that keeps this server on the right side of somebody else's acceptable use policy.
 *
 * Three things are being checked and all three are about the difference between kinds of answer. A
 * service saying "never heard of it" is a success. A service not answering is a failure and costs a
 * wait. A queue with nowhere to put an ask says so out loud rather than growing until the process
 * runs out of memory.
 */
public class AsyncQueueTests {
  private AsyncQueue queue;

  @After
  public void tearDown() {
    if (queue != null) {
      queue.close();
    }
  }

  private AsyncQueue running(long gap) {
    queue = new AsyncQueue(Verbose.OFF, gap);
    queue.start();
    return queue;
  }

  @Test
  public void workIsDoneOffTheThreadThatAskedForIt() {
    AtomicInteger done = new AtomicInteger();
    AsyncQueue queue = running(0);
    for (int k = 0; k < 5; k++) {
      assertTrue(queue.submit("example.org", "job " + k, () -> {
        done.incrementAndGet();
        return true;
      }));
    }
    assertTrue(queue.settle(5000));
    assertEquals(5, done.get());
    assertEquals(5, queue.counts().answered());
    assertEquals(0, queue.depth());
  }

  @Test
  public void aThousandIsTheLimitAndTheThousandAndFirstIsToldSo() {
    // an unbounded queue is a memory leak with a schedule; at the real pace a thousand items is
    // already half an hour of work, and anything producing more than that in one go is a mistake
    queue = new AsyncQueue(Verbose.OFF, 0);
    for (int k = 0; k < AsyncQueue.CAPACITY; k++) {
      assertTrue("item " + k, queue.submit("example.org", "job " + k, () -> true));
    }
    assertFalse("the one past the end is refused rather than queued",
        queue.submit("example.org", "one too many", () -> true));
    assertEquals(1, queue.counts().refused());
    assertEquals("and the refusal is written down where somebody can see it", 1,
        queue.recentFor("example.org").stream()
            .filter(item -> item.outcome() == AsyncQueue.Outcome.refused).count());
  }

  @Test
  public void nothingFoundIsAnAnswerAndCostsNoWait() throws Exception {
    // "the service has never heard of that address" is complete and correct. Treating it as an
    // error would put the whole queue into backoff because somebody typed a street name wrong.
    AsyncQueue queue = running(0);
    queue.submit("example.org", "a typo", () -> false);
    assertTrue(queue.settle(5000));
    assertEquals(1, queue.counts().nothing());
    assertEquals(0, queue.counts().failed());
    assertEquals(0, queue.backoffLeft());
    assertEquals("and it reads as nothing rather than as a failure",
        AsyncQueue.Outcome.nothing, queue.recentFor("example.org").get(0).outcome());
  }

  @Test
  public void aFailureBacksOffAndTheNextOneCostsMore() {
    AsyncQueue queue = running(0);
    queue.submit("example.org", "down", () -> {
      throw new IllegalStateException("they are not answering");
    });
    // the first failure schedules a wait, which is what stops a temporary problem becoming a block
    long until = System.currentTimeMillis() + 4000;
    while (queue.counts().failed() == 0 && System.currentTimeMillis() < until) {
      sleep();
    }
    assertEquals(1, queue.counts().failed());
    assertTrue("ten seconds to start with", queue.backoffLeft() > 5000);
    assertEquals("and doubling from there", AsyncQueue.BACKOFF_START_MILLIS * 2,
        queue.backoffNext());
    assertTrue(queue.lastProblem().contains("not answering"));
    assertEquals("it is put back rather than thrown away", 1, queue.depth());
  }

  @Test
  public void aJobThatKeepsFailingIsGivenUpOnRatherThanRetriedForever() {
    // after five tries it is the job that is wrong rather than the service, and a queue that never
    // gives up is one bad address holding a slot forever
    queue = new AsyncQueue(Verbose.OFF, 0, 5);
    queue.start();
    AtomicInteger tries = new AtomicInteger();
    queue.submit("example.org", "hopeless", () -> {
      tries.incrementAndGet();
      throw new IllegalStateException("no");
    });
    long until = System.currentTimeMillis() + 20_000;
    while (queue.counts().abandoned() == 0 && System.currentTimeMillis() < until) {
      sleep();
    }
    assertEquals(1, queue.counts().abandoned());
    assertEquals(AsyncQueue.ATTEMPTS, tries.get());
    assertEquals(0, queue.depth());
  }

  @Test
  public void oneCommunityNeverSeesAnothersWork() throws Exception {
    // the queue is one for the whole machine because the rate limit is; the screen that reports on
    // it belongs to one community, and another community's addresses on it would be a leak between
    // two groups who have nothing to do with each other
    AsyncQueue queue = running(0);
    queue.submit("example.org", "ours", () -> true);
    queue.submit("elsewhere.org", "theirs", () -> true);
    assertTrue(queue.settle(5000));
    assertEquals(1, queue.recentFor("example.org").size());
    assertEquals("ours", queue.recentFor("example.org").get(0).label());
    assertEquals(2, queue.recentFor(null).size());
  }

  @Test
  public void clearingDropsOnlyThisCommunitysWaitingWork() {
    queue = new AsyncQueue(Verbose.OFF, 0);
    queue.submit("example.org", "ours", () -> true);
    queue.submit("example.org", "ours again", () -> true);
    queue.submit("elsewhere.org", "theirs", () -> true);
    assertEquals(2, queue.clear("example.org"));
    assertEquals(1, queue.depth());
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
