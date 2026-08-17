package io.hearth.web;

import io.hearth.certs.CertStore;
import io.hearth.certs.CertificateManager;
import io.hearth.certs.Challenges;
import io.hearth.certs.FakeAuthority;
import io.hearth.certs.TlsContexts;
import io.hearth.common.Verbose;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Serving HTTPS with the certificates the ACME work obtains.
 *
 * The point of the whole chain: a certificate that is fetched, cached and renewed but never
 * presented is not a feature. So these go over a real TLS socket, check the certificate the server
 * hands back is the one for the host that was asked for, and check that a renewal is picked up
 * without a restart -- because that last one is the difference between automatic renewal and an
 * outage in ninety days.
 */
public class TlsTests {
  private File certs;
  private CertStore store;
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    certs = Files.createTempDirectory("hearth-tls-test").toFile();
    store = CertStore.open(certs);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
    deleteTree(certs);
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

  private void givenACertificateFor(String domain) throws Exception {
    FakeAuthority.Pair pair = FakeAuthority.selfSignedPair(domain, 60L * 86_400_000L);
    store.writeCertificate(domain, pair.chainPem(), pair.keyPem());
  }

  /** boot a server with TLS on an ephemeral port, serving whatever is in the cert cache */
  private TestServer secureServer(String... domainConfigs) throws Exception {
    Configs built = Configs.dir();
    for (String domain : domainConfigs) {
      built = built.domain(domain, "{\"name\":\"" + domain + "\"}");
    }
    configs = built;
    TlsContexts tls = new TlsContexts(store, Verbose.OFF);
    tls.reload();
    server = TestServer.builder(configs.file()).withTls(tls).build();
    return server;
  }

  // ---- the handshake -------------------------------------------------------------------------------

  @Test
  public void httpsServesTheSiteWithTheDomainsOwnCertificate() throws Exception {
    givenACertificateFor("example.org");
    TestServer running = secureServer("example.org");

    Talk talk = get(running.httpsPort(), "example.org", "/");
    assertEquals(200, talk.status);
    assertTrue("it is the site, over TLS", talk.body.contains("example.org"));
    assertEquals("and the certificate is the one for that host",
        "CN=example.org", talk.certificate.getSubjectX500Principal().getName());
  }

  @Test
  public void sniPicksTheCertificatePerDomain() throws Exception {
    // the reason one process can host several communities on one address
    givenACertificateFor("first.example.org");
    givenACertificateFor("second.example.org");
    TestServer running = secureServer("first.example.org", "second.example.org");

    assertEquals("CN=first.example.org",
        get(running.httpsPort(), "first.example.org", "/").certificate.getSubjectX500Principal().getName());
    assertEquals("CN=second.example.org",
        get(running.httpsPort(), "second.example.org", "/").certificate.getSubjectX500Principal().getName());
  }

  @Test
  public void aHostWithNoCertificateGetsTheFallbackRatherThanADroppedConnection() throws Exception {
    // a refused connection looks exactly like a firewall problem and people lose hours to that; a
    // browser warning names the real problem in its first sentence
    givenACertificateFor("has.example.org");
    TestServer running = secureServer("has.example.org", "none.example.org");

    Talk talk = get(running.httpsPort(), "none.example.org", "/");
    assertEquals("the handshake completes", 200, talk.status);
    assertTrue("with something that is obviously not for this host",
        talk.certificate.getSubjectX500Principal().getName().contains("hearth.invalid"));
  }

  @Test
  public void plainHttpKeepsWorkingWithTlsOn() throws Exception {
    // port 80 cannot become a redirect: it is what answers the ACME challenge, so a domain with no
    // certificate yet needs it serving plain HTTP or it never gets one
    givenACertificateFor("example.org");
    TestServer running = secureServer("example.org");

    try (io.hearth.testkit.Http http = new io.hearth.testkit.Http()) {
      assertEquals(200, http.get(running.port, "example.org", "/").status);
      running.challenges.publish("tok", "tok.thumb");
      assertEquals("and still answers the challenge", 200,
          http.get(running.port, "example.org", Challenges.PREFIX + "tok").status);
    }
  }

