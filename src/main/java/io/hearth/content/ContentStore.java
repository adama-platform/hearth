package io.hearth.content;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The content and templates tables.
 *
 * Every write announces itself on the event bus before returning, naming the table and the row.
 * That is what makes the caches correct: nothing here knows what is caching pages, and nothing that
 * caches pages knows about this class.
 */
public class ContentStore {
  private static final String CONTENT_COLUMNS =
      "id, uri, title, kind, template_name, nav_folder, fields, body, published, human_only,"
          + " published_at, created_at, updated_at, updated_by";
  private static final String TEMPLATE_COLUMNS =
      "id, name, parameters, body, directory, directory_path, directory_pattern, directory_body,"
          + " directory_page_size, directory_order, created_at, updated_at, updated_by";

  private final Store store;
  /** every save is recorded; the history is a property of writing content, not of the admin UI */
  private final ContentVersions versions;

  public ContentStore(Store store) {
    this.store = store;
    this.versions = new ContentVersions(store);
  }

  // ---- content -------------------------------------------------------------------------------

  private Proposals proposals;

  /** suggested edits waiting for somebody to say yes */
  public synchronized Proposals proposals() {
    if (proposals == null) {
      proposals = new Proposals(store, this);
    }
    return proposals;
  }

  public ContentVersions versions() {
    return versions;
  }

