package io.hearth.mcp;

import io.hearth.board.Poll;
import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
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
 * The board as a place agents can work, held to what the person holding the connection may do.
 *
 * The mission for this is a community of friends whose agents do some of the organising: one puts a
 * question to the group, people and other agents answer it, and the answer becomes an evening. What
 * makes that safe rather than alarming is the second half of the sentence -- an agent can never do
 * anything the person could not, which is invariant 26 stated about sessions and enforced here
 * about tools.
 */
public class AgentBoardTests {
  private static final String REDIRECT = "https://grok.com/connectors/callback";

  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;
  private McpClient agent;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    ana = signIn("ana@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
    agent = new McpClient(server.port, "example.org").connect(admin, REDIRECT);
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

  private io.hearth.board.Polls polls() {
    return server.auth.forDomain("example.org").polls;
  }

  @Test
  public void everyPollToolIsOffered() throws Exception {
    String tools = agent.listTools().body();
    for (String name : new String[]{"poll_create", "poll_get", "poll_list", "poll_option_add",
        "poll_option_remove", "poll_vote", "poll_close"}) {
      assertTrue(name + " is not offered", tools.contains(name));
    }
    // a tool description is a prompt, so the rules a model cannot see have to be in it
    assertTrue("it has to say that days and either-ors count differently",
        tools.contains("independently"));
    assertTrue("and that a schedule poll ends by making an event",
        tools.contains("becomes an event"));
  }

  @Test
  public void anAgentCanRunTheWholeThing() throws Exception {
    // the shape the mission describes: an agent asks the group about an evening, a person votes,
    // the agent votes for the person it works for, and the answer becomes an event
    McpClient.Response posted = agent.call("board_post", "title", "Supper this month",
        "body", "Where and when?");
    long post = posted.toolResult().path("id").asLong();

    McpClient.Response asked = agent.call("poll_create", "post", post, "kind", "schedule",
        "question", "Supper club", "closes_at", LocalDate.now().plusDays(3).toString());
    long poll = asked.toolResult().path("id").asLong();
    assertEquals("schedule", asked.toolResult().path("kind").asText());
    assertTrue("it explains how to vote rather than making the model guess",
        asked.toolResult().path("how_to_vote").asText().contains("up, a down, or nothing"));

    LocalDate saturday = LocalDate.now().plusDays(8);
    agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", LocalDate.now().plusDays(7).toString(), "at", "from 7");
    McpClient.Response added = agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", saturday.toString(), "at", "from 7");
    long sat = added.toolResult().path("id").asLong();

    // a person, on the site, and an agent, through a tool, in the same poll
    ana.submitToAndFollow("/board", Map.of("action", "poll_vote",
        "option", Long.toString(sat), "weight", "up"));
    McpClient.Response voted = agent.call("poll_vote", "option", sat, "weight", 1);
    assertEquals("for", voted.toolResult().path("your_vote").asText());

    McpClient.Response read = agent.call("poll_get", "id", poll);
    assertEquals(2, read.toolResult().path("when").path("voters").asInt());
    assertTrue(read.toolResult().path("when").path("decided").asBoolean());

    McpClient.Response closed = agent.call("poll_close", "id", poll);
    assertEquals("converted", closed.toolResult().path("state").asText());
    long eventId = closed.toolResult().path("became_event").asLong();
    assertEquals(saturday,
        server.auth.forDomain("example.org").calendar.byId(eventId).startsOn());
  }

  @Test
  public void aTieComesBackAsSomethingTheModelCanAct0n() throws Exception {
    long post = agent.call("board_post", "title", "Supper", "body", "When?")
        .toolResult().path("id").asLong();
    long poll = agent.call("poll_create", "post", post, "kind", "schedule", "question", "Supper",
        "closes_at", LocalDate.now().plusDays(3).toString()).toolResult().path("id").asLong();
    long friday = agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", LocalDate.now().plusDays(7).toString()).toolResult().path("id").asLong();
    long saturday = agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", LocalDate.now().plusDays(8).toString()).toolResult().path("id").asLong();

    agent.call("poll_vote", "option", friday, "weight", 1);
    ana.submitToAndFollow("/board", Map.of("action", "poll_vote",
        "option", Long.toString(saturday), "weight", "up"));

    McpClient.Response closed = agent.call("poll_close", "id", poll);
    // not an error: the group has not decided, and the model's next move is to say so or add a day
    assertFalse(closed.isToolError());
    assertEquals("closed", closed.toolResult().path("state").asText());
    assertTrue(closed.toolResult().path("outcome").asText(),
        closed.toolResult().path("outcome").asText().contains("level"));
  }

  @Test
  public void aDayIsADayAndAPlaceIsFromTheAddressBook() throws Exception {
    long post = agent.call("board_post", "title", "Supper", "body", "When?")
        .toolResult().path("id").asLong();
    long poll = agent.call("poll_create", "post", post, "kind", "schedule", "question", "Supper",
        "closes_at", LocalDate.now().plusDays(3).toString()).toolResult().path("id").asLong();

    McpClient.Response vague = agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", "next Saturday");
    assertTrue(vague.isToolError());
    assertTrue(vague.refusal(), vague.refusal().contains("YYYY-MM-DD"));

    // a place is never free text: the winner becomes an event's location, and a typed address
    // would mean somebody retyping it
    McpClient.Response typed = agent.call("poll_option_add", "poll", poll, "facet", "place",
        "label", "the pub on the corner");
    assertTrue(typed.isToolError());
    assertTrue(typed.refusal(), typed.refusal().contains("address book"));

    // the same day twice is one day, or its votes are split in two
    agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", LocalDate.now().plusDays(7).toString());
    assertTrue(agent.call("poll_option_add", "poll", poll, "facet", "time",
        "day", LocalDate.now().plusDays(7).toString()).isToolError());
  }

  @Test
  public void aScheduleVoteThatNeverClosesIsRefusedWhenItIsAsked() throws Exception {
    // it becomes an event by itself, and one with no closing time never does. Refusing at the
    // point of asking beats discovering it after people have voted.
    long post = agent.call("board_post", "title", "Supper", "body", "When?")
        .toolResult().path("id").asLong();
    McpClient.Response refused = agent.call("poll_create", "post", post, "kind", "schedule",
        "question", "Supper");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("when it closes"));
  }

