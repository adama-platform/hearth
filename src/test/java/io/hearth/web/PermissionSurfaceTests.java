package io.hearth.web;

import io.hearth.auth.Permission;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a narrow role can actually do, and what it is shown.
 *
 * Three questions, and the third is the one that keeps getting missed.
 *
 * <ol>
 *   <li><b>Is every action guarded?</b> A section permission is permission to see a screen, never to
 *       press what is on it -- so each of these signs in as somebody holding exactly one role and
 *       posts the thing that role should not be able to do.</li>
 *   <li><b>Does a permission that exists actually do something?</b> `board_moderate` gated the admin
 *       board screen and nothing on the board itself, and `content_publish` was a checkbox in the
 *       role editor that decided nothing at all. A permission that is offered and never asked is
 *       worse than no permission: somebody grants it, believes the split exists, and it does not.
 *       </li>
 *   <li><b>Is somebody shown only what they can do?</b> A button that refuses, or a link that
 *       answers 404, teaches people that this software is broken and that asking an administrator
 *       is the only way to get anything done.</li>
 * </ol>
 */
public class PermissionSurfaceTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signedIn("boss@example.com");
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

  private Browser signedIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    String name = Character.toUpperCase(email.charAt(0)) + email.substring(1, email.indexOf('@'));
    browser.get("/self");
    browser.submitTo("/self", Map.of("action", "profile", "display_name", name,
        "headline", "", "about", "", "location", "", "links", ""));
    return browser;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  /** somebody approved, holding one role that grants exactly these permissions */
  private Browser memberWith(String email, String role, Permission... permissions)
      throws Exception {
    Browser member = signedIn(email);
    long id = idOf(email);
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));

    var fields = new java.util.LinkedHashMap<String, String>();
    fields.put("action", "save");
    fields.put("name", role);
    fields.put("label", role);
    fields.put("description", "");
    fields.put("color", "blue");
    for (Permission permission : permissions) {
      fields.put("p_" + permission.name(), "on");
    }
    admin.get("/admin/roles/new");
    Browser.Page made = admin.submitToAndFollow("/admin/roles", fields);
    assertTrue(made.body(), server.auth.forDomain("example.org").roleDefs.byName(role) != null);
    server.auth.forDomain("example.org").roles.grant(id, role, idOf("boss@example.com"));
    return member;
  }

  // ---- a permission that is offered has to be asked for --------------------------------------------



  @Test
  public void awriterCannotPublishAndIsToldWhySoftly() throws Exception {
    Browser writer = memberWith("ana@example.com", "writer", Permission.content_write);

    writer.get("/admin/content/new");
    Browser.Page refused = writer.submitToAndFollow("/admin/content",
        Map.of("action", "save", "uri", "/notes", "title", "Notes", "kind", "markdown",
            "template_name", "", "body", "hello", "published", "on"));
    assertTrue(refused.contains("not to publish them"));
    assertEquals("nothing was written", 0,
        server.auth.forDomain("example.org").site.store().contentCount());

    // ...and the same save without the box is fine
    writer.get("/admin/content/new");
    writer.submitToAndFollow("/admin/content",
        Map.of("action", "save", "uri", "/notes", "title", "Notes", "kind", "markdown",
            "template_name", "", "body", "hello"));
    assertEquals(1, server.auth.forDomain("example.org").site.store().contentCount());
    assertFalse(server.auth.forDomain("example.org").site.store().byUri("/notes").published());
  }

  @Test
  public void awriterIsNotShownACheckboxThatWouldRefuseThem() throws Exception {
    Browser writer = memberWith("ana@example.com", "writer", Permission.content_write);
    Browser.Page form = writer.get("/admin/content/new");
    assertFalse("no checkbox", form.contains("type=\"checkbox\" name=\"published\""));
    assertTrue("but it says who can", form.contains("Publish a page"));

    Browser publisher = memberWith("bo@example.com", "publisher",
        Permission.content_write, Permission.content_publish);
    assertTrue(publisher.get("/admin/content/new")
        .contains("type=\"checkbox\" name=\"published\""));
  }

  @Test
  public void editingALivePageDoesNotCountAsPublishingIt() throws Exception {
    admin.get("/admin/content/new");
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/about",
        "title", "About", "kind", "markdown", "template_name", "", "body", "hello",
        "published", "on"));
    long id = server.auth.forDomain("example.org").site.store().byUri("/about").id();

    Browser writer = memberWith("ana@example.com", "writer", Permission.content_write);
    writer.get("/admin/content/edit/" + id);
    Browser.Page saved = writer.submitToAndFollow("/admin/content",
        Map.of("action", "save", "id", Long.toString(id), "uri", "/about", "title", "About",
            "kind", "markdown", "template_name", "", "body", "hello again", "published", "on"));
    assertTrue(saved.contains("saved"));
    assertTrue("still live", server.auth.forDomain("example.org").site.store().byUri("/about")
        .published());
    assertTrue(server.auth.forDomain("example.org").site.store().byUri("/about").body()
        .contains("again"));
  }

  // ---- the overview shows what you can reach and nothing else ---------------------------------------

  @Test
  public void aNarrowRoleDoesNotGetTheMembershipOnTheFrontOfTheAdmin() throws Exception {
    // `admin_enter` is implied by every permission, so this page is the one everybody with any
    // role at all lands on -- and it used to print who was online next to their email addresses
    Browser decorator = memberWith("ana@example.com", "decorator", Permission.appearance_write);
    Browser.Page page = decorator.get("/admin");
    assertEquals(200, page.status());
    assertFalse("no presence list", page.contains("Here now"));
    assertFalse("no addresses", page.contains("boss@example.com"));
    assertFalse("no counts about people", page.contains(">people</div>"));
    assertTrue("and it says why it is empty rather than looking broken",
        page.contains("covers one part of this place"));

    Browser greeter = memberWith("bo@example.com", "greeter", Permission.people_read);
    assertTrue(greeter.get("/admin").contains("Here now"));
  }


  // ---- and the refusals stay refusals ---------------------------------------------------------------

  @Test
  public void everySectionSomebodyMayNotOpenIsAbsentAndAnswersLikeItIsNotThere() throws Exception {
    Browser decorator = memberWith("ana@example.com", "decorator", Permission.appearance_write);
    Browser.Page appearance = decorator.get("/admin/appearance");
    assertEquals("the one they were given", 200, appearance.status());
    assertFalse("and nothing else in the sidebar", appearance.contains("/admin/people\""));

    for (String path : new String[]{"/admin/people", "/admin/bans", "/admin/content",
        "/admin/system/logs", "/admin/roles", "/admin/legal"}) {
      assertEquals(path + " should look like it does not exist", 404,
          decorator.get(path).status());
    }
  }

  @Test
  public void aRefusalSaysWhatWasNeededAndDoesNotShout() throws Exception {
    Browser greeter = memberWith("ana@example.com", "greeter", Permission.people_read);
    Browser.Page refused = greeter.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf("boss@example.com"))));
    assertEquals("a refusal is still a page, not a slammed door", 200, refused.status());
    assertTrue(refused.contains("You are not able to do that"));
    assertTrue("and it names the permission in the words the role editor uses",
        refused.contains("Approve somebody waiting to join"));
  }
}
