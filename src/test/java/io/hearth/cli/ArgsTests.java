package io.hearth.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The command line, which is one required flag and a handful of one-shot steps.
 *
 * Most of what is here is refusals. A flag that was removed has to say so by name rather than fall
 * into "unknown argument", because the person hitting it is upgrading and has the old flag in a
 * service file -- and the worst outcome is a server that ignores `--http-port` and quietly serves
 * somewhere else.
 */
public class ArgsTests {
  @Test
  public void rootIsAllYouNeed() throws Exception {
    Args args = Args.parse(new String[]{"--root", "/var/hearth"});
    assertEquals("/var/hearth", args.root.getPath());
    assertFalse(args.verbose);
    assertFalse(args.check);
    assertFalse("nothing is a one-shot by default", args.isOneShot());
  }

  @Test
  public void rootIsRequired() {
    try {
      Args.parse(new String[]{"--verbose"});
      fail("expected a refusal");
    } catch (Args.ArgsException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("--root is required"));
    }
  }

  @Test
  public void verboseAndCheck() throws Exception {
    Args args = Args.parse(new String[]{"--root", "r", "--verbose", "--check"});
    assertTrue(args.verbose);
    assertTrue(args.check);
    assertTrue(args.isOneShot());
  }

  @Test
  public void theShortVerboseFlagWorksToo() throws Exception {
    assertTrue(Args.parse(new String[]{"--root", "r", "-v"}).verbose);
  }

  // ---- the setup steps -------------------------------------------------------------------------

  @Test
  public void eachSetupStepIsItsOwnFlag() throws Exception {
    assertTrue(Args.parse(new String[]{"--root", "r", "--setup"}).setup);
    assertEquals("example.org",
        Args.parse(new String[]{"--root", "r", "--domain-setup", "example.org"}).domainSetup);
    assertTrue(Args.parse(new String[]{"--root", "r", "--setup-certs"}).setupCerts);
    assertEquals("example.org",
        Args.parse(new String[]{"--root", "r", "--setup-email", "example.org"}).setupEmail);
  }

  @Test
  public void testEmailTakesADomainAndAnAddress() throws Exception {
    Args args = Args.parse(new String[]{"--root", "r", "--test-email", "example.org", "you@example.com"});
    assertEquals("example.org", args.testEmailDomain);
    assertEquals("you@example.com", args.testEmailTo);
    assertTrue(args.isOneShot());
  }

  @Test
  public void twoSetupStepsAtOnceIsRefused() {
    // each one writes a file and reports on it; two would interleave two conversations
    try {
      Args.parse(new String[]{"--root", "r", "--setup", "--setup-certs"});
      fail("expected a refusal");
    } catch (Args.ArgsException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("one setup step at a time"));
    }
  }

  // ---- refusals ---------------------------------------------------------------------------------

  @Test
  public void theOldDirectoryFlagsSayWhereTheyWent() {
    // somebody upgrading has these in a service file; "unknown argument" would not help them
    for (String flag : new String[]{"--configs", "--stores", "--certs"}) {
      try {
        Args.parse(new String[]{flag, "x", "--root", "r"});
        fail("expected a refusal for " + flag);
      } catch (Args.ArgsException ex) {
        assertTrue(ex.getMessage(), ex.getMessage().contains("--root"));
      }
    }
  }

  @Test
  public void theOldPortFlagsPointAtTheConfigFile() {
    // the dangerous one: silently ignoring --http-port means serving somewhere else
    for (String flag : new String[]{"--port", "--http-port", "--https-port", "--bind"}) {
      try {
        Args.parse(new String[]{"--root", "r", flag, "8080"});
        fail("expected a refusal for " + flag);
      } catch (Args.ArgsException ex) {
        assertTrue(ex.getMessage(), ex.getMessage().contains("config.cfg"));
      }
    }
  }

  @Test
  public void theRenamedCertFlagSaysItsNewName() {
    try {
      Args.parse(new String[]{"--root", "r", "--do-cert-setup"});
      fail("expected a refusal");
    } catch (Args.ArgsException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("--setup-certs"));
    }
  }

  @Test
  public void unknownFlagIsAnError() {
    try {
      Args.parse(new String[]{"--root", "r", "--nope"});
      fail("expected a refusal");
    } catch (Args.ArgsException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("unknown argument"));
    }
  }

  @Test
  public void flagWithoutValueIsAnError() {
    try {
      Args.parse(new String[]{"--root"});
      fail("expected a refusal");
    } catch (Args.ArgsException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("needs a value"));
    }
  }

  @Test
  public void aFlagFollowedByAnotherFlagIsNotAValue() {
    // --domain-setup --verbose would otherwise make a domain called "--verbose"
    try {
      Args.parse(new String[]{"--root", "r", "--domain-setup", "--verbose"});
      fail("expected a refusal");
    } catch (Args.ArgsException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("needs a value"));
    }
  }

  // ---- help and version ---------------------------------------------------------------------------

  @Test
  public void noArgumentsIsHelp() throws Exception {
    assertTrue(Args.parse(new String[0]).help);
  }

  @Test
  public void helpAndVersionDoNotNeedARoot() throws Exception {
    assertTrue(Args.parse(new String[]{"--help"}).help);
    assertTrue(Args.parse(new String[]{"--version"}).version);
    assertNull(Args.parse(new String[]{"--help"}).root);
  }

  @Test
  public void usageDocumentsEveryFlag() {
    String usage = Args.usage();
    for (String flag : new String[]{"--root", "--setup", "--domain-setup", "--setup-certs",
        "--setup-email", "--test-email", "--check", "--verbose", "--help", "--version"}) {
      assertTrue("usage should document " + flag, usage.contains(flag));
    }
  }

  @Test
  public void usageDescribesTheLayoutUnderTheRoot() {
    String usage = Args.usage();
    assertTrue(usage.contains("config.cfg"));
    assertTrue(usage.contains("domains/"));
    assertTrue(usage.contains("dbs/"));
    assertTrue(usage.contains("certs/"));
    assertTrue("and how a host resolves, which is the one surprising rule",
        usage.contains("wildcard"));
  }
}
