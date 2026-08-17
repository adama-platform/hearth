package io.hearth.tasks;

import org.junit.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every measure, every field, and every way of asking with half an answer.
 *
 * The reason this is exhaustive rather than representative: seven measures share four columns, and
 * the failure mode is not a crash but a sentence that reads plausibly and describes the wrong thing.
 * "60kg x 8" where "8 x 60kg" was meant is invisible in a screenshot and wrong in a chart, and the
 * only way to be sure is to walk all of them.
 */
public class MeasureTests {

  @Test
  public void everyMeasureDescribesAFullAnswerWithoutAQuestionMark() {
    for (Measure measure : Measure.values()) {
      String said = measure.describe(60.0, 8, 90, 5000.0);
      assertNotNull(measure.name(), said);
      assertFalse(measure + " left a hole in a complete answer: " + said, said.contains("?"));
      assertFalse(measure + " said nothing", said.isBlank());
    }
  }

  @Test
  public void everyMeasureSaysWhatIsMissingRatherThanInventingIt() {
    // half an answer is a real state -- somebody typed the weight and not the reps -- and the
    // honest reading of it is a gap, not a zero
    for (Measure measure : Measure.values()) {
      if (measure == Measure.none) {
        continue;
      }
      String said = measure.describe(null, null, null, null);
      assertTrue(measure + " invented a number: " + said, said.contains("?"));
      assertNull(measure + " scored something out of nothing",
          measure.effort(null, null, null, null));
    }
  }

  @Test
  public void aMeasureAsksForExactlyTheFieldsItDescribes() {
    for (Measure measure : Measure.values()) {
      List<Measure.Field> fields = measure.fields();
      assertEquals(measure + " draws a different number of boxes than it asks for",
          fields.size(), measure.boxes().size());
      for (Measure.Field field : Measure.Field.values()) {
        assertEquals(measure + " and " + field + " disagree",
            fields.contains(field), measure.asks(field));
      }
      // and everything it asks for turns up in what it says
      for (Map<String, Object> box : measure.boxes()) {
        assertTrue(measure + " has an unnamed box", box.get("name") != null);
        assertNotNull(box.get("label"));
      }
    }
    assertEquals("only bodyweight-with-weight is signed", 1,
        java.util.Arrays.stream(Measure.values()).filter(Measure::signed).count());
    assertTrue(Measure.none.fields().isEmpty());
    assertTrue(Measure.none.boxes().isEmpty());
  }

  @Test
  public void timesReadTheWayPeopleSayThem() {
    assertEquals("45s", Measure.duration.describe(null, null, 45, null));
    assertEquals("1m", Measure.duration.describe(null, null, 60, null));
    assertEquals("1m 30s", Measure.duration.describe(null, null, 90, null));
    assertEquals("1h 5m", Measure.duration.describe(null, null, 3900, null));
  }

  @Test
  public void distancesSwitchToKilometresWhenTheyShould() {
    assertEquals("900m in 45s", Measure.distance_duration.describe(null, null, 45, 900.0));
    assertEquals("5km in 26m", Measure.distance_duration.describe(null, null, 1560, 5000.0));
    // and a weight that is a whole number does not grow a decimal point on the way to a screen
    assertEquals("40kg x 8", Measure.weight_reps.describe(40.0, 8, null, null));
    assertEquals("42.5kg x 8", Measure.weight_reps.describe(42.5, 8, null, null));
  }

  @Test
  public void anUnknownMeasureIsNullAndAnAbsentOneIsNone() {
    // the difference matters at the seam: a config or a model sending nothing means "just tick it
    // off", and one sending nonsense should be told so rather than quietly given a todo
    assertNull(Measure.of("kilometres_per_hour"));
    assertEquals(Measure.none, Measure.of(null));
    assertEquals(Measure.none, Measure.of("  "));
    assertEquals(Measure.weight_reps, Measure.of(" WEIGHT_REPS "));
    for (Map<String, Object> row : Measure.all()) {
      assertNotNull(Measure.of(String.valueOf(row.get("name"))));
    }
    assertEquals(Measure.values().length, Measure.all().size());
  }

  @Test
  public void everyMeasureNamesWhatAChartOfItWouldBeAChartOf() {
    for (Measure measure : Measure.values()) {
      assertNotNull(measure.effortLabel());
      assertEquals(measure == Measure.none, measure.effortLabel().isEmpty());
      assertTrue(measure.moreIsBetter());
    }
  }

  // ---- one-rep max -----------------------------------------------------------------------------

  @Test
  public void aOneRepMaxIsOnlyOfferedWhereItMeansSomething() {
    // a plank has no one-rep max and neither does a 5k; offering a number for them would be a
    // number somebody then tries to beat
    for (Measure measure : Measure.values()) {
      boolean loaded = measure == Measure.weight_reps || measure == Measure.weighted_bodyweight;
      assertEquals(measure + " disagrees with itself about whether it has one",
          loaded, measure.hasOneRepMax());
      if (!loaded) {
        assertNull(measure + " estimated a maximum", measure.oneRepMax(100.0, 5));
      }
    }
    assertEquals(100.0, Measure.weight_reps.oneRepMax(100.0, 1), 0.001);
    assertEquals("Epley: 100 x (1 + 5/30)", 116.667,
        Measure.weight_reps.oneRepMax(100.0, 5), 0.01);
    assertTrue("more reps at the same weight is a bigger estimate",
        Measure.weight_reps.oneRepMax(100.0, 8) > Measure.weight_reps.oneRepMax(100.0, 5));
  }

