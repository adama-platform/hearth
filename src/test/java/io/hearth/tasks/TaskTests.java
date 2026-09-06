package io.hearth.tasks;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
import io.hearth.testkit.McpClient;
import io.hearth.testkit.TestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The ranch: work that has steps, habits that graduate, and the sheet that says what today is.
 *
 * The things worth proving are the ones that make this different from a checkbox grid in a
 * spreadsheet: that a task can be at a named step rather than merely unfinished, that a habit's
 * shape survives (which days, not how many), that graduating keeps the evidence, and that the sheet
 * puts overdue work in front of somebody rather than in a section they learn to skip.
 */
public class TaskTests {
  private Configs configs;
  private TestServer server;
  private Browser me;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Ranch\",\"admin_emails\":[\"boss@example.com\"],"
            + "\"mcp\":{\"enabled\":true,\"vendors\":[\"grok\"]}}");
    server = TestServer.ofConfigs(configs.file());
    me = signIn("boss@example.com");
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

  private Tasks tasks() {
    return server.auth.forDomain("example.org").tasks;
  }

  private Processes processes() {
    return server.auth.forDomain("example.org").processes;
  }

  private long meId() throws Exception {
    return server.auth.forDomain("example.org").users.byEmail("boss@example.com").id();
  }

  private McpClient agent() throws Exception {
    return new McpClient(server.port, "example.org")
        .connect(me, "https://grok.com/connectors/callback");
  }

  // ---- tasks with steps -------------------------------------------------------------------------

  @Test
  public void aProcessNeedsAtLeastTwoStatesAndCannotRedefineDone() throws Exception {
    try {
      processes().save("fence", "Fence repair", List.of("surveyed"), meId());
      org.junit.Assert.fail("should have refused");
    } catch (Tasks.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("at least two"));
    }
    try {
      processes().save("fence", "Fence repair", List.of("surveyed", "done"), meId());
      org.junit.Assert.fail("should have refused");
    } catch (Tasks.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("already has"));
    }
  }

  /**
   * A task walks the states its process declares, and nothing else.
   *
   * The refusal is the point: without it a typo puts work into a state no screen lists, and it is
   * found months later by somebody wondering what happened to the fence.
   */
  @Test
  public void aTaskWalksItsProcessAndATypoIsRefused() throws Exception {
    processes().save("fence", "Fence repair",
        List.of("surveyed", "materials", "built", "checked"), meId());
    Tasks.Record task = tasks().add(meId(), "North fence",
        Map.of("process", "fence", "area", "ranch"), processes());

    tasks().moveTo(task.id(), "materials", processes(), meId());
    assertEquals("materials", tasks().byId(task.id()).state());

    try {
      tasks().moveTo(task.id(), "materialz", processes(), meId());
      org.junit.Assert.fail("should have refused");
    } catch (Tasks.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("not a state of fence"));
      assertTrue("and says what the states are", refused.getMessage().contains("surveyed -> "));
    }
    assertEquals("nothing moved", "materials", tasks().byId(task.id()).state());
  }

  @Test
  public void anyTaskCanStillBeDoneOrDropped() throws Exception {
    processes().save("fence", "Fence", List.of("surveyed", "built"), meId());
    Tasks.Record task = tasks().add(meId(), "South fence", Map.of("process", "fence"),
        processes());
    tasks().moveTo(task.id(), "done", processes(), meId());
    assertTrue(tasks().byId(task.id()).isFinished());
    assertNotNull("and when", tasks().byId(task.id()).doneAt());
  }

  @Test
  public void theSheetSaysWhatIsNextForATaskInAProcess() throws Exception {
    processes().save("fence", "Fence", List.of("surveyed", "materials", "built"), meId());
    tasks().add(meId(), "North fence", Map.of("process", "fence"), processes());
    Map<String, Object> sheet = server.auth.forDomain("example.org").tasks
        .sheet(meId(), processes()).anytime().get(0);
    assertEquals("it starts at the process's first state, not at open", "surveyed",
        sheet.get("state"));
    assertEquals(List.of("surveyed", "materials", "built"), sheet.get("process_states"));
    assertEquals("and knows what comes next", "materials", sheet.get("next_state"));
  }

  // ---- habits ----------------------------------------------------------------------------------

  @Test
  public void aHabitNeedsACadence() throws Exception {
    try {
      tasks().add(meId(), "Stretch", Map.of("kind", "habit"));
      org.junit.Assert.fail("should have refused");
    } catch (Tasks.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("needs a cadence"));
    }
  }

  /**
   * The streak counts back from today, and today not being done yet does not break it.
   *
   * Otherwise every streak reads zero every morning, which is wrong and is the single most
   * discouraging thing a tracker can do to somebody.
   */
  @Test
  public void aStreakSurvivesTheMorning() throws Exception {
    Tasks.Record habit = tasks().add(meId(), "Mobility",
        Map.of("kind", "habit", "cadence", "daily"));
    LocalDate today = tasks().today();
    for (int back = 1; back <= 4; back++) {
      tasks().mark(habit.id(), today.minusDays(back), null, meId());
    }
    assertEquals("four yesterdays, and today still to do", 4,
        tasks().standing(tasks().byId(habit.id())).streak());
    assertTrue("and it is due", tasks().standing(tasks().byId(habit.id())).dueToday());

    tasks().mark(habit.id(), today, null, meId());
    assertEquals(5, tasks().standing(tasks().byId(habit.id())).streak());
    assertFalse(tasks().standing(tasks().byId(habit.id())).dueToday());
  }

  @Test
  public void markingTwiceInADayIsTheSameAsOnce() throws Exception {
    Tasks.Record habit = tasks().add(meId(), "Mobility",
        Map.of("kind", "habit", "cadence", "daily"));
    tasks().mark(habit.id(), null, "morning", meId());
    tasks().mark(habit.id(), null, "again", meId());
    assertEquals(1, tasks().marks(habit.id(), 30).size());
  }

  @Test
  public void aGapBreaksTheStreakAndTheShapeSurvives() throws Exception {
    Tasks.Record habit = tasks().add(meId(), "Mobility",
        Map.of("kind", "habit", "cadence", "daily"));
    LocalDate today = tasks().today();
    tasks().mark(habit.id(), today, null, meId());
    tasks().mark(habit.id(), today.minusDays(1), null, meId());
    // a gap at day 2
    tasks().mark(habit.id(), today.minusDays(3), null, meId());
    tasks().mark(habit.id(), today.minusDays(4), null, meId());

    Tasks.Standing standing = tasks().standing(tasks().byId(habit.id()));
    assertEquals("the streak stops at the gap", 2, standing.streak());
    assertEquals("but the shape is all there -- which days, not how many", 4, standing.last7());
  }

  /**
   * Graduating is the success case, and it keeps everything.
   *
   * A habit exists to stop needing to exist. Deleting it would throw away the only evidence the
   * effort was worth anything, which is the difference between this and a checklist.
   */
  @Test
  public void aHabitGraduatesAndKeepsItsMarks() throws Exception {
    Tasks.Record habit = tasks().add(meId(), "Mobility",
        Map.of("kind", "habit", "cadence", "daily"));
    tasks().mark(habit.id(), tasks().today().minusDays(1), null, meId());
    tasks().mark(habit.id(), tasks().today(), null, meId());

    tasks().graduate(habit.id(), meId());
    assertTrue(tasks().byId(habit.id()).isGraduated());
    assertEquals("every mark is still there", 2, tasks().marks(habit.id(), 30).size());

    Tasks.Sheet sheet = tasks().sheet(meId(), processes());
    assertEquals("and it is off the sheet", 0, sheet.today().size() + sheet.anytime().size());
    assertEquals("counted, not forgotten", 1, sheet.graduated());
  }

  @Test
  public void aTaskCannotGraduateAndAHabitIsNotFinishedWithAState() throws Exception {
    Tasks.Record task = tasks().add(meId(), "Fix the gate", Map.of());
    try {
      tasks().graduate(task.id(), meId());
      org.junit.Assert.fail("should have refused");
    } catch (Tasks.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("not graduated"));
    }
    Tasks.Record habit = tasks().add(meId(), "Stretch",
        Map.of("kind", "habit", "cadence", "daily"));
    try {
      tasks().mark(task.id(), null, null, meId());
      org.junit.Assert.fail("should have refused");
    } catch (Tasks.Refused refused) {
      assertTrue(refused.getMessage(), refused.getMessage().contains("is a task"));
    }
    assertNotNull(habit);
  }

  @Test
  public void aWeeklyHabitIsDueWhenTheWeekIsRunningOut() throws Exception {
    Tasks.Record habit = tasks().add(meId(), "Long walk",
        Map.of("kind", "habit", "cadence", "weekly", "per_week", 1));
    Tasks.Standing standing = tasks().standing(tasks().byId(habit.id()));
    assertEquals(1, standing.neededThisWeek());
    assertEquals(0, standing.keptThisWeek());

    tasks().mark(habit.id(), tasks().today(), null, meId());
    assertEquals(1, tasks().standing(tasks().byId(habit.id())).keptThisWeek());
    assertFalse("done for the week", tasks().standing(tasks().byId(habit.id())).dueToday());
  }

  // ---- the sheet -------------------------------------------------------------------------------

  /**
   * Overdue work is in today's list, not a section of its own.
   *
   * A separate overdue section is where things go to be ignored. In today's list it is simply what
   * has to happen, which is what it is.
   */
  @Test
  public void theSheetPutsTodayOverdueAndTheHorizonWhereTheyBelong() throws Exception {
    LocalDate today = tasks().today();
    tasks().add(meId(), "Overdue thing",
        Map.of("due_on", today.minusDays(3).toString()));
    tasks().add(meId(), "Due today", Map.of("due_on", today.toString()));
    tasks().add(meId(), "Next week", Map.of("due_on", today.plusDays(6).toString()));
    tasks().add(meId(), "Far off", Map.of("due_on", today.plusDays(90).toString()));
    tasks().add(meId(), "Someday", Map.of());

    Tasks.Sheet sheet = tasks().sheet(meId(), processes());
    assertEquals("overdue and due today, together", 2, sheet.today().size());
    assertEquals(3L, sheet.today().stream()
        .filter(row -> row.containsKey("overdue_by_days"))
        .findFirst().orElseThrow().get("overdue_by_days"));
    assertEquals(1, sheet.horizon().size());
    assertEquals("undated and beyond the horizon are both there to pull from",
        2, sheet.anytime().size());
  }

  @Test
  public void aFinishedTaskLeavesTheSheet() throws Exception {
    Tasks.Record task = tasks().add(meId(), "Fix the gate",
        Map.of("due_on", tasks().today().toString()));
    assertEquals(1, tasks().sheet(meId(), processes()).today().size());
    tasks().moveTo(task.id(), "done", processes(), meId());
    assertEquals(0, tasks().sheet(meId(), processes()).today().size());
  }

  // ---- what an agent gets -----------------------------------------------------------------------

  @Test
  public void anAgentRunsTheWholeThing() throws Exception {
    McpClient grok = agent();

    grok.call("process_save", "process", "fence", "title", "Fence repair",
        "states", List.of("surveyed", "materials", "built", "checked"));
    grok.call("task_add", "title", "North fence", "process", "fence", "area", "ranch");
    grok.call("task_add", "title", "Mobility", "kind", "habit", "cadence", "daily");

    String sheet = grok.call("day_sheet").toolResult().toString();
    assertTrue(sheet, sheet.contains("North fence"));
    assertTrue("a new daily habit is due today", sheet.contains("Mobility"));
    assertTrue("and the sheet says what step is next", sheet.contains("next_state"));

    long habitId = tasks().all(meId(), false).stream()
        .filter(Tasks.Record::isHabit).findFirst().orElseThrow().id();
    String marked = grok.call("habit_mark", "id", (int) habitId).toolResult().toString();
    assertTrue(marked, marked.contains("\"streak\":1"));

    String graduated = grok.call("habit_graduate", "id", (int) habitId).toolResult().toString();
    assertTrue(graduated, graduated.contains("\"graduated\":true"));
  }

  @Test
  public void anAgentCannotReachSomebodyElsesList() throws Exception {
    Browser ana = signIn("ana@example.com");
    long anaId = server.auth.forDomain("example.org").users.byEmail("ana@example.com").id();
    Tasks.Record hers = tasks().add(anaId, "Her private thing", Map.of());

    String refusal = agent().call("task_move", "id", (int) hers.id(), "state", "done").refusal();
    assertTrue(refusal, refusal.contains("no task " + hers.id() + " on your list"));
    assertEquals("and it is untouched", "open", tasks().byId(hers.id()).state());
    assertNotNull(ana);
  }

  @Test
  public void theToolsTellAnAgentWhatAHabitIsFor() throws Exception {
    String tools = agent().listTools().body();
    assertTrue("graduating is the success case", tools.contains("stop needing to exist"));
    assertTrue("and the sheet is where to start", tools.contains("THE ONE TO START WITH"));
    assertTrue("and overdue work is not hidden", tools.contains("where things go to be ignored"));
  }

  // ---- challenges ------------------------------------------------------------------------------

  /**
   * A challenge graduates itself the day after it ends.
   *
   * That is what putting an end on a habit is FOR. A thirty-day challenge still asking on day forty
   * is exactly the dispiriting stale checkbox this is meant to replace, and "the user should delete
   * it" is not an answer -- the whole point is that it finishes on its own.
   */
  @Test
  public void aChallengeGraduatesItselfWhenItsLastDayHasPassed() throws Exception {
    LocalDate today = tasks().today();
    Tasks.Record challenge = tasks().add(meId(), "Thirty days of mobility",
        Map.of("kind", "habit", "cadence", "daily",
            "starts_on", today.minusDays(31).toString(),
            "ends_on", today.minusDays(1).toString()));
    tasks().mark(challenge.id(), today.minusDays(2), null, meId());

    Tasks.Sheet sheet = tasks().sheet(meId(), processes());
    assertEquals("off the sheet", 0, sheet.today().size() + sheet.anytime().size());
    assertEquals("and counted as finished", 1, sheet.graduated());
    assertTrue(tasks().byId(challenge.id()).isGraduated());
    assertEquals("keeping what it earned", 1, tasks().marks(challenge.id(), 60).size());
  }

  @Test
  public void aChallengeThatHasNotStartedIsOnTheHorizonRatherThanTodaysList() throws Exception {
    LocalDate today = tasks().today();
    tasks().add(meId(), "Winter conditioning",
        Map.of("kind", "habit", "cadence", "daily",
            "starts_on", today.plusDays(5).toString(),
            "ends_on", today.plusDays(40).toString()));

    Tasks.Sheet sheet = tasks().sheet(meId(), processes());
    assertEquals("not asking yet", 0, sheet.today().size());
    assertEquals(1, sheet.horizon().size());
    assertEquals(today.plusDays(5).toString(), sheet.horizon().get(0).get("starts_on"));
  }

  @Test
  public void aRunningChallengeIsOnTodaysListWithDaysLeft() throws Exception {
    LocalDate today = tasks().today();
    tasks().add(meId(), "Thirty days of mobility",
        Map.of("kind", "habit", "cadence", "daily",
            "starts_on", today.minusDays(3).toString(),
            "ends_on", today.plusDays(26).toString()));

    Tasks.Sheet sheet = tasks().sheet(meId(), processes());
    assertEquals(1, sheet.today().size());
    assertEquals(Boolean.TRUE, sheet.today().get(0).get("challenge"));
    assertEquals(26L, sheet.today().get(0).get("days_left"));
  }

  // ---- the daily docket --------------------------------------------------------------------------

  /**
   * Nothing on the docket means no email, and that is the feature.
   *
   * A daily message that arrives whether or not it has anything to say gets filtered within a
   * fortnight, and then the one that mattered goes into the same folder.
   */
  @Test
  public void aDocketWithNothingOnItIsNotSent() throws Exception {
    server.auth.forDomain("example.org").users.approve(meId(), null);
    Docket docket = new Docket(server.mail());
    server.mail().clear();

    Docket.Sent sent = docket.run(server.tree.resolve("example.org"),
        server.auth.forDomain("example.org"));
    assertEquals(0, sent.people());
    assertEquals(0, server.mail().forFlow("docket").size());
  }

  @Test
  public void aDocketWithSomethingOnItGoesOutOncePerDay() throws Exception {
    server.auth.forDomain("example.org").users.approve(meId(), null);
    tasks().add(meId(), "Move the heifers",
        Map.of("due_on", tasks().today().toString(), "area", "ranch"));
    tasks().add(meId(), "Worm the calves",
        Map.of("due_on", tasks().today().plusDays(3).toString()));
    Docket docket = new Docket(server.mail());
    server.mail().clear();

    assertEquals(1, docket.run(server.tree.resolve("example.org"),
        server.auth.forDomain("example.org")).people());
    assertEquals(1, server.mail().forFlow("docket").size());
    String body = server.mail().forFlow("docket").get(0).note();
    assertTrue(body, body.contains("Move the heifers"));
    assertTrue("with what it belongs to", body.contains("(ranch)"));
    assertTrue("and what is coming", body.contains("Worm the calves"));

    assertEquals("a second pass on the same day sends nothing", 0,
        docket.run(server.tree.resolve("example.org"),
            server.auth.forDomain("example.org")).people());
  }

  @Test
  public void aDocketSaysHowLateSomethingIs() throws Exception {
    server.auth.forDomain("example.org").users.approve(meId(), null);
    tasks().add(meId(), "Fix the gate",
        Map.of("due_on", tasks().today().minusDays(4).toString()));
    Docket docket = new Docket(server.mail());
    server.mail().clear();
    docket.run(server.tree.resolve("example.org"), server.auth.forDomain("example.org"));
    assertTrue(server.mail().forFlow("docket").get(0).note().contains("4 day(s) late"));
  }

  @Test
  public void somebodyWaitingForApprovalGetsNoDocket() throws Exception {
    Browser waiting = signIn("new@example.com");
    long id = server.auth.forDomain("example.org").users.byEmail("new@example.com").id();
    tasks().add(id, "Their thing", Map.of("due_on", tasks().today().toString()));
    server.mail().clear();

    new Docket(server.mail()).run(server.tree.resolve("example.org"),
        server.auth.forDomain("example.org"));
    assertEquals(0, server.mail().forFlow("docket").size());
    assertNotNull(waiting);
  }
}
