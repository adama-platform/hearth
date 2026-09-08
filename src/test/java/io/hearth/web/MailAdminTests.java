package io.hearth.web;

import io.hearth.auth.Permission;
import io.hearth.smtp.Mailboxes;
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
import static org.junit.Assert.assertTrue;

/**
 * The screens where somebody says where their post goes, driven over real HTTP.
 *
 * <b>Its own permission, and that is the first thing checked here.</b> These screens can silently
 * redirect a person's mail and show who has been writing to whom, which is not the same decision as
 * being trusted with the community's colours -- so an administrator of everything else, holding no
 * `mail_route`, must find nothing at all rather than a door that says no.
 */
public class MailAdminTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"]}");
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

  private Mailboxes boxes() {
    return server.auth.forDomain("example.org").mailboxes;
  }

  private static final String[] PAGES = {
      "/admin/mail", "/admin/mail/addresses", "/admin/mail/log", "/admin/mail/setup"};

  // ---- the screens -------------------------------------------------------------------------------

  @Test
  public void everyMailScreenIsItsOwnUrl() throws Exception {
    for (String path : PAGES) {
      assertEquals(path + " should load", 200, admin.get(path).status());
    }
    assertEquals(200, admin.get("/admin/mail/new").status());
    assertEquals(200, admin.get("/admin/mail/addresses/new").status());
  }

  @Test
  public void theLogListingIsAPanelOnItsOwnPath() throws Exception {
    Browser.Page panel = admin.get("/admin/mail/log/list");
    assertEquals(200, panel.status());
    assertFalse("a panel carries no shell", panel.contains("<!doctype html>"));
    assertFalse(panel.contains("Sign out"));
  }

  /**
   * A section somebody may not open answers 404 and is absent from the sidebar.
   *
   * A 403 confirms what is behind the door, and a sidebar of doors that say no advertises what
   * somebody is not trusted with.
   */
  @Test
  public void anAdministratorWithoutMailRouteFindsNothingThere() throws Exception {
    server.auth.forDomain("example.org").roleDefs.save("editor", "Editor", "",
        java.util.Set.of(Permission.content_write), "", null);
    Browser editor = signIn("editor@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail("editor@example.com").id();
    server.auth.forDomain("example.org").users.approve(id, null);
    server.auth.forDomain("example.org").roles.grant(id, "editor", null);

    Browser.Page content = editor.get("/admin/content");
    assertEquals("they can do their own job", 200, content.status());
    assertFalse("and the door is not even drawn", content.contains("/admin/mail"));
    for (String path : PAGES) {
      assertEquals(path + " must look like it does not exist", 404, editor.get(path).status());
    }
  }

  @Test
  public void anAnonymousRequestGetsTheSame() throws Exception {
    Browser nobody = new Browser(server.port, "example.org");
    for (String path : PAGES) {
      assertEquals(404, nobody.get(path).status());
    }
  }

  // ---- rules ------------------------------------------------------------------------------------

  @Test
  public void aRuleIsWrittenAndThenDecidesAMessage() throws Exception {
    admin.submitToAndFollow("/admin/mail", Map.of("action", "save", "name", "hers", "to", "her", "from", "", "subject", "",
        "what", "forward", "forwardTo", "her@elsewhere.example", "position", "10",
        "enabled", "on"));

    Mailboxes.Rule rule = boxes().decide("example.org", "her", "anyone@example.com", "hello");
    assertNotNull("the rule the form wrote is the rule a message meets", rule);
    assertEquals("her@elsewhere.example", rule.forwardTo());
    assertEquals(Mailboxes.Action.forward, rule.action());
    assertTrue(admin.get("/admin/mail").contains("her@elsewhere.example"));
  }

  /**
   * Forwarding to an address at this same domain is a loop, and is refused rather than saved.
   *
   * It would be accepted here, matched by the same rule set and forwarded again, until the hop
   * counter stopped it thirty messages later -- by which point the far end has seen thirty copies
   * and formed a view about this machine.
   */
  @Test
  public void aRuleThatForwardsBackIntoThisDomainIsRefused() throws Exception {
    Browser.Page landed = admin.submitToAndFollow("/admin/mail", Map.of(
        "action", "save", "name", "loop", "to", "jeff", "from", "", "subject", "",
        "what", "forward", "forwardTo", "someone@example.org", "position", "10", "enabled", "on"));
    assertTrue("a refusal, said out loud", landed.contains("class=\"problem\""));
    assertTrue(landed.body(), landed.contains("which is a loop"));
    assertEquals(0, boxes().countRules("example.org"));
  }

  @Test
  public void aRuleThatForwardsNowhereIsRefused() throws Exception {
    Browser.Page landed = admin.submitToAndFollow("/admin/mail", Map.of(
        "action", "save", "name", "", "to", "jeff", "from", "", "subject", "",
        "what", "forward", "forwardTo", "", "position", "10", "enabled", "on"));
    assertTrue(landed.contains("class=\"problem\""));
    assertEquals(0, boxes().countRules("example.org"));
  }

  @Test
  public void aRuleCanBeDeleted() throws Exception {
    long id = boxes().saveRule(0, "example.org", 10, "bin", Mailboxes.EVERYONE, "", "",
        Mailboxes.Action.drop, "", true, null);
    admin.submitToAndFollow("/admin/mail", Map.of("action", "delete", "id", String.valueOf(id)));
    assertEquals(0, boxes().countRules("example.org"));
  }

  /**
   * The whole path for a rule that keeps mail, from the form an administrator fills in.
   *
   * This is the one worth driving end to end: the action was added to the enum, the validator and
   * the handler before it existed in the template, which made the entire mailbox feature
   * unreachable from the screen that is meant to switch it on.
   */
  @Test
  public void aRuleCanBeSetToKeepMailInSomebodysMailbox() throws Exception {
    long owner = server.auth.forDomain("example.org").users
        .create("jeff@example.com", null, true, null).id();
    server.auth.forDomain("example.org").users.approve(owner, null);
    boxes().saveBox(0, "example.org", "jeff", "mine", owner, true, null);

    assertTrue("the option is on the form", admin.get("/admin/mail/new").contains("keep it here"));
    admin.submitToAndFollow("/admin/mail", Map.of("action", "save", "name", "mine", "to", "jeff",
        "from", "", "subject", "", "what", "deliver", "forwardTo", "", "position", "10",
        "enabled", "on"));

    Mailboxes.Rule rule = boxes().decide("example.org", "jeff", "anyone@example.com", "");
    assertNotNull(rule);
    assertEquals(Mailboxes.Action.deliver, rule.action());
    assertTrue("and the listing says where it goes",
        admin.get("/admin/mail").contains("kept here"));
  }

  /**
   * Keeping mail for an address nobody owns is refused at the form.
   *
   * The message would land in a table no screen lists, which is mail lost rather than delivered --
   * and the mistake is far cheaper to catch here than the first time real mail arrives.
   */
  @Test
  public void aKeepRuleIsRefusedWhenNobodyOwnsTheAddress() throws Exception {
    boxes().saveBox(0, "example.org", "orphan", "", null, true, null);
    Browser.Page landed = admin.submitToAndFollow("/admin/mail", Map.of("action", "save",
        "name", "", "to", "orphan", "from", "", "subject", "", "what", "deliver",
        "forwardTo", "", "position", "10", "enabled", "on"));
    assertTrue(landed.body(), landed.contains("Nobody owns"));
    assertEquals(0, boxes().countRules("example.org"));
  }

  @Test
  public void aKeepRuleForAnAddressThatDoesNotExistIsRefused() throws Exception {
    Browser.Page landed = admin.submitToAndFollow("/admin/mail", Map.of("action", "save",
        "name", "", "to", "nosuch", "from", "", "subject", "", "what", "deliver",
        "forwardTo", "", "position", "10", "enabled", "on"));
    assertTrue(landed.body(), landed.contains("not an address here yet"));
    assertEquals(0, boxes().countRules("example.org"));
  }

  @Test
  public void aCatchAllCannotBeKept() throws Exception {
    // there is no one address to keep it in, so the rule would have nowhere to put anything
    Browser.Page landed = admin.submitToAndFollow("/admin/mail", Map.of("action", "save",
        "name", "", "to", Mailboxes.EVERYONE, "from", "", "subject", "", "what", "deliver",
        "forwardTo", "", "position", "10", "enabled", "on"));
    assertTrue(landed.body(), landed.contains("catch-all cannot be kept"));
    assertEquals(0, boxes().countRules("example.org"));
  }

  /**
   * One person, several addresses, and the screen says whose each one is.
   *
   * That is the shape the whole mailbox feature rests on: a reply goes out from the address a
   * message arrived at, so having more than one of them is the point rather than an edge case.
   */
  @Test
  public void onePersonCanBeGivenSeveralAddresses() throws Exception {
    long owner = server.auth.forDomain("example.org").users
        .create("jeff@example.com", null, true, null).id();
    server.auth.forDomain("example.org").users.approve(owner, null);
    admin.submitToAndFollow("/admin/mail/addresses", Map.of("action", "save",
        "localPart", "jeff", "label", "mine", "owner", String.valueOf(owner), "enabled", "on"));
    admin.submitToAndFollow("/admin/mail/addresses", Map.of("action", "save",
        "localPart", "receipts", "label", "statements", "owner", String.valueOf(owner),
        "enabled", "on"));

    assertEquals(2, boxes().ownedBy(owner).size());
    Browser.Page page = admin.get("/admin/mail/addresses");
    assertTrue(page.body(), page.contains("jeff@example.org"));
    assertTrue(page.body(), page.contains("receipts@example.org"));
    assertTrue("and it says whose they are", page.contains("jeff@example.com"));
  }

  // ---- addresses ---------------------------------------------------------------------------------

  @Test
  public void namingAnAddressMakesItAPlace() throws Exception {
    admin.submitToAndFollow("/admin/mail/addresses", Map.of("action", "save",
        "localPart", "receipts", "label", "statements", "owner", "", "enabled", "on"));
    assertNotNull(boxes().box("example.org", "receipts"));
    assertTrue(admin.get("/admin/mail/addresses").contains("receipts@example.org"));
  }

  /**
   * An address with no rule is accepted and discarded, and the screen says so.
   *
   * Somebody names an address, sees it in a list, and reasonably assumes mail to it goes somewhere.
   */
  @Test
  public void theAddressListSaysWhichOnesGoNowhere() throws Exception {
    boxes().saveBox(0, "example.org", "receipts", "statements", null, true, null);
    Browser.Page page = admin.get("/admin/mail/addresses");
    assertTrue(page.body(), page.contains("nothing matches it"));

    boxes().saveRule(0, "example.org", 10, "all", Mailboxes.EVERYONE, "", "",
        Mailboxes.Action.forward, "me@elsewhere.example", true, null);
    assertFalse(admin.get("/admin/mail/addresses").contains("nothing matches it"));
  }

  @Test
  public void twoAddressesWithTheSameNameAreRefused() throws Exception {
    boxes().saveBox(0, "example.org", "jeff", "", null, true, null);
    Browser.Page landed = admin.submitToAndFollow("/admin/mail/addresses", Map.of(
        "action", "save", "localPart", "jeff", "label", "", "owner", "", "enabled", "on"));
    assertTrue(landed.contains("class=\"problem\""));
    assertEquals(1, boxes().boxes("example.org").size());
  }

  @Test
  public void aNameThatWouldNeedEscapingInAHeaderIsRefused() throws Exception {
    Browser.Page landed = admin.submitToAndFollow("/admin/mail/addresses", Map.of(
        "action", "save", "localPart", "has space", "label", "", "owner", "", "enabled", "on"));
    assertTrue(landed.contains("class=\"problem\""));
    assertEquals(0, boxes().boxes("example.org").size());
  }

  // ---- the setup screen ---------------------------------------------------------------------------

  /**
   * The guidance is generated from what is running, so it cannot describe a selector nobody uses.
   *
   * With forwarding off there is no key, and the screen says that rather than printing a record for
   * a key that does not exist.
   */
  @Test
  public void theSetupScreenSaysWhatIsActuallySwitchedOn() throws Exception {
    Browser.Page page = admin.get("/admin/mail/setup");
    assertTrue(page.body(), page.contains("example.org"));
    assertTrue("the MX record names this machine", page.contains("10 mail.example.org"));
    assertTrue("SPF, because the return path is rewritten to this domain",
        page.contains("v=spf1"));
    assertTrue(page.contains("v=DMARC1"));
    assertTrue("the one Workspace setting that matters", page.contains("Inbound gateway"));
    assertTrue("and the two that look right and are traps",
        page.contains("Email allowlist"));
    assertTrue("reverse DNS, which this server cannot see from the inside",
        page.contains("PTR"));
    assertTrue("with the command that answers it", page.contains("dig +short"));
    assertTrue("no key while forwarding is off, said plainly rather than faked",
        page.contains("no signing key"));
  }

  @Test
  public void theSetupScreenReportsForwardingAsOffUntilItIsOn() throws Exception {
    Browser.Page page = admin.get("/admin/mail/setup");
    assertTrue(page.body(), page.contains("Forwarding"));
    assertTrue("inbound mail is off in this test's config, and the screen says so",
        page.contains("nothing arrives here at all"));
  }

  // ---- the log -------------------------------------------------------------------------------------

  @Test
  public void oneMessageCanBeOpenedAndInspected() throws Exception {
    long id = server.auth.forDomain("example.org").mailLog.record(new io.hearth.smtp.MailLog.Draft()
        .domain("example.org")
        .envelope("someone@gmail.com", "jeff@example.org")
        .message("Someone <someone@gmail.com>", "about Thursday", "<abc@gmail.com>", 1024)
        .outcome(io.hearth.smtp.MailLog.Outcome.forwarded, "250 OK")
        .preview("Can you do the ninth?"));

    assertTrue(admin.get("/admin/mail/log").contains("about Thursday"));
    Browser.Page one = admin.get("/admin/mail/log/review/" + id);
    assertEquals(200, one.status());
    assertTrue(one.body(), one.contains("someone@gmail.com"));
    assertTrue("the preview, because a rule that matched the wrong thing is only obvious next to it",
        one.contains("Can you do the ninth?"));
    assertTrue(one.contains("250 OK"));
  }

  /**
   * One database can serve several domains, and the log is keyed by the domain a message arrived
   * for. Without the check, an administrator of one could read another's mail log by guessing a row
   * number.
   */
  @Test
  public void aMessageForAnotherDomainIsNotReadableFromThisOne() throws Exception {
    long id = server.auth.forDomain("example.org").mailLog.record(new io.hearth.smtp.MailLog.Draft()
        .domain("somebody-else.example")
        .envelope("someone@gmail.com", "them@somebody-else.example")
        .message("", "not yours", "", 10)
        .outcome(io.hearth.smtp.MailLog.Outcome.forwarded, ""));
    assertEquals(404, admin.get("/admin/mail/log/review/" + id).status());
  }

  @Test
  public void theTroubleFilterIsItsOwnQuery() throws Exception {
    server.auth.forDomain("example.org").mailLog.record(new io.hearth.smtp.MailLog.Draft()
        .domain("example.org").envelope("a@b.example", "jeff@example.org")
        .message("", "fine", "", 10)
        .outcome(io.hearth.smtp.MailLog.Outcome.forwarded, ""));
    server.auth.forDomain("example.org").mailLog.record(new io.hearth.smtp.MailLog.Draft()
        .domain("example.org").envelope("a@b.example", "jeff@example.org")
        .message("", "broken", "", 10)
        .outcome(io.hearth.smtp.MailLog.Outcome.failed, "550 no"));

    assertTrue(admin.get("/admin/mail/log/list").contains("fine"));
    Browser.Page troubles = admin.get("/admin/mail/log/list?trouble=1");
    assertTrue(troubles.body(), troubles.contains("broken"));
    assertFalse("filters go in the query and identity in the path", troubles.contains(">fine<"));
  }
}
