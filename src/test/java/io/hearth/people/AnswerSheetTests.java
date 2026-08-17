package io.hearth.people;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AnswerSheetTests {
  private static Question question(long id, boolean published) {
    return Question.parse(id, Question.definition(Question.Kind.free, "Q" + id, "", List.of(), 0, 0, false),
        0, published, null);
  }

  @Test
  public void answersRoundTripThroughTheBlob() {
    AnswerSheet sheet = AnswerSheet.empty(1).with(7, "because").with(9, "why not");
    AnswerSheet reread = AnswerSheet.parse(1, sheet.toBlob(), 0, 0);
    assertEquals("because", reread.answerTo(7));
    assertEquals("why not", reread.answerTo(9));
    assertTrue(reread.hasAnswered(7));
    assertFalse(reread.hasAnswered(8));
  }

  @Test
  public void anUnreadableBlobReadsAsNothingAnswered() {
    AnswerSheet sheet = AnswerSheet.parse(1, "{not json", 0, 0);
    assertNull("one bad row must not break somebody's whole page", sheet.answerTo(1));
    assertTrue(sheet.answers().isEmpty());
  }

  @Test
  public void clearingAnAnswerRemovesIt() {
    AnswerSheet sheet = AnswerSheet.empty(1).with(7, "because").with(7, null);
    assertNull(sheet.answerTo(7));
    assertFalse(sheet.toBlob().contains("because"));
  }

  @Test
  public void countingIsRelativeToTheCurrentQuestions() {
    AnswerSheet sheet = AnswerSheet.empty(1).with(1, "a").with(2, "b");
    AnswerSheet counted = sheet.countedAgainst(List.of(question(1, true), question(2, true), question(3, true)));
    assertEquals(2, counted.answered());
    assertEquals(1, counted.remaining());
    assertTrue(counted.anythingLeft());
  }

  @Test
  public void unpublishedQuestionsDoNotCount() {
    AnswerSheet sheet = AnswerSheet.empty(1);
    AnswerSheet counted = sheet.countedAgainst(List.of(question(1, true), question(2, false)));
    assertEquals("a draft is not something somebody is behind on", 1, counted.remaining());
  }

  @Test
  public void anAnswerToADeletedQuestionStaysButStopsCounting() {
    AnswerSheet sheet = AnswerSheet.empty(1).with(1, "a").with(99, "answered before it was removed");
    AnswerSheet counted = sheet.countedAgainst(List.of(question(1, true)));
    assertEquals(1, counted.answered());
    assertEquals(0, counted.remaining());
    assertEquals("deleting a question should not rewrite history",
        "answered before it was removed", counted.answerTo(99));
  }

  @Test
  public void aVeryLongAnswerIsTruncatedRatherThanRejected() {
    AnswerSheet sheet = AnswerSheet.empty(1).with(1, "x".repeat(9000));
    assertEquals(4000, sheet.answerTo(1).length());
  }

  @Test
  public void anEmptySheetIsAllRemaining() {
    AnswerSheet counted = AnswerSheet.empty(1)
        .countedAgainst(List.of(question(1, true), question(2, true)));
    assertEquals(0, counted.answered());
    assertEquals(2, counted.remaining());
  }
}
