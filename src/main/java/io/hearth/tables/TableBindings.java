package io.hearth.tables;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.js.JavaScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bridge between a community's tables and a page that wants to read them.
 *
 * <b>Every function a page gets is generated from a definition somebody wrote down.</b> A table
 * with two indexes produces exactly two `_list_` functions, and there is no way to express a query
 * that was not declared -- no filter argument, no operator, no fragment of SQL. That is the whole
 * security design and it is worth stating plainly: the page never names a column, it names a
 * function, and the function was built from the stored definition's own strings.
 *
 * <b>Reads only.</b> A page renders; it does not write. A dynamic page runs on every request,
 * including a crawler's, so a page that could insert would be a table filling itself up with
 * whatever fetched it. Writing is for the admin section and the model endpoint, where there is
 * somebody to hold responsible.
 *
 * <b>Query parameters arrive already typed.</b> They are known before the script starts, so they go
 * in as a JSON object rather than as a callback -- one fewer round trip and one fewer thing to get
 * wrong. See {@link #typed} for what "strictest" means.
 */
public final class TableBindings implements JavaScript.Host {
  private static final Logger LOG = LoggerFactory.getLogger(TableBindings.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final UserTables tables;
  private final boolean mayWrite;
  private final Long actor;

  /** what a page gets: reads only */
  public TableBindings(UserTables tables) {
    this(tables, false, null);
  }

  /**
   * What a mutation gets: the same reads, plus merge.
   *
   * The write half is a constructor argument rather than a method somebody remembers not to call,
   * so a page's bindings cannot grow one by accident -- the prologue a page is given does not
   * contain the function at all, which is a stronger statement than a refusal.
   */
  public TableBindings(UserTables tables, boolean mayWrite, Long actor) {
    this.tables = tables;
    this.mayWrite = mayWrite;
    this.actor = actor;
  }

  /**
   * A page's worth of JavaScript, on one line.
   *
   * One line because the engine computes the line number an author is shown by subtracting the
   * prologue's height, and a table added to the community must not move everybody's error
   * messages.
   */
  public String prologue(Map<String, String> queryParameters) {
    StringBuilder out = new StringBuilder(512);
    out.append("var __query=").append(queryJson(queryParameters)).append(';');
    // query(name) gives the typed value or null; query(name, fallback) gives the fallback when the
    // parameter is absent *or* when it is the wrong shape for the fallback offered
    out.append("function query(n,d){var v=__query[n];if(v===undefined||v===null){")
        .append("return d===undefined?null:d;}")
        .append("if(d!==undefined&&d!==null&&typeof d!==typeof v){return d;}return v;}");
    for (UserTable table : tables.all()) {
      String name = table.name();
      out.append("function ").append(name).append("_get_id(i){return __call('")
          .append(name).append("','get',[i]);}");
      for (String index : table.indexes()) {
        out.append("function ").append(name).append("_list_").append(index)
            .append("(v){return __call('").append(name).append("','ix',['")
            .append(index).append("',v]);}");
      }
      out.append("function ").append(name).append("_page(a,c){return __call('")
          .append(name).append("','page',[a,c]);}");
      out.append("function ").append(name).append("_all(){return __call('")
          .append(name).append("','all',[]);}");
      if (mayWrite) {
        out.append("function ").append(name)
            .append("_merge_by_id(i,d){return __call('").append(name)
            .append("','merge',[i,d]);}");
      }
    }
    return out.toString();
  }

  /**
   * The extra line a mutation gets: what was submitted.
   *
   * Typed the same way a query parameter is, for the same reason -- a form posting `count=2` should
   * arrive as a number, because the merge that receives it is going into a number column.
   */
  public String formPrologue(Map<String, String> fields) {
    StringBuilder out = new StringBuilder(256);
    out.append("var __form=").append(queryJson(fields)).append(';');
    out.append("function form(n,d){var v=__form[n];if(v===undefined||v===null){")
        .append("return d===undefined?null:d;}")
        .append("if(d!==undefined&&d!==null&&typeof d!==typeof v){return d;}return v;}");
    return out.toString();
  }

  /**
   * Answer one request from a page.
   *
   * The table name is looked up rather than trusted, and an unknown one comes back as an error the
   * prologue throws -- a page asking for a table that was dropped this morning should say so on the
   * line that asked, not return an empty list that looks like "no rows yet".
   */
  @Override
  public String data(String requestJson) {
    try {
      JsonNode request = JSON.readTree(requestJson);
      String name = request.path("t").asText("");
      String op = request.path("op").asText("");
      JsonNode args = request.path("a");
      UserTable table = tables.byName(name);
      if (table == null) {
        return error("there is no table called '" + name + "'");
      }
      return switch (op) {
        case "get" -> {
          Map<String, Object> row = tables.getById(name, asLong(args.path(0)));
          yield row == null ? "null" : UserTables.toJson(row);
        }
        case "ix" -> {
          String index = args.path(0).asText("");
          if (!table.hasIndex(index)) {
            yield error("'" + name + "' has no index on '" + index + "'");
          }
          yield UserTables.toJson(tables.listByIndex(name, index, plain(args.path(1))));
        }
        case "page" -> UserTables.toJson(
            tables.page(name, asLong(args.path(0)), (int) asLong(args.path(1))));
        case "all" -> UserTables.toJson(tables.all(name));
        case "merge" -> {
          if (!mayWrite) {
            yield error("a page cannot write; merging is for a mutation");
          }
          yield UserTables.toJson(merge(name, args));
        }
        default -> error("'" + op + "' is not something a page can ask a table for");
      };
    } catch (Exception ex) {
      LOG.error("user-table-read-failed", ex);
      return error("that table could not be read");
    }
  }

  /**
   * One merge, as the object a program reads back.
   *
   * `{success: true}` or `{success: false, reasons: [...]}` -- always both keys shaped the same way,
   * so a caller can branch on `success` without checking whether `reasons` is there.
   */
  private Map<String, Object> merge(String table, JsonNode args) {
    LinkedHashMap<String, Object> answer = new LinkedHashMap<>();
    long id = asLong(args.path(0));
    JsonNode deltaNode = args.path(1);
    if (!deltaNode.isObject()) {
      answer.put("success", false);
      answer.put("reasons", List.of("the change has to be an object of field names to values"));
      return answer;
    }
    LinkedHashMap<String, Object> delta = new LinkedHashMap<>();
    deltaNode.fields().forEachRemaining(entry -> delta.put(entry.getKey(),
        plain(entry.getValue())));
    UserTables.Merged merged = tables.mergeById(table, id, delta, actor);
    answer.put("success", merged.success());
    answer.put("reasons", merged.reasons());
    return answer;
  }

  private static String error(String message) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__error", message);
    return node.toString();
  }

  private static long asLong(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return 0;
    }
    if (node.isNumber()) {
      return node.asLong();
    }
    try {
      return Long.parseLong(node.asText().trim());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static Object plain(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.asDouble();
    }
    return node.asText();
  }

  private static String queryJson(Map<String, String> parameters) {
    LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
    if (parameters != null) {
      for (Map.Entry<String, String> entry : parameters.entrySet()) {
        typed.put(entry.getKey(), typed(entry.getValue()));
      }
    }
    return UserTables.toJson(typed);
  }

  /**
   * The strictest JavaScript type this text honestly is.
   *
   * `?page=2` should arrive as the number 2, because every page that reads it is going to do
   * arithmetic on it and `"2" + 1` is `"21"` -- a bug that produces a plausible wrong answer rather
   * than an error, which is the worst kind. So: an integer becomes an integer, a decimal becomes a
   * number, `true`/`false` become booleans, and everything else stays a string.
   *
   * <b>Integers are narrowed deliberately.</b> A whole number comes back as a Java long and reaches
   * JavaScript without a decimal point, so `query('page', 0)` is `2` rather than `2.0` and
   * `String(query('page'))` is `"2"` rather than `"2.0"` -- which is what ends up in the href of the
   * next-page link.
   *
   * <b>An empty string stays an empty string</b>, because `?q=` is somebody clearing a search box
   * and turning it into 0 or false would be inventing an answer.
   */
  static Object typed(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return raw;
    }
    if (value.equals("true")) {
      return Boolean.TRUE;
    }
    if (value.equals("false")) {
      return Boolean.FALSE;
    }
    // a leading zero is somebody's identifier rather than a number: "007" must not become 7, and
    // neither must a phone number with a plus on the front
    if (value.length() > 1 && (value.charAt(0) == '0' || value.charAt(0) == '+')) {
      return raw;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      // not an integer; try a decimal
    }
    try {
      double number = Double.parseDouble(value);
      if (Double.isFinite(number)) {
        return number;
      }
    } catch (NumberFormatException ignored) {
      // not a number at all
    }
    return raw;
  }

  /** what the documentation and the editor both show: every function this community's tables give */
  public List<String> functions() {
    java.util.ArrayList<String> names = new java.util.ArrayList<>();
    for (UserTable table : tables.all()) {
      names.addAll(table.functions());
    }
    return names;
  }
}
