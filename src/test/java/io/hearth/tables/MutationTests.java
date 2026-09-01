package io.hearth.tables;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Hidden rows, and the one place a program is allowed to write.
 *
 * Two features that only make sense next to each other. `hidden` is how something gets into a table
 * before anybody should see it, and a mutation is how a program changes a row -- so the sharpest
 * question in this file is whether a mutation can publish something an admin held back. It cannot,
 * and that is asserted rather than assumed.
 */
public class MutationTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser member;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    member = signIn("ana@example.com");
    approve("ana@example.com");
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

  private void approve(String email) throws Exception {
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
  }

  private UserTables tables() {
    return server.auth.forDomain("example.org").tables;
  }

  private Browser visitor() {
    return new Browser(server.port, "example.org");
  }

  private void makeTable() throws Exception {
    tables().create(new UserTable("signups",
        List.of(new UserField("who", UserField.Type.text, false),
            new UserField("job", UserField.Type.text, false),
            new UserField("count", UserField.Type.number, false),
            new UserField("paid", UserField.Type.flag, false)),
        List.of("job")), null);
  }

  private void savePage(String uri, String body) throws Exception {
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", uri, "title", "T",
        "kind", "javascript", "template_name", "", "published", "on", "body", body));
  }

  private long saveMutation(String uri, String body) throws Exception {
    admin.get("/admin/mutations");
    admin.submitToAndFollow("/admin/mutations", Map.of("action", "save", "uri", uri,
        "enabled", "on", "body", body));
    return server.auth.forDomain("example.org").mutations.byUri(uri).id();
  }

  // ---- hidden ----------------------------------------------------------------------------------

  /**
   * A hidden row is not merely filtered out; the flag is not in the row either.
   *
   * Absent is stronger than false. If a page could read `hidden` it could be written to behave
   * differently for a row somebody might later hide, which makes the flag part of the site's
   * behaviour instead of a decision the site is not party to.
   */
  @Test
  public void aHiddenRowIsInvisibleToEveryFunctionAPageHas() throws Exception {
    makeTable();
    long open = tables().insert("signups", Map.of("who", "Ana", "job", "bread"), null);
    long held = tables().insert("signups",
        Map.of("who", "Bo", "job", "bread", UserTable.HIDDEN, true), null);

    assertNull("by id", tables().getById("signups", held));
    assertNotNull(tables().getById("signups", open));
    assertEquals("by index", 1, tables().listByIndex("signups", "job", "bread").size());
    assertEquals("in the whole table", 1, tables().all("signups").size());
    assertEquals("and in a page", 1, tables().page("signups", 0, 50).size());

    assertFalse("the flag is not even carried",
        tables().getById("signups", open).containsKey(UserTable.HIDDEN));
  }

  @Test
  public void anAdminSeesEverythingIncludingTheFlag() throws Exception {
    makeTable();
    long held = tables().insert("signups",
        Map.of("who", "Bo", UserTable.HIDDEN, true), null);
    Map<String, Object> row = tables().getById("signups", held, UserTables.See.everything);
    assertNotNull(row);
    assertEquals(Boolean.TRUE, row.get(UserTable.HIDDEN));
    assertEquals(1, tables().all("signups", UserTables.See.everything).size());
  }

  @Test
  public void aPageCannotSeeAHiddenRowThroughAnyRoute() throws Exception {
    makeTable();
    tables().insert("signups", Map.of("who", "Visible", "job", "bread"), null);
    tables().insert("signups",
        Map.of("who", "Secret", "job", "bread", UserTable.HIDDEN, true), null);

    savePage("/list", "signups_all().forEach(function (r) { render('[' + r.who + ']'); });"
        + " signups_list_job('bread').forEach(function (r) { render('{' + r.who + '}'); });");
    Browser.Page page = visitor().get("/list");
    assertTrue(page.body(), page.contains("[Visible]"));
    assertTrue(page.body(), page.contains("{Visible}"));
    assertFalse("not through any of them", page.contains("Secret"));
  }

  /**
   * The admin's read must not put a hidden row into the cache a page reads from.
   *
   * The cache key carries the visibility for exactly this. Without it the order of two requests
   * would decide whether the site leaked a held-back row, which is the worst kind of bug: correct
   * on a quiet machine and wrong under traffic.
   */
  @Test
  public void anAdminReadDoesNotPoisonWhatAPageSees() throws Exception {
    makeTable();
    long held = tables().insert("signups",
        Map.of("who", "Secret", UserTable.HIDDEN, true), null);

    assertNotNull("the admin sees it first",
        tables().getById("signups", held, UserTables.See.everything));
    assertNull("and the page still does not", tables().getById("signups", held));
    assertEquals(0, tables().all("signups").size());
  }

  @Test
  public void hidingAndUnhidingIsOneCheckboxAndKeepsTheId() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    tables().update("signups", id, Map.of(UserTable.HIDDEN, true), null);
    assertNull(tables().getById("signups", id));

    tables().update("signups", id, Map.of(UserTable.HIDDEN, false), null);
    Map<String, Object> back = tables().getById("signups", id);
    assertNotNull(back);
    assertEquals("the same row, not a new one", id, back.get("id"));
    assertEquals("Ana", back.get("who"));
  }

  @Test
  public void hiddenCannotBeDeclaredAsAField() {
    assertNotNull(UserTable.checkName("a field name", "hidden"));
  }

  // ---- merging ---------------------------------------------------------------------------------

  @Test
  public void aMergeChangesOnlyTheKeysItNames() throws Exception {
    makeTable();
    long id = tables().insert("signups",
        Map.of("who", "Ana", "job", "bread", "count", 2), null);

    UserTables.Merged merged =
        tables().mergeById("signups", id, Map.of("job", "soup"), null);
    assertTrue(merged.reasons().toString(), merged.success());

    Map<String, Object> row = tables().getById("signups", id);
    assertEquals("soup", row.get("job"));
    assertEquals("the keys it did not name are untouched", "Ana", row.get("who"));
    assertEquals(2.0, (Double) row.get("count"), 0.001);
  }

  @Test
  public void aMergeDoesTheSameThingAnUpdateDoes() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana", "job", "bread"), null);
    assertEquals(1, tables().listByIndex("signups", "job", "bread").size());

    tables().mergeById("signups", id, Map.of("job", "soup"), null);

    assertEquals("both sides of the index moved, exactly as an update would",
        0, tables().listByIndex("signups", "job", "bread").size());
    assertEquals(1, tables().listByIndex("signups", "job", "soup").size());
  }

  @Test
  public void aMergeReportsEveryReasonRatherThanTheFirst() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);

    UserTables.Merged merged = tables().mergeById("signups", id,
        new java.util.LinkedHashMap<>(Map.of("nope", "x", "count", "banana")), null);

    assertFalse(merged.success());
    assertEquals("both of them, so they are not found one save at a time",
        2, merged.reasons().size());
    assertTrue(merged.reasons().toString(),
        merged.reasons().stream().anyMatch(r -> r.contains("no field called 'nope'")));
    assertTrue(merged.reasons().toString(),
        merged.reasons().stream().anyMatch(r -> r.contains("is a number")));
    assertEquals("and nothing was written", "Ana",
        tables().getById("signups", id).get("who"));
  }

  @Test
  public void aMergeCannotPublishARowAnAdminHeldBack() throws Exception {
    makeTable();
    long held = tables().insert("signups",
        Map.of("who", "Secret", UserTable.HIDDEN, true), null);

    UserTables.Merged merged = tables().mergeById("signups", held,
        Map.of(UserTable.HIDDEN, false), null);

    assertFalse(merged.success());
    assertTrue(merged.reasons().toString(),
        merged.reasons().get(0).contains("admin section"));
    assertNull("still held back", tables().getById("signups", held));
  }

  @Test
  public void mergingIntoARowThatIsNotThereSaysSo() throws Exception {
    makeTable();
    UserTables.Merged merged = tables().mergeById("signups", 999, Map.of("who", "x"), null);
    assertFalse(merged.success());
    assertTrue(merged.reasons().toString(), merged.reasons().get(0).contains("no row 999"));
  }

  @Test
  public void aMergeCanStillReachAHiddenRowToChangeIt() throws Exception {
    // hiding must not make a row uneditable; only its visibility is off limits
    makeTable();
    long held = tables().insert("signups",
        Map.of("who", "Bo", UserTable.HIDDEN, true), null);
    assertTrue(tables().mergeById("signups", held, Map.of("who", "Bo Chen"), null).success());
    assertEquals("Bo Chen",
        tables().getById("signups", held, UserTables.See.everything).get("who"));
  }

  // ---- mutations over HTTP ----------------------------------------------------------------------

  @Test
  public void aMemberPostsToAMutationAndTheRowChanges() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana", "job", "bread"), null);
    saveMutation("/claim", "var r = signups_merge_by_id(form('id', 0), {job: form('job', '')});"
        + " render(r.success ? 'done' : r.reasons.join(', '));");

    Browser.Page done = member.submitTo("/claim",
        Map.of("id", String.valueOf(id), "job", "soup"));
    assertEquals(200, done.status());
    assertTrue(done.body(), done.contains("done"));
    assertEquals("soup", tables().getById("signups", id).get("job"));
  }

  @Test
  public void aMutationAnswersJsonWhenItRendersNothing() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    saveMutation("/quiet", "signups_merge_by_id(form('id', 0), {who: 'Changed'});");
    Browser.Page done = member.submitTo("/quiet", Map.of("id", String.valueOf(id)));
    assertTrue(done.body(), done.contains("\"success\":true"));
    assertEquals("Changed", tables().getById("signups", id).get("who"));
  }

  @Test
  public void aMutationCanRedirectSoARefreshCannotRepeatIt() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    saveMutation("/go", "signups_merge_by_id(form('id', 0), {who: 'Gone'});"
        + " meta('redirect', '/thanks');");
    Browser.Page done = member.submitTo("/go", Map.of("id", String.valueOf(id)));
    assertEquals(303, done.status());
    assertEquals("/thanks", done.location());
  }

  /**
   * Nobody reaches a mutation without being an approved member, by two different routes.
   *
   * A visitor with no session is refused here, in JSON, because the caller is a form or a script
   * and an HTML sign-in page is no use to either. Somebody signed in but not yet approved never
   * arrives at all -- WebHandler's approval gate bounces them several steps earlier, which is why
   * this asserts they did not run it rather than asserting a status.
   */
  @Test
  public void aMutationRefusesAnythingButAnApprovedMember() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    saveMutation("/claim", "signups_merge_by_id(" + id + ", {who: 'CHANGED'});");

    Browser.Page anonymous = visitor().submitTo("/claim", Map.of());
    assertEquals(403, anonymous.status());
    assertTrue(anonymous.body(), anonymous.contains("signed-in member"));

    signIn("new@example.com").submitTo("/claim", Map.of());

    // asserted on the row rather than on the response, because the bounced page is a whole HTML
    // document and almost any short word can be found somewhere in one
    assertEquals("neither of them ran it", "Ana",
        tables().getById("signups", id).get("who"));
  }

  @Test
  public void aMutationThatIsOffAnswersFourOhFour() throws Exception {
    makeTable();
    admin.get("/admin/mutations");
    admin.submitToAndFollow("/admin/mutations",
        Map.of("action", "save", "uri", "/draft", "body", "render('x');"));
    assertEquals("whether a draft exists here is not something a POST should discover",
        404, member.submitTo("/draft", Map.of()).status());
  }

  @Test
  public void aGetToAMutationIsStillNotAllowed() throws Exception {
    saveMutation("/claim", "render('ran');");
    assertEquals("a GET never writes, so it never reaches one", 404,
        member.get("/claim").status());
  }

  @Test
  public void aPageCannotMergeAtAll() throws Exception {
    makeTable();
    savePage("/sneaky", "render(typeof signups_merge_by_id);");
    assertTrue("the function is not in a page's prologue at all",
        visitor().get("/sneaky").contains("undefined"));
  }

  @Test
  public void aMutationCannotTakeAnAddressAPageAlreadyUses() throws Exception {
    savePage("/about", "render('a page');");
    admin.get("/admin/mutations");
    assertTrue(admin.submitToAndFollow("/admin/mutations",
        Map.of("action", "save", "uri", "/about", "body", ""))
        .contains("already a page"));
  }

  @Test
  public void aPageCanRenderAFormThatTheMutationAccepts() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    saveMutation("/claim", "signups_merge_by_id(form('id', 0), {who: 'Filled in'});");
    savePage("/form", "render('<form method=post action=/claim>');"
        + " render('<input name=csrf value=\"' + csrf() + '\">');"
        + " render('<input name=id value=' + " + id + " + '>');"
        + " render('</form>');");

    Browser.Page page = member.get("/form");
    assertTrue("csrf() gives the page a token that works", page.contains("name=csrf"));
    assertTrue(page.body(), page.contains("name=id"));
  }

  // ---- the admin table editor -------------------------------------------------------------------

  @Test
  public void anAdminAddsEditsAndHidesARowFromTheTableEditor() throws Exception {
    makeTable();
    admin.get("/admin/tables/rows/signups/new");
    Browser.Page added = admin.submitToAndFollow("/admin/tables/rows/signups",
        Map.of("action", "row_save", "table", "signups",
            "v_who", "Ana", "v_job", "bread", "v_count", "3", "v_paid", "on"));
    assertTrue(added.body(), added.contains("added"));

    Map<String, Object> row = tables().all("signups").get(0);
    assertEquals("Ana", row.get("who"));
    assertEquals(3.0, (Double) row.get("count"), 0.001);
    assertEquals(Boolean.TRUE, row.get("paid"));

    long id = (Long) row.get("id");
    admin.get("/admin/tables/rows/signups/row/" + id);
    admin.submitToAndFollow("/admin/tables/rows/signups",
        Map.of("action", "row_save", "table", "signups", "id", String.valueOf(id),
            "v_who", "Ana Rivera", "v_job", "bread", "v_count", "3", "hidden", "on"));

    assertNull("hidden now", tables().getById("signups", id));
    Map<String, Object> seen = tables().getById("signups", id, UserTables.See.everything);
    assertEquals("Ana Rivera", seen.get("who"));
    assertEquals("an unticked box is written as false, not left alone",
        Boolean.FALSE, seen.get("paid"));
  }

  @Test
  public void theRowListingFiltersAndSaysWhichAreHidden() throws Exception {
    makeTable();
    tables().insert("signups", Map.of("who", "Ana Rivera", "job", "bread"), null);
    tables().insert("signups",
        Map.of("who", "Bo Chen", "job", "soup", UserTable.HIDDEN, true), null);

    String all = admin.get("/admin/tables/rows/signups").body();
    assertTrue(all, all.contains("Ana Rivera"));
    assertTrue("the admin sees the held-back one", all.contains("Bo Chen"));
    assertTrue("and is told which it is", all.contains("hidden"));

    String filtered = admin.get("/admin/tables/rows/signups/list?q=chen").body();
    assertTrue(filtered, filtered.contains("Bo Chen"));
    assertFalse(filtered, filtered.contains("Ana Rivera"));

    String visible = admin.get("/admin/tables/rows/signups/list?show=visible").body();
    assertTrue(visible, visible.contains("Ana Rivera"));
    assertFalse(visible, visible.contains("Bo Chen"));
  }

  @Test
  public void deletingARowFromTheEditorRemovesIt() throws Exception {
    makeTable();
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    admin.get("/admin/tables/rows/signups/row/" + id);
    assertTrue(admin.submitToAndFollow("/admin/tables/rows/signups",
        Map.of("action", "row_delete", "table", "signups", "id", String.valueOf(id)))
        .contains("is gone"));
    assertNull(tables().getById("signups", id, UserTables.See.everything));
  }

  @Test
  public void theRowEditorNeedsTheTablesPermission() throws Exception {
    makeTable();
    assertEquals(404, member.get("/admin/tables/rows/signups").status());
    assertEquals(404, member.get("/admin/mutations").status());
  }
}
