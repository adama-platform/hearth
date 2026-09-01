package io.hearth.store;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one screen that destroys data, and the reasons it is allowed to exist.
 *
 * A table this software has stopped declaring survives every upgrade, because the upgrader adds and
 * never drops -- which is what made it safe to remove a third of this project from a database that
 * had rows in it. This is the other half of that bargain: a person, holding every permission there
 * is, can finally throw the leftovers away.
 *
 * The tests that matter are the refusals. The table name arrives from a form, and a form that could
 * name any table at all would make the most powerful button in the admin section into a DROP TABLE
 * with a text field in front of it.
 */
public class LeftoverTests {
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

  private Connection connection() throws SQLException {
    return server.auth.forDomain("example.org").store.connection();
  }

  /** a table from a feature that no longer exists, with something in it */
  private void makeOldTable() throws Exception {
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE board_posts (id BIGINT PRIMARY KEY, said VARCHAR(200))");
      statement.execute("INSERT INTO board_posts VALUES (1, 'I will bring the flour')");
      statement.execute("INSERT INTO board_posts VALUES (2, 'see you Tuesday')");
    }
  }

  /**
   * Unquoted on purpose: H2 folds a bare identifier to upper case and matches, where a quoted one
   * is case-sensitive and `"emails"` finds nothing at all. Getting that wrong here made two of
   * these tests claim the accounts table had been dropped when it was sitting there untouched.
   */
  private boolean exists(String table) throws Exception {
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.executeQuery("SELECT COUNT(*) FROM " + table);
      return true;
    } catch (SQLException ex) {
      return false;
    }
  }

  @Test
  public void aFreshDatabaseHasNothingLeftOver() throws Exception {
    try (Connection connection = connection()) {
      assertTrue("every table in a new database is one the code declares",
          Leftovers.find(connection).isEmpty());
    }
    assertTrue(admin.get("/admin/system/cleanup").contains("Nothing left over"));
  }

  @Test
  public void anOldTableIsFoundWithItsRowCount() throws Exception {
    makeOldTable();
    try (Connection connection = connection()) {
      var found = Leftovers.find(connection);
      assertEquals(1, found.size());
      assertEquals("BOARD_POSTS", found.get(0).name().toUpperCase(java.util.Locale.ROOT));
      assertEquals("the count is what decides whether anybody cares", 2, found.get(0).rows());
    }
    String page = admin.get("/admin/system/cleanup").body();
    assertTrue(page, page.toLowerCase(java.util.Locale.ROOT).contains("board_posts"));
    assertTrue("and says how much is in it", page.contains("2"));
  }

  @Test
  public void droppingItRemovesItForGood() throws Exception {
    makeOldTable();
    admin.get("/admin/system/cleanup");
    Browser.Page done = admin.submitToAndFollow("/admin/system/cleanup",
        Map.of("action", "drop", "table", "BOARD_POSTS"));

    assertTrue(done.body(), done.contains("is gone"));
    assertFalse(exists("BOARD_POSTS"));
    assertTrue("and the screen goes back to saying there is nothing left",
        admin.get("/admin/system/cleanup").contains("Nothing left over"));
  }

  /**
   * A table the software still uses can never be dropped here.
   *
   * The name is a form field, so this is the assertion the whole class exists for. Without the
   * re-derivation in {@link Leftovers#drop}, posting `emails` to this screen would delete every
   * account in the community.
   */
  @Test
  public void aTableTheCodeStillDeclaresIsRefused() throws Exception {
    admin.get("/admin/system/cleanup");
    Browser.Page refused = admin.submitToAndFollow("/admin/system/cleanup",
        Map.of("action", "drop", "table", Schema.EMAILS));

    assertTrue(refused.body(), refused.contains("not a table this server has stopped using"));
    assertTrue("the accounts are all still there", exists(Schema.EMAILS));
  }

  @Test
  public void aTableThatDoesNotExistIsRefusedRatherThanReportedAsDone() throws Exception {
    admin.get("/admin/system/cleanup");
    assertTrue(admin.submitToAndFollow("/admin/system/cleanup",
        Map.of("action", "drop", "table", "no_such_table"))
        .contains("not a table this server has stopped using"));
  }

  @Test
  public void aNameShapedLikeAnAttackIsRefused() throws Exception {
    admin.get("/admin/system/cleanup");
    Browser.Page refused = admin.submitToAndFollow("/admin/system/cleanup",
        Map.of("action", "drop", "table", "x\"; DROP TABLE emails; --"));

    assertTrue(refused.body(), refused.contains("not a table this server has stopped using"));
    assertTrue("nothing was executed", exists(Schema.EMAILS));
  }

  @Test
  public void anythingOtherThanDroppingIsRefused() throws Exception {
    makeOldTable();
    admin.get("/admin/system/cleanup");
    assertTrue(admin.submitToAndFollow("/admin/system/cleanup",
        Map.of("action", "truncate", "table", "BOARD_POSTS"))
        .contains("not something this page can do"));
    assertTrue("and it is still there", exists("BOARD_POSTS"));
  }

  /**
   * The section needs `everything`, and somebody without it is not told it is there.
   *
   * A 403 would confirm what is behind the door and a sidebar entry that refuses advertises what
   * this person is not trusted with -- the same rule every other section follows, applied to the
   * one screen where being wrong is unrecoverable.
   */
  @Test
  public void somebodyWhoCannotDoEverythingSeesNoDoor() throws Exception {
    Browser member = new Browser(server.port, "example.org");
    member.get("/register");
    member.submit(Map.of("email", "member@example.com"));
    member.submit(Map.of("code", server.mail().lastCodeFor("member@example.com")));

    assertEquals(404, member.get("/admin/system/cleanup").status());
    assertFalse(member.get("/admin").contains("/admin/system/cleanup"));
  }
}
