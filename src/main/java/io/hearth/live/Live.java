package io.hearth.live;

import io.hearth.common.Verbose;
import io.hearth.events.EventBus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One hub per community, built at boot and never rebuilt.
 *
 * The same shape as {@link io.hearth.mail.Mailers}: the thing upstream asks for a domain and gets
 * the right one, and nothing upstream has to know there are several. Per community rather than per
 * database, unlike almost everything else here -- two domains sharing an account space still have
 * two rooms, two sets of channels and two conversations, and a presence dot that lit up on one
 * because somebody was reading the other would be a lie about where they are.
 */
public class Live {
  private final Map<String, LiveHub> hubs = new ConcurrentHashMap<>();
  private final Verbose verbose;

  public Live(Verbose verbose) {
    this.verbose = verbose;
  }

  /** wire every configured domain to the event bus once, before the socket opens */
  public static Live of(Iterable<String> domains, EventBus events, Verbose verbose) {
    Live live = new Live(verbose);
    for (String domain : domains) {
      live.forDomain(domain).listenTo(events);
    }
    return live;
  }

  public LiveHub forDomain(String domain) {
    return hubs.computeIfAbsent(domain, name -> new LiveHub(name, verbose));
  }

  /** every hub, for the sweep and the dashboard */
  public Map<String, LiveHub> all() {
    return new LinkedHashMap<>(hubs);
  }

  /** drop what has aged out of every hub; called from the pass that expires messages */
  public void sweep() {
    for (LiveHub hub : hubs.values()) {
      hub.sweep();
    }
  }

  public int connections() {
    int total = 0;
    for (LiveHub hub : hubs.values()) {
      total += hub.connections();
    }
    return total;
  }
}
