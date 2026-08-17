package io.hearth.content;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Static pages: stored raw, rendered on the way out, cached, and invalidated by the event bus.
 *
 * Driven through the real admin pages and the real site, because the interesting behaviour is the
 * join between them -- saving a template in one place has to change what a completely different
 * request sees, and only an end-to-end test proves the event actually flowed.
 */
public class ContentTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = new Browser(server.port, "example.org");
    admin.get("/register");
    admin.submit(Map.of("email", "boss@example.com"));
    admin.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
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

  private Browser visitor() {
    return new Browser(server.port, "example.org");
  }

  private void saveTemplate(String name, String body) throws Exception {
    admin.get("/admin/templates");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", name, "body", body));
  }

  private void savePage(String uri, String kind, String template, String body) throws Exception {
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", uri, "title", "A page",
        "kind", kind, "template_name", template, "published", "on", "body", body));
  }

  // ---- rendering -----------------------------------------------------------------------------

  @Test
  public void markdownBecomesHtml() throws Exception {
    savePage("/about", "markdown", "", "# Hello\n\nSome **bold** text.\n");
    Browser.Page page = visitor().get("/about");
    assertEquals(200, page.status());
    assertTrue(page.body(), page.contains("<h1"));
    assertTrue(page.contains("<strong>bold</strong>"));
  }

  @Test
  public void theFullMarkdownFeatureSetIsOn() throws Exception {
    savePage("/rich", "markdown", "",
        "| a | b |\n|---|---|\n| 1 | 2 |\n\n"
            + "~~struck~~ and https://example.com\n\n"
            + "- [x] done\n- [ ] todo\n\n"
            + "A note[^1]\n\n[^1]: the footnote\n");
    String body = visitor().get("/rich").body();
    assertTrue("tables", body.contains("<table>"));
    assertTrue("strikethrough", body.contains("<del>"));
    assertTrue("autolink", body.contains("href=\"https://example.com\""));
    assertTrue("task lists", body.contains("type=\"checkbox\""));
    assertTrue("footnotes", body.contains("footnote"));
  }

  @Test
  public void headingsGetAnchorsSoDeepLinksWork() throws Exception {
    savePage("/anchors", "markdown", "", "## A Section Title\n\ntext\n");
    assertTrue(visitor().get("/anchors").body().contains("id=\"a-section-title\""));
  }

  @Test
  public void anHtmlFragmentIsWrappedButNotRendered() throws Exception {
    saveTemplate("site", "<html><body><nav>NAV</nav>{{{body}}}</body></html>");
    savePage("/frag", "html", "site", "<p>already html</p>");
    String body = visitor().get("/frag").body();
    assertTrue(body.contains("<nav>NAV</nav>"));
    assertTrue(body.contains("<p>already html</p>"));
    assertFalse("html should not be markdown-escaped", body.contains("&lt;p&gt;"));
  }

  @Test
  public void aFullPageIsServedExactlyAsStored() throws Exception {
    saveTemplate("site", "<html><body><nav>NAV</nav>{{{body}}}</body></html>");
    savePage("/raw", "page", "site", "<!doctype html><html><body>just this</body></html>");
    String body = visitor().get("/raw").body();
    assertEquals("<!doctype html><html><body>just this</body></html>", body);
    assertFalse("a full page ignores the template entirely", body.contains("NAV"));
  }

  @Test
  public void aTemplateWrapsTheBodyAndSeesTheTitle() throws Exception {
    saveTemplate("site", "<html><head><title>{{title}}</title></head><body>{{{body}}}<footer>{{uri}}</footer></body></html>");
    savePage("/about", "markdown", "site", "hello");
    String body = visitor().get("/about").body();
    assertTrue(body.contains("<title>A page</title>"));
    assertTrue(body.contains("<footer>/about</footer>"));
    assertTrue(body.contains("<p>hello</p>"));
  }

  @Test
  public void aMissingTemplateServesTheBodyRatherThanFailing() throws Exception {
    savePage("/about", "markdown", "nosuchtemplate", "hello");
    Browser.Page page = visitor().get("/about");
    assertEquals("an operator typo must not take the page down", 200, page.status());
    assertTrue(page.contains("<p>hello</p>"));
  }

  @Test
  public void anUnpublishedPageIsNotServed() throws Exception {
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/draft", "title", "Draft",
        "kind", "markdown", "template_name", "", "body", "secret"));
    // no "published" checkbox in the form means unpublished
    Browser.Page page = visitor().get("/draft");
    assertFalse("a draft is not a page anybody can read", page.contains("secret"));
    assertEquals("and it is honestly missing rather than quietly something else",
        404, page.status());
  }

  @Test
  public void aPathWithNoPageIsNotFound() throws Exception {
    Browser.Page page = visitor().get("/nothing-here");
    assertEquals(404, page.status());
    assertTrue("wearing the community's own colours, with the way back on it",
        page.contains("Back to the front page"));
    assertTrue("and the offer to sign in, because a lapsed session is the ordinary reason to be"
        + " here", page.contains("sign in"));
  }

  // ---- events and invalidation ------------------------------------------------------------------

  @Test
  public void savingAPageEmitsAnEventNamingTheRow() throws Exception {
    savePage("/about", "markdown", "", "hello");
    MutationEvent event = server.events.recent(20).stream()
        .filter(candidate -> candidate.touches(Schema.CONTENT))
        .findFirst().orElse(null);
    assertNotNull("saving content must announce itself", event);
    assertEquals("example.org", event.domain());
    assertEquals(MutationEvent.Kind.insert, event.kind());
    assertNotNull("and say which row", event.key());
  }

  @Test
  public void editingAPageIsVisibleImmediately() throws Exception {
    savePage("/about", "markdown", "", "first version");
    assertTrue(visitor().get("/about").contains("first version"));
    savePage("/about", "markdown", "", "second version");
    Browser.Page page = visitor().get("/about");
    assertTrue("the cache must not outlive the edit", page.contains("second version"));
    assertFalse(page.contains("first version"));
  }

  @Test
  public void changingATemplateCascadesToEveryPageUsingIt() throws Exception {
    saveTemplate("site", "<html><body><footer>v1</footer>{{{body}}}</body></html>");
    savePage("/one", "markdown", "site", "page one");
    savePage("/two", "markdown", "site", "page two");
    savePage("/other", "markdown", "", "no template");

    // warm all three
    assertTrue(visitor().get("/one").contains("v1"));
    assertTrue(visitor().get("/two").contains("v1"));

    saveTemplate("site", "<html><body><footer>v2</footer>{{{body}}}</body></html>");

    assertTrue("the cascade has to reach every page that named it", visitor().get("/one").contains("v2"));
    assertTrue(visitor().get("/two").contains("v2"));
    assertFalse(visitor().get("/one").contains("v1"));
    assertTrue("a page with no template is untouched", visitor().get("/other").contains("no template"));
  }

  @Test
  public void savingATemplateEmitsAnEventForTheTemplateTable() throws Exception {
    saveTemplate("site", "<html>{{{body}}}</html>");
    assertTrue(server.events.recent(20).stream().anyMatch(event -> event.touches(Schema.TEMPLATES)));
  }

  @Test
  public void deletingAPageStopsServingIt() throws Exception {
    savePage("/gone", "markdown", "", "here for now");
    assertTrue(visitor().get("/gone").contains("here for now"));
    Browser.Page list = admin.get("/admin/content");
    String id = list.body().replaceAll("(?s).*name=\"id\" value=\"(\\d+)\".*", "$1");
    admin.submitTo("/admin/content", Map.of("action", "delete", "id", id));
    assertFalse(visitor().get("/gone").contains("here for now"));
  }

  // ---- the cache actually caches ----------------------------------------------------------------

  @Test
  public void theSecondRequestIsACacheHit() throws Exception {
    savePage("/about", "markdown", "", "hello");
    Browser visitor = visitor();
    visitor.get("/about");
    long hitsBefore = renderedStats().hits();
    visitor.get("/about");
    assertTrue("a warm page should not be re-rendered", renderedStats().hits() > hitsBefore);
  }

  private io.hearth.cache.TtlCache.Stats renderedStats() {
    return server.auth.forDomain("example.org").site.cacheStats().stream()
        .filter(stats -> stats.name().equals("rendered"))
        .findFirst().orElseThrow();
  }

  @Test
  public void contentIsScopedToItsDatabase() throws Exception {
    // a page saved on one domain must not appear on a domain with its own database
    Configs other = Configs.dir()
        .domain("a.test", "{\"admin_emails\":[\"boss@example.com\"]}")
        .domain("b.test", "{\"admin_emails\":[\"boss@example.com\"]}");
    try (TestServer twin = TestServer.ofConfigs(other.file())) {
      Browser bossA = new Browser(twin.port, "a.test");
      bossA.get("/register");
      bossA.submit(Map.of("email", "boss@example.com"));
      bossA.submit(Map.of("code", twin.mail().lastCodeFor("boss@example.com")));
      bossA.get("/admin/content");
      bossA.submitTo("/admin/content", Map.of("action", "save", "uri", "/only-a", "title", "A",
          "kind", "markdown", "template_name", "", "published", "on", "body", "only on a"));

      assertTrue(new Browser(twin.port, "a.test").get("/only-a").contains("only on a"));
      assertFalse(new Browser(twin.port, "b.test").get("/only-a").contains("only on a"));
    } finally {
      other.delete();
    }
  }

  @Test
  public void markdownCanInferATitle() {
    assertEquals("Hello", Markdown.inferTitle("# Hello\n\nbody"));
    assertEquals("just a line", Markdown.inferTitle("just a line\n\nmore"));
    assertEquals("", Markdown.inferTitle(""));
    assertNull(Markdown.toHtml(null).isEmpty() ? null : "should be empty");
  }
}
