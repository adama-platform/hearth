package io.hearth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.Http;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A token somebody copies by hand, and the door it opens.
 *
 * Two properties carry the weight here. The JSON half must never accept the browser's cookie -- a
 * cookie-authenticated JSON endpoint is a forgery hole with no form and no token in it, reachable
 * from any page a member happens to have open. And a token can never do more than the person who
 * made it, because it *is* that person, holding a different keyboard.
 */
public class ApiTests {
  private static final ObjectMapper JSON = new ObjectMapper();

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

  // ---- getting one ------------------------------------------------------------------------------

  @Test
  public void theAddressACliPrintsAsksBeforeItGivesAnything() throws Exception {
    Browser.Page page = admin.get("/api?name=hearth-cli");
    assertEquals(200, page.status());
    assertTrue("it says what is being authorized", page.contains("Authorize <em>hearth-cli</em>"));
    assertTrue("and what that means", page.contains("whatever <strong>you</strong> can do here"));
    assertEquals("and nothing was minted by looking at it", 0,
        ApiTokens.of(accounts(), me()).size());
  }

  @Test
  public void anAnonymousVisitorIsSentToSignInAndBroughtBack() throws Exception {
    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/api?name=hearth-cli");
      assertEquals(303, answer.status);
      assertTrue("somebody following a URL their terminal printed is mid-task",
          answer.header("location").contains("next=%2Fapi%3Fname%3Dhearth-cli"));
    }
  }

  @Test
  public void authorizingShowsTheTokenOnceAndNeverAgain() throws Exception {
    admin.get("/api?name=hearth-cli");
    Browser.Page done = admin.submitToAndFollow("/api",
        Map.of("action", "authorize", "name", "hearth-cli"));
    assertTrue(done.body(), done.contains("Copy this now"));
    String token = tokenIn(done.body());
    assertNotNull("the token is on the page", token);
    assertFalse("and never in the address", done.body().contains("?token="));

    assertFalse("a second look does not show it again", admin.get("/api").contains(token));
    assertEquals("but the token itself is listed", 1, ApiTokens.of(accounts(), me()).size());
    assertTrue(admin.get("/api").contains("hearth-cli"));
  }

  @Test
  public void aTokenIsASessionWithABitSet() throws Exception {
    String token = token("hearth-cli");
    io.hearth.auth.SessionRecord session = accounts().sessions.resolve(token);
    assertNotNull(session);
    assertTrue("so revocation, expiry and the reaper all work on it already", session.robot());
    assertEquals("api:hearth-cli", session.agent());
    assertEquals(me(), session.userId());
    assertTrue("and it expires, because a token pasted into a script should stop working while"
        + " the person who pasted it still remembers doing so",
        session.expiresAt() > System.currentTimeMillis());
  }

  @Test
  public void twoIsTheLimitAndTheThirdIsRefusedRatherThanRotated() throws Exception {
    token("laptop");
    token("build machine");
    Browser.Page done = admin.submitToAndFollow("/api",
        Map.of("action", "authorize", "name", "third"));
    assertTrue(done.body(), done.contains("Revoke one first"));
    assertEquals(2, ApiTokens.of(accounts(), me()).size());
  }

  @Test
  public void revokingOneStopsItAtOnce() throws Exception {
    String token = token("laptop");
    assertEquals(200, whoami(token).status);
    long id = ApiTokens.of(accounts(), me()).get(0).id();
    admin.submitToAndFollow("/api", Map.of("action", "revoke", "token", Long.toString(id)));
    assertEquals("deleted rather than revoked: 'no longer works' should mean it is not there",
        401, whoami(token).status);
    assertEquals(0, ApiTokens.of(accounts(), me()).size());
  }

  @Test
  public void nobodyRevokesSomebodyElsesToken() throws Exception {
    String token = token("laptop");
    Browser other = signIn("ana@example.com");
    long id = ApiTokens.of(accounts(), me()).get(0).id();
    other.get("/api");
    other.submitToAndFollow("/api", Map.of("action", "revoke", "token", Long.toString(id)));
    assertEquals("an id from somewhere else revokes nothing", 200, whoami(token).status);
  }

  // ---- using one --------------------------------------------------------------------------------

  @Test
  public void whoamiSaysWhoAndUntilWhenAndWhatFor() throws Exception {
    String token = token("hearth-cli");
    JsonNode out = json(whoami(token));
    assertEquals("boss@example.com", out.get("email").asText());
    assertEquals("hearth-cli", out.get("token").asText());
    assertEquals("Example", out.get("community").asText());
    assertFalse(out.get("expires_at").isNull());
    assertTrue("what a tool needs before it starts, rather than a 403 halfway through a push",
        out.get("can").toString().contains("content_write"));
    assertTrue(out.get("endpoints").toString().contains("/api/v1/content"));
  }

  @Test
  public void noTokenIsA401ThatSaysWhereToGetOne() throws Exception {
    try (Http http = new Http()) {
      Http.Response answer = http.get(server.port, "example.org", "/api/v1/whoami");
      assertEquals(401, answer.status);
      assertTrue(answer.body.contains("/api"));
      assertEquals("Bearer", answer.header("www-authenticate"));
    }
  }

  @Test
  public void aBrowserCookieIsNeverACredentialHere() throws Exception {
    // the whole reason the two halves are split: a JSON endpoint that took the session cookie
    // would be a forgery hole with no form and no token in it
    assertEquals(401, admin.get("/api/v1/whoami").status());
    assertEquals(401, admin.submitTo("/api/v1/content", Map.of("x", "y")).status());
  }

  @Test
  public void amodelsConnectionIsNotAnApiToken() throws Exception {
    // both are robot sessions; only one of them was made on this screen, and the labels are what
    // keep the two lists from ever being confused for one another
    io.hearth.auth.Sessions.Issued agent =
        accounts().sessions.createForAgent(me(), "claude", 3600);
    assertEquals(401, whoami(agent.token()).status);
    assertEquals(0, ApiTokens.of(accounts(), me()).size());
  }

  @Test
  public void anAccountThatWasTurnedOffStopsWorkingImmediately() throws Exception {
    String token = token("laptop");
    accounts().users.setDisabled(me(), true);
    assertEquals(401, whoami(token).status);
  }

  // ---- content ----------------------------------------------------------------------------------

  @Test
  public void theSiteComesDownAsOneDocument() throws Exception {
    write("/about", "About us", "We meet on Tuesdays.");
    JsonNode bundle = json(get(token("cli"), "/api/v1/content"));
    assertEquals(1, bundle.get("hearth").asInt());
    assertEquals(1, bundle.get("content").size());
    assertEquals("/about", bundle.get("content").get(0).get("uri").asText());
  }

  @Test
  public void aPushOnlyWritesWhatDiffersAndSaysWhatItDid() throws Exception {
    write("/about", "About us", "the first version");
    write("/other", "Other", "unchanged here");
    String token = token("cli");
    JsonNode bundle = json(get(token, "/api/v1/content"));
    String edited = bundle.toString().replace("the first version", "the second version");

    JsonNode result = json(post(token, "/api/v1/content", edited));
    assertTrue(result.get("ok").asBoolean());
    assertEquals(1, result.get("summary").get("updated").asInt());
    assertEquals("a tool that pushes the whole site every time must not fill the history with"
        + " edits nobody made", 1, result.get("summary").get("unchanged").asInt());
    assertEquals(0, result.get("summary").get("created").asInt());

    JsonNode about = rowFor(result, "/about");
    assertEquals("updated", about.get("status").asText());
    assertEquals("[\"body\"]", about.get("changed").toString());
    assertEquals("unchanged", rowFor(result, "/other").get("status").asText());
    assertEquals("the second version",
        server.auth.forDomain("example.org").site.store().byUri("/about").body());
  }

  @Test
  public void nothingUnchangedIsEvenVersioned() throws Exception {
    long id = write("/about", "About us", "the words");
    String token = token("cli");
    long before = server.auth.forDomain("example.org").site.store().versions().count(id);
    post(token, "/api/v1/content", json(get(token, "/api/v1/content")).toString());
    assertEquals("a save is an event, a cache drop and a version; doing it for nothing is not free",
        before, server.auth.forDomain("example.org").site.store().versions().count(id));
  }

  @Test
  public void aDryRunAnswersTheSameThingAndWritesNothing() throws Exception {
    write("/about", "About us", "the first version");
    String token = token("cli");
    String edited = json(get(token, "/api/v1/content")).toString()
        .replace("the first version", "the second version");

    JsonNode result = json(post(token, "/api/v1/content?dry=1", edited));
    assertTrue(result.get("dry_run").asBoolean());
    assertEquals(1, result.get("summary").get("updated").asInt());
    assertEquals("a diff nobody can see before it lands is a diff nobody reviews",
        "the first version",
        server.auth.forDomain("example.org").site.store().byUri("/about").body());
  }

  @Test
  public void aNewPageArrivesFromAFolderNobodyHasEverPushedFrom() throws Exception {
    String token = token("cli");
    JsonNode result = json(post(token, "/api/v1/content",
        "{\"content\":[{\"uri\":\"/from-git\",\"title\":\"From git\",\"kind\":\"markdown\","
            + "\"body\":\"# Hello\",\"published\":true}]}"));
    assertEquals(1, result.get("summary").get("created").asInt());
    assertEquals("created", result.get("content").get(0).get("status").asText());
    assertTrue("and it is a page on the site", admin.get("/from-git").contains("Hello"));
  }

  @Test
  public void nonsenseIsRefusedWithASentenceRatherThanAStatusAlone() throws Exception {
    String token = token("cli");
    Http.Response answer = post(token, "/api/v1/content", "not json");
    assertEquals(400, answer.status);
    assertTrue(answer.body, answer.body.contains("that is not JSON"));
    assertFalse(json(answer).get("ok").asBoolean());
  }

  @Test
  public void aTokenCanNeverDoMoreThanThePersonHoldingIt() throws Exception {
    Browser member = signIn("ana@example.com");
    io.hearth.auth.Accounts accounts = accounts();
    long id = accounts.users.byEmail("ana@example.com").id();
    accounts.users.approve(id, null);
    member.get("/api");
    String token = tokenIn(member.submitToAndFollow("/api",
        Map.of("action", "authorize", "name", "theirs")).body());
    assertNotNull(token);

    Http.Response reading = get(token, "/api/v1/content");
    assertEquals(403, reading.status);
    assertTrue(reading.body, reading.body.contains("write pages"));
    assertEquals("but they can still ask who they are", 200, whoami(token).status);
  }

  @Test
  public void anEndpointThatIsNotThereSaysSo() throws Exception {
    Http.Response answer = get(token("cli"), "/api/v1/nonsense");
    assertEquals(404, answer.status);
    assertTrue(answer.body.contains("no_such_endpoint"));
  }

  @Test
  public void theSettingsScreenSaysWhatIsActuallySwitchedOn() throws Exception {
    Browser.Page page = admin.get("/admin/system/settings");
    assertEquals(200, page.status());
    assertTrue("a report rather than an editor", page.contains("This is a report, not an editor"));
    assertTrue("the key, so 'how do I change this' is on the same line as the thing",
        page.contains("calendar.remind-days-before"));
    assertTrue(page.contains("api.token-days"));
    assertTrue("the clock, with what time it actually is in it -- a zone id is easy to mistype"
        + " into something real and wrong", page.contains("timezone"));
    assertTrue(page.contains("IANA zone id"));
    assertTrue(page.contains("login_security.mode"));
    assertTrue("and where the file is", page.contains("example.org.cfg"));
    assertFalse("and nothing on it posts anywhere, because there is nothing to save",
        page.contains("action=\"/admin/system/settings\""));
  }

  @Test
  public void noSecretIsEverPrintedOnIt() throws Exception {
    Configs keyed = Configs.dir().domain("keyed.example.org",
        "{\"name\":\"Keyed\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"ses\":{\"enabled\":true,\"access-key-id\":\"AKIAsecretlooking\","
            + "\"secret-access-key\":\"averysecretvalue\",\"region\":\"eu-west-1\","
            + "\"from\":\"hello@keyed.example.org\"}}");
    try (TestServer other = TestServer.ofConfigs(keyed.file())) {
      Browser boss = new Browser(other.port, "keyed.example.org");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@example.com"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@example.com")));
      Browser.Page page = boss.get("/admin/system/settings");
      assertTrue(page.contains("ses.access-key-id"));
      assertFalse("a credential on a screen is a credential in a screenshot",
          page.contains("AKIAsecretlooking"));
      assertFalse(page.contains("averysecretvalue"));
      assertTrue("it says whether one is there, which is the useful half",
          page.contains("set"));
    } finally {
      keyed.delete();
    }
  }

  @Test
  public void aCommunityCanSwitchTheWholeThingOff() throws Exception {
    Configs quiet = Configs.dir().domain("quiet.example.org",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@example.com\"],\"disabled\":[\"api\"]}");
    try (TestServer other = TestServer.ofConfigs(quiet.file())) {
      try (Http http = new Http()) {
        assertEquals(404, http.get(other.port, "quiet.example.org", "/api").status);
        assertEquals(404, http.get(other.port, "quiet.example.org", "/api/v1/whoami").status);
      }
    } finally {
      quiet.delete();
    }
  }

  @Test
  public void theLifetimeIsTheCommunitysToSet() throws Exception {
    Configs brief = Configs.dir().domain("brief.example.org",
        "{\"name\":\"Brief\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"api\":{\"token-days\":1,\"max-tokens\":1}}");
    try (TestServer other = TestServer.ofConfigs(brief.file())) {
      Browser boss = new Browser(other.port, "brief.example.org");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@example.com"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@example.com")));
      boss.get("/api");
      boss.submitToAndFollow("/api", Map.of("action", "authorize", "name", "one"));

      long id = other.auth.forDomain("brief.example.org").users.byEmail("boss@example.com").id();
      ApiTokens.Token token = ApiTokens.of(other.auth.forDomain("brief.example.org"), id).get(0);
      // about a day, rather than exactly one: an integer division of "now" against "now plus a
      // day" is 0 or 1 depending on whether a millisecond passed between the two calls
      long left = token.expiresAt() - System.currentTimeMillis();
      assertTrue("a day, give or take the time it took to ask: " + left,
          left > Duration.ofHours(23).toMillis() && left <= Duration.ofHours(24).toMillis());

      assertTrue(boss.submitToAndFollow("/api", Map.of("action", "authorize", "name", "two"))
          .contains("Revoke one first"));
    } finally {
      brief.delete();
    }
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  private long me() throws Exception {
    return accounts().users.byEmail("boss@example.com").id();
  }

  private String token(String name) throws Exception {
    admin.get("/api");
    Browser.Page done = admin.submitToAndFollow("/api",
        Map.of("action", "authorize", "name", name));
    return tokenIn(done.body());
  }

  /** the token out of the one place it is ever shown */
  private static String tokenIn(String html) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("<pre class=\"token\"><code>([^<]+)</code></pre>")
            .matcher(html);
    return matcher.find() ? matcher.group(1) : null;
  }

  private Http.Response whoami(String token) throws Exception {
    return get(token, "/api/v1/whoami");
  }

  private Http.Response get(String token, String path) throws Exception {
    try (Http http = new Http()) {
      return http.send(server.port, "example.org", "GET", path, null,
          "Authorization", "Bearer " + token);
    }
  }

  private Http.Response post(String token, String path, String body) throws Exception {
    try (Http http = new Http()) {
      return http.send(server.port, "example.org", "POST", path,
          body.getBytes(StandardCharsets.UTF_8),
          "Authorization", "Bearer " + token, "Content-Type", "application/json");
    }
  }

  private static JsonNode json(Http.Response answer) throws Exception {
    return JSON.readTree(answer.body);
  }

  private static JsonNode rowFor(JsonNode result, String uri) {
    for (JsonNode row : result.get("content")) {
      if (row.get("name").asText().equals(uri)) {
        return row;
      }
    }
    throw new AssertionError("no row for " + uri);
  }

  private long write(String uri, String title, String body) throws Exception {
    admin.get("/admin/content/new");
    admin.submitTo("/admin/content", Map.of("action", "save", "uri", uri, "title", title,
        "kind", "markdown", "template_name", "", "nav_folder", "", "body", body,
        "published", "on"));
    return server.auth.forDomain("example.org").site.store().byUri(uri).id();
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
