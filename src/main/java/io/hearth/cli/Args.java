package io.hearth.cli;

import java.io.File;

/**
 * The command line, which is deliberately almost nothing.
 *
 * There used to be a flag per directory and a flag per port, which meant the command that started a
 * production server was long enough that nobody typed it -- it lived in a service file somebody
 * wrote once and was then afraid to touch. Worse, three independent paths could point at three
 * different installations and nothing would notice until the wrong people could sign in.
 *
 * So: one required flag, `--root`, and everything else in `config.cfg` under it. What is left here
 * is the things that cannot live in a file because they are about *this run* rather than this
 * installation -- a walkthrough, a check, a test send.
 *
 * <pre>
 *   java -jar hearth.jar --root /var/hearth
 * </pre>
 *
 * Unknown flags are an error rather than a shrug, and the flags that were removed are named
 * explicitly: somebody upgrading has them in a service file, and quietly ignoring `--http-port`
 * would mean serving on a different port than the command says.
 */
public class Args {
  public final File root;
  public final boolean verbose;
  /** load everything, report, and exit without binding */
  public final boolean check;
  /** walk through config.cfg */
  public final boolean setup;
  /** walk through one domain's config, or null */
  public final String domainSetup;
  /** walk through the certificate authority account */
  public final boolean setupCerts;
  /** walk through one domain's email, or null */
  public final String setupEmail;
  /** walk through the geocoding service for the address book */
  public final boolean setupGps;
  /** turn a directory into a systemd service: where, or null */
  public final String install;
  /** send one test message: which domain, or null */
  public final String testEmailDomain;
  /** and to whom */
  public final String testEmailTo;
  public final boolean help;
  public final boolean version;

  private Args(File root, boolean verbose, boolean check, boolean setup, String domainSetup,
               boolean setupCerts, String setupEmail, boolean setupGps, String testEmailDomain,
               String testEmailTo, String install, boolean help, boolean version) {
    this.install = install;
    this.root = root;
    this.verbose = verbose;
    this.check = check;
    this.setup = setup;
    this.domainSetup = domainSetup;
    this.setupCerts = setupCerts;
    this.setupEmail = setupEmail;
    this.setupGps = setupGps;
    this.testEmailDomain = testEmailDomain;
    this.testEmailTo = testEmailTo;
    this.help = help;
    this.version = version;
  }

  /** does this run do one thing and exit, rather than serve? */
  public boolean isOneShot() {
    return check || setup || domainSetup != null || setupCerts || setupEmail != null || setupGps
        || testEmailDomain != null;
  }

  public static Args parse(String[] args) throws ArgsException {
    File root = null;
    boolean verbose = false;
    boolean check = false;
    boolean setup = false;
    String domainSetup = null;
    boolean setupCerts = false;
    boolean setupGps = false;
    String setupEmail = null;
    String testEmailDomain = null;
    String testEmailTo = null;
    String install = null;
    boolean help = args.length == 0;
    boolean version = false;

    for (int k = 0; k < args.length; k++) {
      String arg = args[k];
      switch (arg) {
        case "--root" -> root = new File(value(args, k++, "--root"));
        case "--verbose", "-v" -> verbose = true;
        case "--check" -> check = true;
        case "--setup" -> setup = true;
        case "--domain-setup" -> domainSetup = value(args, k++, "--domain-setup");
        case "--setup-certs" -> setupCerts = true;
        case "--setup-email" -> setupEmail = value(args, k++, "--setup-email");
        case "--setup-gps" -> setupGps = true;
        case "--test-email" -> {
          testEmailDomain = value(args, k++, "--test-email");
          testEmailTo = value(args, k++, "--test-email");
        }
        case "--install" -> install = value(args, k++, "--install");
        case "--help", "-h" -> help = true;
        case "--version" -> version = true;
        case "--configs", "--stores", "--certs" -> throw new ArgsException(
            arg + " is gone: everything lives under one --root now, and " + arg.substring(2)
                + " is a directory inside it");
        case "--port", "--http-port", "--https-port", "--enable-https", "--http-bounce-port",
             "--bind" -> throw new ArgsException(
            arg + " is gone: ports and binding live in config.cfg under --root. Run --setup.");
        case "--do-cert-setup" -> throw new ArgsException("--do-cert-setup is now --setup-certs");
        default -> throw new ArgsException("unknown argument: " + arg);
      }
    }

    // --install is about a directory on this machine rather than about a running server, and
    // asking for --root as well would be asking twice for the same answer: the root it writes is
    // inside the directory being installed into
    if (!help && !version && install == null && root == null) {
      throw new ArgsException("--root is required; it names the directory everything lives under");
    }
    int steps = (setup ? 1 : 0) + (domainSetup != null ? 1 : 0) + (setupCerts ? 1 : 0)
        + (setupEmail != null ? 1 : 0) + (testEmailDomain != null ? 1 : 0);
    if (steps > 1) {
      throw new ArgsException("run one setup step at a time");
    }
    if (install != null && steps > 0) {
      throw new ArgsException("--install writes files and stops; run a setup step afterwards");
    }
    return new Args(root, verbose, check, setup, domainSetup, setupCerts, setupEmail, setupGps,
        testEmailDomain, testEmailTo, install, help, version);
  }

  private static String value(String[] args, int at, String flag) throws ArgsException {
    if (at + 1 >= args.length) {
      throw new ArgsException(flag + " needs a value");
    }
    String value = args[at + 1];
    if (value.startsWith("--")) {
      throw new ArgsException(flag + " needs a value, but the next thing is " + value);
    }
    return value;
  }

  public static class ArgsException extends Exception {
    public ArgsException(String message) {
      super(message);
    }
  }

  public static String usage() {
    return """
        Hearth -- a single jar community server

        usage: java -jar hearth.jar --root <dir>

          --root <dir>      the one directory everything lives under; created if absent
          --verbose, -v     narrate the config scan and every request decision
          --check           load everything, report, and exit; never opens a socket
          --help, -h        this text
          --version         print the version and exit
          --install <dir>   write a systemd service into <dir> and stop; needs no root

        setting up, one step at a time:

          --setup                     the server itself: ports, TLS, HTTP/2
          --domain-setup <domain>     add or edit a community
          --setup-certs               register with a certificate authority
          --setup-gps                 choose a geocoding service for the address book
          --setup-email <domain>      send real email through Amazon SES
          --test-email <domain> <to>  send one message and report what happened

        everything lives under the root:

          /var/hearth/
            config.cfg      ports, TLS, limits; every setting has a working default
            domains/        one .cfg per virtual host, named for its domain
            dbs/            one database per domain
            certs/          the certificate authority account, and a key and chain per domain

        a request resolves to the most specific config that covers its host. junior.example.org
        gets its own; www.example.org falls back to example.org if that config sets
        "wildcard": true. a domain with no config is not served.
        """;
  }
}