  @Test
  public void anAgentIsHeldToWhatItsPersonMayDo() throws Exception {
    // The connection here belongs to an admin, who holds everything -- so the check is proved by
    // taking a permission away rather than by finding somebody without it. A role with no calendar
    // is the shape a member's connection will have when member connections exist.
    Configs plain = Configs.dir().domain("plain.org",
        "{\"name\":\"Plain\",\"admin_emails\":[\"boss@plain.org\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    try (TestServer other = TestServer.ofConfigs(plain.file())) {
      Browser boss = new Browser(other.port, "plain.org");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@plain.org"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@plain.org")));

      // a permission the acting person does not hold is a refusal that names it, so the model's
      // useful next move is to tell somebody rather than to try another phrasing
      io.hearth.auth.Accounts accounts = other.auth.forDomain("plain.org");
      io.hearth.auth.UserRecord me = accounts.users.byEmail("boss@plain.org");
      assertTrue("an admin holds everything, baseline included",
          accounts.access.can(me, io.hearth.auth.Permission.board_vote));

      // and an ordinary approved member holds exactly the three baseline permissions and no more
      Browser cal = new Browser(other.port, "plain.org");
      cal.get("/register");
      cal.submit(Map.of("email", "cal@plain.org"));
      cal.submit(Map.of("code", other.mail().lastCodeFor("cal@plain.org")));
      long id = accounts.users.byEmail("cal@plain.org").id();
      boss.submitToAndFollow("/admin/people",
          Map.of("action", "approve", "user", Long.toString(id)));
      io.hearth.auth.UserRecord member = accounts.users.byId(id);
      assertTrue(accounts.access.can(member, io.hearth.auth.Permission.board_read));
      assertTrue(accounts.access.can(member, io.hearth.auth.Permission.board_write));
      assertTrue(accounts.access.can(member, io.hearth.auth.Permission.board_vote));
      assertFalse("moderating acts on somebody else's words and is not a baseline",
          accounts.access.can(member, io.hearth.auth.Permission.board_moderate));
      assertFalse("nor is putting an evening in everybody's calendar",
          accounts.access.can(member, io.hearth.auth.Permission.calendar_write));
      assertFalse("and none of the three opens the admin section",
          accounts.access.can(member, io.hearth.auth.Permission.admin_enter));
    } finally {
      plain.delete();
    }
  }

  @Test
  public void aReadOnlyConnectionCanCountAndCannotVote() throws Exception {
    Configs quiet = Configs.dir().domain("quiet.org",
        "{\"name\":\"Quiet\",\"admin_emails\":[\"boss@quiet.org\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"],\"read-only\":true}}");
    try (TestServer other = TestServer.ofConfigs(quiet.file())) {
      Browser boss = new Browser(other.port, "quiet.org");
      boss.get("/register");
      boss.submit(Map.of("email", "boss@quiet.org"));
      boss.submit(Map.of("code", other.mail().lastCodeFor("boss@quiet.org")));
      boss.submitToAndFollow("/board", Map.of("action", "post", "title", "Supper",
          "body", "When?"));
      McpClient watcher = new McpClient(other.port, "quiet.org").connect(boss, REDIRECT);

      assertFalse("reading is what read-only means", watcher.call("board_list").isToolError());
      McpClient.Response refused = watcher.call("poll_create", "post", 1, "kind", "choice",
          "question", "Which?");
      assertTrue(refused.isToolError());
      assertTrue(refused.refusal(), refused.refusal().contains("read only"));
    } finally {
      quiet.delete();
    }
  }

  @Test
  public void anAgentSeesTheVotesInAConversationItJustRead() throws Exception {
    long post = agent.call("board_post", "title", "Supper", "body", "When?")
        .toolResult().path("id").asLong();
    agent.call("poll_create", "post", post, "kind", "choice", "question", "Which pub?",
        "choices", "The Oak");
    McpClient.Response listed = agent.call("poll_list", "post", post);
    assertEquals(1, listed.toolResult().path("count").asInt());
    assertEquals("Which pub?",
        listed.toolResult().path("polls").path(0).path("question").asText());
  }

  @Test
  public void votesFromAnAgentBelongToItsPerson() throws Exception {
    // invariant 119: a community has to be able to ask who did this and get somebody they can talk
    // to. There is no robot in the count.
    long post = agent.call("board_post", "title", "Supper", "body", "When?")
        .toolResult().path("id").asLong();
    long poll = agent.call("poll_create", "post", post, "kind", "choice", "question", "Which?",
        "choices", "The Oak").toolResult().path("id").asLong();
    long option = polls().options(poll).get(0).id();
    agent.call("poll_vote", "option", option);

    long boss = server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
    assertEquals(1, polls().votes(poll).size());
    assertEquals(boss, polls().votes(poll).get(0).userId());
    assertEquals(Poll.Facet.choice, polls().votes(poll).get(0).facet());
  }

  // ---- a member's own assistant --------------------------------------------------------------

  /** a role holding one permission, and somebody in it */
  private void grant(String role, String email, io.hearth.auth.Permission... permissions)
      throws Exception {
    java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
    form.put("action", "save");
    form.put("name", role);
    form.put("label", role);
    form.put("description", "");
    for (io.hearth.auth.Permission permission : permissions) {
      form.put("p_" + permission.name(), "on");
    }
    admin.get("/admin/roles/new");
    admin.submitToAndFollow("/admin/roles", form);
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    server.auth.forDomain("example.org").roles.grant(id, role,
        server.auth.forDomain("example.org").users.byEmail("boss@example.com").id());
  }

  @Test
  public void aMemberWithThePermissionConnectsTheirOwnAssistant() throws Exception {
    // this is the mission: friends whose agents do some of the organising, each acting as its own
    // person rather than everything running under one administrator's name
    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
    McpClient hers = new McpClient(server.port, "example.org")
        .connect(ana, REDIRECT);

    assertFalse("she can read the board", hers.call("board_list").isToolError());
    assertFalse("and take part in it", hers.call("board_post", "title", "Supper",
        "body", "Anyone free?").isToolError());

    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    assertEquals("and what it writes belongs to her", anaId,
        server.auth.forDomain("example.org").board.feed(5).get(0).authorId());
  }

  @Test
  public void aMembersAssistantIsOfferedOnlyWhatSheCanDo() throws Exception {
    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
    McpClient hers = new McpClient(server.port, "example.org")
        .connect(ana, REDIRECT);

    String tools = hers.listTools().body();
    // offered means usable: a tool that could only ever refuse is invariant 149 in a model's hands
    for (String usable : new String[]{"board_list", "board_post", "poll_vote", "poll_create",
        "content_list", "event_list", "place_list"}) {
      assertTrue(usable + " should be offered", tools.contains("\"" + usable + "\""));
    }
    for (String absent : new String[]{"content_save", "template_save", "survey_ask",
        "survey_summarize", "event_save", "place_save", "board_flagged"}) {
      assertFalse(absent + " should not be offered", tools.contains("\"" + absent + "\""));
    }

    // and the boundary is the surface, not the listing: calling one anyway is refused by name
    McpClient.Response refused = hers.call("content_save", "uri", "/about", "title", "Hers",
        "body", "x");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("Write and edit pages"));
    assertTrue("and it tells her to ask rather than to try again differently",
        refused.refusal().contains("tell them"));
  }

  @Test
  public void aMembersAssistantSeesTheSiteAsSheSeesIt() throws Exception {
    // a read is narrowed rather than refused: refusing would make the tool useless to the person
    // it belongs to, and answering in full would hand her a draft she cannot open in a browser
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/open",
        "title", "Open", "kind", "markdown", "template_name", "", "published", "on",
        "body", "everybody"));
    admin.submitToAndFollow("/admin/content", Map.of("action", "save", "uri", "/draft",
        "title", "A draft", "kind", "markdown", "template_name", "", "body", "not yet"));
    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "Announced",
        "starts_on", LocalDate.now().plusDays(4).toString(), "published", "on"));
    admin.submitToAndFollow("/admin/calendar", Map.of("action", "save", "title", "Not announced",
        "starts_on", LocalDate.now().plusDays(5).toString()));

    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
    McpClient hers = new McpClient(server.port, "example.org")
        .connect(ana, REDIRECT);

    String pages = hers.call("content_list").toolResult().toString();
    assertTrue(pages, pages.contains("/open"));
    assertFalse("a draft is absent rather than forbidden", pages.contains("/draft"));

    String events = hers.call("event_list").toolResult().toString();
    assertTrue(events.contains("Announced"));
    assertFalse(events.contains("Not announced"));

    // the admin's own agent still sees both, because they can open both in a browser
    String all = agent.call("content_list").toolResult().toString();
    assertTrue(all.contains("/draft"));
  }

  @Test
  public void theBriefingSaysWhoTheyAreRatherThanWhatTheSoftwareCanDo() throws Exception {
    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
    McpClient hers = new McpClient(server.port, "example.org")
        .connect(ana, REDIRECT);
    String briefing = hers.initialize().body();
    assertTrue(briefing, briefing.contains("who is a member here"));
    assertTrue("it has to say the ceiling out loud, or the model spends three turns finding it",
        briefing.contains("never do anything they could not do themselves"));
    assertFalse("and it must not promise her an admin's reach",
        briefing.contains("shape the site's pages"));

    assertTrue("the admin's briefing still says what an admin can do",
        agent.initialize().body().contains("shape the site's pages"));
  }

  @Test
  public void sheCanSeeWhatSheConnectedAndTakeItAway() throws Exception {
    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
    McpClient hers = new McpClient(server.port, "example.org")
        .connect(ana, REDIRECT);
    assertFalse(hers.call("board_list").isToolError());

    Browser.Page page = ana.get("/self");
    assertTrue(page.body(), page.contains("Assistants"));
    assertTrue("it says the ceiling in her words too", page.contains("held to exactly what you"));

    long id = server.auth.forDomain("example.org").sessions
        .agentsFor(server.auth.forDomain("example.org").users.byEmail("ana@example.com").id())
        .get(0).id();
    ana.submitToAndFollow("/self", Map.of("action", "disconnect", "agent", Long.toString(id)));
    // whoever connected it can take it away, without asking anybody
    assertTrue("and it stops working there and then", hers.call("board_list").status() == 401);
  }

  @Test
  public void takingThePermissionAwayStopsTheAgentAtItsNextRequest() throws Exception {
    // not at the end of the month when the token would have expired anyway
    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
    McpClient hers = new McpClient(server.port, "example.org")
        .connect(ana, REDIRECT);
    assertFalse(hers.call("board_list").isToolError());

    long id = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    server.auth.forDomain("example.org").roles.revoke(id, "helper");
    assertEquals(401, hers.call("board_list").status());
  }

  @Test
  public void somebodyWithNoRoleCannotConnectAtAll() throws Exception {
    // being approved is not enough: a connection is a standing credential held by somebody else's
    // software that can act as this person for a month
    io.hearth.auth.Accounts accounts = server.auth.forDomain("example.org");
    io.hearth.auth.UserRecord member = accounts.users.byEmail("ana@example.com");
    assertFalse(accounts.access.can(member, io.hearth.auth.Permission.agent_connect));
    assertFalse("and it is not an admin thing either -- it opens no screen",
        io.hearth.auth.Permission.agent_connect.implies()
            .contains(io.hearth.auth.Permission.admin_enter));
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
