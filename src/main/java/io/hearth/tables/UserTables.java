package io.hearth.tables;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;
import io.hearth.store.Database;
import io.hearth.store.H2Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tables a community invented, in a database of their own.
 *
 * <b>A second file, and that is the whole safety argument.</b> The system database is described by
 * {@code Schema.java} in code, upgraded by diffing, and never dropped from. This one is the
 * opposite: its shape is whatever somebody typed into a form this afternoon, and the operations on
 * it are CREATE, ALTER and DROP. Those two things cannot live in one file. Putting them in one
 * would mean a `DROP TABLE` on the same connection that holds every account in the community, a
 * schema upgrader that has to tell "a table the code stopped declaring" apart from "a table a
 * member invented", and one corrupt page taking both. `<domain>.data.mv.db` sits beside
 * `<domain>.mv.db`; deleting it loses every user table and nothing else, which is a sentence an
 * operator can act on.
 *
 * <b>Every read goes through a cache keyed the way the invalidation works.</b> A row by id, an
 * index lookup by value, and the whole-table reads. A write invalidates the id it touched and --
 * this is the part that is easy to get wrong -- both the *old* and the *new* value of every indexed
 * field it changed. Invalidating only the new value leaves the row visible under its previous index
 * value for a full TTL, which is a member still listed under the group they just left.
 *
 * <b>Nothing here is reachable without a table definition.</b> Table and column names are never
 * taken from a request: a name arrives, it is looked up in the catalogue, and the SQL is built from
 * the *stored definition's* own strings. There is no path from a form field to an identifier in a
 * statement, which is the only structural defence available when the feature is "let people make
 * tables".
 */
