package io.hearth.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The parts of the register page that exist to cost a bot a rewrite.
 *
 * Everything here is defence in depth rather than a security boundary -- the approval requirement is
 * the boundary, and it does not care how the account was created. These tests pin the behaviour so
 * that a refactor cannot quietly turn the form back into plain HTML with predictable names.
 */
public class MintedFormTests {
  private Configs configs;
  private TestServer server;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org", "{\"name\":\"Example Community\"}");
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

  private Browser browser() {
    return new Browser(server.port, "example.org");
  }

  // ---- there is no form in the HTML ---------------------------------------------------------

  @Test
  public void thePageContainsNoFormAtAll() throws Exception {
    Browser.Page page = browser().get("/register");
    assertEquals(200, page.status());
    assertFalse("a scraper reading the HTML should find no form", page.contains("<form"));
    assertFalse(page.contains("<input"));
    assertFalse("and certainly no field called email", page.contains("name=\"email\""));
  }

  @Test
  public void thePageSaysSoWithoutJavaScript() throws Exception {
    assertTrue(browser().get("/register").contains("<noscript>"));
  }

  @Test
  public void theFormIsDescribedByABlobTheScriptReads() throws Exception {
    JsonNode mint = browser().get("/register").mint();
    assertNotNull(mint);
    assertTrue(mint.hasNonNull("action"));
    assertTrue(mint.hasNonNull("nonce"));
    assertTrue(mint.get("want").get("email").asBoolean());
    for (String field : new String[]{"email", "code", "password", "handle", "csrf", "proof", "signals", "trap"}) {
      assertTrue("the blob should name " + field, mint.get("f").hasNonNull(field));
    }
  }

  // ---- names differ every time ---------------------------------------------------------------

  @Test
  public void fieldNamesAreDifferentOnEveryPageLoad() throws Exception {
    String first = browser().get("/register").nameFor("email");
    String second = browser().get("/register").nameFor("email");
    String third = browser().get("/register").nameFor("email");
    assertNotEquals("a name harvested from one visit must not work on the next", first, second);
    assertNotEquals(second, third);
  }

  @Test
  public void everyFieldOnOnePageSharesThePrefixButNothingElse() throws Exception {
    Browser.Page page = browser().get("/register");
    String email = page.nameFor("email");
    String code = page.nameFor("code");
    assertNotEquals(email, code);
    assertEquals("the two-letter prefix is per form", email.substring(0, 2), code.substring(0, 2));
    assertTrue("names should look like nothing in particular", email.matches("[a-z]{2}[0-9a-f]{10}"));
  }

  @Test
  public void aNameFromAnotherFormIsIgnored() throws Exception {
    Browser mine = browser();
    Browser.Page theirs = browser().get("/register");
    Browser.Page ours = mine.get("/register");
    // post our ticket, but with the other page's field name for the address
    Map<String, String> body = new LinkedHashMap<>();
    body.put(ours.nameFor("csrf"), ours.mint().get("csrf").asText());
    body.put(ours.nameFor("proof"), Browser.proofOf(ours.mint().get("nonce").asText()));
    body.put(ours.nameFor("signals"), "m:10|k:10|t:0|p:0|s:0|f:1|e:2000");
    body.put(theirs.nameFor("email"), "owner@example.com");
    Browser.Page result = mine.submitRaw(ours.mint().get("action").asText(), body);
    // the field simply is not seen, so it reads as an empty form
    assertEquals(400, result.status());
    assertTrue(result.contains("does not look like an email address"));
    assertEquals(0, server.mail().count());
  }

  // ---- the proof that a script ran -----------------------------------------------------------

  @Test
  public void aSubmissionWithNoProofIsRefused() throws Exception {
    Browser client = browser().withoutProof();
    client.get("/register");
    Browser.Page result = client.submit(Map.of("email", "owner@example.com"));
    assertEquals(400, result.status());
    assertTrue(result.body(), result.contains("JavaScript"));
    assertEquals("nothing should have been mailed", 0, server.mail().count());
  }

  @Test
  public void aSubmissionWithTheWrongProofIsRefused() throws Exception {
    Browser client = browser();
    Browser.Page page = client.get("/register");
    Map<String, String> body = new LinkedHashMap<>();
    body.put(page.nameFor("csrf"), page.mint().get("csrf").asText());
    body.put(page.nameFor("proof"), "definitely-not-it");
    body.put(page.nameFor("signals"), "m:10|k:10|t:0|p:0|s:0|f:1|e:2000");
    body.put(page.nameFor("email"), "owner@example.com");
    assertEquals(400, client.submitRaw(page.mint().get("action").asText(), body).status());
    assertEquals(0, server.mail().count());
  }

