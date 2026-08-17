package io.hearth.vhost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The virtual hosts, as a tree of DNS labels rooted at the top level domain.
 *
 * Configs are flat files on disk -- one "domain.cfg" per domain -- and this is the structure they
 * get loaded into. A tree because that is the shape of the question being asked: given
 * junior.example.org, walk from the top level domain down and find the most specific
 * configuration that applies.
 *
 *   (root)
 *     +-- com
 *     |     +-- example          <- example.org.cfg
 *     |           +-- junior      <- junior.example.org.cfg
 *     +-- localhost               <- localhost.cfg
 *
 * Resolution descends as far as the labels allow, remembering the deepest node that has a config
 * and applies. "Applies" means the node is the exact domain asked for, or it declared itself a
 * wildcard and so covers the subdomains beneath it. A node with no config of its own is just a
 * junction -- "com" exists in the tree above only because something lives under it.
 *
 * Built once at boot by {@link DomainScanner} and then read-only. Resolution allocates nothing
 * beyond the label split and never touches the filesystem.
 */
public class DomainTree {
  public static final DomainTree EMPTY = builder().build();
  private final Node root;
  private final Map<String, DomainConfig> byDomain;
  /**
   * Named subdomains, resolved to the config that claimed them.
   *
   * Kept beside the tree rather than inserted into it, because everything that walks `all()` --
   * the databases, the mailers, the notifier -- must see one entry per *community*. An alias in
   * there would give www.example.org a database of its own and a second set of accounts.
   */
  private final Map<String, DomainConfig> aliases;

  private DomainTree(Node root, Map<String, DomainConfig> byDomain,
                     Map<String, DomainConfig> aliases) {
    this.root = root;
    this.byDomain = byDomain;
    this.aliases = aliases;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** the most specific config covering this domain, or null when nothing covers it */
  public DomainConfig resolve(String domain) {
    if (domain == null || domain.isEmpty()) {
      return null;
    }
    // an explicitly named subdomain is the most specific answer there is: somebody wrote it down
    DomainConfig named = aliases.get(domain);
    if (named != null) {
      return named.enabled ? named : null;
    }
    String[] labels = split(domain);
    Node node = root;
    DomainConfig best = null;
    for (int k = labels.length - 1; k >= 0; k--) {
      node = node.children.get(labels[k]);
      if (node == null) {
        break;
      }
      if (node.config != null && (k == 0 || node.config.wildcard)) {
        // deeper is more specific, so a later hit always wins over an earlier one
        best = node.config;
      }
    }
    return best;
  }

  /**
   * Every hostname this server answers for by name: the configured domains and their listed
   * subdomains. What a certificate order needs, and what mail routing is allowed to accept for.
   */
  public java.util.List<String> hostnames() {
    java.util.TreeSet<String> names = new java.util.TreeSet<>(byDomain.keySet());
    names.addAll(aliases.keySet());
    return java.util.List.copyOf(names);
  }

  /** the subdomains this config claimed, as full hostnames */
  public java.util.List<String> aliasesOf(String domain) {
    java.util.ArrayList<String> found = new java.util.ArrayList<>();
    for (Map.Entry<String, DomainConfig> entry : aliases.entrySet()) {
      if (entry.getValue().domain.equals(domain)) {
        found.add(entry.getKey());
      }
    }
    java.util.Collections.sort(found);
    return found;
  }

  /** the config installed at exactly this domain, ignoring wildcards */
  public DomainConfig exact(String domain) {
    return domain == null ? null : byDomain.get(domain);
  }

  /**
   * The descent, step by step, for --verbose. This repeats the walk rather than instrumenting
   * resolve() so that the hot path stays a plain loop.
   */
  public List<String> explain(String domain) {
    ArrayList<String> lines = new ArrayList<>();
    if (domain == null || domain.isEmpty()) {
      lines.add("no usable host header; nothing to search for");
      return lines;
    }
    String[] labels = split(domain);
    Node node = root;
    String best = null;
    StringBuilder path = new StringBuilder();
    for (int k = labels.length - 1; k >= 0; k--) {
      if (path.length() > 0) {
        path.insert(0, '.');
      }
      path.insert(0, labels[k]);
      String at = path.toString();
      node = node.children.get(labels[k]);
      if (node == null) {
        lines.add("descend " + at + " -> no such branch; stopping");
        break;
      }
      if (node.config == null) {
        lines.add("descend " + at + " -> junction, no config here");
      } else if (k == 0) {
        lines.add("descend " + at + " -> config (exact) " + node.config.configFile.getName());
        best = at;
      } else if (node.config.wildcard) {
        lines.add("descend " + at + " -> config (wildcard) " + node.config.configFile.getName());
        best = at;
      } else {
        lines.add("descend " + at + " -> config found but wildcard=false, does not cover subdomains");
      }
    }
    lines.add(best == null ? "unresolved" : "most specific match: " + best);
    return lines;
  }

  public int size() {
    return byDomain.size();
  }

  public boolean isEmpty() {
    return byDomain.isEmpty();
  }

  /** every configured domain, sorted, for the boot report */
  public TreeMap<String, DomainConfig> all() {
    return new TreeMap<>(byDomain);
  }

  /** "junior.example.org" -> ["junior", "example", "com"] */
  static String[] split(String domain) {
    return domain.split("\\.", -1);
  }

  private static class Node {
    final Map<String, Node> children = new HashMap<>();
    DomainConfig config;
  }

  /** collects configs and wires up the tree; {@link #build()} freezes it */
  public static class Builder {
    private final Node root = new Node();
    private final HashMap<String, DomainConfig> byDomain = new HashMap<>();
    private final HashMap<String, DomainConfig> aliases = new HashMap<>();

    /** returns the config already installed at this domain, or null when the slot was free */
    public DomainConfig insert(DomainConfig config) {
      DomainConfig prior = byDomain.putIfAbsent(config.domain, config);
      if (prior != null) {
        return prior;
      }
      String[] labels = split(config.domain);
      Node node = root;
      for (int k = labels.length - 1; k >= 0; k--) {
        node = node.children.computeIfAbsent(labels[k], label -> new Node());
      }
      node.config = config;
      for (String label : config.subdomains) {
        // last writer would win silently, so the first claim keeps it and the scanner reports the
        // clash -- two configs quietly fighting over www is not something to discover in a log
        aliases.putIfAbsent(label + "." + config.domain, config);
      }
      return null;
    }

    /** a named subdomain that collides with a domain having its own config file */
    public java.util.List<String> aliasCollisions() {
      java.util.ArrayList<String> clashes = new java.util.ArrayList<>();
      for (Map.Entry<String, DomainConfig> entry : aliases.entrySet()) {
        DomainConfig owner = byDomain.get(entry.getKey());
        if (owner != null && owner != entry.getValue()) {
          clashes.add(entry.getKey() + " is listed under " + entry.getValue().domain
              + " and also has its own config");
        }
      }
      java.util.Collections.sort(clashes);
      return clashes;
    }

    public int size() {
      return byDomain.size();
    }

    public DomainTree build() {
      // a host with its own config file always wins over somebody else naming it as a subdomain
      HashMap<String, DomainConfig> resolved = new HashMap<>(aliases);
      resolved.keySet().removeAll(byDomain.keySet());
      return new DomainTree(root, Collections.unmodifiableMap(new HashMap<>(byDomain)),
          Collections.unmodifiableMap(resolved));
    }
  }
}
