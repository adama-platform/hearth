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
  public static final AuthSystem EMPTY = new AuthSystem(Map.of(), Map.of(), null);
  private final Map<String, Accounts> byDomain;
  private final Map<String, Accounts> byDatabase;
  /** kept so a settings write can put the rebuilt config back where every reader will find it */
  private final DomainTree tree;

  private AuthSystem(Map<String, Accounts> byDomain, Map<String, Accounts> byDatabase,
                     DomainTree tree) {
    this.byDomain = Collections.unmodifiableMap(byDomain);
    this.byDatabase = Collections.unmodifiableMap(byDatabase);
    this.tree = tree;
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
    return new AuthSystem(byDomain, byDatabase, tree);
  }

  /**
   * Rebuild a domain's config from its file plus what the community has decided, and install it.
   *
   * Called once per domain at boot, after the settings are loaded, and again after every write from
   * the admin section. Everything expensive -- reading rows, writing them into a copy of the file's
   * JSON, parsing and cross-checking the result -- happens here, on the write, so that a reader on
   * the request path still just takes a reference to a finished object.
   *
   * A settings row that will not parse is the one case worth being careful about. It cannot come
   * from the editor, which rebuilds before it commits, but it can come from somebody editing the
   * database by hand -- and the honest answer there is to keep serving the file's configuration and
   * say so, rather than to refuse to boot over a value that is only cosmetic. A community whose
   * tagline is malformed should not be a community that is down.
   */
  public DomainConfig applySettings(String domain, io.hearth.common.Verbose verbose) {
    if (tree == null) {
      return null;
    }
    DomainConfig config = tree.exact(domain);
    Accounts accounts = byDomain.get(domain);
    if (config == null || accounts == null) {
      return config;
    }
    java.util.Map<String, String> overrides = accounts.settings.overrides();
    if (overrides.isEmpty()) {
      return config;
    }
    try {
      DomainConfig rebuilt = config.with(overrides);
      tree.replace(rebuilt);
      // and the clock, which is the one value something else holds a copy of. A shared database
      // takes the owning domain's, exactly as it takes its login policy and its admin list.
      if (accounts.databaseDomain.equals(domain)) {
        accounts.clockIs(rebuilt.zone);
      }
      return rebuilt;
    } catch (io.hearth.common.ConfigException ex) {
      if (verbose != null) {
        verbose.say("settings for " + domain + " could not be applied (" + ex.getMessage()
            + "); serving what the config file says");
      }
      return config;
    }
  }

  /** every domain, at boot, once the settings tables have been read */
  public void applyAllSettings(io.hearth.common.Verbose verbose) {
    // every write from the admin section rebuilds and swaps, without the handler having to
    // remember to -- the same reason a mutation event comes from the DAO rather than the caller
    for (Map.Entry<String, Accounts> entry : byDomain.entrySet()) {
      String domain = entry.getKey();
      entry.getValue().settings.onChange(() -> applyForDatabaseOf(domain, verbose));
    }
    for (String domain : byDomain.keySet()) {
      applySettings(domain, verbose);
    }
  }

  /**
   * Re-apply every domain sharing one database.
   *
   * A shared database is one account space and one set of settings, so a change made on one of its
   * domains is a change to all of them. Rebuilding only the domain whose screen was open would
   * leave the others serving the old answer until a restart -- and "signing in here signs you in
   * there" already tells everybody these are one community.
   */
  private void applyForDatabaseOf(String domain, io.hearth.common.Verbose verbose) {
    Accounts accounts = byDomain.get(domain);
    if (accounts == null) {
      return;
    }
    for (Map.Entry<String, Accounts> entry : byDomain.entrySet()) {
      if (entry.getValue() == accounts) {
        applySettings(entry.getKey(), verbose);
      }
    }
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

  /** the account spaces, for anything that has to walk them once */
  public java.util.Collection<Accounts> databases() {
    return byDatabase.values();
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
