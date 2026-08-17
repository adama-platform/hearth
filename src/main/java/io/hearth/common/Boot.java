package io.hearth.common;

/**
 * Boot-time console output. Everything the operator sees before the server is accepting
 * traffic comes through here, so a failed start reads like a story rather than a stack trace.
 * Color is dropped when NO_COLOR is set or when stdout isn't a terminal.
 */
public class Boot {
  private static final boolean COLOR = color();
  private static final String RESET = esc("\u001B[0m");
  private static final String BOLD = esc("\u001B[1m");
  private static final String DIM = esc("\u001B[2m");
  private static final String CYAN = esc("\u001B[36m");
  private static final String GREEN = esc("\u001B[32m");
  private static final String YELLOW = esc("\u001B[33m");
  private static final String RED = esc("\u001B[31m");
  private static final String BLUE = esc("\u001B[34m");
  private static final String MAGENTA = esc("\u001B[35m");
  private static final String RULE = "  -----------------------------------------";

  private static boolean color() {
    if (System.getenv("NO_COLOR") != null) {
      return false;
    }
    return System.console() != null;
  }

  private static String esc(String code) {
    return COLOR ? code : "";
  }

  public static void banner(String version) {
    System.out.println();
    System.out.println(BOLD + CYAN + "  _  _ ___   _   ___ _____ _  _ " + RESET);
    System.out.println(BOLD + CYAN + " | || | __| /_\\ | _ \\_   _| || |" + RESET);
    System.out.println(BOLD + CYAN + " | __ | _| / _ \\|   / | | | __ |" + RESET);
    System.out.println(BOLD + CYAN + " |_||_|___/_/ \\_\\_|_\\ |_| |_||_|" + RESET);
    System.out.println();
    System.out.println(DIM + "  Hearth " + version + RESET);
    System.out.println(DIM + "  a community server for fewer than 500 friends" + RESET);
    System.out.println(DIM + RULE + RESET);
  }

  public static void section(String title) {
    System.out.println();
    System.out.println(sectionLine(title));
  }

  /**
   * The same lines, as strings.
   *
   * Anything that writes to a stream of its own -- the certificate walkthrough, which a test drives
   * with canned answers -- renders identically to the boot report without going through System.out,
   * which a test cannot capture cleanly.
   */
  public static String sectionLine(String title) {
    return BOLD + MAGENTA + "  [" + title + "]" + RESET;
  }

  public static String stepLine(String message) {
    return CYAN + "  --> " + RESET + message;
  }

  public static String okLine(String message) {
    return GREEN + BOLD + "  [OK] " + RESET + GREEN + message + RESET;
  }

  public static String warnLine(String message) {
    return YELLOW + "  [!!] " + RESET + YELLOW + message + RESET;
  }

  public static String failLine(String message) {
    return RED + BOLD + " [FAIL] " + RESET + RED + message + RESET;
  }

  public static void step(String message) {
    System.out.println(stepLine(message));
  }

  public static void ok(String message) {
    System.out.println(okLine(message));
  }

  public static void warn(String message) {
    System.out.println(warnLine(message));
  }

  public static void fail(String message) {
    System.out.println(failLine(message));
  }

  public static void info(String label, String value) {
    System.out.println("       " + DIM + label + ": " + RESET + BOLD + value + RESET);
  }

  public static void ready(String url) {
    System.out.println();
    System.out.println(DIM + RULE + RESET);
    System.out.println(BOLD + GREEN + "  serving" + RESET);
    System.out.println("       " + DIM + "url: " + RESET + BOLD + BLUE + url + RESET);
    System.out.println(DIM + RULE + RESET);
    System.out.println();
  }
}
