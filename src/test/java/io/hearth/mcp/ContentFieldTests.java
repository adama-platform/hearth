package io.hearth.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.Assert.assertTrue;

/**
 * A model configuring what a template asks for, and filling it in on a page.
 *
 * Both halves of this were unreachable and the failure was silent in both directions. A template
 * saved by a model got {@code "[]"} for its declarations whatever it sent, and a page saved by a
 * model got {@code "{}"} for its values -- so an agent could build a template, attach pages to it,
 * and never once put a value in a box the template asked for. Nothing refused; the pages simply
 * rendered with holes in them.
 *
 * The rules being held down here are the ones that make a partial write safe. Saving a body must
 * not strip a template's declarations; setting one field value must not clear the other three or
 * reset a listing's page size; a name the template never declared is refused out loud rather than
 * dropped, because a write that reports success while discarding half of what it was given teaches
 * a model that it worked.
 */
public class ContentFieldTests {
  private static final String REDIRECT = "https://grok.com/connectors/callback";
  private static final ObjectMapper JSON = new ObjectMapper();

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

  /** one field declaration, the shape template_save takes */
  private static ObjectNode field(String name, String type, String label, boolean required) {
    ObjectNode node = JSON.createObjectNode();
    node.put("name", name);
    node.put("type", type);
    node.put("label", label);
    node.put("required", required);
    return node;
  }

  private static ArrayNode fields(ObjectNode... each) {
    ArrayNode array = JSON.createArrayNode();
    for (ObjectNode one : each) {
      array.add(one);
    }
    return array;
  }

  private static ObjectNode values(String... pairs) {
    ObjectNode node = JSON.createObjectNode();
    for (int k = 0; k + 1 < pairs.length; k += 2) {
      node.put(pairs[k], pairs[k + 1]);
    }
    return node;
  }

  private static boolean refused(McpClient.Response response) {
    return response.toolResult().toString().contains("refused")
        || response.body().contains("\"isError\":true");
  }

  private static String errorText(McpClient.Response response) {
    return response.body();
  }

  // ---- declaring what a template asks for ---------------------------------------------------

  @Test
  public void aModelCanDeclareFieldsOnATemplate() throws Exception {
    McpClient.Response saved = grok.call("template_save",
        "name", "article",
        "body", "<h1>{{title}}</h1><p>{{subtitle}}</p>{{{body}}}",
        "fields", fields(
            field("subtitle", "text", "Subtitle", false),
            field("hero", "markdown", "Hero", false)));
    assertEquals(200, saved.status());

    JsonNode got = grok.call("template_get", "name", "article").toolResult();
    JsonNode declared = got.path("fields");
    assertEquals(2, declared.size());
    assertEquals("subtitle", declared.get(0).path("name").asText());
    assertEquals("text", declared.get(0).path("type").asText());
    assertEquals("Subtitle", declared.get(0).path("label").asText());
    assertEquals("markdown", declared.get(1).path("type").asText());
  }

