package io.hearth.web;

import io.hearth.auth.SessionRecord;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Being refused for want of a session, and getting back to where you were going.
 *
 * <b>A refusal that loses the destination is a refusal that costs somebody the errand.</b> Somebody
 * follows a link to a thread, or comes back to a bookmark a week later with a lapsed session, and
 * what happens next decides whether they read the thread or give up: a sign-in form that returns
 * them to it is a two second interruption, and a sign-in form that drops them on a home page is a
 * dead end with no clue what they were looking at.
 *
 * So this file walks every path that can refuse for want of a session and asserts two halves:
 * <b>the bounce carries where they were going</b>, and <b>the sign-in honours it</b> -- through the
 * whole two-step flow, across a wrong code, across switching between the sign-in and sign-up forms,
 * and with the refusals from {@link Landing} still refusing.
 *
 * The refusals are as much the point as the returns. `?next=` is the classic open redirect, and the
 * rule it obeys is that a value which is not a plain path on this site is <b>dropped</b> rather than
 * repaired -- and that approval outranks all of it, because an unapproved person has nothing they
 * could usefully be returned to.
 */
public class AuthReturnTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signedIn("boss@example.com", "The Boss");
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

  private Browser signedIn(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    browser.get("/welcome");
    browser.submitTo("/welcome",
        Map.of("action", "name", "display_name", name, "location", "", "about", ""));
    browser.get("/welcome?step=3");
    return browser;
  }

  private Browser approved(String email, String name) throws Exception {
    Browser member = signedIn(email, name);
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));
    return member;
  }

  /** sign in an existing account, from wherever they were sent, and say where they landed */
  private String signInFrom(Browser browser, String email, String bounceLocation) throws Exception {
    browser.get(bounceLocation);
    browser.submit(Map.of("email", email));
    Browser.Page done = browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    assertEquals(303, done.status());
    return done.location();
  }

  // ---- the bounce carries the destination ----------------------------------------------------------

  @Test
  public void everyPathThatNeedsASessionSaysWhereYouWereGoing() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    String[][] cases = {
        {"/home", "/login?next=%2Fhome"},
        {"/self", "/login?next=%2Fself"},
        {"/survey", "/login?next=%2Fsurvey"},
        {"/welcome", "/login?next=%2Fwelcome"},
        {"/members", "/login?next=%2Fmembers"},
        {"/board", "/login?next=%2Fboard"},
        {"/events", "/login?next=%2Fevents"},
        {"/places", "/login?next=%2Fplaces"},
    };
    for (String[] one : cases) {
      Browser.Page page = stranger.get(one[0]);
      assertEquals(one[0] + " should bounce", 303, page.status());
      assertEquals(one[0] + " should say where it was going", one[1], page.location());
    }
  }

  @Test
  public void theQueryIsPartOfTheAddressAndComesBackWithIt() throws Exception {
    // "/survey" and "/survey?all=1" are different pages to the person looking at them, and coming
    // back to nearly the right one is the kind of wrongness nobody reports and everybody notices
    Browser stranger = new Browser(server.port, "example.org");
    assertEquals("/login?next=%2Fsurvey%3Fall%3D1", stranger.get("/survey?all=1").location());
    assertEquals("/login?next=%2Fboard%2F7", stranger.get("/board/7").location());
    assertEquals("/login?next=%2Fmembers%2F3", stranger.get("/members/3").location());
  }

  @Test
  public void aDeepLinkSurvivesTheWholeSignInAndLandsExactlyThere() throws Exception {
    Browser ana = approved("ana@example.com", "Ana");
    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Bread day", "body", "Who is baking?"));
    long postId = server.auth.forDomain("example.org").board.feed(10).get(0).id();
    ana.forgetCookies();

    // a link somebody was sent, opened in a browser with no session
    Browser.Page bounced = ana.get("/board/" + postId);
    assertEquals(303, bounced.status());
    assertEquals("/login?next=%2Fboard%2F" + postId, bounced.location());

    assertEquals("/board/" + postId, signInFrom(ana, "ana@example.com", bounced.location()));
    assertTrue("and the thread is what they were after",
        ana.get("/board/" + postId).contains("Who is baking?"));
  }

  @Test
  public void theDestinationSurvivesTheCodeStepAndAWrongCodeBeforeIt() throws Exception {
    Browser ana = approved("ana@example.com", "Ana");
    ana.forgetCookies();

    ana.get("/login?next=%2Fsurvey%3Fall%3D1");
    ana.submit(Map.of("email", "ana@example.com"));
    Browser.Page wrong = ana.submit(Map.of("code", "000000"));
    assertEquals(400, wrong.status());
    assertTrue("the code page still knows where they were going",
        wrong.contains("next=%2Fsurvey%3Fall%3D1"));

    Browser.Page done = ana.submit(Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    assertEquals("/survey?all=1", done.location());
  }

  @Test
  public void switchingBetweenSigningInAndSigningUpKeepsIt() throws Exception {
    // somebody bounced here may not have an account at all, and the first thing they press is
    // "Create an account" -- which used to drop the destination on the floor
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page login = stranger.get("/login?next=%2Fboard");
    assertTrue("the way to the sign-up form keeps it",
        login.contains("href=\"/register?next=%2Fboard\""));

    Browser.Page register = stranger.get("/register?next=%2Fboard");
    assertTrue("and the way back keeps it",
        register.contains("href=\"/login?next=%2Fboard\""));

    Browser.Page forgot = stranger.get("/forgot-password?next=%2Fboard");
    assertTrue(forgot.contains("href=\"/login?next=%2Fboard\""));
  }

  @Test
  public void registeringFromABouncedLinkLandsThereOnceApproved() throws Exception {
    // the whole journey for somebody with no account: refused, sent to sign in, made an account,
    // approved, and then finally where they were going in the first place
    Browser newcomer = new Browser(server.port, "example.org");
    Browser.Page bounced = newcomer.get("/events");
    assertEquals("/login?next=%2Fevents", bounced.location());

    newcomer.get("/register?next=%2Fevents");
    newcomer.submit(Map.of("email", "ana@example.com"));
    Browser.Page made = newcomer.submit(
        Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    assertEquals("approval outranks the request: there is nothing there for them yet",
        "/welcome", made.location());

    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    admin.get("/admin/people");
    admin.submitTo("/admin/people", Map.of("action", "approve", "user", Long.toString(id)));

    newcomer.forgetCookies();
    assertEquals("/events", signInFrom(newcomer, "ana@example.com", "/login?next=%2Fevents"));
  }

  // ---- the refusals ---------------------------------------------------------------------------------

  @Test
  public void anotherSiteIsNeverWhereSomebodyLands() throws Exception {
    Browser ana = approved("ana@example.com", "Ana");
    ana.forgetCookies();
    String[] hostile = {
        "https%3A%2F%2Fnot-the-community.example%2Flogin",
        "%2F%2Fnot-the-community.example%2Flogin",
        "%2F%5Cnot-the-community.example",
        "javascript%3Aalert(1)",
        "%2Fboard%22%3E%3Cscript%3E",
    };
    for (String next : hostile) {
      Browser browser = new Browser(server.port, "example.org");
      String landed = signInFrom(browser, "ana@example.com", "/login?next=" + next);
      assertEquals(next + " should have been dropped rather than repaired", "/home", landed);
      assertFalse(landed.contains("not-the-community"));
    }
  }

  @Test
  public void anUnapprovedPersonGoesWhereTheyCanActuallyDoSomething() throws Exception {
    // approval outranks `next` -- and a `next` that bypassed the waiting page would also be a way
    // to find out what exists behind it
    Browser newcomer = new Browser(server.port, "example.org");
    newcomer.get("/register?next=%2Fadmin%2Fpeople");
    newcomer.submit(Map.of("email", "ana@example.com"));
    Browser.Page done = newcomer.submit(
        Map.of("code", server.mail().lastCodeFor("ana@example.com")));
    assertEquals("/welcome", done.location());
    assertTrue("and the community is still shut to them",
        newcomer.get("/board").contains("Waiting for approval"));
  }

  @Test
  public void theAdminSectionStillAnswersLikeSomethingThatIsNotThere() throws Exception {
    // invariant 65: the way back is on the page, never in the status code. A 303 here would tell
    // whoever asked that this path is guarded, which is the one thing the 404 exists to withhold.
    Browser stranger = new Browser(server.port, "example.org");
    Browser.Page page = stranger.get("/admin/bans");
    assertEquals(404, page.status());
    assertTrue("but there is something to press", page.contains("/login?next=%2Fadmin%2Fbans"));

    Browser member = approved("ana@example.com", "Ana");
    Browser.Page refused = member.get("/admin/bans");
    assertEquals(404, refused.status());
    assertFalse("somebody signed in is told nothing at all, which is the point",
        refused.contains("next=%2Fadmin"));
  }

  @Test
  public void aRequestLineThisServerWouldNotHaveWrittenCarriesNothing() throws Exception {
    // `here` builds the value from our own request line, which is exactly the assumption that turns
    // somebody else's URL into a header injection -- so it goes through the same refusal as a
    // `next` that arrived from outside, and an address that cannot be echoed carries nothing at all
    String raw = io.hearth.testkit.Http.raw(server.port,
        "GET /survey?q=<script> HTTP/1.1\r\nHost: example.org\r\nConnection: close\r\n\r\n");
    assertTrue(raw, raw.startsWith("HTTP/1.1 303"));
    assertTrue("the destination is dropped rather than repaired",
        raw.contains("location: /login\r\n") || raw.contains("Location: /login\r\n"));
  }

  // ---- a session that stops working -----------------------------------------------------------------

  @Test
  public void aSessionRevokedUnderneathSomebodyBouncesWithTheWayBack() throws Exception {
    Browser ana = approved("ana@example.com", "Ana");
    assertEquals(200, ana.get("/survey").status());

    // an admin turns the account off mid-visit; the next page is a refusal like any other
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    server.auth.forDomain("example.org").sessions.revokeAllFor(id);

    Browser.Page page = ana.get("/survey?all=1");
    assertEquals(303, page.status());
    assertEquals("/login?next=%2Fsurvey%3Fall%3D1", page.location());
  }

  @Test
  public void signingOutAndBackInIsAJourneyThatEndsWhereItStarted() throws Exception {
    Browser ana = approved("ana@example.com", "Ana");
    Browser.Page out = ana.submitTo("/logout", Map.of());
    assertEquals("signing out goes to the front page, which needs nobody", "/", out.location());
    assertNull(ana.cookie("hearth_session"));

    Browser.Page bounced = ana.get("/members");
    assertEquals("/login?next=%2Fmembers", bounced.location());
    assertEquals("/members", signInFrom(ana, "ana@example.com", bounced.location()));
  }

  @Test
  public void aSessionCookieThatWasNeverIssuedIsJustSignedOut() throws Exception {
    Browser forged = new Browser(server.port, "example.org");
    forged.setCookie("hearth_session", "not-a-token-anybody-issued");
    Browser.Page page = forged.get("/board");
    assertEquals(303, page.status());
    assertEquals("/login?next=%2Fboard", page.location());

    SessionRecord none = server.auth.forDomain("example.org").sessions
        .resolve("not-a-token-anybody-issued");
    assertNull("and nothing was minted on the way past", none);
  }

  @Test
  public void thePathComesBackEvenWhenTheAccountBehindTheSessionHasGone() throws Exception {
    // a live cookie whose person was deleted: from where somebody is standing that is being signed
    // out, so it answers the same way, including the way back
    Browser ana = approved("ana@example.com", "Ana");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    server.auth.forDomain("example.org").users.delete(id);

    Browser.Page page = ana.get("/home");
    assertEquals(303, page.status());
    assertEquals("/login?next=%2Fhome", page.location());
    assertTrue("and the cookie goes with it, so the next request is honestly anonymous",
        page.setCookie("hearth_session").contains("Max-Age=0"));
  }
}
