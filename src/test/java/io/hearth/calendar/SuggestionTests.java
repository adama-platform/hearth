package io.hearth.calendar;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
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
 * A calendar a community writes to, rather than one published at it.
 *
 * The queue is the whole safety argument for opening this up, so the tests that matter are the ones
 * about the gap: a suggestion is not on the calendar, does not appear to members, and does not
 * become one until somebody with the permission says so.
 */
public class SuggestionTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"calendar\":{\"enabled\":true,\"suggestions\":true}}");
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

  private Calendar calendar() {
    return server.auth.forDomain("example.org").calendar;
  }

  @Test
  public void anybodyCanPutSomethingForwardAndItIsNotOnTheCalendarYet() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Board games",
        "starts_on", "2030-03-14", "start_time", "7pm", "body", "Bring a game"));

    List<Calendar.Event> waiting = calendar().suggestions(10);
    assertEquals(1, waiting.size());
    assertEquals("Board games", waiting.get(0).title());
    assertTrue(waiting.get(0).suggested());
    assertFalse("not announced", waiting.get(0).published());
    assertEquals("and not on the calendar members read", 0,
        calendar().upcoming(java.time.LocalDate.of(2030, 1, 1), 10).size());
  }

  @Test
  public void acceptingOneIsWhatPutsItOnTheCalendar() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Board games",
        "starts_on", "2030-03-14"));
    long id = calendar().suggestions(10).get(0).id();

    assertEquals(200, admin.get("/admin/calendar/suggestions").status());
    admin.submitToAndFollow("/admin/calendar/suggestions",
        Map.of("action", "accept", "id", Long.toString(id)));

    Calendar.Event event = calendar().byId(id);
    assertTrue("accepting publishes it, because accepted-but-invisible is the worst of both",
        event.live());
    assertEquals(1, calendar().upcoming(java.time.LocalDate.of(2030, 1, 1), 10).size());
    assertEquals("and it is gone from the queue", 0, calendar().openSuggestions());
  }

  @Test
  public void decliningKeepsTheRowAndTheReason() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Karaoke",
        "starts_on", "2030-03-14"));
    long id = calendar().suggestions(10).get(0).id();

    admin.submitToAndFollow("/admin/calendar/suggestions", Map.of("action", "decline",
        "id", Long.toString(id), "note", "We already have something that weekend"));

    Calendar.Event event = calendar().byId(id);
    assertNotNull("a queue where things quietly disappear is one nobody uses twice", event);
    assertTrue(event.declined());
    assertEquals("We already have something that weekend", event.decidedNote());
    assertFalse(event.published());
    assertEquals(0, calendar().openSuggestions());
  }

  @Test
  public void somebodyWithoutThePermissionCannotDecide() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Board games",
        "starts_on", "2030-03-14"));
    long id = calendar().suggestions(10).get(0).id();

    assertEquals("a section they may not open answers 404 rather than confirming it exists",
        404, ana.get("/admin/calendar/suggestions").status());
    ana.submitToAndFollow("/admin/calendar/suggestions",
        Map.of("action", "accept", "id", Long.toString(id)));
    assertTrue("and it is still waiting", calendar().byId(id).suggested());
  }

  @Test
  public void suggestionsCanBeSwitchedOffAndThenNothingArrives() throws Exception {
    server.close();
    configs.delete();
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"calendar\":{\"suggestions\":false}}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    Browser ana = member("ana@example.com");

    assertFalse("and the form is not offered", ana.get("/events").contains("Suggest something"));
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Board games",
        "starts_on", "2030-03-14"));
    assertEquals(0, calendar().openSuggestions());
  }

  @Test
  public void aSuggestionWithoutADayIsNotASuggestion() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/events",
        Map.of("action", "suggest", "title", "Someday", "starts_on", ""));
    assertEquals("people turn up on the day it says", 0, calendar().openSuggestions());
    ana.submitToAndFollow("/events",
        Map.of("action", "suggest", "title", "", "starts_on", "2030-03-14"));
    assertEquals(0, calendar().openSuggestions());
  }

  // ---- where it is ------------------------------------------------------------------------------

  @Test
  public void anEventCanBeAtAPlaceTheCommunityAlreadyWroteDown() throws Exception {
    admin.submitToAndFollow("/admin/places/kinds", Map.of("action", "save", "slug", "venue",
        "label", "Venue", "plural", "Venues", "published", "on"));
    admin.submitToAndFollow("/admin/places", Map.of("action", "save", "type_slug", "venue",
        "slug", "the-oak", "name", "The Oak", "address", "12 High Street", "published", "on"));
    long placeId = server.auth.forDomain("example.org").places.bySlug("venue", "the-oak").id();

    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "Games night",
        "starts_on", "2030-03-14", "place", Long.toString(placeId), "location", "back room",
        "published", "on"));

    Calendar.Event event = calendar().all(10).get(0);
    assertEquals(Long.valueOf(placeId), event.placeId());
    assertEquals("and the free text is what goes beside it", "back room", event.location());

    Browser ana = member("ana@example.com");
    Browser.Page page = ana.get("/events/" + event.id());
    assertTrue(page.contains("The Oak"));
    assertTrue("with a link to everything the community wrote down about it",
        page.contains("/places/venue/the-oak"));
    assertTrue(page.contains("back room"));
  }

  @Test
  public void anEventWithNoPlaceIsJustAnAddress() throws Exception {
    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "In the garden",
        "starts_on", "2030-03-14", "place", "", "location", "Ana's garden", "published", "on"));
    Calendar.Event event = calendar().all(10).get(0);
    assertNull(event.placeId());
    assertEquals("Ana's garden", event.location());
    assertTrue(member("ana@example.com").get("/events/" + event.id()).contains("garden"));
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private Browser member(String email) throws Exception {
    Browser browser = signIn(email);
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf(email))));
    return browser;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
