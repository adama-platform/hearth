package io.hearth.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A field a template asks every page using it to fill in.
 *
 * A template is a shell, and most shells want something from the page beyond its body -- a subtitle,
 * a hero line, a date, whether to show the sidebar. Declaring those on the template means the page
 * editor can show the right boxes instead of the author guessing which mustache variables the
 * template happens to reference.
 *
 * Stored as a JSON array on the template, because the set is the template's business and changes
 * whenever the template does. A table would make adding a field a schema migration.
 */
public record TemplateField(String name, Type type, String label, String help, boolean required) {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final int MAX_FIELDS = 40;

  /** deliberately few; text covers most of it */
  public enum Type {
    text("a single line"),
    multiline("a paragraph or two"),
    markdown("markdown, rendered before the template sees it"),
    number("a number"),
    bool("a checkbox"),
    url("a link"),
    date("a date");

    public final String describe;

    Type(String describe) {
      this.describe = describe;
    }

    public static Type of(String raw) {
      if (raw == null) {
        return text;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return text;
      }
    }
  }

  /** a name a template can actually reference and a form can carry */
  public static boolean isValidName(String name) {
    return name != null && name.matches("[a-z][a-z0-9_]{0,31}");
  }

  public String labelOr() {
    return label == null || label.isBlank() ? name : label;
  }

  /** parse a template's declaration blob; anything malformed yields no fields rather than an error */
  public static List<TemplateField> parse(String blob) {
    ArrayList<TemplateField> fields = new ArrayList<>();
    if (blob == null || blob.isBlank()) {
      return fields;
    }
    try {
      JsonNode node = JSON.readTree(blob);
      if (!node.isArray()) {
        return fields;
      }
      for (int k = 0; k < node.size() && fields.size() < MAX_FIELDS; k++) {
        JsonNode item = node.get(k);
        String name = item.path("name").asText("");
        if (!isValidName(name)) {
          continue;
        }
        fields.add(new TemplateField(name, Type.of(item.path("type").asText(null)),
            item.path("label").asText(""), item.path("help").asText(""),
            item.path("required").asBoolean(false)));
      }
    } catch (Exception ex) {
      fields.clear();
    }
    return fields;
  }

  public static String toBlob(List<TemplateField> fields) {
    ArrayNode array = JSON.createArrayNode();
    for (TemplateField field : fields) {
      ObjectNode node = array.addObject();
      node.put("name", field.name());
      node.put("type", field.type().name());
      node.put("label", field.label() == null ? "" : field.label());
      node.put("help", field.help() == null ? "" : field.help());
      node.put("required", field.required());
    }
    return array.toString();
  }
}
