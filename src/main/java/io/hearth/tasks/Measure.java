package io.hearth.tasks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a recorded set of a task actually consists of.
 *
 * <b>A closed list, for the same reason permissions are one.</b> A definition that could invent its
 * own measurement is a definition nothing can chart, compare or add up -- and the whole point of
 * recording a workout is that last month is comparable to this month. Seven of them cover what
 * people actually track, and an eighth is a conversation rather than a config key.
 *
 * <b>Four columns hold all of it.</b> Weight, reps, seconds, distance -- each nullable, each
 * meaningless unless this measure declares it. Seven tables would be seven history queries and
 * seven charts, the seventh of which would eventually disagree with the first. What a measure does
 * is say which of the four are asked for, in which order, and what to call them.
 *
 * <b>Weight is signed on purpose.</b> A weighted pull-up is +20kg and an assisted one is -20kg, and
 * they are the same movement getting easier or harder along one axis. Two measures for that would
 * put somebody's progress on two charts with a gap in the middle, exactly where the interesting
 * part is -- the week they stopped needing the band.
 */
public enum Measure {
  /** nothing to record: it is done or it is not */
  none("Done or not", "just tick it off"),
  /** the ordinary barbell case */
  weight_reps("Weight and reps", "e.g. 60kg x 8"),
  /** press-ups, pull-ups, anything moving only you */
  bodyweight_reps("Bodyweight reps", "e.g. 12"),
  /**
   * Bodyweight with the load adjusted either way.
   *
   * Positive is weight added; negative is assistance taken off. The two are one number because they
   * are one axis, and somebody's first unassisted rep is the moment that number crosses zero.
   */
  weighted_bodyweight("Bodyweight, plus or minus weight", "e.g. +10kg x 5, or -20kg assisted"),
  /** a plank, a hang, a stretch */
  duration("Time", "e.g. 90s"),
  /** a loaded carry, a weighted hang */
  duration_weight("Time under weight", "e.g. 60s at 24kg"),
  /** a run, a row, a ride */
  distance_duration("Distance and time", "e.g. 5km in 26m"),
  /** a sled push, a farmer's walk */
  weight_distance("Weight over a distance", "e.g. 40kg for 40m");

  /** one thing a set records */
  public enum Field {
    weight("Weight", "kg"),
    reps("Reps", ""),
    seconds("Time", "s"),
    distance("Distance", "m");

    public final String label;
    public final String unit;

    Field(String label, String unit) {
      this.label = label;
      this.unit = unit;
    }
  }

  public final String label;
  public final String hint;

  Measure(String label, String hint) {
    this.label = label;
    this.hint = hint;
  }

