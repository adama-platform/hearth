package io.hearth.content;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The community's own pages for what it already holds.
 *
 * What is being proved here is mostly about reach rather than about markup. A listing that renders
 * the same thing for a stranger and for a member is a way around the page it lists, and a page whose
 * address has a hole in it is a router -- so the audience is checked where the page is built, and
 * the pattern matches one token and nothing else.
 */
public class FeedPageTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com", "The Boss");
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

  // ---- events ------------------------------------------------------------------------------------

  @Test
  public void aListingIsTheOperatorsShapeFilledIn() throws Exception {
    event("Supper club", 10, false);
    event("Bring a book", 12, false);
    page("/whats-on/{{page}}", "event_listing",
        "<h1>What is on</h1>{{#events}}<article>{{title}} on {{starts_on}}</article>{{/events}}");

    Browser.Page listing = admin.get("/whats-on");
    assertEquals(200, listing.status());
    assertTrue(listing.contains("Supper club"));
    assertTrue(listing.contains("Bring a book"));
    assertTrue("the operator's markup, not ours", listing.contains("<article>"));
  }

  @Test
  public void pageOneIsAlwaysTheBarePath() throws Exception {
    for (int k = 0; k < 5; k++) {
      event("Event " + k, 3 + k, false);
    }
    page("/whats-on/{{page}}", "event_listing",
        "{{#events}}[{{title}}]{{/events}}"
            + "{{#pagination.has_next}}next={{pagination.next_url}}{{/pagination.has_next}}"
            + " page={{pagination.page}} of {{pagination.pages}} count={{pagination.count}}",
        "page_size", "2");

    Browser.Page first = admin.get("/whats-on");
    assertTrue(first.body(), first.contains("page=1 of 3 count=5"));
    assertTrue(first.contains("next=/whats-on/2"));
    assertTrue(first.contains("[Event 0]"));
    assertFalse("two rows, not five", first.contains("[Event 2]"));

    Browser.Page second = admin.get("/whats-on/2");
    assertTrue(second.contains("[Event 2]"));
    assertTrue(second.contains("page=2 of 3"));

    // a page number past the end falls through to whatever else answers that address -- today the
    // community's own front page -- rather than rendering an empty listing that says nothing
    assertFalse("and page nine of a three-page listing is a link that went stale",
        admin.get("/whats-on/9").contains("[Event"));
  }

  @Test
  public void thePaginationObjectCarriesTheNextRowsId() throws Exception {
    long first = event("First", 4, false);
    long second = event("Second", 5, false);
    page("/whats-on/{{page}}", "event_listing",
        "next_id={{pagination.next_id}}", "page_size", "1");
    assertTrue(admin.get("/whats-on").contains("next_id=" + second));
    assertTrue("and nothing at the end, because there is no next row",
        admin.get("/whats-on/2").contains("next_id="));
    assertTrue(first > 0);
  }

  @Test
  public void oneEventHasItsOwnPatternedAddress() throws Exception {
    long id = event("Supper club", 10, false);
    page("/thing/{{event_id}}", "event", "<h1>{{title}}</h1><p>{{time}}</p>{{{body_html}}}");
    Browser.Page one = admin.get("/thing/" + id);
    assertEquals(200, one.status());
    assertTrue(one.contains("<h1>Supper club</h1>"));
    assertTrue("the body comes through as html rather than as markdown",
        one.contains("Bring a chair"));
    assertFalse("and an id that is nobody's renders nothing",
        admin.get("/thing/99999").contains("<h1>Supper club</h1>"));
  }

  @Test
  public void theHoleTakesOneSegmentAndNoMore() throws Exception {
    long id = event("Supper club", 10, false);
    page("/thing/{{event_id}}", "event", "{{title}}");
    assertTrue(admin.get("/thing/" + id).contains("Supper club"));
    assertFalse("a slash is not part of the hole",
        admin.get("/thing/" + id + "/extra").contains("Supper club"));
    assertFalse("and neither is nothing at all", admin.get("/thing/").contains("Supper club"));
  }

  // ---- who may read one --------------------------------------------------------------------------

  @Test
  public void aStrangerSeesOnlyWhatTheCommunityOpened() throws Exception {
    event("Members only", 10, false);
    long open = event("Anybody welcome", 11, true);
    page("/whats-on/{{page}}", "event_listing", "{{#events}}[{{title}}]{{/events}}");

    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/whats-on");
      assertEquals("the page itself is public, because an open event is public", 200, answer.status);
      assertTrue(answer.body.contains("[Anybody welcome]"));
      assertFalse("and everything else is simply not on it",
          answer.body.contains("[Members only]"));
    }
    assertTrue("while a member sees both", admin.get("/whats-on").contains("[Members only]"));
    assertTrue(open > 0);
  }

  @Test
  public void oneEventThatIsNotOpenIsNotReadableByAStranger() throws Exception {
    long shut = event("Members only", 10, false);
    long open = event("Anybody welcome", 11, true);
    page("/thing/{{event_id}}", "event", "{{title}}");
    try (Http http = new Http()) {
      assertFalse(http.get(server.port, "example.org", "/thing/" + shut).body
          .contains("Members only"));
      assertTrue(http.get(server.port, "example.org", "/thing/" + open).body
          .contains("Anybody welcome"));
    }
  }

  @Test
  public void theAddressBookAndTheDirectoryAskYouToSignIn() throws Exception {
    page("/venues/{{page}}", "place_listing", "{{#places}}[{{name}}]{{/places}}");
    page("/people/{{page}}", "member_listing", "{{#members}}[{{name}}]{{/members}}");
    try (Http http = new Http()) {
      Http.Response places = http.get(server.port, "example.org", "/venues");
      assertEquals("a 404 would be a lie about a page on the community's own navigation",
          303, places.status);
      assertTrue("and it carries the errand", places.header("location").contains("next=%2Fvenues"));
      assertEquals(303, http.get(server.port, "example.org", "/people").status);
    }
  }

  @Test
  public void aMemberListingNamesPeopleAndNeverAddressesThem() throws Exception {
    signIn("ana@example.com", "Ana Rivera");
    page("/people/{{page}}", "member_listing", "{{#members}}[{{name}} {{where}}]{{/members}}");
    Browser.Page listing = admin.get("/people");
    assertTrue(listing.contains("[Ana Rivera"));
    assertFalse("a member list is the easiest thing in the world to screenshot",
        listing.contains("ana@example.com"));
  }

  @Test
  public void onePersonIsReachableThroughTheirNumber() throws Exception {
    signIn("ana@example.com", "Ana Rivera");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    page("/who/{{member_id}}", "member", "<h1>{{name}}</h1>");
    assertTrue(admin.get("/who/" + id).contains("<h1>Ana Rivera</h1>"));
  }

  @Test
  public void aPlaceListingShowsWhatIsPublished() throws Exception {
    admin.get("/admin/places/new");
    admin.submitTo("/admin/places", Map.of("action", "save", "type_slug", "unsorted",
        "name", "The Oak", "slug", "the-oak", "address", "1 High Street", "body", "The pub.",
        "published", "on"));
    admin.submitTo("/admin/places", Map.of("action", "save", "type_slug", "unsorted",
        "name", "A draft", "slug", "a-draft", "address", "", "body", ""));
    page("/venues/{{page}}", "place_listing", "{{#places}}[{{name}} at {{address}}]{{/places}}");
    Browser.Page listing = admin.get("/venues");
    assertTrue(listing.body(), listing.contains("[The Oak at 1 High Street]"));
    assertFalse("a draft is not an entry", listing.contains("[A draft"));
  }

  // ---- how a listing is ordered ---------------------------------------------------------------

  @Test
  public void aPlaceListingIsAlphabeticalUnlessSomebodySaysOtherwise() throws Exception {
    place("The Oak", "the-oak");
    place("Ashfield Hall", "ashfield");
    place("Riverside", "riverside");
    page("/venues/{{page}}", "place_listing", "{{#places}}[{{name}}]{{/places}}");
    // a directory somebody looks things up in has one useful order, and it is the predictable one
    assertTrue(admin.get("/venues").body()
        .contains("[Ashfield Hall][Riverside][The Oak]"));
  }

  @Test
  public void aPlaceListingCanBeNarrowedToOneKindOrLeftAtEveryKind() throws Exception {
    admin.get("/admin/places/kinds/new");
    admin.submitTo("/admin/places/kinds", Map.of("action", "save", "slug", "ranch",
        "label", "Ranch", "plural", "Ranches", "description", "", "published", "on"));
    place("The Oak", "the-oak");
    admin.get("/admin/places/new");
    admin.submitTo("/admin/places", Map.of("action", "save", "type_slug", "ranch",
        "name", "Oak Hill", "slug", "oak-hill", "address", "", "body", "", "published", "on"));

    page("/venues/{{page}}", "place_listing", "{{#places}}[{{name}}]{{/places}}",
        "place_kind", "ranch");
    Browser.Page onlyRanches = admin.get("/venues");
    assertTrue(onlyRanches.contains("[Oak Hill]"));
    assertFalse("a community with two kinds wants two pages, not one page of both",
        onlyRanches.contains("[The Oak]"));

    long id = server.auth.forDomain("example.org").site.store().byUri("/venues/{{page}}").id();
    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/venues/{{page}}", "title", "A feed", "kind", "place_listing",
        "template_name", "", "nav_folder", "", "place_kind", "*",
        "body", "{{#places}}[{{name}}]{{/places}}", "published", "on"));
    assertTrue("and * means it meant everything rather than having forgotten to choose",
        admin.get("/venues").contains("[The Oak]"));
  }

  @Test
  public void aMemberListingCanBeAlphabeticalOrByWhenPeopleJoined() throws Exception {
    signIn("zoe@example.com", "Zoe Adams");
    signIn("ana@example.com", "Ana Rivera");
    page("/people/{{page}}", "member_listing", "{{#members}}[{{name}}]{{/members}}");
    assertTrue(admin.get("/people").body().contains("[Ana Rivera]"));

    long id = server.auth.forDomain("example.org").site.store().byUri("/people/{{page}}").id();
    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/people/{{page}}", "title", "A feed", "kind", "member_listing",
        "template_name", "", "nav_folder", "", "sort", "joined",
        "body", "{{#members}}[{{name}}]{{/members}}", "published", "on"));
    // oldest first: "who has been here longest" is a thing a community says about itself
    assertTrue(admin.get("/people").body().indexOf("[The Boss]")
        < admin.get("/people").body().indexOf("[Zoe Adams]"));
  }

  @Test
  public void theEditorSaysTheAddressRuleForWhicheverKindIsChosen() throws Exception {
    Browser.Page form = admin.get("/admin/content/new");
    assertTrue("a rule per kind, rather than a paragraph covering all six",
        form.contains("data-uri-rule="));
    assertTrue(form.contains("must contain {{member_id}}"));
    assertTrue(form.contains("page one is always the bare path"));
  }

  // ---- the page it is built from ------------------------------------------------------------------

  @Test
  public void aPageSomebodyWroteBeatsAPatternThatWantedTheSameAddress() throws Exception {
    event("Supper club", 10, false);
    page("/whats-on/{{page}}", "event_listing", "the listing");
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/whats-on",
        "title", "Hand written", "kind", "html", "template_name", "", "nav_folder", "",
        "body", "the page somebody wrote", "published", "on"));
    assertTrue(admin.get("/whats-on").contains("the page somebody wrote"));
  }

  @Test
  public void aPageForOneRowRefusesToBeSavedWithNoHoleInIt() throws Exception {
    admin.get("/admin/content/new");
    Browser.Page done = admin.submitToAndFollow("/admin/content", Map.of("action", "save",
        "uri", "/thing", "title", "One event", "kind", "event", "template_name", "",
        "nav_folder", "", "body", "{{title}}", "published", "on"));
    assertTrue(done.body(), done.contains("needs {{event_id}}"));
  }

  @Test
  public void anUnpublishedShapeAnswersNothing() throws Exception {
    event("Supper club", 10, false);
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", "/whats-on/{{page}}",
        "title", "What is on", "kind", "event_listing", "template_name", "", "nav_folder", "",
        "body", "{{#events}}[{{title}}]{{/events}}"));
    assertFalse("a shape nobody published answers nothing",
        admin.get("/whats-on").contains("[Supper club]"));
  }

  // ---- caching ------------------------------------------------------------------------------------

  @Test
  public void addingAnEventChangesEveryListingThatCouldShowIt() throws Exception {
    event("Supper club", 10, false);
    page("/whats-on/{{page}}", "event_listing", "{{#events}}[{{title}}]{{/events}}");
    assertTrue(admin.get("/whats-on").contains("[Supper club]"));

    event("Bring a book", 12, false);
    assertTrue("a listing changes whenever anything it lists changes",
        admin.get("/whats-on").contains("[Bring a book]"));
  }

  @Test
  public void editingTheShapeTakesEffectAtOnce() throws Exception {
    event("Supper club", 10, false);
    long id = page("/whats-on/{{page}}", "event_listing", "first: {{#events}}{{title}}{{/events}}");
    assertTrue(admin.get("/whats-on").contains("first: Supper club"));

    admin.get("/admin/content/edit/" + id);
    admin.submitTo("/admin/content", Map.of("action", "save", "id", Long.toString(id),
        "uri", "/whats-on/{{page}}", "title", "What is on", "kind", "event_listing",
        "template_name", "", "nav_folder", "",
        "body", "second: {{#events}}{{title}}{{/events}}", "published", "on"));
    assertTrue(admin.get("/whats-on").contains("second: Supper club"));
  }

  @Test
  public void whatAStrangerSawIsNotWhatAMemberGets() throws Exception {
    // the audience is part of the cache key: an anonymous request warming the cache and a member
    // then getting that page would be the whole point of the check, cached away
    event("Members only", 10, false);
    event("Anybody welcome", 11, true);
    page("/whats-on/{{page}}", "event_listing", "{{#events}}[{{title}}]{{/events}}");
    try (Http http = new Http()) {
      assertFalse(http.get(server.port, "example.org", "/whats-on").body
          .contains("[Members only]"));
    }
    assertTrue(admin.get("/whats-on").contains("[Members only]"));
  }

  // ---- plumbing ------------------------------------------------------------------------------------

  /** an event, days from now, optionally open to anybody */
  private long event(String title, int inDays, boolean open) throws Exception {
    LocalDate day = LocalDate.now().plusDays(inDays);
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", title,
        "body", "Bring a chair.", "location", "The hall", "place_id", "",
        "starts_on", day.toString(), "ends_on", day.toString(), "start_time", "7pm",
        "capacity", "", "published", "on"));
    long id = server.auth.forDomain("example.org").calendar.all(100).stream()
        .filter(row -> row.title().equals(title)).findFirst().orElseThrow().id();
    if (open) {
      server.auth.forDomain("example.org").calendar.openToPublic(id, true, null);
    }
    return id;
  }

  private void place(String name, String slug) throws Exception {
    admin.get("/admin/places/new");
    admin.submitTo("/admin/places", Map.of("action", "save", "type_slug", "unsorted",
        "name", name, "slug", slug, "address", "", "body", "", "published", "on"));
  }

  private long page(String uri, String kind, String body, String... fields) throws Exception {
    LinkedHashMap<String, String> form = new LinkedHashMap<>();
    form.put("action", "save");
    form.put("uri", uri);
    form.put("title", "A feed");
    form.put("kind", kind);
    form.put("template_name", "");
    form.put("nav_folder", "");
    form.put("body", body);
    form.put("published", "on");
    for (int k = 0; k + 1 < fields.length; k += 2) {
      form.put(fields[k], fields[k + 1]);
    }
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", form);
    return server.auth.forDomain("example.org").site.store().byUri(uri).id();
  }

  private Browser signIn(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    browser.get("/welcome");
    browser.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "", "about", ""));
    if (!email.startsWith("boss")) {
      long id = server.auth.forDomain("example.org").users.byEmail(email).id();
      admin.get("/admin/people");
      admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
    }
    return browser;
  }
}
