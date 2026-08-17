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
import static org.junit.Assert.assertTrue;

/**
 * The admin URL space.
 *
 * The rules being proved here are the ones that came out of things going wrong: every sub-view is
 * its own path, a panel renders the same whether it is embedded or fetched, identity lives in the
 * path while filters live in the query, and every mutation is a POST that redirects.
 */
public class AdminSectionTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = new Browser(server.port, "example.org");
    admin.get("/register");
    admin.submit(Map.of("email", "boss@example.com"));
    admin.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
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

  /** every path this admin answers on, page and panel alike */
  private static final String[] PAGES = {
      "/admin", "/admin/people", "/admin/bans", "/admin/content", "/admin/templates",
      "/admin/navigation", "/admin/survey", "/admin/survey/retired",
      "/admin/system/events", "/admin/system/analytics", "/admin/system/caching",
      "/admin/system/ai", "/admin/system/logs"};

  @Test
  public void theSidebarIsAMenuOnAPhoneAndAColumnOnAScreen() throws Exception {
    // Thirty links wrapped into a strip above every page is a screenful to scroll past before
    // reading anything, and each chip is too small to hit. It is now a <details>, shipped open --
    // so a phone with no JavaScript gets exactly what it had, and the script closes it rather than
    // being what opens it.
    Browser.Page page = admin.get("/admin");
    assertTrue(page.body(), page.contains("data-sidemenu"));
    assertTrue("open in the markup, or a browser without the script has no way in",
        page.contains("<details class=\"sidemenu\" data-sidemenu open>"));
    assertTrue("and the button says which section it is hiding", page.contains("Overview"));
    assertTrue("the manners come from the file the site menu already uses",
        page.contains("/~menu.js"));
  }

  @Test
  public void theAsyncScreenSaysWhatTheBoxIsAskingOtherPeople() throws Exception {
    Browser.Page page = admin.get("/admin/system/async");
    assertEquals(200, page.status());
    assertTrue(page.body(), page.contains("waiting"));
    assertTrue("the pace is on the screen because it is a promise, not a tuning knob",
        page.contains("every 1.5 seconds") || page.contains("every 0.005 seconds"));
    assertTrue("and the difference between the two kinds of failure is explained",
        page.contains("never heard of that address"));

    // its panel is a real path like every other, and renders the same either way
    Browser.Page panel = admin.get("/admin/system/async/queue");
    assertEquals(200, panel.status());
    assertFalse("a panel is a fragment, not a page", panel.contains("<html"));
  }

  @Test
  public void theQueueCanBeClearedAndRefilledFromTheScreen() throws Exception {
    Browser.Page cleared = admin.submitToAndFollow("/admin/system/async",
        Map.of("action", "clear"));
    assertTrue(cleared.body(), cleared.contains("dropped"));
    Browser.Page retried = admin.submitToAndFollow("/admin/system/async",
        Map.of("action", "retry"));
    assertTrue(retried.body(),
        retried.contains("queued") || retried.contains("Nothing is waiting"));
    assertTrue(admin.submitToAndFollow("/admin/system/async", Map.of("action", "wat"))
        .contains("not something this page can do"));
  }

  private static final String[] PANELS = {
      "/admin/people/list", "/admin/bans/list", "/admin/content/list", "/admin/templates/list",
      "/admin/survey/list", "/admin/survey/retired/list", "/admin/system/events/stream",
      "/admin/system/caching/stats", "/admin/system/ai/actions", "/admin/system/logs/results"};

  // ---- the shell -------------------------------------------------------------------------------

  @Test
  public void everySectionIsItsOwnUrl() throws Exception {
    // real server loads, so a bookmark and the back button both work
    for (String path : PAGES) {
      assertEquals(path + " should load", 200, admin.get(path).status());
    }
  }

  @Test
  public void everyPanelIsItsOwnUrlToo() throws Exception {
    // the rule that replaced ?fragment=1: a refreshable view is a path, so it shows up in the log
    // as itself and cannot be broken by how a template escapes a query string
    for (String path : PANELS) {
      Browser.Page panel = admin.get(path);
      assertEquals(path + " should load", 200, panel.status());
      assertFalse(path + " must not carry the shell", panel.contains("<!doctype html>"));
      assertFalse(path + " must not carry the sidebar", panel.contains("class=\"side\""));
      assertFalse(path + " must not carry the top bar", panel.contains("Sign out"));
    }
  }

  @Test
  public void aPanelIsTheSameHtmlEmbeddedOrFetched() throws Exception {
    // the page includes its panel by calling the method the panel's URL calls, so the two cannot
    // drift; if they ever do, a live refresh silently shows something the page never would
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/twice", "title", "Twice",
        "kind", "markdown", "template_name", "", "published", "on", "body", "hello"));
    Browser.Page page = admin.get("/admin/content");
    Browser.Page panel = admin.get("/admin/content/list");
    assertTrue("the page embeds the panel", page.contains("data-panel=\"/admin/content/list\""));
    assertTrue("and the panel's rows are in the page", page.contains("/twice"));
    assertTrue(panel.contains("/twice"));
    assertTrue("the panel body appears verbatim inside the page",
        page.body().contains(panel.body().trim()));
  }

  @Test
  public void theSidebarNestsTheSectionsUnderTheirHeadings() throws Exception {
    // Standing in System: its children are open, everybody else's are folded away. Thirty entries
    // is a list nobody reads, and most of them are nobody's business most days.
    Browser.Page page = admin.get("/admin/system/events");
    for (String label : new String[]{"Overview", "People", "Content", "Survey", "System",
        "Machine", "Events", "Analytics", "Caching", "AI", "Log"}) {
      assertTrue("sidebar should offer " + label, page.contains("<span>" + label + "</span>"));
    }
    for (String folded : new String[]{"Bans", "Templates", "Retired"}) {
      assertFalse("somebody in System has no business seeing " + folded,
          page.contains("<span>" + folded + "</span>"));
    }
    assertTrue("the current section is marked", page.contains("class=\"on kid\""));
    assertTrue("a nested section is drawn as one", page.contains(" kid\""));
    assertTrue("each item carries an inline icon", page.contains("<svg class=\"icon\""));
  }

  @Test
  public void aChildOpensItsWholeFamilyAndNobodyElses() throws Exception {
    // being *in* a child keeps its siblings visible, so moving between them is one press
    Browser.Page bans = admin.get("/admin/bans");
    assertTrue(bans.contains("<span>Bans</span>"));
    assertTrue("its siblings come with it", bans.contains("<span>Roles</span>"));
    assertFalse(bans.contains("<span>Templates</span>"));

    Browser.Page people = admin.get("/admin/people");
    assertTrue("and standing on the parent opens them too", people.contains("<span>Bans</span>"));
  }

  @Test
  public void aHeadingWithNoPageOfItsOwnLandsOnItsFirstChild() throws Exception {
    // System is a grouping, not a destination; sending somebody to an empty shell would be worse
    Browser.Page page = admin.get("/admin/system");
    assertEquals(200, page.status());
    assertTrue(page.contains("Events"));
  }

  @Test
  public void theTopBarSaysWhoYouAreAndOffersAWayOut() throws Exception {
    Browser.Page page = admin.get("/admin");
    assertTrue(page.contains("boss@example.com"));
    assertTrue("signing out has to be quick", page.contains("Sign out"));
    assertTrue("and it is a form, not a link", page.contains("<form method=\"post\" action=\"/logout\""));
    assertTrue(page.contains("view site"));
  }

  @Test
  public void anUnknownAdminSubPageIsNotFound() throws Exception {
    assertEquals(404, admin.get("/admin/nonsense").status());
    assertEquals(404, admin.get("/admin/content/nonsense").status());
    assertEquals("an edit with no id names nothing", 404, admin.get("/admin/content/edit/").status());
  }

  @Test
  public void theWholeSectionIsInvisibleToNonAdmins() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    for (String path : new String[]{"/admin", "/admin/people", "/admin/people/list",
        "/admin/system/events", "/admin/system/logs/results"}) {
      assertEquals(path + " should not exist for a stranger", 404, stranger.get(path).status());
    }
  }

  // ---- every mutation is a POST that redirects ------------------------------------------------------

  @Test
  public void everyChangeRedirectsAndSaysSoOnTheNextPage() throws Exception {
    // redirect-after-post, so a refresh cannot repeat the action and the URL stays a plain path
    Browser.Page saved = admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/about",
        "title", "About", "kind", "markdown", "template_name", "", "published", "on", "body", "hello"));
    assertEquals(303, saved.status());
    assertEquals("/admin/content", saved.location());
    assertFalse("nothing is announced in a URL", saved.location().contains("?"));

    Browser.Page landed = admin.follow(saved);
    assertTrue("the confirmation rides on the session, not the query string",
        landed.contains("/about saved."));
    assertTrue(landed.contains("class=\"done\""));

    assertFalse("and it is shown exactly once",
        admin.get("/admin/content").contains("/about saved."));
  }

  @Test
  public void aRefusalComesBackInRedAndTheWorkIsNotLost() throws Exception {
    Browser.Page bad = admin.submitTo("/admin/content", Map.of("action", "save", "uri", "about",
        "title", "About", "kind", "markdown", "template_name", "", "body", "hello"));
    assertEquals(303, bad.status());
    Browser.Page landed = admin.follow(bad);
    assertTrue(landed.contains("starts with"));
    assertTrue("a refusal is not a confirmation", landed.contains("class=\"problem\""));
  }

  @Test
  public void adminActionsNeedACsrfToken() throws Exception {
    admin.get("/admin/content");
    Browser.Page forged = admin.submitRaw("/admin/content",
        Map.of("action", "save", "uri", "/x", "body", "x"));
    assertEquals(303, forged.status());
    assertTrue(admin.follow(forged).contains("That form expired"));
    assertFalse("and nothing was written", admin.get("/admin/content/list").contains("/x"));
  }

  // ---- listings and forms are different pages --------------------------------------------------------

  @Test
  public void creatingAndEditingAreTheirOwnPages() throws Exception {
    // a form wedged above a listing has ambiguous state the moment the listing moves
    Browser.Page list = admin.get("/admin/content");
    assertTrue(list.contains("href=\"/admin/content/new\""));
    assertFalse("the listing does not carry an editor", list.contains("name=\"body\""));

    Browser.Page blank = admin.get("/admin/content/new");
    assertEquals(200, blank.status());
    assertTrue(blank.contains("name=\"body\""));
    assertTrue(blank.contains("New page"));

    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/about",
        "title", "About", "kind", "markdown", "template_name", "", "published", "on", "body", "hello"));
    String id = admin.get("/admin/content/list").body()
        .replaceAll("(?s).*name=\"id\" value=\"(\\d+)\".*", "$1");

    Browser.Page editing = admin.get("/admin/content/edit/" + id);
    assertEquals("identity goes in the path", 200, editing.status());
    assertTrue("the body should be in the textarea", editing.contains("hello"));
    assertTrue(editing.contains("Edit /about"));
  }

  @Test
  public void theContentListingFiltersOnTextAndOnPublished() throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/needle",
        "title", "Needle", "kind", "markdown", "template_name", "", "published", "on", "body", "x"));
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/haystack",
        "title", "Haystack", "kind", "markdown", "template_name", "", "body", "y"));

    Browser.Page found = admin.get("/admin/content/list?q=needle");
    assertTrue(found.contains("/needle"));
    assertFalse("a filter that does not filter is decoration", found.contains("/haystack"));

    Browser.Page drafts = admin.get("/admin/content/list?published=no");
    assertTrue(drafts.contains("/haystack"));
    assertFalse(drafts.contains("/needle"));
  }

  @Test
  public void aPageOutsideTheNavigationIsFlaggedRatherThanLost() throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/orphan",
        "title", "Orphan", "kind", "markdown", "template_name", "", "published", "on", "body", "x"));
    assertTrue("the listing warns", admin.get("/admin/content/list").contains("no folder"));

    Browser.Page navigation = admin.get("/admin/navigation");
    assertTrue(navigation.contains("Outside the navigation"));
    assertTrue(navigation.contains("/orphan"));
  }

  @Test
  public void givingAPageAFolderPutsItInTheNavigationTree() throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/guide",
        "title", "Guide", "kind", "markdown", "template_name", "", "nav_folder", "Docs",
        "published", "on", "body", "x"));
    Browser.Page navigation = admin.get("/admin/navigation");
    assertTrue(navigation.contains("Docs"));
    assertTrue(navigation.contains("/guide"));
    assertFalse(navigation.contains("Outside the navigation"));
  }

  // ---- content kinds and template fields ---------------------------------------------------------------

  @Test
  public void theKindsSayWhatTheyAreRatherThanNamingThemselves() throws Exception {
    Browser.Page form = admin.get("/admin/content/new");
    assertTrue(form.contains("Markdown content"));
    assertTrue(form.contains("HTML content"));
    assertTrue(form.contains("Full page"));
    assertTrue("and each carries the sentence the editor shows", form.contains("data-describe="));
    assertTrue("a full page needs no template, and the editor knows it",
        form.contains("data-wants-template="));
  }

  @Test
  public void aFullPageIsSavedWithNoTemplateEvenIfOneWasSelected() throws Exception {
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "site",
        "body", "<html>{{{body}}}</html>"));
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/whole",
        "title", "Whole", "kind", "page", "template_name", "site", "published", "on",
        "body", "<!doctype html><html><body>whole</body></html>"));
    Browser.Page list = admin.get("/admin/content/list");
    assertTrue(list.contains("Full page"));
    assertTrue("no template is claimed", list.contains("<td class=\"dim mono\"></td>"));
  }

  @Test
  public void aTemplateDeclaresFieldsAndThePageEditorAsksForThem() throws Exception {
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "hero",
        "body", "<h1>{{headline}}</h1>{{{body}}}",
        "p_name_0", "headline", "p_type_0", "text", "p_label_0", "Headline",
        "p_help_0", "the big words", "p_required_0", "on"));

    Browser.Page templates = admin.get("/admin/templates/list");
    assertTrue("the listing counts them", templates.contains("hero"));

    Browser.Page editor = admin.get("/admin/content/new");
    assertTrue("the page editor is told what each template needs",
        editor.contains("field_headline"));
    assertTrue(editor.contains("the big words"));
  }

  @Test
  public void aRequiredTemplateFieldIsActuallyRequired() throws Exception {
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "hero",
        "body", "<h1>{{headline}}</h1>{{{body}}}",
        "p_name_0", "headline", "p_type_0", "text", "p_label_0", "Headline", "p_required_0", "on"));
    Browser.Page refused = admin.submitToAndFollow("/admin/content",
        Map.of("action", "save", "uri", "/hero", "title", "Hero", "kind", "markdown",
            "template_name", "hero", "published", "on", "body", "x"));
    assertTrue(refused.body(), refused.contains("required by the hero template"));

    Browser.Page saved = admin.submitToAndFollow("/admin/content",
        Map.of("action", "save", "uri", "/hero", "title", "Hero", "kind", "markdown",
            "template_name", "hero", "field_headline", "Welcome", "published", "on", "body", "x"));
    assertTrue(saved.contains("/hero saved."));
    assertTrue("and the value comes back into the editor",
        admin.get("/admin/content/list").contains("/hero"));
  }

  @Test
  public void aFieldNameHasToBeUsableAsAName() throws Exception {
    Browser.Page bad = admin.submitToAndFollow("/admin/templates",
        Map.of("action", "save", "name", "hero", "body", "x",
            "p_name_0", "Not A Name", "p_type_0", "text"));
    assertTrue(bad.contains("not a usable field name"));
  }

  // ---- templates -------------------------------------------------------------------------------------

  @Test
  public void theTemplatePageListsAndCountsUsage() throws Exception {
    admin.submitToAndFollow("/admin/templates", Map.of("action", "save", "name", "site",
        "body", "<html>{{{body}}}</html>"));
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/a", "title", "A",
        "kind", "markdown", "template_name", "site", "published", "on", "body", "hi"));

    Browser.Page list = admin.get("/admin/templates/list");
    assertTrue(list.contains("site"));
    assertTrue("it should say how many pages depend on it", list.contains("\"num dim\">1</td>"));
  }

  @Test
  public void aTemplateNeedsASaneName() throws Exception {
    Browser.Page bad = admin.submitToAndFollow("/admin/templates",
        Map.of("action", "save", "name", "not a name", "body", "x"));
    assertTrue(bad.contains("letters, digits"));
  }

  @Test
  public void aNewTemplateStartsFromSomethingThatWorks() throws Exception {
    Browser.Page page = admin.get("/admin/templates/new");
    assertTrue("the blank form should be a usable starting point", page.contains("&lt;!doctype html&gt;"));
    assertTrue(page.contains("{{{body}}}"));
  }

  // ---- the event bus inspector -----------------------------------------------------------------------

  @Test
  public void theEventPageShowsWhatFlowed() throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/a", "title", "A",
        "kind", "markdown", "template_name", "", "published", "on", "body", "hi"));
    Browser.Page page = admin.get("/admin/system/events");
    assertEquals(200, page.status());
    assertTrue(page.contains("content"));
    assertTrue(page.contains("insert"));
    assertTrue("it says how big the window is", page.contains("1000"));
    assertTrue("and the live button is a button, not a link with a query flag",
        page.contains("data-live="));
  }

  // ---- caching ----------------------------------------------------------------------------------------

  @Test
  public void theCachingPageShowsEveryCacheAndItsHitRate() throws Exception {
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/cached",
        "title", "Cached", "kind", "markdown", "template_name", "", "published", "on", "body", "hi"));
    Browser visitor = new Browser(server.port, "example.org");
    visitor.get("/cached");
    visitor.get("/cached");

    Browser.Page page = admin.get("/admin/system/caching");
    assertEquals(200, page.status());
    assertTrue("every cache is listed with its policy", page.contains("rendered"));
    assertTrue(page.contains("sessions"));
    assertTrue("and the hit rate, which is the only reason to look", page.contains("hit rate"));
    assertTrue(page.contains("invalidated"));
  }

  // ---- analytics --------------------------------------------------------------------------------------

  @Test
  public void theAnalyticsPageSummarizesRealTraffic() throws Exception {
    Browser visitor = new Browser(server.port, "example.org");
    for (int k = 0; k < 3; k++) {
      visitor.get("/");
    }
    visitor.get("/definitely-not-here/wp-login.php");

    Browser.Page page = admin.get("/admin/system/analytics");
    assertEquals(200, page.status());
    assertTrue(page.contains("Top pages"));
    assertTrue(page.contains("Common IPs"));
    assertTrue(page.contains("Common members"));
    assertTrue(page.contains("Browsers and bots"));
    assertTrue(page.contains("Status codes"));
    assertTrue("the requests we just made should be counted", page.contains("127.0.0.1"));
  }

  @Test
  public void theAnalyticsPageKnowsWhichMemberWasBusy() throws Exception {
    // the admin's own requests are attributed, which is only possible because session resolution
    // happens on the request path and writes the user id into the log
    admin.get("/");
    admin.get("/");
    Browser.Page page = admin.get("/admin/system/analytics");
    assertTrue(page.body(), page.contains("user "));
  }

  // ---- the log ------------------------------------------------------------------------------------------

  @Test
  public void theLogListsRequests() throws Exception {
    new Browser(server.port, "example.org").get("/some-page");
    Browser.Page page = admin.get("/admin/system/logs");
    assertEquals(200, page.status());
    assertTrue(page.contains("/some-page"));
    assertTrue("with a search box", page.contains("type=\"search\""));
  }

  @Test
  public void theLogPanelIsSearchable() throws Exception {
    Browser visitor = new Browser(server.port, "example.org");
    visitor.get("/needle");
    visitor.get("/haystack");

    Browser.Page all = admin.get("/admin/system/logs/results");
    assertTrue(all.contains("/needle"));
    assertTrue(all.contains("/haystack"));

    Browser.Page filtered = admin.get("/admin/system/logs/results?q=needle");
    assertTrue(filtered.contains("/needle"));
    assertFalse("the search actually filters", filtered.contains("/haystack"));
    assertFalse("a panel has no shell", filtered.contains("<!doctype html>"));
  }

  @Test
  public void theLogCanShowErrorsOnly() throws Exception {
    // the shielded request is the interesting one: it is refused before the domain would otherwise
    // be known, and it still has to show up on this domain's log
    Browser visitor = new Browser(server.port, "example.org");
    visitor.get("/");
    visitor.get("/wp-login.php");
    Browser.Page errors = admin.get("/admin/system/logs/results?errors=1");
    assertTrue(errors.contains("410"));
    assertFalse(errors.contains("<td class=\"num\">200</td>"));
  }

  @Test
  public void theAdminSectionIsRecordedInTheAccessLogLikeAnythingElse() throws Exception {
    admin.get("/admin/system/analytics");
    admin.get("/admin/people/list");
    assertNotNull(server.accessLog);
    assertTrue("a page shows up as itself", server.accessLog.recent().stream()
        .anyMatch(hit -> hit.uri().equals("/admin/system/analytics") && hit.userId() != null));
    assertTrue("and so does a panel, which is the point of giving it a path",
        server.accessLog.recent().stream().anyMatch(hit -> hit.uri().equals("/admin/people/list")));
  }

  // ---- people ---------------------------------------------------------------------------------------

  @Test
  public void theTwoKindsOfAdminAreTwoDifferentColours() throws Exception {
    // one is a fact about a file on the box and cannot be revoked from in here; the other is a
    // decision somebody made in this UI and can be undone in it. Confusing them wastes an afternoon.
    Browser deputy = new Browser(server.port, "example.org");
    deputy.get("/register");
    deputy.submit(Map.of("email", "deputy@example.com"));
    deputy.submit(Map.of("code", server.mail().lastCodeFor("deputy@example.com")));
    long id = server.auth.forDomain("example.org").users.byEmail("deputy@example.com").id();
    admin.submitToAndFollow("/admin/people", Map.of("user", Long.toString(id), "action", "grant_admin"));

    Browser.Page list = admin.get("/admin/people/list");
    assertTrue("a config admin is red", list.contains("pill red"));
    assertTrue("a promoted admin is purple", list.contains("pill purple"));
  }

  @Test
  public void thePeopleListingFiltersByStateAndByEmail() throws Exception {
    Browser waiting = new Browser(server.port, "example.org");
    waiting.get("/register");
    waiting.submit(Map.of("email", "newcomer@example.com"));
    waiting.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));

    Browser.Page everybody = admin.get("/admin/people/list");
    assertTrue(everybody.contains("newcomer@example.com"));
    assertTrue(everybody.contains("boss@example.com"));

    Browser.Page onlyWaiting = admin.get("/admin/people/list?state=waiting");
    assertTrue(onlyWaiting.contains("newcomer@example.com"));
    assertFalse("the admin is approved, so they are not waiting",
        onlyWaiting.contains("boss@example.com"));

    Browser.Page searched = admin.get("/admin/people/list?q=boss");
    assertTrue(searched.contains("boss@example.com"));
    assertFalse("contains-of, not a listing with a highlight", searched.contains("newcomer@example.com"));

    Browser.Page both = admin.get("/admin/people/list?q=new&state=waiting");
    assertTrue("the filters compose", both.contains("newcomer@example.com"));
    assertFalse(both.contains("boss@example.com"));
  }

  @Test
  public void aProfileIsReviewedOnItsOwnPageWithTheDecisionOnIt() throws Exception {
    Browser newcomer = new Browser(server.port, "example.org");
    newcomer.get("/register");
    newcomer.submit(Map.of("email", "newcomer@example.com"));
    newcomer.submit(Map.of("code", server.mail().lastCodeFor("newcomer@example.com")));
    long id = server.auth.forDomain("example.org").users.byEmail("newcomer@example.com").id();

    Browser.Page review = admin.get("/admin/people/review/" + id);
    assertEquals(200, review.status());
    assertTrue(review.contains("newcomer@example.com"));
    assertTrue(review.contains("Approve"));
    assertTrue(review.contains("Reject and delete"));
    assertTrue("and the harsher one, for somebody not worth reviewing twice",
        review.contains("Reject, delete and ban"));
    assertTrue("turning an account off is not the same as rejecting it", review.contains("Turn off"));
  }

  @Test
  public void turningSomebodyOffIsReversible() throws Exception {
    Browser member = new Browser(server.port, "example.org");
    member.get("/register");
    member.submit(Map.of("email", "member@example.com"));
    member.submit(Map.of("code", server.mail().lastCodeFor("member@example.com")));
    long id = server.auth.forDomain("example.org").users.byEmail("member@example.com").id();

    admin.submitToAndFollow("/admin/people", Map.of("user", Long.toString(id), "action", "disable"));
    assertTrue(server.auth.forDomain("example.org").users.byId(id).disabled());
    assertTrue("and it is visible at a glance",
        admin.get("/admin/people/list?state=disabled").contains("member@example.com"));

    admin.submitToAndFollow("/admin/people", Map.of("user", Long.toString(id), "action", "enable"));
    assertFalse("unlike a rejection, nothing was thrown away",
        server.auth.forDomain("example.org").users.byId(id).disabled());
  }

  @Test
  public void noAdminUrlCarriesAQueryParameterThatChangesAnything() throws Exception {
    // filters are a view; identity is a path; mutations are a POST. Nothing else belongs in a query.
    for (String path : PAGES) {
      String body = admin.get(path).body();
      assertFalse(path + " should not link to a mutation with a query parameter",
          body.contains("?action=") || body.contains("&action=")
              || body.contains("?edit=") || body.contains("?review=") || body.contains("?done="));
    }
  }
}