  @Test
  public void theProofMatchesWhatThePageScriptWouldCompute() throws Exception {
    JsonNode mint = browser().get("/register").mint();
    // the server's expectation and the shipped algorithm have to agree, or nobody can ever register
    assertEquals(FormMint.proofOf(mint.get("nonce").asText()),
        Browser.proofOf(mint.get("nonce").asText()));
  }

  // ---- interaction counts --------------------------------------------------------------------

  @Test
  public void aSubmissionWithNoInteractionAtAllIsRefused() throws Exception {
    Browser client = browser().withoutSignals();
    client.get("/register");
    Browser.Page result = client.submit(Map.of("email", "owner@example.com"));
    assertEquals(400, result.status());
    assertEquals(0, server.mail().count());
  }

  @Test
  public void allZeroCountsAreRefused() throws Exception {
    Browser client = browser().withSignals("m:0|k:0|t:0|p:0|s:0|f:0|e:0");
    client.get("/register");
    assertEquals(400, client.submit(Map.of("email", "owner@example.com")).status());
    assertEquals(0, server.mail().count());
  }

  @Test
  public void oneEventIsEnough() throws Exception {
    // deliberately not a threshold: somebody who tabs through with a password manager is real
    Browser client = browser().withSignals("m:0|k:0|t:0|p:0|s:0|f:1|e:400");
    client.get("/register");
    assertEquals(200, client.submit(Map.of("email", "owner@example.com")).status());
    assertEquals(1, server.mail().count());
  }

  @Test
  public void touchOnlyCountsAsInteraction() throws Exception {
    Browser client = browser().withSignals("m:0|k:0|t:14|p:6|s:2|f:1|e:5000");
    client.get("/register");
    assertEquals(200, client.submit(Map.of("email", "phone@example.com")).status());
  }

  @Test
  public void theCountsAreKeptOnTheAccount() throws Exception {
    Browser client = browser().withSignals("m:31|k:12|t:0|p:5|s:3|f:2|e:7400");
    client.get("/register");
    client.submit(Map.of("email", "owner@example.com"));
    client.submit(Map.of("code", server.mail().lastCodeFor("owner@example.com")));

    var user = server.auth.forDomain("example.org").users.byEmail("owner@example.com");
    assertNotNull(user);
    assertEquals("the total should be queryable without parsing", 53, user.signupEvents());
    assertEquals("m:31|k:12|t:0|p:5|s:3|f:2|e:7400", user.signupSignals());
    assertNotNull("and where it came from", user.signupIp());
  }

  // ---- the honeypot ----------------------------------------------------------------------------

  @Test
  public void fillingTheHiddenTrapIsRefused() throws Exception {
    Browser client = browser().fillingTheTrap();
    client.get("/register");
    Browser.Page result = client.submit(Map.of("email", "owner@example.com"));
    assertEquals(400, result.status());
    assertEquals(0, server.mail().count());
  }

  // ---- one submission per form -----------------------------------------------------------------

  @Test
  public void aCapturedSubmissionCannotBeReplayed() throws Exception {
    Browser client = browser();
    Browser.Page page = client.get("/register");
    Map<String, String> body = new LinkedHashMap<>();
    body.put(page.nameFor("csrf"), page.mint().get("csrf").asText());
    body.put(page.nameFor("proof"), Browser.proofOf(page.mint().get("nonce").asText()));
    body.put(page.nameFor("signals"), "m:10|k:10|t:0|p:0|s:0|f:1|e:2000");
    body.put(page.nameFor("email"), "owner@example.com");
    String action = page.mint().get("action").asText();

    assertEquals(200, client.submitRaw(action, body).status());
    assertEquals(1, server.mail().count());
    // exactly the same bytes again
    assertEquals(400, client.submitRaw(action, body).status());
    assertEquals("the replay should not have mailed a second code", 1, server.mail().count());
  }

  // ---- the cookie a form depends on --------------------------------------------------------------

  @Test
  public void anotherRequestDoesNotInvalidateAFormAlreadyOnScreen() throws Exception {
    // the bug this exists for: every response used to mint a fresh CSRF token, so the browser's own
    // favicon request overwrote the cookie the open form was carrying, and submitting it said the
    // form had expired -- instantly, on a completely ordinary registration
    Browser client = browser();
    Browser.Page form = client.get("/register");
    client.get("/favicon.ico");
    client.get("/");
    // the form is still on screen; the other requests happened around it
    Browser.Page result = client.submitPage(form, Map.of("email", "owner@example.com"));
    assertEquals("a form must survive the rest of the page loading", 200, result.status());
    assertTrue(result.wants("code"));
    assertEquals(1, server.mail().count());
  }