public class UserTables implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(UserTables.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** the table names and definitions; the one table in here this software declares itself */
  private static final String CATALOGUE = "hearth_tables";

  /** what a page may ask for in one go, whatever it passes */
  public static final int MAX_PAGE = 1000;

  /** the event table name, so a listener can tell user data from everything else */
  public static final String EVENT_TABLE_PREFIX = "data:";

  /** the physical column behind {@link UserTable#HIDDEN}; prefixed like every declared field */
  private static final String HIDDEN_COLUMN = "f_" + UserTable.HIDDEN;

  /**
   * What a read is allowed to see.
   *
   * The two are never the same query and are never the same cache entry. A page asking for a row an
   * admin has hidden must get nothing, and an admin's read must not put that row into the cache the
   * page reads from -- which is why the key carries this and not a boolean tacked on the end.
   */
  public enum See {
    /** what a program gets: hidden rows absent, and the flag itself not in the row at all */
    published,
    /** what the admin's table editor gets: everything, with the flag */
    everything;

    String where(String prefix) {
      return this == published ? prefix + HIDDEN_COLUMN + " = FALSE" : "";
    }
  }

  private final String domain;
  private final File file;
  private final Database database;
  private final EventBus events;
  private final ConcurrentHashMap<String, UserTable> catalogue = new ConcurrentHashMap<>();
  private final TableCache cache;

  private UserTables(String domain, File file, Database database, EventBus events) {
    this.domain = domain;
    this.file = file;
    this.database = database;
    this.events = events;
    this.cache = new TableCache();
    events.subscribe(cache::onMutation);
  }

  /**
   * Open (or create) the data database for one domain.
   *
   * Lazily in the sense that matters: the file is created on first open, so a community that never
   * makes a table has one anyway -- an empty H2 file is a few kilobytes and the alternative is a
   * boot path with a conditional in it.
   */
  public static UserTables open(File storesRoot, String domain, EventBus events)
      throws SQLException {
    File file = new File(storesRoot, domain + ".data");
    Database database = new H2Database(file, "data-" + domain);
    UserTables tables = new UserTables(domain, file, database, events);
    tables.ensureCatalogue();
    tables.reload();
    tables.ensureHidden();
    return tables;
  }

  private void ensureCatalogue() throws SQLException {
    try (Connection connection = database.connection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS " + CATALOGUE
          + " (name VARCHAR(64) PRIMARY KEY, definition VARCHAR(65536) NOT NULL)");
    }
  }

  /**
   * Give every table the hidden column, for the ones made before there was one.
   *
   * The implicit columns are not in a definition, so nothing else would ever notice they are
   * missing -- and a table without it would fail every read the moment the filter went in. Additive
   * and idempotent, which is what makes it safe to run at every open rather than behind a version
   * number this file does not keep.
   */
  private void ensureHidden() throws SQLException {
    try (Connection connection = database.connection()) {
      for (UserTable table : all()) {
        if (hasColumn(connection, table.physical(), HIDDEN_COLUMN)) {
          continue;
        }
        try (Statement statement = connection.createStatement()) {
          statement.execute("ALTER TABLE " + table.physical() + " ADD COLUMN "
              + HIDDEN_COLUMN + " BOOLEAN NOT NULL DEFAULT FALSE");
        }
      }
    }
  }

  private static boolean hasColumn(Connection connection, String table, String column)
      throws SQLException {
    try (ResultSet columns = connection.getMetaData().getColumns(null, "PUBLIC",
        table.toUpperCase(java.util.Locale.ROOT), column.toUpperCase(java.util.Locale.ROOT))) {
      return columns.next();
    }
  }

  /** read every definition back into memory; called at boot and after any change */
  public final void reload() throws SQLException {
    LinkedHashMap<String, UserTable> loaded = new LinkedHashMap<>();
    try (Connection connection = database.connection();
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery(
             "SELECT name, definition FROM " + CATALOGUE + " ORDER BY name")) {
      while (rows.next()) {
        UserTable table = UserTable.fromJson(rows.getString("definition"));
        if (table != null) {
          loaded.put(table.name(), table);
        }
      }
    }
    catalogue.keySet().retainAll(loaded.keySet());
    catalogue.putAll(loaded);
  }

  public File file() {
    return file;
  }

  public String domain() {
    return domain;
  }

  public List<UserTable> all() {
    ArrayList<UserTable> tables = new ArrayList<>(catalogue.values());
    tables.sort((left, right) -> left.name().compareTo(right.name()));
    return tables;
  }

  public UserTable byName(String name) {
    return name == null ? null : catalogue.get(UserTable.normalize(name));
  }

  public io.hearth.cache.TtlCache.Stats cacheStats() {
    return cache.stats();
  }

  // ---- the shape of a table --------------------------------------------------------------------

  /**
   * Create a table.
   *
   * The DDL is built from the validated definition and nothing else. Indexes are created in the
   * same transaction as the table, because a table that exists without the index its definition
   * promises is a table whose `_list_` function does a full scan while claiming not to.
   */
  public void create(UserTable table, Long actor) throws SQLException, Refused {
    refuseIfBroken(table);
    if (catalogue.containsKey(table.name())) {
      throw new Refused("there is already a table called '" + table.name() + "'");
    }
    // the identity column through the dialect rather than inline: H2 runs in MODE=STRICT, which
    // refuses its own AUTO_INCREMENT extension, and the difference between databases belongs in
    // one place -- the same rule the system schema follows
    StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(table.physical())
        .append(" (id ").append(database.dialect().identityColumn()).append(" PRIMARY KEY")
        .append(", ").append(HIDDEN_COLUMN).append(" BOOLEAN NOT NULL DEFAULT FALSE");
    for (UserField field : table.fields()) {
      ddl.append(", ").append(field.physical()).append(' ').append(field.type().sql);
      if (field.required()) {
        ddl.append(" NOT NULL DEFAULT ").append(defaultFor(field));
      }
    }
    ddl.append(')');
    try (Connection connection = database.connection();
         Statement statement = connection.createStatement()) {
      statement.execute(ddl.toString());
      for (String index : table.indexes()) {
        statement.execute(indexDdl(table, index));
      }
      save(connection, table);
    }
    reload();
    announce(table.name(), "*", MutationEvent.Kind.insert, actor);
  }

  /**
   * Change a table's shape.
   *
   * <b>Adds and drops, and says which it did.</b> A field that is gone from the new definition is
   * dropped along with its data -- there is no honest alternative once somebody has removed it from
   * the form, and pretending otherwise would leave a column no program can read filling the file.
   * A field that changes type is rejected rather than converted, because "what should the number 3
   * become when this becomes a flag" has no answer this software should be picking.
   */
  public List<String> alter(UserTable wanted, Long actor) throws SQLException, Refused {
    refuseIfBroken(wanted);
    UserTable existing = catalogue.get(wanted.name());
    if (existing == null) {
      throw new Refused("there is no table called '" + wanted.name() + "'");
    }
    for (UserField field : wanted.fields()) {
      UserField had = existing.field(field.name());
      if (had != null && had.type() != field.type()) {
        throw new Refused("'" + field.name() + "' is already " + had.type().label
            + "; changing the type of a field that has data in it is not something this can do."
            + " Add a new field instead.");
      }
    }
    ArrayList<String> done = new ArrayList<>();
    try (Connection connection = database.connection();
         Statement statement = connection.createStatement()) {
      for (UserField field : wanted.fields()) {
        if (existing.field(field.name()) == null) {
          statement.execute("ALTER TABLE " + wanted.physical() + " ADD COLUMN "
              + field.physical() + " " + field.type().sql);
          done.add("added " + field.name());
        }
      }
      for (UserField field : existing.fields()) {
        if (wanted.field(field.name()) == null) {
          statement.execute("ALTER TABLE " + wanted.physical() + " DROP COLUMN "
              + field.physical());
          done.add("dropped " + field.name() + " and everything in it");
        }
      }
      for (String index : existing.indexes()) {
        if (!wanted.hasIndex(index) || wanted.field(index) == null) {
          statement.execute("DROP INDEX IF EXISTS " + indexName(wanted, index));
          done.add("dropped the index on " + index);
        }
      }
      for (String index : wanted.indexes()) {
        if (!existing.hasIndex(index) || existing.field(index) == null) {
          statement.execute(indexDdl(wanted, index));
          done.add("indexed " + index);
        }
      }
      save(connection, wanted);
    }
    reload();
    cache.forgetTable(wanted.name());
    announce(wanted.name(), "*", MutationEvent.Kind.update, actor);
    return done;
  }

  /** drop a table and everything in it */
  public void drop(String name, Long actor) throws SQLException, Refused {
    UserTable table = byName(name);
    if (table == null) {
      throw new Refused("there is no table called '" + name + "'");
    }
    try (Connection connection = database.connection();
         Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + table.physical());
      try (PreparedStatement delete = connection.prepareStatement(
          "DELETE FROM " + CATALOGUE + " WHERE name = ?")) {
        delete.setString(1, table.name());
        delete.executeUpdate();
      }
    }
    catalogue.remove(table.name());
    cache.forgetTable(table.name());
    announce(table.name(), "*", MutationEvent.Kind.delete, actor);
  }

  private void save(Connection connection, UserTable table) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "MERGE INTO " + CATALOGUE + " (name, definition) VALUES (?, ?)")) {
      statement.setString(1, table.name());
      statement.setString(2, table.toJson());
      statement.executeUpdate();
    }
  }

  private static String indexName(UserTable table, String field) {
    return "ix_" + table.name() + "_" + field;
  }

  private static String indexDdl(UserTable table, String field) {
    return "CREATE INDEX " + indexName(table, field) + " ON " + table.physical()
        + " (" + table.field(field).physical() + ")";
  }

  private static String defaultFor(UserField field) {
    return switch (field.type()) {
      case text -> "''";
      case number, moment -> "0";
      case flag -> "FALSE";
    };
  }

  private void refuseIfBroken(UserTable table) throws Refused {
    List<String> problems = table.problems();
    if (!problems.isEmpty()) {
      throw new Refused(String.join("; ", problems));
    }
  }

  // ---- rows ------------------------------------------------------------------------------------

  /** one row a program may see, by id, or null */
  public Map<String, Object> getById(String tableName, long id) throws SQLException {
    return getById(tableName, id, See.published);
  }

  public Map<String, Object> getById(String tableName, long id, See see) throws SQLException {
    UserTable table = byName(tableName);
    if (table == null) {
      return null;
    }
    String key = TableCache.idKey(table.name(), see, id);
    List<Map<String, Object>> hit = cache.get(key);
    if (hit != null) {
      return hit.isEmpty() ? null : hit.get(0);
    }
    String filter = see.where(" AND ");
    List<Map<String, Object>> found = query(table, see,
        "SELECT * FROM " + table.physical() + " WHERE id = ?" + filter, statement ->
            statement.setLong(1, id), 1);
    cache.put(key, found);
    return found.isEmpty() ? null : found.get(0);
  }

  /** every row whose indexed field equals this value */
  public List<Map<String, Object>> listByIndex(String tableName, String index, Object value)
      throws SQLException {
    UserTable table = byName(tableName);
    if (table == null || !table.hasIndex(index)) {
      return List.of();
    }
    UserField field = table.field(index);
    String key = TableCache.indexKey(table.name(), See.published, index, value);
    List<Map<String, Object>> hit = cache.get(key);
    if (hit != null) {
      return hit;
    }
    List<Map<String, Object>> found = query(table, See.published,
        "SELECT * FROM " + table.physical() + " WHERE " + field.physical()
            + " = ?" + See.published.where(" AND ") + " ORDER BY id",
        statement -> bind(statement, 1, field, value), MAX_PAGE);
    cache.put(key, found);
    return found;
  }

  /**
   * A page of rows, after an id.
   *
   * Keyset paging rather than OFFSET, because OFFSET re-reads and re-discards every row before it
   * and shifts under an insert -- a reader on page four sees a row twice because somebody added one
   * to page one. `idAfter` is a place in the table rather than a count of pages.
   */
  public List<Map<String, Object>> page(String tableName, long idAfter, int count)
      throws SQLException {
    return page(tableName, idAfter, count, See.published);
  }

  public List<Map<String, Object>> page(String tableName, long idAfter, int count, See see)
      throws SQLException {
    UserTable table = byName(tableName);
    if (table == null) {
      return List.of();
    }
    int limit = Math.max(1, Math.min(MAX_PAGE, count));
    String key = TableCache.pageKey(table.name(), see, idAfter, limit);
    List<Map<String, Object>> hit = cache.get(key);
    if (hit != null) {
      return hit;
    }
    List<Map<String, Object>> found = query(table, see,
        "SELECT * FROM " + table.physical() + " WHERE id > ?" + see.where(" AND ")
            + " ORDER BY id " + database.dialect().limit(limit),
        statement -> statement.setLong(1, idAfter), limit);
    cache.put(key, found);
    return found;
  }

  /** the whole table, up to the ceiling a table is allowed to reach */
  public List<Map<String, Object>> all(String tableName) throws SQLException {
    return all(tableName, See.published);
  }

  public List<Map<String, Object>> all(String tableName, See see) throws SQLException {
    UserTable table = byName(tableName);
    if (table == null) {
      return List.of();
    }
    String key = TableCache.allKey(table.name(), see);
    List<Map<String, Object>> hit = cache.get(key);
    if (hit != null) {
      return hit;
    }
    List<Map<String, Object>> found = query(table, see,
        "SELECT * FROM " + table.physical()
            + (see == See.published ? " WHERE " + see.where("") : "")
            + " ORDER BY id " + database.dialect().limit(UserTable.MAX_ROWS),
        statement -> { }, UserTable.MAX_ROWS);
    cache.put(key, found);
    return found;
  }

  public long count(String tableName) throws SQLException {
    UserTable table = byName(tableName);
    if (table == null) {
      return 0;
    }
    try (Connection connection = database.connection();
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery(
             "SELECT COUNT(*) FROM " + table.physical())) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  /** insert a row and return its id */
  public long insert(String tableName, Map<String, Object> values, Long actor)
      throws SQLException, Refused {
    UserTable table = byName(tableName);
    if (table == null) {
      throw new Refused("there is no table called '" + tableName + "'");
    }
    if (count(table.name()) >= UserTable.MAX_ROWS) {
      throw new Refused("'" + table.name() + "' is full at " + UserTable.MAX_ROWS + " rows");
    }
    ArrayList<UserField> given = new ArrayList<>();
    for (UserField field : table.fields()) {
      if (values.containsKey(field.name())) {
        given.add(field);
      }
    }
    boolean setsHidden = values.containsKey(UserTable.HIDDEN);
    StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.physical()).append(" (");
    StringBuilder marks = new StringBuilder();
    for (int k = 0; k < given.size(); k++) {
      sql.append(k == 0 ? "" : ", ").append(given.get(k).physical());
      marks.append(k == 0 ? "" : ", ").append('?');
    }
    if (setsHidden) {
      sql.append(given.isEmpty() ? "" : ", ").append(HIDDEN_COLUMN);
      marks.append(given.isEmpty() ? "" : ", ").append('?');
    }
    sql.append(") VALUES (").append(given.isEmpty() && !setsHidden ? "" : marks).append(')');
    if (given.isEmpty() && !setsHidden) {
      sql = new StringBuilder("INSERT INTO " + table.physical() + " (id) VALUES (DEFAULT)");
    }
    long id;
    try (Connection connection = database.connection();
         PreparedStatement statement = connection.prepareStatement(sql.toString(),
             Statement.RETURN_GENERATED_KEYS)) {
      for (int k = 0; k < given.size(); k++) {
        bind(statement, k + 1, given.get(k), values.get(given.get(k).name()));
      }
      if (setsHidden) {
        statement.setBoolean(given.size() + 1, toBoolean(values.get(UserTable.HIDDEN)));
      }
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        id = keys.next() ? keys.getLong(1) : 0;
      }
    }
    announceRow(table, id, null, values, MutationEvent.Kind.insert, actor);
    return id;
  }

  /**
   * Update a row.
   *
   * The old row is read first, and not only to check it exists: the *before* value of every indexed
   * field is what tells the cache which index entry has stopped being true. Skipping that read
   * would leave the row cached under an index value it no longer has.
   */
  public boolean update(String tableName, long id, Map<String, Object> values, Long actor)
      throws SQLException, Refused {
    UserTable table = byName(tableName);
    if (table == null) {
      throw new Refused("there is no table called '" + tableName + "'");
    }
    // everything, not published: an update has to find a row an admin has hidden, or hiding one
    // would make it uneditable
    Map<String, Object> before = getById(table.name(), id, See.everything);
    if (before == null) {
      return false;
    }
    ArrayList<UserField> given = new ArrayList<>();
    for (UserField field : table.fields()) {
      if (values.containsKey(field.name())) {
        given.add(field);
      }
    }
    boolean setsHidden = values.containsKey(UserTable.HIDDEN);
    if (given.isEmpty() && !setsHidden) {
      return true;
    }
    StringBuilder sql = new StringBuilder("UPDATE ").append(table.physical()).append(" SET ");
    for (int k = 0; k < given.size(); k++) {
      sql.append(k == 0 ? "" : ", ").append(given.get(k).physical()).append(" = ?");
    }
    if (setsHidden) {
      sql.append(given.isEmpty() ? "" : ", ").append(HIDDEN_COLUMN).append(" = ?");
    }
    sql.append(" WHERE id = ?");
    try (Connection connection = database.connection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      int at = 1;
      for (UserField field : given) {
        bind(statement, at++, field, values.get(field.name()));
      }
      if (setsHidden) {
        statement.setBoolean(at++, toBoolean(values.get(UserTable.HIDDEN)));
      }
      statement.setLong(at, id);
      statement.executeUpdate();
    }
    announceRow(table, id, before, values, MutationEvent.Kind.update, actor);
    return true;
  }

  /**
   * Merge a partial row into an existing one, and say why not if it did not happen.
   *
   * <b>The same work as an update, reported differently.</b> It goes through {@link #update} rather
   * than beside it, so the events, the cache invalidation and the type coercion cannot drift from
   * the admin path -- two write paths that agree today is a bug scheduled for later.
   *
   * <b>A column merge: keys absent are keys untouched.</b> That is what makes it safe to call from a
   * form that shows three of a row's nine fields. The opposite -- treating the delta as the whole
   * row -- would blank the six nobody submitted while looking like it worked, which is the same
   * failure the content field values already refuse.
   *
   * <b>Every reason, not the first one.</b> A caller sending four fields with two wrong should be
   * told about both, because the alternative is finding them one save at a time. Nothing is written
   * at all unless every field is acceptable: a partial merge would leave a row half-updated with a
   * `success:false` beside it, which is the worst of both.
   *
   * <b>`hidden` is not mergeable.</b> A program must not be able to publish a row an admin held
   * back, and naming the field is how a caller finds that out rather than wondering why it had no
   * effect.
   */
  public Merged mergeById(String tableName, long id, Map<String, Object> delta, Long actor) {
    ArrayList<String> reasons = new ArrayList<>();
    UserTable table = byName(tableName);
    if (table == null) {
      return Merged.no("there is no table called '" + tableName + "'");
    }
    if (id <= 0) {
      return Merged.no("an id is needed to merge into a row");
    }
    if (delta == null || delta.isEmpty()) {
      return Merged.no("there is nothing to merge");
    }
    LinkedHashMap<String, Object> clean = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : delta.entrySet()) {
      String key = entry.getKey();
      if (UserTable.HIDDEN.equals(key)) {
        reasons.add("'" + UserTable.HIDDEN + "' can only be changed by a person in the admin"
            + " section");
        continue;
      }
      if ("id".equals(key)) {
        reasons.add("'id' is what says which row this is; it cannot be part of the change");
        continue;
      }
      UserField field = table.field(key);
      if (field == null) {
        reasons.add("'" + tableName + "' has no field called '" + key + "'");
        continue;
      }
      String wrong = checkValue(field, entry.getValue());
      if (wrong != null) {
        reasons.add(wrong);
        continue;
      }
      clean.put(key, entry.getValue());
    }
    if (!reasons.isEmpty()) {
      return new Merged(false, reasons);
    }
    try {
      if (!update(tableName, id, clean, actor)) {
        return Merged.no("there is no row " + id + " in '" + tableName + "'");
      }
    } catch (SQLException | Refused ex) {
      LOG.error("user-table-merge-failed", ex);
      return Merged.no(String.valueOf(ex.getMessage()));
    }
    return new Merged(true, List.of());
  }

  /**
   * Is this value something the field can hold?
   *
   * Checked before anything is written rather than relying on the coercion in {@code bind}, which
   * turns anything unparseable into 0 -- silently storing zero for "banana" is a plausible wrong
   * answer, and this is the one write path where the caller is a program that can be told.
   */
  private static String checkValue(UserField field, Object value) {
    if (value == null) {
      return null;
    }
    return switch (field.type()) {
      case text -> value instanceof Map || value instanceof List
          ? "'" + field.name() + "' is text, and that is not a string"
          : null;
      case number, moment -> {
        if (value instanceof Number) {
          yield null;
        }
        try {
          Double.parseDouble(String.valueOf(value).trim());
          yield null;
        } catch (NumberFormatException ex) {
          yield "'" + field.name() + "' is a number, and '" + value + "' is not one";
        }
      }
      case flag -> {
        if (value instanceof Boolean) {
          yield null;
        }
        String text = String.valueOf(value).trim();
        yield text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")
            ? null
            : "'" + field.name() + "' is true or false, and '" + value + "' is neither";
      }
    };
  }

  /** what a merge did, in the shape a program reads it back in */
  public record Merged(boolean success, List<String> reasons) {
    static Merged no(String reason) {
      return new Merged(false, List.of(reason));
    }
  }

  public boolean delete(String tableName, long id, Long actor) throws SQLException, Refused {
    UserTable table = byName(tableName);
    if (table == null) {
      throw new Refused("there is no table called '" + tableName + "'");
    }
    Map<String, Object> before = getById(table.name(), id, See.everything);
    if (before == null) {
      return false;
    }
    try (Connection connection = database.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + table.physical() + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    announceRow(table, id, before, Map.of(), MutationEvent.Kind.delete, actor);
    return true;
  }

  // ---- events ----------------------------------------------------------------------------------

  /**
   * Say what changed, in the terms the cache is keyed by.
   *
   * One event for the row, and one for each *side* of every indexed field that moved. A page
   * subscribing to "everybody in group 4" has to be told both when somebody joins group 4 and when
   * somebody leaves it, and those are two different index values -- so a single event naming only
   * the new one is exactly half an answer.
   */
  private void announceRow(UserTable table, long id, Map<String, Object> before,
                           Map<String, Object> after, MutationEvent.Kind kind, Long actor) {
    announce(table.name(), String.valueOf(id), kind, actor);
    for (String index : table.indexes()) {
      Object was = before == null ? null : before.get(index);
      Object now = after.containsKey(index) ? after.get(index) : (kind ==
          MutationEvent.Kind.delete ? null : was);
      if (kind == MutationEvent.Kind.delete) {
        now = null;
      }
      if (!sameValue(was, now)) {
        if (was != null) {
          announce(table.name(), index + "=" + was, kind, actor);
        }
        if (now != null) {
          announce(table.name(), index + "=" + now, kind, actor);
        }
      }
    }
  }

  private static boolean sameValue(Object left, Object right) {
    if (left == null || right == null) {
      return left == right;
    }
    return String.valueOf(left).equals(String.valueOf(right));
  }

  private void announce(String table, String key, MutationEvent.Kind kind, Long actor) {
    events.emit(new MutationEvent(0, System.currentTimeMillis(), domain,
        EVENT_TABLE_PREFIX + table, key, kind, actor));
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private List<Map<String, Object>> query(UserTable table, See see, String sql, Binder binder,
                                          int limit) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = database.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet found = statement.executeQuery()) {
        while (found.next() && rows.size() < limit) {
          rows.add(read(table, found, see));
        }
      }
    }
    return rows;
  }

  /**
   * One row.
   *
   * A published read does not carry the hidden flag at all, rather than carrying `false`. Absent is
   * stronger than false: there is nothing for a page to test, so no page can be written that
   * behaves differently for a row somebody might later hide.
   */
  private static Map<String, Object> read(UserTable table, ResultSet found, See see)
      throws SQLException {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", found.getLong("id"));
    if (see == See.everything) {
      row.put(UserTable.HIDDEN, found.getBoolean(HIDDEN_COLUMN));
    }
    for (UserField field : table.fields()) {
      Object value = switch (field.type()) {
        case text -> found.getString(field.physical());
        case number -> found.getDouble(field.physical());
        case flag -> found.getBoolean(field.physical());
        case moment -> found.getLong(field.physical());
      };
      row.put(field.name(), found.wasNull() ? null : value);
    }
    return row;
  }

  private static void bind(PreparedStatement statement, int at, UserField field, Object value)
      throws SQLException {
    if (value == null) {
      statement.setNull(at, switch (field.type()) {
        case text -> java.sql.Types.VARCHAR;
        case number -> java.sql.Types.DOUBLE;
        case flag -> java.sql.Types.BOOLEAN;
        case moment -> java.sql.Types.BIGINT;
      });
      return;
    }
    switch (field.type()) {
      case text -> statement.setString(at, String.valueOf(value));
      case number -> statement.setDouble(at, toDouble(value));
      case flag -> statement.setBoolean(at, toBoolean(value));
      case moment -> statement.setLong(at, (long) toDouble(value));
    }
  }

  private static double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean flag) {
      return flag;
    }
    String text = String.valueOf(value).trim();
    return text.equalsIgnoreCase("true") || text.equals("1") || text.equalsIgnoreCase("on")
        || text.equalsIgnoreCase("yes");
  }

  /** rows as JSON, which is what the JavaScript boundary actually wants */
  public static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception ex) {
      LOG.error("user-table-json-failed", ex);
      return "null";
    }
  }

  @Override
  public void close() {
    database.close();
  }

  /** a refusal a person or a program should be told about in words */
  public static class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }
}
