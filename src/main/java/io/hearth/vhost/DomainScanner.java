package io.hearth.vhost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.common.ConfigException;
import io.hearth.common.ConfigObject;
import io.hearth.common.Verbose;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the --configs directory once at boot and builds the {@link DomainTree}.
 *
 * The directory is flat. One JSON file per virtual host, named for the domain it configures:
 *
 *   configs/
 *     localhost.cfg
 *     example.org.cfg
 *     junior.example.org.cfg
 *
 * Flat on disk because that is the easiest thing to look at, edit, diff, and rsync -- `ls configs`
 * is the list of everything this server will serve. The label tree that answers requests is built
 * in memory from these names; see {@link DomainTree}.
 *
 * The scan is deliberately unforgiving. A malformed config, an unreadable file, a symlink, an
 * unknown key, a filename that isn't a valid domain -- all of these stop the server from starting.
 * An operator would rather see a refusal at boot than discover at 2am that a domain has been
 * serving a config that half-parsed.
 */
public class DomainScanner {
  public static final String CONFIG_SUFFIX = ".cfg";
  /** a backstop against being pointed at the wrong directory */
  public static final int MAX_CONFIGS = 4096;
  /** a domain config is a handful of keys; anything larger is not a config */
  public static final long MAX_CONFIG_BYTES = 256 * 1024;

  private final Verbose verbose;
  private final ObjectMapper mapper;
  private final DomainTree.Builder tree;
  private final List<String> warnings;
  /** what config.cfg says the clock is; every domain inherits it unless it says otherwise */
  private final java.time.ZoneId defaultZone;

  private DomainScanner(Verbose verbose, java.time.ZoneId defaultZone) {
    this.verbose = verbose;
    this.mapper = new ObjectMapper();
    this.tree = DomainTree.builder();
    this.warnings = new ArrayList<>();
    this.defaultZone = defaultZone == null ? java.time.ZoneId.systemDefault() : defaultZone;
  }

  /** load every *.cfg in configsRoot; throws rather than returning a partial tree */
  public static Result scan(File configsRoot, Verbose verbose) throws ConfigException {
    return scan(configsRoot, java.time.ZoneId.systemDefault(), verbose);
  }

  /**
   * @param defaultZone the clock from `config.cfg`, which every domain inherits unless it says
   *     otherwise. It arrives here rather than being read per file because the server's config is
   *     already open by the time the scan runs, and two readers of one setting eventually disagree.
   */
  public static Result scan(File configsRoot, java.time.ZoneId defaultZone, Verbose verbose)
      throws ConfigException {
    DomainScanner scanner = new DomainScanner(verbose, defaultZone);
    File root = scanner.validateRoot(configsRoot);
    verbose.say("scanning configs directory " + root);
    scanner.load(root);
    verbose.say("scan complete: " + scanner.tree.size() + " domain(s)");
    // Two configs claiming one hostname is a boot-time refusal, not a warning: whichever won would
    // do so by hash order, and a community discovering its subdomain belongs to somebody else is
    // the sort of thing that should happen before the socket opens.
    List<String> clashes = scanner.tree.aliasCollisions();
    if (!clashes.isEmpty()) {
      throw new ConfigException("subdomain conflict: " + String.join("; ", clashes));
    }
    return new Result(root, scanner.tree.build(), scanner.warnings);
  }

  private File validateRoot(File configsRoot) throws ConfigException {
    if (configsRoot == null) {
      throw new ConfigException("--configs is required");
    }
    if (!configsRoot.exists()) {
      throw new ConfigException("configs directory does not exist: " + configsRoot);
    }
    if (!configsRoot.isDirectory()) {
      throw new ConfigException("configs path is not a directory: " + configsRoot);
    }
    if (Files.isSymbolicLink(configsRoot.toPath())) {
      throw new ConfigException("configs directory is a symlink, which we refuse to follow: " + configsRoot);
    }
    try {
      return configsRoot.getCanonicalFile();
    } catch (IOException ex) {
      throw new ConfigException("cannot resolve configs directory: " + configsRoot, ex);
    }
  }

  private void load(File root) throws ConfigException {
    File[] entries = root.listFiles();
    if (entries == null) {
      throw new ConfigException("cannot list configs directory: " + root);
    }
    // sort so that boot output and error messages are the same on every machine
    Arrays.sort(entries, (a, b) -> a.getName().compareTo(b.getName()));
    int count = 0;
    for (File entry : entries) {
      String name = entry.getName();
      if (entry.isDirectory()) {
        // the configs directory holds configs and only configs; content lives in the database
        verbose.detail("skipping directory " + name + "/");
        continue;
      }
      if (!name.endsWith(CONFIG_SUFFIX)) {
        verbose.detail("skipping " + name + " (not a " + CONFIG_SUFFIX + " file)");
        continue;
      }
      if (++count > MAX_CONFIGS) {
        throw new ConfigException("more than " + MAX_CONFIGS + " configs in " + root + "; refusing to load further");
      }
      loadOne(root, entry);
    }
  }

  private void loadOne(File root, File configFile) throws ConfigException {
    String name = configFile.getName();
    String domain = name.substring(0, name.length() - CONFIG_SUFFIX.length());
    if (domain.isEmpty()) {
      throw new ConfigException(configFile + ": a config must be named after its domain");
    }
    if (!Hosts.isValidDomain(domain)) {
      throw new ConfigException("'" + configFile + "' is not named after a valid domain; the name must be"
          + " lowercase dotted DNS labels followed by " + CONFIG_SUFFIX + ", e.g. junior.example.org" + CONFIG_SUFFIX);
    }
    if (!configFile.isFile()) {
      throw new ConfigException(configFile + " is not a regular file");
    }
    if (Files.isSymbolicLink(configFile.toPath())) {
      throw new ConfigException(configFile + " is a symlink, which we refuse to follow");
    }
    if (configFile.length() > MAX_CONFIG_BYTES) {
      throw new ConfigException(configFile + " is larger than " + MAX_CONFIG_BYTES + " bytes; that is not a config");
    }
    ObjectNode node = parse(configFile);
    DomainConfig config =
        DomainConfig.of(domain, root, configFile, new ConfigObject(node, name), defaultZone);
    DomainConfig prior = tree.insert(config);
    if (prior != null) {
      // filenames are unique per directory, so this needs a case-insensitive filesystem or a
      // future non-flat layout to happen at all; fail loudly if it ever does
      throw new ConfigException("two configs claim '" + domain + "': " + prior.configFile.getName() + " and " + name);
    }
    verbose.say("loaded " + domain + " from " + name);
    verbose.detail("name=" + config.name + " enabled=" + config.enabled + " wildcard=" + config.wildcard);
    if (!config.enabled) {
      warnings.add(name + " has enabled=false; " + domain + " will be refused as though it had no config");
    }
  }

  private ObjectNode parse(File configFile) throws ConfigException {
    try {
      JsonNode parsed = mapper.readTree(configFile);
      if (parsed == null || !parsed.isObject()) {
        throw new ConfigException(configFile + " must contain a JSON object");
      }
      return (ObjectNode) parsed;
    } catch (IOException ex) {
      throw new ConfigException(configFile + " is not readable JSON: " + ex.getMessage(), ex);
    }
  }

  /** what a scan produced: where it looked, what it loaded, and what looked off but wasn't fatal */
  public static class Result {
    public final File root;
    public final DomainTree tree;
    public final List<String> warnings;

    public Result(File root, DomainTree tree, List<String> warnings) {
      this.root = root;
      this.tree = tree;
      this.warnings = warnings;
    }
  }
}
