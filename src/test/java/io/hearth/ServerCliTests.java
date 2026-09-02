package io.hearth;

import io.hearth.testkit.Configs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/**
 * The entry point, in process.
 *
 * Only the paths that return normally are exercised here -- a failing boot calls System.exit, which
 * would take the test JVM with it, so the refusal path is covered by `just validate` running the
 * real jar. That split is deliberate: this class proves the CLI wiring, the smoke test proves the
 * exit codes.
 */
public class ServerCliTests {
  private PrintStream originalOut;
  private ByteArrayOutputStream captured;

  @Before
  public void captureStdout() {
    originalOut = System.out;
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
  }

  @After
  public void restoreStdout() {
    System.setOut(originalOut);
  }

  private String output() {
    return captured.toString(StandardCharsets.UTF_8);
  }

  /**
   * The jar says what it is, and what it is is `MAIN`.
   *
   * Asserted against the literal rather than against {@code Server.VERSION}, which would be true
   * whatever the constant said and would go on passing if somebody reintroduced a version number
   * by accident. There is no version here on purpose: nobody resolves this jar from a repository,
   * so a number on it would promise a thing nobody is tracking.
   */
  @Test
  public void versionSaysItWasBuiltFromMain() {
    Server.main(new String[]{"--version"});
    assertTrue(output(), output().contains("Hearth MAIN"));
  }

  @Test
  public void noArgumentsPrintsUsage() {
    Server.main(new String[0]);
    assertTrue(output().contains("--root"));
    assertTrue("it shows the layout under the root", output().contains("domains/"));
    assertTrue(output().contains("config.cfg"));
  }

  @Test
  public void helpPrintsUsage() {
    Server.main(new String[]{"--help"});
    assertTrue(output().contains("usage: java -jar"));
  }

  @Test
  public void checkLoadsAndReportsWithoutBinding() throws Exception {
    Configs configs = Configs.standard();
    try {
      Server.main(new String[]{"--root", configs.asRoot().rootDir().getPath(), "--check"});
      String out = output();
      assertTrue(out.contains("configs are valid"));
      assertTrue("the report should name every domain", out.contains("example.com"));
      assertTrue(out.contains("blog.example.com"));
      assertTrue(out.contains("localhost"));
      assertTrue("a disabled domain should be called out", out.contains("[disabled]"));
      assertTrue("a wildcard domain should be called out", out.contains("[+subdomains]"));
      assertTrue("--check must never mention a socket", !out.contains("listening"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void checkVerboseNarratesTheScan() throws Exception {
    Configs configs = Configs.standard();
    try {
      Server.main(new String[]{"--root", configs.asRoot().rootDir().getPath(), "--check", "--verbose"});
      String out = output();
      assertTrue(out.contains("scanning"));
      assertTrue(out.contains("loaded example.com from example.com.cfg"));
      assertTrue(out.contains("scan complete: 4 domain(s)"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void checkWarnsOnAnEmptyDirectory() throws Exception {
    Configs configs = Configs.dir();
    try {
      Server.main(new String[]{"--root", configs.asRoot().rootDir().getPath(), "--check"});
      assertTrue(output().contains("every request will be refused"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void checkWarnsOnADisabledDomain() throws Exception {
    Configs configs = Configs.dir()
        .domain("example.com", "{\"name\":\"Example\"}")
        .domain("off.org", "{\"enabled\":false}");
    try {
      Server.main(new String[]{"--root", configs.asRoot().rootDir().getPath(), "--check"});
      String out = output();
      assertTrue(out.contains("enabled=false"));
      assertTrue(out.contains("configs are valid"));
    } finally {
      configs.delete();
    }
  }
}
