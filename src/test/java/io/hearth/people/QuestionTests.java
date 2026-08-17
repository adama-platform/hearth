package io.hearth.people;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestionTests {
  private static Question parse(String definition) {
    return Question.parse(1, definition, 0, true, null);
  }

  @Test
  public void aFreeTextQuestionRoundTrips() {
    String blob = Question.definition(Question.Kind.free, "What brings you here?", "a sentence is fine",
        List.of(), 0, 0, true);
    Question question = parse(blob);
    assertEquals(Question.Kind.free, question.kind());
    assertEquals("What brings you here?", question.prompt());
    assertEquals("a sentence is fine", question.help());
    assertTrue(question.required());
  }

  @Test
  public void aChoiceQuestionKeepsItsOptions() {
    String blob = Question.definition(Question.Kind.choice, "Which house?", "",
        List.of("Gryffindor", "Slytherin"), 0, 0, false);
    Question question = parse(blob);
    assertEquals(Question.Kind.choice, question.kind());
    assertEquals(List.of("Gryffindor", "Slytherin"), question.options());
    assertTrue(question.accepts("Slytherin"));
    assertFalse("an option nobody offered is not an answer", question.accepts("Hufflepuff"));
  }

  @Test
  public void aRatingKeepsItsBounds() {
    Question question = parse(Question.definition(Question.Kind.rating, "How keen?", "", List.of(), 1, 7, false));
    assertEquals(1, question.min());
    assertEquals(7, question.max());
    assertEquals(7, question.scale().size());
    assertTrue(question.accepts("7"));
    assertFalse(question.accepts("8"));
    assertFalse(question.accepts("0"));
    assertFalse(question.accepts("three"));
  }

  @Test
  public void aBackwardsRatingIsRepaired() {
    Question question = parse("{\"kind\":\"rating\",\"min\":5,\"max\":2}");
    assertTrue("a scale that runs backwards would render as nothing", question.max() > question.min());
  }

  @Test
  public void anUnreadableBlobBecomesAFreeTextQuestion() {
    Question question = parse("{not json at all");
    assertEquals("a question nobody can parse is still one somebody wrote", Question.Kind.free, question.kind());
    assertTrue(question.prompt().contains("unreadable"));
  }

  @Test
  public void anUnknownKindFallsBackToFreeText() {
    assertEquals(Question.Kind.free, parse("{\"kind\":\"telepathy\"}").kind());
    assertEquals(Question.Kind.free, Question.Kind.of(null));
  }

  @Test
  public void blankAnswersAreNeverAccepted() {
    Question free = parse(Question.definition(Question.Kind.free, "Why?", "", List.of(), 0, 0, false));
    assertFalse(free.accepts(null));
    assertFalse(free.accepts(""));
    assertFalse(free.accepts("   "));
    assertTrue(free.accepts("because"));
  }

  @Test
  public void optionsAreParsedOnePerLine() {
    List<String> options = Question.optionsFrom("one\n  two  \n\nthree\n");
    assertEquals(List.of("one", "two", "three"), options);
    assertTrue(Question.optionsFrom(null).isEmpty());
  }

  @Test
  public void optionsAreBounded() {
    StringBuilder many = new StringBuilder();
    for (int k = 0; k < 100; k++) {
      many.append("option").append(k).append('\n');
    }
    assertEquals(Question.MAX_OPTIONS, Question.optionsFrom(many.toString()).size());
  }

  @Test
  public void aVeryLongFreeTextAnswerIsRefused() {
    Question free = parse(Question.definition(Question.Kind.free, "Why?", "", List.of(), 0, 0, false));
    assertFalse(free.accepts("x".repeat(4001)));
    assertTrue(free.accepts("x".repeat(4000)));
  }
}
