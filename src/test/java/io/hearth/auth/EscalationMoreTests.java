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
 * The two escalations the sections added since the last review opened, proved from outside.
 *
 * Both are the same shape as the first escalation this project found, and both were invisible for
 * the same reason: a
 * permission that reads like a small job reaching a button that is not one.
 */
public class EscalationMoreTests {
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

  private Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private void give(String email, String role, Permission... permissions) throws Exception {
    accounts().roleDefs.save(role, role, "", EnumSet.copyOf(java.util.List.of(permissions)),
        "blue", null);
    accounts().roles.grant(accounts().users.byEmail(email).id(), role, null);
  }





  @Test
  public void theMembersDirectoryIsForMembers() throws Exception {
    // the single most valuable page on this server to somebody who should not have it
    try (io.hearth.testkit.Http http = new io.hearth.testkit.Http()) {
      assertEquals(303, http.get(server.port, "example.org", "/members").status);
      assertEquals(303, http.get(server.port, "example.org", "/members/1").status);
    }
    // signed in but not approved: the community itself is what approval gates
    Browser waiting = signIn("waiting@example.com");
    assertFalse(waiting.get("/members").contains("class=\"members\""));
  }

  @Test
  public void aMemberPageNeverCarriesAnEmailAddress() throws Exception {
    Browser ana = signIn("ana@example.com");
    long id = accounts().users.byEmail("ana@example.com").id();
    boss.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    ana.submitToAndFollow("/self", Map.of("action", "profile", "display_name", "Ana",
        "location", "Kansas City", "about", "I bring the bread."));

    Browser.Page directory = ana.get("/members");
    assertTrue(directory.contains("Ana"));
    assertTrue(directory.contains("Kansas City"));
    assertFalse("a member list is the easiest thing in the world to screenshot",
        directory.contains("ana@example.com"));
    assertFalse(ana.get("/members/" + id).contains("ana@example.com"));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