  // ---- renewal reaches the listener ------------------------------------------------------------------

  @Test
  public void aRenewedCertificateIsPresentedWithoutARestart() throws Exception {
    // the difference between automatic renewal and an outage in ninety days
    givenACertificateFor("example.org");
    TlsContexts tls = new TlsContexts(store, Verbose.OFF);
    tls.reload();
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    server = TestServer.builder(configs.file()).withTls(tls).build();

    X509Certificate before = get(server.httpsPort(), "example.org", "/").certificate;

    // what the manager does when a renewal lands
    FakeAuthority.Pair renewed = FakeAuthority.selfSignedPair("example.org", 89L * 86_400_000L);
    store.writeCertificate("example.org", renewed.chainPem(), renewed.keyPem());
    assertTrue(tls.reload("example.org"));

    X509Certificate after = get(server.httpsPort(), "example.org", "/").certificate;
    assertFalse("a new socket gets the new certificate", before.equals(after));
    assertTrue("and it is the fresher one", after.getNotAfter().after(before.getNotAfter()));
  }

  @Test
  public void theManagerHandsNewCertificatesToTheTlsLayer() throws Exception {
    // the wiring the boot path relies on: issuing notifies, and the notification reloads
    store.writeAccountKey("-----BEGIN RSA PRIVATE KEY-----\nfake\n-----END RSA PRIVATE KEY-----\n");
    store.writeAccount(new CertStore.AccountRecord("https://example.org/acct/1",
        "owner@example.com", io.hearth.certs.Acme.STAGING, true));
    TlsContexts tls = new TlsContexts(store, Verbose.OFF);
    assertEquals("nothing to present yet", 0, tls.size());

    java.util.List<String> announced = new java.util.ArrayList<>();
    CertificateManager manager = new CertificateManager(store, new FakeAuthority(),
        new Challenges(), Verbose.OFF);
    manager.onIssued((domain, detail) -> {
      tls.reload(domain);
      announced.add(domain + ": " + detail);
    });
    manager.start(List.of("example.org"));
    assertEquals(1, manager.sweep());

    assertTrue("the operator is told what actually happened, not what is about to",
        announced.get(0).startsWith("example.org: good until"));
    assertTrue("and the certificate is presentable", tls.has("example.org"));
  }

  @Test
  public void aFailureIsAnnouncedToo() throws Exception {
    store.writeAccountKey("-----BEGIN RSA PRIVATE KEY-----\nfake\n-----END RSA PRIVATE KEY-----\n");
    store.writeAccount(new CertStore.AccountRecord("https://example.org/acct/1",
        "owner@example.com", io.hearth.certs.Acme.STAGING, true));
    java.util.List<String> problems = new java.util.ArrayList<>();
    CertificateManager manager = new CertificateManager(store,
        new FakeAuthority().failing("port 80 is closed"), new Challenges(), Verbose.OFF);
    manager.onFailed((domain, why) -> problems.add(domain + ": " + why));
    manager.start(List.of("example.org"));
    manager.sweep();

    assertEquals(1, problems.size());
    assertTrue(problems.get(0).contains("port 80 is closed"));
  }

  // ---- the bounce listener ----------------------------------------------------------------------------

  @Test
  public void theBouncePortRedirectsToHttps() throws Exception {
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    server = TestServer.builder(configs.file()).withBounce().build();

    Talk talk = plain(server.bouncePort(), "example.org", "GET /about?x=1 HTTP/1.1");
    assertEquals(308, talk.status);
    assertEquals("https://example.org/about?x=1", talk.location);
  }

  @Test
  public void theBouncePortDoesNothingElseAtAll() throws Exception {
    // its value is that it always works, which means there is nothing behind it to go wrong
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    server = TestServer.builder(configs.file()).withBounce().build();

    for (String path : new String[]{"/", "/admin", "/register", "/.well-known/acme-challenge/tok"}) {
      Talk talk = plain(server.bouncePort(), "example.org", "GET " + path + " HTTP/1.1");
      assertEquals(path + " should still be a redirect", 308, talk.status);
      assertEquals("https://example.org" + path, talk.location);
    }
  }

