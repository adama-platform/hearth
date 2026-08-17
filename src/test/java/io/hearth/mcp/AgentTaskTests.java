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
import static org.junit.Assert.assertTrue;

/**
 * An assistant setting somebody's routine up, and then helping them tune it.
 *
 * This is the scenario the whole feature is for: a model writes the exercise definitions with
 * enough in them to look the form up, puts them on a project, records what actually happened with
 * the timestamp, collects the three scores, and reads back which of them is worth its place. What
 * makes it safe rather than alarming is the other half -- it is always this person's own log, there
 * is no argument anywhere for whose, and every call is written into the AI log under two names.
 */
public class AgentTaskTests {
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
    grant("helper", "ana@example.com", io.hearth.auth.Permission.agent_connect);
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

  @Test
  public void anAssistantBuildsARoutineAndTunesIt() throws Exception {
    // 1. what the thing is, with enough to look the form up
    McpClient.Response wrote = hers.call("task_definition_save",
        "name", "Bulgarian split squat", "measured_in", "weight_reps",
        "summary", "one leg at a time",
        "instructions", "Back foot on a bench. Front shin roughly vertical. Knee tracks the toe.",
        "reference_url", "https://example.org/form", "tags", "legs", "sets", 3, "reps", 8);
    long def = wrote.toolResult().path("id").asLong();
    assertEquals("weight_reps", wrote.toolResult().path("measured_in").asText());
    assertEquals("you", wrote.toolResult().path("belongs_to").asText());

    // 2. somewhere to put it, in the words this person uses
    long project = hers.call("task_project_save", "name", "Legs", "calls_one", "exercise",
        "calls_many", "exercises").toolResult().path("id").asLong();
    long task = hers.call("task_add", "project", project, "definition", def, "repeat_days", 7)
        .toolResult().path("id").asLong();

    // 3. what actually happened, when it happened
    McpClient.Response set = hers.call("task_record", "task", task, "weight", 40, "reps", 8,
        "difficulty", 4, "time_cost", 2, "impact", 5, "note", "left side weaker");
    assertEquals("40kg x 8", set.toolResult().path("recorded").asText());
    assertFalse("the timestamp is the point of the record",
        set.toolResult().path("at").asText().isBlank());
    hers.call("task_record", "task", task, "weight", 40, "reps", 8,
        "difficulty", 4, "time_cost", 2, "impact", 5);

    // 4. and reading back whether it is worth its place
    McpClient.Response review = hers.call("task_review");
    assertEquals(1, review.toolResult().path("count").asInt());
    var row = review.toolResult().path("definitions").path(0);
    assertEquals("Bulgarian split squat", row.path("name").asText());
    assertEquals(5.0, row.path("impact").asDouble(), 0.001);
    assertEquals(2.0, row.path("time_cost").asDouble(), 0.001);
    assertEquals("impact over time is the number to tune towards", 2.5,
        row.path("impact_per_time").asDouble(), 0.001);
    assertTrue(row.path("verdict").asText(), row.path("verdict").asText().contains("keep this"));

    // and the instructions come back where somebody would read them
    McpClient.Response read = hers.call("task_definition", "id", def);
    assertTrue(read.toolResult().path("instructions").asText().contains("shin roughly vertical"));
    assertEquals(2, read.toolResult().path("sets").asInt());
  }

  @Test
  public void theRoutineIsAlwaysThePersonsOwn() throws Exception {
    // there is no argument anywhere in this half for whose log this is, and that is the design:
    // a tool with a "user" parameter is one prompt away from reading somebody else's
    long hersProject = hers.call("task_project_save", "name", "Her training")
        .toolResult().path("id").asLong();

    // the admin's own agent, which holds every permission there is, still cannot see it
    McpClient.Response refused = theirs.call("task_project", "id", hersProject);
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("no project with that id"));
    assertFalse(theirs.call("task_projects").toolResult().toString().contains("Her training"));

    // nor write to it
    assertTrue(theirs.call("task_add", "project", hersProject, "title", "sneaked in").isToolError());
    assertTrue(accounts().tasks.tasksIn(hersProject).isEmpty());

