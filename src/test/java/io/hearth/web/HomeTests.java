package io.hearth.web;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The dashboard, and the two things it forced: a front page that is not it, and a gate that works.
 *
 * `/` is the community's own website and `/home` is the page about you. Most of this file is that
 * distinction holding -- who lands where, what is on it, and what somebody who has not been let in
 * yet can see, which turned out to be rather more than anybody intended.
 */
public class HomeTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
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

  private Browser approvedMember(String email, String name) throws Exception {
    Browser member = signIn(email);
    member.get("/welcome");
    member.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "", "about", ""));
    // all the way to the last screen, so the dashboard has nothing to nag them about
    member.get("/welcome?step=3");
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
    return member;
  }

  // ---- the front page and the dashboard are different pages ---------------------------------------

  @Test
  public void signingInLandsOnTheDashboardAndTheFrontPageStaysTheFrontPage() throws Exception {
    Browser member = approvedMember("ana@example.com", "Ana");

    Browser.Page home = member.get("/home");
    assertEquals(200, home.status());
    assertTrue(home.contains("Hello, Ana"));

    Browser.Page front = member.get("/");
    assertEquals("the community's own page is untouched by any of this", 200, front.status());
    assertTrue(front.contains("Hello, world."));
    assertFalse("and it is not the dashboard", front.contains("Waiting for you"));
  }

  @Test
  public void theBarPointsHomeAtDifferentPlacesForDifferentPeople() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    assertTrue("a stranger's Home is the front page",
        stranger.get("/").contains("href=\"/\""));

    Browser member = approvedMember("ana@example.com", "Ana");
    assertTrue("a member's Home is the dashboard",
        member.get("/home").contains("href=\"/home\""));
  }

  @Test
  public void signingOutGoesToTheFrontPageRatherThanAPageThatWouldBounceThem() throws Exception {
    Browser member = approvedMember("ana@example.com", "Ana");
    Browser.Page out = member.submitTo("/logout", Map.of());
    assertEquals(303, out.status());
    assertEquals("/", out.location());
  }

  @Test
  public void thereIsNoDashboardForNobody() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/home");
    assertEquals(303, page.status());
    assertEquals("/login?next=%2Fhome", page.location());
  }

  // ---- what is on it -------------------------------------------------------------------------------

  @Test
  public void itLeadsWithWhatIsWaitingAndSaysNothingWhenNothingIs() throws Exception {
    Browser member = approvedMember("ana@example.com", "Ana");
    Browser.Page quiet = member.get("/home");
    assertFalse("an empty to-do panel is a panel people stop reading",
        quiet.contains("Waiting for you"));

    admin.get("/admin/survey/new");
    admin.submitTo("/admin/survey", Map.of("action", "save", "prompt", "Why did you join?",
        "kind", "free", "options", "", "position", "0", "min", "1", "max", "5", "published", "on"));
    assertTrue(server.auth.forDomain("example.org").survey.settle(5000));

    Browser.Page busy = member.get("/home");
    assertTrue(busy.contains("Waiting for you"));
    assertTrue(busy.contains("One question is waiting"));
    assertTrue(busy.contains("href=\"/survey\""));
  }

  @Test
  public void anUnfinishedWelcomeIsTheFirstThingItAsksFor() throws Exception {
    // approved, but never told anybody their name -- which is the state the dashboard exists to
    // catch, because somebody who jumped out of the welcome has no other reminder
    Browser member = signIn("ana@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));

    Browser.Page page = member.get("/home");
    assertTrue(page.contains("Say what we should call you"));
    assertTrue(page.contains("href=\"/welcome\""));
  }

  @Test
  public void theConversationsAreTheOnesTheyAreIn() throws Exception {
    Browser ana = approvedMember("ana@example.com", "Ana");
    Browser bo = approvedMember("bo@example.com", "Bo");

    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Bread day",
        "body", "Who is baking?"));
    bo.get("/board");
    bo.submitTo("/board", Map.of("action", "post", "title", "Chairs",
        "body", "We need more of them."));

    Browser.Page page = ana.get("/home");
    assertTrue("the one she started", page.contains("Bread day"));
    assertTrue("and the rest of the board is underneath rather than absent",
        page.contains("Elsewhere on the board"));
    assertTrue(page.contains("Chairs"));

    Browser.Page his = bo.get("/home");
    assertTrue(his.contains("Conversations you are in"));
    assertTrue(his.contains("Chairs"));
  }

  @Test
  public void theNextWeekIsSevenDaysAndSaysWhetherYouAnswered() throws Exception {
    LocalDate today = LocalDate.now();
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Supper",
        "body", "Bring a chair.", "location", "The hall", "place_id", "",
        "starts_on", today.plusDays(2).toString(), "ends_on", today.plusDays(2).toString(),
        "start_time", "7pm", "capacity", "", "published", "on"));
    admin.get("/admin/calendar/new");
    admin.submitTo("/admin/calendar", Map.of("action", "save", "title", "Much later",
        "body", "", "location", "", "place_id", "",
        "starts_on", today.plusDays(30).toString(), "ends_on", today.plusDays(30).toString(),
        "start_time", "", "capacity", "", "published", "on"));

    Browser member = approvedMember("ana@example.com", "Ana");
    Browser.Page page = member.get("/home");
    assertTrue(page.contains("Supper"));
    assertFalse("a month out is not something anybody can act on today",
        page.contains("Much later"));
    assertTrue(page.contains("no answer yet"));
    assertTrue("and it is on the list of things to do", page.contains("needs an answer"));
  }

  // ---- the gate ------------------------------------------------------------------------------------

  @Test
  public void somebodyWaitingForApprovalSeesNoneOfTheCommunity() throws Exception {
    // This was wrong, and quietly: the gate used to let through anything with a configured path,
    // which is every surface there is.
    Browser waiting = signIn("newcomer@example.com");
    for (String path : new String[]{"/", "/home", "/board", "/events", "/places"}) {
      Browser.Page page = waiting.get(path);
      assertTrue(path + " should be behind the approval gate",
          page.contains("Waiting for approval"));
    }
    // ...and what they can still do is the three things approval is decided from
    assertTrue(waiting.get("/self").contains("display_name"));
    assertTrue(waiting.get("/welcome").contains("call you"));
    assertEquals(200, waiting.get("/survey").status());
    assertFalse(waiting.get("/survey").contains("Waiting for approval"));
  }

  // ---- light and dark ------------------------------------------------------------------------------

  @Test
  public void thePaletteIsLightUntilSomethingSaysOtherwise() throws Exception {
    String page = new Browser(server.port, "example.org").get("/").body();
    assertTrue("light is what a page is", page.contains(":root{color-scheme:light;"));
    assertFalse("never the browser's guess about somebody's laptop",
        page.contains("prefers-color-scheme"));
    assertTrue("and dark is an attribute somebody can turn on",
        page.contains(":root[data-theme=\"dark\"]{color-scheme:dark;"));
    assertTrue("with a switch in the bar", page.contains("data-theme-toggle"));
  }

  @Test
  public void theSwitchIsAFileEverybodyCanFetch() throws Exception {
    // it has to run before the first paint on every page, including the ones with no nonce to give
    // an inline script, and including pages a stranger sees
    try (Http http = new Http()) {
      Http.Response res = http.get(server.port, "example.org", "/~theme.js");
      assertEquals(200, res.status);
      assertTrue(res.header("content-type").startsWith("text/javascript"));
      assertTrue(res.body.contains("localStorage"));
      assertTrue("and it is cached rather than fetched per page",
          res.header("cache-control").contains("max-age"));
    }
  }
}
