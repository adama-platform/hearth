package io.hearth.settings;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One thing a community can decide about itself, and everything a screen needs to ask for it.
 *
 * The key is the dotted path it has in a domain's config file -- `board.expiry-days`, `invites.
 * tagline` -- and that is not a coincidence, it is the whole mechanism. A setting is applied by
 * writing it into a copy of the file's JSON and re-parsing the result, so every check that already
 * refuses a bad value in a file refuses the same value typed into the admin section, with the same
 * message. The alternative was a second validator per setting, which is thirty chances to disagree
 * with the one that decides whether the server boots.
 *
 * What is <b>not</b> here is as deliberate as what is. Nothing that decides who may sign in, what a
 * credential is, what a program may do, or which bytes a request is allowed to carry. Those stay in
 * a file an operator owns, because the config file is a thing you can read, diff, put in a private
 * repository and restore from a backup -- and because a setting somebody can change from a browser
 * is a setting somebody who has taken over a browser can change.
 */
public record Setting(String key, Kind kind, String group, String label, String help,
                      List<String> choices) {

  /** how a value is written down, which is also how the editor draws a box for it */
  public enum Kind {
    text("a single line"),
    multiline("a paragraph or two"),
    integer("a whole number"),
    bool("on or off"),
    choice("one of a few"),
    words("a list of words, one per line"),
    numbers("a list of whole numbers, one per line");

    public final String describe;

    Kind(String describe) {
      this.describe = describe;
    }
  }

  public static Setting text(String key, String group, String label, String help) {
    return new Setting(key, Kind.text, group, label, help, List.of());
  }

  public static Setting multiline(String key, String group, String label, String help) {
    return new Setting(key, Kind.multiline, group, label, help, List.of());
  }

  public static Setting integer(String key, String group, String label, String help) {
    return new Setting(key, Kind.integer, group, label, help, List.of());
  }

  public static Setting bool(String key, String group, String label, String help) {
    return new Setting(key, Kind.bool, group, label, help, List.of());
  }

  public static Setting choice(String key, String group, String label, String help,
                               List<String> choices) {
    return new Setting(key, Kind.choice, group, label, help, choices);
  }

  public static Setting words(String key, String group, String label, String help,
                              List<String> choices) {
    return new Setting(key, Kind.words, group, label, help, choices);
  }

  public static Setting numbers(String key, String group, String label, String help) {
    return new Setting(key, Kind.numbers, group, label, help, List.of());
  }

  /** the last segment of the key, which is what it is called inside its block */
  public String leaf() {
    int dot = key.lastIndexOf('.');
    return dot < 0 ? key : key.substring(dot + 1);
  }

  /** the block it lives in, or null for a key at the top of the file */
  public String block() {
    int dot = key.lastIndexOf('.');
    return dot < 0 ? null : key.substring(0, dot);
  }

  /** a name a form can carry without any escaping question */
  public String field() {
    return "s_" + key.replace('.', '_').replace('-', '_');
  }

  /**
   * Write this value into a copy of the config, where the ordinary parser will find it.
   *
   * A blank value removes the key rather than writing an empty one, which is what makes "clear this
   * and let the default come back" work: the file's own value, or the built-in, depending on which
   * copy this is being applied to.
   */
  public void applyTo(ObjectNode root, String value) {
    String block = block();
    ObjectNode target = block == null ? root : childOf(root, block);
    String leaf = leaf();
    if (value == null || value.isBlank()) {
      target.remove(leaf);
      return;
    }
    switch (kind) {
      case integer -> {
        try {
          // an int rather than a long, because the config reader asks Jackson `isInt()` and a
          // LongNode answers no -- so every number typed here came back as "must be an integer"
          target.put(leaf, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
          // left as text on purpose: the config parser's own type error is a better message than
          // anything invented here, and it is the message the operator would get from a file
          target.put(leaf, value.trim());
        }
      }
      case bool -> target.put(leaf, Boolean.parseBoolean(value.trim())
          || "on".equalsIgnoreCase(value.trim()));
      case words -> {
        ArrayNode array = target.putArray(leaf);
        for (String word : value.split("[\\r\\n,]+")) {
          if (!word.isBlank()) {
            array.add(word.trim());
          }
        }
      }
      case numbers -> {
        // written as strings, because the list settings this covers are read with `stringsOf` and
        // parsed afterwards -- the config reader wants the same shape a hand-written file has, and
        // a JSON number there is refused as "must be a string"
        ArrayNode array = target.putArray(leaf);
        for (String word : value.split("[\\r\\n,]+")) {
          if (!word.isBlank()) {
            array.add(word.trim());
          }
        }
      }
      default -> target.put(leaf, value);
    }
  }

  private static ObjectNode childOf(ObjectNode root, String block) {
    ObjectNode node = root;
    for (String part : block.split("\\.")) {
      com.fasterxml.jackson.databind.JsonNode existing = node.get(part);
      node = existing instanceof ObjectNode object ? object : node.putObject(part);
    }
    return node;
  }
}
