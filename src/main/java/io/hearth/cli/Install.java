package io.hearth.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Turn a directory into a running service, on a machine that uses systemd.
 *
 * <b>This writes files somebody could have written by hand, and says what it wrote.</b> Same rule
 * as every other walkthrough here -- the point is that an operator can read the unit, disagree with
 * it, and edit it, rather than being handed a service that works for reasons nobody on the box
 * understands. It asks no questions, so unlike the walkthroughs it does not need a terminal: there
 * is nothing here for somebody to think about, and running it from a provisioning script is a
 * legitimate thing to want.
 *
 * <b>Nothing here needs root.</b> It writes into a directory the operator already owns and stops.
 * The half that does need root -- creating a user, installing the unit, enabling it -- is written
 * out as `install.sh` for them to read first and then run with sudo. A program that wanted root to
 * tell you what it was about to do would be a program you had to trust before you could check it.
 *
 * <b>Re-running is the deployment mechanism, not an accident.</b> A second `--install` with a newer
 * jar does not overwrite the running one: it stages `hearth.new.jar` beside it, and the start
 * script swaps it in on the next restart, keeping the previous one as `hearth.prev.jar`. So
 * deploying is "copy a jar, restart", rolling back is "move one file, restart", and a half-finished
 * upload cannot replace a working server -- the script checks the staged file is really a jar
 * before it moves anything.
 */
public final class Install {
  /** what a service is called when the directory does not suggest something better */
  public static final String DEFAULT_SERVICE = "hearth";
  /** the canonical "this machine was booted with systemd" check */
  private static final String SYSTEMD_MARKER = "/run/systemd/system";

  private Install() {
  }

  /** what happened, in the words the terminal wants */
  public record Report(boolean ok, String problem, List<String> wrote, String service,
                       boolean staged, File home) {
    static Report no(String problem) {
      return new Report(false, problem, List.of(), null, false, null);
    }
  }