  public ContentRecord byUri(String uri) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CONTENT_COLUMNS + " FROM " + Schema.CONTENT + " WHERE uri = ?")) {
      statement.setString(1, uri);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readContent(rows) : null;
      }
    }
  }

  /**
   * The merge key: what this page is called when neither its address nor its row number survives.
   *
   * Kept off {@link ContentRecord} on purpose. A version snapshot is the whole *document* -- body,
   * title, template, folder, fields, published, human-only -- and identity is not part of a
   * document: restoring March's version must not be able to change which page this is.
   */
  public String uuidOf(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT uuid FROM " + Schema.CONTENT + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  public ContentRecord byUuid(String uuid) throws SQLException {
    if (uuid == null || uuid.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CONTENT_COLUMNS + " FROM " + Schema.CONTENT + " WHERE uuid = ?")) {
      statement.setString(1, uuid.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readContent(rows) : null;
      }
    }
  }

  /**
   * Set a merge key, whatever is there.
   *
   * The one caller is the import, and it is the authority: a page arriving under a key is that
   * page, and adopting one written here means taking its name -- otherwise the next import is a
   * second copy of everything.
   */
  public void setUuid(String table, long id, String uuid) throws SQLException {
    if (uuid == null || uuid.isBlank()) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + table + " SET uuid = ? WHERE id = ?")) {
      statement.setString(1, uuid.trim());
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  /** stamp a merge key onto a row that has none; never overwrites one */
  public void stampUuid(String table, long id, String uuid) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + table + " SET uuid = ? WHERE id = ? AND uuid = ''")) {
      statement.setString(1, uuid);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  /**
   * Give everything that has no merge key one, once.
   *
   * At boot rather than lazily, because the first thing an operator does with this is export the
   * whole site -- and a bundle where half the rows have no key is a bundle that imports as
   * duplicates on the other side. Runs over rows with an empty key only, so it is a no-op on the
   * second boot.
   */
  public int stampMissingUuids() throws SQLException {
    int stamped = 0;
    for (String table : new String[]{Schema.CONTENT, Schema.TEMPLATES}) {
      ArrayList<Long> missing = new ArrayList<>();
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "SELECT id FROM " + table + " WHERE uuid = ''");
           ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          missing.add(rows.getLong(1));
        }
      }
      for (Long id : missing) {
        stampUuid(table, id, java.util.UUID.randomUUID().toString());
        stamped++;
      }
    }
    return stamped;
  }

  public ContentRecord byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CONTENT_COLUMNS + " FROM " + Schema.CONTENT + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readContent(rows) : null;
      }
    }
  }

  public List<ContentRecord> allContent(int limit) throws SQLException {
    ArrayList<ContentRecord> pages = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CONTENT_COLUMNS + " FROM " + Schema.CONTENT + " ORDER BY uri "
                 + store.dialect().limit(limit))) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          pages.add(readContent(rows));
        }
      }
    }
    return pages;
  }

  /** create or replace a page by uri; returns the row and announces the change */
  public ContentRecord save(ContentRecord page, Long actor) throws SQLException {
    return save(page, actor, null);
  }

  /**
   * Save, recording who did it.
   *
   * The email is carried alongside the id so a history listing needs no join, and so removing an
   * account does not erase the authorship of everything that account ever wrote.
   */
  public ContentRecord save(ContentRecord page, Long actor, String actorEmail) throws SQLException {
    // Identity is the id when the caller has one, and the uri only when it does not. Matching on
    // uri alone meant that changing a page's address in the editor did not rename it -- it created
    // a second page and left the first one there, with the history stranded under the old id. That
    // is the one failure a version history cannot survive, since a history that stops at a rename
    // is a history of a page nobody is editing any more.
    //
    // The uri fallback is deliberate and still needed: the model tools save by uri without knowing
    // an id, and "create it or update it" is exactly what they mean.
    ContentRecord existing = page.id() > 0 ? byId(page.id()) : null;
    if (existing == null) {
      existing = byUri(page.uri());
    }
    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.CONTENT + " (uri, title, kind, template_name, nav_folder,"
                   + " fields, body, published, human_only, updated_by, published_at)"
                   + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
               Statement.RETURN_GENERATED_KEYS)) {
        bindContent(statement, page, actor);
        // absent means "the day this was written", which is what the reader falls back to -- so
        // the column stays null rather than being filled with a copy of created_at that would then
        // have to be kept in step with it
        statement.setTimestamp(11, page.publishedAt());
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          keys.next();
          // a new page gets its merge key here rather than at the next boot, so a site exported
          // five minutes after it was written is a bundle that imports as a merge
          stampUuid(Schema.CONTENT, keys.getLong(1), java.util.UUID.randomUUID().toString());
          ContentRecord saved = byId(keys.getLong(1));
          store.changed(Schema.CONTENT, saved.id(), MutationEvent.Kind.insert, actor);
          versions.record(saved, actor, actorEmail);
          return saved;
        }
      }
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CONTENT + " SET uri = ?, title = ?, kind = ?, template_name = ?,"
                 + " nav_folder = ?, fields = ?, body = ?, published = ?, human_only = ?,"
                 + " updated_by = ?, published_at = ?, updated_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ?")) {
      bindContent(statement, page, actor);
      // an edit that says nothing about the date keeps the one already there rather than clearing
      // it, so saving a page does not quietly move it to the top of a listing
      statement.setTimestamp(11, page.publishedAt() != null ? page.publishedAt()
          : existing.publishedAt());
      statement.setLong(12, existing.id());
      statement.executeUpdate();
    }
    ContentRecord saved = byId(existing.id());
    store.changed(Schema.CONTENT, saved.id(), MutationEvent.Kind.update, actor);
    versions.record(saved, actor, actorEmail);
    return saved;
  }

  public void deleteContent(long id, Long actor) throws SQLException {
    // the history goes with the page: keeping versions of something that no longer exists means a
    // list nobody can reach and a uri that quietly comes back if the page is recreated
    versions.forget(id, actor);
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CONTENT + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.CONTENT, id, MutationEvent.Kind.delete, actor);
  }

  public long contentCount() throws SQLException {
    return count(Schema.CONTENT);
  }

  // ---- templates -----------------------------------------------------------------------------

  /** the merge key of one template, for the same reason content has one */
  public String templateUuidOf(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT uuid FROM " + Schema.TEMPLATES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  public TemplateRecord templateByUuid(String uuid) throws SQLException {
    if (uuid == null || uuid.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TEMPLATE_COLUMNS + " FROM " + Schema.TEMPLATES + " WHERE uuid = ?")) {
      statement.setString(1, uuid.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readTemplate(rows) : null;
      }
    }
  }

  public TemplateRecord templateByName(String name) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TEMPLATE_COLUMNS + " FROM " + Schema.TEMPLATES + " WHERE name = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readTemplate(rows) : null;
      }
    }
  }

  public List<TemplateRecord> allTemplates(int limit) throws SQLException {
    ArrayList<TemplateRecord> templates = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TEMPLATE_COLUMNS + " FROM " + Schema.TEMPLATES + " ORDER BY name "
                 + store.dialect().limit(limit));
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        templates.add(readTemplate(rows));
      }
    }
    return templates;
  }

  /**
   * Create or replace a template.
   *
   * The event names the template row, and the cascade -- dropping every rendered page that used it
   * -- happens in the listener. Doing it here would mean this class knew what a page cache was.
   */
  public TemplateRecord saveTemplate(String name, String body, Long actor) throws SQLException {
    return saveTemplate(name, body, "[]", actor);
  }

  public TemplateRecord saveTemplate(String name, String body, String parameters, Long actor) throws SQLException {
    TemplateRecord existing = templateByName(name);
    return saveTemplate(name, body, parameters,
        existing != null && existing.directory(),
        existing == null ? "" : existing.directoryPath(),
        existing == null ? "" : existing.directoryPattern(),
        existing == null ? 10 : existing.directoryPageSize(),
        existing == null ? "newest" : existing.directoryOrder(), actor);
  }

  public TemplateRecord saveTemplate(String name, String body, String parameters,
                                     boolean directory, String directoryPath,
                                     String directoryPattern, int pageSize, String order,
                                     Long actor) throws SQLException {
    TemplateRecord had = templateByName(name);
    return saveTemplate(name, body, parameters, directory, directoryPath, directoryPattern,
        had == null ? "" : had.directoryBody(), pageSize, order, actor);
  }

  /** @param directoryBody the index's own markup; empty falls back to the page template */
  public TemplateRecord saveTemplate(String name, String body, String parameters,
                                     boolean directory, String directoryPath,
                                     String directoryPattern, String directoryBody, int pageSize,
                                     String order, Long actor) throws SQLException {
    TemplateRecord existing = templateByName(name);
    if (existing == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.TEMPLATES + " (name, body, parameters, directory,"
                   + " directory_path, directory_pattern, directory_page_size, directory_order,"
                   + " updated_by, directory_body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
               Statement.RETURN_GENERATED_KEYS)) {
        statement.setString(1, name);
        statement.setString(2, body);
        statement.setString(3, parameters);
        statement.setBoolean(4, directory);
        statement.setString(5, orEmpty(directoryPath));
        statement.setString(6, orEmpty(directoryPattern));
        statement.setInt(7, pageSize);
        statement.setString(8, orEmpty(order).isBlank() ? "newest" : order);
        setActor(statement, 9, actor);
        // ticking the box gets a working listing rather than a second empty box: two templates
        // that both do something is what "it publishes an index" should mean
        statement.setString(10, seedIndex(directory, body, directoryBody));
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          keys.next();
          stampUuid(Schema.TEMPLATES, keys.getLong(1), java.util.UUID.randomUUID().toString());
          store.changed(Schema.TEMPLATES, keys.getLong(1), MutationEvent.Kind.insert, actor);
        }
      }
      return templateByName(name);
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TEMPLATES + " SET body = ?, parameters = ?, directory = ?,"
                 + " directory_path = ?, directory_pattern = ?, directory_page_size = ?,"
                 + " directory_order = ?, updated_by = ?, directory_body = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, body);
      statement.setString(2, parameters);
      statement.setBoolean(3, directory);
      statement.setString(4, orEmpty(directoryPath));
      statement.setString(5, orEmpty(directoryPattern));
      statement.setInt(6, pageSize);
      statement.setString(7, orEmpty(order).isBlank() ? "newest" : order);
      setActor(statement, 8, actor);
      // an index that has never been written gets the working one the moment the box is ticked
      statement.setString(9, existing.hasOwnIndex() && orEmpty(directoryBody).isBlank()
          ? existing.directoryBody() : seedIndex(directory, body, directoryBody));
      statement.setLong(10, existing.id());
      statement.executeUpdate();
    }
    store.changed(Schema.TEMPLATES, existing.id(), MutationEvent.Kind.update, actor);
    return templateByName(name);
  }

  /**
   * Whether to write the free listing in, and the one case where it must not.
   *
   * A template whose own body branches on `{{#directory}}` was written to be both halves, and
   * taking the index over would change what it renders -- so it is left alone and keeps working
   * exactly as it did. Everything else gets a listing that works the moment the box is ticked.
   */
  private static String seedIndex(boolean directory, String body, String directoryBody) {
    if (!orEmpty(directoryBody).isBlank()) {
      return directoryBody;
    }
    if (!directory || orEmpty(body).contains("{{#directory}}")) {
      return "";
    }
    return defaultIndexBody();
  }

  /**
   * The listing somebody gets for free when they tick the box.
   *
   * Written out rather than left empty because an empty second template is a directory that
   * renders nothing, and the first thing anybody would do is ask what it should contain. This is
   * the answer, in a shape they can then edit: the entries, the fields the template declares, and
   * the pagination.
   */
  public static String defaultIndexBody() {
    return "<article class=\"index\">\n"
        + "  <h1>{{title}}</h1>\n"
        + "  {{^anyEntries}}<p>Nothing here yet.</p>{{/anyEntries}}\n"
        + "  {{#entries}}\n"
        + "  <section class=\"entry\">\n"
        + "    <h2><a href=\"{{uri}}\">{{title}}</a></h2>\n"
        + "    <p class=\"when\">{{at}}</p>\n"
        + "    <p>{{excerpt}}</p>\n"
        + "  </section>\n"
        + "  {{/entries}}\n"
        + "  {{#pages}}\n"
        + "  <nav class=\"pages\">\n"
        + "    {{#hasPrev}}<a href=\"{{prevUrl}}\">&larr; newer</a>{{/hasPrev}}\n"
        + "    {{#numbers}}<a href=\"{{url}}\"{{#here}} aria-current=\"page\"{{/here}}>{{n}}</a>{{/numbers}}\n"
        + "    {{#hasNext}}<a href=\"{{nextUrl}}\">older &rarr;</a>{{/hasNext}}\n"
        + "  </nav>\n"
        + "  {{/pages}}\n"
        + "</article>\n";
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * Every page that names this template, for its listing.
   *
   * Published only, and ordered by when it was created rather than when it was last touched: a
   * blog whose front page reshuffles because somebody fixed a typo in a two-year-old post is one
   * nobody can find anything in.
   */
  public java.util.List<ContentRecord> usingTemplate(String templateName, boolean newestFirst,
                                                     int offset, int limit) throws SQLException {
    java.util.ArrayList<ContentRecord> found = new java.util.ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + CONTENT_COLUMNS + " FROM " + Schema.CONTENT
                 + " WHERE template_name = ? AND published = TRUE AND human_only = FALSE"
                 // by the day it went out rather than the day the row appeared: a page drafted in
                 // January and published in March is a March page, and filing it two months back
                 // is filing it where nobody looks
                 + " ORDER BY COALESCE(published_at, created_at) "
                 + (newestFirst ? "DESC" : "ASC") + ", id " + (newestFirst ? "DESC" : "ASC")
                 + " OFFSET " + Math.max(0, offset) + " ROWS FETCH FIRST ? ROWS ONLY")) {
      statement.setString(1, templateName);
      statement.setInt(2, Math.max(1, limit));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readContent(rows));
        }
      }
    }
    return found;
  }

  /** how many entries a listing has, for the page count */
  public int countUsingTemplate(String templateName) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.CONTENT
                 + " WHERE template_name = ? AND published = TRUE AND human_only = FALSE")) {
      statement.setString(1, templateName);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  public void deleteTemplate(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.TEMPLATES + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TEMPLATES, id, MutationEvent.Kind.delete, actor);
  }

  public long templateCount() throws SQLException {
    return count(Schema.TEMPLATES);
  }

  /** the navigation tree: folder path to the pages inside it, for templates that walk it */
  public java.util.TreeMap<String, java.util.List<ContentRecord>> navigation() throws SQLException {
    java.util.TreeMap<String, java.util.List<ContentRecord>> tree = new java.util.TreeMap<>();
    for (ContentRecord page : allContent(5000)) {
      if (!page.published() || page.isOutsideNavigation()) {
        continue;
      }
      tree.computeIfAbsent(page.navFolder(), key -> new ArrayList<>()).add(page);
    }
    return tree;
  }

  /** every folder anybody has used, so the editor can offer them */
  public java.util.TreeSet<String> folders() throws SQLException {
    java.util.TreeSet<String> folders = new java.util.TreeSet<>();
    for (ContentRecord page : allContent(5000)) {
      if (!page.isOutsideNavigation()) {
        folders.add(page.navFolder());
      }
    }
    return folders;
  }

  /** the names of pages using a template, so a cascade can name what it dropped */
  public List<String> urisUsingTemplate(String templateName) throws SQLException {
    ArrayList<String> uris = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT uri FROM " + Schema.CONTENT + " WHERE template_name = ?")) {
      statement.setString(1, templateName);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          uris.add(rows.getString(1));
        }
      }
    }
    return uris;
  }

  // ---- plumbing ------------------------------------------------------------------------------

  private long count(String table) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static void bindContent(PreparedStatement statement, ContentRecord page, Long actor) throws SQLException {
    statement.setString(1, page.uri());
    statement.setString(2, page.title());
    statement.setString(3, page.kind().name());
    // a full page uses no template, so it must not keep a stale reference to one
    statement.setString(4, page.kind().wantsTemplate() ? page.templateName() : null);
    statement.setString(5, page.navFolder() == null ? "" : page.navFolder());
    statement.setString(6, page.fields() == null || page.fields().isBlank() ? "{}" : page.fields());
    statement.setString(7, page.body());
    statement.setBoolean(8, page.published());
    statement.setBoolean(9, page.humanOnly());
    setActor(statement, 10, actor);
  }

  private static void setActor(PreparedStatement statement, int index, Long actor) throws SQLException {
    if (actor == null) {
      statement.setNull(index, java.sql.Types.BIGINT);
    } else {
      statement.setLong(index, actor);
    }
  }

  private static ContentRecord readContent(ResultSet rows) throws SQLException {
    long updatedBy = rows.getLong("updated_by");
    boolean wasNull = rows.wasNull();
    return new ContentRecord(
        rows.getLong("id"),
        rows.getString("uri"),
        rows.getString("title"),
        ContentRecord.Kind.of(rows.getString("kind")),
        rows.getString("template_name"),
        rows.getString("nav_folder"),
        rows.getString("fields"),
        rows.getString("body"),
        rows.getBoolean("published"),
        rows.getBoolean("human_only"),
        rows.getTimestamp("published_at"),
        rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"),
        wasNull ? null : updatedBy);
  }

  private static TemplateRecord readTemplate(ResultSet rows) throws SQLException {
    long updatedBy = rows.getLong("updated_by");
    boolean wasNull = rows.wasNull();
    return new TemplateRecord(
        rows.getLong("id"),
        rows.getString("name"),
        rows.getString("parameters"),
        rows.getString("body"),
        rows.getBoolean("directory"),
        rows.getString("directory_path"),
        rows.getString("directory_pattern"),
        rows.getString("directory_body"),
        rows.getInt("directory_page_size"),
        rows.getString("directory_order"),
        rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"),
        wasNull ? null : updatedBy);
  }
}
