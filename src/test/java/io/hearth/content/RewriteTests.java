package io.hearth.content;

import io.hearth.auth.Accounts;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Addresses that answer with another address, and the proposals that make them.
 *
 * <b>The ordering is the property most of this is about.</b> A rewrite is consulted after a real
 * page and after a directory listing, which is what lets somebody put a page back at an old address
 * and have it simply work. Getting that backwards would mean a redirect quietly shadowing a page
 * somebody wrote, which looks exactly like the page failing to save.
 */
public class RewriteTests {
  private static final String DOMAIN = "example.org";

  private Configs configs;
  private TestServer server;
  private Http http;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain(DOMAIN,
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    // never follows a redirect: the whole point here is what the redirect itself says
    http = new Http();
  }

  @After
  public void tearDown() {
    if (http != null) {
      http.close();
    }
    if (server != null) {
      server.close();
    }
    if (configs != null) {
      configs.delete();
    }
  }

  private Accounts accounts() {
    return server.auth.forDomain(DOMAIN);
  }

  private Rewrites rewrites() {
    return accounts().rewrites;
  }

  private ContentStore content() {
    return accounts().site.store();
  }

  private ContentRecord page(String uri, String title, boolean published) throws Exception {
    return content().save(new ContentRecord(0, uri, title, ContentRecord.Kind.markdown, "", "", "",
        "# " + title, published, false, null, null, null), null);
  }

  /** the same page at a new address, which is what an editor saving a moved page produces */
  private static ContentRecord movedTo(ContentRecord page, String uri) {
    return new ContentRecord(page.id(), uri, page.title(), page.kind(), page.templateName(),
        page.navFolder(), page.fields(), page.body(), page.published(), page.humanOnly(),
        page.publishedAt(), page.createdAt(), page.updatedAt(), page.updatedBy());
  }

  private static ContentRecord renamed(ContentRecord page, String title) {
    return new ContentRecord(page.id(), page.uri(), title, page.kind(), page.templateName(),
        page.navFolder(), page.fields(), page.body(), page.published(), page.humanOnly(),
        page.publishedAt(), page.createdAt(), page.updatedAt(), page.updatedBy());
  }

  private long live(String from, String to, Rewrites.Status status) throws Exception {
    return rewrites().save(0, from, to, status, Rewrites.State.active, true, "", null, null);
  }

  // ---- the request path --------------------------------------------------------------------------

  @Test
  public void anAddressThatMovedAnswersWithWhereItWent() throws Exception {
    page("/the-new-page", "The new page", true);
    live("/the-old-page", "/the-new-page", Rewrites.Status.moved);

    Http.Response response = http.get(server.port, DOMAIN, "/the-old-page");
    assertEquals("301 is the answer every crawler has understood for twenty-five years",
        301, response.status);
    assertEquals("/the-new-page", response.header("location"));
  }

  @Test
  public void everyStatusThisOffersActuallyAnswersWithIt() throws Exception {
    live("/a", "/one", Rewrites.Status.moved);
    live("/b", "/two", Rewrites.Status.movedStrict);
    live("/c", "/three", Rewrites.Status.temporary);
    live("/d", "/four", Rewrites.Status.temporaryStrict);

    assertEquals(301, http.get(server.port, DOMAIN, "/a").status);
    assertEquals(308, http.get(server.port, DOMAIN, "/b").status);
    assertEquals(302, http.get(server.port, DOMAIN, "/c").status);
    assertEquals(307, http.get(server.port, DOMAIN, "/d").status);
  }

  /**
   * A page that is deliberately gone says so, rather than looking like a mistake.
   *
   * A crawler treats a 404 as possibly temporary and comes back for months; it drops a 410 quickly.
   */
  @Test
  public void anAddressThatIsGoneAnswersGoneRatherThanNotFound() throws Exception {
    live("/the-old-page", "", Rewrites.Status.gone);
    Http.Response response = http.get(server.port, DOMAIN, "/the-old-page");
    assertEquals(410, response.status);
    assertNull("there is nowhere to send anybody", response.header("location"));
  }

