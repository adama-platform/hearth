package io.hearth.web;

import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The server, over a socket, driven by an HTTP client.
 *
 * One server for the whole class -- it is immutable once booted, so there is nothing to reset
 * between tests, and binding a socket per test method is wasted time.
 */
public class ServerHttpTests {
  private static Configs configs;
  private static TestServer server;
  private static Http http;

  @BeforeClass
  public static void boot() throws Exception {
    // a build that lost the surefire system property would silently test one hostname
    assertTrue("Host header must be settable; check surefire jdk.httpclient.allowRestrictedHeaders",
        Http.hostHeaderIsSettable());
    configs = Configs.standard();
    server = TestServer.ofConfigs(configs.file());
    http = new Http();
  }

  @AfterClass
  public static void teardown() {
    if (http != null) {
      http.close();
    }
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private static Http.Response get(String host, String path) throws Exception {
    return http.get(server.port, host, path);
  }

  // ---- the happy path ----------------------------------------------------------------------

  @Test
  public void configuredDomainSaysHello() throws Exception {
    Http.Response res = get("example.com", "/");
    assertEquals(200, res.status);
    assertTrue(res.bodyContains(":-)"));
    assertTrue(res.bodyContains("Hello, world."));
    assertTrue(res.bodyContains("Example"));
  }

  @Test
  public void everyConfiguredDomainIsServed() throws Exception {
    assertEquals(200, get("localhost", "/").status);
    assertEquals(200, get("example.com", "/").status);
    assertEquals(200, get("blog.example.com", "/").status);
  }

  @Test
  public void mostSpecificConfigWins() throws Exception {
    assertTrue(get("blog.example.com", "/").bodyContains("Example Blog"));
    assertFalse(get("example.com", "/").bodyContains("Example Blog"));
  }

  @Test
  public void wildcardCoversSubdomains() throws Exception {
    // covered means resolved, which now means redirected: a community has one address, and two
    // spellings of it is two sets of links and two session cookies
    assertEquals(308, get("www.example.com", "/").status);
    assertEquals("http://example.com/", get("www.example.com", "/").header("Location"));
    assertEquals("http://example.com/", get("a.b.c.example.com", "/").header("Location"));
    assertEquals("and nothing under a name nobody configured", 404,
        get("www.nope.org", "/").status);
  }

  @Test
  public void aRedirectFromASubdomainKeepsThePathAndTheQuery() throws Exception {
    assertEquals("http://example.com/deep/page?q=a%20b&n=2",
        get("www.example.com", "/deep/page?q=a%20b&n=2").header("Location"));
  }

  @Test
  public void anAddressNothingAnswersIsNotFound() throws Exception {
    // it used to be the front page with a 200, which is a lie to a person (the link appears to
    // work and shows something else), to a search engine (every typo is a page) and to anything
    // automated (a 404 is how a client learns an address is wrong)
    assertEquals(404, get("example.com", "/anything/at/all").status);
    assertEquals("and the front page is still the front page, query and all",
        200, get("example.com", "/?query=string").status);
  }

  // ---- the sad path ------------------------------------------------------------------------

  @Test
  public void unconfiguredDomainIsRefused() throws Exception {
    Http.Response res = get("nope.org", "/");
    assertEquals(404, res.status);
    assertTrue(res.bodyContains(":-("));
    assertTrue(res.bodyContains("Not found and not supported."));
  }

  @Test
  public void nonWildcardSubdomainIsRefused() throws Exception {
    assertEquals(200, get("localhost", "/").status);
    assertEquals(404, get("api.localhost", "/").status);
  }

  @Test
  public void disabledDomainLooksUnconfigured() throws Exception {
    Http.Response res = get("off.org", "/");
    assertEquals(404, res.status);
    assertTrue(res.bodyContains(":-("));
    // the kill switch must not leak that the domain exists at all
    assertFalse(res.bodyContains("Turned Off"));
  }

  @Test
  public void unconfiguredDomainNeverLeaksAConfiguredOne() throws Exception {
    String body = get("nope.org", "/").body;
    assertFalse(body.contains("Example"));
    assertFalse(body.contains("Local"));
  }

  // ---- host header handling ----------------------------------------------------------------

  @Test
  public void hostIsCaseInsensitive() throws Exception {
    assertEquals(200, get("EXAMPLE.COM", "/").status);
    assertEquals(200, get("Blog.Example.Com", "/").status);
  }

  @Test
  public void trailingDotResolvesTheSame() throws Exception {
    assertEquals(200, get("example.com.", "/").status);
  }

  @Test
  public void hostWithPortResolves() throws Exception {
    assertEquals(200, get("example.com:8080", "/").status);
  }

  @Test
  public void addressHostIsABadRequest() throws Exception {
    assertEquals(400, get("127.0.0.1", "/").status);
    assertEquals(400, get("127.0.0.1:8080", "/").status);
  }

  @Test
  public void malformedHostIsABadRequest() throws Exception {
    assertEquals(400, get("exa_mple.com", "/").status);
    assertEquals(400, get("example..com", "/").status);
    assertEquals(400, get("-example.com", "/").status);
  }

  @Test
  public void missingHostIsABadRequest() throws Exception {
    // HTTP/1.0 has no mandatory Host, so a raw socket is the only way to send none
    String raw = Http.raw(server.port, "GET / HTTP/1.0\r\n\r\n");
    assertTrue(raw.startsWith("HTTP/1.0 400") || raw.startsWith("HTTP/1.1 400"));
  }

  // ---- the shield --------------------------------------------------------------------------

  @Test
  public void scannerNoiseIsGone() throws Exception {
    assertEquals(410, get("example.com", "/wp-login.php").status);
    assertEquals(410, get("example.com", "/phpmyadmin/").status);
    assertEquals(410, get("example.com", "/actuator/env").status);
    assertEquals(410, get("example.com", "/.env").status);
  }

  @Test
  public void shieldRunsBeforeHostResolution() throws Exception {
    // an unconfigured domain asking for a shielded path gets the shield's answer, not a 404
    assertEquals(410, get("nope.org", "/wp-login.php").status);
  }

  @Test
  public void shieldedResponsesCarryNoBody() throws Exception {
    Http.Response res = get("example.com", "/wp-login.php");
    assertEquals(410, res.status);
    assertEquals(0, res.bytes.length);
  }

  @Test
  public void wellKnownIsNotShielded() throws Exception {
    // ACME and security.txt have to survive the scanner shield. There is a handler now, so an
    // unknown challenge token is a plain 404 rather than the catch-all home page -- and 410, which
    // is what the shield says, would mean validation was refused before it was even looked up.
    Http.Response response = get("example.com", "/.well-known/acme-challenge/token");
    assertEquals(404, response.status);
    // nothing serves security.txt, so it is missing rather than refused -- 410 is what the shield
    // says, and it would mean the request was thrown away before anybody looked it up
    assertEquals(404, get("example.com", "/.well-known/security.txt").status);
  }

  @Test
  public void aChallengeOnASubdomainIsAnsweredRatherThanRedirected() throws Exception {
    // this ordering is load bearing. An authority validating www.example.com fetches the token
    // *from www*, and a 308 to the primary domain is not an answer to that -- it would mean a
    // listed subdomain could never get a certificate, which is the whole reason to list one.
    assertEquals(404, get("www.example.com", "/.well-known/acme-challenge/token").status);
    assertEquals("and everything else on that name still goes home", 308,
        get("www.example.com", "/anything").status);
  }

  // ---- methods -----------------------------------------------------------------------------

  @Test
  public void headMatchesGetWithoutABody() throws Exception {
    Http.Response get = get("example.com", "/");
    Http.Response head = http.head(server.port, "example.com", "/");
    assertEquals(200, head.status);
    assertEquals(0, head.bytes.length);
    assertEquals(get.header("content-length"), head.header("content-length"));
    assertEquals(get.header("content-type"), head.header("content-type"));
  }

  @Test
  public void unimplementedMethodsAreRefused() throws Exception {
    for (String method : new String[]{"POST", "PUT", "DELETE", "PATCH", "OPTIONS"}) {
      Http.Response res = http.send(server.port, "example.com", method, "/", new byte[0]);
      assertEquals(method + " should be refused", 405, res.status);
      assertEquals("GET, HEAD", res.header("allow"));
    }
  }

  @Test
  public void unimplementedMethodsAreRefusedBeforeHostResolution() throws Exception {
    // a verb this server does not implement at all is refused without needing to know the domain
    assertEquals(405, http.send(server.port, "nope.org", "DELETE", "/", new byte[0]).status);
  }

  @Test
  public void postToAnUnconfiguredDomainIs404NotAllowed() throws Exception {
    // whether POST is allowed depends on which paths the domain's config declared as forms, so an
    // unconfigured domain gets the same refusal it gets for everything else
    assertEquals(404, http.send(server.port, "nope.org", "POST", "/", new byte[0]).status);
  }

  @Test
  public void postToANonFormPathIsRefused() throws Exception {
    Http.Response res = http.send(server.port, "example.com", "POST", "/not-a-form", new byte[0]);
    assertEquals(405, res.status);
    assertEquals("GET, HEAD", res.header("allow"));
  }

  // ---- response shape ----------------------------------------------------------------------

  @Test
  public void securityHeadersAreOnEveryResponse() throws Exception {
    Http.Response[] all = {
        get("example.com", "/"),
        get("nope.org", "/"),
        get("127.0.0.1", "/"),
        get("example.com", "/wp-login.php")};
    for (Http.Response res : all) {
      assertEquals("nosniff", res.header("X-Content-Type-Options"));
      assertEquals("no-referrer", res.header("Referrer-Policy"));
      assertEquals("SAMEORIGIN", res.header("X-Frame-Options"));
      assertNotNull(res.header("Content-Security-Policy"));
      assertEquals("none", res.header("Accept-Ranges"));
    }
  }

  @Test
  public void hstsIsNotSentOverPlaintext() throws Exception {
    // pinning a developer's browser to https for a port with no TLS behind it would be a bad day
    assertNull(get("example.com", "/").header("Strict-Transport-Security"));
  }

  @Test
  public void htmlIsUtf8() throws Exception {
    assertEquals("text/html; charset=utf-8", get("example.com", "/").header("content-type"));
    assertEquals("text/html; charset=utf-8", get("nope.org", "/").header("content-type"));
  }

  @Test
  public void contentLengthMatchesTheBody() throws Exception {
    Http.Response res = get("example.com", "/");
    assertEquals(res.bytes.length, Integer.parseInt(res.header("content-length")));
  }

  @Test
  public void aCommunityNameIsEscaped() throws Exception {
    // the name comes out of a config file, which is operator-written but still goes through escaping
    Configs configs = Configs.dir().domain("evil.test", "{\"name\":\"<script>x</script>\"}");
    try (TestServer server = TestServer.ofConfigs(configs.file()); Http client = new Http()) {
      Http.Response res = client.get(server.port, "evil.test", "/");
      assertEquals(200, res.status);
      assertTrue(res.bodyContains("&lt;script&gt;"));
      // Asked of the parsed document rather than of the bytes. The name is also the home screen
      // label, which is a meta attribute, and HTML does not require `<` to be escaped inside an
      // attribute value -- so the bytes legitimately contain the characters while the document
      // contains no such element. What matters is that nothing became a tag.
      org.jsoup.nodes.Document parsed = org.jsoup.Jsoup.parse(res.body);
      assertTrue("nothing the name contained became an element",
          parsed.select("script").stream().noneMatch(tag -> tag.data().contains("x")));
    } finally {
      configs.delete();
    }
  }

  @Test
  public void configPathIsNotDisclosed() throws Exception {
    // --verbose is off on this server, so the filesystem layout stays private
    assertFalse(get("example.com", "/").bodyContains("domain.cfg"));
    assertFalse(get("example.com", "/").bodyContains(configs.file().getPath()));
  }

  @Test
  public void noServerBanner() throws Exception {
    assertNull(get("example.com", "/").header("Server"));
  }

  // ---- connection behavior -----------------------------------------------------------------

  @Test
  public void successKeepsTheConnectionAlive() throws Exception {
    String raw = Http.raw(server.port,
        "GET / HTTP/1.1\r\nHost: example.com\r\n\r\nGET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
    assertEquals("both pipelined requests should be answered on one socket", 2, Http.countResponses(raw));
  }

  @Test
  public void failureClosesTheConnection() throws Exception {
    String raw = Http.raw(server.port,
        "GET / HTTP/1.1\r\nHost: nope.org\r\n\r\nGET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
    assertEquals("a 404 should end the connection", 1, Http.countResponses(raw));
    assertTrue(raw.contains("404"));
  }

  @Test
  public void manyRequestsInARow() throws Exception {
    for (int k = 0; k < 25; k++) {
      assertEquals(200, get("example.com", "/?n=" + k).status);
    }
  }

  @Test
  public void concurrentRequestsAllSucceed() throws Exception {
    int threads = 16;
    Thread[] workers = new Thread[threads];
    int[] statuses = new int[threads];
    for (int k = 0; k < threads; k++) {
      final int index = k;
      workers[k] = new Thread(() -> {
        try (Http client = new Http()) {
          statuses[index] = client.get(server.port, "example.com", "/?t=" + index).status;
        } catch (Exception ex) {
          statuses[index] = -1;
        }
      });
      workers[k].start();
    }
    for (Thread worker : workers) {
      worker.join(20000);
    }
    for (int k = 0; k < threads; k++) {
      assertEquals("worker " + k, 200, statuses[k]);
    }
  }

  @Test
  public void malformedRequestLineIsRejected() throws Exception {
    String raw = Http.raw(server.port, "NOT-A-REQUEST\r\n\r\n");
    assertTrue("expected a 400, got: " + firstLine(raw), firstLine(raw).contains(" 400"));
  }

  @Test
  public void absoluteFormUriStillUsesTheHostHeader() throws Exception {
    // a proxy-style request line must not become a second way to pick a virtual host
    String raw = Http.raw(server.port, "GET http://nope.org/ HTTP/1.1\r\nHost: example.com\r\n\r\n");
    assertTrue("expected the Host header to win, got: " + firstLine(raw),
        firstLine(raw).contains(" 200"));
    // and a host nobody configured is refused whatever the request line says
    String bad = Http.raw(server.port, "GET http://example.com/ HTTP/1.1\r\nHost: nope.org\r\n\r\n");
    assertTrue("got: " + firstLine(bad), firstLine(bad).contains(" 404"));
  }

  @Test
  public void unsupportedProtocolVersionIsRefused() throws Exception {
    String raw = Http.raw(server.port, "GET / HTTP/9.9\r\nHost: example.com\r\n\r\n");
    assertTrue("expected a 505, got: " + firstLine(raw), firstLine(raw).contains(" 505"));
  }

  @Test
  public void weNeverEchoAClientInventedProtocolVersion() throws Exception {
    // Netty parses HTTP/9.9 into a real version object; reflecting it in our status line would put
    // a token the client chose on the wire as if we spoke it
    String raw = Http.raw(server.port, "GET / HTTP/9.9\r\nHost: example.com\r\n\r\n");
    assertFalse("status line reflected the request's version: " + firstLine(raw), raw.startsWith("HTTP/9.9"));
    assertTrue(raw.startsWith("HTTP/1.1 "));
  }

  @Test
  public void http10IsAnsweredAsHttp10() throws Exception {
    String raw = Http.raw(server.port, "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n");
    assertTrue("expected an HTTP/1.0 answer, got: " + firstLine(raw), raw.startsWith("HTTP/1.0 200"));
  }

  private static String firstLine(String raw) {
    return raw.lines().findFirst().orElse("<no response>");
  }
}
