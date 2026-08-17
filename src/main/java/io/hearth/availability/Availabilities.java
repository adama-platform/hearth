package io.hearth.availability;

import io.hearth.auth.AuthSystem;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainTree;
import io.hearth.vhost.Surface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One grid per community, built at boot and kept current by one thread each.
 *
 * The same shape as {@link io.hearth.live.Live} and {@link io.hearth.mail.Mailers}: whatever is
 * upstream asks for a domain and gets the right one, and nothing upstream has to know there are
 * several. Per community rather than per database, because two domains sharing an account space are
 * still two communities deciding two different Tuesdays.
 */
public class Availabilities {
  private final Map<String, AvailabilityIndexer> indexers = new ConcurrentHashMap<>();

  private Availabilities() {
  }

  /** wire every configured domain before the socket opens */
  public static Availabilities of(DomainTree tree, AuthSystem auth, CalendarFetch.Fetcher fetcher,
                                  EventBus events, Verbose verbose) {
    Availabilities all = new Availabilities();
    for (DomainConfig config : tree.all().values()) {
      if (!config.has(Surface.availability)) {
        continue;
      }
      io.hearth.auth.Accounts accounts = auth.forDomain(config.domain);
      if (accounts == null) {
        continue;
      }
      all.indexers.put(config.domain,
          new AvailabilityIndexer(config, accounts, fetcher, events, verbose));
    }
    return all;
  }

  public AvailabilityIndexer forDomain(String domain) {
    return indexers.get(domain);
  }

  public Map<String, AvailabilityIndexer> all() {
    return new LinkedHashMap<>(indexers);
  }

  /** started after the databases are open, so the first build has something to read */
  public void start() {
    for (AvailabilityIndexer indexer : indexers.values()) {
      indexer.start();
    }
  }

  public void shutdown() {
    for (AvailabilityIndexer indexer : indexers.values()) {
      indexer.shutdown();
    }
  }
}
