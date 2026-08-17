package io.hearth.events;

/**
 * Something that wants to know when a row changed.
 *
 * Called synchronously on the thread that did the mutation, so an implementation must be fast and
 * must not throw -- the bus catches and logs, but a listener that blocks is a listener that slows
 * down every write in the process. Caches invalidating an entry is the intended shape; anything
 * heavier belongs behind a queue.
 */
@FunctionalInterface
public interface EventListener {
  void onMutation(MutationEvent event);
}