  /**
   * The read under the write has to carry everything the write replaces.
   *
   * template_get used to answer with "subtitle:text" strings, which is enough to look at and not
   * enough to edit: a model adding a third field has to resend the first two, and a label it never
   * saw is a label the next save deletes.
   */
  @Test
  public void readingATemplateGivesBackEnoughToEditIt() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "The standfirst", true)));

    JsonNode declared = grok.call("template_get", "name", "article").toolResult().path("fields");
    assertEquals("The standfirst", declared.get(0).path("label").asText());
    assertTrue(declared.get(0).path("required").asBoolean());
  }

  /** invariant: fixing a body must not silently strip every box off the page editor */
  @Test
  public void savingABodyKeepsTheDeclarations() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false)));

    grok.call("template_save", "name", "article", "body", "<main>{{{body}}}</main>");

    JsonNode declared = grok.call("template_get", "name", "article").toolResult().path("fields");
    assertEquals(1, declared.size());
    assertEquals("subtitle", declared.get(0).path("name").asText());
  }

  @Test
  public void passingFieldsReplacesTheDeclarationWholesale() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false),
            field("hero", "text", "Hero", false)));

    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false)));

    JsonNode declared = grok.call("template_get", "name", "article").toolResult().path("fields");
    assertEquals(1, declared.size());
    assertEquals("subtitle", declared.get(0).path("name").asText());
  }

  @Test
  public void anUnusableFieldNameIsRefusedByName() throws Exception {
    McpClient.Response response = grok.call("template_save", "name", "article",
        "body", "{{{body}}}", "fields", fields(field("Sub Title", "text", "x", false)));
    assertTrue(errorText(response), refused(response));
    assertTrue(errorText(response), errorText(response).contains("Sub Title"));
  }

  /**
   * An invented type is refused rather than coerced.
   *
   * {@code TemplateField.Type.of} falls back to text on purpose, because it parses a blob this
   * server wrote and a half-readable declaration should not take a page down. Reading an argument
   * is the opposite case: quietly turning "boolean" into a text box means the author finds out
   * from a form that renders wrong.
   */
  @Test
  public void anInventedFieldTypeIsRefused() throws Exception {
    McpClient.Response response = grok.call("template_save", "name", "article",
        "body", "{{{body}}}", "fields", fields(field("flag", "boolean", "Flag", false)));
    assertTrue(errorText(response), refused(response));
    assertTrue(errorText(response), errorText(response).contains("bool"));
  }

  @Test
  public void aFieldDeclaredTwiceIsRefused() throws Exception {
    McpClient.Response response = grok.call("template_save", "name", "article",
        "body", "{{{body}}}", "fields", fields(field("subtitle", "text", "One", false),
            field("subtitle", "text", "Two", false)));
    assertTrue(errorText(response), refused(response));
  }

  // ---- filling them in on a page -------------------------------------------------------------

  @Test
  public void aModelCanSetFieldValuesOnAPage() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{subtitle}}{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false)));

    McpClient.Response saved = grok.call("content_save", "uri", "/news", "title", "News",
        "kind", "markdown", "template", "article", "body", "Hello.",
        "fields", values("subtitle", "A quiet week"));
    assertEquals(200, saved.status());
    assertEquals("A quiet week", saved.toolResult().path("fields").path("subtitle").asText());

    JsonNode got = grok.call("content_get", "uri", "/news").toolResult();
    assertEquals("A quiet week", got.path("fields").path("subtitle").asText());
  }

  /** the values reach the rendered page, which is the only proof that matters */
  @Test
  public void aFieldValueRendersThroughTheTemplate() throws Exception {
    grok.call("template_save", "name", "article",
        "body", "<h1>{{title}}</h1><p class=\"sub\">{{subtitle}}</p>{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false)));
    grok.call("content_save", "uri", "/news", "title", "News", "kind", "markdown",
        "template", "article", "body", "Hello.", "published", true,
        "fields", values("subtitle", "A quiet week"));

    String page = new Browser(server.port, "example.org").get("/news").body();
    assertTrue(page, page.contains("A quiet week"));
  }

  /**
   * The same, written by a person at the admin screen.
   *
   * The gap this covers was never about models: a template declared a field, the editor drew a box
   * for it, the value was stored, and the page's own template could not see it -- the values
   * reached a directory listing's rows and the feeds and stopped there. So the proof belongs on
   * both paths, and a fix that only worked for an agent would be the wrong fix.
   */
  @Test
  public void aFieldValueWrittenByAPersonAlsoRenders() throws Exception {
    grok.call("template_save", "name", "article",
        "body", "<h1>{{title}}</h1><p class=\"sub\">{{subtitle}}</p>{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false)));

    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/hand-written",
        "title", "Hand written", "kind", "markdown", "template_name", "article",
        "published", "on", "body", "Hello.", "field_subtitle", "Typed by a person"));

    String page = new Browser(server.port, "example.org").get("/hand-written").body();
    assertTrue(page, page.contains("Typed by a person"));
  }

  /** a field cannot shadow the page's own title or body by being named after one */
  @Test
  public void aFieldCannotShadowTheBuiltIns() throws Exception {
    grok.call("template_save", "name", "article", "body", "<h1>{{title}}</h1>{{{body}}}",
        "fields", fields(field("title", "text", "Title", false)));
    grok.call("content_save", "uri", "/news", "title", "The real title", "kind", "markdown",
        "template", "article", "body", "Hello.", "published", true,
        "fields", values("title", "an impostor"));

    String page = new Browser(server.port, "example.org").get("/news").body();
    assertTrue(page, page.contains("The real title"));
    assertFalse(page, page.contains("an impostor"));
  }

  /**
   * Setting one value must not clear the others.
   *
   * This is invariant 30 arriving in a second place. A submission mentions a handful of the keys
   * that exist, and treating that as the new state of the record erases the rest while looking
   * like it worked.
   */
  @Test
  public void settingOneFieldLeavesTheOthersAlone() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false),
            field("hero", "text", "Hero", false)));
    grok.call("content_save", "uri", "/news", "title", "News", "kind", "markdown",
        "template", "article", "body", "Hello.",
        "fields", values("subtitle", "A quiet week", "hero", "banner.jpg"));

    grok.call("content_meta", "uri", "/news", "fields", values("subtitle", "A loud week"));

    JsonNode got = grok.call("content_get", "uri", "/news").toolResult();
    assertEquals("A loud week", got.path("fields").path("subtitle").asText());
    assertEquals("banner.jpg", got.path("fields").path("hero").asText());
  }

  @Test
  public void aFieldTheTemplateNeverDeclaredIsRefusedRatherThanDropped() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", false)));
    grok.call("content_save", "uri", "/news", "title", "News", "kind", "markdown",
        "template", "article", "body", "Hello.");

    McpClient.Response response = grok.call("content_meta", "uri", "/news",
        "fields", values("stinger", "nope"));
    assertTrue(errorText(response), refused(response));
    assertTrue(errorText(response), errorText(response).contains("stinger"));
  }

  @Test
  public void aRequiredFieldLeftEmptyRefusesTheSave() throws Exception {
    grok.call("template_save", "name", "article", "body", "{{{body}}}",
        "fields", fields(field("subtitle", "text", "Subtitle", true)));

    McpClient.Response response = grok.call("content_save", "uri", "/news", "title", "News",
        "kind", "markdown", "template", "article", "body", "Hello.");
    assertTrue(errorText(response), refused(response));
    assertTrue(errorText(response), errorText(response).contains("Subtitle"));
  }

  // ---- changing details without touching the words --------------------------------------------

  @Test
  public void contentMetaChangesDetailsAndLeavesTheBodyExactlyAsItWas() throws Exception {
    String body = "We meet on Tuesdays.\n\n> And the quote stays *exactly* like this.\n";
    grok.call("content_save", "uri", "/about", "title", "About", "kind", "markdown",
        "body", body, "published", true);

    McpClient.Response changed = grok.call("content_meta", "uri", "/about",
        "title", "About us", "folder", "Community", "published", false);
    assertEquals(200, changed.status());

    JsonNode got = grok.call("content_get", "uri", "/about").toolResult();
    assertEquals("About us", got.path("title").asText());
    assertEquals("Community", got.path("folder").asText());
    assertFalse(got.path("published").asBoolean());
    assertEquals("the body is the one thing content_meta may never touch",
        body, got.path("body").asText());
  }

  /** the guarantee is structural: there is no argument that makes this tool write prose */
  @Test
  public void contentMetaRefusesABody() throws Exception {
    grok.call("content_save", "uri", "/about", "title", "About", "kind", "markdown",
        "body", "Original.");

    McpClient.Response response = grok.call("content_meta", "uri", "/about", "body", "Replaced.");
    assertTrue(errorText(response), refused(response));

    JsonNode got = grok.call("content_get", "uri", "/about").toolResult();
    assertEquals("Original.", got.path("body").asText());
  }

  @Test
  public void contentMetaWillNotCreateAPage() throws Exception {
    McpClient.Response response = grok.call("content_meta", "uri", "/nothing-here",
        "title", "Ghost");
    assertTrue(errorText(response), refused(response));
    assertTrue(errorText(response), errorText(response).contains("content_save"));
  }

  /** human only is a boundary, and a narrower tool is not a way around it */
  @Test
  public void contentMetaCannotTouchAHumanOnlyPage() throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/private",
        "title", "Private", "kind", "markdown", "template_name", "", "published", "on",
        "body", "Ours.", "human_only", "on"));

    McpClient.Response response = grok.call("content_meta", "uri", "/private", "title", "Theirs");
    assertTrue(errorText(response), refused(response));

    admin.get("/admin/content");
    assertTrue(admin.get("/private").body().contains("Ours."));
  }

  /** and it is still not a way to set the bit -- invariant 28 */
  @Test
  public void contentMetaCannotSetHumanOnly() throws Exception {
    grok.call("content_save", "uri", "/about", "title", "About", "kind", "markdown",
        "body", "Open.");
    grok.call("content_meta", "uri", "/about", "human_only", true);

    // if the bit had been set the agent would no longer be able to read it back
    assertEquals("About", grok.call("content_get", "uri", "/about").toolResult()
        .path("title").asText());
  }

}
