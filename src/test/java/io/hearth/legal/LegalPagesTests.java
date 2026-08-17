package io.hearth.legal;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The terms and the privacy policy: published from the first day, and overridable.
 *
 * The two things most likely to break here are both about reach rather than about text. A document
 * that needs a session is a document nobody receiving an invitation can read, and a default that
 * lives in the database is a default that stops improving the moment a community is created.
 */
public class LegalPagesTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
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

  @Test
  public void bothDocumentsAreThereBeforeAnybodyHasWrittenAnything() throws Exception {
    try (Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.org", "/legal/terms-of-service").status);
      assertEquals(200, http.get(server.port, "example.org", "/legal/privacy-policy").status);
      assertEquals(200, http.get(server.port, "example.org", "/legal").status);
      assertEquals(404, http.get(server.port, "example.org", "/legal/something-else").status);
    }
  }

  @Test
  public void theyAreReadableWithoutSigningIn() throws Exception {
    // every email links here, and most of those go to somebody with no account. "The terms you are
    // accepting are behind a login" is not a defensible sentence.
    try (Http http = new Http()) {
      Http.Response terms = http.get(server.port, "example.org", "/legal/terms-of-service");
      assertEquals(200, terms.status);
      assertTrue(terms.bodyContains("Terms of Service"));
      assertFalse("and it is the document, not a redirect to sign in",
          terms.bodyContains("name=\"email\""));
    }
  }

  @Test
  public void theCommunityNameIsFilledIn() throws Exception {
    try (Http http = new Http()) {
      Http.Response privacy = http.get(server.port, "example.org", "/legal/privacy-policy");
      assertTrue(privacy.bodyContains("Example Community"));
      assertTrue(privacy.bodyContains("example.org"));
      assertFalse("the placeholder itself never reaches a reader",
          privacy.bodyContains("{{community}}"));
    }
  }

  @Test
  public void thePrivacyPolicyNamesEveryCookieThereIs() throws Exception {
    try (Http http = new Http()) {
      Http.Response privacy = http.get(server.port, "example.org", "/legal/privacy-policy");
      assertTrue(privacy.bodyContains("session cookie"));
      assertTrue(privacy.bodyContains("CSRF"));
      assertTrue("and says plainly that there are no others",
          privacy.bodyContains("no third-party cookies"));
    }
  }

  @Test
  public void theTermsSayWhoIsNotAParty() throws Exception {
    // in a self-hosted community this is the fact somebody actually has to be told
    try (Http http = new Http()) {
      Http.Response terms = http.get(server.port, "example.org", "/legal/terms-of-service");
      assertTrue(terms.bodyContains("Hearth"));
      assertTrue(terms.bodyContains("hosting provider"));
      assertTrue("and events are the risk this kind of community actually carries",
          terms.bodyContains("at your own risk"));
    }
  }

  @Test
  public void everyPageLinksToThem() throws Exception {
    try (Http http = new Http()) {
      Http.Response home = http.get(server.port, "example.org", "/");
      assertTrue(home.bodyContains("/legal/terms-of-service"));
      assertTrue(home.bodyContains("/legal/privacy-policy"));
      assertTrue("with the cookie notice beside them, which is what a notice is",
          home.bodyContains("Two cookies"));
    }
  }

  @Test
  public void anAdminCanWriteTheirOwnAndPutItBack() throws Exception {
    Browser admin = signIn("boss@example.com");
    assertEquals(200, admin.get("/admin/legal").status());

    admin.submitToAndFollow("/admin/legal", Map.of("action", "save",
        "slug", "terms-of-service", "body", "# Our Terms\n\nBe kind at {{community}}."));

    try (Http http = new Http()) {
      Http.Response terms = http.get(server.port, "example.org", "/legal/terms-of-service");
      assertTrue(terms.bodyContains("Be kind at Example Community."));
      assertFalse("the shipped text is gone while an override stands",
          terms.bodyContains("at your own risk"));
    }
    assertTrue("and the listing says whose it is now",
        admin.get("/admin/legal").contains("yours"));

    admin.submitToAndFollow("/admin/legal",
        Map.of("action", "reset", "slug", "terms-of-service"));
    try (Http http = new Http()) {
      assertTrue("resetting picks the shipped text back up, improvements and all",
          http.get(server.port, "example.org", "/legal/terms-of-service")
              .bodyContains("at your own risk"));
    }
  }

  @Test
  public void savingAnEmptyDocumentIsTheSameAsResetting() throws Exception {
    Browser admin = signIn("boss@example.com");
    admin.submitToAndFollow("/admin/legal",
        Map.of("action", "save", "slug", "privacy-policy", "body", "# Ours"));
    admin.submitToAndFollow("/admin/legal",
        Map.of("action", "save", "slug", "privacy-policy", "body", ""));
    try (Http http = new Http()) {
      assertTrue("an empty box is somebody asking for the default back, not a blank policy",
          http.get(server.port, "example.org", "/legal/privacy-policy")
              .bodyContains("session cookie"));
    }
  }

  @Test
  public void theEditorStartsFromWhatIsPublishedRatherThanFromNothing() throws Exception {
    Browser admin = signIn("boss@example.com");
    Browser.Page form = admin.get("/admin/legal/edit/privacy-policy");
    assertEquals(200, form.status());
    assertTrue("editing the privacy policy starts from the privacy policy",
        form.contains("Cookies"));
  }

  @Test
  public void somebodyWithoutThePermissionSeesNoDoorAtAll() throws Exception {
    Browser member = signIn("member@example.com");
    assertEquals("a 403 would confirm what is behind it", 404,
        member.get("/admin/legal").status());
    assertFalse(member.get("/admin").contains("/admin/legal"));
  }

  @Test
  public void aDocumentThisServerDoesNotPublishIsRefused() throws Exception {
    Browser admin = signIn("boss@example.com");
    assertTrue(admin.submitToAndFollow("/admin/legal",
        Map.of("action", "save", "slug", "cookie-policy", "body", "# Hi"))
        .contains("not a document"));
    assertTrue(admin.submitToAndFollow("/admin/legal",
        Map.of("action", "burn", "slug", "terms-of-service")).contains("not something this page"));
  }

  @Test
  public void anEditorAskedForADocumentThatIsNotThereLandsOnOne() throws Exception {
    // the form model falls back rather than showing an empty box that saves to nothing
    Browser admin = signIn("boss@example.com");
    assertEquals(200, admin.get("/admin/legal/edit/nonsense").status());
  }

  @Test
  public void theIndexListsBothAndSaysWhatEachIsFor() throws Exception {
    try (Http http = new Http()) {
      Http.Response index = http.get(server.port, "example.org", "/legal");
      assertTrue(index.bodyContains("Terms of Service"));
      assertTrue(index.bodyContains("Privacy Policy"));
      assertTrue(index.bodyContains("who is on the hook for what"));
    }
  }

  @Test
  public void thereIsNothingToPostTo() throws Exception {
    // a document is read, never submitted; POST falls through to the 405 every other read-only
    // path gets rather than into the handler
    try (Http http = new Http()) {
      assertEquals(405, http.send(server.port, "example.org", "POST",
          "/legal/terms-of-service", "x=1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .status);
    }
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
