package io.hearth.auth;

import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.store.Store;
import io.hearth.store.Stores;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainTree;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps a domain onto the {@link Accounts} it should use.
 *
 * Built once, after the stores are open and before the socket is. Every request-time lookup is a
 * hash hit against an immutable map.
 */
public class AuthSystem implements AutoCloseable {
  public static final AuthSystem EMPTY = new AuthSystem(Map.of(), Map.of());
  private final Map<String, Accounts> byDomain;
  private final Map<String, Accounts> byDatabase;

  private AuthSystem(Map<String, Accounts> byDomain, Map<String, Accounts> byDatabase) {
    this.byDomain = Collections.unmodifiableMap(byDomain);
    this.byDatabase = Collections.unmodifiableMap(byDatabase);
  }

  public static AuthSystem of(Stores stores, DomainTree tree, Verbose verbose) {
    return of(stores, tree, EventBus.NONE, verbose);
  }

  public static AuthSystem of(Stores stores, DomainTree tree, EventBus events, Verbose verbose) {
    HashMap<String, Accounts> byDatabase = new HashMap<>();
    HashMap<String, Accounts> byDomain = new HashMap<>();
    for (Map.Entry<String, DomainConfig> entry : tree.all().entrySet()) {
      DomainConfig config = entry.getValue();
      Store store = stores.forDomain(config.domain);
      if (store == null) {
        continue;
      }
      Accounts accounts = byDatabase.computeIfAbsent(store.databaseDomain, database -> {
        // a shared database is one account space, so the owning domain's policy and admin list
        // govern it; two answers to "who is an admin here" would be one answer too many
        DomainConfig owner = tree.exact(database);
        DomainConfig governing = owner == null ? config : owner;
        LoginSecurity security = governing.loginSecurity;
        java.util.Set<String> admins = new java.util.TreeSet<>(governing.adminEmails);
        verbose.say("accounts for " + database + ": " + security.describe()
            + (admins.isEmpty() ? ", no bootstrap admins" : ", bootstrap admins " + admins));
        // the owning domain's clock too, for the same reason as its policy: one account space
        // cannot have two answers to what "today" is
        return new Accounts(store, database, security, admins, governing.caches, events,
            governing.zone, verbose);
      });
      byDomain.put(config.domain, accounts);
    }
    return new AuthSystem(byDomain, byDatabase);
  }

  /** the account space a domain lives in, or null when it has no database */
  public Accounts forDomain(String domain) {
    return byDomain.get(domain);
  }

  /** start every reaper; called once the server is about to accept traffic */
  public void start() {
    for (Accounts accounts : byDatabase.values()) {
      accounts.start();
    }
  }

  public int size() {
    return byDatabase.size();
  }

  /** the governing policy per database, for the boot report */
  public TreeMap<String, LoginSecurity> policies() {
    TreeMap<String, LoginSecurity> policies = new TreeMap<>();
    for (Map.Entry<String, Accounts> entry : byDatabase.entrySet()) {
      policies.put(entry.getKey(), entry.getValue().security);
    }
    return policies;
  }

  @Override
  public void close() {
    for (Accounts accounts : byDatabase.values()) {
      accounts.shutdown();
    }
  }
}
