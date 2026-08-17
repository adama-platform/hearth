package io.hearth.board;

import io.hearth.calendar.Calendar;
import org.junit.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;

/**
 * Before, on the day, and afterwards.
 *
 * Computed rather than stored, which is the decision worth a test: an event that moves changes what
 * "before" meant, and a phase written onto a row when the comment was made would then be wrong on
 * every row with nothing to notice it.
 */
public class CommentPhaseTests {
  private static Calendar.Event on(LocalDate starts, LocalDate ends) {
    return new Calendar.Event(1, "Supper", "", "The hall", null, Calendar.State.accepted, null,
        null, "", starts, ends, "7pm", null, true, false, 0, 0, 0, null, null, null, "", null,
        "uid@example.org", 0, null);
  }

  private static Timestamp at(LocalDate day) {
    return new Timestamp(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
  }

  @Test
  public void aCommentIsPlacedAgainstTheDayItselfRatherThanAnInstant() {
    LocalDate day = LocalDate.of(2026, 5, 14);
    Calendar.Event event = on(day, day);
    assertEquals(CommentPhase.before, CommentPhase.of(event, at(day.minusDays(1))));
    assertEquals("the morning of, about tonight, is a during-comment by any reading that matters",
        CommentPhase.during, CommentPhase.of(event, at(day)));
    assertEquals(CommentPhase.after, CommentPhase.of(event, at(day.plusDays(1))));
  }

  @Test
  public void everyDayOfASpanIsDuringIt() {
    Calendar.Event event = on(LocalDate.of(2026, 5, 14), LocalDate.of(2026, 5, 16));
    assertEquals(CommentPhase.during, CommentPhase.of(event, at(LocalDate.of(2026, 5, 14))));
    assertEquals(CommentPhase.during, CommentPhase.of(event, at(LocalDate.of(2026, 5, 15))));
    assertEquals(CommentPhase.during, CommentPhase.of(event, at(LocalDate.of(2026, 5, 16))));
    assertEquals(CommentPhase.after, CommentPhase.of(event, at(LocalDate.of(2026, 5, 17))));
  }

  @Test
  public void movingTheEventMovesWhatBeforeMeant() {
    // the whole reason this is computed. The same comment, the same timestamp, a rescheduled
    // event -- and the answer changes, which is correct and is what a stored column could not do.
    LocalDate said = LocalDate.of(2026, 5, 20);
    assertEquals(CommentPhase.after,
        CommentPhase.of(on(LocalDate.of(2026, 5, 14), LocalDate.of(2026, 5, 14)), at(said)));
    assertEquals(CommentPhase.before,
        CommentPhase.of(on(LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 14)), at(said)));
  }

  @Test
  public void nothingToCompareAgainstIsBefore() {
    assertEquals(CommentPhase.before, CommentPhase.of(null, at(LocalDate.now())));
    assertEquals(CommentPhase.before,
        CommentPhase.of(on(LocalDate.now(), LocalDate.now()), null));
  }
}