  public static Measure of(String raw) {
    if (raw == null || raw.isBlank()) {
      return none;
    }
    try {
      return valueOf(raw.trim().toLowerCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** the fields this measure asks for, in the order somebody would say them aloud */
  public List<Field> fields() {
    return switch (this) {
      case none -> List.of();
      case weight_reps -> List.of(Field.weight, Field.reps);
      case bodyweight_reps -> List.of(Field.reps);
      case weighted_bodyweight -> List.of(Field.weight, Field.reps);
      case duration -> List.of(Field.seconds);
      case duration_weight -> List.of(Field.seconds, Field.weight);
      case distance_duration -> List.of(Field.distance, Field.seconds);
      case weight_distance -> List.of(Field.weight, Field.distance);
    };
  }

  public boolean asks(Field field) {
    return fields().contains(field);
  }

  /** is this something you do sets of, or something you tick off? */
  public boolean hasSets() {
    return this != none;
  }

  /** may the weight be below zero, meaning help rather than load? */
  public boolean signed() {
    return this == weighted_bodyweight;
  }

  /**
   * One set, in the words somebody would use.
   *
   * Built here rather than in a template so that the page, the history, the export and anything a
   * model reads all say it the same way -- a set that reads "60 x 8" on one screen and "8 @ 60kg"
   * on another is two formats to learn for one fact.
   */
  public String describe(Double weight, Integer reps, Integer seconds, Double distance) {
    return switch (this) {
      case none -> "done";
      case weight_reps -> num(weight) + "kg x " + (reps == null ? "?" : reps);
      case bodyweight_reps -> (reps == null ? "?" : reps) + " reps";
      case weighted_bodyweight -> {
        String load = weight == null || weight == 0 ? "bodyweight"
            : (weight > 0 ? "+" + num(weight) + "kg" : num(weight) + "kg assisted");
        yield load + " x " + (reps == null ? "?" : reps);
      }
      case duration -> time(seconds);
      case duration_weight -> time(seconds) + " at " + num(weight) + "kg";
      case distance_duration -> length(distance) + " in " + time(seconds);
      case weight_distance -> num(weight) + "kg for " + length(distance);
    };
  }

  /**
   * The one number worth charting for this measure.
   *
   * <b>Deliberately not one formula for everything.</b> Tonnage is the right answer for a barbell
   * lift and nonsense for a 5k; time is the right answer for a plank and backwards for a run, where
   * less is better. So each measure names what "more" means for it, and anything that plots a
   * history asks rather than assuming -- a chart that silently rewarded slower running would be
   * worse than no chart.
   *
   * @return null when the numbers do not add up to a single comparable figure.
   */
  public Double effort(Double weight, Integer reps, Integer seconds, Double distance) {
    return switch (this) {
      case none -> null;
      // volume: what was lifted, however many times
      case weight_reps -> weight == null || reps == null ? null : weight * reps;
      case bodyweight_reps -> reps == null ? null : (double) reps;
      // the added or removed load is the axis somebody is actually moving along
      case weighted_bodyweight -> reps == null ? null
          : (weight == null ? 0 : weight) * reps + reps;
      case duration, duration_weight -> seconds == null ? null
          : (this == duration ? seconds : seconds * (weight == null ? 1 : Math.max(1, weight)));
      case distance_duration -> distance == null ? null : distance;
      case weight_distance -> weight == null || distance == null ? null : weight * distance;
    };
  }

  /**
   * The weight this set suggests somebody could lift once, or null when the question is meaningless.
   *
   * <b>Epley, and only up to {@value #HONEST_REPS} reps.</b> Every one-rep-max formula is a curve
   * fitted to what people in a study managed, and every one of them drifts badly as the reps go up:
   * at twenty reps the answer is dominated by how long somebody can suffer rather than by what they
   * can lift, and the number stops being about strength at all. So this refuses past a point rather
   * than returning a confident figure nobody should act on -- a set of thirty press-ups does not
   * have a one-rep max, and pretending otherwise would put a number on a screen that somebody would
   * then try to beat.
   *
   * <b>Only where a bar is loaded.</b> A plank has no one-rep max, and neither does a 5k. Bodyweight
   * work with load added is included, because the added weight is a real axis and somebody moving
   * from assisted to +20kg is doing exactly what this number is for -- but with the body's own mass
   * unknown, it is the *added* load it speaks about, which the label says out loud.
   *
   * @return the estimate in kg, or null when this measure or this set cannot support one.
   */
  public Double oneRepMax(Double weight, Integer reps) {
    if (this != weight_reps && this != weighted_bodyweight) {
      return null;
    }
    if (weight == null || reps == null || reps < 1 || reps > HONEST_REPS) {
      return null;
    }
    if (weight <= 0) {
      // an assisted rep is easier than one unassisted; there is no maximum to estimate from it
      return null;
    }
    if (reps == 1) {
      return weight;
    }
    return weight * (1 + reps / 30.0);
  }

  /** past this many reps a one-rep-max estimate is about endurance rather than strength */
  public static final int HONEST_REPS = 12;

  /** whether asking about a one-rep max makes any sense for this measure at all */
  public boolean hasOneRepMax() {
    return this == weight_reps || this == weighted_bodyweight;
  }

  /** what the estimate is an estimate of, since for bodyweight work it is the added load */
  public String oneRepMaxLabel() {
    return this == weighted_bodyweight ? "estimated best added weight for one rep"
        : "estimated one-rep max";
  }

  /** what "better" means here, for a screen that would otherwise congratulate somebody wrongly */
  public boolean moreIsBetter() {
    return true;
  }

  /** what a chart of this is a chart of */
  public String effortLabel() {
    return switch (this) {
      case none -> "";
      case weight_reps -> "volume (kg lifted)";
      case bodyweight_reps -> "reps";
      case weighted_bodyweight -> "effective reps";
      case duration -> "seconds";
      case duration_weight -> "seconds under load";
      case distance_duration -> "distance (m)";
      case weight_distance -> "kg-metres";
    };
  }

  /** the boxes a form draws, for one set */
  public List<Map<String, Object>> boxes() {
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (Field field : fields()) {
      LinkedHashMap<String, Object> box = new LinkedHashMap<>();
      box.put("name", field.name());
      box.put("label", field.label);
      box.put("unit", field.unit);
      box.put("signed", field == Field.weight && signed());
      box.put("whole", field == Field.reps || field == Field.seconds);
      out.add(box);
    }
    return out;
  }

  /** every measure, for a form that has to offer them */
  public static List<Map<String, Object>> all() {
    ArrayList<Map<String, Object>> out = new ArrayList<>();
    for (Measure measure : values()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", measure.name());
      row.put("label", measure.label);
      row.put("hint", measure.hint);
      out.add(row);
    }
    return out;
  }

  private static String num(Double value) {
    if (value == null) {
      return "?";
    }
    return value == Math.rint(value) ? String.valueOf((long) (double) value) : String.valueOf(value);
  }

  private static String time(Integer seconds) {
    if (seconds == null) {
      return "?";
    }
    if (seconds < 60) {
      return seconds + "s";
    }
    int minutes = seconds / 60;
    int rest = seconds % 60;
    if (minutes < 60) {
      return rest == 0 ? minutes + "m" : minutes + "m " + rest + "s";
    }
    return (minutes / 60) + "h " + (minutes % 60) + "m";
  }

  private static String length(Double metres) {
    if (metres == null) {
      return "?";
    }
    return metres >= 1000 ? num(metres / 1000) + "km" : num(metres) + "m";
  }
}
