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
import static org.junit.Assert.assertTrue;

/**
 * Can somebody make themselves more powerful than they were given?
 *
 * A permission system is only as good as its narrowest gate. These are the paths where a mild
 * permission reaches a powerful action, and every one of them is checked from the outside -- by
 * posting what a browser would post, as somebody holding only the mild permission.
 */
public class EscalationTests {
  private Configs configs;
  private TestServer server;
  private Browser boss;
  private Browser mild;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    boss = signIn("boss@example.com");
    mild = signIn("mild@example.com");
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

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private void give(String email, String role, Permission... permissions) throws Exception {
    accounts().roleDefs.save(role, role, "", EnumSet.copyOf(java.util.List.of(permissions)),
        "blue", null);
    accounts().roles.grant(accounts().users.byEmail(email).id(), role, null);
  }

  private long idOf(String email) throws Exception {
    return accounts().users.byEmail(email).id();
  }

  // ---- the People section --------------------------------------------------------------------

  @Test
  public void readingTheMemberListDoesNotLetYouMakeAnAdmin() throws Exception {
    // "see the member list and read profiles" is the mildest People permission, the one a greeter
    // or a welcomer would be given. It must not reach the button that hands over the whole server.
    give("mild@example.com", "greeter", Permission.people_read);
    assertEquals("the section itself is theirs", 200, mild.get("/admin/people").status());

    mild.submitToAndFollow("/admin/people",
        Map.of("action", "grant_admin", "user", Long.toString(idOf("mild@example.com"))));

    assertFalse("reading a list is not permission to take the server",
        accounts().access.can(accounts().users.byEmail("mild@example.com"),
            Permission.everything));
    assertFalse(accounts().roles.of(idOf("mild@example.com")).contains("admin"));
  }

  @Test
  public void readingTheMemberListDoesNotLetYouApprove() throws Exception {
    give("mild@example.com", "greeter", Permission.people_read);
    signIn("newcomer@example.com");

    mild.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf("newcomer@example.com"))));
    assertFalse("approving is who gets into the community",
        accounts().users.byEmail("newcomer@example.com").isApproved());
  }

  @Test
  public void readingTheMemberListDoesNotLetYouDeleteSomebody() throws Exception {
    give("mild@example.com", "greeter", Permission.people_read);
    signIn("victim@example.com");

    mild.submitToAndFollow("/admin/people",
        Map.of("action", "reject", "user", Long.toString(idOf("victim@example.com"))));
    org.junit.Assert.assertNotNull("rejecting deletes the account and everything it wrote",
        accounts().users.byEmail("victim@example.com"));
  }

  @Test
  public void somebodyWhoMayApproveStillCannotHandOverTheServer() throws Exception {
    give("mild@example.com", "approver", Permission.people_approve);
    signIn("newcomer@example.com");

    // they can do their job
    mild.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf("newcomer@example.com"))));
    assertTrue(accounts().users.byEmail("newcomer@example.com").isApproved());

    // and not more than their job
    mild.submitToAndFollow("/admin/people",
        Map.of("action", "grant_admin", "user", Long.toString(idOf("mild@example.com"))));
    assertFalse(accounts().roles.of(idOf("mild@example.com")).contains("admin"));
  }

  @Test
  public void anAdminCanStillDoAllOfIt() throws Exception {
    signIn("newcomer@example.com");
    boss.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf("newcomer@example.com"))));
    assertTrue(accounts().users.byEmail("newcomer@example.com").isApproved());

    boss.submitToAndFollow("/admin/people",
        Map.of("action", "grant_admin", "user", Long.toString(idOf("newcomer@example.com"))));
    assertTrue(accounts().roles.of(idOf("newcomer@example.com")).contains("admin"));
  }

  // ---- the other sections where one permission opens several actions ---------------------------

  @Test
  public void suggestingAnEditDoesNotLetYouApproveOne() throws Exception {
    // the suggestions section needs content_propose; approving needs content_review, and the
    // difference between them is the entire point of having a queue
    give("mild@example.com", "suggester", Permission.content_propose);
    assertEquals(200, mild.get("/admin/content/proposals").status());

    boss.submitToAndFollow("/admin/content", Map.of("action", "save", "kind", "markdown",
        "template_name", "", "published", "on", "uri", "/about", "title", "About", "body", "one"));
    long id = accounts().site.store().byUri("/about").id();
    mild.submitToAndFollow("/admin/content", Map.of("action", "suggest", "kind", "markdown",
        "template_name", "", "id", Long.toString(id), "uri", "/about", "title", "About",
        "body", "two"));
    long proposal = accounts().site.store().proposals().open(5).get(0).id();

    mild.submitToAndFollow("/admin/content/proposals",
        Map.of("action", "approve", "id", Long.toString(proposal)));
    assertEquals("one is not the other", "one", accounts().site.store().byId(id).body());
  }

  @Test
  public void invitingOneDoesNotLetYouInviteAThousand() throws Exception {
    give("mild@example.com", "greeter", Permission.invites_send);
    StringBuilder addresses = new StringBuilder();
    for (int k = 0; k < 20; k++) {
      addresses.append("person").append(k).append("@example.com\n");
    }

    mild.submitToAndFollow("/admin/invites",
        Map.of("action", "bulk", "addresses", addresses.toString()));
    assertEquals("bulk is its own permission because it is its own kind of mistake",
        0, accounts().invites.count());
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