  @Test
  public void theBouncePortStripsTheIncomingPort() throws Exception {
    // whatever port they arrived on is not where they should be sent
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    server = TestServer.builder(configs.file()).withBounce().build();

    Talk talk = plain(server.bouncePort(), "example.org:9999", "GET / HTTP/1.1");
    assertEquals("https://example.org/", talk.location);
  }

  @Test
  public void theBouncePortRefusesAHostItCannotTrust() throws Exception {
    // this goes straight into a Location header, so anything that could split the response or point
    // somewhere else is refused rather than cleaned up
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    server = TestServer.builder(configs.file()).withBounce().build();

    assertEquals(400, plain(server.bouncePort(), "evil.example.org/\\path", "GET / HTTP/1.1").status);
    assertNull(plain(server.bouncePort(), "", "GET / HTTP/1.1").location);
  }

  @Test
  public void theBounceLocationIsBuiltCarefully() {
    assertEquals("https://example.org/a", BounceHandler.location("example.org", "/a", 443));
    assertEquals("port 443 is the default and does not belong in the url",
        "https://example.org/", BounceHandler.location("example.org", "/", 443));
    assertEquals("a non standard https port has to be carried",
        "https://example.org:8443/", BounceHandler.location("example.org", "/", 8443));
    assertNull("no host, nowhere to send them", BounceHandler.location(null, "/", 443));
    assertNull("a header injection attempt", BounceHandler.location("example.org\r\nX: y", "/", 443));
    assertNull("a path that could split the response",
        BounceHandler.location("example.org", "/a\r\nX: y", 443));
    assertNull("an absolute-form target is not ours to rewrite",
        BounceHandler.location("example.org", "http://elsewhere.test/", 443));
  }

  @Test
  public void thereIsNoBounceListenerUnlessItIsAskedFor() throws Exception {
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example\"}");
    server = TestServer.ofConfigs(configs.file());
    assertEquals("off by default", -1, server.bouncePort());
  }

  // ---- talking ------------------------------------------------------------------------------------------

  private record Talk(int status, String body, String location, X509Certificate certificate) {
    Talk(int status, String body, String location, X509Certificate certificate) {
      this.status = status;
      this.body = body;
      this.location = location;
      this.certificate = certificate;
    }
  }

  /** an HTTPS request with an explicit SNI name, trusting anything, keeping the peer certificate */
  private static Talk get(int port, String host, String path) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[]{TRUST_EVERYTHING}, new java.security.SecureRandom());
    SSLSocketFactory factory = context.getSocketFactory();
    try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", port)) {
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setServerNames(List.of(new SNIHostName(host)));
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      X509Certificate presented = (X509Certificate) socket.getSession().getPeerCertificates()[0];

      OutputStream out = socket.getOutputStream();
      out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.UTF_8));
      out.flush();
      Response response = read(socket);
      return new Talk(response.status, response.body, response.location, presented);
    }
  }

  /** a plain HTTP request written by hand, for the bounce port */
  private static Talk plain(int port, String host, String requestLine) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.getOutputStream().write((requestLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      Response response = read(socket);
      return new Talk(response.status, response.body, response.location, null);
    }
  }

  private record Response(int status, String body, String location) {
  }

  private static Response read(Socket socket) throws Exception {
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    String statusLine = reader.readLine();
    int status = statusLine == null ? -1 : Integer.parseInt(statusLine.split(" ")[1]);
    String location = null;
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      if (line.toLowerCase(java.util.Locale.ROOT).startsWith("location:")) {
        location = line.substring(9).trim();
      }
    }
    StringBuilder body = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      body.append(line).append('\n');
    }
    return new Response(status, body.toString(), location);
  }

  /** a test client that trusts anything, because the whole point is inspecting what was presented */
  private static final X509TrustManager TRUST_EVERYTHING = new X509TrustManager() {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  };
}
