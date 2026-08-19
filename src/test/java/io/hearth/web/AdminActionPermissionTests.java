package io.hearth.web;

import io.hearth.auth.Permission;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A section permission is permission to see a screen, never to press what is on it.
 *
 * Invariant 86, tested from the outside as a valid member rather than by reading handlers. The
 * shape of this bug is always the same and it is invisible from any one screen: every button on a
 * section posts to that section's path, so an action whose handler checks nothing inherits the
 * <em>mildest</em> permission that opens the screen. `/admin/content` opens for `content_read` --
 * "See pages and their history" -- and that is the weakest thing anybody on that screen can hold.
 *
 * Two buttons there were reachable with it: delete, and restore. Both are writes, and restore is
 * the worse of the two because it also carries the old version's published flag, so it was a way
 * past `content_publish` as well -- the one check the save path does make.
 *
 * The test grants exactly one permission and then presses everything, because a role with more
 * than one proves nothing about which of them was being checked.
 */
public class AdminActionPermissionTests {
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

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  /** somebody approved, holding exactly the permissions named and nothing else */
  private Browser memberWith(String email, String role, Permission... permissions)
      throws Exception {
    Browser browser = signIn(email);
    long id = accounts().users.byEmail(email).id();
    admin.get("/admin/people");
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("name", role);
    form.put("label", role);
    form.put("description", "");
    for (Permission permission : permissions) {
      form.put("p_" + permission.name(), "on");
    }
    admin.get("/admin/roles/new");
    admin.submitToAndFollow("/admin/roles", form);
    accounts().roles.grant(id, role, accounts().users.byEmail("boss@example.com").id());
    return browser;
  }

  private long pageAt(String uri, String body, boolean published) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("uri", uri);
    form.put("title", "A page");
    form.put("kind", "markdown");
    form.put("template_name", "");
    form.put("body", body);
    if (published) {
      form.put("published", "on");
    }
    admin.get("/admin/content/new");
    admin.submitToAndFollow("/admin/content", form);
    return accounts().site.store().byUri(uri).id();
  }

  // ---- reading is not deleting -------------------------------------------------------------------

  @Test
  public void readingPagesIsNotPermissionToDeleteOne() throws Exception {
    long id = pageAt("/about", "We meet on Tuesdays.", true);
    Browser reader = memberWith("reader@example.com", "reader", Permission.content_read);

    assertEquals("the screen is theirs to look at", 200, reader.get("/admin/content").status());
    reader.submitToAndFollow("/admin/content",
        Map.of("action", "delete", "id", Long.toString(id)));

    assertNotNull("a reader must not be able to delete a page",
        accounts().site.store().byUri("/about"));
  }

  // ---- reading is not rewriting ------------------------------------------------------------------

  @Test
  public void readingPagesIsNotPermissionToRestoreAnOldVersion() throws Exception {
    long id = pageAt("/about", "The first words.", true);
    LinkedHashMap<String, String> edit = new LinkedHashMap<>();
    edit.put("action", "save");
    edit.put("id", Long.toString(id));
    edit.put("uri", "/about");
    edit.put("title", "A page");
    edit.put("kind", "markdown");
    edit.put("template_name", "");
    edit.put("body", "The second words.");
    edit.put("published", "on");
    admin.get("/admin/content/edit/" + id);
    admin.submitToAndFollow("/admin/content", edit);
    assertEquals("The second words.", accounts().site.store().byUri("/about").body());

    Browser reader = memberWith("reader@example.com", "reader", Permission.content_read);
    reader.get("/admin/content");
    reader.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "1"));

    assertEquals("a restore is a save, so reading is not enough for it",
        "The second words.", accounts().site.store().byUri("/about").body());
  }

  /**
   * And the second half of the same button: a restore carries the old version's published flag.
   *
   * So somebody who may write but deliberately may not publish could take a page they had just
   * unpublished, restore the version before it, and have it live again -- past the one check the
   * save path does make on exactly that transition.
   */
  @Test
  public void restoringMayNotPublishForSomebodyWhoMayNotPublish() throws Exception {
    long id = pageAt("/about", "Live words.", true);
    Browser writer = memberWith("writer@example.com", "writer", Permission.content_write);

    LinkedHashMap<String, String> takeDown = new LinkedHashMap<>();
    takeDown.put("action", "save");
    takeDown.put("id", Long.toString(id));
    takeDown.put("uri", "/about");
    takeDown.put("title", "A page");
    takeDown.put("kind", "markdown");
    takeDown.put("template_name", "");
    takeDown.put("body", "Live words.");
    admin.get("/admin/content/edit/" + id);
    admin.submitToAndFollow("/admin/content", takeDown);
    org.junit.Assert.assertFalse(accounts().site.store().byUri("/about").published());

    writer.get("/admin/content");
    writer.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "1"));

    org.junit.Assert.assertFalse("restoring must not be a way past 'may not publish'",
        accounts().site.store().byUri("/about").published());
  }

  /** the whole point of the section permission is that the screen itself still opens */
  @Test
  public void aWriterCanStillDeleteAndRestore() throws Exception {
    long id = pageAt("/about", "The first words.", true);
    LinkedHashMap<String, String> edit = new LinkedHashMap<>();
    edit.put("action", "save");
    edit.put("id", Long.toString(id));
    edit.put("uri", "/about");
    edit.put("title", "A page");
    edit.put("kind", "markdown");
    edit.put("template_name", "");
    edit.put("body", "The second words.");
    edit.put("published", "on");
    admin.get("/admin/content/edit/" + id);
    admin.submitToAndFollow("/admin/content", edit);

    Browser writer = memberWith("writer@example.com", "writer",
        Permission.content_write, Permission.content_publish);
    writer.get("/admin/content");
    writer.submitToAndFollow("/admin/content",
        Map.of("action", "restore", "id", Long.toString(id), "version", "1"));
    assertEquals("The first words.", accounts().site.store().byUri("/about").body());

    writer.get("/admin/content");
    writer.submitToAndFollow("/admin/content",
        Map.of("action", "delete", "id", Long.toString(id)));
    assertNull(accounts().site.store().byUri("/about"));
  }

  /** and the refusal is visible rather than a silent no-op */
  @Test
  public void aRefusedActionSaysSo() throws Exception {
    long id = pageAt("/about", "We meet on Tuesdays.", true);
    Browser reader = memberWith("reader@example.com", "reader", Permission.content_read);
    reader.get("/admin/content");
    Browser.Page page = reader.submitToAndFollow("/admin/content",
        Map.of("action", "delete", "id", Long.toString(id)));
    assertTrue(page.body(), page.body().contains("not able"));
  }
}
