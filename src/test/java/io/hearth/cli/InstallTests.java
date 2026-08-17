package io.hearth.cli;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Turning a directory into a service, and the deployment story that falls out of it.
 *
 * The scripts are what is really under test, because they are the part nobody compiles. Two of them
 * carry a promise that is easy to get wrong and expensive to get wrong twice: a re-run must not
 * overwrite the jar a service is running from, and a half-finished upload must not replace a server
 * that works. Both are exercised here by running the real script with a real shell.
 */
public class InstallTests {
  @Test
  public void aDirectoryThatIsNotThereIsRefusedRatherThanCreated() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File missing = new File(temp(), "nowhere");
    Install.Report report = Install.run(missing, jar());
    assertFalse(report.ok());
    assertTrue(report.problem(), report.problem().contains("mkdir -p"));
    assertFalse("an install path is a decision about where a database lives; a typo that silently"
        + " made the directory is a server nobody can find later", missing.exists());
  }

  @Test
  public void looseClassesAreNotSomethingToInstall() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    Install.Report report = Install.run(temp(), null);
    assertFalse(report.ok());
    assertTrue(report.problem(), report.problem().contains("loose classes"));
  }

  @Test
  public void everythingItWritesIsThere() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File home = temp();
    Install.Report report = Install.run(home, jar());
    assertTrue(report.problem(), report.ok());
    assertTrue(new File(home, "hearth.jar").isFile());
    assertTrue(new File(home, "run.sh").isFile());
    assertTrue(new File(home, "run.sh").canExecute());
    assertTrue(new File(home, "install.sh").canExecute());
    assertTrue(new File(home, report.service() + ".service").isFile());
    assertTrue("the root the service will use, ready for the walkthrough to fill in",
        new File(home, "data").isDirectory());
    assertFalse(report.staged());
  }

  @Test
  public void theServiceIsNamedAfterTheDirectory() throws Exception {
    // two communities on one box are two services with names somebody can tell apart
    assertEquals("hearth", Install.serviceNameFor(new File("/hearth")));
    assertEquals("supper", Install.serviceNameFor(new File("/srv/supper")));
    assertEquals("a unit name with punctuation in it is not a unit name",
        "hearth", Install.serviceNameFor(new File("/srv/.")));
  }

  @Test
  public void theUnitAsksForTheOnePrivilegeItNeedsAndNoMore() throws Exception {
    String unit = Install.unit(new File("/hearth"), "hearth");
    assertTrue("ports 80 and 443 without being root",
        unit.contains("AmbientCapabilities=CAP_NET_BIND_SERVICE"));
    assertTrue("and nothing else, ever", unit.contains("CapabilityBoundingSet=CAP_NET_BIND_SERVICE"));
    assertTrue(unit.contains("NoNewPrivileges=true"));
    assertTrue("the only directory it may write to", unit.contains("ReadWritePaths=/hearth"));
    assertTrue(unit.contains("ProtectSystem=strict"));
    assertFalse("a service that runs as root has no reason to", unit.contains("User=root"));
    assertTrue(unit.contains("ExecStart=/hearth/run.sh"));
    assertTrue("restarting is what picks up a new jar, so it has to come back on its own",
        unit.contains("Restart=on-failure"));
  }

  @Test
  public void aSecondInstallStagesRatherThanOverwrites() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File home = temp();
    Install.run(home, jar());
    byte[] running = Files.readAllBytes(new File(home, "hearth.jar").toPath());

    Install.Report again = Install.run(home, otherJar());
    assertTrue(again.staged());
    assertArrayEqualsMessage("the jar the service is running from is not touched",
        running, Files.readAllBytes(new File(home, "hearth.jar").toPath()));
    assertTrue(new File(home, "hearth.new.jar").isFile());
  }

  @Test
  public void installingTheSameJarTwiceStagesNothing() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File home = temp();
    Install.run(home, jar());
    Install.Report again = Install.run(home, jar());
    assertTrue(again.ok());
    assertFalse("re-running is safe and is meant to be", again.staged());
    assertFalse(new File(home, "hearth.new.jar").exists());
    assertTrue(again.wrote().toString().contains("already the same jar"));
  }

  @Test
  public void theStartScriptSwapsAStagedJarInAndKeepsTheOldOne() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File home = temp();
    Install.run(home, jar());
    Files.writeString(new File(home, "hearth.new.jar").toPath(), "PK-a-newer-jar");

    run(home);
    assertEquals("PK-a-newer-jar", Files.readString(new File(home, "hearth.jar").toPath()));
    assertTrue("rolling back is moving one file back", new File(home, "hearth.prev.jar").isFile());
    assertFalse(new File(home, "hearth.new.jar").exists());
  }

  @Test
  public void aHalfFinishedUploadNeverReplacesAServerThatWorks() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File home = temp();
    Install.run(home, jar());
    String before = Files.readString(new File(home, "hearth.jar").toPath());
    Files.writeString(new File(home, "hearth.new.jar").toPath(), "not a jar at all");

    String output = run(home);
    assertTrue(output, output.contains("is not a jar"));
    assertEquals("the working jar is still the working jar",
        before, Files.readString(new File(home, "hearth.jar").toPath()));
    assertTrue("and the bad file is left where somebody can look at it",
        new File(home, "hearth.new.jar").isFile());
  }

  @Test
  public void bothScriptsAreScriptsTheShellWillAccept() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    File home = temp();
    Install.Report report = Install.run(home, jar());
    // a generated script with a syntax error is a service that fails at 3am with no clue why
    assertEquals(0, shell("sh", "-n", new File(home, "run.sh").getAbsolutePath()).exit());
    assertEquals(0, shell("sh", "-n", new File(home, "install.sh").getAbsolutePath()).exit());
    assertTrue(report.ok());
  }

  @Test
  public void theRootScriptRefusesToRunWithoutRootAndSaysHow() throws Exception {
    Assume.assumeTrue(Install.hasSystemd());
    Assume.assumeTrue(!"0".equals(System.getenv("EUID")));
    File home = temp();
    Install.run(home, jar());
    Result result = shell("sh", new File(home, "install.sh").getAbsolutePath());
    // it does nothing at all before that check, which is what makes it safe to read and then run
    assertEquals(1, result.exit());
    assertTrue(result.output(), result.output().contains("needs root"));
  }

  @Test
  public void theRootScriptChecksBeforeEveryThingItDoes() throws Exception {
    // idempotency, read out of the script itself: every step asks first, and nothing it does
    // touches data/ or a config file
    String script = Install.installScript(new File("/hearth"), "hearth");
    assertTrue(script.contains("if ! getent group"));
    assertTrue(script.contains("if ! id -u"));
    assertTrue("mkdir -p and chown are safe twice", script.contains("mkdir -p"));
    assertTrue("systemctl enable is safe twice", script.contains("systemctl enable"));
    assertFalse("starting a server is something somebody should do while watching",
        script.contains("systemctl start \"$SERVICE"));
    assertFalse("and restarting one that is up is not what re-running an installer means",
        script.contains("systemctl restart \"$SERVICE"));
    assertTrue("the database and the private keys are nobody else's business",
        script.contains("chmod 750"));
  }

  @Test
  public void aPathWithAQuoteInItIsStillOneShellWord() {
    assertEquals("'/srv/it'\\''s here'", Install.shellQuote("/srv/it's here"));
    assertTrue(Install.runScript(new File("/srv/odd dir")).contains("cd '/srv/odd dir'"));
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private static void assertArrayEqualsMessage(String message, byte[] expected, byte[] actual) {
    assertTrue(message, java.util.Arrays.equals(expected, actual));
  }

  /** run the generated start script with a java that only echoes, so nothing is served */
  private static String run(File home) throws Exception {
    ProcessBuilder builder = new ProcessBuilder("sh", new File(home, "run.sh").getAbsolutePath());
    builder.environment().put("JAVA", "/bin/echo");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor();
    return output;
  }

  private record Result(int exit, String output) {
  }

  private static Result shell(String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(process.waitFor(), output);
  }

  /** something jar-shaped to install; the installer only cares that it is a file named .jar */
  private static File jar() throws Exception {
    Path jar = Files.createTempFile("hearth-test", ".jar");
    Files.writeString(jar, "PK-pretend-this-is-a-jar");
    jar.toFile().deleteOnExit();
    return jar.toFile();
  }

  private static File otherJar() throws Exception {
    Path jar = Files.createTempFile("hearth-test-newer", ".jar");
    Files.writeString(jar, "PK-a-different-jar-entirely");
    jar.toFile().deleteOnExit();
    return jar.toFile();
  }

  private static File temp() throws Exception {
    File dir = Files.createTempDirectory("hearth-install").toFile();
    dir.deleteOnExit();
    return dir;
  }
}
