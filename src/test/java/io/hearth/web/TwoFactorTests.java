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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * `password_and_code`: something they know, then something sent to the address they proved.
 *
 * The mode exists because passwordless -- the default -- makes the mailbox the whole credential, and
 * a community that has grown into something worth attacking wants a second one. What makes it real
 * rather than decorative is that the password alone hands out nothing: no session cookie, no
 * redirect, no state that survives the request. The code is a separate proof, and until it comes
 * back nobody is signed in.
 */
public class TwoFactorTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir()
        .domain("open.test", "{\"name\":\"Open\",\"admin_emails\":[\"boss@example.com\"]}")
        .domain("strict.test", "{\"name\":\"Strict\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"login_security\":{\"mode\":\"password_and_code\",\"password-min-length\":8}}");
    server = TestServer.ofConfigs(configs.file());
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

  private Browser strict() {
    return new Browser(server.port, "strict.test");
  }

  /** sign up on the two-factor domain: a code to prove the address, and a password chosen with it */
  private Browser signUp(String email, String password) throws Exception {
    Browser browser = strict();
    browser.get("/register");
    Browser.Page sent = browser.submit(Map.of("email", email));
    assertTrue("a password site collects one with the code", sent.wants("password"));
    Browser.Page done = browser.submit(
        Map.of("code", server.mail().lastCodeFor(email), "password", password));
    assertEquals(303, done.status());
    assertNotNull(browser.cookie("hearth_session"));
    return browser;
  }

  @Test
  public void thePolicyIsWhatTheDomainAskedFor() {
    assertTrue(server.auth.forDomain("strict.test").security.requiresSecondFactor());
    assertTrue(server.auth.forDomain("strict.test").security.usesPasswords());
    assertFalse("and the neighbouring domain is untouched",
        server.auth.forDomain("open.test").security.requiresSecondFactor());
  }

  @Test
  public void signingInTakesThePasswordAndThenACode() throws Exception {
    Browser browser = signUp("boss@example.com", "correct-horse");
    browser.forgetCookies();
    server.mail().clear();

    Browser.Page form = browser.get("/login");
    assertTrue(form.wants("password"));

    Browser.Page afterPassword = browser.submit(
        Map.of("email", "boss@example.com", "password", "correct-horse"));
    assertEquals("the right password is not a sign-in, it is the first half of one",
        200, afterPassword.status());
    assertTrue(afterPassword.contains("One more step"));
    assertNull("no session yet", browser.cookie("hearth_session"));

    assertEquals("and the second factor was mailed", 1, server.mail().count());
    assertEquals("two_factor", server.mail().last().flow());

    Browser.Page done = browser.submit(
        Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    assertEquals(303, done.status());
    assertNotNull("both proofs given, now there is a session", browser.cookie("hearth_session"));
    assertTrue(browser.get("/").contains("You are signed in as boss@example.com"));
  }

  @Test
  public void theRightPasswordWithTheWrongCodeIsNotASignIn() throws Exception {
    Browser browser = signUp("boss@example.com", "correct-horse");
    browser.forgetCookies();

    browser.get("/login");
    browser.submit(Map.of("email", "boss@example.com", "password", "correct-horse"));
    Browser.Page refused = browser.submit(Map.of("code", "000000"));
    assertEquals("a refused submission comes back as the form, not as a page that worked",
        400, refused.status());
    assertNull("a wrong second factor leaves them where they were",
        browser.cookie("hearth_session"));
  }

  @Test
  public void theWrongPasswordNeverReachesTheSecondFactor() throws Exception {
    signUp("boss@example.com", "correct-horse");
    server.mail().clear();

    Browser attacker = strict();
    attacker.get("/login");
    Browser.Page refused = attacker.submit(
        Map.of("email", "boss@example.com", "password", "wrong-horse"));
    assertEquals(400, refused.status());
    assertTrue(refused.contains("did not match"));
    assertNull(attacker.cookie("hearth_session"));
    assertEquals("mailing a code on a wrong password would make the mailbox a doorbell for guessers",
        0, server.mail().count());
  }

  @Test
  public void anUnknownAddressLooksExactlyLikeAWrongPassword() throws Exception {
    // the second factor must not become an account-enumeration oracle: if a real address paused for
    // a code and an unknown one did not, the pause would answer the question
    signUp("boss@example.com", "correct-horse");

    Browser probe = strict();
    probe.get("/login");
    Browser.Page unknown = probe.submit(
        Map.of("email", "nobody@example.com", "password", "correct-horse"));

    Browser other = strict();
    other.get("/login");
    Browser.Page wrongPassword = other.submit(
        Map.of("email", "boss@example.com", "password", "wrong-horse"));

    assertEquals(unknown.status(), wrongPassword.status());
    assertTrue(unknown.contains("did not match"));
    assertTrue(wrongPassword.contains("did not match"));
  }

  @Test
  public void thePolicyReadsBackInOneSentence() {
    assertTrue(server.auth.forDomain("strict.test").security.describe().contains("password_and_code"));
  }
}
