package io.hearth.tasks;

import io.hearth.testkit.Browser;
import io.hearth.testkit.Configs;
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
 * Projects, what things are, and what somebody actually did.
 *
 * Two things are being proved here and the second is the important one. The first is the
 * arithmetic: seven kinds of measurement, one set of four columns, and a history that has to mean
 * the same thing in every one of them. The second is privacy -- a training log is the most personal
 * thing this server holds, and the design rests on ownership being enforced in a query rather than
 * remembered by a handler.
 */
public class TaskTests {
  private Configs configs;
  private TestServer server;
  private Browser admin;
  private Browser ana;
  private Browser bo;

  @Before
  public void setUp() throws Exception {
    configs = Configs.dir().domain("example.org",
        "{\"name\":\"Example\",\"admin_emails\":[\"boss@example.com\"]}");
    server = TestServer.ofConfigs(configs.file());
    admin = signIn("boss@example.com");
    ana = member("ana@example.com");
    bo = member("bo@example.com");
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

  private TaskStore tasks() {
    return server.auth.forDomain("example.org").tasks;
  }

  private long idOf(String email) throws Exception {
    return server.auth.forDomain("example.org").users.byEmail(email).id();
  }

  // ---- the measurements ------------------------------------------------------------------------

  @Test
  public void everyMeasureSaysWhatItRecordsAndReadsBackTheSameWay() {
    // one set of four columns for seven kinds of thing; what a measure does is say which of them
    // are asked for and what the answer reads like
    assertEquals(List.of(Measure.Field.weight, Measure.Field.reps),
        Measure.weight_reps.fields());
    assertEquals("60kg x 8", Measure.weight_reps.describe(60.0, 8, null, null));
    assertEquals("12 reps", Measure.bodyweight_reps.describe(null, 12, null, null));
    assertEquals("45s", Measure.duration.describe(null, null, 45, null));
    assertEquals("a minute and a half reads as one", "1m 30s",
        Measure.duration.describe(null, null, 90, null));
    assertEquals("5km in 26m", Measure.distance_duration.describe(null, null, 1560, 5000.0));
    assertEquals("40kg for 40m", Measure.weight_distance.describe(40.0, null, null, 40.0));
    assertFalse("a plain todo has no sets", Measure.none.hasSets());
  }

  @Test
  public void assistanceAndAddedWeightAreOneAxis() {
    // somebody's first unassisted rep is the moment this number crosses zero, and two measures for
    // it would put that moment in the gap between two charts
    assertTrue(Measure.weighted_bodyweight.signed());
    assertEquals("-20kg assisted x 5",
        Measure.weighted_bodyweight.describe(-20.0, 5, null, null));
    assertEquals("bodyweight x 5", Measure.weighted_bodyweight.describe(0.0, 5, null, null));
    assertEquals("+10kg x 5", Measure.weighted_bodyweight.describe(10.0, 5, null, null));

    // and effort moves the right way across zero: assisted is easier than bodyweight, which is
    // easier than loaded
    double assisted = Measure.weighted_bodyweight.effort(-20.0, 5, null, null);
    double plain = Measure.weighted_bodyweight.effort(0.0, 5, null, null);
    double loaded = Measure.weighted_bodyweight.effort(10.0, 5, null, null);
    assertTrue(assisted < plain);
    assertTrue(plain < loaded);
  }

  @Test
  public void effortIsAskedForRatherThanAssumed() {
    // tonnage is right for a barbell and nonsense for a 5k. Each measure names what "more" means
    // for it, because a chart that silently rewarded slower running is worse than no chart.
    assertEquals(480.0, Measure.weight_reps.effort(60.0, 8, null, null), 0.001);
    assertEquals(5000.0, Measure.distance_duration.effort(null, null, 1560, 5000.0), 0.001);
    assertNull("nothing to chart for a plain tick",
        Measure.none.effort(null, null, null, null));
    assertNull("and nothing from half an answer", Measure.weight_reps.effort(60.0, null, null, null));
  }

  // ---- definitions and instances -----------------------------------------------------------------

  @Test
  public void aDefinitionOutlivesTheTaskAndTheHistoryFollowsIt() throws Exception {
    long me = idOf("ana@example.com");
    Records.Def def = tasks().saveDef(null, me, null, "Bulgarian split squat",
        Measure.weight_reps, "one leg at a time", "Back foot on a bench. Knee tracks the toe.",
        "https://example.org/form", "legs", "{\"sets\":3,\"reps\":8}", 90, false, me);
    Records.Project project = tasks().saveProject(null, me, "Legs", "", "exercise", "exercises",
        List.of(), 24, me);
    Records.Task task = tasks().addTask(project.id(), def.id(), def.name(), "", "", 0, null, null,
        me);

    tasks().record(task.id(), def.id(), project.id(), me, 0, 40.0, 8, null, null, 3, 2, 4, "");
    tasks().record(task.id(), def.id(), project.id(), me, 1, 40.0, 8, null, null, 3, 2, 4, "");

    // the task goes; what happened does not
    tasks().deleteTask(task.id(), me);
    assertNull(tasks().task(task.id()));
    Records.History history = tasks().historyOf(def, me);
    assertEquals(2, history.sets());
    assertEquals("and it is still attached to what it was", 1, history.occasions());
    assertEquals(640.0, history.bestEffort(), 0.001);
  }

  @Test
  public void aSharedDefinitionIsAdoptedByPointingAtIt() throws Exception {
    // improving the community's form notes has to improve everybody's copy, or a library is just a
    // place people take one bad copy from
    Records.Def shared = tasks().saveDef(null, null, null, "Deadlift", Measure.weight_reps,
        "the hinge", "Bar over midfoot. Chest up.", "", "back", "{}", 180, true,
        idOf("boss@example.com"));
    long me = idOf("ana@example.com");
    Records.Def mine = tasks().adopt(shared.id(), me, me);

    assertEquals(shared.id(), (long) mine.parentId());
    assertEquals("", mine.instructions());
    assertEquals("but reading it follows the parent", "Bar over midfoot. Chest up.",
        tasks().resolved(mine).instructions());

    // the community improves theirs, and hers improves with it
    tasks().saveDef(shared.id(), null, null, "Deadlift", Measure.weight_reps, "the hinge",
        "Bar over midfoot. Chest up. Push the floor away.", "", "back", "{}", 180, true,
        idOf("boss@example.com"));
    assertTrue(tasks().resolved(tasks().def(mine.id())).instructions().contains("floor away"));

    // and her own words win where she wrote some
    tasks().saveDef(mine.id(), me, shared.id(), "Deadlift", Measure.weight_reps, "",
        "Straps above 120kg.", "", "", "{}", 0, false, me);
    assertEquals("Straps above 120kg.",
        tasks().resolved(tasks().def(mine.id())).instructions());
  }

  // ---- doing things ------------------------------------------------------------------------------

  @Test
  public void loggingASetFromTheScreenRecordsWhenItHappened() throws Exception {
    long project = startProject(ana, "Mondays", "");
    long def = writeDef(ana, "Bench press", "weight_reps");
    long task = addTask(ana, project, def);

    ana.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(task),
        "weight", "60", "reps", "8", "difficulty", "3", "time_cost", "2", "impact", "4"));