  /**
   * A page at the address wins, always.
   *
   * This is the ordering the whole feature rests on: content, then a listing, then a rewrite. It is
   * what lets somebody put a page back at an old address without first hunting down the redirect --
   * the page answers and the rewrite behind it simply stops mattering.
   */
  @Test
  public void aRealPageBeatsARewriteAtTheSameAddress() throws Exception {
    live("/about", "/somewhere-else", Rewrites.Status.moved);
    assertEquals("nothing is there yet, so the rewrite answers",
        301, http.get(server.port, DOMAIN, "/about").status);

    page("/about", "About", true);
    Http.Response response = http.get(server.port, DOMAIN, "/about");
    assertEquals("and now the page does", 200, response.status);
    assertTrue(response.body, response.body.contains("About"));
  }

  @Test
  public void aProposalDoesNothingUntilSomebodyAcceptsIt() throws Exception {
    rewrites().save(0, "/waiting", "/somewhere", Rewrites.Status.moved, Rewrites.State.proposed,
        true, "", null, null);
    assertEquals("a suggestion is not a redirect", 404,
        http.get(server.port, DOMAIN, "/waiting").status);
  }

  @Test
  public void aRewriteThatIsTurnedOffDoesNotFire() throws Exception {
    long id = live("/off", "/on", Rewrites.Status.moved);
    assertEquals(301, http.get(server.port, DOMAIN, "/off").status);

    Rewrites.Record one = rewrites().byId(id);
    rewrites().save(id, one.fromUri(), one.toUri(), one.statusOr(), Rewrites.State.active,
        false, "", null, null);
    assertEquals(404, http.get(server.port, DOMAIN, "/off").status);
  }

  @Test
  public void aPermanentRedirectMayBeCachedAndATemporaryOneMayNot() throws Exception {
    live("/perm", "/x", Rewrites.Status.moved);
    live("/temp", "/y", Rewrites.Status.temporary);
    assertTrue(http.get(server.port, DOMAIN, "/perm").header("cache-control").contains("max-age"));
    assertEquals("a temporary redirect that a browser remembers is a permanent one",
        "no-store", http.get(server.port, DOMAIN, "/temp").header("cache-control"));
  }

  @Test
  public void everythingAddedToARewriteResponseIsStillSecure() throws Exception {
    live("/old", "/new", Rewrites.Status.moved);
    Http.Response response = http.get(server.port, DOMAIN, "/old");
    assertEquals("nosniff", response.header("x-content-type-options"));
    assertEquals("no-referrer", response.header("referrer-policy"));
  }

  @Test
  public void howOftenOneFiredIsCountedAndWrittenOut() throws Exception {
    long id = live("/counted", "/somewhere", Rewrites.Status.moved);
    for (int k = 0; k < 3; k++) {
      http.get(server.port, DOMAIN, "/counted");
    }
    assertEquals("counted in memory, off the request path", 0, rewrites().byId(id).hits());

    accounts().rewriteHits.flush(rewrites());
    Rewrites.Record after = rewrites().byId(id);
    assertEquals(3, after.hits());
    assertNotNull("and when, so a redirect nobody follows can be found", after.lastUsedAt());
  }

  // ---- proposals ---------------------------------------------------------------------------------

  /**
   * Moving a published page proposes a redirect; moving a draft does not.
   *
   * That is the whole of what "meaningful" means here. It is not how different the two addresses
   * are -- it is whether the old one was ever a promise. A draft's address has never been anywhere:
   * nobody linked to it and nothing indexed it.
   */
  @Test
  public void movingAPublishedPageProposesARedirect() throws Exception {
    ContentRecord published = page("/old-home", "Home", true);
    content().save(movedTo(published, "/new-home"), null);

    List<Rewrites.Record> proposals = rewrites().proposals();
    assertEquals(1, proposals.size());
    assertEquals("/old-home", proposals.get(0).fromUri());
    assertEquals("/new-home", proposals.get(0).toUri());
    assertEquals(301, proposals.get(0).status());
    assertEquals(Long.valueOf(published.id()), proposals.get(0).contentId());
  }

  @Test
  public void movingADraftProposesNothing() throws Exception {
    ContentRecord draft = page("/draft-one", "Draft", false);
    content().save(movedTo(draft, "/draft-two"), null);
    assertTrue("nobody could have linked to an address that was never published",
        rewrites().proposals().isEmpty());
  }

  @Test
  public void editingAPageWithoutMovingItProposesNothing() throws Exception {
    ContentRecord published = page("/steady", "Steady", true);
    content().save(renamed(published, "Steady, renamed"), null);
    assertTrue(rewrites().proposals().isEmpty());
  }

