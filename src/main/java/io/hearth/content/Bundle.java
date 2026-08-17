package io.hearth.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A community's pages and templates as one JSON document, and back again.
 *
 * <b>What this is for is somewhere else.</b> A site that can only be edited through its own admin
 * screen is a site with no history anybody else can read, no way to write six pages on a train, and
 * no answer to "restore what it looked like in March" that does not involve a database file. A
 * bundle is a file: check it into git, generate it from a repository of markdown, hand it to
 * somebody, keep it as a backup.
 *
 * <b>The uuid is the whole design.</b> A uri is an address and an id is a row number in one
 * database; neither survives the round trip, because the point of the round trip is that the other
 * end is a different install -- or the same one, three months and two renames later. Every page and
 * every template carries a key that is stamped once and never rewritten, so bringing a bundle back
 * is a *merge*: same key, same page, whatever its address has become.
 *
 * <b>Importing goes through the ordinary save.</b> Every page written by an import is versioned,
 * emits its mutation event and drops the caches exactly as an edit from the screen does -- so the
 * history says the site was imported on Tuesday rather than quietly skipping a day, and a bad
 * import is undone page by page from the history like any other bad edit.
 *
 * <b>A page whose key is new but whose address is taken is adopted, not duplicated.</b> That is the
 * first import into a site somebody has already been writing by hand, which is the common case and
 * the one where making two pages at one address would be worst.
 */
public final class Bundle {
  /** the format's own version, so a reader from later can tell what it is holding */
  public static final int FORMAT = 1;
  private static final ObjectMapper JSON = new ObjectMapper();
  /** the most rows one bundle carries; the scale target is a design input */
  private static final int CEILING = 5000;

  private Bundle() {
  }

  /** what happened to one row, and which of its parts moved */
  public record Change(String uuid, String name, String status, List<String> changed) {
    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String UNCHANGED = "unchanged";
    public static final String SKIPPED = "skipped";
  }

  /** what an import did, in the words a screen wants */
  public record Report(int pagesAdded, int pagesUpdated, int templatesAdded, int templatesUpdated,
                       List<String> problems, List<Change> pages, List<Change> templates,
                       int pagesUnchanged, int templatesUnchanged, boolean dryRun) {
    Report(int pagesAdded, int pagesUpdated, int templatesAdded, int templatesUpdated,
           List<String> problems) {
      this(pagesAdded, pagesUpdated, templatesAdded, templatesUpdated, problems, List.of(),
          List.of(), 0, 0, false);
    }

    public int total() {
      return pagesAdded + pagesUpdated + templatesAdded + templatesUpdated;
    }

    public String describe() {
      StringBuilder out = new StringBuilder();
      if (dryRun) {
        out.append("Nothing was written. ");
      }
      out.append(pagesAdded).append(" page(s) added, ").append(pagesUpdated).append(" updated");
      if (pagesUnchanged > 0) {
        out.append(", ").append(pagesUnchanged).append(" already the same");
      }
      if (templatesAdded + templatesUpdated > 0) {
        out.append("; ").append(templatesAdded).append(" template(s) added, ")
            .append(templatesUpdated).append(" updated");
      }
      if (!problems.isEmpty()) {
        out.append(". Skipped: ").append(String.join("; ", problems));
      }
      return out.append('.').toString();
    }
  }

