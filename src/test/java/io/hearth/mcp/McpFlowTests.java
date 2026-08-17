package io.hearth.mcp;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
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
 * Connecting a model to a community, from registration to the first tool call.
 *
 * The flow is the subject here, not the tools. What is being proved is that the only way to get an
 * agent token is to have a human admin look at a screen and agree -- and that every shortcut around
 * that is closed: a code cannot be redeemed twice, by another client, for another redirect, or
 * without the verifier; a connector cannot register a redirect this site does not trust; and a
 * token that is not an agent token is not accepted by the endpoint.
 */
public class McpFlowTests {
  private static final String REDIRECT = "https://grok.com/connectors/callback";

  private Configs configs;
  private TestServer server;
  private Browser admin;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example Community\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
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

  private McpClient connector() {
    return new McpClient(server.port, "example.org");
  }

  // ---- discovery ---------------------------------------------------------------------------------

  @Test
  public void aConnectorCanFindItsWayInWithoutBeingTold() throws Exception {
    // RFC 9728 then RFC 8414: the endpoint says who issues its tokens, and that server says how
    Browser anyone = new Browser(server.port, "example.org");
    Browser.Page resource = anyone.get("/.well-known/oauth-protected-resource");
    assertEquals(200, resource.status());
    assertTrue(resource.contains("\"resource\":\"https://example.org/mcp\""));
    assertTrue(resource.contains("authorization_servers"));

    Browser.Page authServer = anyone.get("/.well-known/oauth-authorization-server");
    assertEquals(200, authServer.status());
    assertTrue(authServer.contains("\"authorization_endpoint\":\"https://example.org/mcp/authorize\""));
    assertTrue(authServer.contains("\"token_endpoint\":\"https://example.org/mcp/token\""));
    assertTrue("PKCE is advertised", authServer.contains("S256"));
    assertFalse("and plain is not, because it protects against nothing",
        authServer.contains("\"plain\""));
  }

  @Test
  public void anUnauthenticatedCallIsToldWhereToGetAToken() throws Exception {
    McpClient.Response response = connector().listTools();
    assertEquals(401, response.status());
    assertNotNull("the header is how a connector discovers the flow", response.wwwAuthenticate());
    assertTrue(response.wwwAuthenticate().contains("resource_metadata"));
    assertTrue(response.wwwAuthenticate().contains("/.well-known/oauth-protected-resource"));
  }

  // ---- registration --------------------------------------------------------------------------------

  @Test
  public void aKnownVendorCanRegisterItself() throws Exception {
    McpClient client = connector();
    McpClient.Response response = client.register("Grok", REDIRECT);
    assertEquals(201, response.status());
    assertNotNull(client.clientId());
    assertTrue(response.contains("\"token_endpoint_auth_method\":\"none\""));
  }

  @Test
  public void aConnectorCannotRegisterARedirectThisSiteDoesNotTrust() throws Exception {
    // the whole security of self-registration: without this, anybody who can reach the endpoint
    // could set up a client pointing at their own host and wait for an admin to click approve
    McpClient.Response response = connector().register("Not Grok", "https://evil.example.net/steal");
    assertEquals(400, response.status());
    assertTrue(response.contains("invalid_redirect_uri"));
    assertTrue(response.contains("extra-redirect-prefixes"));
  }

  @Test
  public void aPlainHttpRedirectIsRefused() throws Exception {
    // a code on the wire in clear text is a code somebody else has
    assertEquals(400, connector().register("Grok", "http://grok.com/callback").status());
  }

  // ---- the consent screen ----------------------------------------------------------------------------

  @Test
  public void aPersonHasToLookAtTheScreenAndAgree() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    Browser.Page consent = client.consentPage(admin, REDIRECT);

