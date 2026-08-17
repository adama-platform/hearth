package io.hearth.web;

import io.hearth.content.ContentRecord;
import io.hearth.content.ContentVersions;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Version history, end to end.
 *
 * This is meant to replace keeping a website in git, so the bar is that an old version comes back
 * *exactly* -- every facet of it, not just the body -- however many edits ago it was, and whichever
 * side of a snapshot boundary it falls on. The patch algorithm has its own exhaustive tests; these
 * are about the storage, the reconstruction, and the screen somebody actually uses.
 */
public class VersionHistoryTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
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

  private io.hearth.content.ContentStore store() {
    return server.auth.forDomain("example.org").site.store();
  }

  private ContentVersions versions() {
    return store().versions();
  }

  private Browser.Page save(Map<String, String> fields) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("kind", "markdown");
    form.put("template_name", "");
    form.put("published", "on");
    form.putAll(fields);
    return admin.submitToAndFollow("/admin/content", form);
  }

  private static String prose(int paragraphs, String marker) {
    StringBuilder text = new StringBuilder();
    for (int k = 0; k < paragraphs; k++) {
      text.append("## Section ").append(k).append("\n\n")
          .append("We meet on the first Tuesday. ").append(marker).append(" ").append(k).append("\n\n");
    }
    return text.toString();
  }

  // ---- recording -------------------------------------------------------------------------------

  @Test
  public void everySaveIsRecordedWithWhoDidIt() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(3, "first")));
    long id = store().byUri("/about").id();

    List<ContentVersions.Entry> history = versions().history(id);
    assertEquals(1, history.size());
    assertEquals(1, history.get(0).version());
    assertEquals("created", history.get(0).summary());
    assertEquals("boss@example.com", history.get(0).who());
    assertTrue("the first version has to be a snapshot; there is nothing to patch against",
        history.get(0).snapshot());
  }

  @Test
  public void editingAddsAVersionAndSaysWhatChanged() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(3, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us",
        "body", prose(3, "first")));

    List<ContentVersions.Entry> history = versions().history(id);
    assertEquals("newest first", 2, history.size());
    assertEquals(2, history.get(0).version());
    assertEquals("title", history.get(0).summary());
  }

  @Test
  public void theSummaryNamesEveryFacetThatMoved() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "x")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "Renamed",
        "body", prose(4, "x"), "nav_folder", "Docs", "human_only", "on"));

    String summary = versions().history(id).get(0).summary();
    assertTrue(summary, summary.contains("title"));
    assertTrue(summary, summary.contains("folder"));
    assertTrue(summary, summary.contains("locked to humans"));
    assertTrue("and how much the body moved", summary.contains("body +"));
  }

  @Test
  public void unpublishingIsVisibleInTheHistory() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", "hello"));
    long id = store().byUri("/about").id();
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/about", "title", "About", "kind", "markdown", "template_name", "", "body", "hello"));
    assertEquals("unpublished", versions().history(id).get(0).summary());
  }

  // ---- reconstruction --------------------------------------------------------------------------

  @Test
  public void anyVersionComesBackExactly() throws Exception {
    // the whole point: not "roughly what it said", but the document
    save(Map.of("uri", "/handbook", "title", "Handbook", "body", prose(6, "one")));
    long id = store().byUri("/handbook").id();

    String[] bodies = new String[12];
    bodies[0] = prose(6, "one");
    for (int k = 1; k < bodies.length; k++) {
      bodies[k] = prose(6 + k, "revision " + k);
      save(Map.of("id", Long.toString(id), "uri", "/handbook", "title", "Handbook",
          "body", bodies[k]));
    }

    for (int k = 0; k < bodies.length; k++) {
      ContentRecord old = versions().versionOf(id, k + 1);
      assertNotNull("version " + (k + 1) + " should exist", old);
      assertEquals("version " + (k + 1) + " should come back exactly", bodies[k], old.body());
    }
  }

  @Test
  public void everyFacetSurvivesReconstructionNotJustTheBody() throws Exception {
    // "what did this look like in March" is not answerable if the answer drops the template
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "hero",
        "body", "<html>{{{body}}}</html>", "p_name_0", "intro", "p_type_0", "text"));
    save(Map.of("uri", "/a", "title", "First title", "template_name", "hero",
        "nav_folder", "Docs", "body", "first body", "field_intro", "the intro"));
    long id = store().byUri("/a").id();

    save(Map.of("id", Long.toString(id), "uri", "/a", "title", "Second title",
        "template_name", "", "body", "second body"));

    ContentRecord first = versions().versionOf(id, 1);
    assertEquals("First title", first.title());
    assertEquals("hero", first.templateName());
    assertEquals("Docs", first.navFolder());
    assertEquals("first body", first.body());
    assertTrue("including the template field values", first.fields().contains("the intro"));
    assertTrue(first.published());
    assertFalse(first.humanOnly());
  }

  @Test
  public void aSnapshotIsWrittenPeriodicallySoRebuildsStayShort() throws Exception {
    save(Map.of("uri", "/a", "title", "A", "body", "line 0"));
    long id = store().byUri("/a").id();
    for (int k = 1; k <= 25; k++) {
      save(Map.of("id", Long.toString(id), "uri", "/a", "title", "A", "body", "line " + k));
    }

    long snapshots = versions().history(id).stream().filter(ContentVersions.Entry::snapshot).count();
    assertTrue("there should be several anchors in 26 versions, was " + snapshots, snapshots >= 3);
    assertEquals("and every version still rebuilds", "line 7", versions().versionOf(id, 8).body());
    assertEquals("line 25", versions().versionOf(id, 26).body());
  }

  @Test
  public void aBigRewriteIsStoredWholeRatherThanAsAPatch() throws Exception {
    // a patch larger than the document is a worse snapshot
    save(Map.of("uri", "/a", "title", "A", "body", prose(10, "original")));
    long id = store().byUri("/a").id();
    save(Map.of("id", Long.toString(id), "uri", "/a", "title", "A",
        "body", prose(10, "completely different words throughout")));

    assertEquals("both versions rebuild", prose(10, "original"), versions().versionOf(id, 1).body());
    assertTrue(versions().versionOf(id, 2).body().contains("completely different"));
  }

  @Test
  public void askingForAVersionThatIsNotThereGivesNothing() throws Exception {
    save(Map.of("uri", "/a", "title", "A", "body", "hello"));
    long id = store().byUri("/a").id();
    assertNull(versions().versionOf(id, 99));
    assertNull(versions().versionOf(9999, 1));
  }

  @Test
  public void deletingThePageTakesTheHistoryWithIt() throws Exception {
    save(Map.of("uri", "/a", "title", "A", "body", "hello"));
    long id = store().byUri("/a").id();
    assertEquals(1, versions().count(id));

    admin.submitToAndFollow("/admin/content",
        Map.of("action", "delete", "id", Long.toString(id)));
    assertEquals("a history nobody can reach is a uri that quietly comes back",
        0, versions().count(id));
  }

  @Test
  public void anEditByAnAgentSaysItWasTheAgentsPerson() throws Exception {
    // the history is who did what; an agent acting as somebody is still that somebody's name
    save(Map.of("uri", "/a", "title", "A", "body", "hello"));
    long id = store().byUri("/a").id();
    assertEquals("boss@example.com", versions().history(id).get(0).who());
  }

  // ---- the screen ------------------------------------------------------------------------------

  @Test
  public void theEditorLinksToTheHistory() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", "hello"));
    long id = store().byUri("/about").id();

    Browser.Page editor = admin.get("/admin/content/edit/" + id);
    assertTrue("the button somebody clicks", editor.contains("/admin/content/history/" + id));
    assertTrue("and it says how much there is", editor.contains("history (1)"));
  }

  @Test
  public void theHistoryPageListsWhoChangedWhat() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us",
        "body", prose(2, "first")));

    Browser.Page page = admin.get("/admin/content/history/" + id);
    assertEquals(200, page.status());
    assertTrue(page.contains("History of /about"));
    assertTrue("the summary", page.contains("title"));
    assertTrue("who", page.contains("boss@example.com"));
    assertTrue("and a way to look at it", page.contains("data-version=\"1\""));
    assertTrue("the modal is a real dialog", page.contains("<dialog"));
  }

  @Test
  public void aVersionPreviewIsItsOwnUrlAndCarriesNoShell() throws Exception {
    // same rule as every other sub-view: the modal fetches a real path
    save(Map.of("uri", "/about", "title", "About", "body", "the original words"));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About", "body", "rewritten"));

    Browser.Page preview = admin.get("/admin/content/history/" + id + "/version/1");
    assertEquals(200, preview.status());
    assertTrue("it shows the old body", preview.contains("the original words"));
    assertTrue("and how the page looked", preview.contains("<iframe"));
    assertFalse("no shell inside a modal", preview.contains("<!doctype html>"));
    assertFalse(preview.contains("Sign out"));
  }

  @Test
  public void thePreviewShowsEveryFacetNotJustTheBody() throws Exception {
    save(Map.of("uri", "/about", "title", "The Old Title", "nav_folder", "Docs",
        "body", "words"));
    long id = store().byUri("/about").id();

    Browser.Page preview = admin.get("/admin/content/history/" + id + "/version/1");
    assertTrue(preview.contains("The Old Title"));
    assertTrue(preview.contains("Docs"));
    assertTrue(preview.contains("/about"));
  }

  @Test
  public void aMissingVersionSaysSoRatherThanShowingSomethingPlausible() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", "hello"));
    long id = store().byUri("/about").id();
    Browser.Page preview = admin.get("/admin/content/history/" + id + "/version/42");
    assertEquals(200, preview.status());
    assertTrue(preview.contains("no version 42"));
  }

  @Test
  public void theHistoryOfAPageThatIsGoneIsNotFound() throws Exception {
    Browser.Page page = admin.get("/admin/content/history/9999");
    assertEquals(200, page.status());
    assertTrue(page.contains("gone"));
  }

  @Test
  public void theHistoryIsAdminsOnly() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", "hello"));
    long id = store().byUri("/about").id();
    Browser stranger = new Browser(server.port, "example.org");
    assertEquals(404, stranger.get("/admin/content/history/" + id).status());
    assertEquals(404, stranger.get("/admin/content/history/" + id + "/version/1").status());
  }

  // ---- restoring -------------------------------------------------------------------------------

  @Test
  public void restoringBringsBackTheWholeOldPage() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(3, "first"),
        "nav_folder", "Community"));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us",
        "body", prose(3, "second"), "nav_folder", "Elsewhere"));

    Browser.Page done = admin.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "1"));
    assertEquals(200, done.status());

    ContentRecord now = store().byId(id);
    assertEquals("the title came back", "About", now.title());
    assertTrue("and the body", now.body().contains("first"));
    assertFalse(now.body().contains("second"));
    assertEquals("and the facets nobody thinks of until they are gone",
        "Community", now.navFolder());
  }

  @Test
  public void restoringIsASaveRatherThanARewind() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(3, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us",
        "body", prose(3, "regrettable")));
    admin.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "1"));

    List<ContentVersions.Entry> history = versions().history(id);
    assertEquals("three versions, not one: nothing was deleted", 3, history.size());
    assertEquals(3, history.get(0).version());
    assertTrue("and the edit being undone is still there to read",
        versions().reconstruct(id, 2).contains("regrettable"));
  }

  @Test
  public void renamingAPageRenamesItRatherThanCloningIt() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about-us", "title", "About",
        "body", prose(2, "second")));

    assertNull("the old address is gone, not still serving the old page",
        store().byUri("/about"));
    assertEquals("and it is the same page, with the same id", id, store().byUri("/about-us").id());
    assertEquals("so the history follows it across the rename",
        2, versions().history(id).size());
  }

  @Test
  public void renamingOntoAnAddressSomethingElseAnswersOnIsRefused() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    save(Map.of("uri", "/contact", "title", "Contact", "body", prose(2, "second")));
    long id = store().byUri("/about").id();

    Browser.Page done = save(Map.of("id", Long.toString(id), "uri", "/contact",
        "title", "About", "body", prose(2, "first")));
    assertTrue(done.contains("already the address"));
    assertEquals("and neither page moved", "/about", store().byId(id).uri());
    assertTrue(store().byUri("/contact").title().equals("Contact"));
  }

  @Test
  public void restoringDoesNotMoveThePageBackToAnOldUri() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about-us", "title", "About",
        "body", prose(2, "second")));

    admin.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "1"));
    assertEquals("the words come back; the address does not, because something else may live there",
        "/about-us", store().byId(id).uri());
  }

  @Test
  public void restoringAVersionThatIsNotThereIsRefusedRatherThanGuessed() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();

    Browser.Page done = admin.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "97"));
    assertTrue(done.contains("no version 97"));
    assertEquals("and nothing was written", 1, versions().history(id).size());
  }

  @Test
  public void theHistoryPageOffersRestoreOnEveryVersionExceptTheCurrentOne() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About us",
        "body", prose(2, "second")));

    Browser.Page page = admin.get("/admin/content/history/" + id);
    assertEquals(200, page.status());
    assertEquals("one restore button: two versions, and the newest is already current",
        1, page.body().split("value=\"restore\"", -1).length - 1);
  }

  // ---- seeing what changed ---------------------------------------------------------------------

  @Test
  public void theChangesViewShowsTheLinesThatMovedAndNotTheRest() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(40, "first")));
    long id = store().byUri("/about").id();
    String edited = prose(40, "first").replace("We meet on the first Tuesday. first 7",
        "We meet on the second Tuesday. first 7");
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About", "body", edited));

    Browser.Page page = admin.get("/admin/content/history/" + id + "/changes/2");
    assertEquals(200, page.status());
    assertTrue("the new line", page.contains("second Tuesday"));
    assertTrue("the old one, which the stored patch throws away", page.contains("first Tuesday"));
    assertTrue(page.contains("+1"));
    // written as &minus; in the template; the compactor's parser decodes it, and UTF-8 carries the
    // character itself rather than a reference to it
    assertTrue(page.contains("\u22121"));
    assertFalse("and not the eighty lines that did not move", page.contains("Section 30"));
  }

  @Test
  public void theFirstVersionSaysThereIsNothingToCompareAgainst() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();

    Browser.Page page = admin.get("/admin/content/history/" + id + "/changes/1");
    assertEquals(200, page.status());
    assertTrue(page.contains("nothing before it"));
  }

  @Test
  public void anEditThatOnlyTouchesTheSettingsSaysSoRatherThanShowingNothing() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(3, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "A different title",
        "body", prose(3, "first")));

    Browser.Page page = admin.get("/admin/content/history/" + id + "/changes/2");
    assertTrue(page.body(), page.contains("title"));
  }

  @Test
  public void theHistoryPageLinksToTheChangesForEveryVersionAfterTheFirst() throws Exception {
    save(Map.of("uri", "/about", "title", "About", "body", prose(2, "first")));
    long id = store().byUri("/about").id();
    save(Map.of("id", Long.toString(id), "uri", "/about", "title", "About",
        "body", prose(2, "second")));

    Browser.Page page = admin.get("/admin/content/history/" + id);
    assertEquals("version 1 has nothing to be compared against",
        1, page.body().split("data-changes-version=", -1).length - 1);
  }
}