  @Test
  public void deletingAPublishedPageProposesTellingCrawlersItIsGone() throws Exception {
    ContentRecord published = page("/going", "Going", true);
    content().deleteContent(published.id(), null);

    List<Rewrites.Record> proposals = rewrites().proposals();
    assertEquals(1, proposals.size());
    assertEquals("/going", proposals.get(0).fromUri());
    assertTrue(proposals.get(0).isGone());
    assertEquals("", proposals.get(0).toUri());
  }

  @Test
  public void deletingADraftProposesNothing() throws Exception {
    ContentRecord draft = page("/never-seen", "Draft", false);
    content().deleteContent(draft.id(), null);
    assertTrue(rewrites().proposals().isEmpty());
  }

  /**
   * A page that moves twice before anybody accepts updates the proposal rather than making a second.
   *
   * The `from` is unique, so a second row would be refused anyway -- what matters is that the
   * proposal points at where the page has actually ended up rather than at where it paused.
   */
  @Test
  public void aPageThatMovesTwiceBeforeAnybodyLooksHasOneProposalPointingAtTheEnd()
      throws Exception {
    ContentRecord published = page("/first", "Page", true);
    ContentRecord moved = content().save(movedTo(published, "/second"), null);
    content().save(movedTo(moved, "/third"), null);

    List<Rewrites.Record> proposals = rewrites().proposals();
    assertEquals("one per address that was published, not one per edit", 2, proposals.size());
    assertEquals("and the first one skips the address it paused at", "/third",
        rewrites().byFrom("/first").toUri());
    assertEquals("/third", rewrites().byFrom("/second").toUri());
  }

  // ---- accepting, and chains ---------------------------------------------------------------------

  /**
   * Accepting a proposal re-points everything that was aimed at the old address.
   *
   * A chain costs a round trip at every hop, bleeds a little ranking at each one, and browsers give
   * up after a handful. Collapsing costs one query when somebody presses a button; the alternative
   * is an extra request for every visitor for ever.
   */
  @Test
  public void acceptingCollapsesAChainRatherThanExtendingIt() throws Exception {
    live("/a", "/b", Rewrites.Status.moved);
    long proposal = rewrites().save(0, "/b", "/c", Rewrites.Status.moved, Rewrites.State.proposed,
        true, "", null, null);

    int collapsed = rewrites().accept(proposal, null);
    assertEquals("the older redirect moved with it", 1, collapsed);
    assertEquals("/c", rewrites().byFrom("/a").toUri());
    assertEquals("/c", rewrites().byFrom("/b").toUri());

    // and both land in one hop from the outside
    assertEquals("/c", http.get(server.port, DOMAIN, "/a").header("location"));
    assertEquals("/c", http.get(server.port, DOMAIN, "/b").header("location"));
  }

  @Test
  public void aLongChainCollapsesAllTheWay() throws Exception {
    live("/one", "/two", Rewrites.Status.moved);
    live("/two", "/three", Rewrites.Status.moved);
    live("/three", "/four", Rewrites.Status.moved);
    long proposal = rewrites().save(0, "/four", "/five", Rewrites.Status.moved,
        Rewrites.State.proposed, true, "", null, null);

    assertEquals(3, rewrites().accept(proposal, null));
    for (String from : List.of("/one", "/two", "/three", "/four")) {
      assertEquals(from + " should land straight on /five", "/five",
          rewrites().byFrom(from).toUri());
    }
  }

  /**
   * A move that comes back round to where it started leaves no self-pointing redirect.
   *
   * What one would do if it fired is send a browser to the address it just asked for, which is a
   * loop -- and one a person reading the list would have to work out for themselves.
   */
  @Test
  public void aMoveThatReturnsToItsOwnAddressLeavesNoLoop() throws Exception {
    live("/home", "/house", Rewrites.Status.moved);
    long back = rewrites().save(0, "/house", "/home", Rewrites.Status.moved,
        Rewrites.State.proposed, true, "", null, null);
    rewrites().accept(back, null);

    assertNull("/home would have pointed at itself", rewrites().byFrom("/home"));
    assertEquals("and /house still goes home", "/home", rewrites().byFrom("/house").toUri());
    assertEquals(404, http.get(server.port, DOMAIN, "/home").status);
  }