  @Test
  public void pastAPointItStopsBeingAboutStrength() {
    // every formula drifts badly as the reps go up; at twenty the answer is about how long somebody
    // can suffer, and a confident figure there is worse than no figure
    assertNotNull(Measure.weight_reps.oneRepMax(60.0, Measure.HONEST_REPS));
    assertNull("a set of thirty does not have a one-rep max",
        Measure.weight_reps.oneRepMax(60.0, Measure.HONEST_REPS + 1));
    assertNull(Measure.weight_reps.oneRepMax(60.0, 30));
    assertNull("nor does half an answer", Measure.weight_reps.oneRepMax(60.0, null));
    assertNull(Measure.weight_reps.oneRepMax(null, 5));
    assertNull(Measure.weight_reps.oneRepMax(60.0, 0));
  }

  @Test
  public void anAssistedRepHasNoMaximumToEstimateFrom() {
    // it is easier than one unassisted, so there is nothing to extrapolate towards -- and a number
    // here would be an estimate of a lift somebody cannot yet do at all
    assertNull(Measure.weighted_bodyweight.oneRepMax(-20.0, 5));
    assertNull(Measure.weighted_bodyweight.oneRepMax(0.0, 5));
    assertEquals(23.333, Measure.weighted_bodyweight.oneRepMax(20.0, 5), 0.01);
    assertTrue("and it says it is about the added load rather than the whole lift",
        Measure.weighted_bodyweight.oneRepMaxLabel().contains("added weight"));
  }

  // ---- rest, and grouping ------------------------------------------------------------------------

  @Test
  public void restReadsTheWayPeopleSayIt() {
    assertEquals("", def(0).restSaid());
    assertFalse(def(0).hasRest());
    assertEquals("45s", def(45).restSaid());
    assertEquals("2m", def(120).restSaid());
    assertEquals("1m 30s", def(90).restSaid());
    assertTrue(def(90).hasRest());
  }

  @Test
  public void aGroupKnowsWhatItIsAndWhereTheRestGoes() {
    // the difference between the two modes is what happens between them, and getting it backwards
    // turns a time-saving device into one that takes longer
    assertEquals("superset", Records.Grouping.related.label());
    assertTrue(Records.Grouping.related.hint().contains("after the round"));
    assertEquals("in order", Records.Grouping.sequenced.label());
    assertTrue(Records.Grouping.sequenced.hint().contains("in this order"));

    assertEquals(Records.Grouping.related, Records.Grouping.of(" RELATED "));
    assertNull("an empty name is not in a group", Records.Grouping.of(""));
    assertNull("and neither is a third word", Records.Grouping.of("circuit"));
  }

  @Test
  public void beingInAGroupNeedsBothANameAndAMode() {
    assertTrue(task("legs", Records.Grouping.related).grouped());
    assertFalse("a mode with no name is not a group", task("", Records.Grouping.related).grouped());
    assertFalse("nor a name with no mode", task("legs", null).grouped());
  }

  private static Records.Def def(int rest) {
    return new Records.Def(1, 7L, null, "x", "x", Measure.weight_reps, "", "", "", "", "{}", rest,
        false, null, null, null, null);
  }

  private static Records.Task task(String group, Records.Grouping mode) {
    return new Records.Task(1, 1, null, "x", "", "", group, mode, 0, null, 0, null, null, null,
        null, null);
  }

  // ---- the records, and the judgements that live on them ---------------------------------------

  @Test
  public void aProjectKnowsItsOwnWordsAndItsOwnShape() {
    Records.Project list = new Records.Project(1, 7L, "Chores", "chores", "", "job", "jobs",
        List.of(), 24, false, null, null, null);
    assertFalse(list.isShared());
    assertFalse(list.isBoard());
    assertEquals("job", list.one());
    assertEquals("jobs", list.many());
    assertEquals("", list.firstPhase());
    assertEquals("", list.lastPhase());

    Records.Project board = new Records.Project(2, null, "Party", "party", "", "", "",
        List.of("Waiting", "Doing", "Done"), 0, false, null, null, null);
    assertTrue(board.isShared());
    assertTrue(board.isBoard());
    assertEquals("Waiting", board.firstPhase());
    assertEquals("Done", board.lastPhase());
    assertTrue(board.hasPhase("Doing"));
    assertFalse(board.hasPhase("Elsewhere"));
    assertFalse("a project with no phases still answers", board.hasPhase(null));
    assertEquals("and falls back to the ordinary words", "task", board.one());
    assertEquals("tasks", board.many());
  }