    List<Records.Entry> entries = tasks().entriesForTask(task, idOf("ana@example.com"), 10);
    assertEquals(1, entries.size());
    Records.Entry entry = entries.get(0);
    assertEquals(60.0, entry.weight(), 0.001);
    assertEquals(Integer.valueOf(8), entry.reps());
    assertEquals(Integer.valueOf(4), entry.impact());
    assertNotNull("the timestamp is the whole reason this table exists", entry.recordedAt());

    // and the screen shows it back, with the boxes ready for the next set
    Browser.Page page = ana.get("/tasks/" + project + "/task/" + task);
    assertTrue(page.body(), page.contains("60kg x 8"));
    assertTrue("the next set is numbered", page.contains("Set 2"));
  }

  @Test
  public void aTickIsADataPointToo() throws Exception {
    // a todo list that recorded nothing would make the feedback useless on exactly the things it is
    // most useful for: "that took an hour and achieved nothing"
    long project = startProject(ana, "Before the party", "");
    ana.submitToAndFollow("/tasks", Map.of("action", "add_task",
        "project", Long.toString(project), "title", "Book the hall"));
    long task = tasks().tasksIn(project).get(0).id();

    ana.submitToAndFollow("/tasks", Map.of("action", "complete", "task", Long.toString(task),
        "difficulty", "2", "time_cost", "5", "impact", "1"));
    assertTrue(tasks().task(task).done());
    List<Records.Entry> entries = tasks().entriesForTask(task, idOf("ana@example.com"), 10);
    assertEquals(1, entries.size());
    assertEquals(Integer.valueOf(5), entries.get(0).timeCost());
    assertEquals(Integer.valueOf(1), entries.get(0).impact());
  }

  @Test
  public void aRepeatingTaskComesBackRatherThanClosing() throws Exception {
    long project = startProject(ana, "Weekly", "");
    ana.submitToAndFollow("/tasks", Map.of("action", "add_task",
        "project", Long.toString(project), "title", "Water the plants", "repeat_days", "7"));
    long task = tasks().tasksIn(project).get(0).id();

    ana.submitToAndFollow("/tasks", Map.of("action", "complete", "task", Long.toString(task)));
    Records.Task after = tasks().task(task);
    assertFalse("a routine is a thing that comes back", after.done());
    assertEquals(LocalDate.now().plusDays(7), after.dueOn());
    assertEquals("and the occasion was still recorded", 1,
        tasks().entriesForTask(task, idOf("ana@example.com"), 10).size());
  }

  @Test
  public void aBoardMovesThroughItsOwnPhasesAndTheLastOneMeansDone() throws Exception {
    long project = startProject(ana, "Party", "Waiting, Doing, Done");
    ana.submitToAndFollow("/tasks", Map.of("action", "add_task",
        "project", Long.toString(project), "title", "Order the cake"));
    long task = tasks().tasksIn(project).get(0).id();
    assertEquals("it lands in the first column", "Waiting", tasks().task(task).phase());

    ana.submitToAndFollow("/tasks", Map.of("action", "move_task", "task", Long.toString(task),
        "phase", "Doing"));
    assertEquals("Doing", tasks().task(task).phase());
    assertFalse(tasks().task(task).done());

    ana.submitToAndFollow("/tasks", Map.of("action", "move_task", "task", Long.toString(task),
        "phase", "Done"));
    assertTrue("reaching the last column is what finished means on a board",
        tasks().task(task).done());

    // a phase the project never declared is refused rather than invented
    ana.submitToAndFollow("/tasks", Map.of("action", "move_task", "task", Long.toString(task),
        "phase", "Elsewhere"));
    assertEquals("Done", tasks().task(task).phase());
  }

  @Test
  public void finishedThingsDropOutOfTheWayAndAreStillThere() throws Exception {
    long project = startProject(ana, "Chores", "");
    ana.submitToAndFollow("/tasks", Map.of("action", "add_task",
        "project", Long.toString(project), "title", "Old job"));
    long task = tasks().tasksIn(project).get(0).id();
    ana.submitToAndFollow("/tasks", Map.of("action", "complete", "task", Long.toString(task)));

    // still on screen straight away, because hiding it the instant it is ticked looks like a bug
    assertTrue(ana.get("/tasks/" + project).contains("Old job"));

    // the rule is a property of the project, and the row never goes
    Records.Project settings = tasks().project(project);
    assertTrue(settings.shouldHide(tasks().task(task).doneAt(),
        System.currentTimeMillis() + 25L * 3_600_000L));
    assertFalse(settings.shouldHide(tasks().task(task).doneAt(), System.currentTimeMillis()));
    assertEquals("and it is still in the project", 1, tasks().tasksIn(project).size());
  }

  // ---- whose is this -----------------------------------------------------------------------------

  @Test
  public void nobodyOpensSomebodyElsesProject() throws Exception {
    long hers = startProject(ana, "Her training", "");
    long def = writeDef(ana, "Squat", "weight_reps");
    long task = addTask(ana, hers, def);
    ana.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(task),
        "weight", "80", "reps", "5"));

    // not the project, not the item, not by guessing an id -- and 404 rather than 403, because
    // whether somebody else's log exists is itself their business
    assertEquals(404, bo.get("/tasks/" + hers).status());
    assertEquals(404, bo.get("/tasks/" + hers + "/task/" + task).status());
    assertFalse(bo.get("/tasks").contains("Her training"));

    // and an administrator is not an exception on this path
    assertEquals(404, admin.get("/tasks/" + hers).status());
    assertFalse("nor can they write to it", admin.get("/tasks").contains("Her training"));

    // writing to it is refused too, not just reading
    bo.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(task),
        "weight", "999", "reps", "1"));
    assertEquals(1, tasks().entriesForTask(task, idOf("ana@example.com"), 10).size());
    assertEquals("and nothing was written under his name either", 0,
        tasks().entriesForTask(task, idOf("bo@example.com"), 10).size());
  }

  @Test
  public void theCommunitysOwnProjectIsForEverybody() throws Exception {
    admin.submitToAndFollow("/tasks", Map.of("action", "new_project", "name", "Summer party",
        "shared", "on"));
    long project = tasks().allProjects().stream()
        .filter(Records.Project::isShared).findFirst().orElseThrow().id();
    assertTrue(ana.get("/tasks").contains("Summer party"));
    assertEquals(200, ana.get("/tasks/" + project).status());

    // and a member cannot start one for the community
    ana.submitToAndFollow("/tasks", Map.of("action", "new_project", "name", "Mine really",
        "shared", "on"));
    assertEquals("it became her own instead of the community's", 1,
        tasks().allProjects().stream().filter(Records.Project::isShared).count());
  }

  @Test
  public void theAdminScreenCountsMembersProjectsAndDoesNotOpenThem() throws Exception {
    long hers = startProject(ana, "Her training", "");
    long def = writeDef(ana, "Squat", "weight_reps");
    long task = addTask(ana, hers, def);
    ana.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(task),
        "weight", "80", "reps", "5"));

    Browser.Page page = admin.get("/admin/tasks");
    assertEquals(200, page.status());
    assertTrue("knowing people use it is an administrative fact", page.contains("Her training"));
    assertTrue(page.contains("member(s) keep something of their own"));
    // what she lifted is not
    assertFalse(page.body(), page.contains("80kg"));
    assertFalse(page.contains("Squat"));
    assertTrue("and it says why, so nobody goes looking for the link",
        page.contains("not openable from here"));
  }

  @Test
  public void aSharedLibraryNeedsThePermissionAndAPrivateOneDoesNot() throws Exception {
    // writing your own definitions is like writing your own todo list; writing into the community's
    // library is a different act
    ana.submitToAndFollow("/tasks", Map.of("action", "new_def", "name", "My own thing",
        "measure", "bodyweight_reps", "share", "on"));
    Records.Def mine = tasks().defsFor(idOf("ana@example.com"), false).stream()
        .filter(def -> def.name().equals("My own thing")).findFirst().orElseThrow();
    assertFalse("it stayed hers", mine.isCommunitys());
    assertFalse(mine.shared());

    admin.submitToAndFollow("/tasks", Map.of("action", "new_def", "name", "Push-up",
        "measure", "bodyweight_reps", "share", "on"));
    assertEquals(1, tasks().sharedDefs().size());
    assertTrue("and everybody sees it", ana.get("/tasks/library").contains("Push-up"));
  }

  // ---- what it is all for ------------------------------------------------------------------------

  @Test
  public void theVerdictIsAboutImpactForTime() throws Exception {
    // the whole point of three numbers rather than one: the thing that is exhausting and useless is
    // exactly the thing worth finding
    long me = idOf("ana@example.com");
    Records.Def good = tasks().saveDef(null, me, null, "Worth it", Measure.bodyweight_reps, "", "",
        "", "", "{}", 0, false, me);
    Records.Def bad = tasks().saveDef(null, me, null, "Not worth it", Measure.bodyweight_reps, "",
        "", "", "", "{}", 0, false, me);
    for (int k = 0; k < 3; k++) {
      tasks().record(null, good.id(), null, me, k, null, 10, null, null, 4, 1, 5, "");
      tasks().record(null, bad.id(), null, me, k, null, 10, null, null, 2, 5, 1, "");
    }
    assertTrue(tasks().historyOf(good, me).verdict().contains("keep this one"));
    assertTrue(tasks().historyOf(bad, me).verdict().contains("worth replacing"));

    // and saying nothing is not the same as saying three
    Records.Def quiet = tasks().saveDef(null, me, null, "Nobody said", Measure.bodyweight_reps,
        "", "", "", "", "{}", 0, false, me);
    tasks().record(null, quiet.id(), null, me, 0, null, 10, null, null, null, null, null, "");
    assertNull(tasks().historyOf(quiet, me).averageImpact());
    assertTrue(tasks().historyOf(quiet, me).verdict().contains("Not enough said"));
  }

  @Test
  public void leavingTakesTheWholeLog() throws Exception {
    long project = startProject(ana, "Hers", "");
    long def = writeDef(ana, "Squat", "weight_reps");
    long task = addTask(ana, project, def);
    ana.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(task),
        "weight", "80", "reps", "5"));
    long me = idOf("ana@example.com");

    // it is in her own export, because it is hers and nobody else can read it
    assertTrue(ana.get("/self?tab=data&download=export").contains("80kg x 5"));

    admin.submitToAndFollow("/admin/people", Map.of("action", "erase",
        "user", Long.toString(me), "confirm", "delete"));
    assertTrue(tasks().recentFor(me, 10).isEmpty());
    assertNull(tasks().project(project));
    assertNull(tasks().def(def));
  }

  // ---- rest, supersets and the estimate -----------------------------------------------------------

  @Test
  public void restBelongsToTheMovementAndFollowsItEverywhere() throws Exception {
    // a heavy squat wants three minutes in every routine it ever appears in, so it is a property of
    // the definition rather than of one occasion
    long me = idOf("ana@example.com");
    Records.Def def = tasks().saveDef(null, me, null, "Squat", Measure.weight_reps, "", "", "",
        "", "{}", 180, false, me);
    assertEquals(180, tasks().def(def.id()).restSeconds());
    assertEquals("3m", tasks().def(def.id()).restSaid());

    // and it is inherited by a copy that has not set its own
    Records.Def shared = tasks().saveDef(null, null, null, "Deadlift", Measure.weight_reps, "",
        "", "", "", "{}", 240, true, idOf("boss@example.com"));
    Records.Def mine = tasks().adopt(shared.id(), me, me);
    assertEquals(240, tasks().resolved(mine).restSeconds());
  }

  @Test
  public void theRestTimerIsRenderedByTheServerAndOnlyTickedByTheScript() throws Exception {
    // a gym is the worst network anybody uses regularly, so the page has to be right before any
    // script has loaded
    long project = startProject(ana, "Mondays", "");
    ana.submitToAndFollow("/tasks", Map.of("action", "new_def", "name", "Squat",
        "measure", "weight_reps", "rest_seconds", "180"));
    long def = tasks().defsFor(idOf("ana@example.com"), false).stream()
        .filter(one -> one.name().equals("Squat")).findFirst().orElseThrow().id();
    long task = addTask(ana, project, def);

    // nothing to rest from before the first set
    assertFalse(ana.get("/tasks/" + project + "/task/" + task).contains("data-rest"));

    ana.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(task),
        "weight", "100", "reps", "5"));
    Browser.Page page = ana.get("/tasks/" + project + "/task/" + task);
    assertTrue(page.body(), page.contains("since your last set"));
    assertTrue("the target is on the page in words", page.contains("rest 3m"));
    assertTrue("and the numbers the script needs are in attributes, not in a script block",
        page.contains("data-rest=\"180\""));
    assertTrue(page.contains("/~rest.js"));
  }

  @Test
  public void aSupersetRestsAfterTheRoundAndACircuitSaysWhatIsNext() throws Exception {
    long project = startProject(ana, "Mondays", "");
    long press = writeDef(ana, "Bench press", "weight_reps");
    long row = writeDef(ana, "Row", "weight_reps");
    long pressTask = addTask(ana, project, press);
    long rowTask = addTask(ana, project, row);

    for (long task : new long[]{pressTask, rowTask}) {
      ana.submitToAndFollow("/tasks", Map.of("action", "group_task", "task", Long.toString(task),
          "group_name", "Push and pull", "group_mode", "related"));
    }
    assertTrue(tasks().task(pressTask).grouped());
    assertEquals(2, tasks().groupWith(tasks().task(pressTask)).size());

    ana.submitToAndFollow("/tasks", Map.of("action", "log_set", "task", Long.toString(pressTask),
        "weight", "60", "reps", "8"));
    Browser.Page page = ana.get("/tasks/" + project + "/task/" + pressTask);
    assertTrue(page.body(), page.contains("superset"));
    assertTrue("the other half is one tap away", page.contains("Row"));
    assertTrue("and the rest is not offered between the parts",
        page.contains("rest after the round, not between"));
    assertFalse(page.contains("rest 1m"));

    // the project page draws them as one thing
    Browser.Page board = ana.get("/tasks/" + project);
    assertTrue(board.body(), board.contains("Push and pull"));
    assertTrue(board.contains("Alternate between them"));

    // a sequenced group says which one comes next instead
    for (long task : new long[]{pressTask, rowTask}) {
      ana.submitToAndFollow("/tasks", Map.of("action", "group_task", "task", Long.toString(task),
          "group_name", "Push and pull", "group_mode", "sequenced"));
    }
    Browser.Page ordered = ana.get("/tasks/" + project + "/task/" + pressTask);
    assertTrue(ordered.body(), ordered.contains("then"));
    assertTrue(ordered.contains("Row"));
    assertTrue("and the rest is its own again", ordered.contains("since your last set"));
  }

  @Test
  public void leavingAGroupLeavesNothingBehind() throws Exception {
    long project = startProject(ana, "Mondays", "");
    long def = writeDef(ana, "Bench press", "weight_reps");
    long task = addTask(ana, project, def);
    ana.submitToAndFollow("/tasks", Map.of("action", "group_task", "task", Long.toString(task),
        "group_name", "Push and pull", "group_mode", "related"));
    assertTrue(tasks().task(task).grouped());

    ana.submitToAndFollow("/tasks", Map.of("action", "group_task", "task", Long.toString(task),
        "group_name", "", "group_mode", ""));
    assertFalse("a group is a shared name, so there is nothing left to clean up",
        tasks().task(task).grouped());
    assertTrue(tasks().groupWith(tasks().task(task)).isEmpty());
  }

  @Test
  public void theEstimateIsTheBestSingleSetAndSaysWhereItCameFrom() throws Exception {
    long me = idOf("ana@example.com");
    Records.Def def = tasks().saveDef(null, me, null, "Squat", Measure.weight_reps, "", "", "", "",
        "{}", 180, false, me);
    tasks().record(null, def.id(), null, me, 0, 100.0, 5, null, null, null, null, null, "");
    tasks().record(null, def.id(), null, me, 1, 120.0, 3, null, null, null, null, null, "");
    // and one that is too many reps to say anything about
    tasks().record(null, def.id(), null, me, 2, 40.0, 30, null, null, null, null, null, "");

    Records.History history = tasks().historyOf(def, me);
    assertEquals("120 x (1 + 3/30) = 132", 132.0, history.estimatedMax(), 0.001);
    assertNotNull(history.bestOneRepMaxAt());

    long project = startProject(ana, "Mondays", "");
    long task = addTask(ana, project, def.id());
    Browser.Page page = ana.get("/tasks/library/" + def.id());
    assertTrue(page.body(), page.contains("132"));
    assertTrue("it says what it is and how far to trust it", page.contains("Epley"));
    assertTrue(page.contains("a direction, not a target"));
    assertTrue(String.valueOf(task), task > 0);
  }

  @Test
  public void nothingWithoutABarGetsAnEstimateOnItsPage() throws Exception {
    long me = idOf("ana@example.com");
    Records.Def plank = tasks().saveDef(null, me, null, "Plank", Measure.duration, "", "", "", "",
        "{}", 60, false, me);
    tasks().record(null, plank.id(), null, me, 0, null, null, 90, null, null, null, null, "");
    assertNull(tasks().historyOf(plank, me).estimatedMax());
    assertFalse(ana.get("/tasks/library/" + plank.id()).contains("Epley"));
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private long startProject(Browser who, String name, String phases) throws Exception {
    who.submitToAndFollow("/tasks", Map.of("action", "new_project", "name", name,
        "phases", phases));
    // by name rather than by position: allProjects() is ordered for a screen, not by age
    return tasks().allProjects().stream().filter(project -> project.name().equals(name))
        .findFirst().orElseThrow().id();
  }

  private long writeDef(Browser who, String name, String measure) throws Exception {
    who.submitToAndFollow("/tasks", Map.of("action", "new_def", "name", name,
        "measure", measure));
    long best = 0;
    for (Records.Def def : tasks().defsFor(idOf("ana@example.com"), true)) {
      if (def.name().equals(name)) {
        best = Math.max(best, def.id());
      }
    }
    return best;
  }

  private long addTask(Browser who, long project, long def) throws Exception {
    who.submitToAndFollow("/tasks", Map.of("action", "add_task",
        "project", Long.toString(project), "def", Long.toString(def)));
    List<Records.Task> all = tasks().tasksIn(project);
    return all.get(all.size() - 1).id();
  }

  private Browser member(String email) throws Exception {
    Browser browser = signIn(email);
    long id = server.auth.forDomain("example.org").users.byEmail(email).id();
    admin.submitToAndFollow("/admin/people",
        Map.of("action", "approve", "user", Long.toString(id)));
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
