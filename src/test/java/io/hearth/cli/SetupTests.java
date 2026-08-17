package io.hearth.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.common.ConfigException;
import io.hearth.common.ServerConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * One directory, and the conversations that fill it in.
 *
 * The root exists to remove a class of mistake: three independent path flags could point at three
 * different installations, and nothing noticed until the wrong people could sign in. The
 * walkthroughs exist because the settings that are easy to get wrong are the ones whose failure
 * arrives later and silently -- no admin address means nobody can ever approve anybody.
 */
public class SetupTests {
  private static final ObjectMapper JSON = new ObjectMapper();
  private File dir;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("hearth-root-test").toFile();
  }

  @After
  public void tearDown() {
    deleteTree(dir);
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    file.delete();
  }

  private String walk(String answers, Walk walk) throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    Ask ask = new Ask(new BufferedReader(new StringReader(answers)),
        new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      walk.run(new Setup(Root.at(dir), ask));
    } catch (Ask.NoTerminal expected) {
      captured.write("NO TERMINAL".getBytes(StandardCharsets.UTF_8));
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  private interface Walk {
    void run(Setup setup) throws Exception;
  }

  /**
   * Canned answers, each one terminated.
   *
   * String.join leaves the last line unterminated, so a walkthrough whose final answer is "take the
   * default" would hit end-of-input instead and refuse -- which is a real behaviour, and exactly the
   * wrong one to trigger by accident in a test about something else.
   */
  private static String answers(String... lines) {
    StringBuilder typed = new StringBuilder();
    for (String line : lines) {
      typed.append(line).append('\n');
    }
    return typed.toString();
  }

  private JsonNode read(File file) throws Exception {
    return JSON.readTree(file);
  }

  // ---- the root ---------------------------------------------------------------------------------

  @Test
  public void openingARootCreatesEverythingUnderIt() throws Exception {
    Root root = Root.open(new File(dir, "fresh"));
    assertTrue(root.domains().isDirectory());
    assertTrue(root.databases().isDirectory());
    assertTrue(root.certs().isDirectory());
    assertFalse("config.cfg is not invented: absent means every default, which is a real state",
        root.hasConfig());
  }

  @Test
  public void aRootThatIsAFileIsRefused() throws Exception {
    File file = new File(dir, "notes.txt");
    Files.writeString(file.toPath(), "hello");
    try {
      Root.open(file);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("not a directory"));
    }
  }

  @Test
  public void aDomainCannotEscapeTheDomainsDirectory() {
    Root root = Root.at(dir);
    for (String nasty : new String[]{"../../etc/passwd", "a/b", ".hidden", ""}) {
      try {
        root.domainFile(nasty);
        fail("expected a refusal for '" + nasty + "'");
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("refusing"));
      }
    }
  }

  // ---- config.cfg -------------------------------------------------------------------------------

  @Test
  public void noConfigFileMeansEveryDefault() throws Exception {
    ServerConfig config = ServerConfig.read(new File(dir, "nothing.cfg"));
    assertEquals(80, config.httpPort);
    assertFalse(config.httpsEnabled);
    assertTrue("http/2 is on by default; there is no reason to say no to it", config.http2);
  }

  @Test
  public void aTypoInTheConfigIsRefusedRatherThanIgnored() throws Exception {
    // a server that ignored "htp-port" and listened on 80 anyway would look like it worked
    File file = new File(dir, "config.cfg");
    Files.writeString(file.toPath(), "{\"htp-port\": 8080}");
    try {
      ServerConfig.read(file);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("unknown key"));
    }
  }

  @Test
  public void twoListenersCannotShareAPort() throws Exception {
    File file = new File(dir, "config.cfg");
    Files.writeString(file.toPath(), "{\"http-port\": 8443, \"enable-https\": true, \"https-port\": 8443}");
    try {
      ServerConfig.read(file);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("both 8443"));
    }
  }

  @Test
  public void theConfigDrivesTheListeners() throws Exception {
    File file = new File(dir, "config.cfg");
    Files.writeString(file.toPath(), "{\"http-port\": 8080, \"enable-https\": true,"
        + " \"https-port\": 8443, \"enable-http-bounce\": true, \"http-bounce-port\": 9999}");
    ServerConfig config = ServerConfig.read(file);
    assertEquals(8080, config.web().port);
    assertEquals(8443, config.web().httpsPort);
    assertEquals(9999, config.web().bouncePort);
    assertTrue(config.describe().contains("http/2"));
  }

  @Test
  public void theBouncePortIsOffUnlessItIsTurnedOn() throws Exception {
    File file = new File(dir, "config.cfg");
    Files.writeString(file.toPath(), "{\"http-bounce-port\": 9999}");
    assertFalse("a port with no switch is not a listener", ServerConfig.read(file).web().bounceEnabled());
  }

  // ---- --setup ----------------------------------------------------------------------------------

  @Test
  public void theSetupWalkthroughWritesAConfigYouCouldHaveWritten() throws Exception {
    // http port, https?, bounce?, timezone, bind, inbound mail?
    String printed = walk(answers("8080", "n", "n", "Europe/London", "127.0.0.1", "n"),
        Setup::server);

    assertTrue(printed.contains("wrote config.cfg"));
    JsonNode config = read(new File(dir, "config.cfg"));
    assertEquals(8080, config.get("http-port").asInt());
    assertFalse(config.get("enable-https").asBoolean());
    assertEquals("127.0.0.1", config.get("bind").asText());
    assertEquals("this is a program for people who meet in a room, so the clock is a fact about a"
        + " place", "Europe/London", config.get("timezone").asText());
    assertTrue("and it says what is left to do", printed.contains("--domain-setup"));
  }

  @Test
  public void turningOnHttpsAsksAboutHttp2() throws Exception {
    String printed = walk(answers("80", "y", "443", "y", "n", "", "0.0.0.0", "n"), Setup::server);
    JsonNode config = read(new File(dir, "config.cfg"));
    assertTrue(config.get("enable-https").asBoolean());
    assertEquals(443, config.get("https-port").asInt());
    assertTrue(config.get("http2").asBoolean());
    assertTrue("and points at the next step", printed.contains("--setup-certs"));
  }

  @Test
  public void anExistingConfigIsShownBeforeItIsReplaced() throws Exception {
    Files.writeString(new File(dir, "config.cfg").toPath(), "{\"http-port\": 9000}");
    String printed = walk("n\n", Setup::server);
    assertTrue(printed.contains("already a config.cfg"));
    assertTrue("it shows what is there now", printed.contains("9000"));
    assertEquals("and answering no leaves it alone", 9000,
        read(new File(dir, "config.cfg")).get("http-port").asInt());
  }

  @Test
  public void asecondRunOffersWhatTheFileAlreadySays() throws Exception {
    // pressing return through a review must not quietly revert what the first run decided
    Files.writeString(new File(dir, "config.cfg").toPath(),
        "{\"http-port\":8443,\"enable-https\":true,\"https-port\":9443,\"http2\":false,"
            + "\"enable-http-bounce\":true,\"http-bounce-port\":9998,\"bind\":\"127.0.0.1\","
            + "\"smtp\":{\"enabled\":true,\"port\":2525,\"enforce-dmarc\":false}}");
    // every question, answered by pressing return: edit, ports, http2, bounce, the clock, bind,
    // and the three about mail
    walk(answers("y", "", "", "", "", "", "", "", "", "", "", ""), Setup::server);

    JsonNode config = read(new File(dir, "config.cfg"));
    assertEquals(8443, config.get("http-port").asInt());
    assertEquals(9443, config.get("https-port").asInt());
    assertFalse("even the answer somebody would never say no to", config.get("http2").asBoolean());
    assertTrue(config.get("enable-http-bounce").asBoolean());
    assertEquals(9998, config.get("http-bounce-port").asInt());
    assertEquals("127.0.0.1", config.get("bind").asText());
    assertEquals("a file that said nothing about the clock keeps the machine's",
        java.time.ZoneId.systemDefault().getId(), config.get("timezone").asText());
    assertTrue(config.get("smtp").get("enabled").asBoolean());
    assertEquals(2525, config.get("smtp").get("port").asInt());
    assertFalse(config.get("smtp").get("enforce-dmarc").asBoolean());
  }

  @Test
  public void inboundMailIsOffUntilSomebodySaysOtherwise() throws Exception {
    walk(answers("80", "n", "n", "", "0.0.0.0", "n"), Setup::server);
    assertFalse("port 25 needs root and a listener on it is found within the hour",
        read(new File(dir, "config.cfg")).has("smtp"));

    walk(answers("y", "80", "n", "n", "", "0.0.0.0", "y", "25", "y"), Setup::server);
    JsonNode smtp = read(new File(dir, "config.cfg")).get("smtp");
    assertTrue(smtp.get("enabled").asBoolean());
    assertEquals(25, smtp.get("port").asInt());
    assertTrue(smtp.get("enforce-dmarc").asBoolean());
  }

  @Test
  public void aWalkthroughWithNobodyToAnswerRefuses() throws Exception {
    // every one of these exists to make somebody think, and a pipe cannot think
    assertTrue(walk("", Setup::server).contains("NO TERMINAL"));
    assertFalse(new File(dir, "config.cfg").exists());
  }

  // ---- --domain-setup ---------------------------------------------------------------------------

  @Test
  public void theDomainWalkthroughWritesTheDomainFile() throws Exception {
    String printed = walk(answers("My Community", "America/New_York", "owner@example.com", "www",
            "y", "y", ""),
        setup -> setup.domain("example.org"));

    assertTrue(printed.contains("wrote example.org.cfg"));
    JsonNode config = read(new File(new File(dir, "domains"), "example.org.cfg"));
    assertEquals("My Community", config.get("name").asText());
    assertEquals("a community somewhere else than its box says so once",
        "America/New_York", config.get("timezone").asText());
    assertEquals("owner@example.com", config.get("admin_emails").get(0).asText());
    assertTrue(config.get("wildcard").asBoolean());
    assertEquals("a named subdomain, which a certificate can actually cover",
        "www", config.get("subdomains").get(0).asText());
    assertTrue(config.get("accepts-mail").asBoolean());
    assertFalse("passwordless is the default, so it is not written out",
        config.has("login_security"));
  }

  @Test
  public void aDomainWithNoAdminIsAllowedButWarnedAbout() throws Exception {
    // it is a legitimate intermediate state, and it is also how nobody can ever approve anybody
    String printed = walk(answers("Nameless", "", "", "y", ""),
        setup -> setup.domain("example.org"));
    assertTrue(printed.contains("nobody will be able to approve anybody"));
  }

  @Test
  public void anAdminEntryThatIsNotAnAddressIsRefused() throws Exception {
    String printed = walk(answers("Community", "", "not-an-address", "y", ""),
        setup -> setup.domain("example.org"));
    assertTrue(printed.contains("is not an email address"));
    assertFalse(new File(new File(dir, "domains"), "example.org.cfg").exists());
  }

  @Test
  public void aDomainThatIsNotOneIsRefusedBeforeAnythingIsWritten() throws Exception {
    String printed = walk("\n", setup -> setup.domain("NOT A DOMAIN"));
    assertTrue(printed.contains("not a valid domain"));
  }

  @Test
  public void editingADomainKeepsWhatWasNotAsked() throws Exception {
    File domains = new File(dir, "domains");
    domains.mkdirs();
    Files.writeString(new File(domains, "example.org.cfg").toPath(),
        "{\"name\":\"Old\",\"admin_emails\":[\"a@example.com\"],\"mcp\":{\"enabled\":true}}");

    walk(answers("y", "New Name", "", "a@example.com", "", "y", "y", ""),
        setup -> setup.domain("example.org"));

    JsonNode config = read(new File(domains, "example.org.cfg"));
    assertEquals("New Name", config.get("name").asText());
    assertTrue("a block the walkthrough never mentions has to survive it",
        config.get("mcp").get("enabled").asBoolean());
  }

  @Test
  public void theClockComesFromTheServerUnlessACommunitySaysOtherwise() throws Exception {
    Files.writeString(new File(dir, "config.cfg").toPath(), "{\"timezone\":\"Europe/London\"}");
    File domains = new File(dir, "domains");
    domains.mkdirs();

    // pressing return through the question accepts the server's, and writes nothing: a domain with
    // no timezone key inherits, which is what accepting the default meant
    walk(answers("Here", "", "a@example.com", "", "y", "y", ""),
        setup -> setup.domain("here.example.org"));
    JsonNode here = read(new File(domains, "here.example.org.cfg"));
    assertFalse("nothing is written when it is the same", here.has("timezone"));

    walk(answers("Away", "America/New_York", "a@example.com", "", "y", "y", ""),
        setup -> setup.domain("away.example.org"));
    assertEquals("America/New_York",
        read(new File(domains, "away.example.org.cfg")).get("timezone").asText());

    io.hearth.vhost.DomainTree tree = io.hearth.vhost.DomainScanner.scan(domains,
        ServerConfig.read(new File(dir, "config.cfg")).zone, io.hearth.common.Verbose.OFF).tree;
    assertEquals("the one that said nothing inherited", java.time.ZoneId.of("Europe/London"),
        tree.resolve("here.example.org").zone);
    assertEquals(java.time.ZoneId.of("America/New_York"),
        tree.resolve("away.example.org").zone);
  }

  @Test
  public void aZoneNobodyCanReadIsRefusedRatherThanGuessedAt() throws Exception {
    // in the walkthrough it asks again; at boot it refuses to start, because a server on a zone
    // somebody mistyped puts every event on the wrong evening and looks like a calendar bug
    String printed = walk(answers("80", "n", "n", "America/New York", "UTC", "0.0.0.0", "n"),
        Setup::server);
    assertTrue(printed, printed.contains("not a zone id"));
    assertEquals("UTC", read(new File(dir, "config.cfg")).get("timezone").asText());

    Files.writeString(new File(dir, "config.cfg").toPath(), "{\"timezone\":\"Mars/Olympus\"}");
    try {
      ServerConfig.read(new File(dir, "config.cfg"));
      org.junit.Assert.fail("a zone that is not one has to stop the server");
    } catch (io.hearth.common.ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("IANA"));
    }
  }

  // ---- --setup-email ----------------------------------------------------------------------------

  @Test
  public void theEmailWalkthroughNeedsADomainFirst() throws Exception {
    String printed = walk("\n", setup -> setup.email("example.org"));
    assertTrue(printed.contains("no config for example.org"));
    assertTrue(printed.contains("--domain-setup"));
  }

  @Test
  public void theEmailWalkthroughWritesTheSesBlockAndLocksTheFile() throws Exception {
    File domains = new File(dir, "domains");
    domains.mkdirs();
    File file = new File(domains, "example.org.cfg");
    Files.writeString(file.toPath(), "{\"name\":\"Example\"}");

    // ...and the calendar address at the end, which is a different address for a different reason:
    // the sending one only has to be verified, this one has to be *received* at
    String printed = walk(answers("y", "no-reply@example.org", "Example",
        "no-reply@example.org", "us-east-1", "AKIAEXAMPLE", "secret",
        "events@example.org", "Example Calendar"),
        setup -> setup.email("example.org"));

    assertTrue(printed.contains("readable only by this user"));
    JsonNode calendar = read(file).get("calendar");
    assertEquals("events@example.org", calendar.get("events-address").asText());
    assertEquals("Example Calendar", calendar.get("events-name").asText());
    assertTrue("and it says what else has to be true for that address to work",
        printed.contains("MX record"));
    JsonNode ses = read(file).get("ses");
    assertTrue(ses.get("enabled").asBoolean());
    assertEquals("no-reply@example.org", ses.get("from").asText());
    assertEquals("us-east-1", ses.get("region").asText());
    assertEquals("the credentials are in the file, so it is 0600",
        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(file.toPath()));
    assertTrue("and it says how to check it", printed.contains("--test-email"));
  }

  @Test
  public void anUnverifiedSenderStopsBeforeAnythingIsWritten() throws Exception {
    // the sandbox is what catches people out, so the walkthrough asks before it collects keys
    File domains = new File(dir, "domains");
    domains.mkdirs();
    File file = new File(domains, "example.org.cfg");
    Files.writeString(file.toPath(), "{\"name\":\"Example\"}");

    String printed = walk("n\n", setup -> setup.email("example.org"));
    assertTrue(printed.contains("sandbox"));
    assertFalse("nothing was collected", read(file).has("ses"));
  }

  @Test
  public void testEmailWithoutSetupSaysSoRatherThanTrying() throws Exception {
    File domains = new File(dir, "domains");
    domains.mkdirs();
    Files.writeString(new File(domains, "example.org.cfg").toPath(), "{\"name\":\"Example\"}");
    String printed = walk("\n", setup -> setup.testEmail("example.org", "you@example.com"));
    assertTrue(printed.contains("email is not set up"));
    assertTrue(printed.contains("--setup-email"));
  }

  // ---- --setup-gps ------------------------------------------------------------------------------

  @Test
  public void theGpsWalkthroughSaysWhyTheFamousServicesAreNotOffered() throws Exception {
    String printed = walk(answers("nominatim", "somebody@example.org"), Setup::gps);
    assertTrue("the expensive mistake is made before anybody types anything",
        printed.contains("Google, Mapbox and HERE"));
    assertTrue(printed.contains("thirty days"));
    assertTrue("and it says what this server actually does with the answer",
        printed.contains("writes the coordinate onto the place"));

    JsonNode gps = read(new File(dir, "config.cfg")).get("gps");
    assertTrue(gps.get("enabled").asBoolean());
    assertEquals("nominatim", gps.get("service").asText());
    assertEquals("somebody@example.org", gps.get("contact").asText());
    assertTrue("no key was asked for", gps.get("key") == null);
  }

  @Test
  public void aServiceWithAKeyAsksForOneAndLocksTheFile() throws Exception {
    walk(answers("opencage", "sk-abc123", "somebody@example.org"), Setup::gps);
    JsonNode gps = read(new File(dir, "config.cfg")).get("gps");
    assertEquals("opencage", gps.get("service").asText());
    assertEquals("sk-abc123", gps.get("key").asText());
    assertEquals("a key in a file is a file only this user reads",
        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        java.nio.file.Files.getPosixFilePermissions(new File(dir, "config.cfg").toPath()));
  }

  @Test
  public void aServiceNobodyOffersChangesNothing() throws Exception {
    String printed = walk(answers("google"), Setup::gps);
    assertTrue(printed.contains("not one of them"));
    assertTrue(printed.contains("nothing was changed"));
    assertFalse(new File(dir, "config.cfg").isFile());
  }
}
