package io.hearth.certs;

import io.hearth.common.ConfigException;
import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import io.hearth.vhost.DomainConfig;
import io.hearth.vhost.DomainScanner;
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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Getting and keeping certificates.
 *
 * Three separable things, tested separately because they fail separately: the cache on disk, the
 * challenge this server answers over real HTTP, and the decision about when to order. The ACME
 * conversation itself is behind an interface and stood in for by {@link FakeAuthority}, since the
 * cases worth testing -- expiring soon, cannot be reached, no account -- cannot be arranged at a
 * real certificate authority on demand.
 */
public class CertificateTests {
  private File dir;
  private CertStore store;

  @Before
  public void setUp() throws Exception {
    dir = Files.createTempDirectory("hearth-certs-test").toFile();
    store = CertStore.open(dir);
  }

  @After
  public void tearDown() {
    deleteTree(dir);
  }

  private static void deleteTree(File root) {
    File[] children = root.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    root.delete();
  }

  private static Acme.Account account() {
    return new Acme.Account(Acme.STAGING, "key", "https://example.org/acct/1");
  }

  private void givenAnAccount() throws Exception {
    store.writeAccountKey("-----BEGIN RSA PRIVATE KEY-----\nfake\n-----END RSA PRIVATE KEY-----\n");
    store.writeAccount(new CertStore.AccountRecord(
        "https://example.org/acct/1", "owner@example.com", Acme.STAGING, true));
  }

  // ---- the cache directory -----------------------------------------------------------------------

  @Test
  public void theDirectoryIsCreatedIfItIsNotThere() throws Exception {
    File fresh = new File(dir, "not/there/yet");
    CertStore opened = CertStore.open(fresh);
    assertTrue(fresh.isDirectory());
    assertFalse(opened.hasAccount());
  }

