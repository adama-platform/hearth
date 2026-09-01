package io.hearth.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What a connected model can actually do, and what it cannot.
 *
 * The interesting half is the second one. **Human only** is the feature that has to hold under
 * every angle of attack a model has: listing, searching, fetching by exact uri, and writing to a
 * uri it guessed. A locked page that merely fails to appear in a listing is not locked.
 */
public class McpToolTests {
  private static final String REDIRECT = "https://grok.com/connectors/callback";

  private Configs configs;
  private TestServer server;
  private Browser admin;
  private McpClient grok;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    admin = new Browser(server.port, "example.org");
    admin.get("/register");
    admin.submit(Map.of("email", "boss@example.com"));
    admin.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    grok = new McpClient(server.port, "example.org").connect(admin, REDIRECT);
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

  private void page(String uri, String title, String body) throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", uri, "title", title,
        "kind", "markdown", "template_name", "", "published", "on", "body", body));
  }

  private void lockedPage(String uri, String title, String body) throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", uri, "title", title,
        "kind", "markdown", "template_name", "", "published", "on", "body", body,
        "human_only", "on"));
  }

  // ---- content -----------------------------------------------------------------------------------

  @Test
  public void itCanListAndReadPages() throws Exception {
    page("/about", "About us", "We meet on Tuesdays.");
    McpClient.Response list = grok.call("content_list");
    assertEquals(200, list.status());
    assertTrue(list.toolResult().path("pages").toString().contains("/about"));

    McpClient.Response got = grok.call("content_get", "uri", "/about");
    assertEquals("We meet on Tuesdays.", got.toolResult().path("body").asText());
  }

  @Test
  public void itCanSearchByWhatIsInsideAPage() throws Exception {
    page("/about", "About us", "We meet on Tuesdays in the back room.");
    page("/rules", "Rules", "Be kind.");
    McpClient.Response hits = grok.call("content_search", "query", "Tuesdays");
    assertEquals(1, hits.toolResult().path("count").asInt());
    assertTrue(hits.toolResult().path("matches").get(0).path("excerpt").asText().contains("Tuesdays"));
  }

  @Test
  public void itCanWriteAPageAndThePageIsThenServed() throws Exception {
    // deliberately not /welcome: that is the orientation flow's address, and a route wins over a
    // page from the content table
    McpClient.Response saved = grok.call("content_save",
        "uri", "/hello", "title", "Welcome", "body", "# Hello\n\nGlad you are here.");
    assertEquals(200, saved.status());
    assertTrue(saved.toolResult().path("created").asBoolean());

    Browser visitor = new Browser(server.port, "example.org");
    Browser.Page served = visitor.get("/hello");
    assertEquals("what the model wrote is what the site serves", 200, served.status());
    assertTrue(served.contains("Glad you are here."));
  }

  @Test
  public void anUpdateOnlyChangesWhatItMentions() throws Exception {
    page("/about", "About us", "Original body.");
    grok.call("content_save", "uri", "/about", "body", "Rewritten body.");
    JsonNode after = grok.call("content_get", "uri", "/about").toolResult();
    assertEquals("Rewritten body.", after.path("body").asText());
    assertEquals("the title it did not mention is untouched", "About us", after.path("title").asText());
  }

  // ---- human only --------------------------------------------------------------------------------

  @Test
  public void aLockedPageIsInvisibleFromEveryAngle() throws Exception {
    page("/open", "Open", "anybody can read this");
    lockedPage("/private", "Private", "the secret handshake is a nod");

    assertFalse("not in a listing",
        grok.call("content_list").toolResult().toString().contains("/private"));
    assertFalse("not in a search by title",
        grok.call("content_search", "query", "Private").toolResult().toString().contains("/private"));
    assertFalse("not in a search by body",
        grok.call("content_search", "query", "handshake").toolResult().toString().contains("handshake"));
    assertFalse("not in the navigation",
        grok.call("navigation_get").toolResult().toString().contains("/private"));

    McpClient.Response fetched = grok.call("content_get", "uri", "/private");
    assertTrue(fetched.isToolError());
    assertEquals("and fetching it by exact uri answers as if it were simply absent",
        "there is no page at '/private'", fetched.refusal());
  }

  @Test
  public void aLockedPageCannotBeOverwrittenByGuessingItsUri() throws Exception {
    // the one place the lock speaks up. Silently letting a write land would be catastrophic, and
    // pretending it worked would teach the model it succeeded -- so this refusal says what it is
    lockedPage("/private", "Private", "the original");
    McpClient.Response refused = grok.call("content_save", "uri", "/private", "body", "clobbered");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("human only"));

    Browser visitor = new Browser(server.port, "example.org");
    assertTrue("and the page is exactly as it was", visitor.get("/private").contains("the original"));
  }

  @Test
  public void aLockedPageCannotBeDeleted() throws Exception {
    lockedPage("/private", "Private", "still here");
    McpClient.Response refused = grok.call("content_delete", "uri", "/private");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal().contains("human only"));
    assertNotNull(server.auth.forDomain("example.org").site.store().byUri("/private"));
  }

  @Test
  public void anAgentCannotLockOrUnlockAnything() throws Exception {
    // the bit belongs to the person at the admin screen; a model that could clear it could clear
    // its own restriction
    lockedPage("/private", "Private", "locked");
    grok.call("content_save", "uri", "/private", "human_only", false);
    assertTrue("still locked",
        server.auth.forDomain("example.org").site.store().byUri("/private").humanOnly());

    grok.call("content_save", "uri", "/fresh", "body", "new", "human_only", true);
    assertFalse("and it cannot set the bit either",
        server.auth.forDomain("example.org").site.store().byUri("/fresh").humanOnly());
  }

  @Test
  public void peopleStillSeeALockedPageNormally() throws Exception {
    // human only is about models, not about publication
    lockedPage("/private", "Private", "for the humans");
    Browser visitor = new Browser(server.port, "example.org");
    Browser.Page served = visitor.get("/private");
    assertEquals(200, served.status());
    assertTrue(served.contains("for the humans"));
    assertTrue("and the admin listing shows it",
        admin.get("/admin/content/list").contains("/private"));
  }

  // ---- templates ---------------------------------------------------------------------------------

  @Test
  public void itCanManageTemplatesAndIsToldWhatItAffected() throws Exception {
    grok.call("template_save", "name", "site", "body", "<html><body>{{{body}}}</body></html>");
    page("/a", "A", "hello");
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/a", "title", "A",
        "kind", "markdown", "template_name", "site", "published", "on", "body", "hello"));

    McpClient.Response saved = grok.call("template_save", "name", "site",
        "body", "<html><body><main>{{{body}}}</main></body></html>");
    assertEquals(1, saved.toolResult().path("re_rendered").asInt());
    assertTrue(saved.toolResult().path("pages").toString().contains("/a"));

    Browser visitor = new Browser(server.port, "example.org");
    assertTrue("and the change reached the served page", visitor.get("/a").contains("<main>"));
  }

  @Test
  public void aTemplateInUseCannotBeDeleted() throws Exception {
    grok.call("template_save", "name", "site", "body", "<html>{{{body}}}</html>");
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/a", "title", "A",
        "kind", "markdown", "template_name", "site", "published", "on", "body", "hi"));
    McpClient.Response refused = grok.call("template_delete", "name", "site");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal().contains("still used by"));
  }

  // ---- survey ------------------------------------------------------------------------------------




  // ---- read only ------------------------------------------------------------------------------------

  @Test
  public void aReadOnlyConnectionCanLookButNotTouch() throws Exception {
    Configs readOnly = Configs.dir().domain("quiet.test",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"],\"read-only\":true}}");
    try (TestServer other = TestServer.ofConfigs(readOnly.file())) {
      Browser owner = new Browser(other.port, "quiet.test");
      owner.get("/register");
      owner.submit(Map.of("email", "boss@example.com"));
      owner.submit(Map.of("code", other.mail().lastCodeFor("boss@example.com")));
      McpClient client = new McpClient(other.port, "quiet.test").connect(owner, REDIRECT);

      assertEquals(200, client.call("content_list").status());
      McpClient.Response refused = client.call("content_save", "uri", "/x", "body", "y");
      assertTrue(refused.isToolError());
      assertTrue(refused.refusal(), refused.refusal().contains("read only"));
    } finally {
      readOnly.delete();
    }
  }

  // ---- the AI log -------------------------------------------------------------------------------------

  @Test
  public void everythingItDoesIsWrittenDown() throws Exception {
    grok.call("content_save", "uri", "/logged", "title", "Logged", "body", "hello");
    var actions = server.aiLog.recent(10);
    assertFalse(actions.isEmpty());

    AiLog.Action last = actions.get(actions.size() - 1);
    assertEquals("content_save", last.tool());
    assertEquals("/logged", last.subject());
    assertEquals(AiLog.Outcome.ok, last.outcome());
    assertEquals("under the name of the person who authorized it", "boss@example.com", last.email());
    assertEquals("and the name of the agent that did it", "Test Connector", last.agent());
    assertTrue("with the arguments kept as JSON", last.argumentsJson().contains("/logged"));
    assertTrue("rendered for reading", last.prettyArguments().contains("\n"));
    assertTrue("and flagged as a change", last.changedSomething());
  }

  @Test
  public void aRefusalIsLoggedAsLoudlyAsASuccess() throws Exception {
    lockedPage("/private", "Private", "locked");
    grok.call("content_save", "uri", "/private", "body", "clobbered");
    AiLog.Action last = server.aiLog.recent(1).get(0);
    assertEquals(AiLog.Outcome.refused, last.outcome());
    assertTrue(last.detail().contains("human only"));
    assertFalse("a refusal changed nothing", last.changedSomething());
  }

  @Test
  public void theLogSeparatesReadingFromChanging() throws Exception {
    grok.call("content_list");
    grok.call("content_save", "uri", "/one", "body", "x");
    grok.call("content_search", "query", "x");
    assertEquals("only the write counts as a change", 1, server.aiLog.writeCount());
    assertEquals(1, server.aiLog.search(null, null, true, 50).size());
    // three tool calls plus the authorization itself, which is worth a line of its own
    assertEquals(4, server.aiLog.search(null, AiLog.Outcome.ok, false, 50).size());
    assertEquals(1, server.aiLog.search("oauth_authorize", null, false, 50).size());
    assertEquals("and it is searchable by what was touched",
        1, server.aiLog.search("/one", null, false, 50).size());
  }

  @Test
  public void theLogKeepsAThousandAndNoMore() throws Exception {
    AiLog log = new AiLog(3);
    for (int k = 1; k <= 5; k++) {
      log.record("d", "a", 1, "e", "content_list", "s" + k, AiLog.Outcome.ok, "d", null, null, 0);
    }
    assertEquals(3, log.size());
    assertEquals("the oldest fall off the front", "s3", log.recent(10).get(0).subject());
    assertEquals(5, log.recorded());
  }


}