  /**
   * The jar this JVM is running from, or null when it is running from loose classes.
   *
   * A development run and the test suite are both "loose classes", and installing from one would
   * copy a directory somewhere and produce a service that could never start -- so the answer is
   * null and the caller refuses out loud.
   */
  public static File currentJar() {
    try {
      java.security.CodeSource source = Install.class.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return null;
      }
      File file = new File(source.getLocation().toURI());
      return file.isFile() && file.getName().endsWith(".jar") ? file : null;
    } catch (Exception ex) {
      return null;
    }
  }

  /** is this a machine where a systemd unit would mean anything? */
  public static boolean hasSystemd() {
    return new File(SYSTEMD_MARKER).isDirectory();
  }

  /** what this box calls itself, for the report; "linux" when it will not say */
  public static String distribution() {
    File release = new File("/etc/os-release");
    if (!release.isFile()) {
      return System.getProperty("os.name", "unknown");
    }
    try {
      for (String line : Files.readAllLines(release.toPath(), StandardCharsets.UTF_8)) {
        if (line.startsWith("PRETTY_NAME=")) {
          return line.substring(12).replace("\"", "").trim();
        }
      }
    } catch (IOException ex) {
      // an unreadable os-release is not a reason to refuse to install
    }
    return System.getProperty("os.name", "linux");
  }

  /**
   * The service name for a directory.
   *
   * Taken from the directory rather than fixed, so two communities on one box are two services with
   * names somebody can tell apart -- `/srv/supper` is `supper.service`. Anything that is not a
   * plain name falls back, because a unit name with a slash in it is not a unit name.
   */
  public static String serviceNameFor(File home) {
    String raw = home.getName().toLowerCase(java.util.Locale.ROOT);
    StringBuilder out = new StringBuilder();
    for (char ch : raw.toCharArray()) {
      if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
        out.append(ch);
      }
    }
    String clean = out.toString();
    return clean.isEmpty() || clean.length() > 32 ? DEFAULT_SERVICE : clean;
  }

  public static Report run(File target, File jar) {
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
      return Report.no("--install writes a systemd service, and this is "
          + System.getProperty("os.name") + ". Nothing here would work.");
    }
    if (!hasSystemd()) {
      return Report.no("this machine does not look like it was booted with systemd (" + SYSTEMD_MARKER
          + " is not there), so a unit file would be an ornament. Everything Hearth needs to run is"
          + " one command -- java -jar hearth.jar --root <dir> -- under whatever supervises things"
          + " here.");
    }
    if (target == null) {
      return Report.no("--install needs the directory to install into");
    }
    File home = target.getAbsoluteFile();
    // Deliberately not created for them. An install path is a decision about where a community's
    // database lives, and a typo that silently makes /hearthh is a server nobody can find later.
    if (!home.isDirectory()) {
      return Report.no(home + " is not a directory. Make it first (mkdir -p " + home + "),"
          + " which is also the moment to decide whether it is on a disk you back up.");
    }
    if (!home.canWrite()) {
      return Report.no("cannot write to " + home + "; run this as somebody who can");
    }
    if (jar == null || !jar.isFile()) {
      return Report.no("this is running from loose classes rather than from a jar, so there is"
          + " nothing to install. Build one (just package) and run --install from it.");
    }

    String service = serviceNameFor(home);
    ArrayList<String> wrote = new ArrayList<>();
    boolean staged;
    try {
      File installed = new File(home, "hearth.jar");
      staged = placeJar(jar, installed, wrote);
      Files.createDirectories(new File(home, "data").toPath());
      wrote.add("data/ -- the --root directory: config.cfg, domains/, dbs/, certs/");

      write(new File(home, "run.sh"), runScript(home), true, wrote,
          "what systemd runs; it swaps in a staged jar before starting");
      write(new File(home, service + ".service"), unit(home, service), false, wrote,
          "the unit, for you to read and disagree with");
      write(new File(home, "install.sh"), installScript(home, service), true, wrote,
          "the part that needs root: the user, the unit, and enabling it");
    } catch (IOException ex) {
      return Report.no("could not write into " + home + ": " + ex.getMessage());
    }
    return new Report(true, null, wrote, service, staged, home);
  }

  /**
   * Put the jar where it goes, or stage it beside the one already there.
   *
   * The staging is the whole deployment story. Overwriting a jar a service is running from works on
   * Linux -- the running process holds the inode -- but it means the file on disk and the process
   * in memory are different software, which is the state every confusing incident report starts in.
   *
   * @return true when it was staged rather than installed
   */
  private static boolean placeJar(File from, File to, List<String> wrote) throws IOException {
    if (!to.exists()) {
      Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
      wrote.add("hearth.jar -- the server itself");
      return false;
    }
    if (sameBytes(from, to)) {
      wrote.add("hearth.jar -- already the same jar, left alone");
      return false;
    }
    Files.copy(from.toPath(), new File(to.getParentFile(), "hearth.new.jar").toPath(),
        StandardCopyOption.REPLACE_EXISTING);
    wrote.add("hearth.new.jar -- staged; the next restart swaps it in and keeps the old one");
    return true;
  }

  private static boolean sameBytes(File left, File right) throws IOException {
    if (left.length() != right.length()) {
      return false;
    }
    return java.util.Arrays.equals(Files.readAllBytes(left.toPath()),
        Files.readAllBytes(right.toPath()));
  }

  private static void write(File file, String body, boolean executable, List<String> wrote,
                            String why) throws IOException {
    Files.writeString(file.toPath(), body, StandardCharsets.UTF_8);
    if (executable) {
      file.setExecutable(true, false);
    }
    wrote.add(file.getName() + " -- " + why);
  }

  /** the java this JVM is running, so a box with three of them starts the right one */
  public static String javaBinary() {
    String home = System.getProperty("java.home");
    if (home != null && !home.isBlank()) {
      File binary = new File(home, "bin/java");
      if (binary.isFile()) {
        return binary.getAbsolutePath();
      }
    }
    return "java";
  }

  static String runScript(File home) {
    return "#!/bin/sh\n"
        + "# Written by `hearth --install`. Re-running --install overwrites this file.\n"
        + "#\n"
        + "# Deploying is: copy a new jar in as hearth.new.jar, then restart the service. This\n"
        + "# script swaps it in on the way up and keeps the one it replaced, so rolling back is\n"
        + "#   mv hearth.prev.jar hearth.new.jar && systemctl restart " + serviceNameFor(home) + "\n"
        + "set -eu\n"
        + "cd " + shellQuote(home.getAbsolutePath()) + "\n"
        + "\n"
        + "JAVA=\"${JAVA:-" + javaBinary() + "}\"\n"
        + "\n"
        + "if [ -f hearth.new.jar ]; then\n"
        + "  # A jar starts with the two bytes PK. Checking costs nothing and stops a half-finished\n"
        + "  # upload from replacing a server that works.\n"
        + "  if [ -s hearth.new.jar ] && [ \"$(head -c 2 hearth.new.jar)\" = \"PK\" ]; then\n"
        + "    if [ -f hearth.jar ]; then cp -f hearth.jar hearth.prev.jar; fi\n"
        + "    mv -f hearth.new.jar hearth.jar\n"
        + "    echo \"hearth: picked up a new jar\"\n"
        + "  else\n"
        + "    echo \"hearth: hearth.new.jar is not a jar; starting the old one\" >&2\n"
        + "  fi\n"
        + "fi\n"
        + "\n"
        + "exec \"$JAVA\" ${JAVA_OPTS:-} -jar hearth.jar --root data\n";
  }

  static String unit(File home, String service) {
    String path = home.getAbsolutePath();
    return "# Written by `hearth --install`. Re-running --install overwrites this file;\n"
        + "# install.sh copies it to /etc/systemd/system/" + service + ".service.\n"
        + "[Unit]\n"
        + "Description=Hearth community server (" + path + ")\n"
        + "After=network-online.target\n"
        + "Wants=network-online.target\n"
        + "\n"
        + "[Service]\n"
        + "Type=simple\n"
        + "User=" + service + "\n"
        + "Group=" + service + "\n"
        + "WorkingDirectory=" + path + "\n"
        + "# optional, and absent by default: JAVA_OPTS=-Xmx512m, or JAVA=/path/to/java\n"
        + "EnvironmentFile=-" + path + "/env\n"
        + "ExecStart=" + path + "/run.sh\n"
        + "Restart=on-failure\n"
        + "RestartSec=5\n"
        + "TimeoutStopSec=30\n"
        + "\n"
        + "# Ports 80 and 443 without running as root: the one privilege this server needs, granted\n"
        + "# by name rather than by being root and dropping it afterwards.\n"
        + "AmbientCapabilities=CAP_NET_BIND_SERVICE\n"
        + "CapabilityBoundingSet=CAP_NET_BIND_SERVICE\n"
        + "NoNewPrivileges=true\n"
        + "PrivateTmp=true\n"
        + "ProtectSystem=strict\n"
        + "ProtectHome=true\n"
        + "ProtectKernelTunables=true\n"
        + "ProtectControlGroups=true\n"
        + "RestrictSUIDSGID=true\n"
        + "# the only directory it may write to, which is the whole of what it needs\n"
        + "ReadWritePaths=" + path + "\n"
        + "LimitNOFILE=65535\n"
        + "\n"
        + "StandardOutput=journal\n"
        + "StandardError=journal\n"
        + "SyslogIdentifier=" + service + "\n"
        + "\n"
        + "[Install]\n"
        + "WantedBy=multi-user.target\n";
  }

  static String installScript(File home, String service) {
    String path = shellQuote(home.getAbsolutePath());
    return "#!/bin/sh\n"
        + "# Written by `hearth --install`. Run it with sudo, as many times as you like:\n"
        + "# every step checks before it acts, and none of them touches data/ or your config.\n"
        + "set -eu\n"
        + "\n"
        + "HOME_DIR=" + path + "\n"
        + "SERVICE=" + service + "\n"
        + "UNIT=/etc/systemd/system/$SERVICE.service\n"
        + "\n"
        + "if [ \"$(id -u)\" -ne 0 ]; then\n"
        + "  echo \"this part needs root: sudo $HOME_DIR/install.sh\" >&2\n"
        + "  exit 1\n"
        + "fi\n"
        + "\n"
        + "# the account the server runs as: a system account with no login and no password, which\n"
        + "# is what \"this is a service, not a person\" looks like on a unix box\n"
        + "if ! getent group \"$SERVICE\" >/dev/null 2>&1; then\n"
        + "  groupadd --system \"$SERVICE\"\n"
        + "  echo \"created the $SERVICE group\"\n"
        + "else\n"
        + "  echo \"the $SERVICE group is already there\"\n"
        + "fi\n"
        + "if ! id -u \"$SERVICE\" >/dev/null 2>&1; then\n"
        + "  useradd --system --gid \"$SERVICE\" --home-dir \"$HOME_DIR\" \\\n"
        + "    --shell /usr/sbin/nologin --comment \"Hearth community server\" \"$SERVICE\"\n"
        + "  echo \"created the $SERVICE user\"\n"
        + "else\n"
        + "  echo \"the $SERVICE user is already there\"\n"
        + "fi\n"
        + "\n"
        + "mkdir -p \"$HOME_DIR/data\"\n"
        + "chown -R \"$SERVICE:$SERVICE\" \"$HOME_DIR\"\n"
        + "# the database holds password hashes and session hashes, and the certs directory holds\n"
        + "# private keys: neither is anybody else's business on a shared box\n"
        + "chmod 750 \"$HOME_DIR\"\n"
        + "chmod 750 \"$HOME_DIR/data\"\n"
        + "chmod 755 \"$HOME_DIR/run.sh\"\n"
        + "echo \"$HOME_DIR belongs to $SERVICE\"\n"
        + "\n"
        + "install -m 0644 \"$HOME_DIR/$SERVICE.service\" \"$UNIT\"\n"
        + "systemctl daemon-reload\n"
        + "systemctl enable \"$SERVICE.service\" >/dev/null\n"
        + "echo \"$UNIT installed and enabled at boot\"\n"
        + "\n"
        + "# Deliberately not started or restarted here. Starting a server is a thing somebody\n"
        + "# should do while watching, and restarting one that is already up is not what \"run the\n"
        + "# installer again\" should mean.\n"
        + "if systemctl is-active --quiet \"$SERVICE.service\"; then\n"
        + "  echo\n"
        + "  echo \"$SERVICE is running. To pick up a newly staged jar:\"\n"
        + "  echo \"  sudo systemctl restart $SERVICE\"\n"
        + "else\n"
        + "  echo\n"
        + "  echo \"Next:\"\n"
        + "  echo \"  sudo -u $SERVICE " + javaBinary()
        + " -jar $HOME_DIR/hearth.jar --root $HOME_DIR/data --setup\"\n"
        + "  echo \"  sudo systemctl start $SERVICE\"\n"
        + "  echo \"  journalctl -u $SERVICE -f\"\n"
        + "fi\n";
  }

  /** a path as one shell word; refuses nothing, quotes everything */
  static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** the sentence a terminal gets after everything is written */
  public static List<String> nextSteps(Report report) {
    if (!report.ok()) {
      return List.of();
    }
    String path = report.home().getAbsolutePath();
    ArrayList<String> steps = new ArrayList<>();
    steps.add("read " + path + "/" + report.service() + ".service and "
        + path + "/install.sh");
    steps.add("sudo " + path + "/install.sh");
    steps.add("sudo -u " + report.service() + " " + javaBinary() + " -jar " + path
        + "/hearth.jar --root " + path + "/data --setup");
    steps.add("sudo systemctl " + (report.staged() ? "restart " : "start ") + report.service());
    return steps;
  }
}