  @Test
  public void theCsrfCookieIsStablePerBrowserNotPerPage() throws Exception {
    Browser client = browser();
    Browser.Page first = client.get("/register");
    String issued = client.cookie("hearth_csrf");
    assertNotNull(issued);
    client.get("/");
    client.get("/login");
    assertEquals("a second page must not rotate the token", issued, client.cookie("hearth_csrf"));
    // and the token in the first page's blob still matches what the browser holds
    assertEquals(issued, first.mint().get("csrf").asText());
  }

  @Test
  public void aFreshBrowserStillGetsAToken() throws Exception {
    Browser client = browser();
    client.get("/register");
    assertNotNull("somebody arriving with no cookies has to be given one", client.cookie("hearth_csrf"));
  }

  @Test
  public void aJunkCsrfCookieIsReplacedRatherThanTrusted() {
    assertFalse(Cookies.isWellFormed(null));
    assertFalse(Cookies.isWellFormed("short"));
    assertFalse("no injecting attributes through the cookie", Cookies.isWellFormed("aaaaaaaaaaaaaaaa; Path=/"));
    assertFalse(Cookies.isWellFormed("a".repeat(65)));
    assertTrue(Cookies.isWellFormed("d_JWXcP59Tdxibj9ngvqC1hL"));
  }

  @Test
  public void theFaviconCostsNothing() throws Exception {
    Browser client = browser();
    Browser.Page page = client.get("/register");
    assertTrue("the page declares its own icon, so nothing asks for a file",
        page.contains("rel=\"icon\" href=\"data:image/svg+xml"));
    Browser.Page favicon = client.get("/favicon.ico");
    assertEquals("and a browser that asks anyway gets nothing back", 204, favicon.status());
    assertEquals(0, favicon.body().length());
    assertNull("least of all a cookie", favicon.setCookie("hearth_csrf"));
  }

  @Test
  public void anUnknownTicketIsRefused() throws Exception {
    Browser client = browser();
    client.get("/register");
    assertEquals(400, client.submitRaw("/register?ft=nosuchticket", Map.of()).status());
  }

  // ---- the script is allowed to run, and only it -----------------------------------------------

  @Test
  public void theContentSecurityPolicyNamesTheScriptByNonce() throws Exception {
    Browser.Page page = browser().get("/register");
    assertNotNull(page.csp());
    assertTrue(page.csp(), page.csp().contains("script-src 'self' 'nonce-"));
    // a nonce is the point: 'unsafe-inline' in script-src would let an injected script run too.
    // Checked on the script-src directive alone, since style-src legitimately carries it. 'self'
    // sits beside the nonce so a nonced module can import a vendored library from /3rd -- it lets
    // in same-origin files, which here means files inside the jar, and nothing else.
    assertFalse("script-src must not allow inline scripts wholesale",
        scriptSrc(page.csp()).contains("unsafe-inline"));
    // and the nonce in the header is the one on the tag
    String nonce = page.csp().replaceAll(".*script-src 'self' 'nonce-([^']+)'.*", "$1");
    assertTrue(page.contains("nonce=\"" + nonce + "\""));
  }

  /** just the script-src directive out of a full policy */
  private static String scriptSrc(String policy) {
    for (String directive : policy.split(";")) {
      String trimmed = directive.trim();
      if (trimmed.startsWith("script-src")) {
        return trimmed;
      }
    }
    return "";
  }

  @Test
  public void aPageWithNoInlineScriptStillLoadsTheOnesThisServerShips() throws Exception {
    // 'none' here was a real outage rather than a tightening: every script this server ships as a
    // file -- the live channel, the chat, a vendored library -- is same-origin, and 'none' refuses
    // those too. The chat page loaded no JavaScript at all.
    Browser.Page home = browser().get("/");
    assertNotNull(home.csp());
    assertTrue(home.csp(), home.csp().contains("script-src 'self'"));
    // style-src legitimately carries 'unsafe-inline'; script-src must never
    String scripts = home.csp().substring(home.csp().indexOf("script-src"));
    scripts = scripts.substring(0, scripts.indexOf(';'));
    assertFalse(scripts, scripts.contains("unsafe-inline"));
    assertFalse("and nothing from anybody else's server", scripts.contains("http"));
  }

  @Test
  public void everyMintedPageGetsAFreshNonce() throws Exception {
    assertNotEquals(browser().get("/register").mint().get("nonce").asText(),
        browser().get("/register").mint().get("nonce").asText());
  }

  @Test
  public void ticketsDoNotAccumulateForever() throws Exception {
    FormMint mint = new FormMint();
    for (int k = 0; k < 50; k++) {
      mint.mint("csrf");
    }
    assertEquals(50, mint.size());
    mint.sweep();
    assertEquals("nothing is expired yet", 50, mint.size());
    FormMint.Ticket one = mint.mint("csrf");
    mint.spend(one.id);
    assertNull(mint.find(one.id));
  }
}
