package io.hearth.mcp;

import io.hearth.auth.Permission;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What is advertised and what is executable, checked against each other for every tool at once.
 *
 * <b>This is the review, written down so it cannot rot.</b> The two lists -- the permission map the
 * listing is built from, and the checks the surface actually performs -- are maintained by hand in
 * two files, and two hand-maintained lists agree until somebody adds a tool. The failure that
 * matters is not a tool wrongly hidden; it is a tool advertised as absent and callable anyway, or a
 * tool listed and then refused. So every tool this server declares is walked, both ways round.
 *
 * The other half is narrowing. Several reads are deliberately offered to everybody and answer with
 * less, which is the right shape and the easiest one to get wrong: the classic version of this bug
 * is a filter on the listing and none on the fetch-by-id beside it, so the thing that was hidden is
 * one exact address away. Each of those pairs is checked here.
 */
public class ToolSurfaceTests {
  private static final String REDIRECT = "https://grok.com/connectors/callback";

  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;
  private McpClient hers;
  private McpClient theirs;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    ana = signIn("ana@example.com");
    long id = accounts().users.byEmail("ana@example.com").id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    grant("helper", "ana@example.com", Permission.agent_connect);
    hers = new McpClient(server.port, "example.org").connect(ana, REDIRECT);
    theirs = new McpClient(server.port, "example.org").connect(admin, REDIRECT);
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

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  /** every tool this server declares, whatever anybody may call */
  private List<String> everyTool() {
    ArrayList<String> names = new ArrayList<>();
    for (McpTools.Tool tool : new McpTools(new AiSurface(accounts(), false)).all()) {
      names.add(tool.name());
    }
    return names;
  }

  private List<String> offeredTo(McpClient client) throws Exception {
    ArrayList<String> names = new ArrayList<>();
    com.fasterxml.jackson.databind.JsonNode tools =
        client.listTools().result().path("tools");
    for (com.fasterxml.jackson.databind.JsonNode tool : tools) {
      names.add(tool.path("name").asText());
    }
    return names;
  }

  // ---- advertising and executing agree ---------------------------------------------------------

  @Test
  public void everyToolThatNeedsSomethingIsHiddenFromSomebodyWhoLacksIt() throws Exception {
    List<String> everything = everyTool();
    List<String> forHer = offeredTo(hers);
    List<String> forThem = offeredTo(theirs);

    assertEquals("an admin holds everything, so nothing is hidden from them",
        everything.size(), forThem.size());
    assertTrue("and a member is offered strictly fewer", forHer.size() < forThem.size());

    for (String tool : everything) {
      Permission needed = McpTools.needs(tool);
      boolean sheHasIt = needed == null
          || accounts().access.can(accounts().users.byEmail("ana@example.com"), needed);
      assertEquals(tool + " is offered to her when she "
              + (sheHasIt ? "can" : "cannot") + " call it",
          sheHasIt, forHer.contains(tool));
    }
  }

  @Test
  public void everyToolHiddenFromHerIsAlsoRefusedToHer() throws Exception {
    // The listing is a courtesy and the surface is the boundary. A tool that is merely *absent*
    // from the list, and works when called anyway, is the whole of this review's point -- a model
    // that guesses a name, or a connector that cached an older list, would walk straight through.
    List<String> forHer = offeredTo(hers);
    int checked = 0;
    for (String tool : everyTool()) {
      if (forHer.contains(tool)) {
        continue;
      }
      McpClient.Response answer = hers.call(tool);
      assertTrue(tool + " was hidden from her and did not refuse: " + answer.body(),
          answer.isToolError());
      assertFalse(tool + " refused without saying why", answer.refusal().isBlank());
      checked++;
    }
    assertTrue("no hidden tools were found, so this test proved nothing", checked > 0);
  }

  @Test
  public void everyToolNeedsAPermissionOrIsDeliberatelyOpen() {
    // The ones with no entry are open on purpose: reads a member has a narrowed version of. Naming
    // them here is what makes adding a *new* unlisted tool a decision rather than an oversight --
    // a tool that reaches the surface with neither a permission nor a line in this list fails.
    List<String> deliberatelyOpen = List.of(
        "content_list", "content_search", "content_get",
        "event_list", "event_get",
        "place_list", "place_get", "place_types",
        "survey_list");
    for (String tool : everyTool()) {
      assertTrue(tool + " has neither a permission nor a place on the open list; decide which",
          McpTools.needs(tool) != null || deliberatelyOpen.contains(tool));
    }
  }

  // ---- the narrowed reads, listing and fetch together ------------------------------------------