  @Test
  public void aCertsPathThatIsAFileIsRefused() throws Exception {
    File file = new File(dir, "notes.txt");
    Files.writeString(file.toPath(), "hello");
    try {
      CertStore.open(file);
      fail("expected a refusal");
    } catch (ConfigException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("not a directory"));
    }
  }

  @Test
  public void theAccountRoundTrips() throws Exception {
    givenAnAccount();
    assertTrue(store.hasAccount());
    CertStore.AccountRecord read = store.readAccount();
    assertEquals("owner@example.com", read.contact());
    assertEquals(Acme.STAGING, read.directory());
    assertTrue(read.staging());
    assertTrue(read.describe().contains("staging"));
  }

  @Test
  public void privateKeysAreNotWorldReadable() throws Exception {
    givenAnAccount();
    java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
        Files.getPosixFilePermissions(store.accountKeyFile().toPath());
    assertEquals("the account key is the one secret in this directory", 2, permissions.size());
    assertTrue(permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
    assertFalse(permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
  }

  @Test
  public void aStoredCertificateIsReadBackWithItsExpiry() throws Exception {
    String pem = FakeAuthority.selfSigned("example.org", 30L * 86_400_000L);
    store.writeCertificate("example.org", pem, "key");

    CertStore.Held held = store.held("example.org");
    assertNotNull(held);
    assertEquals("example.org", held.domain());
    assertEquals(29, held.daysLeft(System.currentTimeMillis()));
    assertTrue(store.bundleFile("example.org").isFile());
    assertTrue("the bundle carries what a TLS listener needs",
        Files.readString(store.bundleFile("example.org").toPath()).contains("\"cert\""));
  }

  @Test
  public void renewalIsTwentyDaysBeforeExpiry() throws Exception {
    long now = System.currentTimeMillis();
    store.writeCertificate("fresh.example.org", FakeAuthority.selfSigned("fresh.example.org", 40L * 86_400_000L), "k");
    store.writeCertificate("soon.example.org", FakeAuthority.selfSigned("soon.example.org", 19L * 86_400_000L), "k");

    assertFalse("40 days left is not urgent", store.held("fresh.example.org").needsRenewal(now));
    assertTrue("19 days left is inside the window", store.held("soon.example.org").needsRenewal(now));
    assertFalse(store.held("soon.example.org").isExpired(now));
  }

  @Test
  public void garbageOnDiskReadsAsNothingRatherThanCrashing() throws Exception {
    Files.writeString(store.chainFile("broken.example.org").toPath(), "this is not a certificate");
    assertNull(store.held("broken.example.org"));
    assertTrue("and it is not listed as held", store.all().isEmpty());
  }

  @Test
  public void aDomainCannotEscapeTheCacheDirectory() {
    // the filename comes from a config file, which is operator input, which is still input
    for (String nasty : new String[]{"../etc/shadow", "a/b", ".hidden", ""}) {
      try {
        store.keyFile(nasty);
        fail("expected a refusal for '" + nasty + "'");
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("refusing"));
      }
    }
  }

  // ---- the challenge, over real HTTP ---------------------------------------------------------------

  @Test
  public void theServerAnswersTheChallengeItself() throws Exception {
    // no bucket, no upload, no second system to keep in sync: the server that wants the certificate
    // is the one that answers
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      server.challenges.publish("the-token", "the-token.the-thumbprint");
      Http.Response answered = http.get(server.port, "example.org",
          Challenges.PREFIX + "the-token");
      assertEquals(200, answered.status);
      assertEquals("the-token.the-thumbprint", new String(answered.bytes, StandardCharsets.UTF_8).trim());
    } finally {
      configs.delete();
    }
  }

  @Test
  public void anUnknownTokenIsAPlainNotFound() throws Exception {
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      Http.Response response = http.get(server.port, "example.org", Challenges.PREFIX + "nope");
      assertEquals(404, response.status);
      assertEquals(1, server.challenges.missedCount());
    } finally {
      configs.delete();
    }
  }

  @Test
  public void theChallengeIsAnsweredEvenForAHostThisServerDoesNotServe() throws Exception {
    // it has to come before host resolution. A validation refused because the Host header did not
    // match a config is an opaque CA error an hour later, and possibly a rate limit
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      server.challenges.publish("tok", "tok.thumb");
      Http.Response response = http.get(server.port, "nothing.here.test", Challenges.PREFIX + "tok");
      assertEquals(200, response.status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void theChallengeSurvivesTheScannerShield() throws Exception {
    // a long random path is exactly what the shield is built to drop, and exactly what a token is
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      String token = "wp-admin-config-php-x9Z2kQ7hLm4nP1sT8vB3cD6fG0jK5rW";
      server.challenges.publish(token, "answer");
      assertEquals(200, http.get(server.port, "example.org", Challenges.PREFIX + token).status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void theChallengeIsAnsweredForADisabledDomain() throws Exception {
    // renewing the certificate of a domain somebody has temporarily switched off is legitimate
    Configs configs = Configs.dir().domain("off.example.org", "{\"name\":\"Off\",\"enabled\":false}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http http = new Http()) {
      server.challenges.publish("tok", "tok.thumb");
      assertEquals(200, http.get(server.port, "off.example.org", Challenges.PREFIX + "tok").status);
    } finally {
      configs.delete();
    }
  }

  @Test
  public void withdrawingStopsTheAnswer() throws Exception {
    Challenges challenges = new Challenges();
    challenges.publish("tok", "answer");
    assertEquals("answer", challenges.answerFor(Challenges.PREFIX + "tok"));
    challenges.withdraw("tok");
    assertNull(challenges.answerFor(Challenges.PREFIX + "tok"));
    assertEquals(0, challenges.pendingCount());
  }

  @Test
  public void onlyTheChallengePathIsAChallenge() {
    assertTrue(Challenges.isChallenge(Challenges.PREFIX + "x"));
    assertFalse("the prefix with no token is not one", Challenges.isChallenge(Challenges.PREFIX));
    assertFalse(Challenges.isChallenge("/.well-known/security.txt"));
    assertFalse(Challenges.isChallenge("/"));
    assertFalse(Challenges.isChallenge(null));
  }

  // ---- deciding when to order -----------------------------------------------------------------------

  private CertificateManager manager(FakeAuthority authority) {
    return new CertificateManager(store, authority, new Challenges(), Verbose.OFF);
  }

  @Test
  public void aDomainWithNoCertificateGetsOne() throws Exception {
    givenAnAccount();
    FakeAuthority authority = new FakeAuthority();
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("example.org"));

    assertEquals(1, certificates.sweep());
    assertEquals(List.of("example.org"), authority.ordered());
    assertNotNull(store.held("example.org"));
    assertTrue("and its key was written", store.keyFile("example.org").isFile());
  }

  @Test
  public void aCertificateWithPlentyOfTimeIsLeftAlone() throws Exception {
    givenAnAccount();
    FakeAuthority authority = new FakeAuthority();
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("example.org"));
    certificates.sweep();

    assertEquals("the second sweep should do nothing", 0, certificates.sweep());
    assertEquals(1, authority.issuedCount());
  }

  @Test
  public void aCertificateNearExpiryIsRenewed() throws Exception {
    givenAnAccount();
    store.writeCertificate("example.org", FakeAuthority.selfSigned("example.org", 5L * 86_400_000L), "k");
    FakeAuthority authority = new FakeAuthority();
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("example.org"));

    assertEquals(1, certificates.sweep());
    assertTrue("and now it has room again",
        store.held("example.org").daysLeft(System.currentTimeMillis()) > 80);
    assertTrue(String.join("\n", certificates.journal()).contains("renewing"));
  }

  @Test
  public void theDomainKeyIsKeptAcrossRenewals() throws Exception {
    givenAnAccount();
    FakeAuthority authority = new FakeAuthority();
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("example.org"));
    certificates.sweep();
    String firstKey = store.readKey("example.org");

    store.writeCertificate("example.org", FakeAuthority.selfSigned("example.org", 5L * 86_400_000L), "k");
    certificates.sweep();
    assertEquals("renewing reuses the key rather than churning it",
        firstKey, store.readKey("example.org"));
  }

  @Test
  public void oneBadDomainDoesNotStopTheOthers() throws Exception {
    // a community with four domains and one bad DNS record should end up with three certificates
    // and one clear complaint, not zero certificates
    givenAnAccount();
    FakeAuthority authority = new FakeAuthority() {
      @Override
      public Issued issue(Account account, String domain, String key, Publisher publisher) throws Exception {
        if (domain.equals("broken.example.org")) {
          throw new IllegalStateException("the CA could not reach broken.example.org to verify it");
        }
        return super.issue(account, domain, key, publisher);
      }
    };
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("a.example.org", "broken.example.org", "b.example.org"));

    assertEquals(2, certificates.sweep());
    assertNotNull(store.held("a.example.org"));
    assertNotNull(store.held("b.example.org"));
    assertNull(store.held("broken.example.org"));
    assertEquals(1, certificates.failedCount());
    assertTrue("and it says which one and why",
        String.join("\n", certificates.journal()).contains("could not reach broken.example.org"));
  }

  @Test
  public void aFailedDomainIsRetriedOnTheNextSweep() throws Exception {
    givenAnAccount();
    FakeAuthority authority = new FakeAuthority().failing("port 80 is closed");
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("example.org"));

    assertEquals(0, certificates.sweep());
    assertNull(store.held("example.org"));
    assertEquals("the DNS got fixed; the next sweep just works", 1, certificates.sweep());
    assertNotNull(store.held("example.org"));
  }

  @Test
  public void nothingIsOrderedWithoutAnAccount() throws Exception {
    FakeAuthority authority = new FakeAuthority();
    CertificateManager certificates = manager(authority);
    certificates.start(List.of("example.org"));

    assertEquals(0, certificates.sweep());
    assertTrue(authority.ordered().isEmpty());
    assertTrue(String.join("\n", certificates.journal()).contains("--do-cert-setup"));
  }

  @Test
  public void aWildcardIsRefusedWithAnExplanation() throws Exception {
    givenAnAccount();
    CertificateManager certificates = manager(new FakeAuthority());
    certificates.start(List.of("*.example.org"));
    assertEquals(0, certificates.sweep());
    assertTrue(String.join("\n", certificates.journal()).contains("DNS-01"));
  }

  @Test
  public void theChallengeIsPublishedDuringTheOrderAndTakenBackAfter() throws Exception {
    givenAnAccount();
    Challenges challenges = new Challenges();
    FakeAuthority authority = new FakeAuthority().verifyingWith(
        token -> challenges.answerFor(Challenges.PREFIX + token));
    CertificateManager certificates =
        new CertificateManager(store, authority, challenges, Verbose.OFF);
    certificates.start(List.of("example.org"));

    assertEquals("the fake CA only issues if the answer was actually being served",
        1, certificates.sweep());
    assertEquals("and nothing is left published afterwards", 0, challenges.pendingCount());
    assertEquals(1, challenges.servedCount());
  }

  // ---- which domains are managed ---------------------------------------------------------------------

  @Test
  public void junctionsAndLocalhostAreNotManaged() throws Exception {
    // "org" is in the tree because example.org hangs off it, and no authority will ever issue for
    // localhost -- asking is how you meet a rate limit for nothing
    Configs configs = Configs.dir()
        .domain("example.org", "{\"name\":\"Example\"}")
        .domain("junior.example.org", "{\"name\":\"Junior\"}")
        .domain("localhost", "{\"name\":\"Local\"}");
    try {
      Map<String, DomainConfig> all = DomainScanner.scan(configs.file(), Verbose.OFF).tree.all();
      List<String> managed = CertSetup.managedDomains(all);
      assertTrue(managed.contains("example.org"));
      assertTrue(managed.contains("junior.example.org"));
      assertFalse("no certificate authority issues for localhost", managed.contains("localhost"));
      assertFalse("and a junction is not a hostname", managed.contains("org"));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void aDisabledDomainIsNotManaged() throws Exception {
    Configs configs = Configs.dir()
        .domain("on.example.org", "{\"name\":\"On\"}")
        .domain("off.example.org", "{\"name\":\"Off\",\"enabled\":false}");
    try {
      List<String> managed = CertSetup.managedDomains(
          DomainScanner.scan(configs.file(), Verbose.OFF).tree.all());
      assertTrue(managed.contains("on.example.org"));
      assertFalse(managed.contains("off.example.org"));
    } finally {
      configs.delete();
    }
  }

  // ---- the walkthrough ---------------------------------------------------------------------------------

  /** run the walkthrough with canned answers, and hand back what it printed */
  private String walkthrough(String answers, Map<String, DomainConfig> domains, Acme acme) throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    CertSetup setup = new CertSetup(store, acme,
        new BufferedReader(new StringReader(answers)), new PrintStream(captured, true, StandardCharsets.UTF_8));
    setup.run(domains, 80);
    return captured.toString(StandardCharsets.UTF_8);
  }

  private Map<String, DomainConfig> oneDomain() throws Exception {
    Configs configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    try {
      return DomainScanner.scan(configs.file(), Verbose.OFF).tree.all();
    } finally {
      configs.delete();
    }
  }

  @Test
  public void theWalkthroughRegistersAnAccount() throws Exception {
    String printed = walkthrough(String.join("\n", "y", "y", "owner@example.com", "y"),
        oneDomain(), new FakeAuthority());

    assertTrue("it names the domains it is about to promise", printed.contains("example.org"));
    assertTrue("and explains the two requirements", printed.contains("Port 80"));
    assertTrue("and shows the terms", printed.contains("https://example.org/terms"));
    assertTrue(store.hasAccount());
    assertEquals("owner@example.com", store.readAccount().contact());
    assertTrue("staging by default, because rate limits are unforgiving",
        store.readAccount().staging());
  }

  @Test
  public void answeringNoToProductionGetsProduction() throws Exception {
    walkthrough(String.join("\n", "y", "n", "owner@example.com", "y"), oneDomain(), new FakeAuthority());
    assertFalse(store.readAccount().staging());
    assertEquals(Acme.PRODUCTION, store.readAccount().directory());
  }

  @Test
  public void sayingTheDomainsAreNotReadyStopsBeforeAnythingIsWritten() throws Exception {
    // the whole point: a person who has not pointed their DNS yet should not burn a rate limit
    walkthrough("n\n", oneDomain(), new FakeAuthority());
    assertFalse(store.hasAccount());
    assertFalse(store.accountKeyFile().exists());
  }

  @Test
  public void refusingTheTermsRegistersNothing() throws Exception {
    String printed = walkthrough(String.join("\n", "y", "y", "owner@example.com", "n"),
        oneDomain(), new FakeAuthority());
    assertTrue(printed.contains("Nothing was registered"));
    assertFalse(store.hasAccount());
  }

  @Test
  public void aFailedRegistrationLeavesNoHalfSetUpDirectory() throws Exception {
    // a key with no account reads as "half configured" on the next boot, which is worse than empty
    Acme failing = new FakeAuthority() {
      @Override
      public String registerAccount(String directory, String key, String email, boolean agree) {
        throw new IllegalStateException("the authority said no");
      }
    };
    String printed = walkthrough(String.join("\n", "y", "y", "owner@example.com", "y"),
        oneDomain(), failing);
    assertTrue(printed.contains("registration failed"));
    assertFalse(store.accountKeyFile().exists());
    assertFalse(store.hasAccount());
  }

  @Test
  public void aBadEmailIsCaughtBeforeTheAuthorityIsBothered() throws Exception {
    String printed = walkthrough(String.join("\n", "y", "y", "not-an-email", "y"),
        oneDomain(), new FakeAuthority());
    assertTrue(printed.contains("does not look like an email"));
    assertFalse(store.hasAccount());
  }

  @Test
  public void withNoDomainsThereIsNothingToSetUp() throws Exception {
    String printed = walkthrough("y\n", Map.of(), new FakeAuthority());
    assertTrue(printed.contains("no domains are configured")
        || printed.contains("nothing to get certificates for"));
    assertFalse(store.hasAccount());
  }

  @Test
  public void aNonInteractiveRunRefusesRatherThanAssuming() throws Exception {
    // this walkthrough exists to make somebody think, and a pipe cannot think
    String printed = walkthrough("", oneDomain(), new FakeAuthority());
    assertTrue(printed.contains("needs a terminal"));
    assertFalse(store.hasAccount());
  }

  @Test
  public void anExistingAccountHasToBeConfirmedBeforeItIsReplaced() throws Exception {
    givenAnAccount();
    String printed = walkthrough(String.join("\n", "y", "n"), oneDomain(), new FakeAuthority());
    assertTrue(printed.contains("already an account"));
    assertEquals("and answering no left the old one alone",
        "owner@example.com", store.readAccount().contact());
  }
}
