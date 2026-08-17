package io.hearth.store;

import io.hearth.common.Verbose;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brings a database on disk into line with {@link Schema}, by looking at what is actually there.
 *
 * There is no migration list and no sequence of numbered scripts. The upgrader reads
 * INFORMATION_SCHEMA, compares it to the tables declared in code, and does the smallest thing that
 * closes the gap. That is the right trade for a project still deciding what its tables are: adding
 * a field to Schema.java and restarting is the entire workflow.
 *
 * What it will do:
 *   - create a table that doesn't exist
 *   - add a column that doesn't exist, IN THE DECLARED POSITION via H2's ALTER TABLE ... BEFORE,
 *     so an upgraded database ends up shaped like a freshly created one
 *   - create a missing index
 *
 * What it will not do:
 *   - drop or rename anything. A column the code no longer declares is reported and left alone;
 *     data loss is not something a startup path should decide on its own.
 *   - change a column's type. That is reported as fatal, because running against a column that
 *     isn't the type the code thinks it is fails later, further away, and harder to diagnose.
 */
public class SchemaUpgrader {
  private final Verbose verbose;
  private final List<String> applied = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();

  public SchemaUpgrader(Verbose verbose) {
    this.verbose = verbose;
  }

  /** the DDL actually run, in order; empty when the database was already correct */
  public List<String> applied() {
    return applied;
  }

  /** things worth telling the operator that were not fatal */
  public List<String> notes() {
    return notes;
  }

  public void upgrade(Connection connection, String label) throws SchemaException {
    try {
      for (Table table : Schema.TABLES) {
        Map<String, Live> live = readColumns(connection, table.name);
        if (live.isEmpty()) {
          create(connection, table, label);
        } else {
          rename(connection, table, live, label);
          reconcile(connection, table, live, label);
        }
        createIndexes(connection, table);
      }
      recordVersion(connection);
    } catch (SQLException ex) {
      throw new SchemaException(label + ": schema upgrade failed: " + ex.getMessage(), ex);
    }
  }

  private void create(Connection connection, Table table, String label) throws SQLException {
    String ddl = table.createDdl();
    try (Statement statement = connection.createStatement()) {
      statement.execute(ddl);
    }
    applied.add(ddl);
    verbose.detail(label + ": created table " + table.name + " (" + table.columns.size() + " columns)");
  }

  /**
   * Apply the renames the code declares, before anything else looks at what is missing.
   *
   * Before, because a renamed column looks exactly like a missing one to {@link #reconcile}: it
   * would add an empty column under the new name and leave the values sitting in the old one, which
   * is a silent and total data loss for that field. Doing it first means reconcile sees a database
   * that already agrees with the code about what things are called.
   *
   * Both names present is left alone and reported rather than guessed at -- that is somebody's
   * half-finished surgery, and picking one to keep is not a decision a boot path should make.
   */
  private void rename(Connection connection, Table table, Map<String, Live> live, String label)
      throws SQLException {
    for (Column column : table.columns) {
      if (column.was == null) {
        continue;
      }
      String from = column.was.toUpperCase(Locale.ROOT);
      String to = column.name.toUpperCase(Locale.ROOT);
      if (!live.containsKey(from)) {
        continue;
      }
      if (live.containsKey(to)) {
        notes.add(table.name + " has both " + column.was + " and " + column.name
            + "; leaving them alone. One of them is a rename that did not finish.");
        continue;
      }
      String ddl = "ALTER TABLE " + table.name + " ALTER COLUMN " + column.was
          + " RENAME TO " + column.name;
      try (Statement statement = connection.createStatement()) {
        statement.execute(ddl);
      }
      applied.add(ddl);
      Live was = live.remove(from);
      live.put(to, new Live(column.name, was.type(), was.ordinal()));
      verbose.detail(label + ": renamed " + table.name + "." + column.was + " to " + column.name);
    }
  }

