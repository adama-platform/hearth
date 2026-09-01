package io.hearth.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools an agent is offered, and what each one does.
 *
 * A tool description is a prompt. The model reads nothing else about this server, so the wording
 * here is the entire briefing it gets -- which is why these say what a thing is *for* and when not
 * to use it, rather than restating the parameter names.
 *
 * The set is deliberately small. Every tool is one a person would recognize as a job ("search the
 * site", "ask the community something") rather than a row operation, because a model given
 * `execute_sql` will eventually execute some SQL.
 */
public class McpTools {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AiSurface surface;

  public McpTools(AiSurface surface) {
    this.surface = surface;
  }

  /** one callable tool: its name, its briefing, and the shape of its arguments */
  public record Tool(String name, String title, String description, ObjectNode schema) {
  }

  /**
   * What each tool needs the acting person to be allowed to do, or null for anybody connected.
   *
   * <b>Offered means usable.</b> A tool a connection can never call is invariant 38 in a model's
   * hands: a control that would refuse teaches whoever meets it that the software is broken, and a
   * model meeting one spends its turns finding a phrasing that works. This is the same map the
   * surface enforces with -- listed here so the two cannot drift, and enforced there because a
   * listing is a courtesy and the surface is the boundary.
   *
   * Reads that a member legitimately has a narrowed version of are absent from this map on
   * purpose: they are offered to everybody and answer with less. Refusing to list events for
   * somebody who can see the calendar would be worse than useless.
   */
  private static final java.util.Map<String, io.hearth.auth.Permission> NEEDS =
      java.util.Map.ofEntries(
          java.util.Map.entry("content_save", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("content_meta", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("content_delete", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("template_list", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("template_get", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("template_save", io.hearth.auth.Permission.templates_write),
          java.util.Map.entry("template_delete", io.hearth.auth.Permission.templates_write),
          java.util.Map.entry("navigation_get", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("site_spec", io.hearth.auth.Permission.content_read));

  /** what a tool needs, for the listing and for the screen that explains a connection */
  public static io.hearth.auth.Permission needs(String tool) {
    return NEEDS.get(tool);
  }

  public List<Tool> all() {
    ArrayList<Tool> tools = new ArrayList<>();

    tools.add(new Tool("content_list", "List pages",
        "Every page on this site, with its uri, title, template and folder. Start here when you"
            + " need to know what exists. Bodies are not included -- use content_get for one page"
            + " or content_search to find pages by what is in them."
            + " Pages an admin has marked human only are not listed and cannot be reached.",
        schema(prop("folder", "string", "only pages in this navigation folder"),
            prop("published", "boolean", "true for published only, false for drafts only"))));

    tools.add(new Tool("content_search", "Search pages",
        "Find pages whose uri, title or body contains some text. Returns an excerpt around the"
            + " match. Use this rather than listing everything and reading each page.",
        required(schema(prop("query", "string", "text to look for; matching is case insensitive")),
            "query")));

    tools.add(new Tool("content_get", "Read a page",
        "One page in full, including its body exactly as stored. The body is markdown, an HTML"
            + " fragment, or a whole HTML document depending on the page's kind.",
        required(schema(prop("uri", "string", "the page's path, e.g. /about")), "uri")));

    tools.add(new Tool("content_save", "Write a page",
        "Create a page, or change one that exists. Only the fields you pass are changed -- anything"
            + " you leave out keeps its current value, so you can fix a body without resending the"
            + " title. Read the page first if you are editing it; you are replacing the body"
            + " wholesale, not patching it.",
        required(schema(
            prop("uri", "string", "the page's path, e.g. /about; creates it if there is none"),
            prop("title", "string", "shown in the browser tab and available to the template"),
            prop("body", "string", "the page source, in the form its kind implies"),
            prop("kind", "string", "markdown, html, page (a whole HTML document), or javascript"
                + " (a program run on every request -- call site_spec first, its javascript"
                + " section lists every function available and this community's own tables)"),
            prop("template", "string", "the template to wrap it in; ignored when kind is page"),
            prop("folder", "string", "navigation folder; empty leaves it out of the navigation"),
            objectProp("fields", "values for the fields this page's template declares, as"
                + " {\"field_name\": \"value\"}. Read them from template_get. Only the ones you"
                + " pass are changed; a name the template does not declare is refused rather than"
                + " ignored."),
            prop("published", "boolean", "unpublished pages are not served")),
            "uri")));

    tools.add(new Tool("content_meta", "Change a page's details, not its words",
        "Retitle a page, move it between navigation folders, publish or unpublish it, change which"
            + " template wraps it, or fill in the fields that template declares -- while leaving"
            + " the body exactly as it is. This tool cannot write a body at all, which is the"
            + " reason to use it: filing pages or filling in a subtitle should not involve handing"
            + " back somebody's prose and hoping it came through unchanged. Use content_save when"
            + " the words themselves are what is changing. The page has to exist already.",
        required(schema(
            prop("uri", "string", "the page's path, e.g. /about"),
            prop("title", "string", "shown in the browser tab and available to the template"),
            prop("template", "string", "the template to wrap it in"),
            prop("folder", "string", "navigation folder; empty leaves it out of the navigation"),
            objectProp("fields", "values for the fields the template declares, as"
                + " {\"field_name\": \"value\"}; only the ones you pass are changed"),
            prop("published", "boolean", "unpublished pages are not served")),
            "uri")));

    tools.add(new Tool("content_delete", "Delete a page",
        "Remove a page for good. There is no undo and no version history, so prefer setting"
            + " published to false if there is any chance somebody wants it back.",
        required(schema(prop("uri", "string", "the page's path")), "uri")));










    // ---- how to build anything here ------------------------------------------------------------
    //
    // Two dead section headers stood here, for the polls and the training log, describing rules a
    // model would need for features that were removed a while ago.

    tools.add(new Tool("site_spec", "How to build a site here",
        "Everything you need to write pages that work: every kind of page, the address rule for"
            + " each, how a template declares fields, and -- in its `javascript` section -- every"
            + " function a dynamic page can call, including this community's own tables and the"
            + " shape of their rows. That part is generated from what exists right now, so read it"
            + " again rather than remembering it. Read this before writing anything other than a"
            + " plain markdown page.",
        schema()));

    tools.add(new Tool("navigation_get", "Read the navigation",
        "The navigation tree: which pages sit in which folder, and which pages sit outside it. A"
            + " page outside the navigation is reachable by its uri and by nothing else, which is"
            + " usually an oversight worth reporting.",
        schema()));

    tools.add(new Tool("template_list", "List templates",
        "The templates pages can be wrapped in, with the fields each one declares and how many"
            + " pages use it.",
        schema()));

    tools.add(new Tool("template_get", "Read a template",
        "One template's mustache source, the fields it declares in full, and the uris that depend"
            + " on it. Read this before template_save if you are changing the fields: saving them"
            + " replaces the declaration wholesale, so send back the ones you are keeping.",
        required(schema(prop("name", "string", "the template's name")), "name")));

    tools.add(new Tool("template_save", "Write a template",
        "Create or replace a template. Saving one immediately re-renders every page that uses it,"
            + " so check template_get first and know what you are about to change. Use {{{body}}}"
            + " with three braces for the page content; two braces will escape the markup and show"
            + " it as text."
            + " A template can also publish a **directory index**: an address of its own where"
            + " every published page using it appears in a paginated listing. That index is a"
            + " *second* template with a second job -- a list rather than a document -- so it has"
            + " its own body. Pass directory_body to write it; leave it out and a working listing"
            + " is written for you. The index is given {{#entries}} (uri, title, at, excerpt,"
            + " folder, and any field this template declares), count, page, pages,"
            + " prevUrl/nextUrl/firstUrl/lastUrl and {{#numbers}}."
            + " A template can also **declare fields** -- the things it wants from every page"
            + " beyond a body, like a subtitle or a hero line. Declaring one puts a box on the"
            + " page editor and makes {{field_name}} available in this template; pages fill it in"
            + " with content_save or content_meta. Leave fields out and what is declared stays as"
            + " it is; pass it and it becomes exactly what you sent, so read template_get first.",
        required(schema(
            prop("name", "string", "letters, digits, underscore or hyphen"),
            prop("body", "string", "the mustache template source"),
            objectArrayProp("fields", "what every page using this template is asked for, as"
                + " [{\"name\":\"subtitle\",\"type\":\"text\",\"label\":\"Subtitle\","
                + "\"help\":\"one line under the title\",\"required\":false}]."
                + " name is lowercase letters, digits and underscore. type is one of text,"
                + " multiline, markdown, number, bool, url, date. Removing a field stops pages"
                + " being asked for it and does not delete what they already recorded."),
            prop("directory", "boolean", "publish an index of every page using this template"),
            prop("directory_path", "string", "where the index lives, e.g. /blog"),
            prop("directory_pattern", "string",
                "how page two is addressed, with {page} in it; page one is always the bare path"),
            prop("directory_body", "string", "the index's own mustache source"),
            prop("directory_page_size", "integer", "entries per page"),
            prop("directory_order", "string", "newest or oldest, by each page's published date")),
            "name", "body")));

    tools.add(new Tool("template_delete", "Delete a template",
        "Remove a template. Refused while any page still uses it.",
        required(schema(prop("name", "string", "the template's name")), "name")));









    return tools;
  }

  /** the tools/list payload, narrowed to what this connection can actually call */
  public ArrayNode listing() throws SQLException {
    ArrayNode array = JSON.createArrayNode();
    for (Tool tool : offered()) {
      ObjectNode node = array.addObject();
      node.put("name", tool.name());
      node.put("title", tool.title());
      node.put("description", tool.description());
      node.set("inputSchema", tool.schema());
    }
    return array;
  }

  /** every tool this connection may call; a write surface is absent rather than refusing */
  public List<Tool> offered() throws SQLException {
    ArrayList<Tool> offered = new ArrayList<>();
    for (Tool tool : all()) {
      io.hearth.auth.Permission needed = NEEDS.get(tool.name());
      if (needed == null || surface.may(needed)) {
        offered.add(tool);
      }
    }
    return offered;
  }

  public boolean has(String name) {
    return all().stream().anyMatch(tool -> tool.name().equals(name));
  }

  /** what a tool call produced, plus the short line that goes in the AI log */
  public record Result(Object payload, String subject, String detail) {
  }

  /**
   * Run a tool.
   *
   * Everything reachable from here goes through {@link AiSurface}, which is where the human-only
   * rule and the read-only rule live. Nothing in this class talks to a store.
   */
  public Result call(String name, JsonNode arguments) throws SQLException, AiSurface.Refused {
    Map<String, Object> args = asMap(arguments);
    switch (name) {
      case "content_list" -> {
        List<Map<String, Object>> pages = surface.listContent(
            optString(args, "folder"), optBoolean(args, "published"));
        return new Result(Map.of("pages", pages, "count", pages.size()),
            null, pages.size() + " page(s)");
      }
      case "content_search" -> {
        String query = optString(args, "query");
        List<Map<String, Object>> hits = surface.searchContent(query);
        return new Result(Map.of("matches", hits, "count", hits.size()),
            query, hits.size() + " match(es) for '" + query + "'");
      }
      case "content_get" -> {
        String uri = optString(args, "uri");
        Map<String, Object> page = surface.getContent(uri);
        if (page == null) {
          // deliberately the same answer whether the page is missing or locked to humans
          throw new AiSurface.Refused("there is no page at '" + uri + "'");
        }
        return new Result(page, uri, "read " + uri);
      }
      case "content_save" -> {
        String uri = optString(args, "uri");
        Map<String, Object> saved = surface.saveContent(uri, args);
        return new Result(saved, uri,
            (Boolean.TRUE.equals(saved.get("created")) ? "created " : "updated ") + uri);
      }
      case "content_meta" -> {
        String uri = optString(args, "uri");
        Map<String, Object> saved = surface.saveContentMeta(uri, args);
        // the log line says which of the two kinds of write this was, because "updated /about"
        // reading the same for a retitle and a rewrite is the thing somebody auditing an agent
        // afterwards most needs told apart
        return new Result(saved, uri, "changed the details of " + uri + ", body untouched");
      }
      case "content_delete" -> {
        String uri = optString(args, "uri");
        return new Result(surface.deleteContent(uri), uri, "deleted " + uri);
      }
      case "site_spec" -> {
        Map<String, Object> spec = surface.siteSpec();
        return new Result(spec, null, "the shape of a page here");
      }
      case "navigation_get" -> {
        return new Result(surface.navigation(), null, "read the navigation");
      }
      case "template_list" -> {
        List<Map<String, Object>> templates = surface.listTemplates();
        return new Result(Map.of("templates", templates, "count", templates.size()),
            null, templates.size() + " template(s)");
      }
      case "template_get" -> {
        String templateName = optString(args, "name");
        Map<String, Object> template = surface.getTemplate(templateName);
        if (template == null) {
          throw new AiSurface.Refused("there is no template called '" + templateName + "'");
        }
        return new Result(template, templateName, "read template " + templateName);
      }
      case "template_save" -> {
        String templateName = optString(args, "name");
        Map<String, Object> saved = surface.saveTemplate(templateName, optString(args, "body"),
            args);
        return new Result(saved, templateName,
            "saved template " + templateName + ", re-rendering " + saved.get("re_rendered") + " page(s)");
      }
      case "template_delete" -> {
        String templateName = optString(args, "name");
        return new Result(surface.deleteTemplate(templateName), templateName,
            "deleted template " + templateName);
      }
      default -> throw new AiSurface.Refused("there is no tool called '" + name + "'");
    }
  }

  // ---- schema helpers ----------------------------------------------------------------------------

  private static ObjectNode schema(ObjectNode... properties) {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    for (ObjectNode property : properties) {
      props.set(property.get("__name").asText(), strip(property));
    }
    return schema;
  }

  private static ObjectNode required(ObjectNode schema, String... names) {
    ArrayNode required = schema.putArray("required");
    for (String name : names) {
      required.add(name);
    }
    return schema;
  }

  private static ObjectNode prop(String name, String type, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", type);
    node.put("description", description);
    if (type.equals("array")) {
      node.putObject("items").put("type", "string");
    }
    return node;
  }

  /**
   * A list of objects, which {@link #prop} cannot express.
   *
   * prop() declares {@code items: string} for every array, which is right for the several tools
   * taking a list of names and wrong for a list of declarations -- and a schema that promises
   * strings while the handler reads objects is a model told to send the one thing that will be
   * refused.
   */
  private static ObjectNode objectArrayProp(String name, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", "array");
    node.put("description", description);
    node.putObject("items").put("type", "object");
    return node;
  }

  /** a free-form object, for the fields a community invented and this code has never heard of */
  private static ObjectNode objectProp(String name, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", "object");
    node.put("description", description);
    node.putObject("additionalProperties").put("type", "string");
    return node;
  }

  private static ObjectNode strip(ObjectNode property) {
    ObjectNode copy = property.deepCopy();
    copy.remove("__name");
    return copy;
  }

  // ---- argument reading --------------------------------------------------------------------------

  private static Map<String, Object> asMap(JsonNode node) {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    if (node == null || !node.isObject()) {
      return map;
    }
    node.fields().forEachRemaining(entry -> map.put(entry.getKey(), unwrap(entry.getValue())));
    return map;
  }

  private static Object unwrap(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isArray()) {
      ArrayList<Object> list = new ArrayList<>();
      node.forEach(item -> list.add(unwrap(item)));
      return list;
    }
    // An object argument used to fall through to asText(), which for a container node is the empty
    // string -- so every nested object a tool declared arrived as "". place_save has advertised a
    // `fields` object since the address book shipped and reads it with an `instanceof Map` that
    // could never be true, which meant a model filling in a kind's own fields was told it had
    // worked and nothing was written. That is the precise failure invariant 102 refuses for an
    // *undeclared* field, arriving through the plumbing instead: silent success for a write that
    // did not happen. Objects are now objects, and ToolArgumentTests holds both halves down.
    if (node.isObject()) {
      LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
      node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), unwrap(entry.getValue())));
      return fields;
    }
    return node.asText();
  }

  /** a list of strings from an argument that may be one, several, or absent */
  private static List<String> strings(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return List.of();
    }
    ArrayList<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
    } else {
      // a model that sent one string where a list was asked for meant one option, and refusing
      // that is a refusal about JSON rather than about the community
      out.add(String.valueOf(value));
    }
    return out;
  }

  private static Double optDouble(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String optString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static int optInt(Map<String, Object> args, String key, int fallback) {
    Object value = args.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static Boolean optBoolean(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean flag) {
      return flag;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static long requireLong(Map<String, Object> args, String key) throws AiSurface.Refused {
    Object value = args.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (RuntimeException ex) {
      throw new AiSurface.Refused(key + " is required and must be a number");
    }
  }
}