  @Test
  public void twoRewritesPointingAtEachOtherDoNotHangTheCollapse() throws Exception {
    // built by hand rather than by a move, because nothing this server does produces it
    live("/x", "/y", Rewrites.Status.moved);
    live("/y", "/x", Rewrites.Status.moved);
    long proposal = rewrites().save(0, "/z", "/x", Rewrites.Status.moved, Rewrites.State.proposed,
        true, "", null, null);
    rewrites().accept(proposal, null);
    assertNotNull("it finished", rewrites().byFrom("/z"));
  }

  @Test
  public void rejectingAProposalLeavesNothingBehind() throws Exception {
    long proposal = rewrites().save(0, "/gone", "/elsewhere", Rewrites.Status.moved,
        Rewrites.State.proposed, true, "", null, null);
    rewrites().reject(proposal, null);
    assertNull(rewrites().byId(proposal));
    assertEquals(404, http.get(server.port, DOMAIN, "/gone").status);
  }

  @Test
  public void acceptingSomethingThatIsNotAProposalDoesNothing() throws Exception {
    long id = live("/already", "/live", Rewrites.Status.moved);
    assertEquals(0, rewrites().accept(id, null));
    assertEquals(Rewrites.State.active, rewrites().byId(id).state());
  }

  // ---- what may be written -----------------------------------------------------------------------

  /**
   * A destination that leaves the site by protocol is refused; one that leaves by address is not.
   *
   * Sending somebody to another domain is a real thing to want. What is refused is anything that is
   * not an http address, because a `javascript:` target in a Location header is an open redirect
   * with a payload on the end of it.
   */
  @Test
  public void onlyAnHttpAddressMayLeaveTheSite() {
    assertNull(Rewrites.check("/old", "https://elsewhere.example/new", Rewrites.Status.moved,
        false));
    assertNull(Rewrites.check("/old", "/new", Rewrites.Status.moved, false));

    for (String bad : new String[]{"javascript:alert(1)", "data:text/html,<script>",
        "//evil.example/x", "vbscript:x", "file:///etc/passwd"}) {
      assertNotNull(bad + " should be refused",
          Rewrites.check("/old", bad, Rewrites.Status.moved, false));
    }
  }

  @Test
  public void nothingThatWouldBreakAHeaderMayBeStored() {
    for (String bad : new String[]{"/a\r\nLocation: https://evil.example", "/a b", "/a b",
        "/a\nSet-Cookie: x=y"}) {
      assertNotNull(bad + " should be refused", Rewrites.checkUri(bad));
    }
    assertNull(Rewrites.checkUri("/an-ordinary-path"));
    assertNull(Rewrites.checkUri("/a/nested/one"));
  }

  @Test
  public void aRewriteMayNotSendPeopleToTheAddressTheyAsked() {
    assertNotNull(Rewrites.check("/same", "/same", Rewrites.Status.moved, false));
  }

  @Test
  public void aRedirectNeedsSomewhereToGoAndGoneDoesNot() {
    assertNotNull(Rewrites.check("/old", "", Rewrites.Status.moved, false));
    assertNull("gone is the one answer with nowhere",
        Rewrites.check("/old", "", Rewrites.Status.gone, false));
  }

  @Test
  public void aQueryStringIsNotAnAddressThisMatches() {
    // the router strips the query before anything sees the path, so a rewrite carrying one could
    // never match and would sit in the list looking correct
    assertNotNull(Rewrites.checkUri("/search?q=x"));
    assertNotNull(Rewrites.checkUri("/page#section"));
  }

  // ---- the admin screens -------------------------------------------------------------------------

  @Test
  public void theScreensAreTheirOwnUrlsAndTheProposalsAreOnThem() throws Exception {
    Browser admin = signedIn();
    page("/moved-from", "A page", true);
    ContentRecord published = content().byUri("/moved-from");
    content().save(movedTo(published, "/moved-to"), null);

    assertEquals(200, admin.get("/admin/rewrites").status());
    assertEquals(200, admin.get("/admin/rewrites/new").status());
    Browser.Page list = admin.get("/admin/rewrites");
    assertTrue(list.body(), list.contains("/moved-from"));
    assertTrue("and it says it is waiting", list.contains("waiting"));

    Browser.Page panel = admin.get("/admin/rewrites/list");
    assertEquals(200, panel.status());
    assertFalse("a panel carries no shell", panel.contains("<!doctype html>"));
  }