    String tools = hers.listTools().body();
    assertFalse("no tool takes a whose", tools.contains("\"user\""));
  }

  @Test
  public void anUnknownMeasureIsRefusedWithTheListRatherThanGuessedAt() throws Exception {
    McpClient.Response refused = hers.call("task_definition_save", "name", "Something",
        "measured_in", "kilometres_per_hour");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("weight_reps"));
    assertTrue("and it says what 'none' is for", refused.refusal().contains("none"));
  }

  @Test
  public void aSharedDefinitionNeedsThePermissionToKeepTheLibrary() throws Exception {
    // writing your own is like writing your own todo list; writing into the community's library is
    // a different act
    McpClient.Response refused = hers.call("task_definition_save", "name", "Push-up",
        "measured_in", "bodyweight_reps", "share", true);
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("shared library"));

    McpClient.Response allowed = theirs.call("task_definition_save", "name", "Push-up",
        "measured_in", "bodyweight_reps",
        "instructions", "Hands under the shoulders. Body in one line.", "share", true);
    assertEquals("the community", allowed.toolResult().path("belongs_to").asText());
    // and she can now see it and take a copy
    assertTrue(hers.call("task_definitions").toolResult().toString().contains("Push-up"));
  }

  @Test
  public void tickingSomethingOffMovesARepeatRatherThanClosingIt() throws Exception {
    long project = hers.call("task_project_save", "name", "Weekly")
        .toolResult().path("id").asLong();
    long task = hers.call("task_add", "project", project, "title", "Water the plants",
        "repeat_days", 7).toolResult().path("id").asLong();

    McpClient.Response done = hers.call("task_complete", "id", task, "time_cost", 1, "impact", 3);
    assertTrue(done.toolResult().path("done").asBoolean());
    assertFalse("it says when it comes back, so the model can say so",
        done.toolResult().path("comes_back_on").asText().isBlank());
    assertFalse("and it is not closed", accounts().tasks.task(task).done());
  }

  @Test
  public void aMissingRatingIsRecordedAsMissing() throws Exception {
    // guessing them would fill a history with opinions nobody held, and the whole reason they exist
    // is to find the exercise that is exhausting and useless
    long project = hers.call("task_project_save", "name", "Quiet")
        .toolResult().path("id").asLong();
    long def = hers.call("task_definition_save", "name", "Plank", "measured_in", "duration")
        .toolResult().path("id").asLong();
    long task = hers.call("task_add", "project", project, "definition", def)
        .toolResult().path("id").asLong();
    hers.call("task_record", "task", task, "seconds", 90);

    long me = accounts().users.byEmail("ana@example.com").id();
    var entry = accounts().tasks.entriesForTask(task, me, 5).get(0);
    assertEquals("90s recorded", Integer.valueOf(90), entry.seconds());
    assertNotNull(entry.recordedAt());
    assertEquals("and nothing invented about how it felt", null, entry.impact());

    // the review leaves it out rather than treating silence as average
    assertTrue(hers.call("task_review").toolResult().path("definitions").path(0)
        .path("impact").isNull());
    assertTrue(hers.call("task_review").toolResult().path("how_to_read_this").asText()
        .contains("nobody has said"));
  }

  @Test
  public void everyMeasureRoundTripsThroughTheTools() throws Exception {
    // seven kinds of thing, four columns, one description everything reads back through
    long project = hers.call("task_project_save", "name", "Everything")
        .toolResult().path("id").asLong();
    Object[][] cases = {
        {"weight_reps", new String[]{"weight", "60", "reps", "8"}, "60kg x 8"},
        {"bodyweight_reps", new String[]{"reps", "12"}, "12 reps"},
        {"weighted_bodyweight", new String[]{"weight", "-20", "reps", "5"},
            "-20kg assisted x 5"},
        {"duration", new String[]{"seconds", "45"}, "45s"},
        {"duration_weight", new String[]{"seconds", "60", "weight", "24"}, "1m at 24kg"},
        {"distance_duration", new String[]{"distance", "5000", "seconds", "1560"},
            "5km in 26m"},
        {"weight_distance", new String[]{"weight", "40", "distance", "40"}, "40kg for 40m"},
    };
    for (Object[] one : cases) {
      String measure = (String) one[0];
      String[] pairs = (String[]) one[1];
      long def = hers.call("task_definition_save", "name", "Test " + measure,
          "measured_in", measure).toolResult().path("id").asLong();
      long task = hers.call("task_add", "project", project, "definition", def)
          .toolResult().path("id").asLong();
      Object[] args = new Object[pairs.length + 2];
      args[0] = "task";
      args[1] = task;
      System.arraycopy(pairs, 0, args, 2, pairs.length);
      McpClient.Response recorded = hers.call("task_record", args);
      assertEquals(measure, one[2], recorded.toolResult().path("recorded").asText());
    }
  }

  @Test
  public void anAssistantSetsUpASupersetAndIsToldWhereTheRestGoes() throws Exception {
    long project = hers.call("task_project_save", "name", "Mondays")
        .toolResult().path("id").asLong();
    long press = hers.call("task_definition_save", "name", "Bench press",
        "measured_in", "weight_reps", "rest_seconds", 180).toolResult().path("id").asLong();
    long row = hers.call("task_definition_save", "name", "Row", "measured_in", "weight_reps",
        "rest_seconds", 90).toolResult().path("id").asLong();
    long pressTask = hers.call("task_add", "project", project, "definition", press)
        .toolResult().path("id").asLong();
    long rowTask = hers.call("task_add", "project", project, "definition", row)
        .toolResult().path("id").asLong();

    McpClient.Response grouped = hers.call("task_group", "id", pressTask,
        "name", "Push and pull", "mode", "related");
    assertEquals("related", grouped.toolResult().path("mode").asText());
    assertTrue("it tells the model the rule rather than leaving it to guess",
        grouped.toolResult().path("how").asText().contains("after the round"));

    hers.call("task_group", "id", rowTask, "name", "Push and pull", "mode", "related");
    String read = hers.call("task_project", "id", project).toolResult().toString();
    assertTrue(read.contains("Push and pull"));
    assertTrue(read.contains("\"group_mode\":\"related\""));

    // and taking one out is the same tool with an empty name
    McpClient.Response out = hers.call("task_group", "id", rowTask, "name", "");
    assertTrue(out.toolResult().path("group").isNull());
    assertFalse(accounts().tasks.task(rowTask).grouped());
  }

  @Test
  public void anUnknownGroupingIsRefusedWithBothWords() throws Exception {
    long project = hers.call("task_project_save", "name", "Mondays")
        .toolResult().path("id").asLong();
    long task = hers.call("task_add", "project", project, "title", "Something")
        .toolResult().path("id").asLong();
    McpClient.Response refused = hers.call("task_group", "id", task, "name", "x",
        "mode", "circuit");
    assertTrue(refused.isToolError());
    assertTrue(refused.refusal(), refused.refusal().contains("related"));
    assertTrue(refused.refusal().contains("sequenced"));
  }

  @Test
  public void restIsSetOnTheDefinitionAndComesBackWithIt() throws Exception {
    long def = hers.call("task_definition_save", "name", "Squat", "measured_in", "weight_reps",
        "rest_seconds", 180).toolResult().path("id").asLong();
    assertEquals(180, hers.call("task_definition", "id", def)
        .toolResult().path("rest_seconds").asInt());
    assertTrue("and the tool says why it lives there",
        hers.listTools().body().contains("property of the movement"));
  }

  @Test
  public void theEstimateComesBackWithItsOwnCaveat() throws Exception {
    long project = hers.call("task_project_save", "name", "Mondays")
        .toolResult().path("id").asLong();
    long def = hers.call("task_definition_save", "name", "Squat", "measured_in", "weight_reps")
        .toolResult().path("id").asLong();
    long task = hers.call("task_add", "project", project, "definition", def)
        .toolResult().path("id").asLong();
    hers.call("task_record", "task", task, "weight", 120, "reps", 3);

    var read = hers.call("task_definition", "id", def).toolResult();
    assertEquals(132.0, read.path("estimated_one_rep_max").asDouble(), 0.001);
    assertTrue("a model reading this has to be told how far to trust it",
        read.path("one_rep_max_means").asText().contains("direction, not a target"));
    assertTrue(read.path("one_rep_max_means").asText().contains("12 reps"));

    // and it turns up in the review, where a model would use it to suggest a change
    assertEquals(132.0, hers.call("task_review").toolResult().path("definitions").path(0)
        .path("estimated_one_rep_max").asDouble(), 0.001);

    // nothing with a bar means nothing to estimate, and the field is absent rather than zero
    long plank = hers.call("task_definition_save", "name", "Plank", "measured_in", "duration")
        .toolResult().path("id").asLong();
    assertTrue(hers.call("task_definition", "id", plank).toolResult()
        .path("estimated_one_rep_max").isMissingNode());
  }

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
