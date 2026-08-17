package io.hearth.store;

import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainTree;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every database this process owns, keyed by the domain that asked for it.
 *
 * One database per domain by default. A domain that sets "use_database_domain" points at another
 * domain's database instead, which is how a family of sites shares one set of accounts -- log in
 * once at example.org and junior.example.org knows who you are, because there is literally one
 * emails table behind both.
 *
 * Delegation is one level only. If a.com delegates to b.com, then b.com must own its database and
 * not delegate onward. Chains would make "which database am I actually on" a question requiring a
 * traversal, and a cycle would make it unanswerable; refusing at boot is cheaper than either.
 *
 * Opened once at startup, before the socket. After that the map is immutable and every lookup is
 * a hash hit.
 */
public class Stores implements AutoCloseable {
  public final File root;
  private final Map<String, Store> byDomain;
  private final Map<String, Store> byDatabase;

  private Stores(File root, Map<String, Store> byDomain, Map<String, Store> byDatabase) {
    this.root = root;
    this.byDomain = Collections.unmodifiableMap(byDomain);
    this.byDatabase = Collections.unmodifiableMap(byDatabase);
  }

  /** empty, for tests and for the paths that never touch a database */
  public static Stores none() {
    return new Stores(null, Map.of(), Map.of());
  }

  /**
   * Open one database per distinct database-domain in the tree, then map every domain onto the
   * store it should use.
   */
  public static Stores open(File storesRoot, DomainTree tree, Verbose verbose) throws ConfigException {
    return open(storesRoot, tree, EventBus.NONE, verbose);
  }

  public static Stores open(File storesRoot, DomainTree tree, EventBus events, Verbose verbose) throws ConfigException {
    File root = validateRoot(storesRoot);
    verbose.say("stores path " + root);
    TreeMap<String, DomainConfig> domains = tree.all();

    // resolve delegation first so that a bad pointer fails before any file is touched
    LinkedHashMap<String, String> databaseOf = new LinkedHashMap<>();
    TreeSet<String> databases = new TreeSet<>();
    for (Map.Entry<String, DomainConfig> entry : domains.entrySet()) {
      DomainConfig config = entry.getValue();
      String target = config.useDatabaseDomain;
      if (target == null) {
        databaseOf.put(config.domain, config.domain);
        databases.add(config.domain);
        continue;
      }
      DomainConfig owner = tree.exact(target);
      if (owner == null) {
        throw new ConfigException(config.configFile.getName() + ": use_database_domain points at '" + target
            + "', which has no config of its own; it must be a domain this server serves");
      }
      if (target.equals(config.domain)) {
        throw new ConfigException(config.configFile.getName() + ": use_database_domain points at itself");
      }
      if (owner.useDatabaseDomain != null) {
        throw new ConfigException(config.configFile.getName() + ": use_database_domain points at '" + target
            + "', which itself delegates to '" + owner.useDatabaseDomain + "'; delegation is one level only");
      }
      databaseOf.put(config.domain, target);
      databases.add(target);
      verbose.detail(config.domain + " shares the database of " + target);
    }

    HashMap<String, Store> byDatabase = new HashMap<>();
    try {
      for (String database : databases) {
        byDatabase.put(database, Store.open(root, database, events, verbose));
      }
    } catch (SchemaException ex) {
      for (Store store : byDatabase.values()) {
        store.close();
      }
      throw new ConfigException(ex.getMessage(), ex);
    }

    HashMap<String, Store> byDomain = new HashMap<>();
    for (Map.Entry<String, String> entry : databaseOf.entrySet()) {
      byDomain.put(entry.getKey(), byDatabase.get(entry.getValue()));
    }
    verbose.say("opened " + byDatabase.size() + " database(s) for " + byDomain.size() + " domain(s)");
    return new Stores(root, byDomain, byDatabase);
  }

  private static File validateRoot(File storesRoot) throws ConfigException {
    if (storesRoot == null) {
      throw new ConfigException("--stores is required; it names the directory holding the database files");
    }
    if (!storesRoot.exists()) {
      // unlike configs, this one we create: an empty stores path on a fresh install is normal
      if (!storesRoot.mkdirs()) {
        throw new ConfigException("could not create the stores directory: " + storesRoot);
      }
    }
    if (!storesRoot.isDirectory()) {
      throw new ConfigException("stores path is not a directory: " + storesRoot);
    }
    if (Files.isSymbolicLink(storesRoot.toPath())) {
      throw new ConfigException("stores path is a symlink, which we refuse to follow: " + storesRoot);
    }
    try {
      return storesRoot.getCanonicalFile();
    } catch (IOException ex) {
      throw new ConfigException("cannot resolve the stores path: " + storesRoot, ex);
    }
  }

  /** the database a domain should read and write; null when it has none */
  public Store forDomain(String domain) {
    return byDomain.get(domain);
  }

  public int databaseCount() {
    return byDatabase.size();
  }

  public int domainCount() {
    return byDomain.size();
  }

  /** every audit, sorted by database domain, for the boot report */
  public List<Store.Audit> audits() {
    ArrayList<Store.Audit> audits = new ArrayList<>();
    for (String database : new TreeSet<>(byDatabase.keySet())) {
      audits.add(byDatabase.get(database).audit());
    }
    return audits;
  }

  /** which domains share each database, for the boot report */
  public TreeMap<String, List<String>> sharing() {
    TreeMap<String, List<String>> sharing = new TreeMap<>();
    for (Map.Entry<String, Store> entry : byDomain.entrySet()) {
      sharing.computeIfAbsent(entry.getValue().databaseDomain, key -> new ArrayList<>()).add(entry.getKey());
    }
    for (List<String> users : sharing.values()) {
      Collections.sort(users);
    }
    return sharing;
  }

  @Override
  public void close() {
    for (Store store : byDatabase.values()) {
      store.close();
    }
  }
}
