package io.hearth.web;

import io.hearth.testkit.Browser;
import io.hearth.testkit.CapturingMailer;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Signing up and signing in, driven through a browser that keeps cookies.
 *
 * The code is read back out of the mailer, which is exactly what a person does with the terminal on
 * a dev box. Nothing here reaches into PendingCodes or mints a session directly, so a change that
 * broke the delivery, the CSRF token, the cookie attributes, or the redirect would fail a test.
 */
public class AccountFlowTests {
  private Configs configs;
  private TestServer server;
  private Browser browser;

  @Before
  public void setUp() throws Exception {
    // admin_emails uses a wildcard-ish trick here: every test address is listed, so registrations
    // are approved on the spot and these tests stay about the flows rather than about approval.
    // ApprovalTests covers what happens when an address is NOT on the list.
    configs = Configs.dir()
        .domain("example.org", "{\"name\":\"Example Community\",\"admin_emails\":"
            + "[\"owner@example.com\",\"known@example.com\",\"one@example.com\",\"two@example.com\"]}")
        .domain("locked.test", "{\"name\":\"Locked Down\",\"admin_emails\":[\"owner@example.com\"],"
            + "\"login_security\":{\"mode\":\"password\",\"password-min-length\":8}}");
    server = TestServer.ofConfigs(configs.file());
    browser = new Browser(server.port, "example.org");
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

  private CapturingMailer mail() {
    return server.mail();
  }

  /** the whole passwordless signup, as a person does it */
  private Browser.Page registerAs(String email) throws Exception {
    browser.get("/register");
    Browser.Page sent = browser.submit(Map.of("email", email));
    assertEquals(200, sent.status());
    String code = mail().lastCodeFor(email);
    assertNotNull("no code was mailed to " + email, code);
    return browser.submit(Map.of("code", code));
  }

  // ---- the acceptance path -----------------------------------------------------------------

  @Test
  public void registerWithAnEmailedCodeCreatesAnAccountAndSignsIn() throws Exception {
    Browser.Page form = browser.get("/register");
    assertEquals(200, form.status());
    assertTrue(form.wants("email"));
    assertNotNull("the form needs a CSRF token", form.nameFor("csrf"));

    Browser.Page sent = browser.submit(Map.of("email", "owner@example.com"));
    assertEquals(200, sent.status());
    assertTrue("should now be asking for the code", sent.wants("code"));
    assertTrue(sent.contains("owner@example.com"));

    assertEquals(1, mail().count());
    CapturingMailer.Sent message = mail().last();
    assertEquals("register", message.flow());
    assertEquals("owner@example.com", message.email());
    assertEquals("example.org", message.domain());
    assertEquals(6, message.code().length());

    Browser.Page done = browser.submit(Map.of("code", message.code()));
    assertEquals(303, done.status());
    // brand new, with no name on the account yet, so the welcome rather than the home page
    assertEquals("/welcome", done.location());
    assertNotNull("a session cookie should have been set", browser.cookie("hearth_session"));

    Browser.Page home = browser.get("/");
    assertEquals(200, home.status());
    assertTrue(home.contains("You are signed in as owner@example.com"));
    assertTrue("the nav should offer a way out", home.contains("Sign out"));
  }

  @Test
  public void theAccountSurvivesAndCanSignInAgain() throws Exception {
    registerAs("owner@example.com");
    browser.forgetCookies();

    browser.get("/login");
    browser.submit(Map.of("email", "owner@example.com"));
    String code = mail().lastCodeFor("owner@example.com");
    Browser.Page done = browser.submit(Map.of("code", code));
    assertEquals(303, done.status());
    assertTrue(browser.get("/").contains("You are signed in as owner@example.com"));
    assertEquals("login", mail().last().flow());
  }

  @Test
  public void signingOutEndsTheSession() throws Exception {
    registerAs("owner@example.com");
    assertTrue(browser.get("/").contains("Sign out"));

    Browser.Page home = browser.get("/");
    Browser.Page out = browser.submitTo("/logout", Map.of());
    assertEquals(303, out.status());
    assertEquals("/", out.location());
    assertFalse("the session cookie should be cleared", browser.hasCookie("hearth_session"));
    assertFalse(browser.get("/").contains("You are signed in"));
    assertNotNull(home);
  }

  @Test
  public void signingOutRefusesAGetRequest() throws Exception {
    registerAs("owner@example.com");
    Browser.Page out = browser.get("/logout");
    assertEquals(405, out.status());
    // still signed in; a link somebody else's page points at must not end a session
    assertTrue(browser.get("/").contains("You are signed in"));
  }

  // ---- codes -------------------------------------------------------------------------------

  @Test
  public void aWrongCodeIsRejectedWithoutCreatingAnAccount() throws Exception {
    browser.get("/register");
    browser.submit(Map.of("email", "owner@example.com"));
    Browser.Page wrong = browser.submit(Map.of("code", "000000"));
    assertEquals(400, wrong.status());
    assertTrue(wrong.contains("did not match"));
    assertNull(browser.cookie("hearth_session"));
  }

  @Test
  public void aCodeBurnsAfterTooManyWrongGuesses() throws Exception {
    browser.get("/register");
    browser.submit(Map.of("email", "owner@example.com"));
    Browser.Page page = null;
    for (int k = 0; k < 5; k++) {
      page = browser.submit(Map.of("code", "000000"));
    }
    assertEquals(400, page.status());
    assertTrue(page.body(), page.contains("start again"));
    // even the right code is no good now
    String real = mail().lastCodeFor("owner@example.com");
    assertTrue(browser.submit(Map.of("code", real)).contains("start again"));
    assertNull(browser.cookie("hearth_session"));
  }

  @Test
  public void aCodeIsBoundToItsFlow() throws Exception {
    // a handle minted for registration must not be spendable at the sign-in endpoint
    browser.get("/register");
    Browser.Page sent = browser.submit(Map.of("email", "owner@example.com"));
    String handle = sent.mint().get("handle").asText();
    String code = mail().lastCodeFor("owner@example.com");

    // carry the registration handle into the sign-in form by hand
    Browser.Page loginForm = browser.get("/login");
    Browser.Page crossed = browser.submitRaw(loginForm.mint().get("action").asText(), Map.of(
        loginForm.nameFor("csrf"), loginForm.mint().get("csrf").asText(),
        loginForm.nameFor("proof"), Browser.proofOf(loginForm.mint().get("nonce").asText()),
        loginForm.nameFor("signals"), "m:9|k:9|t:0|p:0|s:0|f:1|e:1200",
        loginForm.nameFor("handle"), handle,
        loginForm.nameFor("code"), code));
    assertEquals(400, crossed.status());
    assertNull(browser.cookie("hearth_session"));
  }

  @Test
  public void eachRequestGetsADifferentCode() throws Exception {
    browser.get("/register");
    browser.submit(Map.of("email", "one@example.com"));
    String first = mail().lastCodeFor("one@example.com");
    browser.get("/register");
    browser.submit(Map.of("email", "two@example.com"));
    String second = mail().lastCodeFor("two@example.com");
    assertNotEquals(first, second);
  }

  // ---- enumeration -------------------------------------------------------------------------

  @Test
  public void signingInWithAnUnknownAddressLooksTheSameAsAKnownOne() throws Exception {
    registerAs("known@example.com");
    browser.forgetCookies();
    mail().clear();

    browser.get("/login");
    Browser.Page known = browser.submit(Map.of("email", "known@example.com"));
    browser.get("/login");
    Browser.Page unknown = browser.submit(Map.of("email", "nobody@example.com"));

    assertEquals(known.status(), unknown.status());
    assertTrue(known.wants("code"));
    assertTrue("an unknown address must still be asked for a code", unknown.wants("code"));
    // and no mail actually went to the address with no account
    assertEquals(1, mail().count());
    assertEquals("known@example.com", mail().last().email());
  }

  @Test
  public void registeringAnAddressThatAlreadyExistsJustSignsThemIn() throws Exception {
    registerAs("owner@example.com");
    browser.forgetCookies();

    Browser.Page done = registerAs("owner@example.com");
    assertEquals(303, done.status());
    assertTrue(browser.get("/").contains("You are signed in as owner@example.com"));
  }

  // ---- CSRF and cookies --------------------------------------------------------------------

  @Test
  public void aPostWithNoTicketAtAllIsRefused() throws Exception {
    // a client that posted the field names it guessed, with no minted form behind it
    browser.get("/register");
    Browser.Page forged = browser.submitRaw("/register", Map.of("email", "owner@example.com"));
    assertEquals(400, forged.status());
    assertTrue(forged.contains("expired"));
    assertEquals("no mail should have been sent", 0, mail().count());
  }

  @Test
  public void aPostWithSomebodyElsesCsrfTokenIsRefused() throws Exception {
    Browser.Page page = browser.get("/register");
    var mint = page.mint();
    Browser.Page forged = browser.submitRaw(mint.get("action").asText(), Map.of(
        page.nameFor("csrf"), "not-the-one-in-the-cookie",
        page.nameFor("proof"), Browser.proofOf(mint.get("nonce").asText()),
        page.nameFor("signals"), "m:5|k:5|t:0|p:0|s:0|f:1|e:900",
        page.nameFor("email"), "owner@example.com"));
    assertEquals(400, forged.status());
    assertEquals(0, mail().count());
  }

  @Test
  public void theSessionCookieIsLockedDown() throws Exception {
    Browser.Page done = registerAs("owner@example.com");
    String header = done.setCookie("hearth_session");
    assertNotNull(header);
    assertTrue(header, header.contains("HttpOnly"));
    assertTrue(header, header.contains("SameSite=Lax"));
    assertTrue(header, header.contains("Path=/"));
    // no TLS in developer mode, so Secure would make the browser drop it entirely
    assertFalse(header, header.contains("Secure"));
  }

  @Test
  public void theTokenInTheCookieIsNotWhatIsStored() throws Exception {
    registerAs("owner@example.com");
    String token = browser.cookie("hearth_session");
    assertNotNull(token);
    long sessions = server.auth.forDomain("example.org").sessions.count();
    assertEquals(1, sessions);
    // the raw token must not resolve as a stored hash; only its SHA-256 is on disk
    assertNotNull(server.auth.forDomain("example.org").sessions.resolve(token));
    assertNull(server.auth.forDomain("example.org").sessions.resolve(io.hearth.auth.Tokens.hash(token)));
  }

  // ---- passwords ---------------------------------------------------------------------------

  @Test
  public void aPasswordSiteAsksForOneAtRegistration() throws Exception {
    Browser passworded = new Browser(server.port, "locked.test");
    passworded.get("/register");
    Browser.Page sent = passworded.submit(Map.of("email", "owner@example.com"));
    assertTrue("a password site should collect one with the code", sent.wants("password"));

    String code = mail().lastCodeFor("owner@example.com");
    Browser.Page done = passworded.submit(Map.of("code", code, "password", "correct-horse"));
    assertEquals(303, done.status());
    assertNotNull(passworded.cookie("hearth_session"));
  }

  @Test
  public void aPasswordSiteSignsInWithThePassword() throws Exception {
    Browser passworded = new Browser(server.port, "locked.test");
    passworded.get("/register");
    passworded.submit(Map.of("email", "owner@example.com"));
    passworded.submit(Map.of("code", mail().lastCodeFor("owner@example.com"), "password", "correct-horse"));
    passworded.forgetCookies();

    Browser.Page form = passworded.get("/login");
    assertTrue(form.wants("password"));
    Browser.Page done = passworded.submit(Map.of("email", "owner@example.com", "password", "correct-horse"));
    assertEquals(303, done.status());
    assertNotNull(passworded.cookie("hearth_session"));
  }

  @Test
  public void aWrongPasswordSaysTheSameThingAsAnUnknownAddress() throws Exception {
    Browser passworded = new Browser(server.port, "locked.test");
    passworded.get("/register");
    passworded.submit(Map.of("email", "owner@example.com"));
    passworded.submit(Map.of("code", mail().lastCodeFor("owner@example.com"), "password", "correct-horse"));
    passworded.forgetCookies();

    passworded.get("/login");
    Browser.Page wrong = passworded.submit(Map.of("email", "owner@example.com", "password", "wrong-password"));
    passworded.get("/login");
    Browser.Page unknown = passworded.submit(Map.of("email", "nobody@example.com", "password", "wrong-password"));

    assertEquals(wrong.status(), unknown.status());
    assertTrue(wrong.contains("did not match"));
    assertTrue(unknown.contains("did not match"));
    assertNull(passworded.cookie("hearth_session"));
  }

  @Test
  public void aShortPasswordIsRefused() throws Exception {
    Browser passworded = new Browser(server.port, "locked.test");
    passworded.get("/register");
    passworded.submit(Map.of("email", "owner@example.com"));
    Browser.Page tooShort = passworded.submit(
        Map.of("code", mail().lastCodeFor("owner@example.com"), "password", "short"));
    assertEquals(400, tooShort.status());
    assertTrue(tooShort.contains("at least 8 characters"));
    assertNull(passworded.cookie("hearth_session"));
  }

  @Test
  public void resettingAPasswordEndsEveryOtherSession() throws Exception {
    Browser first = new Browser(server.port, "locked.test");
    first.get("/register");
    first.submit(Map.of("email", "owner@example.com"));
    first.submit(Map.of("code", mail().lastCodeFor("owner@example.com"), "password", "correct-horse"));
    assertTrue(first.get("/").contains("You are signed in"));

    Browser second = new Browser(server.port, "locked.test");
    second.get("/forgot-password");
    Browser.Page asked = second.submit(Map.of("email", "owner@example.com"));
    assertTrue(asked.wants("code"));
    assertEquals("reset", mail().last().flow());
    assertNotNull("a reset should carry a link as well as a code", mail().last().link());

    Browser.Page done = second.submit(
        Map.of("code", mail().lastCodeFor("owner@example.com"), "password", "a-brand-new-one"));
    assertEquals(303, done.status());
    assertNotNull(second.cookie("hearth_session"));
    // the first browser's session was issued before the password changed
    assertFalse("the old session should be gone", first.get("/").contains("You are signed in"));
  }

  // ---- configurable urls -------------------------------------------------------------------

  @Test
  public void theAccountPathsFollowTheConfig() throws Exception {
    Configs custom = Configs.dir().domain("custom.test",
        "{\"name\":\"Custom\",\"admin_emails\":[\"owner@example.com\"],"
            + "\"urls\":{\"register\":\"/join\",\"login\":\"/signin\",\"logout\":\"/signout\"}}");
    try (TestServer other = TestServer.ofConfigs(custom.file())) {
      Browser client = new Browser(other.port, "custom.test");
      assertEquals(200, client.get("/join").status());
      assertEquals(200, client.get("/signin").status());
      // the defaults are not also served
      assertEquals("a community that moved its sign-up form has nothing at the old address",
          404, client.get("/register").status());
      assertNull("and certainly not the form", client.get("/register").mint());

      client.get("/join");
      client.submit(Map.of("email", "owner@example.com"));
      String code = other.mail().lastCodeFor("owner@example.com");
      Browser.Page done = client.submit(Map.of("code", code));
      assertEquals(303, done.status());
      assertTrue(client.get("/").contains("You are signed in"));
    } finally {
      custom.delete();
    }
  }

  @Test
  public void theNavigationChangesWithWhoIsLookingAtIt() throws Exception {
    Browser.Page anonymous = browser.get("/");
    assertTrue(anonymous.contains("Sign in"));
    assertTrue(anonymous.contains("Create an account"));
    assertFalse(anonymous.contains("Sign out"));

    registerAs("owner@example.com");
    Browser.Page signedIn = browser.get("/");
    assertTrue(signedIn.contains("Sign out"));
    assertFalse(signedIn.contains("Create an account"));
  }
}
