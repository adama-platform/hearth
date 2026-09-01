package io.hearth.content;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
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
 * A page whose body is a program, driven the way a person drives it.
 *
 * Saved through the admin editor and fetched over HTTP as an anonymous visitor, because the join is
 * where this can go wrong: the kind has to survive the form, the engine has to run on its own
 * threads, what {@code render} built has to reach the template, and what {@code meta} set has to
 * beat what the page has stored.
 *
 * The refusals matter more than the happy path here. This is the first thing in Hearth that runs
 * code somebody typed, so the tests that count are the ones proving a mistake is contained: an
 * infinite loop is stopped, a thrown error becomes a message rather than a blank page, and neither
 * takes the server with it.
 */
public class DynamicPageTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

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

  private void saveProgram(String uri, String body) throws Exception {
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", uri, "title", "Stored title",
        "kind", "javascript", "template_name", "", "published", "on", "body", body));
  }

  @Test
  public void whatTheProgramRendersIsThePage() throws Exception {
    saveProgram("/dynamic", "render('<h1>Hello</h1>'); render('<p>from V8</p>');");

    Browser.Page page = visitor().get("/dynamic");
    assertEquals(200, page.status());
    assertTrue(page.body(), page.contains("<h1>Hello</h1>"));
    assertTrue("every render() call, in the order they were made",
        page.body().indexOf("<h1>Hello</h1>") < page.body().indexOf("<p>from V8</p>"));
  }

  @Test
  public void aLoopBuildsTheDocument() throws Exception {
    saveProgram("/list", "for (var i = 1; i <= 3; i++) { render('<li>' + i + '</li>'); }");
    assertTrue(visitor().get("/list").contains("<li>1</li><li>2</li><li>3</li>"));
  }

  /**
   * meta() beats the stored title, which is the whole point of it.
   *
   * The declared fields lose to the boxes above them because those are the page's own answer; a
   * program is the opposite case -- it ran a millisecond ago and the boxes were typed once. A
   * meta('title') that could not replace the stored title would not be manipulating the title.
   */
  @Test
  public void metaSetsTheTitleAndTheTemplatesFields() throws Exception {
    admin.get("/admin/templates");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "shell",
        "body", "<title>{{title}}</title><h2>{{subtitle}}</h2><main>{{{body}}}</main>"));
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/meta",
        "title", "Stored title", "kind", "javascript", "template_name", "shell",
        "published", "on",
        "body", "meta('title', 'Set by the program'); meta('subtitle', 'and so was this');"
            + " render('<p>body</p>');"));

    Browser.Page page = visitor().get("/meta");
    assertTrue(page.body(), page.contains("<title>Set by the program</title>"));
    assertFalse("the stored title lost, on purpose", page.contains("Stored title"));
    assertTrue("and an undeclared name reaches the template just the same",
        page.contains("<h2>and so was this</h2>"));
    assertTrue(page.contains("<main><p>body</p></main>"));
  }

  @Test
  public void aProgramCannotOverwriteTheBodyItJustBuilt() throws Exception {
    admin.get("/admin/templates");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "plain",
        "body", "<main>{{{body}}}</main>"));
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/nobody",
        "title", "t", "kind", "javascript", "template_name", "plain", "published", "on",
        "body", "render('<p>real</p>'); meta('body', '<p>hijacked</p>');"));

    Browser.Page page = visitor().get("/nobody");
    assertTrue(page.contains("<p>real</p>"));
    assertFalse("meta('body') would silently throw away every render() call",
        page.contains("hijacked"));
  }

  // ---- the refusals, which are the reason this is safe to have at all ---------------------------

  /**
   * An infinite loop is stopped, and the server is still there afterwards.
   *
   * This is the test that decides whether the feature can exist. Without V8's guard a
   * {@code while(true){}} in a page body holds a pool thread for ever, and the fourth such page
   * takes the whole feature down with it -- so the assertion is not only that this request answers
   * but that an ordinary page still answers after it.
   */
  @Test
  public void aRunawayProgramIsStoppedAndTheServerSurvives() throws Exception {
    saveProgram("/runaway", "while (true) { }");
    saveProgram("/fine", "render('<p>still here</p>');");

    long started = System.currentTimeMillis();
    Browser.Page page = visitor().get("/runaway");
    long took = System.currentTimeMillis() - started;

    assertEquals(200, page.status());
    assertTrue("it says what happened rather than serving a blank page: " + page.body(),
        page.contains("did not run"));
    assertTrue("stopped near the engine's ceiling rather than run to completion, in " + took + "ms",
        took < 15_000);
    assertTrue("and the next page is served as though nothing happened",
        visitor().get("/fine").contains("still here"));
  }

  @Test
  public void anErrorBecomesAMessageWithTheAuthorsLineNumber() throws Exception {
    saveProgram("/broken", "render('one');\nrender('two');\nnope();");

    Browser.Page page = visitor().get("/broken");
    assertEquals(200, page.status());
    assertTrue(page.body(), page.contains("nope is not defined"));
    assertTrue("the line the author wrote, not the line V8 ran: " + page.body(),
        page.contains("line 3"));
    assertFalse("a half-built page looks finished and is not",
        page.contains(">one<"));
  }

  @Test
  public void aSyntaxErrorIsReportedRatherThanServedAsSource() throws Exception {
    saveProgram("/syntax", "render('unclosed");
    Browser.Page page = visitor().get("/syntax");
    assertTrue(page.body(), page.contains("did not run"));
    assertFalse("the source is never the fallback", page.contains("render('unclosed"));
  }

  /**
   * Nothing a program defines survives into the next request.
   *
   * A fresh isolate per execution is what buys this, and it is worth a test because the tempting
   * optimisation -- one runtime per pool thread -- looks identical until two pages on the same
   * thread start seeing each other's globals.
   */
  @Test
  public void oneProgramCannotSeeAnother() throws Exception {
    saveProgram("/setter", "globalThis.secret = 'leaked'; render('set');");
    saveProgram("/reader", "render(typeof globalThis.secret);");

    assertTrue(visitor().get("/setter").contains("set"));
    for (int attempt = 0; attempt < 6; attempt++) {
      assertTrue("a later page must never see it, whichever thread it lands on",
          visitor().get("/reader").contains("undefined"));
    }
  }

  @Test
  public void aProgramHasNoNetworkNoStorageAndNoClockBeyondTheMachines() throws Exception {
    saveProgram("/reach", "render([typeof fetch, typeof require, typeof XMLHttpRequest,"
        + " typeof process, typeof java].join(','));");
    assertTrue(visitor().get("/reach").body(),
        visitor().get("/reach").contains("undefined,undefined,undefined,undefined,undefined"));
  }

  // ---- what the admin section shows about it ----------------------------------------------------

  /**
   * Every kind is timed, and the listing prints the worst of the last fifty.
   *
   * A dynamic page is the one worth watching, but the column is only readable because the markdown
   * page beside it also has a number -- 40ms means nothing until the thing next to it is 0.3ms.
   */
  @Test
  public void theListingShowsWhatEveryPageCosts() throws Exception {
    saveProgram("/timed", "for (var i = 0; i < 500; i++) { render('<i>' + i + '</i>'); }");
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/static",
        "title", "Static", "kind", "markdown", "template_name", "", "published", "on",
        "body", "# just words"));

    visitor().get("/timed");
    visitor().get("/timed");
    visitor().get("/static");

    RenderTimes.Stat dynamic = server.auth.forDomain("example.org").site.times().of("/timed");
    assertNotNull("a program is timed", dynamic);
    assertEquals("and not cached, so every request is an execution", 2, dynamic.samples());

    RenderTimes.Stat stat = server.auth.forDomain("example.org").site.times().of("/static");
    assertNotNull("markdown is timed too, which is what makes the column readable", stat);

    String listing = admin.get("/admin/content").body();
    assertTrue("the column is on the screen", listing.contains("p99"));
    assertTrue(listing, listing.contains(dynamic.p99Shown()));
  }

  /**
   * An agent cannot write one, and is not told the kind exists.
   *
   * This is the sharpest edge the dynamic kind added. An agent acts as the person who connected
   * it, that person may well hold content_write, and "write me an about page" must not be a route
   * to running code on this server. Both directions are checked: it cannot create one, and it
   * cannot convert a document it is allowed to edit into one.
   */
  @Test
  public void anAgentCannotWriteAProgram() throws Exception {
    io.hearth.testkit.McpClient grok =
        new io.hearth.testkit.McpClient(server.port, "example.org")
            .connect(admin, "https://grok.com/connectors/callback");

    String refusal = grok.call("content_save", "uri", "/agent-code",
        "kind", "javascript", "body", "render('hi');").refusal();
    assertTrue(refusal, refusal.contains("runs code on the server"));
    assertEquals("and nothing was written", null,
        server.auth.forDomain("example.org").site.store().byUri("/agent-code"));

    saveProgram("/mine", "render('mine');");
    String second = grok.call("content_save", "uri", "/mine", "body", "just words").refusal();
    assertTrue(second, second.contains("only a person"));

    String spec = grok.call("site_spec").toolResult().toString();
    assertFalse("a kind it may never use is not advertised", spec.contains("javascript"));
  }

  @Test
  public void aPageNothingHasAskedForHasNoTiming() throws Exception {
    saveProgram("/never", "render('x');");
    assertEquals(null, server.auth.forDomain("example.org").site.times().of("/never"));
    // the em dash itself: the whitespace compactor parses the HTML and re-serialises it, so the
    // entity written in the template arrives as the character it names
    assertTrue("and the listing says so with a dash rather than a zero",
        admin.get("/admin/content").contains("\u2014"));
  }
}