  @Test
  public void aProposalIsAcceptedFromTheListAndThenFires() throws Exception {
    Browser admin = signedIn();
    ContentRecord published = page("/before", "A page", true);
    content().save(movedTo(published, "/after"), null);
    long proposal = rewrites().proposals().get(0).id();

    admin.submitToAndFollow("/admin/rewrites",
        Map.of("action", "accept", "id", String.valueOf(proposal)));
    assertEquals(Rewrites.State.active, rewrites().byId(proposal).state());
    assertEquals("/after", http.get(server.port, DOMAIN, "/before").header("location"));
  }

  @Test
  public void aRewriteIsWrittenFromTheFormAndThenAnswers() throws Exception {
    Browser admin = signedIn();
    admin.submitToAndFollow("/admin/rewrites", Map.of("action", "save", "from", "/typed",
        "to", "/somewhere", "status", "301", "note", "by hand", "enabled", "on"));

    Rewrites.Record one = rewrites().byFrom("/typed");
    assertNotNull(one);
    assertEquals("/somewhere", one.toUri());
    assertEquals("by hand", one.note());
    assertEquals(301, http.get(server.port, DOMAIN, "/typed").status);
  }

  @Test
  public void aFormThatWouldMakeAnOpenRedirectIsRefused() throws Exception {
    Browser admin = signedIn();
    Browser.Page landed = admin.submitToAndFollow("/admin/rewrites", Map.of("action", "save",
        "from", "/x", "to", "javascript:alert(1)", "status", "301", "note", "", "enabled", "on"));
    assertTrue(landed.body(), landed.contains("class=\"problem\""));
    assertNull(rewrites().byFrom("/x"));
  }

  @Test
  public void twoRewritesCannotClaimTheSameAddress() throws Exception {
    Browser admin = signedIn();
    live("/taken", "/one", Rewrites.Status.moved);
    Browser.Page landed = admin.submitToAndFollow("/admin/rewrites", Map.of("action", "save",
        "from", "/taken", "to", "/two", "status", "301", "note", "", "enabled", "on"));
    assertTrue(landed.body(), landed.contains("already answers"));
    assertEquals("/one", rewrites().byFrom("/taken").toUri());
  }

  /**
   * Saving one that a page already answers for says so rather than leaving it to be noticed.
   *
   * It is not a refusal -- the page may be about to move -- but a row that will never fire is worth
   * a sentence at the moment somebody makes it.
   */
  @Test
  public void aRewriteBehindAPageSaysSoWhenItIsSaved() throws Exception {
    Browser admin = signedIn();
    page("/live-page", "Live", true);
    Browser.Page landed = admin.submitToAndFollow("/admin/rewrites", Map.of("action", "save",
        "from", "/live-page", "to", "/elsewhere", "status", "301", "note", "", "enabled", "on"));
    assertTrue(landed.body(), landed.contains("a page always wins"));
    assertNotNull("and it is still saved, because the page may be about to move",
        rewrites().byFrom("/live-page"));
  }

  /**
   * Rewrites take `content_publish`, not `content_write`.
   *
   * Pointing an address that has standing at somewhere else is the same kind of act as taking a
   * page down, and somebody trusted to write a page is not automatically trusted with that.
   */
  @Test
  public void somebodyWhoMayWriteButNotPublishCannotReachIt() throws Exception {
    accounts().roleDefs.save("writer", "Writer", "",
        java.util.Set.of(io.hearth.auth.Permission.content_write), "", null);
    Browser writer = new Browser(server.port, DOMAIN);
    writer.get("/register");
    writer.submit(Map.of("email", "writer@example.com"));
    writer.submit(Map.of("code", server.mail().lastCodeFor("writer@example.com")));
    long id = accounts().users.byEmail("writer@example.com").id();
    accounts().users.approve(id, null);
    accounts().roles.grant(id, "writer", null);

    assertEquals("they can still write pages", 200, writer.get("/admin/content").status());
    assertEquals("and the door is not even drawn", 404, writer.get("/admin/rewrites").status());
    assertFalse(writer.get("/admin/content").contains("/admin/rewrites"));
  }

  private Browser signedIn() throws Exception {
    Browser admin = new Browser(server.port, DOMAIN);
    admin.get("/register");
    admin.submit(Map.of("email", "boss@example.com"));
    admin.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    return admin;
  }
}
