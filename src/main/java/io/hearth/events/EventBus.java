package io.hearth.events;

import java.util.List;

/**
 * Where mutations are announced.
 *
 * This interface exists so that the in-process implementation can be replaced by one that talks to
 * a shared service without anything above it changing. That is the whole scaling story for this
 * server: put a sticky load balancer in front of several processes, and the thing that breaks first
 * is cache coherence -- process A updates a page and process B keeps serving the old one. Making
 * every cache invalidation flow through an interface now means the fix later is one implementation
 * rather than an audit of every cache.
 *
 * {@link #recent} is what makes it debuggable. An operator looking at a stale page wants to know
 * whether the invalidation happened at all, and the answer should not require attaching a debugger.
 */
public interface EventBus {
  /** announce a change; never throws, and never lets a listener's failure reach the caller */
  void emit(MutationEvent event);

  /** convenience: stamp sequence and time, then emit */
  MutationEvent emit(String domain, String table, String key, MutationEvent.Kind kind, Long actor);

  /** register a listener for the life of the process; there is deliberately no unsubscribe yet */
  void subscribe(EventListener listener);

  /** the most recent events, newest first, up to what the buffer holds */
  List<MutationEvent> recent(int limit);

  /** everything the buffer holds, newest first */
  List<MutationEvent> recent();

  /** total events emitted since boot, which is more than the buffer holds */
  long emitted();

  /** how many events the inspector can show */
  int capacity();

  /** a bus that does nothing, for tests and for paths with no database */
  EventBus NONE = new EventBus() {
    @Override
    public void emit(MutationEvent event) {
    }

    @Override
    public MutationEvent emit(String domain, String table, String key, MutationEvent.Kind kind, Long actor) {
      return new MutationEvent(0, 0, domain, table, key, kind, actor);
    }

    @Override
    public void subscribe(EventListener listener) {
    }

    @Override
    public List<MutationEvent> recent(int limit) {
      return List.of();
    }

    @Override
    public List<MutationEvent> recent() {
      return List.of();
    }

    @Override
    public long emitted() {
      return 0;
    }

    @Override
    public int capacity() {
      return 0;
    }
  };
}
