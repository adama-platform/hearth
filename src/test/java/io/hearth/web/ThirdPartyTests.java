package io.hearth.web;

import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Vendored libraries, served from inside the jar.
 *
 * The path is the whole security surface: this is the one place in the server that turns a request
 * path into a classpath lookup, and a `..` that got through would read anything on it. So most of
 * these are refusals.
 */
public class ThirdPartyTests {
  private Configs configs;
  private TestServer server;
  private Http http;

  @Before
  public void setUp() throws Exception {
    configs = Configs.standard();
    server = TestServer.ofConfigs(configs.file());
    http = new Http();
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  // ---- the path rule, checked directly -----------------------------------------------------

  @Test
  public void aPackageVersionFileIsTheOnlyShapeAllowed() {
    assertTrue(ThirdParty.safe("milkdown/7.21.3/milkdown.js"));
    assertTrue("a scoped name and a nested file are still that shape",
        ThirdParty.safe("@scope/pkg/1.0.0/dist/thing.js"));

    assertFalse("too few segments", ThirdParty.safe("milkdown/milkdown.js"));
    assertFalse(ThirdParty.safe(""));
    assertFalse(ThirdParty.safe(null));
  }

  @Test
  public void nothingCanClimbOutOfTheDirectory() {
    for (String attempt : new String[]{
        "../../application.properties",
        "milkdown/../../../etc/passwd",
        "milkdown/7.21.3/../../../templates/layout.mustache",
        "..%2f..%2fx/y/z",
        "/etc/passwd/x/y",
        "milkdown/./../x/y",
        "a/b/c\\u0000.js"}) {
      assertFalse(attempt, ThirdParty.safe(attempt));
    }
  }

  @Test
  public void aSegmentThatIsOnlyDotsIsRefusedEvenThoughDotsAreAllowedInside() {
    assertTrue("a version has dots in it", ThirdParty.safe("p/7.21.3/f.js"));
    assertFalse(ThirdParty.safe("p/../f.js"));
    assertFalse(ThirdParty.safe("p/./f.js"));
    assertFalse(ThirdParty.safe("p/..../f.js"));
  }

  @Test
  public void anEmptySegmentIsRefused() {
    assertFalse(ThirdParty.safe("p//f.js"));
    assertFalse(ThirdParty.safe("/p/v/f.js"));
    assertFalse(ThirdParty.safe("p/v/"));
  }

  @Test
  public void theContentTypeFollowsTheExtension() {
    assertTrue(ThirdParty.contentType("p/v/a.js").startsWith("text/javascript"));
    assertTrue(ThirdParty.contentType("p/v/a.css").startsWith("text/css"));
    assertTrue(ThirdParty.contentType("p/v/a.woff2").startsWith("font/"));
    assertEquals("application/octet-stream", ThirdParty.contentType("p/v/a.exe"));
  }

  // ---- over HTTP ---------------------------------------------------------------------------

  @Test
  public void aVendoredFileIsServedAndCachedForALongTime() throws Exception {
    Http.Response response = http.get(server.port, "example.org",
        "/3rd/milkdown/7.21.3/theme.css");
    // the file is only present after `just third-party`, so a checkout without it still passes
    org.junit.Assume.assumeTrue("milkdown is vendored", response.status == 200);

    assertTrue(response.header("content-type"), response.header("content-type").startsWith("text/css"));
    String cache = response.header("cache-control");
    assertTrue(cache, cache.contains("immutable"));
    assertTrue("the version is in the path, so a year is honest", cache.contains("max-age=31536000"));
  }

  @Test
  public void somethingThatIsNotThereIs404RatherThanAnError() throws Exception {
    assertEquals(404, http.get(server.port, "example.org", "/3rd/nope/1.0.0/nope.js").status);
  }

  @Test
  public void aTraversalOverHttpGetsNothing() throws Exception {
    for (String path : new String[]{
        "/3rd/../templates/layout.mustache",
        "/3rd/milkdown/7.21.3/../../../logback.xml",
        "/3rd/a/b/../../../../pom.xml"}) {
      int status = http.get(server.port, "example.org", path).status;
      // 410 is the scanner shield getting there first, which is a refusal too -- a path with .. in
      // it is exactly the traffic it exists to swallow
      assertTrue(path + " answered " + status,
          status == 404 || status == 400 || status == 410);
    }
  }

  @Test
  public void itIsServedWithoutASessionAndOnAnyDomain() throws Exception {
    Http.Response response = http.get(server.port, "junior.example.org",
        "/3rd/milkdown/7.21.3/theme.css");
    org.junit.Assume.assumeTrue("milkdown is vendored", response.status == 200);
    assertTrue("the same bytes for every community, signed in or not", response.body.length() > 0);
  }

  @Test
  public void theEditorPageAsksForTheVendoredCopyAndNotACdn() throws Exception {
    String form = java.nio.file.Files.readString(
        java.nio.file.Path.of("src/main/resources/templates/admin/content_form.mustache"));
    assertTrue(form.contains("/3rd/milkdown/"));
    assertFalse("a page that loads from a CDN has told them a member was reading it",
        form.contains("cdn.jsdelivr.net"));
    assertFalse(form.contains("esm.sh"));
    assertTrue("and the textarea stays the field that is submitted",
        form.contains("name=\"body\""));
  }
}
