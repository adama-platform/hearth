package io.hearth.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One question an admin asked.
 *
 * The definition is a JSON blob because the shape genuinely differs per kind -- a rating has bounds,
 * a dropdown has options, free text has neither -- and because the set is meant to change often.
 * Columns per question type would make "ask a new kind of question" a schema change, which is
 * exactly the friction this feature is trying not to have.
 *
 * The blob is parsed into this record on the way out, once, and cached. Nothing downstream sees
 * JSON.
 */
public record Question(long id, Kind kind, String prompt, String help, List<String> options,
                       int min, int max, boolean required, int position, boolean published,
                       Timestamp updatedAt, boolean deleted) {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final int MAX_OPTIONS = 40;

  /**
   * How somebody answers.
   *
   * The label and the sentence are here rather than in a template because the admin picking a kind
   * and the member answering it have to be looking at the same words, and because the primary
   * readers of a question list are an admin and an AI -- both of which do better with "a number
   * between two bounds" than with "rating".
   */
  public enum Kind {
    /** a text box; anything goes */
    free("Free text", "A box they type into. Best for anything you have not thought of yet."),
    /** pick one of the options */
    choice("Dropdown", "They pick one of the options you list, one per line."),
    /** a number between min and max */
    rating("Rating", "A number between the bounds you set, for anything comparable.");

    public final String label;
    public final String describe;

    Kind(String label, String describe) {
      this.label = label;
      this.describe = describe;
    }

    public static Kind of(String raw) {
      if (raw == null) {
        return free;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return free;
      }
    }
  }

  /** parse a stored blob; anything malformed becomes a free-text question rather than an error */
  public static Question parse(long id, String definition, int position, boolean published,
                               Timestamp updatedAt) {
    return parse(id, definition, position, published, updatedAt, false);
  }

  public static Question parse(long id, String definition, int position, boolean published,
                               Timestamp updatedAt, boolean deleted) {
    try {
      JsonNode node = JSON.readTree(definition == null ? "{}" : definition);
      Kind kind = Kind.of(text(node, "kind"));
      ArrayList<String> options = new ArrayList<>();
      JsonNode raw = node.get("options");
      if (raw != null && raw.isArray()) {
        for (int k = 0; k < raw.size() && options.size() < MAX_OPTIONS; k++) {
          if (raw.get(k).isTextual() && !raw.get(k).textValue().isBlank()) {
            options.add(raw.get(k).textValue().trim());
          }
        }
      }
      int min = node.path("min").asInt(1);
      int max = node.path("max").asInt(5);
      if (max <= min) {
        max = min + 1;
      }
      return new Question(id, kind, text(node, "prompt"), text(node, "help"), List.copyOf(options),
          min, max, node.path("required").asBoolean(false), position, published, updatedAt, deleted);
    } catch (Exception ex) {
      // a question nobody can read is still a question somebody wrote; show it as free text
      return new Question(id, Kind.free, "(unreadable question)", "", List.of(), 1, 5, false,
          position, published, updatedAt, deleted);
    }
  }

  /** the blob to store */
  public static String definition(Kind kind, String prompt, String help, List<String> options,
                                  int min, int max, boolean required) {
    ObjectNode node = JSON.createObjectNode();
    node.put("kind", kind.name());
    node.put("prompt", prompt == null ? "" : prompt);
    node.put("help", help == null ? "" : help);
    node.put("required", required);
    if (kind == Kind.choice) {
      ArrayNode array = node.putArray("options");
      if (options != null) {
        for (int k = 0; k < options.size() && k < MAX_OPTIONS; k++) {
          String option = options.get(k).trim();
          if (!option.isEmpty()) {
            array.add(option);
          }
        }
      }
    }
    if (kind == Kind.rating) {
      node.put("min", min);
      node.put("max", max);
    }
    return node.toString();
  }

  /** parse a textarea of one option per line */
  public static List<String> optionsFrom(String raw) {
    ArrayList<String> options = new ArrayList<>();
    if (raw == null) {
      return options;
    }
    for (String line : raw.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && trimmed.length() <= 200 && options.size() < MAX_OPTIONS) {
        options.add(trimmed);
      }
    }
    return options;
  }

  /** the scale, for rendering a row of buttons */
  public List<Integer> scale() {
    ArrayList<Integer> values = new ArrayList<>();
    for (int k = min; k <= max && values.size() <= 20; k++) {
      values.add(k);
    }
    return values;
  }

  /**
   * Is this answer usable?
   *
   * Deliberately forgiving for free text -- anything non-blank counts -- and strict for the kinds
   * that have a defined range, because an out-of-range rating is a client that made it up.
   */
  public boolean accepts(String answer) {
    if (answer == null || answer.isBlank()) {
      return false;
    }
    return switch (kind) {
      case free -> answer.length() <= 4000;
      case choice -> options.contains(answer.trim());
      case rating -> {
        try {
          int value = Integer.parseInt(answer.trim());
          yield value >= min && value <= max;
        } catch (NumberFormatException ex) {
          yield false;
        }
      }
    };
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isTextual() ? "" : value.textValue();
  }
}
