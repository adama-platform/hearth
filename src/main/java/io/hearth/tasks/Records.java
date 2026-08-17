package io.hearth.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The four things this package is made of, and the rules that live on them rather than in a query.
 *
 * They are together in one file because they are one idea read four ways: a place to put things, a
 * description of a thing, an occasion of it, and what happened. Splitting them across four files
 * would put the {@link Entry#effort()} calculation two directories from the {@link Def#measure()}
 * that decides what it means.
 */
public final class Records {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Records() {
  }

  /**
   * Somewhere tasks live, and the words that project uses for them.
   *
   * @param ownerId null for the community's own; otherwise whose it is, and nobody else's business.
   * @param phases empty for a plain list, or the columns of a board.
   */
  public record Project(long id, Long ownerId, String name, String slug, String summary,
                        String taskWord, String tasksWord, List<String> phases,
                        int hideDoneHours, boolean archived, Long createdBy, Timestamp createdAt,
                        Timestamp updatedAt) {

    public boolean isShared() {
      return ownerId == null;
    }

    public boolean isBoard() {
      return !phases.isEmpty();
    }

    /** the phase something new lands in */
    public String firstPhase() {
      return phases.isEmpty() ? "" : phases.get(0);
    }

    /** the phase that means finished, which is the last one a board declares */
    public String lastPhase() {
      return phases.isEmpty() ? "" : phases.get(phases.size() - 1);
    }

    public boolean hasPhase(String phase) {
      return phase != null && phases.contains(phase);
    }

    /** one of these, named the way this project names it */
    public String one() {
      return taskWord == null || taskWord.isBlank() ? "task" : taskWord;
    }

    public String many() {
      return tasksWord == null || tasksWord.isBlank() ? "tasks" : tasksWord;
    }

    /** has this been done long enough ago to drop out of the way? */
    public boolean shouldHide(Timestamp doneAt, long now) {
      if (doneAt == null || hideDoneHours <= 0) {
        return false;
      }
      return now - doneAt.getTime() > hideDoneHours * 3_600_000L;
    }
  }

  /**
   * What a task is, as opposed to one occasion of doing it.
   *
   * @param parentId the shared definition this derives from, if any. Sharing is a pointer rather
   *     than a copy, so improving the community's form notes improves everybody's.
   */
  public record Def(long id, Long ownerId, Long parentId, String name, String slug, Measure measure,
                    String summary, String instructions, String referenceUrl, String tags,
                    String target, int restSeconds, boolean shared, Timestamp retiredAt,
                    Long createdBy, Timestamp createdAt, Timestamp updatedAt) {

    public boolean hasRest() {
      return restSeconds > 0;
    }

    /** the rest, in the words somebody would say it: "2m", "90s" */
    public String restSaid() {
      if (restSeconds <= 0) {
        return "";
      }
      if (restSeconds < 60) {
        return restSeconds + "s";
      }
      int minutes = restSeconds / 60;
      int rest = restSeconds % 60;
      return rest == 0 ? minutes + "m" : minutes + "m " + rest + "s";
    }

    public boolean retired() {
      return retiredAt != null;
    }

    public boolean isCommunitys() {
      return ownerId == null;
    }

    public boolean derived() {
      return parentId != null;
    }

    public List<String> tagList() {
      ArrayList<String> out = new ArrayList<>();
      for (String tag : tags.split("[,\\n]")) {
        String clean = tag.trim();
        if (!clean.isEmpty()) {
          out.add(clean);
        }
      }
      return out;
    }

    /** how many sets to offer when somebody opens it, from the target blob */
    public int suggestedSets() {
      return Math.max(1, Math.min(20, targetInt("sets", measure.hasSets() ? 3 : 1)));
    }

    public int targetInt(String key, int fallback) {
      try {
        JsonNode node = JSON.readTree(target == null || target.isBlank() ? "{}" : target);
        JsonNode value = node.get(key);
        return value == null || !value.canConvertToInt() ? fallback : value.asInt();
      } catch (Exception ex) {
        return fallback;
      }
    }

    public Double targetDouble(String key) {
      try {
        JsonNode node = JSON.readTree(target == null || target.isBlank() ? "{}" : target);
        JsonNode value = node.get(key);
        return value == null || !value.isNumber() ? null : value.asDouble();
      } catch (Exception ex) {
        return null;
      }
    }
  }

  /**
   * How several tasks are done together.
   *
   * <b>Two modes, and the difference is what happens between them.</b> A superset is `related`: you
   * alternate, and the rest belongs after the round rather than after each set -- which is the whole
   * reason people superset in the first place, and getting it wrong turns a time-saving device into
   * one that takes longer. A circuit or a warm-up building into a working set is `sequenced`: the
   * order is the point, and doing the third one first is doing something else.
   */
  public enum Grouping {
    /** a superset: alternate between them, rest after the round */
    related,
    /** a circuit or a progression: the order is the point */
    sequenced;

    public static Grouping of(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }

    public String label() {
      return this == related ? "superset" : "in order";
    }

    public String hint() {
      return this == related
          ? "Alternate between them, and rest after the round rather than after each set."
          : "Do them in this order.";
    }
  }

  /** one item in one project: a thing to do, or a thing to do again */
  public record Task(long id, long projectId, Long defId, String title, String notes, String phase,
                     String groupName, Grouping grouping, int position, Timestamp doneAt,
                     int repeatDays, LocalDate dueOn, Long assignedTo, Long createdBy,
                     Timestamp createdAt, Timestamp updatedAt) {

    public boolean grouped() {
      return grouping != null && groupName != null && !groupName.isBlank();
    }

    public boolean done() {
      return doneAt != null;
    }

    public boolean repeats() {
      return repeatDays > 0;
    }

    public boolean overdue(LocalDate today) {
      return dueOn != null && doneAt == null && dueOn.isBefore(today);
    }

    /** when this comes round again, once it has been done */
    public LocalDate nextDue(LocalDate from) {
      return repeatDays > 0 ? from.plusDays(repeatDays) : null;
    }
  }

  /**
   * One recorded set, or one occasion of ticking something off.
   *
   * @param difficulty how hard, one to five, or null for "did not say" -- which is a different fact
   *     from three, and the difference is what stops an unanswered form quietly reading as average.
   */
  public record Entry(long id, Long taskId, Long defId, Long projectId, long userId, int setIndex,
                      Double weight, Integer reps, Integer seconds, Double distance,
                      Integer difficulty, Integer timeCost, Integer impact, String note,
                      Timestamp recordedAt) {

    public boolean rated() {
      return difficulty != null || timeCost != null || impact != null;
    }

    public String describe(Measure measure) {
      return measure.describe(weight, reps, seconds, distance);
    }

    public Double effort(Measure measure) {
      return measure.effort(weight, reps, seconds, distance);
    }

    /**
     * What this was worth for what it cost.
     *
     * Impact over time, which is the number the whole feedback idea is for: somebody tuning a
     * routine towards high impact for little time is looking for exactly this ratio, and neither
     * half of it means anything alone. Null unless both were answered -- a score built from one
     * answer and an assumption is a score that flatters whatever was left blank.
     */
    public Double worth() {
      if (impact == null || timeCost == null || timeCost <= 0) {
        return null;
      }
      return (double) impact / timeCost;
    }
  }

  /** what a definition has come to, over however long somebody has been doing it */
  public record History(Def def, int occasions, int sets, Timestamp lastAt, Double bestEffort,
                        Double lastEffort, Double bestOneRepMax, Timestamp bestOneRepMaxAt,
                        Double averageDifficulty, Double averageTime, Double averageImpact,
                        List<Entry> recent) {

    public boolean any() {
      return occasions > 0;
    }

    /**
     * The best estimate this history contains, rounded to something somebody would say.
     *
     * Half a kilo, because the number is an estimate from a curve and printing 102.83kg claims a
     * precision that is not in the input -- and because the plates come in halves.
     */
    public Double estimatedMax() {
      return bestOneRepMax == null ? null : Math.round(bestOneRepMax * 2) / 2.0;
    }

    /** the ratio the tuning is aimed at, or null when nobody has said */
    public Double worth() {
      if (averageImpact == null || averageTime == null || averageTime <= 0) {
        return null;
      }
      return averageImpact / averageTime;
    }

    /**
     * A sentence about whether this is worth its place.
     *
     * Deliberately a judgement said out loud rather than a number on a dial: the numbers are three
     * small integers averaged over a handful of occasions, which is enough to notice a pattern and
     * nowhere near enough to rank a routine. Saying "this is costing more than it gives" is a
     * prompt to think; printing 2.7 out of 5 is a claim to precision that is not there.
     */
    public String verdict() {
      if (averageImpact == null || averageTime == null) {
        return "Not enough said about it yet.";
      }
      if (averageImpact >= 4 && averageTime <= 2) {
        return "High impact for little time -- keep this one.";
      }
      if (averageImpact >= 4) {
        return "Worth the time it takes.";
      }
      if (averageImpact <= 2 && averageTime >= 4) {
        return "Costing more than it gives -- worth replacing.";
      }
      if (averageDifficulty != null && averageDifficulty <= 2 && averageImpact <= 3) {
        return "Easy, and not doing much. Harder, or drop it.";
      }
      return "Middling so far.";
    }
  }
}
