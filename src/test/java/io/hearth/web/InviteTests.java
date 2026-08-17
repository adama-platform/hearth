package io.hearth.web;

import io.hearth.people.InvitePixel;
import io.hearth.people.Invites;
import io.hearth.testkit.Browser;
import io.hearth.testkit.CapturingMailer;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
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
 * Invitations, and whether they worked.
 *
 * The number worth having is conversion: not how many were sent, but how many became somebody who
 * signed in. So most of this is the funnel -- and the open tracking, which is the part most likely
 * to be believed more than it deserves.
 */
public class InviteTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = new Browser(server.port, "example.org");
    admin.get("/register");
    admin.submit(Map.of("email", "boss@example.com"));
    admin.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    // an invitation says who is asking, and a name is what makes one sendable
    admin.get("/self");
    admin.submitTo("/self", Map.of("action", "profile", "display_name", "The Boss",
        "headline", "", "about", "", "location", "", "links", ""));
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

  private Invites invites() {
    return server.auth.forDomain("example.org").invites;
  }

  private CapturingMailer mail() {
    return (CapturingMailer) server.mailer;
  }

  /**
   * The form: an address, and pressing it sends.
   *
   * There is no "send it now" box any more -- the button says what it does, and an invitation
   * nobody received was a state with no purpose. Writing one without sending is still a thing the
   * store can do, which is what {@link #writeWithoutSending} exercises.
   */
  private Browser.Page invite(String email, boolean send) throws Exception {
    if (!send) {
      writeWithoutSending(email);
      return admin.get("/admin/invites");
    }
    var form = new java.util.LinkedHashMap<String, String>();
    form.put("action", "create");
    form.put("email", email);
    // no note: an invitation is one address in one box, and the message is the community's
    return admin.submitToAndFollow("/admin/invites", form);
  }

  /** straight to the store, because the screen no longer offers it */
  private void writeWithoutSending(String email) throws Exception {
    io.hearth.auth.Accounts accounts = server.auth.forDomain("example.org");
    accounts.invites.create(email, "", accounts.users.byEmail("boss@example.com").id(),
        "boss@example.com", "The Boss");
  }

  /** somebody registers from scratch, the way an invited person would */
  private Browser signUp(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }

  // ---- writing and sending -----------------------------------------------------------------------

  @Test
  public void writingOneAndSendingItAreSeparate() throws Exception {
    // they fail for different reasons: a mistyped address is a bad invitation, a wrong SES key is a
    // bad afternoon, and one error message for two problems helps with neither
    invite("newcomer@example.com", false);
    Invites.Invite written = invites().all(10).get(0);
    assertFalse("not sent yet", written.sent());
    assertEquals("not sent", written.stage());
    assertEquals("and nothing went out", 0, mail().forFlow("invite").size());

    admin.submitToAndFollow("/admin/invites",
        Map.of("action", "send", "id", Long.toString(written.id())));
    assertTrue(invites().byId(written.id()).sent());
    assertEquals(1, mail().forFlow("invite").size());
  }

  @Test
  public void followingTheLinkIsRecordedAsAClick() throws Exception {
    // the honest half of the tracking: a mail client fetches the pixel by itself, and nothing
    // follows a link on somebody's behalf
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    assertFalse("nobody has been anywhere yet", invites().byId(invite.id()).clicked());
    assertEquals("sent", invites().byId(invite.id()).stage());

    Browser invited = new Browser(server.port, "example.org");
    invited.get("/register?invite=" + invite.token());

    Invites.Invite after = invites().byId(invite.id());
    assertTrue(after.clicked());
    assertEquals(1, after.clicks());
    assertEquals("clicked", after.stage());

    // coming back counts again, the same way an open does, so somebody hesitating is visible
    invited.get("/register?invite=" + invite.token());
    assertEquals(2, invites().byId(invite.id()).clicks());
    assertNotNull(invites().byId(invite.id()).clickedAt());
    assertEquals("but the first time stays the first time",
        after.clickedAt(), invites().byId(invite.id()).clickedAt());
  }

  @Test
  public void aClickOutranksAnOpenAndAJoinOutranksBoth() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    invites().markOpened(invite.token());
    assertEquals("opened", invites().byId(invite.id()).stage());

    Browser invited = new Browser(server.port, "example.org");
    invited.get("/register?invite=" + invite.token());
    assertEquals("clicked", invites().byId(invite.id()).stage());

    Invites.Funnel funnel = invites().funnel();
    assertEquals(1, funnel.opened());
    assertEquals(1, funnel.clicked());
    assertEquals(100, funnel.clickRate());
    assertEquals(0, funnel.converted());

    signUp("newcomer@example.com");
    assertEquals("joined", invites().byId(invite.id()).stage());
    assertEquals(1, invites().funnel().converted());
  }

  @Test
  public void arrivingWithoutAnInvitationRecordsNothing() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    Browser plain = new Browser(server.port, "example.org");
    plain.get("/register");
    plain.get("/register?invite=nonsense");
    assertFalse(invites().byId(invite.id()).clicked());
  }

  @Test
  public void theFunnelScreenShowsTheClicks() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    new Browser(server.port, "example.org").get("/register?invite=" + invite.token());

    Browser.Page page = admin.get("/admin/invites");
    assertTrue(page.contains(">clicked</div>"));
    assertTrue("and the row says where it got to",
        admin.get("/admin/invites/list").contains("clicked"));
  }

  @Test
  public void theMessageCarriesALinkAndAPixel() throws Exception {
    invite("newcomer@example.com", true);
    CapturingMailer.Sent sent = mail().lastInvite();
    assertNotNull(sent);
    assertEquals("newcomer@example.com", sent.email());

    Invites.Invite invite = invites().all(10).get(0);
    assertTrue("the link brings them to register", sent.link().contains("/register"));
    assertTrue("carrying the invitation", sent.link().contains(invite.token()));
    assertNotNull("and a pixel", sent.pixel());
    assertTrue(sent.pixel().contains(InvitePixel.PREFIX));
    assertTrue("which is absolute, because a mail client fetches it",
        sent.pixel().startsWith("https://example.org"));
    assertTrue("and looks like an image", sent.pixel().endsWith(".gif"));
    assertTrue("and the community's own text, filled in with its name",
        mail().inviteHtml().get(0).contains("Example"));
  }

  @Test
  public void anAddressThatAlreadyHasAnAccountIsRefused() throws Exception {
    signUp("member@example.com");
    Browser.Page refused = invite("member@example.com", true);
    assertTrue(refused.contains("already has an account"));
    assertEquals(0, invites().count());
  }

  @Test
  public void aBannedAddressIsRefused() throws Exception {
    admin.submitToAndFollow("/admin/bans", Map.of("action", "ban", "email", "spam@example.com"));
    Browser.Page refused = invite("spam@example.com", true);
    assertTrue(refused.contains("banned"));
    assertEquals(0, invites().count());
  }

  @Test
  public void somethingThatIsNotAnAddressIsRefused() throws Exception {
    Browser.Page refused = invite("not an address", true);
    assertTrue(refused.contains("does not look like an email"));
    assertEquals(0, invites().count());
  }

  // ---- the tracking pixel ------------------------------------------------------------------------

  @Test
  public void fetchingThePixelRecordsAnOpen() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    assertFalse(invite.opened());

    try (Http http = new Http()) {
      Http.Response response = http.get(server.port, "example.org",
          InvitePixel.PREFIX + invite.token() + ".gif");
      assertEquals(200, response.status);
      assertTrue("it has to actually be an image", response.bytes.length > 0);
      assertEquals("GIF", new String(response.bytes, 0, 3, java.nio.charset.StandardCharsets.US_ASCII));
    }

    Invites.Invite after = invites().byId(invite.id());
    assertTrue(after.opened());
    assertEquals(1, after.opens());
    assertEquals("opened", after.stage());
  }

  @Test
  public void openingItAgainCountsAgainButKeepsTheFirstTime() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    try (Http http = new Http()) {
      for (int k = 0; k < 3; k++) {
        http.get(server.port, "example.org", InvitePixel.PREFIX + invite.token() + ".gif");
      }
    }
    Invites.Invite after = invites().byId(invite.id());
    assertEquals(3, after.opens());
    assertNotNull("the first open is when it was read", after.openedAt());
    assertNotNull("the last says whether anybody looked since", after.lastOpenedAt());
  }

  @Test
  public void thePixelIsNotEatenByTheScannerShield() throws Exception {
    // a long random path is exactly what the shield drops, and exactly what a token is
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    try (Http http = new Http()) {
      assertEquals(200, http.get(server.port, "example.org",
          InvitePixel.PREFIX + invite.token() + ".gif").status);
    }
    assertTrue(invites().byId(invite.id()).opened());
  }

  @Test
  public void anUnknownTokenStillGetsAPixelAndChangesNothing() throws Exception {
    // a mail client fetching a stale pixel must not get an error page in place of an image
    try (Http http = new Http()) {
      Http.Response response = http.get(server.port, "example.org",
          InvitePixel.PREFIX + "nothing-here.gif");
      assertEquals(200, response.status);
      assertTrue(response.bytes.length > 0);
    }
    assertEquals(0, invites().count());
  }

  @Test
  public void thePixelIsNotCached() throws Exception {
    // a cached pixel is one open recorded forever, which would make the number meaningless
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    try (Http http = new Http()) {
      Http.Response response = http.get(server.port, "example.org",
          InvitePixel.PREFIX + invite.token() + ".gif");
      String cacheControl = response.header("cache-control");
      assertNotNull(cacheControl);
      assertTrue(cacheControl, cacheControl.contains("no-store"));
    }
  }

  @Test
  public void aTokenIsParsedOutOfThePathWithOrWithoutTheExtension() {
    assertEquals("abc", InvitePixel.tokenOf(InvitePixel.PREFIX + "abc.gif"));
    assertEquals("abc", InvitePixel.tokenOf(InvitePixel.PREFIX + "abc"));
    assertNull(InvitePixel.tokenOf("/somewhere/else"));
    assertFalse(InvitePixel.isPixel(InvitePixel.PREFIX));
  }

  // ---- conversion ---------------------------------------------------------------------------------

  @Test
  public void signingUpConvertsTheInvitation() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite before = invites().all(10).get(0);
    assertFalse(before.converted());

    signUp("newcomer@example.com");

    Invites.Invite after = invites().byId(before.id());
    assertTrue(after.converted());
    assertEquals("joined", after.stage());
    assertNotNull(after.convertedUser());
    assertEquals("and the member points back at the invitation",
        server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id(),
        after.convertedUser().longValue());
  }

  @Test
  public void aMemberCanBeTracedBackToTheInvitationThatBroughtThem() throws Exception {
    invite("newcomer@example.com", true);
    signUp("newcomer@example.com");
    long userId = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();

    Invites.Invite source = invites().forUser(userId);
    assertNotNull(source);
    assertEquals("boss@example.com", source.createdByEmail());
  }

  @Test
  public void thePeopleListingSaysWhoCameInOnAnInvitation() throws Exception {
    invite("newcomer@example.com", true);
    signUp("newcomer@example.com");
    signUp("stranger@example.com");

    Browser.Page page = admin.get("/admin/people");
    assertEquals(200, page.status());
    assertTrue("a funnel you cannot trace back to a person is a number nobody can act on",
        page.contains("newcomer@example.com"));
    assertTrue(page.contains("invited"));
    // the tag belongs to the invited one; the row for the stranger must not carry it
    String[] rows = page.body().split("<tr");
    for (String row : rows) {
      if (row.contains("stranger@example.com")) {
        assertFalse("somebody who walked in is not marked as invited", row.contains(">invited<"));
      }
    }
  }

  @Test
  public void theReviewPageNamesWhoInvitedThem() throws Exception {
    invite("newcomer@example.com", true);
    signUp("newcomer@example.com");
    long userId = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();

    Browser.Page page = admin.get("/admin/people/review/" + userId);
    assertEquals(200, page.status());
    assertTrue("the admin deciding about somebody should know who vouched for them",
        page.contains("invitation from"));
    assertTrue(page.contains("boss@example.com"));
  }

  @Test
  public void somebodyWhoJoinedWithoutAnInvitationHasNone() throws Exception {
    signUp("stranger@example.com");
    long userId = server.auth.forDomain("example.org").users.byEmail("stranger@example.com").id();
    assertNull(invites().forUser(userId));
  }

  @Test
  public void resendingDoesNotInflateTheConversionRate() throws Exception {
    // three invitations to one address, one person: one conversion, not three
    invite("newcomer@example.com", true);
    invite("newcomer@example.com", true);
    invite("newcomer@example.com", true);
    assertEquals(3, invites().count());

    signUp("newcomer@example.com");

    Invites.Funnel funnel = invites().funnel();
    assertEquals(3, funnel.sent());
    assertEquals("one address became one member", 1, funnel.converted());
    assertEquals(33, funnel.conversionRate());
  }

  @Test
  public void aRevokedInvitationIsNotClaimedBySomebodySigningUp() throws Exception {
    invite("newcomer@example.com", true);
    Invites.Invite invite = invites().all(10).get(0);
    admin.submitToAndFollow("/admin/invites",
        Map.of("action", "revoke", "id", Long.toString(invite.id())));

    signUp("newcomer@example.com");
    assertFalse("a revoked invitation did not do the convincing",
        invites().byId(invite.id()).converted());
  }

  @Test
  public void oneThatAlreadyJoinedCannotBeRevoked() throws Exception {
    invite("newcomer@example.com", true);
    signUp("newcomer@example.com");
    long id = invites().all(10).get(0).id();

    Browser.Page refused = admin.submitToAndFollow("/admin/invites",
        Map.of("action", "revoke", "id", Long.toString(id)));
    assertTrue(refused.contains("already became a member"));
    assertTrue(invites().byId(id).converted());
  }

  // ---- the screen ------------------------------------------------------------------------------------

  @Test
  public void theFunnelIsWhatTheScreenLeadsWith() throws Exception {
    invite("a@example.com", true);
    invite("b@example.com", true);
    invite("c@example.com", false);
    Invites.Invite opened = invites().all(10).get(1);
    try (Http http = new Http()) {
      http.get(server.port, "example.org", InvitePixel.PREFIX + opened.token() + ".gif");
    }
    signUp("a@example.com");

    Browser.Page page = admin.get("/admin/invites");
    assertEquals(200, page.status());
    assertTrue(page.contains("sent"));
    assertTrue(page.contains("opened"));
    assertTrue(page.contains("joined"));
    assertTrue("the rate is the number anybody actually wants", page.contains("conversion"));
  }

  @Test
  public void theScreenIsHonestAboutWhatAnOpenMeans() throws Exception {
    // a tracking pixel reported as a read rate is a tracking pixel that will be believed
    Browser.Page page = admin.get("/admin/invites");
    assertTrue(page.contains("no evidence"));
    assertTrue(page.contains("block remote images"));
  }

  @Test
  public void theListingSeparatesNotSentFromSentFromOpened() throws Exception {
    invite("written@example.com", false);
    invite("posted@example.com", true);

    Browser.Page all = admin.get("/admin/invites/list");
    assertTrue(all.contains("written@example.com"));
    assertTrue(all.contains("posted@example.com"));
    assertTrue("an unsent one says so", all.contains("not sent"));
    assertTrue("and a sent one does not claim to be unread",
        all.contains("no evidence of an open"));

    Browser.Page filtered = admin.get("/admin/invites/list?stage=not+sent");
    assertTrue(filtered.contains("written@example.com"));
    assertFalse(filtered.contains("posted@example.com"));
  }

  @Test
  public void aConvertedInvitationLinksToTheMember() throws Exception {
    invite("newcomer@example.com", true);
    signUp("newcomer@example.com");
    long userId = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();

    Browser.Page listing = admin.get("/admin/invites/list");
    assertTrue(listing.contains("joined"));
    assertTrue("and straight to them", listing.contains("/admin/people/review/" + userId));
  }

  @Test
  public void invitesAreAdminsOnly() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    assertEquals(404, stranger.get("/admin/invites").status());
    assertEquals(404, stranger.get("/admin/invites/list").status());
  }
}
