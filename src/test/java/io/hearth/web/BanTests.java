package io.hearth.web;

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
 * The ban list.
 *
 * A ban exists to make a repeat visitor cheap, not to insult them: the check happens before a code
 * is minted, before anything is mailed, and before a row is written. The second thing it has to be
 * is invisible -- a banned address that gets a different answer is an oracle for who has been
 * banned, and for whether an address ever had an account here at all.
 */
public class BanTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = new Browser(server.port, "example.org");
    register(admin, "boss@example.com");
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

  private void register(Browser browser, String email) throws Exception {
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
  }

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  @Test
  public void banningAnAddressCostsAStrangerNothingButALookup() throws Exception {
    admin.submitToAndFollow("/admin/bans", Map.of("action", "ban", "email", "spam@example.com",
        "reason", "signed up eleven times"));
    assertTrue(accounts().bans.isBanned("spam@example.com"));

    server.mail().clear();
    Browser bot = new Browser(server.port, "example.org");
    bot.get("/register");
    Browser.Page page = bot.submit(Map.of("email", "spam@example.com"));

    assertEquals("no mail was sent", 0, server.mail().count());
    assertNull("and no code exists to redeem", server.mail().lastCodeFor("spam@example.com"));
    assertTrue("but the page is the one everybody else sees", page.contains("Check your email"));
  }

  @Test
  public void aBanIsInvisibleToWhoeverIsBanned() throws Exception {
    // the two pages have to be byte-identical apart from what changes on every load anyway, or the
    // difference is an answer to a question nobody outside should be able to ask
    admin.submitToAndFollow("/admin/bans", Map.of("action", "ban", "email", "quiet@example.com"));

    Browser banned = new Browser(server.port, "example.org");
    banned.get("/register");
    String bannedPage = banned.submit(Map.of("email", "quiet@example.com")).body();

    Browser fresh = new Browser(server.port, "example.org");
    fresh.get("/register");
    String freshPage = fresh.submit(Map.of("email", "fresh@example.com")).body();

    assertFalse("the page must not say so in words", bannedPage.contains("ban"));
    // normalize away the address itself and the per-load opaque names, and what is left has to be
    // the same page -- not merely a page that avoids saying the word
    String a = normalize(freshPage, "fresh@example.com");
    String b = normalize(bannedPage, "quiet@example.com");
    if (!a.equals(b)) {
      int k = 0;
      while (k < a.length() && k < b.length() && a.charAt(k) == b.charAt(k)) {
        k++;
      }
      throw new AssertionError("pages diverge at " + k + ":\n fresh: "
          + a.substring(Math.max(0, k - 60), Math.min(a.length(), k + 60)) + "\nbanned: "
          + b.substring(Math.max(0, k - 60), Math.min(b.length(), k + 60)));
    }
  }

  /**
   * Strip what legitimately differs between two loads of the same page.
   *
   * The address, and every opaque token: the form handle, the CSP nonce, and the per-submission
   * field names the mint issues, which are different on every load by design. What survives is the
   * shape of the page, which is the thing a ban must not change.
   */
  private static String normalize(String page, String email) {
    return page.replace(email, "SOMEBODY").replaceAll("[a-zA-Z0-9_-]{10,}", "OPAQUE");
  }

  @Test
  public void banningSomebodyWhoIsAlreadyHereRemovesTheirAccountToo() throws Exception {
    Browser member = new Browser(server.port, "example.org");
    register(member, "member@example.com");
    assertNotNull(accounts().users.byEmail("member@example.com"));

    Browser.Page done = admin.submitToAndFollow("/admin/bans",
        Map.of("action", "ban", "email", "member@example.com", "reason", "enough"));
    assertTrue(done.contains("the account was removed"));
    assertNull(accounts().users.byEmail("member@example.com"));
    assertFalse("and their session went with it", member.get("/self").contains("member@example.com"));
  }

  @Test
  public void rejectingAndBanningIsOneAction() throws Exception {
    Browser newcomer = new Browser(server.port, "example.org");
    register(newcomer, "newcomer@example.com");
    long id = accounts().users.byEmail("newcomer@example.com").id();

    Browser.Page done = admin.submitToAndFollow("/admin/people",
        Map.of("user", Long.toString(id), "action", "reject_and_ban"));
    assertTrue(done.contains("rejected, removed and banned"));
    assertNull(accounts().users.byEmail("newcomer@example.com"));
    assertTrue(accounts().bans.isBanned("newcomer@example.com"));
  }

  @Test
  public void anAdminCannotBeBanned() throws Exception {
    // both spellings of admin: the config list, and the role granted from inside
    Browser.Page byConfig = admin.submitToAndFollow("/admin/bans",
        Map.of("action", "ban", "email", "boss@example.com"));
    assertTrue(byConfig.contains("admin in the config"));
    assertFalse(accounts().bans.isBanned("boss@example.com"));

    Browser deputy = new Browser(server.port, "example.org");
    register(deputy, "deputy@example.com");
    long id = accounts().users.byEmail("deputy@example.com").id();
    admin.submitToAndFollow("/admin/people", Map.of("user", Long.toString(id), "action", "grant_admin"));

    Browser.Page byRole = admin.submitToAndFollow("/admin/bans",
        Map.of("action", "ban", "email", "deputy@example.com"));
    assertTrue(byRole.contains("belongs to an admin"));
    assertFalse(accounts().bans.isBanned("deputy@example.com"));
  }

  @Test
  public void anAdminCannotBeRejectedEither() throws Exception {
    Browser deputy = new Browser(server.port, "example.org");
    register(deputy, "deputy@example.com");
    long id = accounts().users.byEmail("deputy@example.com").id();
    admin.submitToAndFollow("/admin/people", Map.of("user", Long.toString(id), "action", "grant_admin"));

    Browser.Page refused = admin.submitToAndFollow("/admin/people",
        Map.of("user", Long.toString(id), "action", "reject"));
    assertTrue(refused.contains("Admins cannot be rejected"));
    assertNotNull(accounts().users.byEmail("deputy@example.com"));
  }

  @Test
  public void liftingABanLetsThemBackIn() throws Exception {
    admin.submitToAndFollow("/admin/bans", Map.of("action", "ban", "email", "forgiven@example.com"));
    String listing = admin.get("/admin/bans/list").body();
    String id = listing.replaceAll("(?s).*name=\"id\" value=\"(\\d+)\".*", "$1");

    admin.submitToAndFollow("/admin/bans", Map.of("action", "lift", "id", id));
    assertFalse(accounts().bans.isBanned("forgiven@example.com"));

    server.mail().clear();
    Browser back = new Browser(server.port, "example.org");
    back.get("/register");
    back.submit(Map.of("email", "forgiven@example.com"));
    assertNotNull("the mail flows again", server.mail().lastCodeFor("forgiven@example.com"));
  }

  @Test
  public void theBanCacheFollowsTheEventBus() throws Exception {
    // the list is consulted on the cheapest path in the system, so it is cached; the event bus is
    // what keeps that cache from being a stale answer to a security question
    assertFalse(accounts().bans.isBanned("later@example.com"));
    accounts().bans.ban("later@example.com", "written straight to the store", null);
    assertTrue("a write anywhere invalidates the cache everywhere",
        accounts().bans.isBanned("later@example.com"));
  }

  @Test
  public void aBanNeedsSomethingThatLooksLikeAnAddress() throws Exception {
    Browser.Page bad = admin.submitToAndFollow("/admin/bans",
        Map.of("action", "ban", "email", "not an address"));
    assertTrue(bad.contains("does not look like an email"));
    assertEquals(0, accounts().bans.count());
  }

  @Test
  public void theBanListIsAdminsOnly() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    assertEquals(404, stranger.get("/admin/bans").status());
    assertEquals(404, stranger.get("/admin/bans/list").status());
  }
}
