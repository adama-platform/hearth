package io.hearth.cli;

import io.hearth.common.ConfigException;

import java.io.File;

/**
 * The one directory everything lives under.
 *
 * There used to be three flags for three directories and no way to be sure they belonged to the
 * same installation -- you could point `--stores` at one deployment's databases and `--configs` at
 * another's, and nothing would notice until the wrong people could sign in. One root removes that
 * class of mistake entirely, and it makes the command short enough to type from memory:
 *
 * <pre>
 *   java -jar hearth.jar --root /var/hearth
 *
 *   /var/hearth/
 *     config.cfg      the server itself: ports, TLS, limits
 *     domains/        one .cfg per virtual host, named for its domain
 *     dbs/            one H2 database per domain
 *     certs/          the ACME account, and a key and chain per domain
 * </pre>
 *
 * The subdirectories are created on demand, because a fresh install should be one `mkdir` and one
 * command rather than a checklist. `config.cfg` is not created: an absent one means "every default",
 * which is a legitimate and common state, and writing one silently would make `--setup` look like it
 * had already been run.
 */
public class Root {
  public static final String CONFIG_FILE = "config.cfg";
  private static final String ATTACHMENTS_DIR = "attachments";
  public static final String DOMAINS_DIR = "domains";
  public static final String DATABASES_DIR = "dbs";
  public static final String CERTS_DIR = "certs";

  private final File root;

  private Root(File root) {
    this.root = root;
  }

  /**
   * Resolve the root, creating what is missing.
   *
   * Refuses rather than guesses when the path is a file, or cannot be created. A server that
   * quietly invented a second empty installation next to the real one would be worse than one that
   * did not start.
   */
  public static Root open(File root) throws ConfigException {
    if (root == null) {
      throw new ConfigException("--root is required; it names the directory everything lives under");
    }
    if (root.exists() && !root.isDirectory()) {
      throw new ConfigException("--root " + root + " is a file, not a directory");
    }
    if (!root.exists() && !root.mkdirs()) {
      throw new ConfigException("--root " + root + " could not be created");
    }
    Root opened = new Root(root);
    opened.mkdir(opened.domains());
    opened.mkdir(opened.databases());
    opened.mkdir(opened.certs());
    opened.mkdir(opened.attachments());
    return opened;
  }

  /** resolve without creating anything; for the walkthroughs, which report before they write */
  public static Root at(File root) {
    return new Root(root);
  }

  private void mkdir(File dir) throws ConfigException {
    if (dir.isDirectory()) {
      return;
    }
    if (!dir.mkdirs()) {
      throw new ConfigException("could not create " + dir);
    }
  }

  public File dir() {
    return root;
  }

  /** the server's own settings; may not exist, which means every default */
  public File configFile() {
    return new File(root, CONFIG_FILE);
  }

  public boolean hasConfig() {
    return configFile().isFile();
  }

  /** one .cfg per virtual host */
  public File domains() {
    return new File(root, DOMAINS_DIR);
  }

  public File domainFile(String domain) {
    return new File(domains(), safe(domain) + ".cfg");
  }

  /** H2 files, one per domain */
  public File databases() {
    return new File(root, DATABASES_DIR);
  }

  /** the ACME account and every certificate */
  public File certs() {
    return new File(root, CERTS_DIR);
  }

  /**
   * Uploaded files, which are the third thing on disk and the largest.
   *
   * Invariant 7 says the disk is for startup, with the database and the certificate cache as the
   * two named exceptions. This is the third, and it is the one that needed arguing about: a
   * photograph does not belong in an H2 row, where it would be read into memory to be served,
   * copied by every backup of the database, and impossible to hand to a web server later. It is a
   * directory of files that nothing on the request path scans -- a path is computed from an id.
   */
  public File attachments() {
    return new File(root, ATTACHMENTS_DIR);
  }

  /** what the boot report prints, so an operator can see they pointed at the right place */
  public String describe() {
    return root.getAbsolutePath();
  }

  /**
   * A domain is a filename here, and a filename built from input is still built from input.
   *
   * `--domain-setup ../../etc/passwd` must not write there, so anything that could leave the
   * domains directory is refused rather than cleaned up.
   */
  static String safe(String domain) {
    if (domain == null || domain.isEmpty() || domain.length() > 253
        || domain.contains("/") || domain.contains("\\") || domain.contains("..")
        || domain.startsWith(".") || domain.contains(File.separator)) {
      throw new IllegalArgumentException("refusing to build a path from the domain '" + domain + "'");
    }
    return domain;
  }
}
