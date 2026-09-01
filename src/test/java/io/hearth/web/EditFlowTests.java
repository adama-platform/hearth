package io.hearth.web;

import io.hearth.content.ContentRecord;
import io.hearth.content.TemplateRecord;
import io.hearth.people.ProfileRecord;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Writing something and getting it back.
 *
 * These exist because of a bug that should have been impossible to ship and was not: every long
 * field was read through an accessor with a 512 character ceiling that returned null past it, and
 * every caller turned null into the empty string. Writing more than a paragraph and pressing save
 * stored nothing, silently, over what was already there.
 *
 * It survived a suite of five hundred tests because every one of them posted a sentence. So the
 * numbers here are deliberate: the bodies are longer than a paragraph, because a test that writes
 * "hello" proves only that "hello" fits.
 */
public class EditFlowTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
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

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  /** a page of prose, the length somebody actually writes */
  private static String prose(int paragraphs) {
    StringBuilder text = new StringBuilder();
    for (int k = 0; k < paragraphs; k++) {
      text.append("## Section ").append(k).append("\n\n")
          .append("We meet on the first Tuesday of the month in the back room, and anybody who ")
          .append("wants to bring something to share is welcome to. The kitchen is small so ")
          .append("please ask before using the oven. Paragraph ").append(k).append(".\n\n");
    }
    return text.toString();
  }

  private ContentRecord stored(String uri) throws Exception {
    return server.auth.forDomain("example.org").site.store().byUri(uri);
  }

  private TemplateRecord storedTemplate(String name) throws Exception {
    return server.auth.forDomain("example.org").site.store().templateByName(name);
  }

  private Browser.Page saveContent(Map<String, String> fields) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("kind", "markdown");
    form.put("template_name", "");
    form.put("published", "on");
    form.putAll(fields);
    return admin.submitToAndFollow("/admin/content", form);
  }

  // ---- content -------------------------------------------------------------------------------

  @Test
  public void aRealPageSurvivesBeingSaved() throws Exception {
    // the reported bug, at the length it actually bites
    String body = prose(6);
    assertTrue("the fixture has to be longer than the old ceiling", body.length() > 512);

    saveContent(Map.of("uri", "/about", "title", "About us", "body", body));

    ContentRecord saved = stored("/about");
    assertNotNull("the page exists", saved);
    assertFalse("and it is not empty", saved.body().isEmpty());
    assertEquals("and it is exactly what was written", body, saved.body());
  }

  @Test
  public void aLongPageSurvivesTheWholeRoundTrip() throws Exception {
    // past the old 16KB whole-form ceiling too, which used to fail the CSRF check and read as
    // "that form expired"
    String body = prose(200);
    assertTrue(body.length() > 16 * 1024);

    saveContent(Map.of("uri", "/handbook", "title", "Handbook", "body", body));
    assertEquals(body, stored("/handbook").body());

    Browser.Page served = new Browser(server.port, "example.org").get("/handbook");
    assertEquals(200, served.status());
    assertTrue("and the site serves all of it", served.contains("Paragraph 199"));
  }

  @Test
  public void editingAPageKeepsWhatWasNotEdited() throws Exception {
    String first = prose(4);
    saveContent(Map.of("uri", "/about", "title", "About us", "body", first,
        "nav_folder", "Community"));
    long id = stored("/about").id();

    String second = prose(5) + "\n\nAnd one more thing.";
    saveContent(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us",
        "body", second, "nav_folder", "Community"));

    ContentRecord saved = stored("/about");
    assertEquals("the new body landed", second, saved.body());
    assertEquals("the title is still there", "About us", saved.title());
    assertEquals("and the folder", "Community", saved.navFolder());
    assertEquals("and it is still one page, not two", id, saved.id());
  }

  @Test
  public void theEditorGivesBackWhatIsStored() throws Exception {
    // the other half of the round trip: what the form is prefilled with has to be the real thing,
    // or the next save writes a truncated copy over the original
    String body = prose(5);
    saveContent(Map.of("uri", "/about", "title", "About us", "body", body));
    long id = stored("/about").id();

    Browser.Page editor = admin.get("/admin/content/edit/" + id);
    assertEquals(200, editor.status());
    assertTrue("the last paragraph has to be in the textarea, not just the first",
        editor.contains("Paragraph 4"));
    assertTrue(editor.contains("About us"));
  }

  @Test
  public void savingTwiceUnchangedChangesNothing() throws Exception {
    String body = prose(4);
    saveContent(Map.of("uri", "/about", "title", "About us", "body", body));
    long id = stored("/about").id();

    saveContent(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us", "body", body));
    assertEquals(body, stored("/about").body());
    assertEquals(1, server.auth.forDomain("example.org").site.store().contentCount());
  }

  @Test
  public void anOversizeShortFieldIsRefusedRatherThanStoredEmpty() throws Exception {
    // the rule that came out of this: too long must never become empty. A title is a short field
    // with a 512 character ceiling, and one past it used to be silently blanked.
    String body = prose(4);
    saveContent(Map.of("uri", "/about", "title", "About us", "body", body));

    Browser.Page refused = saveContent(Map.of("uri", "/about",
        "title", "About ".repeat(200), "body", body));

    assertTrue(refused.body(), refused.contains("too long"));
    assertTrue("it names the field so somebody can fix it", refused.contains("title"));
    assertEquals("and the page keeps the title it had", "About us", stored("/about").title());
    assertEquals("and its body", body, stored("/about").body());
  }

  @Test
  public void aRequestTooLargeToParseIsRefusedVisiblyAndChangesNothing() throws Exception {
    // past the aggregator, so the answer is an HTTP 413 rather than anything this code writes.
    // What matters is that it is a status somebody can see, and that nothing was overwritten.
    String body = prose(4);
    saveContent(Map.of("uri", "/about", "title", "About us", "body", body));

    String enormous = "x".repeat(Forms.MAX_TEXT_LENGTH + 1);
    Browser.Page refused = saveContent(Map.of("uri", "/about", "title", "About us", "body", enormous));

    assertEquals("too large is its own answer, not a silent success", 413, refused.status());
    assertEquals("and the page is exactly as it was", body, stored("/about").body());
  }

  @Test
  public void anOversizeFormIsNotReportedAsAnExpiredOne() throws Exception {
    // a body past the ceiling parses to no fields at all, so the CSRF token is missing for a reason
    // that has nothing to do with the token. Saying "that form expired" sends somebody looking in
    // entirely the wrong place.
    Forms parsed = formOf("x".repeat(Forms.MAX_FORM_BYTES + 10));
    assertTrue(parsed.bodyTooLarge());
    assertNull("and there is nothing to read out of it", parsed.get("csrf"));
  }

  private static Forms formOf(String body) {
    io.netty.handler.codec.http.FullHttpRequest req =
        new io.netty.handler.codec.http.DefaultFullHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            io.netty.handler.codec.http.HttpMethod.POST, "/admin/content",
            io.netty.buffer.Unpooled.copiedBuffer("body=" + body,
                java.nio.charset.StandardCharsets.UTF_8));
    req.headers().set("content-type", "application/x-www-form-urlencoded");
    return Forms.of(req);
  }

  // ---- templates -----------------------------------------------------------------------------

  private Browser.Page saveTemplate(Map<String, String> fields) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.putAll(fields);
    return admin.submitToAndFollow("/admin/templates", form);
  }

  private static String templateBody(int blocks) {
    StringBuilder html = new StringBuilder("<!doctype html>\n<html lang=\"en\">\n<head>\n"
        + "  <meta charset=\"utf-8\">\n  <title>{{title}}</title>\n  <style>\n");
    for (int k = 0; k < blocks; k++) {
      html.append("    .block-").append(k)
          .append("{margin:0 0 1rem;padding:.5rem;border:1px solid #ddd;border-radius:4px}\n");
    }
    html.append("  </style>\n</head>\n<body>\n  <main>{{{body}}}</main>\n</body>\n</html>\n");
    return html.toString();
  }

  @Test
  public void aRealTemplateSurvivesBeingSaved() throws Exception {
    // a template with any styling at all is well past 512 characters, which is to say every one
    String body = templateBody(20);
    assertTrue(body.length() > 512);

    saveTemplate(Map.of("name", "site", "body", body));

    TemplateRecord saved = storedTemplate("site");
    assertNotNull(saved);
    assertEquals("the whole template, not the first 512 characters", body, saved.body());
    assertTrue(saved.body().contains("{{{body}}}"));
  }

  @Test
  public void editingATemplateKeepsItsFields() throws Exception {
    saveTemplate(Map.of("name", "hero", "body", templateBody(10),
        "p_name_0", "headline", "p_type_0", "text", "p_label_0", "Headline", "p_required_0", "on"));
    assertEquals(1, storedTemplate("hero").fields().size());

    String rewritten = templateBody(30);
    saveTemplate(Map.of("name", "hero", "body", rewritten,
        "p_name_0", "headline", "p_type_0", "text", "p_label_0", "Headline", "p_required_0", "on"));

    TemplateRecord saved = storedTemplate("hero");
    assertEquals(rewritten, saved.body());
    assertEquals("the declared field is still declared", 1, saved.fields().size());
    assertEquals("headline", saved.fields().get(0).name());
  }

  @Test
  public void theTemplateEditorGivesBackTheWholeBody() throws Exception {
    String body = templateBody(20);
    saveTemplate(Map.of("name", "site", "body", body));

    Browser.Page editor = admin.get("/admin/templates/edit/site");
    assertEquals(200, editor.status());
    assertTrue("the last rule has to be there, or the next save truncates the template",
        editor.contains("block-19"));
  }

  @Test
  public void aTemplateChangeReachesEveryPageThatUsesIt() throws Exception {
    saveTemplate(Map.of("name", "site", "body", templateBody(5)));
    saveContent(Map.of("uri", "/a", "title", "A", "template_name", "site", "body", prose(3)));

    Browser visitor = new Browser(server.port, "example.org");
    assertTrue(visitor.get("/a").contains("block-4"));

    saveTemplate(Map.of("name", "site", "body", templateBody(5).replace("<main>", "<main id=\"new\">")));
    assertTrue("the cascade still works with a real sized template",
        new Browser(server.port, "example.org").get("/a").contains("id=\"new\""));
  }

  @Test
  public void aTemplateFieldValueCanBeProse() throws Exception {
    saveTemplate(Map.of("name", "hero", "body", templateBody(5) ,
        "p_name_0", "intro", "p_type_0", "multiline", "p_label_0", "Intro"));

    String intro = prose(3);
    saveContent(Map.of("uri", "/a", "title", "A", "template_name", "hero",
        "body", prose(2), "field_intro", intro));

    assertTrue("a multiline template field is prose too",
        stored("/a").fields().contains("Paragraph 2"));
  }

  @Test
  public void editingAPageKeepsItsTemplateFieldValues() throws Exception {
    // the field values live in a blob rebuilt from the form on every save, so the editor has to
    // hand them back or an edit that touches only the body silently drops them
    saveTemplate(Map.of("name", "hero", "body", templateBody(5),
        "p_name_0", "intro", "p_type_0", "multiline", "p_label_0", "Intro"));
    String intro = prose(3);
    saveContent(Map.of("uri", "/a", "title", "A", "template_name", "hero",
        "body", prose(2), "field_intro", intro));
    long id = stored("/a").id();

    Browser.Page editor = admin.get("/admin/content/edit/" + id);
    assertTrue("the editor has to carry the stored value back to the form",
        editor.contains("Paragraph 2"));

    String newBody = prose(4);
    saveContent(Map.of("id", Long.toString(id), "uri", "/a", "title", "A",
        "template_name", "hero", "body", newBody, "field_intro", intro));

    ContentRecord saved = stored("/a");
    assertEquals(newBody, saved.body());
    assertTrue("and the field value came through the edit intact",
        saved.fields().contains("Paragraph 2"));
  }

  // ---- the same accessor, everywhere it is used ------------------------------------------------

  @Test
  public void aProfileParagraphSurvives() throws Exception {
    // the same bug, on the page an unapproved member uses to introduce themselves -- which is the
    // one thing an admin reads before deciding about them
    Browser member = signIn("member@example.com");
    String about = prose(4);
    member.get("/self");
    member.submitTo("/self", Map.of("action", "profile", "display_name", "Sam",
        "headline", "woodworker", "about", about, "location", "Portland", "links", ""));

    ProfileRecord profile = server.auth.forDomain("example.org").people.profileOf(
        server.auth.forDomain("example.org").users.byEmail("member@example.com").id());
    // saveProfile trims deliberately; what matters is that the paragraphs are all there
    assertEquals(about.trim(), profile.about());
  }


}