  /**
   * Add whatever the code declares and the database lacks, each in its declared position.
   *
   * The position is expressed as "BEFORE the next column that already exists", walking the declared
   * order. That handles a run of several new columns in the middle of a table correctly, because
   * each one is placed relative to a column that is definitely already there.
   */
  private void reconcile(Connection connection, Table table, Map<String, Live> live, String label) throws SQLException, SchemaException {
    List<Column> declared = table.columns;
    for (int k = 0; k < declared.size(); k++) {
      Column column = declared.get(k);
      String key = column.name.toUpperCase(Locale.ROOT);
      Live existing = live.get(key);
      if (existing != null) {
        if (!existing.type.equals(column.normalizedType())) {
          throw new SchemaException(label + ": " + table.name + "." + column.name + " is " + existing.type
              + " on disk but " + column.normalizedType() + " in code; this server will not change a column's"
              + " type on its own. Fix the schema or move the database aside.");
        }
        continue;
      }
      String anchor = nextExisting(declared, k, live);
      String ddl = "ALTER TABLE " + table.name + " ADD COLUMN " + column.alterDdl()
          + (anchor == null ? "" : " BEFORE " + anchor);
      try (Statement statement = connection.createStatement()) {
        statement.execute(ddl);
      }
      applied.add(ddl);
      // record it as present so a following new column anchors against it correctly
      live.put(key, new Live(column.name, column.normalizedType(), live.size() + 1));
      verbose.detail(label + ": added " + table.name + "." + column.name
          + (anchor == null ? " (at the end)" : " before " + anchor));
      if (column.unique) {
        String constraint = "ALTER TABLE " + table.name + " ADD CONSTRAINT IF NOT EXISTS uq_"
            + table.name + "_" + column.name + " UNIQUE (" + column.name + ")";
        try (Statement statement = connection.createStatement()) {
          statement.execute(constraint);
        }
        applied.add(constraint);
      }
    }
    Map<String, Column> declaredByName = table.byName();
    for (String present : live.keySet()) {
      if (!declaredByName.containsKey(present)) {
        notes.add(table.name + "." + present.toLowerCase(Locale.ROOT)
            + " exists on disk but not in the code schema; left alone");
      }
    }
  }

  /** the first column after position k that the database already has, or null if there is none */
  private String nextExisting(List<Column> declared, int k, Map<String, Live> live) {
    for (int j = k + 1; j < declared.size(); j++) {
      String candidate = declared.get(j).name.toUpperCase(Locale.ROOT);
      if (live.containsKey(candidate)) {
        return declared.get(j).name;
      }
    }
    return null;
  }

  private void createIndexes(Connection connection, Table table) throws SQLException {
    for (Table.Index index : table.indexes) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(index.createDdl(table.name));
      }
    }
    for (Table.Unique unique : table.uniques) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(unique.alterDdl(table.name));
      }
    }
  }

  private void recordVersion(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "MERGE INTO " + Schema.META + " (meta_key, meta_value, updated_at) KEY (meta_key)"
            + " VALUES (?, ?, CURRENT_TIMESTAMP)")) {
      statement.setString(1, "schema_version");
      statement.setString(2, Integer.toString(Schema.VERSION));
      statement.executeUpdate();
    }
  }

  /** the version this database was last brought up to, or null if it has never been written */
  public static String storedVersion(Connection connection) {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT meta_value FROM " + Schema.META + " WHERE meta_key = ?")) {
      statement.setString(1, "schema_version");
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    } catch (SQLException ex) {
      return null; // the meta table itself is about to be created
    }
  }

  /** the schema our tables live in; anything else is H2's own bookkeeping */
  static final String SCHEMA = "PUBLIC";

  /**
   * The columns of one table as the database currently has them, in ordinal order.
   *
   * Scoped to PUBLIC deliberately. H2 ships an INFORMATION_SCHEMA.SESSIONS of its own, so an
   * unscoped lookup reports our sessions table as already existing on a brand new database and the
   * upgrader tries to ALTER a table that was never created.
   */
  static Map<String, Live> readColumns(Connection connection, String table) throws SQLException {
    LinkedHashMap<String, Live> columns = new LinkedHashMap<>();
    DatabaseMetaData meta = connection.getMetaData();
    try (ResultSet rows = meta.getColumns(null, SCHEMA, table.toUpperCase(Locale.ROOT), null)) {
      while (rows.next()) {
        String name = rows.getString("COLUMN_NAME");
        String type = rows.getString("TYPE_NAME");
        int size = rows.getInt("COLUMN_SIZE");
        int ordinal = rows.getInt("ORDINAL_POSITION");
        columns.put(name.toUpperCase(Locale.ROOT), new Live(name, normalize(type, size), ordinal));
      }
    }
    return columns;
  }

  private static String normalize(String type, int size) {
    String upper = type.toUpperCase(Locale.ROOT);
    if (upper.startsWith("VARCHAR") || upper.startsWith("CHARACTER VARYING")) {
      return "VARCHAR(" + size + ")";
    }
    return Column.normalizeType(upper);
  }

  /** a column as it exists on disk right now */
  record Live(String name, String type, int ordinal) {
  }
}
