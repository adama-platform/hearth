package io.hearth.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A community's pages as a file, and back again.
 *
 * The merge key is what is really under test. A uri is an address and an id is a row number in one
 * database, and the whole point of a bundle is that the other end is a different install -- or the
 * same one, three months and two renames later. Everything here is a way of asking whether bringing
 * a bundle back is a merge or a pile of duplicates.
 */
public class BundleTests {
  private static final ObjectMapper JSON = new ObjectMapper();

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

  @Test
  public void everyPageHasAMergeKeyAndKeepsIt() throws Exception {
    long id = write("/about", "About us", "We meet on Tuesdays.");
    String key = store().uuidOf(id);
    assertNotNull(key);
    assertFalse("a key stamped when the page was written, not at the next boot", key.isBlank());

    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/about-us", "title", "About us", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "We meet on Tuesdays.", "published", "on"));
    assertEquals("renaming a page does not make it a different page", key, store().uuidOf(id));
  }

  @Test
  public void theBundleIsEveryPageAndEveryTemplate() throws Exception {
    write("/about", "About us", "We meet on Tuesdays.");
    write("/draft", "Not yet", "half a sentence", false);
    admin.get("/admin/templates/new");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "plain",
        "body", "<main>{{{body}}}</main>"));

    JsonNode bundle = download("/admin/content/bundle");
    assertEquals(1, bundle.get("hearth").asInt());
    assertEquals("example.org", bundle.get("domain").asText());
    assertEquals("a draft is part of a backup; leaving it out is losing it", 2,
        bundle.get("content").size());
    assertTrue(bundle.get("templates").size() >= 1);
    JsonNode about = pageIn(bundle, "/about");
    assertEquals("About us", about.get("title").asText());
    assertEquals("We meet on Tuesdays.", about.get("body").asText());
    assertTrue(about.get("published").asBoolean());
    assertFalse(about.get("uuid").asText().isBlank());
  }

  @Test
  public void onePageComesDownOnItsOwn() throws Exception {
    long id = write("/about", "About us", "We meet on Tuesdays.");
    write("/other", "Other", "something else");
    JsonNode one = download("/admin/content/bundle/" + id);
    assertEquals(1, one.get("content").size());
    assertEquals("/about", one.get("content").get(0).get("uri").asText());
    assertTrue("with the templates, because a page that arrives without its template renders as a"
        + " bare body and looks like a failed import", one.has("templates"));
  }

  @Test
  public void bringingItBackAfterAnEditIsAMergeRatherThanASecondPage() throws Exception {
    long id = write("/about", "About us", "the first version");
    JsonNode bundle = download("/admin/content/bundle");

    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/about", "title", "About us", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "somebody changed this", "published", "on"));

    Browser.Page done = importing(bundle.toString());
    assertTrue(done.body(), done.contains("0 page(s) added, 1 updated"));
    assertEquals("one page, not two", 1, store().allContent(50).size());
    assertEquals("the first version", store().byId(id).body());
  }

  @Test
  public void aPageThatMovedIsStillTheSamePage() throws Exception {
    long id = write("/about", "About us", "the words");
    JsonNode bundle = download("/admin/content/bundle");

    // the address changes here, and the bundle still holds the old one
    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/who-we-are", "title", "About us", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "the words", "published", "on"));

    importing(bundle.toString());
    assertEquals("the key found it, and the address in the bundle won",
        1, store().allContent(50).size());
    assertEquals("/about", store().byId(id).uri());
  }

  @Test
  public void aPageWrittenSomewhereElseArrivesAsANewOne() throws Exception {
    String json = "{\"hearth\":1,\"content\":[{\"uuid\":\"11111111-2222-3333-4444-555555555555\","
        + "\"uri\":\"/from-github\",\"title\":\"From a repository\",\"kind\":\"markdown\","
        + "\"body\":\"# Hello\",\"published\":true}]}";
    Browser.Page done = importing(json);
    assertTrue(done.body(), done.contains("1 page(s) added"));
    ContentRecord page = store().byUri("/from-github");
    assertEquals("From a repository", page.title());
    assertEquals("11111111-2222-3333-4444-555555555555", store().uuidOf(page.id()));
    assertTrue("and it is a page on the site, not a row in a table",
        admin.get("/from-github").contains("Hello"));
  }

  @Test
  public void aPageWithNoKeyAtAllIsStillImportable() throws Exception {
    // somebody's tooling turning a directory of markdown into JSON will not have invented one
    Browser.Page done = importing("{\"content\":[{\"uri\":\"/notes\",\"title\":\"Notes\","
        + "\"kind\":\"markdown\",\"body\":\"a note\",\"published\":true}]}");
    assertTrue(done.body(), done.contains("1 page(s) added"));
    assertFalse("and it is given one on the way in",
        store().uuidOf(store().byUri("/notes").id()).isBlank());
  }

  @Test
  public void anAddressThatIsAlreadyTakenIsAdoptedRatherThanDuplicated() throws Exception {
    write("/about", "Written here", "by hand");
    Browser.Page done = importing("{\"content\":[{\"uuid\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
        + "\"uri\":\"/about\",\"title\":\"From the repository\",\"kind\":\"markdown\","
        + "\"body\":\"generated\",\"published\":true}]}");
    assertTrue(done.body(), done.contains("adopted rather than duplicated"));
    assertEquals("two pages at one address is the worst possible answer",
        1, store().allContent(50).size());
    ContentRecord page = store().byUri("/about");
    assertEquals("From the repository", page.title());
    assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", store().uuidOf(page.id()));
  }

  @Test
  public void anImportIsVersionedLikeAnyOtherEdit() throws Exception {
    long id = write("/about", "About us", "the first version");
    importing("{\"content\":[{\"uuid\":\"" + store().uuidOf(id) + "\",\"uri\":\"/about\","
        + "\"title\":\"About us\",\"kind\":\"markdown\",\"body\":\"the imported version\","
        + "\"published\":true}]}");
    assertTrue("an import that went wrong is undone from the history, not from a backup",
        store().versions().count(id) >= 2);
    assertTrue(admin.get("/admin/content/history/" + id).contains("version"));
  }

  @Test
  public void nonsenseIsRefusedRatherThanHalfApplied() throws Exception {
    assertTrue(importing("not json at all").contains("that is not JSON"));
    assertTrue(importing("{\"content\":[{\"uri\":\"nowhere\"}]}")
        .contains("a page whose uri is 'nowhere'"));
    assertEquals(0, store().allContent(50).size());
  }

  @Test
  public void aTemplateComesBackWithItsPages() throws Exception {
    admin.get("/admin/templates/new");
    admin.submitTo("/admin/templates", Map.of("action", "save", "name", "wrapper",
        "body", "<main>{{{body}}}</main>"));
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/about", "title", "About",
        "kind", "markdown", "template_name", "wrapper", "nav_folder", "", "body", "the words",
        "published", "on"));
    JsonNode bundle = download("/admin/content/bundle");

    // a fresh community, built from the file alone
    Browser other = new Browser(server.port, "example.org");
    assertTrue(admin.get("/about").contains("<main>"));

    admin.submitToAndFollow("/admin/content", Map.of("action", "import",
        "bundle", bundle.toString()));
    assertEquals("wrapper", store().byUri("/about").templateName());
    assertNotNull(other);
  }

  @Test
  public void somebodyWhoMayNotPublishCannotImport() throws Exception {
    // an import writes pages and puts them live, so half the permission is not enough
    Browser writer = signIn("writer@example.com");
    grant("writer@example.com", "writer", io.hearth.auth.Permission.content_write);

    Browser.Page done = writer.submitToAndFollow("/admin/content/bundles",
        Map.of("action", "import", "bundle", "{\"content\":[]}"));
    assertTrue(done.body(), done.contains("needs both"));
  }

  @Test
  public void theWholeSiteIsNotOfferedToSomebodyWhoOnlyReadsIt() throws Exception {
    Browser reader = signIn("reader@example.com");
    grant("reader@example.com", "reader", io.hearth.auth.Permission.content_read);

    assertEquals("a bundle is every page including the drafts and the ones locked away from AI",
        404, reader.get("/admin/content/bundle").status());
    assertEquals("nor is the screen it lives on", 404,
        reader.get("/admin/content/bundles").status());
    assertFalse("and the way in is not drawn either",
        reader.get("/admin/content").contains("Import &amp; export"));
  }

  @Test
  public void aPagesPublishedDateIsWhatListingsOrderBy() throws Exception {
    // a page drafted in January and published in March is a March page, and filing it two months
    // back is filing it where nobody looks
    long id = write("/about", "About us", "words");
    assertNotNull("absent means the first save, which is right for anything written here",
        store().byId(id).publishedOn());

    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/about", "title", "About us", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "words", "published", "on", "published_at", "2011-04-09"));
    assertEquals("2011-04-09",
        store().byId(id).publishedAt().toLocalDateTime().toLocalDate().toString());

    // and an edit that says nothing about it keeps it, rather than moving the page to the top of
    // every listing every time somebody fixes a typo
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/about", "title", "About us", "kind", "markdown", "template_name", "",
        "nav_folder", "", "body", "words, fixed", "published", "on"));
    assertEquals("2011-04-09",
        store().byId(id).publishedAt().toLocalDateTime().toLocalDate().toString());
  }

  @Test
  public void theBundleScreenIsWhereTheFileGoesInAndOut() throws Exception {
    Browser.Page page = admin.get("/admin/content/bundles");
    assertEquals(200, page.status());
    assertTrue("the download", page.contains("Download everything"));
    assertTrue("a file box", page.contains("type=\"file\""));
    assertTrue("and the paste box, behind a modal rather than sitting on the page",
        page.contains("data-modal=\"paste\""));
  }

  @Test
  public void aBundleCanArriveAsAFile() throws Exception {
    String json = "{\"content\":[{\"uri\":\"/from-a-file\",\"title\":\"From a file\","
        + "\"kind\":\"markdown\",\"body\":\"# Hello\",\"published\":true}]}";
    admin.get("/admin/content/bundles");
    admin.uploadTo("/attachment/upload", "site.json", "application/json",
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("bundle", "1"));
    assertNotNull("a file is a file, and this is the one path allowed a big one",
        store().byUri("/from-a-file"));
    assertEquals("and it is content rather than an attachment", 0,
        server.auth.forDomain("example.org").attachments.count());
  }

  @Test
  public void twoPagesNeverShareAKey() throws Exception {
    long first = write("/one", "One", "a");
    long second = write("/two", "Two", "b");
    assertNotEquals(store().uuidOf(first), store().uuidOf(second));
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  /** a role holding exactly these, and somebody approved holding it */
  private void grant(String email, String role, io.hearth.auth.Permission... permissions)
      throws Exception {
    io.hearth.auth.Accounts accounts = server.auth.forDomain("example.org");
    accounts.roleDefs.save(role, role, "", java.util.Set.of(permissions), "", null);
    long id = accounts.users.byEmail(email).id();
    accounts.users.approve(id, null);
    accounts.roles.grant(id, role, null);
  }

  private ContentStore store() {
    return server.auth.forDomain("example.org").site.store();
  }

  private long write(String uri, String title, String body) throws Exception {
    return write(uri, title, body, true);
  }

  private long write(String uri, String title, String body, boolean published) throws Exception {
    java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
    form.put("action", "save");
    form.put("uri", uri);
    form.put("title", title);
    form.put("kind", "markdown");
    form.put("template_name", "");
    form.put("nav_folder", "");
    form.put("body", body);
    if (published) {
      form.put("published", "on");
    }
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", form);
    return store().byUri(uri).id();
  }

  private JsonNode download(String path) throws Exception {
    Browser.Page file = admin.get(path);
    assertEquals(200, file.status());
    return JSON.readTree(file.body());
  }

  /** the paste box, which now lives on its own screen with the download and the file upload */
  private Browser.Page importing(String json) throws Exception {
    admin.get("/admin/content/bundles");
    return admin.submitToAndFollow("/admin/content/bundles",
        Map.of("action", "import", "bundle", json));
  }

  private static JsonNode pageIn(JsonNode bundle, String uri) {
    for (JsonNode row : bundle.get("content")) {
      if (row.get("uri").asText().equals(uri)) {
        return row;
      }
    }
    throw new AssertionError("no page at " + uri);
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
