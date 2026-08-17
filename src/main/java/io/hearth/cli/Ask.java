package io.hearth.cli;

import io.hearth.common.Boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Asking a person questions on a terminal.
 *
 * Shared by every walkthrough, which is the point: `--setup`, `--domain-setup`, `--setup-email` and
 * `--setup-certs` should feel like one program rather than four, and the way they read is most of
 * how that happens.
 *
 * The stream is injectable so tests can drive a walkthrough with canned answers and read back
 * exactly what somebody would have seen. Writing to `System.out` directly is what made the first of
 * these untestable until it was rewritten.
 *
 * **No terminal means refuse.** Every one of these exists to make somebody think about something --
 * where certificates come from, what an admin address means, that credentials are going in a file --
 * and a pipe cannot think. Guessing defaults for an absent human is how a walkthrough becomes a
 * worse version of a config file.
 */
public class Ask {
  private final BufferedReader in;
  private final PrintStream out;

  public Ask() {
    this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
  }

  public Ask(BufferedReader in, PrintStream out) {
    this.in = in;
    this.out = out;
  }

  /** raised when there is nobody to answer; the caller stops rather than assuming */
  public static class NoTerminal extends IOException {
    NoTerminal() {
      super("this needs a terminal to ask questions on");
    }
  }

  public PrintStream out() {
    return out;
  }

  public void say(String line) {
    out.println(line);
  }

  public void blank() {
    out.println();
  }

  public void section(String title) {
    out.println();
    out.println(Boot.sectionLine(title));
  }

  public void step(String message) {
    out.println(Boot.stepLine(message));
  }

  public void ok(String message) {
    out.println(Boot.okLine(message));
  }

  public void warn(String message) {
    out.println(Boot.warnLine(message));
  }

  public void fail(String message) {
    out.println(Boot.failLine(message));
  }

  /** yes or no, with a default for the empty answer */
  public boolean yes(String question, boolean defaultYes) throws IOException {
    String answer = line("  " + question + (defaultYes ? " [Y/n] " : " [y/N] "));
    if (answer.isEmpty()) {
      return defaultYes;
    }
    String trimmed = answer.toLowerCase(Locale.ROOT);
    return trimmed.equals("y") || trimmed.equals("yes");
  }

  /** free text, with a default shown in the prompt */
  public String text(String question, String fallback) throws IOException {
    String suffix = fallback == null || fallback.isEmpty() ? "" : " [" + fallback + "]";
    String answer = line("  " + question + suffix + ": ");
    return answer.isEmpty() && fallback != null ? fallback : answer;
  }

  /** free text that must be given */
  public String required(String question) throws IOException {
    while (true) {
      String answer = line("  " + question + ": ");
      if (!answer.isEmpty()) {
        return answer;
      }
      warn("that one is required");
    }
  }

  /** a secret; still echoed, because a jar has no reliable way not to and pretending is worse */
  public String secret(String question) throws IOException {
    java.io.Console console = System.console();
    if (console != null) {
      out.print("  " + question + ": ");
      out.flush();
      char[] typed = console.readPassword();
      if (typed != null) {
        return new String(typed).trim();
      }
    }
    return required(question);
  }

  /** a whole number inside a range */
  public int number(String question, int fallback, int low, int high) throws IOException {
    while (true) {
      String answer = line("  " + question + " [" + fallback + "]: ");
      if (answer.isEmpty()) {
        return fallback;
      }
      try {
        int value = Integer.parseInt(answer);
        if (value >= low && value <= high) {
          return value;
        }
      } catch (NumberFormatException ex) {
        // fall through to the same complaint
      }
      warn("that has to be a number between " + low + " and " + high);
    }
  }

  private String line(String prompt) throws IOException {
    out.print(prompt);
    out.flush();
    String answer = in.readLine();
    if (answer == null) {
      out.println();
      throw new NoTerminal();
    }
    return answer.trim();
  }
}
