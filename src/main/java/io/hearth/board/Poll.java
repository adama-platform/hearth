package io.hearth.board;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question a conversation is trying to settle, and the arithmetic that settles it.
 *
 * <b>Why this lives on the board rather than beside the survey.</b> The survey asks the community
 * things and keeps the answers; a poll here is a group deciding something, inside the argument
 * where the reasons are. Take the discussion away and what is left is a number nobody can explain
 * six months later -- and the thing this software is for is the evening people actually spend
 * together, which is settled by argument and then by a count, in that order.
 *
 * <h2>Two shapes, and the second is two questions at once</h2>
 *
 * A <b>choice</b> poll is a straight either-or: one vote each, most votes wins, and voting again
 * moves your vote rather than adding one.
 *
 * A <b>schedule</b> poll asks which day and which place together, because in practice they are one
 * decision -- a hall that is free on Thursday and a friend's kitchen that is free on Saturday
 * cannot be chosen separately. Its two halves count differently on purpose:
 *
 * <ul>
 *   <li><b>Days are approval-voted</b>: up, down, or nothing, on each day independently. A week has
 *       several evenings and somebody can be free on three of them, so forcing one pick throws away
 *       most of what they know. What comes out is a histogram, which is the shape that shows
 *       whether one evening is genuinely better or whether the group is split.</li>
 *   <li><b>Places are either-or</b>, like a plain choice. Somebody can be free on three evenings;
 *       nobody thinks the event should happen in three places.</li>
 * </ul>
 *
 * <b>Nothing is stored for "no opinion".</b> The absence of a row is the absence of an opinion. A
 * stored zero would make "has not looked at this yet" and "looked and does not mind" the same fact,
 * and the second one is worth knowing.
 *
 * <b>A tie is reported, never broken.</b> Picking the lower id, or the earlier day, would be this
 * software deciding something a community has not -- and the whole point is the opposite. A
 * schedule poll that ties makes no event and says which half tied, which is a thing somebody can
 * act on in ten seconds by adding a day or asking two people to vote.
 */
public final class Poll {

  /** what a poll is asking */
  public enum Kind {
    /** a straight either-or between a few options */
    choice,
    /** which day, and which place, together -- and it becomes an event */
    schedule;

    public static Kind of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }

  /** which half of a poll an option belongs to */
  public enum Facet {
    choice, time, place;

    public static Facet of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }

