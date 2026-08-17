package io.hearth.live;

import io.hearth.common.Verbose;
import io.hearth.events.LocalEventBus;
import io.hearth.events.MutationEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The hub on its own, including the parts a browser never reaches.
 *
 * A waiter is woken on the publishing thread, which is the one property here that would be
 * expensive to get wrong: a listener that throws, or one belonging to a connection that has already
 * gone, must not stop the next one being told.
 */
public class LiveHubTests {
  private static LiveHub hub() {
    return new LiveHub("example.org", Verbose.OFF);
  }

  private static LiveHub.Waiter waiter(long userId, List<Signal> into) {
    return new LiveHub.Waiter() {
      @Override
      public void wake(Signal signal) {
        into.add(signal);
      }

      @Override
      public long userId() {
        return userId;
      }
    };
  }

  @Test
  public void aBrokenWaiterDoesNotStopTheNextOne() {
    LiveHub hub = hub();
    AtomicInteger reached = new AtomicInteger();
    hub.addWaiter(new LiveHub.Waiter() {
      @Override
      public void wake(Signal signal) {
        throw new IllegalStateException("this connection is gone");
      }

      @Override
      public long userId() {
        return 3;
      }
    });
    hub.addWaiter(new LiveHub.Waiter() {
      @Override
      public void wake(Signal signal) {
        reached.incrementAndGet();
      }

      @Override
      public long userId() {
        return 4;
      }
    });
    hub.publish(Signal.Kind.updated, "posts:1", null);
    assertEquals("a dead connection is not everybody else's problem", 1, reached.get());
  }

  @Test
  public void removingAWaiterStopsIt() {
    LiveHub hub = hub();
    List<Signal> heard = new ArrayList<>();
    LiveHub.Waiter waiter = waiter(3, heard);
    hub.addWaiter(waiter);
    hub.publish(Signal.Kind.updated, "posts:1", null);
    hub.removeWaiter(waiter);
    hub.publish(Signal.Kind.updated, "posts:2", null);
    assertEquals(1, heard.size());
    assertEquals(0, hub.connections());
  }

  @Test
  public void goingQuietIsAnnouncedOnceAndOnlyIfTheyWereHere() {
    LiveHub hub = hub();
    long before = hub.head();
    hub.gone(3);
    assertEquals("nobody was told about somebody who was never here", before, hub.head());
    hub.beat(3);
    long announced = hub.head();
    hub.gone(3);
    assertTrue(hub.head() > announced);
    hub.gone(3);
    assertEquals("and not twice", announced + 1, hub.head());
  }

  @Test
  public void theSweepDropsWhatHasAgedOut() throws Exception {
    LiveHub hub = hub();
    hub.beat(3);
    hub.sweep();
    assertTrue("nothing has aged out yet", hub.isOnline(3));
    hub.sweep();
    assertNotNull(hub.stats().get("sequence"));
    assertEquals("example.org", hub.stats().get("domain"));
    assertEquals(0, hub.stats().get("connections"));
  }

  @Test
  public void onlyTheTablesWorthRedrawingProduceASignal() {
    LiveHub hub = hub();
    LocalEventBus bus = new LocalEventBus(Verbose.OFF);
    hub.listenTo(bus);
    long before = hub.head();

    bus.emit("example.org", "content", "1", MutationEvent.Kind.update, null);
    assertEquals("a page being saved must not wake every browser in the community",
        before, hub.head());

    bus.emit("elsewhere.org", "posts", "1", MutationEvent.Kind.insert, null);
    assertEquals("nor a write on somebody else's domain", before, hub.head());

    bus.emit("example.org", "comments", "7", MutationEvent.Kind.insert, null);
    assertEquals(before + 1, hub.head());
    assertEquals("comments:7", hub.since(before).get(0).scope());
  }

  @Test
  public void aRegistryHandsOutOneHubPerCommunity() {
    Live live = new Live(Verbose.OFF);
    LiveHub one = live.forDomain("example.org");
    assertTrue("the same community is the same hub", one == live.forDomain("example.org"));
    assertFalse("and another community is another one", one == live.forDomain("other.org"));
    assertEquals(2, live.all().size());
    assertEquals(0, live.connections());
    live.sweep();
  }

  @Test
  public void everyLivePageIsOneNameInOneList() {
    // adding a table to LIVE_TABLES is the whole of making a new page update itself, which is why
    // it is worth a test that the four that should be there are
    LiveHub hub = hub();
    LocalEventBus bus = new LocalEventBus(Verbose.OFF);
    hub.listenTo(bus);
    for (String table : List.of("posts", "comments", "calendar", "rsvps", "places")) {
      long before = hub.head();
      bus.emit("example.org", table, "1", MutationEvent.Kind.insert, null);
      assertEquals(table + " should be live", before + 1, hub.head());
    }
  }

  @Test
  public void everybodyOnTheChannelHearsEverything() {
    // there is no addressing left. Every signal names a row every member could already fetch, so
    // "who may hear this" has one answer -- and a channel with no audience list has nowhere for a
    // mistake about audiences to live.
    LiveHub hub = hub();
    List<Signal> ana = new ArrayList<>();
    List<Signal> bo = new ArrayList<>();
    hub.addWaiter(waiter(3, ana));
    hub.addWaiter(waiter(4, bo));
    hub.publish(Signal.Kind.updated, "posts:1", null);
    assertEquals(1, ana.size());
    assertEquals(1, bo.size());
  }
}