  /**
   * The whole site, or one page of it.
   *
   * The templates come along whether or not one page or all of them were asked for, because a page
   * that names a template it arrives without renders as a bare body on the other side -- which
   * looks exactly like the import having failed.
   */
  public static byte[] of(ContentStore content, String community, String domain, String stamp,
                          Long onlyPage) throws SQLException {
    ObjectNode root = JSON.createObjectNode();
    root.put("hearth", FORMAT);
    root.put("community", community);
    root.put("domain", domain);
    root.put("exported_at", stamp);

    ArrayNode templates = root.putArray("templates");
    for (TemplateRecord template : content.allTemplates(CEILING)) {
      ObjectNode row = templates.addObject();
      row.put("uuid", orNew(content.templateUuidOf(template.id())));
      row.put("name", template.name());
      row.put("parameters", template.parameters());
      row.put("body", template.body());
      row.put("directory", template.directory());
      row.put("directory_path", template.directoryPath());
      row.put("directory_pattern", template.directoryPattern());
      row.put("directory_page_size", template.directoryPageSize());
      row.put("directory_order", template.directoryOrder());
    }

    ArrayNode pages = root.putArray("content");
    for (ContentRecord page : content.allContent(CEILING)) {
      if (onlyPage != null && page.id() != onlyPage) {
        continue;
      }
      ObjectNode row = pages.addObject();
      row.put("uuid", orNew(content.uuidOf(page.id())));
      row.put("uri", page.uri());
      row.put("title", page.title());
      row.put("kind", page.kind().name());
      row.put("template", page.templateName() == null ? "" : page.templateName());
      row.put("folder", page.navFolder());
      row.put("fields", page.fields());
      row.put("body", page.body());
      row.put("published", page.published());
      row.put("human_only", page.humanOnly());
    }
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("a bundle that cannot be written is a bug", ex);
    }
  }

  /**
   * Bring one back.
   *
   * Templates first, so a page arriving with a template that is new here finds it already written
   * down. Everything is matched by uuid, then by name or address, then created -- and a row with no
   * uuid at all is still importable, because a bundle generated from a directory of markdown files
   * by somebody else's tooling will not have invented one.
   */
  public static Report apply(ContentStore content, String json, Long actor, String actorEmail)
      throws SQLException {
    return apply(content, json, actor, actorEmail, false);
  }

  /**
   * @param dryRun work out what would change and write nothing. This is what makes the API worth
   *     driving from a script: the answer to "what will this do" has to be obtainable without
   *     doing it, and a diff nobody can see before it lands is a diff nobody reviews.
   */
  public static Report apply(ContentStore content, String json, Long actor, String actorEmail,
                             boolean dryRun) throws SQLException {
    JsonNode root;
    try {
      root = JSON.readTree(json == null ? "" : json);
    } catch (Exception ex) {
      return new Report(0, 0, 0, 0, List.of("that is not JSON: " + ex.getMessage()));
    }
    if (root == null || !root.isObject()) {
      return new Report(0, 0, 0, 0, List.of("a bundle is a JSON object"));
    }
    ArrayList<String> problems = new ArrayList<>();
    ArrayList<Change> templateChanges = new ArrayList<>();
    ArrayList<Change> pageChanges = new ArrayList<>();
    int templatesAdded = 0;
    int templatesUpdated = 0;
    int templatesSame = 0;
    int pagesAdded = 0;
    int pagesUpdated = 0;
    int pagesSame = 0;

    for (JsonNode row : array(root, "templates")) {
      String name = text(row, "name");
      if (name.isBlank()) {
        problems.add("a template with no name");
        templateChanges.add(new Change("", "", Change.SKIPPED, List.of("it has no name")));
        continue;
      }
      String uuid = text(row, "uuid");
      TemplateRecord existing = content.templateByUuid(uuid);
      if (existing == null) {
        existing = content.templateByName(name);
      }
      // the name is the key inside this database, so a template arriving under a key we know keeps
      // whatever it is called here rather than renaming a template every page names
      String saveAs = existing == null ? name : existing.name();
      String parameters = row.has("parameters") ? text(row, "parameters") : "[]";
      String body = text(row, "body");
      boolean directory = row.path("directory").asBoolean(false);
      String directoryPath = text(row, "directory_path");
      String directoryPattern = text(row, "directory_pattern");
      int pageSize = row.path("directory_page_size").asInt(10);
      String order = text(row, "directory_order").isBlank()
          ? "newest" : text(row, "directory_order");

      List<String> moved = existing == null ? List.of()
          : differences(
              new String[]{"body", "parameters", "directory", "directory_path",
                  "directory_pattern", "directory_page_size", "directory_order"},
              new Object[]{existing.body(), existing.parameters(), existing.directory(),
                  existing.directoryPath(), existing.directoryPattern(),
                  existing.directoryPageSize(), existing.directoryOrder()},
              new Object[]{body, parameters, directory, directoryPath, directoryPattern,
                  pageSize, order});
      if (existing != null && moved.isEmpty()) {
        // Nothing to do, and doing it anyway is not free: a save is a mutation event, a cache drop
        // and -- for content -- a version. A tool that pushes the whole site every time would fill
        // the history with edits nobody made.
        templatesSame++;
        templateChanges.add(new Change(uuid, saveAs, Change.UNCHANGED, List.of()));
        continue;
      }
      if (!dryRun) {
        content.saveTemplate(saveAs, body, parameters, directory, directoryPath, directoryPattern,
            pageSize, order, actor);
        TemplateRecord saved = content.templateByName(saveAs);
        if (saved != null) {
          content.setUuid(io.hearth.store.Schema.TEMPLATES, saved.id(), uuid);
        }
      }
      if (existing == null) {
        templatesAdded++;
        templateChanges.add(new Change(uuid, saveAs, Change.CREATED, List.of()));
      } else {
        templatesUpdated++;
        templateChanges.add(new Change(uuid, saveAs, Change.UPDATED, moved));
      }
    }

    for (JsonNode row : array(root, "content")) {
      String uri = text(row, "uri");
      if (!uri.startsWith("/")) {
        problems.add("a page whose uri is '" + uri + "'");
        pageChanges.add(new Change(text(row, "uuid"), uri, Change.SKIPPED,
            List.of("a uri has to start with '/'")));
        continue;
      }
      String uuid = text(row, "uuid");
      ContentRecord existing = content.byUuid(uuid);
      boolean adopted = false;
      if (existing == null) {
        // the same address, a key we have never seen: the first import into a site somebody has
        // been writing by hand. Two pages at one address is the worst possible answer here.
        existing = content.byUri(uri);
        adopted = existing != null;
      }
      ContentRecord page = new ContentRecord(existing == null ? 0 : existing.id(), uri,
          text(row, "title"), ContentRecord.Kind.of(text(row, "kind")),
          text(row, "template").isBlank() ? null : text(row, "template"),
          text(row, "folder"), text(row, "fields").isBlank() ? "{}" : text(row, "fields"),
          text(row, "body"), row.path("published").asBoolean(false),
          row.path("human_only").asBoolean(false), null, null, actor);

      List<String> moved = existing == null ? List.of() : differences(
          new String[]{"uri", "title", "kind", "template", "folder", "fields", "body",
              "published", "human_only"},
          new Object[]{existing.uri(), existing.title(), existing.kind(),
              existing.templateName() == null ? "" : existing.templateName(),
              existing.navFolder(), existing.fields(), existing.body(), existing.published(),
              existing.humanOnly()},
          new Object[]{page.uri(), page.title(), page.kind(),
              page.templateName() == null ? "" : page.templateName(),
              page.navFolder(), page.fields(), page.body(), page.published(), page.humanOnly()});
      boolean keyIsNew = existing != null && !uuid.isBlank()
          && !uuid.equals(content.uuidOf(existing.id()));
      if (existing != null && moved.isEmpty() && !keyIsNew) {
        pagesSame++;
        pageChanges.add(new Change(uuid, uri, Change.UNCHANGED, List.of()));
        continue;
      }
      if (!dryRun) {
        ContentRecord saved = content.save(page, actor, actorEmail);
        content.setUuid(io.hearth.store.Schema.CONTENT, saved.id(), uuid);
      }
      if (existing == null) {
        pagesAdded++;
        pageChanges.add(new Change(uuid, uri, Change.CREATED, List.of()));
      } else {
        pagesUpdated++;
        pageChanges.add(new Change(uuid, uri, Change.UPDATED, moved));
      }
      if (adopted) {
        problems.add(uri + " already existed here and was adopted rather than duplicated");
      }
    }
    return new Report(pagesAdded, pagesUpdated, templatesAdded, templatesUpdated, problems,
        pageChanges, templateChanges, pagesSame, templatesSame, dryRun);
  }

  /** which of these parts are not the same on both sides, by name */
  private static List<String> differences(String[] names, Object[] before, Object[] after) {
    ArrayList<String> moved = new ArrayList<>();
    for (int k = 0; k < names.length; k++) {
      Object was = before[k] == null ? "" : before[k];
      Object now = after[k] == null ? "" : after[k];
      if (!was.equals(now)) {
        moved.add(names[k]);
      }
    }
    return moved;
  }

  /** what one page looks like on its own, for the download beside its editor */
  public static Map<String, Object> summary(Report report) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("pages_added", report.pagesAdded());
    out.put("pages_updated", report.pagesUpdated());
    out.put("templates_added", report.templatesAdded());
    out.put("templates_updated", report.templatesUpdated());
    out.put("problems", report.problems());
    return out;
  }

  private static List<JsonNode> array(JsonNode root, String name) {
    ArrayList<JsonNode> rows = new ArrayList<>();
    JsonNode node = root.get(name);
    if (node != null && node.isArray()) {
      node.forEach(rows::add);
    }
    return rows;
  }

  private static String text(JsonNode row, String name) {
    JsonNode value = row.get(name);
    return value == null || value.isNull() ? "" : value.asText("");
  }

  private static String orNew(String uuid) {
    return uuid == null || uuid.isBlank() ? java.util.UUID.randomUUID().toString() : uuid;
  }

  /** the bytes as a string, for a caller holding an upload */
  public static String text(byte[] json) {
    return json == null ? "" : new String(json, StandardCharsets.UTF_8);
  }
}
