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


  /** the welcome flow went with the survey, so a name is set on /self rather than at /welcome */
  private Browser signedIn(String email, String name) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
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


  // ---- the refusals ---------------------------------------------------------------------------------



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


  // ---- a session that stops working -----------------------------------------------------------------




}
