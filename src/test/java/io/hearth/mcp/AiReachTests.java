package io.hearth.mcp;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What a model can reach, now that it can reach most of it.
 *
 * The organising nobody volunteers for is the job this is actually for -- "put the supper club on
 * the second Tuesday for the next six months" is twelve minutes of clicking and one sentence. So
 * the calendar and the board are full CRUD, and the tests worth having are the ones about where
 * that stops: it cannot moderate, it cannot decide a suggestion, and it cannot read a direct
 * message.
 */
public class AiReachTests {
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

  private io.hearth.auth.Accounts accounts() {
    return server.auth.forDomain("example.org");
  }

  /** the id a survey tool answered with */
  private static long idIn(McpTools.Result result) {
    return Long.parseLong(String.valueOf(result.subject()));
  }

  /** a connection an administrator authorised, which is the only kind there is */
  private AiSurface surface() {
    try {
      io.hearth.auth.UserRecord boss =
          server.auth.forDomain("example.org").users.byEmail("boss@example.com");
      return new AiSurface(server.auth.forDomain("example.org"), false)
          .withBoardExpiry(60)
          .actingAs(boss.id(), boss.email());
    } catch (java.sql.SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Test
  public void aConnectionActingForNobodyCannotPost() throws Exception {
    // there is no "the AI" account, so an agent with no session behind it has nobody for its words
    // to belong to -- and inventing one by picking the first administrator would attribute somebody
    // else's post to a person who was not involved
    try {
      new AiSurface(server.auth.forDomain("example.org"), false)
          .savePost(null, "Notes", "From the meeting.");
      fail("expected a refusal");
    } catch (AiSurface.Refused expected) {
      assertTrue(expected.getMessage().contains("not acting for anybody"));
    }
  }

  /**
   * A read-only connection, and it still acts for somebody.
   *
   * Every real connection does -- `McpRoutes` calls `actingAs` before it hands the surface to a
   * tool -- and a surface with no actor now sees nothing privileged at all, because "acting for
   * nobody" is not a state that should be able to read a draft. Building one without an actor here
   * was testing a shape production never has.
   */
  private AiSurface readOnly() {
    try {
      io.hearth.auth.UserRecord boss =
          server.auth.forDomain("example.org").users.byEmail("boss@example.com");
      return new AiSurface(server.auth.forDomain("example.org"), true)
          .actingAs(boss.id(), boss.email());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  // ---- events -----------------------------------------------------------------------------------

  @Test
  public void aModelCanKeepTheCalendar() throws Exception {
    Map<String, Object> made = surface().saveEvent(null, Map.of("title", "Supper club",
        "starts_on", "2030-04-09", "start_time", "7pm", "published", true));
    assertTrue((Boolean) made.get("created"));
    long id = ((Number) made.get("id")).longValue();

    assertEquals(1, surface().listEvents(false).size());
    assertNotNull(surface().getEvent(id));
    assertEquals("Supper club", surface().getEvent(id).get("title"));

    Map<String, Object> changed = surface().saveEvent(id, Map.of("title", "Supper club, moved",
        "starts_on", "2030-04-16"));
    assertFalse((Boolean) changed.get("created"));
    assertEquals("2030-04-16", surface().getEvent(id).get("starts_on"));

    assertTrue((Boolean) surface().deleteEvent(id).get("deleted"));
    assertNull(surface().getEvent(id));
  }

  @Test
  public void anEventWithNoRealDayIsRefusedRatherThanGuessedAt() throws Exception {
    // people turn up on the day it says, so a default here would be worse than a refusal
    for (Map<String, Object> bad : List.<Map<String, Object>>of(
        Map.of("title", "Someday"),
        Map.of("title", "Someday", "starts_on", "next tuesday"),
        Map.of("starts_on", "2030-04-09"))) {
      try {
        surface().saveEvent(null, bad);
        fail("expected a refusal for " + bad);
      } catch (AiSurface.Refused expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test
  public void anEventCanBeAtAPlaceByItsSlug() throws Exception {
    admin.submitToAndFollow("/admin/places/kinds", Map.of("action", "save", "slug", "venue",
        "label", "Venue", "plural", "Venues", "published", "on"));
    admin.submitToAndFollow("/admin/places", Map.of("action", "save", "type_slug", "venue",
        "slug", "the-oak", "name", "The Oak", "published", "on"));

    Map<String, Object> made = surface().saveEvent(null, Map.of("title", "Games night",
        "starts_on", "2030-04-09", "place_slug", "the-oak", "published", true));
    long id = ((Number) made.get("id")).longValue();
    assertNotNull(server.auth.forDomain("example.org").calendar.byId(id).placeId());

    try {
      surface().saveEvent(null, Map.of("title", "Nowhere", "starts_on", "2030-04-09",
          "place_slug", "not-a-place"));
      fail("a place it invented is refused rather than quietly dropped");
    } catch (AiSurface.Refused expected) {
      assertTrue(expected.getMessage().contains("not-a-place"));
    }
  }

  @Test
  public void aReadOnlyConnectionReadsAndDoesNotWrite() throws Exception {
    surface().saveEvent(null, Map.of("title", "Supper club", "starts_on", "2030-04-09"));
    assertEquals(1, readOnly().listEvents(false).size());
    try {
      readOnly().saveEvent(null, Map.of("title", "Nope", "starts_on", "2030-04-09"));
      fail("expected a refusal");
    } catch (AiSurface.Refused expected) {
      assertTrue(expected.getMessage().contains("read only"));
    }
  }

  @Test
  public void aModelDoesNotDecideWhatTheCommunityIsDoing() throws Exception {
    Browser ana = member("ana@example.com");
    ana.submitToAndFollow("/events", Map.of("action", "suggest", "title", "Karaoke",
        "starts_on", "2030-04-09"));
    // it can see one, because it can see the calendar -- and there is no tool that accepts it.
    // Saying yes to an event is the community deciding what it does.
    assertTrue(surface().listEvents(true).stream()
        .anyMatch(row -> "suggested".equals(row.get("state"))));
    for (McpTools.Tool tool : new McpTools(surface()).all()) {
      assertFalse(tool.name(), tool.name().contains("accept"));
      assertFalse(tool.name(), tool.name().contains("suggest"));
    }
  }

  // ---- the board --------------------------------------------------------------------------------

  @Test
  public void aModelCanWriteUpWhatWasDecided() throws Exception {
    Map<String, Object> posted = surface().savePost(null, "What we decided",
        "Second Tuesday, at The Oak.");
    assertTrue((Boolean) posted.get("created"));
    long id = ((Number) posted.get("id")).longValue();

    assertEquals(1, surface().listPosts(10).size());
    Map<String, Object> read = surface().readPost(id);
    assertEquals("What we decided", read.get("title"));
    assertTrue(String.valueOf(read.get("body")).contains("The Oak"));

    surface().comment(id, "And bring a game.");
    assertEquals(1, ((List<?>) surface().readPost(id).get("comments")).size());

    surface().savePost(id, "What we decided (updated)", "Second Tuesday, at The Oak. 7pm.");
    assertEquals("What we decided (updated)", surface().readPost(id).get("title"));
  }

  @Test
  public void whatAModelPostsBelongsToAPersonRatherThanToARobot() throws Exception {
    surface().savePost(null, "Notes", "From the meeting.");
    Map<String, Object> post = surface().listPosts(10).get(0);
    long bossId = server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
    assertEquals("a community has to be able to ask who put this here and get somebody they can"
        + " talk to", bossId, post.get("who_id"));
    assertEquals("named, because a model has no use for an address it could repeat back",
        "a member", post.get("who"));
    assertFalse("and a connector is never handed one",
        String.valueOf(post).contains("@example.com"));
  }

  @Test
  public void aModelCannotModerate() throws Exception {
    Browser ana = member("ana@example.com");
    ana.get("/board");
    ana.submitTo("/board", Map.of("action", "post", "title", "Ana's post", "body", "hello"));
    // pinning, locking and removing are powers a community gave a person; there is no tool for any
    // of them, which is a stronger rule than a check somebody could route around
    for (McpTools.Tool tool : new McpTools(surface()).all()) {
      assertFalse(tool.name(), tool.name().startsWith("board_remove"));
      assertFalse(tool.name(), tool.name().startsWith("board_pin"));
      assertFalse(tool.name(), tool.name().startsWith("board_lock"));
    }
  }

  @Test
  public void aModelCanStartADiscussionAndItLivesLikeEverybodyElses() throws Exception {
    McpTools tools = new McpTools(surface());
    McpTools.Result started = tools.call("board_post",
        json("{\"title\":\"Saturday\",\"body\":\"Who is bringing what?\"}"));
    assertTrue(String.valueOf(started.detail()), String.valueOf(started.detail())
        .contains("posted Saturday"));
    io.hearth.board.Board.Post made = accounts().board.all(5).get(0);
    assertEquals("Saturday", made.title());
    assertEquals("attributed to the person whose connection this is, never to a robot",
        accounts().users.byEmail("boss@example.com").id(), made.authorId());
    assertNotNull("and it expires like anybody else's rather than living forever",
        made.expiresAt());

    assertTrue(String.valueOf(tools.call("board_reply",
        json("{\"id\":" + made.id() + ",\"body\":\"I will bring the flour.\"}")).detail())
        .contains("replied"));
  }

  @Test
  public void theSurveyIsFullyUnderAModelsControl() throws Exception {
    McpTools tools = new McpTools(surface());
    long first = idIn(tools.call("survey_ask",
        json("{\"prompt\":\"What can you cook?\",\"kind\":\"text\"}")));
    long second = idIn(tools.call("survey_ask",
        json("{\"prompt\":\"Which evenings suit you?\",\"kind\":\"text\"}")));

    tools.call("survey_delete", json("{\"id\":" + first + "}"));
    assertTrue("retired", accounts().people.questionById(first).deleted());
    tools.call("survey_restore", json("{\"id\":" + first + "}"));
    assertFalse("and asking it again is a thing it can decide to do",
        accounts().people.questionById(first).deleted());

    // order matters: people answer three at a time, so what is first is what most people answer
    tools.call("survey_reorder", json("{\"ids\":[" + second + "," + first + "]}"));
    assertTrue(accounts().people.questionById(second).position()
        < accounts().people.questionById(first).position());
  }

  @Test
  public void theSpecTellsAModelHowToBuildASite() throws Exception {
    // a tool description is a prompt, and a model has no screen to read: without this it cannot
    // know that picking a member listing changes what the uri field means
    McpTools tools = new McpTools(surface());
    String spec = tools.call("site_spec", json("{}")).payload().toString();
    assertTrue(spec, spec.contains("member_id"));
    assertTrue(spec.contains("pagination.next_url"));
    assertTrue("and which kinds of place a listing can be narrowed to",
        spec.contains("place_kind_values"));
    assertTrue(spec.contains("published_date"));
  }

  @Test
  public void aModelCanPublishADirectoryIndexWithItsOwnTemplate() throws Exception {
    McpTools tools = new McpTools(surface());
    tools.call("template_save", json("{\"name\":\"post\",\"body\":\"<h1>{{title}}</h1>\","
        + "\"directory\":true,\"directory_path\":\"/blog\","
        + "\"directory_body\":\"{{#entries}}<a href=\\\"{{uri}}\\\">{{title}}</a>{{/entries}}\"}"));
    io.hearth.content.TemplateRecord saved = accounts().site.store().templateByName("post");
    assertTrue(saved.publishesDirectory());
    assertEquals("/blog", saved.directoryPath());
    assertTrue("two templates, not one file branching on itself", saved.hasOwnIndex());

    // and writing the page body again does not switch the index off
    tools.call("template_save", json("{\"name\":\"post\",\"body\":\"<h1>changed</h1>\"}"));
    assertTrue(accounts().site.store().templateByName("post").publishesDirectory());
  }

  @Test
  public void theToolsAreOfferedAndCallable() throws Exception {
    McpTools tools = new McpTools(surface());
    for (String name : List.of("event_list", "event_get", "event_save", "event_delete",
        "board_list", "board_read", "board_post", "board_reply")) {
      assertTrue(name, tools.has(name));
    }
    McpTools.Result made = tools.call("event_save", json(
        "{\"title\":\"Supper club\",\"starts_on\":\"2030-04-09\",\"published\":true}"));
    assertTrue(String.valueOf(made.detail()).contains("created"));
    assertTrue(String.valueOf(tools.call("event_list", json("{}")).detail()).contains("event"));
  }

  private static com.fasterxml.jackson.databind.JsonNode json(String raw) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  private Browser member(String email) throws Exception {
    Browser browser = signIn(email);
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(idOf(email))));
    return browser;
  }

  private Browser signIn(String email) throws Exception {
    Browser browser = new Browser(server.port, "example.org");
    browser.get("/register");
    browser.submit(Map.of("email", email));
    browser.submit(Map.of("code", server.mail().lastCodeFor(email)));
    return browser;
  }
}
