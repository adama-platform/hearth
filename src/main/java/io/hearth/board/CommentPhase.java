package io.hearth.board;

import io.hearth.calendar.Calendar;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * When somebody said it, relative to the thing they were talking about.
 *
 * <b>Three conversations happen under an event and reading them as one loses the useful part.</b>
 *
 * <ul>
 *   <li><b>Before</b> is questions and logistics: what somebody needed answered in order to come,
 *       who is giving whom a lift, whether there is parking. Left unanswered, these are the reason
 *       somebody does not turn up.</li>
 *   <li><b>During</b> is what is happening: running late, we are in the back room, bring a jumper.
 *       It stops mattering the next morning.</li>
 *   <li><b>After</b> is what people made of it, which is the only material anybody has for deciding
 *       whether to do it again -- and the part that is worth reading a year later.</li>
 * </ul>
 *
 * Computed from the timestamp rather than stored, because it is a fact about two dates rather than
 * a property of the comment: an event that gets moved changes what "before" meant, and a stored
 * phase would then be wrong on every row with nothing to notice it.
 *
 * Days, like everything else on this calendar. "During" is the day of the event, or every day of a
 * span. That is coarse and it is right: a community event has a day, not a start instant, and the
 * comment posted at eleven in the morning about tonight is a during-comment by any reading that
 * matters.
 */
public enum CommentPhase {
  before,
  during,
  after;

  public static CommentPhase of(Calendar.Event event, Timestamp when) {
    if (event == null || when == null) {
      return before;
    }
    LocalDate said = when.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    if (said.isBefore(event.startsOn())) {
      return before;
    }
    if (said.isAfter(event.endsOn())) {
      return after;
    }
    return during;
  }

  /** what a page calls it */
  public String label() {
    return switch (this) {
      case before -> "before";
      case during -> "on the day";
      case after -> "afterwards";
    };
  }
}