  @Test
  public void hidingIsAboutHoursAndNeverAboutDeleting() {
    Records.Project quick = new Records.Project(1, 7L, "x", "x", "", "", "", List.of(), 1, false,
        null, null, null);
    Timestamp done = new Timestamp(1_000_000);
    assertFalse(quick.shouldHide(done, 1_000_000));
    assertTrue(quick.shouldHide(done, 1_000_000 + 3_700_000));
    assertFalse("nothing done is never hidden", quick.shouldHide(null, Long.MAX_VALUE));

    Records.Project never = new Records.Project(1, 7L, "x", "x", "", "", "", List.of(), 0, false,
        null, null, null);
    assertFalse("zero means it stays on the screen", never.shouldHide(done, Long.MAX_VALUE));
  }

  @Test
  public void aRepeatingTaskKnowsWhenItComesBack() {
    java.time.LocalDate today = java.time.LocalDate.of(2026, 3, 10);
    Records.Task weekly = new Records.Task(1, 1, null, "Plants", "", "", "", null, 0, null, 7,
        today.minusDays(1), null, null, null, null);
    assertTrue(weekly.repeats());
    assertTrue(weekly.overdue(today));
    assertEquals(today.plusDays(7), weekly.nextDue(today));

    Records.Task once = new Records.Task(2, 1, null, "Hall", "", "", "", null, 0,
        new Timestamp(1), 0, today.minusDays(1), null, null, null, null);
    assertFalse(once.repeats());
    assertNull(once.nextDue(today));
    assertFalse("something already done is not overdue", once.overdue(today));
    assertTrue(once.done());
  }

  @Test
  public void aDefinitionReadsItsOwnTargetAndSurvivesRubbishInIt() {
    Records.Def good = def("{\"sets\":5,\"reps\":8,\"weight\":42.5}");
    assertEquals(5, good.suggestedSets());
    assertEquals(8, good.targetInt("reps", 0));
    assertEquals(42.5, good.targetDouble("weight"), 0.001);
    assertNull(good.targetDouble("nothing"));

    Records.Def broken = def("not json at all");
    assertEquals("a bad blob falls back rather than failing a page", 3, broken.suggestedSets());
    assertEquals(0, broken.targetInt("reps", 0));
    assertNull(broken.targetDouble("weight"));

    Records.Def empty = def("");
    assertEquals(3, empty.suggestedSets());
    // and a silly number of sets is clamped rather than drawn
    assertEquals(20, def("{\"sets\":900}").suggestedSets());
    assertEquals(1, def("{\"sets\":0}").suggestedSets());
  }

  @Test
  public void tagsAreSplitOnCommasAndNewlinesAndNothingElse() {
    Records.Def def = new Records.Def(1, 7L, null, "x", "x", Measure.none, "", "", "",
        " legs, pull \n push ,, ", "{}", 0, false, null, null, null, null);
    assertEquals(List.of("legs", "pull", "push"), def.tagList());
    assertTrue(new Records.Def(1, 7L, null, "x", "x", Measure.none, "", "", "", "", "{}", 0, false,
        null, null, null, null).tagList().isEmpty());
  }

  @Test
  public void worthIsImpactOverTimeAndNeedsBothHalves() {
    assertEquals(2.5, entry(5, 2, 4).worth(), 0.001);
    assertNull("a score built from one answer flatters whatever was left blank",
        entry(null, 2, 4).worth());
    assertNull(entry(5, null, 4).worth());
    assertTrue(entry(null, null, 3).rated());
    assertFalse(entry(null, null, null).rated());
  }

  @Test
  public void theVerdictSaysSomethingRatherThanScoringIt() {
    // three small integers over a handful of occasions is enough to notice a pattern and nowhere
    // near enough to rank a routine, so it is a sentence rather than a number on a dial
    assertTrue(history(4.5, 1.5, null).verdict().contains("keep this one"));
    assertTrue(history(4.5, 4.0, null).verdict().contains("Worth the time"));
    assertTrue(history(1.5, 4.5, null).verdict().contains("worth replacing"));
    assertTrue(history(2.5, 2.0, 1.5).verdict().contains("Harder, or drop it"));
    assertTrue(history(3.0, 3.0, 3.0).verdict().contains("Middling"));
    assertTrue("and silence says so", history(null, null, null).verdict().contains("Not enough"));
    assertNull(history(null, null, null).worth());
    assertEquals(2.0, history(4.0, 2.0, null).worth(), 0.001);
  }

  private static Records.Def def(String target) {
    return new Records.Def(1, 7L, null, "x", "x", Measure.weight_reps, "", "", "", "", target,
        0, false, null, null, null, null);
  }

  private static Records.Entry entry(Integer impact, Integer time, Integer difficulty) {
    return new Records.Entry(1, null, null, null, 7, 0, null, null, null, null, difficulty, time,
        impact, "", new Timestamp(1));
  }

  private static Records.History history(Double impact, Double time, Double difficulty) {
    return new Records.History(def("{}"), 3, 9, new Timestamp(1), 100.0, 90.0, null, null,
        difficulty, time, impact, List.of());
  }
}