    /** may somebody hold an opinion about several of these at once? */
    public boolean isApproval() {
      return this == time;
    }
  }

  public enum State {
    /** taking votes */
    open,
    /** counted, and that is the end of it */
    closed,
    /** counted, and it became an event */
    converted,
    /** called off */
    cancelled;

    public static State of(String raw) {
      if (raw == null) {
        return open;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return open;
      }
    }
  }

  public record Record(long id, long postId, Kind kind, String question, State state,
                       Timestamp closesAt, boolean openOptions, Long createdBy, Timestamp createdAt,
                       Timestamp closedAt, Long eventId, String outcome) {
    public boolean isOpen() {
      return state == State.open;
    }

    /** has its moment passed, whether or not anything has noticed yet? */
    public boolean isDue(long now) {
      return state == State.open && closesAt != null && closesAt.getTime() <= now;
    }

    public boolean becomesAnEvent() {
      return kind == Kind.schedule;
    }
  }

  public record Option(long id, long pollId, Facet facet, String label, LocalDate onDay,
                       String atTime, Long placeId, int position, Long addedBy,
                       Timestamp createdAt, Timestamp removedAt) {
    public boolean removed() {
      return removedAt != null;
    }

    /** what to call it on a screen: the label, or the day it stands for */
    public String describe() {
      if (label != null && !label.isBlank()) {
        return label;
      }
      if (onDay != null) {
        return onDay + (atTime == null || atTime.isBlank() ? "" : ", " + atTime);
      }
      return "option " + id;
    }
  }

  /** one option with its numbers */
  public record Tally(Option option, int up, int down, int voters) {
    /**
     * What decides a winner.
     *
     * For a day, ups minus downs -- a down is a real statement ("I cannot come then") and counting
     * only ups would rank an evening half the group has ruled out above one nobody objects to. For
     * a choice or a place there are no downs, so this is the vote count.
     */
    public int score() {
      return up - down;
    }

    /** for the bar on the screen: the share of the strongest option, never of the total */
    public int share(int strongest) {
      if (strongest <= 0) {
        return 0;
      }
      return Math.max(0, score()) * 100 / strongest;
    }
  }

  /** how one half of a poll came out */
  public record Result(Facet facet, List<Tally> tallies, Tally winner, boolean tied, int voters) {
    public boolean decided() {
      return winner != null && !tied;
    }

    /** why there is no winner, in a sentence somebody can act on */
    public String problem() {
      if (decided()) {
        return null;
      }
      if (tallies.isEmpty()) {
        return "nothing was put forward to vote on";
      }
      if (winner == null) {
        return "nobody voted";
      }
      return "two or more were level";
    }
  }

  private Poll() {
  }

  /**
   * Count one half.
   *
   * Removed options are left out of the counting entirely but their votes are not deleted, so
   * taking an option away cannot silently change what the remaining ones are a share of.
   */
  public static Result count(Facet facet, List<Option> options, List<Vote> votes) {
    LinkedHashMap<Long, int[]> byOption = new LinkedHashMap<>();
    java.util.HashSet<Long> people = new java.util.HashSet<>();
    for (Option option : options) {
      if (!option.removed() && option.facet() == facet) {
        byOption.put(option.id(), new int[]{0, 0, 0});
      }
    }
    for (Vote vote : votes) {
      int[] counts = byOption.get(vote.optionId());
      if (counts == null) {
        continue;
      }
      if (vote.weight() >= 0) {
        counts[0]++;
      } else {
        counts[1]++;
      }
      counts[2]++;
      people.add(vote.userId());
    }

    ArrayList<Tally> tallies = new ArrayList<>();
    for (Option option : options) {
      int[] counts = byOption.get(option.id());
      if (counts == null) {
        continue;
      }
      tallies.add(new Tally(option, counts[0], counts[1], counts[2]));
    }

    Tally best = null;
    boolean tied = false;
    for (Tally tally : tallies) {
      if (tally.voters() == 0) {
        continue;
      }
      if (best == null || tally.score() > best.score()) {
        best = tally;
        tied = false;
      } else if (tally.score() == best.score()) {
        tied = true;
      }
    }
    // A winner on a negative score is not a winner. It means every option offered was voted down
    // more than up, which is the group saying none of these -- and turning that into an event
    // would be the software insisting on an evening nobody wants.
    if (best != null && best.score() <= 0) {
      best = null;
      tied = false;
    }
    return new Result(facet, tallies, best, tied, people.size());
  }

  /** one person's opinion of one option */
  public record Vote(long id, long pollId, long optionId, Facet facet, long userId, int weight) {
  }

  /** the shape a screen or a model reads */
  public static Map<String, Object> describe(Result result) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    int strongest = 0;
    for (Tally tally : result.tallies()) {
      strongest = Math.max(strongest, tally.score());
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Tally tally : result.tallies()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", tally.option().id());
      row.put("what", tally.option().describe());
      row.put("day", tally.option().onDay() == null ? null : tally.option().onDay().toString());
      row.put("at", tally.option().atTime());
      row.put("place_id", tally.option().placeId());
      row.put("up", tally.up());
      row.put("down", tally.down());
      row.put("score", tally.score());
      row.put("share", tally.share(strongest));
      row.put("winning", result.decided() && result.winner().option().id() == tally.option().id());
      rows.add(row);
    }
    out.put("options", rows);
    out.put("voters", result.voters());
    out.put("decided", result.decided());
    out.put("tied", result.tied());
    if (result.decided()) {
      out.put("winner", result.winner().option().describe());
      out.put("winner_id", result.winner().option().id());
    } else {
      out.put("why_not", result.problem());
    }
    return out;
  }
}
