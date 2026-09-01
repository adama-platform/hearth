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
 * Tables a community invented, and the programs that read them.
 *
 * The interesting behaviour is the join: a table declared in the admin section has to become a set
 * of JavaScript functions on a page, the values have to survive the round trip with their types
 * intact, and a write has to invalidate exactly the cached answers it made untrue -- no more, and
 * crucially no fewer.
 */
public class UserTableTests {
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

  private UserTables tables() {
    return server.auth.forDomain("example.org").tables;
  }

  private Browser visitor() {
    return new Browser(server.port, "example.org");
  }

  private UserTable signups() {
    return new UserTable("signups",
        List.of(new UserField("who", UserField.Type.text, false),
            new UserField("job", UserField.Type.text, false),
            new UserField("count", UserField.Type.number, false),
            new UserField("paid", UserField.Type.flag, false)),
        List.of("job"));
  }

  private void savePage(String uri, String body) throws Exception {
    admin.get("/admin/content");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", uri, "title", "T",
        "kind", "javascript", "template_name", "", "published", "on", "body", body));
  }

  // ---- the shape -------------------------------------------------------------------------------

  @Test
  public void aTableLivesInItsOwnFileBesideTheSystemOne() throws Exception {
    assertNotNull(tables());
    assertTrue("a data file of its own, so a CREATE somebody typed is not on the accounts database",
        tables().file().getName().endsWith(".data"));
    assertTrue(new java.io.File(tables().file().getAbsolutePath() + ".mv.db").exists());
  }

  @Test
  public void creatingATableMakesItsFunctionsAppear() throws Exception {
    tables().create(signups(), null);
    assertEquals(List.of("signups_get_id(id)", "signups_list_job(value)",
            "signups_page(idAfter, count)", "signups_all()"),
        tables().byName("signups").functions());
  }

  /**
   * A field named after a SQL keyword works, which is why the columns are prefixed.
   *
   * `value`, `order` and `key` are the first three things somebody names a column and every one of
   * them is a syntax error in H2's strict mode unquoted. The prefix removes the whole class rather
   * than maintaining a denylist that goes stale on the next upgrade.
   */
  @Test
  public void aFieldNamedAfterASqlKeywordIsFine() throws Exception {
    tables().create(new UserTable("things",
        List.of(new UserField("value", UserField.Type.text, false),
            new UserField("order", UserField.Type.number, false),
            new UserField("key", UserField.Type.text, false),
            new UserField("select", UserField.Type.text, false)),
        List.of("key")), null);
    long id = tables().insert("things",
        Map.of("value", "v", "order", 2, "key", "k", "select", "s"), null);
    assertEquals("v", tables().getById("things", id).get("value"));
    assertEquals(1, tables().listByIndex("things", "key", "k").size());
  }

  @Test
  public void abrokenDefinitionIsRefusedWithEveryReason() {
    UserTable bad = new UserTable("9nope", List.of(), List.of("ghost"));
    List<String> problems = bad.problems();
    assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.contains("table name")));
    assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.contains("at least one")));
    assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.contains("no field called")));
  }

  @Test
  public void idIsRefusedAsAFieldName() {
    assertNotNull(UserTable.checkName("a field name", "id"));
    assertNull(UserTable.checkName("a field name", "who"));
  }

  @Test
  public void changingTheTypeOfAFieldIsRefusedRatherThanGuessed() throws Exception {
    tables().create(signups(), null);
    try {
      tables().alter(new UserTable("signups",
          List.of(new UserField("who", UserField.Type.number, false)), List.of()), null);
      org.junit.Assert.fail("should have refused");
    } catch (UserTables.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("changing the type"));
    }
  }

  @Test
  public void alteringAddsDropsAndSaysWhichItDid() throws Exception {
    tables().create(signups(), null);
    tables().insert("signups", Map.of("who", "Ana", "job", "bread"), null);

    List<String> done = tables().alter(new UserTable("signups",
        List.of(new UserField("who", UserField.Type.text, false),
            new UserField("note", UserField.Type.text, false)),
        List.of("who")), null);

    assertTrue(done.toString(), done.contains("added note"));
    assertTrue(done.toString(), done.contains("dropped job and everything in it"));
    assertTrue(done.toString(), done.contains("indexed who"));
    assertTrue(done.toString(), done.contains("dropped the index on job"));
    assertNull("the dropped field is gone from the rows too",
        tables().all("signups").get(0).get("job"));
  }

  @Test
  public void droppingATableTakesItsRowsWithIt() throws Exception {
    tables().create(signups(), null);
    tables().insert("signups", Map.of("who", "Ana"), null);
    tables().drop("signups", null);
    assertNull(tables().byName("signups"));
    assertEquals(0, tables().all("signups").size());
  }

  // ---- reading from a page ---------------------------------------------------------------------

  @Test
  public void aPageReadsATableByIdAndByIndex() throws Exception {
    tables().create(signups(), null);
    long ana = tables().insert("signups",
        Map.of("who", "Ana", "job", "bread", "count", 2, "paid", true), null);
    tables().insert("signups", Map.of("who", "Bo", "job", "bread"), null);
    tables().insert("signups", Map.of("who", "Cy", "job", "soup"), null);

    savePage("/one", "var r = signups_get_id(" + ana + ");"
        + " render(r.who + '/' + r.job + '/' + r.count + '/' + r.paid);");
    assertTrue(visitor().get("/one").body(), visitor().get("/one").contains("Ana/bread/2/true"));

    savePage("/bread", "signups_list_job('bread').forEach(function (r) {"
        + " render('<li>' + r.who + '</li>'); });");
    Browser.Page page = visitor().get("/bread");
    assertTrue(page.body(), page.contains("<li>Ana</li><li>Bo</li>"));
    assertFalse("and nobody from another index value", page.contains("Cy"));
  }

  @Test
  public void aPageWalksTheTableAndPagesThroughIt() throws Exception {
    tables().create(signups(), null);
    for (int k = 0; k < 5; k++) {
      tables().insert("signups", Map.of("who", "p" + k), null);
    }
    savePage("/all", "render(signups_all().length);");
    assertTrue(visitor().get("/all").contains(">5<") || visitor().get("/all").contains("5"));

    savePage("/page", "var rows = signups_page(2, 2);"
        + " rows.forEach(function (r) { render('[' + r.id + ']'); });");
    assertTrue(visitor().get("/page").body(), visitor().get("/page").contains("[3][4]"));
  }

  @Test
  public void aMissingRowIsNullRatherThanAnError() throws Exception {
    tables().create(signups(), null);
    savePage("/missing", "render(signups_get_id(9999) === null ? 'nothing' : 'something');");
    assertTrue(visitor().get("/missing").contains("nothing"));
  }

  /**
   * Asking for a table that is not there is an error on the line that asked.
   *
   * The tempting alternative -- an empty list -- reads exactly like "no rows yet", so a page whose
   * table was dropped this morning renders an empty listing and nobody finds out.
   */
  @Test
  public void askingForATableThatIsGoneSaysSo() throws Exception {
    tables().create(signups(), null);
    savePage("/gone", "render(signups_all().length);");
    assertTrue(visitor().get("/gone").contains("0"));
    tables().drop("signups", null);
    Browser.Page page = visitor().get("/gone");
    assertTrue(page.body(), page.contains("did not run"));
    assertTrue(page.body(), page.contains("signups_all is not defined"));
  }

  @Test
  public void aPageCannotInventAQueryThatWasNotDeclared() throws Exception {
    tables().create(signups(), null);
    savePage("/sneaky", "render(typeof signups_list_who);");
    assertTrue("only declared indexes become functions",
        visitor().get("/sneaky").contains("undefined"));
  }

  // ---- query parameters ------------------------------------------------------------------------

  @Test
  public void queryParametersArriveAsTheStrictestTypeTheyHonestlyAre() throws Exception {
    savePage("/q", "render([typeof query('page'), query('page'), typeof query('rate'),"
        + " typeof query('on'), query('on'), typeof query('who'), query('who')].join('|'));");
    Browser.Page page = visitor().get("/q?page=2&rate=1.5&on=true&who=Ana");
    assertTrue(page.body(), page.contains("number|2|number|boolean|true|string|Ana"));
  }

  @Test
  public void anIntegerHasNoDecimalPointWhenItIsPrintedBack() throws Exception {
    // the number ends up in the href of a next-page link, and "?page=2.0" is not a link anybody
    // wants to have generated
    savePage("/n", "render('page=' + (query('page', 0) + 1));");
    assertTrue(visitor().get("/n?page=2").body(), visitor().get("/n?page=2").contains("page=3"));
  }

  @Test
  public void aMissingParameterGivesTheFallback() throws Exception {
    savePage("/d", "render(query('page', 7) + '/' + (query('nope') === null));");
    assertTrue(visitor().get("/d").body(), visitor().get("/d").contains("7/true"));
  }

  @Test
  public void aParameterOfTheWrongShapeGivesTheFallbackRatherThanAString() throws Exception {
    // ?page=banana must not make arithmetic produce "banana1"
    savePage("/w", "render(query('page', 0) + 1);");
    assertTrue(visitor().get("/w?page=banana").body(),
        visitor().get("/w?page=banana").contains("1"));
  }

  @Test
  public void somethingThatOnlyLooksLikeANumberStaysText() {
    assertEquals("007", TableBindings.typed("007"));
    assertEquals("+441234", TableBindings.typed("+441234"));
    assertEquals("", TableBindings.typed(""));
    assertEquals(2L, TableBindings.typed("2"));
    assertEquals(Boolean.TRUE, TableBindings.typed("true"));
  }

  // ---- the cache and the events it listens to --------------------------------------------------

  /**
   * A row that changes an indexed value leaves the old value as well as joining the new one.
   *
   * This is the half that is easy to miss and impossible to notice by hand: invalidating only the
   * new index value leaves the row cached under the value it used to have for a full TTL, which is
   * a member still listed in the group they just left.
   */
  @Test
  public void movingBetweenIndexValuesInvalidatesBothSides() throws Exception {
    tables().create(signups(), null);
    long id = tables().insert("signups", Map.of("who", "Ana", "job", "bread"), null);

    assertEquals(1, tables().listByIndex("signups", "job", "bread").size());
    assertEquals(0, tables().listByIndex("signups", "job", "soup").size());

    tables().update("signups", id, Map.of("job", "soup"), null);

    assertEquals("the value they left has to stop being true",
        0, tables().listByIndex("signups", "job", "bread").size());
    assertEquals("and the value they joined",
        1, tables().listByIndex("signups", "job", "soup").size());
  }

  @Test
  public void changingARowInvalidatesThatRowAndTheListings() throws Exception {
    tables().create(signups(), null);
    long id = tables().insert("signups", Map.of("who", "Ana"), null);
    assertEquals("Ana", tables().getById("signups", id).get("who"));
    assertEquals(1, tables().all("signups").size());

    tables().update("signups", id, Map.of("who", "Ana Rivera"), null);
    assertEquals("Ana Rivera", tables().getById("signups", id).get("who"));

    tables().insert("signups", Map.of("who", "Bo"), null);
    assertEquals("a listing is a window an insert can move", 2, tables().all("signups").size());
  }

  @Test
  public void deletingARowRemovesItFromItsIndexToo() throws Exception {
    tables().create(signups(), null);
    long id = tables().insert("signups", Map.of("who", "Ana", "job", "bread"), null);
    assertEquals(1, tables().listByIndex("signups", "job", "bread").size());

    tables().delete("signups", id, null);
    assertNull(tables().getById("signups", id));
    assertEquals(0, tables().listByIndex("signups", "job", "bread").size());
    assertEquals(0, tables().all("signups").size());
  }

  @Test
  public void oneTablesWritesDoNotDropAnothersRows() throws Exception {
    tables().create(signups(), null);
    tables().create(new UserTable("notes",
        List.of(new UserField("text", UserField.Type.text, false)), List.of()), null);
    long note = tables().insert("notes", Map.of("text", "keep me"), null);
    assertEquals("keep me", tables().getById("notes", note).get("text"));

    tables().insert("signups", Map.of("who", "Ana"), null);
    assertEquals("still there and still right",
        "keep me", tables().getById("notes", note).get("text"));
  }

  // ---- the admin screens -----------------------------------------------------------------------

  @Test
  public void aTableIsMadeChangedAndDroppedFromTheAdminSection() throws Exception {
    admin.get("/admin/tables");
    Browser.Page made = admin.submitToAndFollow("/admin/tables", Map.of(
        "action", "save", "name", "guests",
        "f_name_0", "who", "f_type_0", "text",
        "f_name_1", "table_no", "f_type_1", "number", "f_index_1", "1"));
    assertTrue(made.body(), made.contains("guests is ready"));
    assertTrue("the functions are on the page it lands on",
        made.contains("guests_list_table_no(value)"));

    UserTable table = tables().byName("guests");
    assertEquals(2, table.fields().size());
    assertTrue(table.hasIndex("table_no"));

    // a field with no name is how one is removed
    Browser.Page changed = admin.submitToAndFollow("/admin/tables", Map.of(
        "action", "save", "name", "guests",
        "f_name_0", "who", "f_type_0", "text",
        "f_name_1", "", "f_type_1", "number"));
    assertTrue(changed.body(), changed.contains("dropped table_no"));
    assertEquals(1, tables().byName("guests").fields().size());

    admin.get("/admin/tables");
    assertTrue(admin.submitToAndFollow("/admin/tables",
        Map.of("action", "drop", "name", "guests")).contains("is gone"));
    assertNull(tables().byName("guests"));
  }

  @Test
  public void aBadNameIsRefusedWithTheReason() throws Exception {
    admin.get("/admin/tables");
    assertTrue(admin.submitToAndFollow("/admin/tables", Map.of(
        "action", "save", "name", "Not A Name", "f_name_0", "who", "f_type_0", "text"))
        .contains("lowercase letters"));
    assertTrue(tables().all().isEmpty());
  }

  @Test
  public void anythingOtherThanSavingOrDroppingIsRefused() throws Exception {
    admin.get("/admin/tables");
    assertTrue(admin.submitToAndFollow("/admin/tables",
        Map.of("action", "truncate", "name", "signups"))
        .contains("not something this page can do"));
  }

  @Test
  public void somebodyWithoutThePermissionSeesNoTablesSection() throws Exception {
    Browser member = new Browser(server.port, "example.org");
    member.get("/register");
    member.submit(Map.of("email", "member@example.com"));
    member.submit(Map.of("code", server.mail().lastCodeFor("member@example.com")));
    assertEquals(404, member.get("/admin/tables").status());
    assertFalse(member.get("/admin").contains("/admin/tables"));
  }

  @Test
  public void theEditorTellsAnAuthorWhichFunctionsExist() throws Exception {
    tables().create(signups(), null);
    admin.get("/admin/content");
    String editor = admin.get("/admin/content/new").body();
    assertTrue("the reference is built from what exists rather than from a paragraph",
        editor.contains("signups_list_job(value)"));
    assertTrue("and there is a way to open it", editor.contains("What can I use here?"));
  }

  @Test
  public void aPageSeesAWriteThatHappenedAfterItWasFirstRendered() throws Exception {
    tables().create(signups(), null);
    savePage("/live", "render(signups_all().length);");
    assertTrue(visitor().get("/live").contains("0"));

    tables().insert("signups", Map.of("who", "Ana"), null);
    assertTrue("a dynamic page is not cached and its reads are invalidated",
        visitor().get("/live").contains("1"));
  }
}