    assertEquals(200, consent.status());
    assertTrue(consent.contains("Connect Grok"));
    assertTrue("it says who is asking", consent.contains("x.ai"));
    assertTrue("and as whom", consent.contains("boss@example.com"));
    assertTrue("what it will be able to do", consent.contains("Create, change and delete pages"));
    assertTrue("and what it will not", consent.contains("See anything marked human only"));
    assertTrue("and where it goes back to", consent.contains(REDIRECT));
    assertTrue("agreeing is a form, not a link", consent.contains("name=\"approve\""));
  }

  @Test
  public void connectingNeedsThePermissionAndSaysWhichOne() throws Exception {
    // The endpoint is open to anybody a community has given `agent_connect` to -- it is no longer
    // admin-only. It is not a baseline either: a connection is a standing credential held by
    // somebody else's software that can act as this person for a month, which is not something to
    // hand out for being approved.
    Browser member = signIn("member@example.com");
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    Browser.Page refused = client.consentPage(member, REDIRECT);
    assertEquals(400, refused.status());
    assertTrue(refused.body(), refused.contains("Connect an assistant that acts as you"));
    assertTrue("and it says who can give it to them, rather than sending them to ask for admin",
        refused.contains("in a role"));
  }

  @Test
  public void signedOutMeansSignInFirst() throws Exception {
    Browser stranger = new Browser(server.port, "example.org");
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    Browser.Page redirected = client.consentPage(stranger, REDIRECT);
    assertEquals(303, redirected.status());
    assertTrue(redirected.location(), redirected.location().startsWith("/login"));
  }

  @Test
  public void signingInDuringTheFlowComesBackToTheConsentScreen() throws Exception {
    // The reported bug: the connector opens a popup, the person is not signed in, they log in --
    // and land on the home page. The popup sits there forever and the connection never completes.
    Browser stranger = new Browser(server.port, "example.org");
    stranger.get("/register");
    stranger.submit(Map.of("email", "boss@example.com"));
    stranger.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    stranger.forgetCookies();

    McpClient client = connector();
    client.register("Grok", REDIRECT);
    Browser.Page sentToLogin = client.consentPage(stranger, REDIRECT);
    assertEquals(303, sentToLogin.status());
    assertTrue(sentToLogin.location(), sentToLogin.location().startsWith("/login?next="));

    // follow it the way a browser would, and sign in on the page it lands on
    Browser.Page loginForm = stranger.get(sentToLogin.location());
    assertEquals(200, loginForm.status());
    stranger.submit(Map.of("email", "boss@example.com"));
    Browser.Page afterCode = stranger.submit(
        Map.of("code", server.mail().lastCodeFor("boss@example.com")));

    assertEquals(303, afterCode.status());
    assertTrue("signing in has to come back to where they were going, not the home page",
        afterCode.location().startsWith("/mcp/authorize"));
    assertTrue("with the connector's parameters intact",
        afterCode.location().contains("client_id=" + client.clientId()));
    assertTrue(afterCode.location().contains("code_challenge="));

    Browser.Page consent = stranger.get(afterCode.location());
    assertEquals("and that is the consent screen", 200, consent.status());
    assertTrue(consent.contains("Connect Grok"));
  }

  @Test
  public void theWholeFlowWorksForSomebodyWhoStartsSignedOut() throws Exception {
    // end to end, the way it actually happens: popup, sign in, approve, token
    Browser person = new Browser(server.port, "example.org");
    person.get("/register");
    person.submit(Map.of("email", "boss@example.com"));
    person.submit(Map.of("code", server.mail().lastCodeFor("boss@example.com")));
    person.forgetCookies();

    McpClient client = connector();
    client.register("Grok", REDIRECT);
    Browser.Page sentToLogin = client.consentPage(person, REDIRECT);
    person.get(sentToLogin.location());
    person.submit(Map.of("email", "boss@example.com"));
    Browser.Page backToAuthorize = person.submit(
        Map.of("code", server.mail().lastCodeFor("boss@example.com")));

    String code = client.authorize(person, REDIRECT, "state-1");
    assertNotNull("consent should now hand back a code", code);
    assertEquals(200, client.redeem(code, REDIRECT).status());
    assertEquals("and the connector is connected", 200, client.listTools().status());
  }

  @Test
  public void aRedirectThatWasNotRegisteredIsRefusedBeforeAnybodyIsRedirected() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    // grok.com is trusted, but this client did not register this particular address
    Browser.Page refused = client.consentPage(admin, "https://grok.com/somewhere-else");
    assertEquals(400, refused.status());
    assertTrue(refused.contains("does not send authorization codes"));
  }

  @Test
  public void aFlowWithoutPkceIsRefused() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    Browser.Page response = admin.get("/mcp/authorize?response_type=code&client_id="
        + client.clientId() + "&redirect_uri=" + java.net.URLEncoder.encode(REDIRECT, "UTF-8"));
    assertEquals(303, response.status());
    assertTrue(response.location(), response.location().contains("error=invalid_request"));
  }

  // ---- the token -------------------------------------------------------------------------------------

  @Test
  public void theWholeFlowEndsInAWorkingToken() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    String code = client.authorize(admin, REDIRECT, "state-123");
    assertNotNull("consent should hand back a code", code);

    McpClient.Response issued = client.redeem(code, REDIRECT);
    assertEquals(200, issued.status());
    assertEquals("Bearer", issued.json().get("token_type").asText());
    assertNotNull(client.token());

    McpClient.Response initialized = client.initialize();
    assertEquals(200, initialized.status());
    assertEquals("hearth", initialized.result().get("serverInfo").get("name").asText());
    assertTrue("the briefing names the community",
        initialized.result().get("instructions").asText().contains("Example Community"));
    assertTrue("and says who it is acting as",
        initialized.result().get("instructions").asText().contains("boss@example.com"));
  }

  @Test
  public void theStateParameterComesBackUntouched() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    client.consentPage(admin, REDIRECT);
    Browser.Page redirected = admin.submitTo("/mcp/authorize", Map.of(
        "client_id", client.clientId(), "redirect_uri", REDIRECT, "state", "abc/123",
        "code_challenge", McpClient.challengeFor(McpClient.randomVerifier()),
        "code_challenge_method", "S256", "approve", "1"));
    assertTrue(redirected.location(), redirected.location().contains("state=abc%2F123"));
  }

  @Test
  public void aCodeIsGoodExactlyOnce() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    String code = client.authorize(admin, REDIRECT, null);
    assertEquals(200, client.redeem(code, REDIRECT).status());

    McpClient.Response again = client.redeem(code, REDIRECT);
    assertEquals(400, again.status());
    assertTrue(again.contains("invalid_grant"));
  }

  @Test
  public void aCodeIsUselessWithoutTheVerifier() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    String code = client.authorize(admin, REDIRECT, null);

    McpClient.Response wrong = client.redeem(code, REDIRECT, McpClient.randomVerifier());
    assertEquals(400, wrong.status());
    assertTrue(wrong.contains("code_verifier does not match"));
    assertNull("and nothing was issued", client.token());
  }

  @Test
  public void aCodeCannotBeRedeemedForADifferentRedirect() throws Exception {
    McpClient client = connector();
    client.register("Grok", REDIRECT);
    String code = client.authorize(admin, REDIRECT, null);
    McpClient.Response wrong = client.redeem(code, "https://grok.com/elsewhere");
    assertEquals(400, wrong.status());
    assertTrue(wrong.contains("redirect_uri does not match"));
  }

  @Test
  public void aCodeCannotBeRedeemedByADifferentClient() throws Exception {
    McpClient first = connector();
    first.register("Grok", REDIRECT);
    String code = first.authorize(admin, REDIRECT, null);

    McpClient second = connector();
    second.register("Also Grok", REDIRECT);
    McpClient.Response stolen = second.redeem(code, REDIRECT, McpClient.randomVerifier());
    assertEquals(400, stolen.status());
    assertNull(second.token());
  }

  @Test
  public void aPersonsLoginCookieIsNotAnAgentToken() throws Exception {
    // the endpoint takes agent tokens only: a session cookie lifted from a browser must not become
    // an MCP credential, which is the whole reason the robot bit exists rather than a scope string
    String cookie = admin.cookie("hearth_session");
    assertNotNull(cookie);
    McpClient client = connector().withToken(cookie);
    assertEquals(401, client.listTools().status());
  }

  @Test
  public void anAgentCannotAuthorizeAnotherAgent() throws Exception {
    // privilege escalation by recursion: an agent that could walk the consent flow would be able to
    // mint itself fresh tokens forever, outliving any revocation
    McpClient client = connector().connect(admin, REDIRECT);
    assertNotNull(client.token());

    Browser asAgent = new Browser(server.port, "example.org");
    asAgent.setCookie("hearth_session", client.token());
    McpClient second = connector();
    second.register("Second", REDIRECT);
    Browser.Page refused = second.consentPage(asAgent, REDIRECT);
    assertTrue("an agent token is not a login for the consent screen",
        refused.status() == 303 || refused.contains("cannot authorize"));
  }

  @Test
  public void revokingTheSessionDisconnectsTheAgent() throws Exception {
    McpClient client = connector().connect(admin, REDIRECT);
    assertEquals(200, client.listTools().status());

    server.auth.forDomain("example.org").sessions.revokeAllFor(
        server.auth.forDomain("example.org").users.byEmail("boss@example.com").id());
    assertEquals("an agent token is a session, so revoking works on it too",
        401, client.listTools().status());
  }

  @Test
  public void demotingTheAdminBetweenConsentAndRedemptionStopsTheToken() throws Exception {
    Browser deputy = signIn("deputy@example.com");
    var accounts = server.auth.forDomain("example.org");
    long id = accounts.users.byEmail("deputy@example.com").id();
    accounts.roles.grant(id, io.hearth.auth.Roles.ADMIN, null);
    accounts.users.approve(id, null);

    McpClient client = connector();
    client.register("Grok", REDIRECT);
    String code = client.authorize(deputy, REDIRECT, null);
    assertNotNull(code);

    accounts.roles.revoke(id, io.hearth.auth.Roles.ADMIN);
    McpClient.Response refused = client.redeem(code, REDIRECT);
    assertEquals(400, refused.status());
    assertTrue(refused.contains("no longer valid"));
  }

  // ---- the endpoint ------------------------------------------------------------------------------------

  @Test
  public void theEndpointOffersItsTools() throws Exception {
    McpClient client = connector().connect(admin, REDIRECT);
    McpClient.Response tools = client.listTools();
    assertEquals(200, tools.status());
    String body = tools.body();
    for (String tool : new String[]{"content_list", "content_search", "content_get", "content_save",
        "template_list", "template_save", "survey_list", "survey_ask", "survey_summarize"}) {
      assertTrue("should offer " + tool, body.contains("\"" + tool + "\""));
    }
  }

  @Test
  public void anUnknownMethodIsAJsonRpcErrorRatherThanACrash() throws Exception {
    McpClient client = connector().connect(admin, REDIRECT);
    McpClient.Response response = client.rpc("nonsense/method", null);
    assertEquals(200, response.status());
    assertTrue(response.rpcError().contains("does not implement"));
  }

  @Test
  public void mcpIsOffUnlessADomainTurnsItOn() throws Exception {
    Configs quiet = Configs.dir().domain("quiet.test",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@example.com\"]}");
    try (TestServer other = TestServer.ofConfigs(quiet.file())) {
      Browser browser = new Browser(other.port, "quiet.test");
      // an unmatched path falls through to the home page on any domain, so what matters is that
      // nothing here behaves like an endpoint: no metadata, and no JSON-RPC
      assertFalse("no resource metadata is published",
          browser.get("/.well-known/oauth-protected-resource").contains("authorization_servers"));
      assertFalse(browser.get("/mcp").contains("jsonrpc"));

      McpClient client = new McpClient(other.port, "quiet.test");
      assertEquals("posting to it is not a form endpoint on this domain",
          405, client.listTools().status());
      assertEquals("and nothing can register", 405, client.register("Grok", REDIRECT).status());
    } finally {
      quiet.delete();
    }
  }
}
