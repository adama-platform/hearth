package io.hearth.web;

import io.hearth.auth.Roles;
import io.hearth.auth.UserRecord;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Nobody gets in until somebody says so, and there is always a way for the first somebody to exist.
 *
 * The two halves are inseparable: requiring approval without a config-level escape hatch produces a
 * server nobody can ever administer, so both are tested together.
 */
public class ApprovalTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
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

  private Browser browser() {
    return new Browser(server.port, "example.org");
  }

  /** register through the real pages and hand back the resulting page */
  private Browser.Page registerAs(Browser client, String email) throws Exception {
    client.get("/register");
    client.submit(Map.of("email", email));
    return client.submit(Map.of("code", server.mail().lastCodeFor(email)));
  }

  private UserRecord userFor(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email);
  }

  // ---- the ordinary case ---------------------------------------------------------------------

  @Test
  public void anOrdinaryRegistrationCreatesTheAccountAndSendsThemToTheWelcome() throws Exception {
    Browser client = browser();
    Browser.Page done = registerAs(client, "newcomer@example.com");

    // a session means "proved the address", not "approved". Somebody brand new lands in the
    // welcome, because saying what to call them and answering the community's questions is both
    // the only useful thing they can do and the whole of what an admin will read
    assertEquals(303, done.status());
    assertEquals("/welcome", done.location());
    assertNotNull(client.cookie("hearth_session"));

    UserRecord user = userFor("newcomer@example.com");
    assertNotNull(user);
    assertFalse("but they are not approved", user.isApproved());
    assertTrue("the address was still proven", user.isVerified());
  }

  @Test
  public void anUnapprovedPersonCanReachTheirOwnPageAndNothingElse() throws Exception {
    Browser client = browser();
    registerAs(client, "newcomer@example.com");

    assertEquals("their own page is the point", 200, client.get("/self").status());
    assertTrue(client.get("/self").contains("display_name"));

    Browser.Page home = client.get("/");
    assertEquals(200, home.status());
    assertTrue("the community itself is what approval gates", home.contains("Waiting for approval"));
    assertFalse(home.contains("Hello, world."));
  }

  @Test
  public void aDisabledAccountGetsNoSessionAtAll() throws Exception {
    // "not yet" and "no" are different answers; only the second refuses a session
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    registerAs(browser(), "victim@example.com");
    long id = userFor("victim@example.com").id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("user", Long.toString(id), "action", "disable"));

    // sign-in mails nothing to a disabled account (that would confirm the account exists), so the
    // refusal is reached through registration, which mails a code to anybody who asks
    Browser client = browser();
    Browser.Page done = registerAs(client, "victim@example.com");
    assertEquals(200, done.status());
    assertTrue(done.body(), done.contains("turned off"));
    assertNull(client.cookie("hearth_session"));
  }

  // ---- the escape hatch ----------------------------------------------------------------------

  @Test
  public void anAddressInTheConfigIsApprovedAndAdminOnSight() throws Exception {
    Browser client = browser();
    Browser.Page done = registerAs(client, "boss@example.com");

    assertEquals("the escape hatch has to work on a completely empty database", 303, done.status());
    assertNotNull(client.cookie("hearth_session"));
    UserRecord boss = userFor("boss@example.com");
    assertTrue(boss.isApproved());
    assertTrue(server.auth.forDomain("example.org").access.isAdmin(boss));
  }

  @Test
  public void theConfigGrantIsWrittenBackToTheRolesTable() throws Exception {
    registerAs(browser(), "boss@example.com");
    UserRecord boss = userFor("boss@example.com");
    assertTrue("so an admin listing shows them",
        server.auth.forDomain("example.org").roles.has(boss.id(), Roles.ADMIN));
  }

  @Test
  public void theConfigListIsCaseInsensitive() throws Exception {
    Browser client = browser();
    Browser.Page done = registerAs(client, "BOSS@Example.COM");
    assertEquals(303, done.status());
    assertNotNull(client.cookie("hearth_session"));
  }

  // ---- the admin page ------------------------------------------------------------------------

  @Test
  public void theAdminPageIsInvisibleToEverybodyElse() throws Exception {
    // signed out
    assertEquals(404, browser().get("/admin").status());

    // signed in, but not an admin
    registerAs(browser(), "newcomer@example.com");
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    UserRecord newcomer = userFor("newcomer@example.com");
    server.auth.forDomain("example.org").users.approve(newcomer.id(), null);

    Browser ordinary = browser();
    ordinary.get("/login");
    ordinary.submit(Map.of("email", "newcomer@example.com"));
    ordinary.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));
    assertNotNull("the newcomer is approved now", ordinary.cookie("hearth_session"));
    assertTrue("and can see the community", ordinary.get("/").contains("Hello, world."));
    assertEquals("but still not an admin", 404, ordinary.get("/admin").status());
  }

  @Test
  public void anAdminSeesWhoIsWaitingAndCanApproveThem() throws Exception {
    registerAs(browser(), "newcomer@example.com");
    Browser admin = browser();
    registerAs(admin, "boss@example.com");

    Browser.Page page = admin.get("/admin/people");
    assertEquals(200, page.status());
    assertTrue(page.contains("newcomer@example.com"));
    assertTrue("somebody waiting is marked as such", page.contains(">waiting<"));

    UserRecord newcomer = userFor("newcomer@example.com");
    Browser.Page after = admin.submitTo("/admin/people",
        Map.of("user", Long.toString(newcomer.id()), "action", "approve"));
    assertEquals(303, after.status());
    assertEquals("nothing is announced in a URL", "/admin/people", after.location());
    assertTrue(admin.follow(after).contains("is approved."));
    assertTrue(userFor("newcomer@example.com").isApproved());
  }

  @Test
  public void anApprovedPersonCanThenSignIn() throws Exception {
    registerAs(browser(), "newcomer@example.com");
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("user", Long.toString(userFor("newcomer@example.com").id()),
        "action", "approve"));

    Browser client = browser();
    client.get("/login");
    client.submit(Map.of("email", "newcomer@example.com"));
    Browser.Page done = client.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));
    assertEquals(303, done.status());
    assertNotNull(client.cookie("hearth_session"));
    assertTrue(client.get("/").contains("You are signed in as newcomer@example.com"));
  }

  @Test
  public void rejectingSomebodyRemovesThemEntirely() throws Exception {
    registerAs(browser(), "newcomer@example.com");
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    long id = userFor("newcomer@example.com").id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("user", Long.toString(id), "action", "approve"));

    Browser client = browser();
    client.get("/login");
    client.submit(Map.of("email", "newcomer@example.com"));
    client.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));
    assertTrue(client.get("/").contains("You are signed in"));

    // "unapprove" used to mean "back to waiting", which left a stranger's account and profile in
    // the database with nobody ever going to look at them again. Rejecting means no.
    admin.submitTo("/admin/people", Map.of("user", Long.toString(id), "action", "reject"));
    assertFalse("approval and access have to end together",
        client.get("/").contains("You are signed in"));
    assertNull("and the account is gone, not merely unapproved", userFor("newcomer@example.com"));
    assertFalse("along with everything they wrote",
        admin.get("/admin/people/list").contains("newcomer@example.com"));
  }

  @Test
  public void anAdminCanMakeSomebodyElseAnAdmin() throws Exception {
    registerAs(browser(), "deputy@example.com");
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    long id = userFor("deputy@example.com").id();

    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("user", Long.toString(id), "action", "grant_admin"));
    UserRecord deputy = userFor("deputy@example.com");
    assertTrue(server.auth.forDomain("example.org").access.isAdmin(deputy));
    assertTrue("granting admin approves them too", deputy.isApproved());
  }

  @Test
  public void aConfigAdminCannotBeDemotedFromInsideTheRunningSystem() throws Exception {
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    long id = userFor("boss@example.com").id();

    Browser.Page result = admin.submitTo("/admin/people",
        Map.of("user", Long.toString(id), "action", "revoke_admin"));
    assertEquals("a refusal still redirects; the reason rides on the session", 303, result.status());
    Browser.Page landed = admin.follow(result);
    assertTrue(landed.body(), landed.contains("admin_emails"));
    assertTrue(server.auth.forDomain("example.org").access.isAdmin(userFor("boss@example.com")));
  }

  @Test
  public void turningAnAccountOffSignsThemOut() throws Exception {
    Browser client = browser();
    registerAs(client, "boss@example.com");
    assertTrue(client.get("/").contains("You are signed in"));

    Browser other = browser();
    registerAs(other, "victim@example.com");
    long id = userFor("victim@example.com").id();
    client.get("/admin/people");
    client.submitTo("/admin/people", Map.of("user", Long.toString(id), "action", "approve"));
    client.get("/admin/people");
    client.submitTo("/admin/people", Map.of("user", Long.toString(id), "action", "disable"));
    assertTrue(userFor("victim@example.com").disabled());
  }

  @Test
  public void theAdminPageRefusesAPostWithoutACsrfToken() throws Exception {
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    admin.get("/admin/people");
    Browser.Page result = admin.submitRaw("/admin/people", Map.of("user", "1", "action", "approve"));
    assertEquals(303, result.status());
    assertTrue(admin.follow(result).contains("That form expired"));
  }

  @Test
  public void theAdminPageShowsTheSignupSignals() throws Exception {
    Browser newcomer = browser().withSignals("m:44|k:19|t:0|p:3|s:1|f:2|e:6100");
    newcomer.get("/register");
    newcomer.submit(Map.of("email", "newcomer@example.com"));
    newcomer.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));

    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    Browser.Page page = admin.get("/admin/people");
    // the total, and the breakdown in the tooltip, so a wave of identical scores is visible
    assertTrue(page.body(), page.contains(">69<"));
    assertTrue(page.contains("m:44|k:19|t:0|p:3|s:1|f:2|e:6100"));
  }

  @Test
  public void theConfigAdminsAreNamedOnThePage() throws Exception {
    Browser admin = browser();
    registerAs(admin, "boss@example.com");
    assertTrue(admin.get("/admin/people").contains("boss@example.com"));
  }
}
