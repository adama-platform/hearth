package io.hearth.auth;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Roles, permissions, and the doors they open.
 *
 * The half worth testing is refusal. A permission system that lets the right people in is easy; one
 * that keeps the wrong people out *and does not tell them what they are missing* is the job. Every
 * refusal here checks a 404 rather than a 403, because a section that says "forbidden" is a section
 * that has confirmed it exists.
 */
public class PermissionTests {
  private Configs configs;
  private TestServer server;
  private Browser boss;
  private Browser ed;
  private Browser nobody;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    boss = signIn("boss@example.com");
    ed = signIn("ed@example.com");
    nobody = signIn("nobody@example.com");
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

  // ---- the built-in roles ----------------------------------------------------------------------

  @Test
  public void adminIsSeededAtBootAndHoldsEverything() throws Exception {
    RoleDefs.Def admin = defs().byName("admin");
    assertNotNull("a database with no administrator role is a locked-out community", admin);
    assertTrue(admin.builtin());
    assertTrue(admin.permissions().contains(Permission.everything));
    for (Permission permission : Permission.values()) {
      assertTrue(permission.name(), admin.allows(permission));
    }
  }

  @Test
  public void editorIsSeededWithContentAndNoSystem() throws Exception {
    RoleDefs.Def editor = defs().byName("editor");
    assertNotNull(editor);
    assertFalse("it is a starting point, not a fixture", editor.builtin());
    assertTrue(editor.allows(Permission.content_write));
    assertTrue(editor.allows(Permission.templates_write));
    assertFalse("editors do not see the machine room", editor.allows(Permission.system_read));
    assertFalse(editor.allows(Permission.people_roles));
  }

  @Test
  public void theAdminRoleRefusesToBeEdited() throws Exception {
    Browser.Page page = boss.submitToAndFollow("/admin/roles",
        Map.of("action", "save", "name", "admin", "label", "Slightly Less Admin"));
    assertTrue(page.contains("built in"));
    assertTrue("and still holds everything",
        defs().byName("admin").permissions().contains(Permission.everything));
  }

  @Test
  public void theAdminRoleRefusesToBeDeleted() throws Exception {
    boss.submitToAndFollow("/admin/roles", Map.of("action", "delete", "name", "admin"));
    assertNotNull(defs().byName("admin"));
  }

  @Test
  public void seedingTwiceChangesNothing() throws Exception {
    int before = defs().all().size();
    defs().seed();
    defs().seed();
    assertEquals("boot is not a migration", before, defs().all().size());
  }


  // ---- what a role opens -----------------------------------------------------------------------

  @Test
  public void somebodyWithNoRoleCannotFindTheAdminSectionAtAll() throws Exception {
    assertEquals(404, nobody.get("/admin").status());
    assertEquals(404, nobody.get("/admin/people").status());
    assertEquals("a 403 would confirm it is there", 404,
        nobody.get("/admin/system/logs").status());
  }

  @Test
  public void anEditorReachesContentAndNotTheSystem() throws Exception {
    grant("ed@example.com", "editor");

    assertEquals(200, ed.get("/admin/content").status());
    assertEquals(200, ed.get("/admin/templates").status());
    assertEquals("the machine room is not theirs", 404, ed.get("/admin/system/logs").status());
    assertEquals(404, ed.get("/admin/system/caching").status());
    assertEquals(404, ed.get("/admin/roles").status());
    assertEquals(404, ed.get("/admin/people").status());
  }

  @Test
  public void theSidebarShowsOnlyTheDoorsThatOpen() throws Exception {
    grant("ed@example.com", "editor");
    Browser.Page page = ed.get("/admin/content");

    assertTrue(page.contains("Content"));
    assertTrue(page.contains("Templates"));
    assertFalse("a sidebar full of doors that say no is worse than a small sidebar",
        page.contains("Caching"));
    assertFalse(page.contains(">Roles<"));
    assertFalse("and the System heading goes with its children",
        page.contains(">System<"));
  }