  @Test
  public void aDraftPageIsNotOneExactUriAway() throws Exception {
    // the listing was filtered and the fetch was not, which is the oldest shape of this bug
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/secret",
        "title", "Not yet", "kind", "markdown", "template_name", "", "body", "a draft"));

    assertFalse(hers.call("content_list").toolResult().toString().contains("/secret"));
    assertFalse("and not by asking for it directly either",
        hers.call("content_get", "uri", "/secret").toolResult().toString().contains("a draft"));
    assertFalse(hers.call("content_search", "query", "draft").toolResult().toString()
        .contains("/secret"));

    assertTrue("the admin's own agent still reads it, because they can open it in a browser",
        theirs.call("content_get", "uri", "/secret").toolResult().toString().contains("a draft"));
  }

  @Test
  public void anUnannouncedEventIsNotOneIdAway() throws Exception {
    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "Quiet supper",
        "starts_on", java.time.LocalDate.now().plusDays(4).toString()));
    long id = accounts().calendar.all(10).get(0).id();

    assertFalse(hers.call("event_list").toolResult().toString().contains("Quiet supper"));
    assertFalse("nor by id", hers.call("event_get", "id", id).toolResult().toString()
        .contains("Quiet supper"));
    // and the guest list is the last thing that should arrive through a side door
    assertFalse(hers.call("event_context", "id", id).toolResult().toString()
        .contains("Quiet supper"));
    assertTrue(theirs.call("event_get", "id", id).toolResult().toString()
        .contains("Quiet supper"));
  }

  @Test
  public void anUnpublishedPlaceAndItsKindAreBothAbsent() throws Exception {
    admin.submitToAndFollow("/admin/places/kinds", Map.of("action", "save", "slug", "hideout",
        "label", "Hideout", "plural", "Hideouts"));
    admin.submitToAndFollow("/admin/places", Map.of("action", "save", "type_slug", "hideout",
        "name", "The Bunker", "address", "nowhere"));

    assertFalse(hers.call("place_list").toolResult().toString().contains("The Bunker"));
    assertFalse("the kind is operator configuration too",
        hers.call("place_types").toolResult().toString().contains("hideout"));
    assertFalse(hers.call("place_get", "type", "hideout", "slug", "the-bunker")
        .toolResult().toString().contains("The Bunker"));
    assertTrue(theirs.call("place_list").toolResult().toString().contains("The Bunker"));
  }

  @Test
  public void anUnaskedQuestionAndItsAnswerCountsAreAbsent() throws Exception {
    admin.submitToAndFollow("/admin/survey", Map.of("action", "save", "prompt", "Draft question",
        "kind", "text"));
    assertFalse(hers.call("survey_list").toolResult().toString().contains("Draft question"));

    admin.submitToAndFollow("/admin/survey", Map.of("action", "save", "prompt", "Real question",
        "kind", "text", "published", "on"));
    String mine = hers.call("survey_list").toolResult().toString();
    assertTrue("a published question is what everybody is being asked", mine.contains("Real"));
    assertFalse("how many have answered is a fact about the community", mine.contains("answers"));
    assertTrue(theirs.call("survey_list").toolResult().toString().contains("answers"));
  }

  // ---- the door itself -------------------------------------------------------------------------

  @Test
  public void discoveryTellsAStrangerWhereToGoAndNothingElse() throws Exception {
    // the well-known documents are answered before any credential exists, so what is in them is
    // public whatever else is true
    try (io.hearth.testkit.Http http = new io.hearth.testkit.Http()) {
      for (String path : new String[]{"/.well-known/oauth-protected-resource",
          "/.well-known/oauth-authorization-server"}) {
        io.hearth.testkit.Http.Response answer = http.get(server.port, "example.org", path);
        assertEquals(path, 200, answer.status);
        assertFalse(path + " names a member", answer.body.contains("@example.com"));
        assertFalse(path + " names a tool", answer.body.contains("content_save"));
      }
    }
  }

  @Test
  public void aTokenlessCallGetsNothingAtAll() throws Exception {
    McpClient stranger = new McpClient(server.port, "example.org");
    assertEquals(401, stranger.rpc("tools/list", null).status());
    assertEquals("not even the list of what exists", 401,
        stranger.rpc("initialize", null).status());
  }

  @Test
  public void anAgentCannotAuthorizeAnotherAgent() throws Exception {
    // the token an agent holds is a session; if it could walk the consent flow it could mint a
    // second credential that outlives the revocation of the first
    Browser asAgent = new Browser(server.port, "example.org");
    asAgent.setCookie("hearth_session", hers.token());
    McpClient second = new McpClient(server.port, "example.org");
    second.register("Grok Two", REDIRECT);
    Browser.Page refused = second.consentPage(asAgent, REDIRECT);
    assertTrue(String.valueOf(refused.status()),
        refused.status() == 400 || refused.status() == 303);
    assertFalse(refused.body().contains("name=\"approve\""));
  }

  private void grant(String role, String email, Permission... permissions) throws Exception {
    java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
    form.put("action", "save");
    form.put("name", role);
    form.put("label", role);
    form.put("description", "");
    for (Permission permission : permissions) {
      form.put("p_" + permission.name(), "on");
    }
    admin.get("/admin/roles/new");
    admin.submitToAndFollow("/admin/roles", form);
    accounts().roles.grant(accounts().users.byEmail(email).id(), role,
        accounts().users.byEmail("boss@example.com").id());
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
