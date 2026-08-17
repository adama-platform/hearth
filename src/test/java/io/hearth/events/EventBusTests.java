package io.hearth.events;

import io.hearth.common.Verbose;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EventBusTests {
  private static LocalEventBus bus(int capacity) {
    return new LocalEventBus(Verbose.OFF, capacity);
  }

  @Test
  public void anEventNamesTheDomainTableAndRow() {
    LocalEventBus bus = bus(10);
    MutationEvent event = bus.emit("a.test", "content", "7", MutationEvent.Kind.update, 3L);
    assertEquals("a.test", event.domain());
    assertEquals("content", event.table());
    assertEquals("7", event.key());
    assertEquals(MutationEvent.Kind.update, event.kind());
    assertEquals(Long.valueOf(3), event.actor());
    assertTrue(event.touches("content", "7"));
    assertFalse(event.touches("content", "8"));
    assertFalse(event.touches("emails", "7"));
  }

  @Test
  public void sequenceNumbersAreMonotonic() {
    LocalEventBus bus = bus(10);
    long first = bus.emit("a.test", "t", "1", MutationEvent.Kind.insert, null).seq();
    long second = bus.emit("a.test", "t", "2", MutationEvent.Kind.insert, null).seq();
    assertEquals(first + 1, second);
    assertEquals(2, bus.emitted());
  }

  @Test
  public void recentIsNewestFirst() {
    LocalEventBus bus = bus(10);
    for (int k = 1; k <= 5; k++) {
      bus.emit("a.test", "t", Integer.toString(k), MutationEvent.Kind.insert, null);
    }
    List<MutationEvent> recent = bus.recent();
    assertEquals(5, recent.size());
    assertEquals("5", recent.get(0).key());
    assertEquals("1", recent.get(4).key());
  }

  @Test
  public void theBufferOverwritesRatherThanGrowing() {
    LocalEventBus bus = bus(3);
    for (int k = 1; k <= 10; k++) {
      bus.emit("a.test", "t", Integer.toString(k), MutationEvent.Kind.insert, null);
    }
    List<MutationEvent> recent = bus.recent();
    // a debugging aid, not a log: losing the oldest is the intended behaviour
    assertEquals(3, recent.size());
    assertEquals("10", recent.get(0).key());
    assertEquals("8", recent.get(2).key());
    assertEquals("but the counter still knows how many there were", 10, bus.emitted());
  }

  @Test
  public void recentRespectsALimit() {
    LocalEventBus bus = bus(100);
    for (int k = 0; k < 20; k++) {
      bus.emit("a.test", "t", Integer.toString(k), MutationEvent.Kind.insert, null);
    }
    assertEquals(5, bus.recent(5).size());
    assertEquals("an empty buffer yields nothing rather than nulls", 0, bus(10).recent(5).size());
  }

  @Test
  public void listenersSeeEveryEventInOrder() {
    LocalEventBus bus = bus(10);
    List<String> seen = new CopyOnWriteArrayList<>();
    bus.subscribe(event -> seen.add(event.key()));
    bus.subscribe(event -> seen.add("second:" + event.key()));
    bus.emit("a.test", "t", "1", MutationEvent.Kind.insert, null);
    assertEquals(List.of("1", "second:1"), seen);
    assertEquals(2, bus.listenerCount());
  }

  @Test
  public void aBrokenListenerDoesNotBreakTheWrite() {
    LocalEventBus bus = bus(10);
    List<String> seen = new ArrayList<>();
    bus.subscribe(event -> {
      throw new IllegalStateException("this listener is broken");
    });
    bus.subscribe(event -> seen.add(event.key()));
    bus.emit("a.test", "t", "1", MutationEvent.Kind.insert, null);
    assertEquals("the next listener still runs", List.of("1"), seen);
    assertEquals("and the event is still recorded", 1, bus.recent().size());
  }

  @Test
  public void theNullBusSwallowsEverything() {
    EventBus.NONE.subscribe(event -> {
      throw new IllegalStateException("never called");
    });
    EventBus.NONE.emit("a.test", "t", "1", MutationEvent.Kind.insert, null);
    assertTrue(EventBus.NONE.recent().isEmpty());
    assertEquals(0, EventBus.NONE.emitted());
  }
}