  @Test
  public void anAdminSeesEverySection() throws Exception {
    // from the overview: the top-level sections, with everybody's children folded away
    Browser.Page page = boss.get("/admin");
    for (String label : new String[]{"People", "Content", "System",
        "Customization"}) {
      assertTrue(label, page.contains("<span>" + label + "</span>"));
    }
    assertTrue("and a child is one press away, from its parent",
        boss.get("/admin/people").contains("<span>Roles</span>"));
    // the addresses did not move when the heading appeared over them; a bookmark still works
    assertTrue(boss.get("/admin/appearance").contains("<span>Legal</span>"));
    assertTrue("and the heading lands on its first child",
        boss.get("/admin/look").contains("<span>Messages</span>"));
  }

  @Test
  public void theOverviewLeadsWithWhoIsHere() throws Exception {
    // the last ten row changes were a debugging view wearing a dashboard's clothes: interesting
    // once, and never again. Who is here is the only number on that page you can act on.
    Browser.Page page = boss.get("/admin");
    assertTrue(page.contains("Here now"));
    assertFalse("the mutation stream has its own screen", page.contains("The last ten mutations"));
    assertFalse("and the numbers about the machine went to System, which is where somebody asks a"
        + " question about a machine", page.contains("live pings"));
    assertTrue("with a line saying so", page.contains("/admin/system/machine"));
  }



  @Test
  public void anyPermissionAtAllImpliesReachingTheAdminSection() throws Exception {
    defs().save("librarian", "Librarian", "", EnumSet.of(Permission.content_read), "blue", null);
    assertTrue("a role with powers nobody can get to is a role that does nothing",
        defs().byName("librarian").allows(Permission.admin_enter));
  }

  @Test
  public void aRoleCannotBeGivenTheGodBit() throws Exception {
    defs().save("sneaky", "Sneaky", "", EnumSet.of(Permission.everything), "blue", null);
    RoleDefs.Def def = defs().byName("sneaky");
    assertFalse("a second god role is one nothing protects from being edited",
        def.permissions().contains(Permission.everything));
    assertFalse(def.allows(Permission.system_read));
  }

  @Test
  public void twoRolesAddUp() throws Exception {
    defs().save("a", "A", "", EnumSet.of(Permission.content_write), "blue", null);
    defs().save("b", "B", "", EnumSet.of(Permission.system_read), "blue", null);
    grant("nobody@example.com", "a");
    grant("nobody@example.com", "b");

    assertEquals(200, nobody.get("/admin/content").status());
    assertEquals("holding two roles can only ever give you more",
        200, nobody.get("/admin/system/logs").status());
  }

  @Test
  public void aGrantOfARoleThatNoLongerExistsGrantsNothing() throws Exception {
    defs().save("temp", "Temp", "", EnumSet.of(Permission.system_read), "blue", null);
    grant("nobody@example.com", "temp");
    assertEquals(200, nobody.get("/admin/system/logs").status());

    // deleted through the DAO, which also drops the grants -- but a stale grant must be harmless
    accounts().roles.grant(accounts().users.byEmail("nobody@example.com").id(), "ghost", null);
    assertEquals("a role with no definition contributes nothing rather than failing",
        200, nobody.get("/admin/system/logs").status());
  }

  @Test
  public void deletingARoleTakesTheGrantsWithIt() throws Exception {
    defs().save("temp", "Temp", "", EnumSet.of(Permission.system_read), "blue", null);
    grant("nobody@example.com", "temp");
    assertEquals(200, nobody.get("/admin/system/logs").status());

    boss.submitToAndFollow("/admin/roles", Map.of("action", "delete", "name", "temp"));
    assertEquals(404, nobody.get("/admin/system/logs").status());
    assertFalse(accounts().roles.of(accounts().users.byEmail("nobody@example.com").id())
        .contains("temp"));
  }

  @Test
  public void aConfigAdminOutranksTheDatabaseEntirely() throws Exception {
    // even with every role deleted, the config list is the lock on the door
    boss.submitToAndFollow("/admin/roles", Map.of("action", "delete", "name", "editor"));
    assertEquals(200, boss.get("/admin/system/logs").status());
    assertTrue(accounts().access.can(accounts().users.byEmail("boss@example.com"),
        Permission.everything));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private RoleDefs defs() {
    return accounts().roleDefs;
  }

  private void grant(String email, String role) throws Exception {
    accounts().roles.grant(accounts().users.byEmail(email).id(), role, null);
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
