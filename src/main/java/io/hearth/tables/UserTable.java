package io.hearth.tables;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One table a member of this community invented, and the rules its name has to survive.
 *
 * <b>Names are validated, then prefixed, and both matter.</b> Validation keeps a name to
 * {@code [a-z][a-z0-9_]*} so it can be spliced into DDL at all; the prefix (`t_` for tables, `f_`
 * for columns) is what makes it *safe* rather than merely tidy, because H2 runs in MODE=STRICT and
 * reserves the standard's keywords. A field called `value`, `order`, `key` or `user` is an
 * obviously reasonable thing to want and every one of them is a syntax error unquoted. Prefixing
 * sidesteps the entire class rather than maintaining a denylist that is wrong the first time
 * somebody upgrades H2.
 *
 * <b>Every table has an `id` and it is not negotiable.</b> The whole JavaScript surface --
 * fetch-by-id, page-after-id, index lookup returning ids -- is built on a single monotonic key, and
 * the caching story underneath it invalidates by id. A table without one could not be cached and
 * could not be paged.
 *
 * <b>An index is a declaration, not a hint.</b> Declaring one is what creates the
 * {@code <table>_list_<field>} function, so the set of indexes is exactly the set of ways a program
 * is allowed to ask a question. That is deliberate: it means every query a page can make is one an
 * operator wrote down, and there is no way to express a scan that was not planned for.
 */
public record UserTable(String name, List<UserField> fields, List<String> indexes) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,30}");

  /** the most a table may hold; a ceiling that keeps `_all()` an honest thing to offer */
  public static final int MAX_ROWS = 100_000;

  /** how many fields one table may declare */
  public static final int MAX_FIELDS = 40;

  public UserTable {
    fields = List.copyOf(fields);
    indexes = List.copyOf(indexes);
  }

  /** the physical table; see the class note about MODE=STRICT */
  public String physical() {
    return "t_" + name;
  }

  public UserField field(String fieldName) {
    for (UserField field : fields) {
      if (field.name().equals(fieldName)) {
        return field;
      }
    }
    return null;
  }

  public boolean hasIndex(String fieldName) {
    return indexes.contains(fieldName);
  }

  /**
   * Is this a name this server will accept?
   *
   * `id` is refused as a field name because every table already has one and a second would be a
   * column the program could write and the engine would ignore.
   */
  public static String checkName(String what, String candidate) {
    if (candidate == null || !NAME.matcher(candidate).matches()) {
      return what + " must be lowercase letters, digits and underscores, starting with a letter,"
          + " and at most 31 characters";
    }
    if (candidate.equals("id")) {
      return "'id' is the one field every table already has";
    }
    return null;
  }

  /** every complaint about this definition, or an empty list if it is fine */
  public List<String> problems() {
    ArrayList<String> problems = new ArrayList<>();
    String bad = checkName("a table name", name);
    if (bad != null) {
      problems.add(bad);
    }
    if (fields.isEmpty()) {
      problems.add("a table needs at least one field");
    }
    if (fields.size() > MAX_FIELDS) {
      problems.add("a table may declare at most " + MAX_FIELDS + " fields");
    }
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (UserField field : fields) {
      String problem = checkName("a field name", field.name());
      if (problem != null) {
        problems.add(problem);
      } else if (!seen.add(field.name())) {
        problems.add("'" + field.name() + "' is declared twice");
      }
      if (field.type() == null) {
        problems.add("'" + field.name() + "' has no type");
      }
    }
    for (String index : indexes) {
      if (field(index) == null) {
        problems.add("there is no field called '" + index + "' to index");
      }
    }
    return problems;
  }

  public String toJson() {
    ObjectNode node = JSON.createObjectNode();
    node.put("name", name);
    ArrayNode fieldsNode = node.putArray("fields");
    for (UserField field : fields) {
      ObjectNode one = fieldsNode.addObject();
      one.put("name", field.name());
      one.put("type", field.type().name());
      one.put("required", field.required());
    }
    ArrayNode indexesNode = node.putArray("indexes");
    for (String index : indexes) {
      indexesNode.add(index);
    }
    return node.toString();
  }

  public static UserTable fromJson(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      ArrayList<UserField> fields = new ArrayList<>();
      for (JsonNode one : node.path("fields")) {
        fields.add(new UserField(one.path("name").asText(),
            UserField.Type.of(one.path("type").asText()),
            one.path("required").asBoolean(false)));
      }
      ArrayList<String> indexes = new ArrayList<>();
      for (JsonNode one : node.path("indexes")) {
        indexes.add(one.asText());
      }
      return new UserTable(node.path("name").asText(), fields, indexes);
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * The JavaScript functions this table puts in front of a page, in the order a person meets them.
   *
   * Generated from the definition rather than written down anywhere, so a table with three indexes
   * has three lookup functions and a table with none has none -- and the documentation the model
   * endpoint hands out is this same list.
   */
  public List<String> functions() {
    ArrayList<String> names = new ArrayList<>();
    names.add(name + "_get_id(id)");
    for (String index : indexes) {
      names.add(name + "_list_" + index + "(value)");
    }
    names.add(name + "_page(idAfter, count)");
    names.add(name + "_all()");
    return names;
  }

  public static String normalize(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }
}
