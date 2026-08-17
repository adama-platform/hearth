package io.hearth.common;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * The --verbose channel. Off, this costs a boolean check; on, it narrates every decision the
 * server makes so a developer can see why a domain resolved the way it did.
 *
 * Messages are lazily formatted via {@link #say(Supplier)} on the request path so that string
 * building never happens when verbose is off.
 *
 * The sink is injectable so that tests can assert on the narration -- verbose output is a feature
 * with its own correctness, not just noise -- and so a test doesn't spray it across a build log.
 */
public class Verbose {
  public static final Verbose OFF = new Verbose(false);
  public final boolean on;
  private final PrintStream out;

  public Verbose(boolean on) {
    this(on, System.out);
  }

  public Verbose(boolean on, PrintStream out) {
    this.on = on;
    this.out = out;
  }

  /** a verbose sink that captures instead of printing; read it back with {@link Captured#text()} */
  public static Captured capturing() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    return new Captured(new Verbose(true, new PrintStream(buffer, true, StandardCharsets.UTF_8)), buffer);
  }

  public void say(String message) {
    if (on) {
      out.println("  ... " + message);
    }
  }

  public void say(Supplier<String> message) {
    if (on) {
      out.println("  ... " + message.get());
    }
  }

  /** indented detail under a prior say() */
  public void detail(String message) {
    if (on) {
      out.println("        " + message);
    }
  }

  public void detail(Supplier<String> message) {
    if (on) {
      out.println("        " + message.get());
    }
  }

  /** a verbose channel plus the buffer it wrote into */
  public static class Captured {
    public final Verbose verbose;
    private final ByteArrayOutputStream buffer;

    Captured(Verbose verbose, ByteArrayOutputStream buffer) {
      this.verbose = verbose;
      this.buffer = buffer;
    }

    public String text() {
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }
}
