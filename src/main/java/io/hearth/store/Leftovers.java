package io.hearth.store;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tables this software no longer declares, and the one place that will drop them.
 *
 * <b>Why they exist at all.</b> {@link SchemaUpgrader} adds and never drops -- a table the code
 * stopped declaring is reported and left alone, which is exactly what made removing a third of this
 * project safe for a database that already had data in it. The cost of that promise is that an
 * upgraded database still carries the discussion board, the calendar, the address book and
 * everything else, holding rows nothing will ever read again.
 *
 * <b>So dropping is a person's decision, never the upgrader's.</b> Boot is not allowed to destroy
 * anything: an operator who upgrades, finds a regression and rolls the jar back must still have
 * their data. This class is reached only from the admin section, by somebody holding every
 * permission there is, pressing a button that names the table and says how many rows are in it.
 * That is the whole design -- the same reasoning as invariant 151, with the human put back in.
 *
 * <b>Nothing is hard-coded.</b> There is no list of old feature names here, because a list would be
 * wrong the next time something is removed. A leftover is any table in the database that
 * {@link Schema#TABLES} does not name, which makes this correct for removals that have not happened
 * yet and honest about ones somebody made by hand.
 */
public final class Leftovers {
  private Leftovers() {
  }

  /**
   * One table the code has stopped declaring.
   *
   * The row count is the number that decides whether anybody cares. A leftover holding zero rows is
   * a feature nobody in this community ever used; one holding four thousand is somebody's
   * discussion board, and they should think before pressing the button.
   */
  public record Table(String name, long rows) {
    public boolean empty() {
      return rows == 0;
    }
  }

  /**
   * Every table in the database that the code no longer declares.
   *
   * Scoped to PUBLIC for the reason {@link SchemaUpgrader} gives: H2 keeps tables of its own in
   * INFORMATION_SCHEMA, and an unscoped read would offer to drop the database's own furniture.
   */
  public static List<Table> find(Connection connection) throws SQLException {
    Set<String> declared = new LinkedHashSet<>();
    for (io.hearth.store.Table table : Schema.TABLES) {
      declared.add(table.name.toUpperCase(Locale.ROOT));
    }
    ArrayList<Table> leftovers = new ArrayList<>();
    DatabaseMetaData meta = connection.getMetaData();
    ArrayList<String> names = new ArrayList<>();
    try (ResultSet rows = meta.getTables(null, SchemaUpgrader.SCHEMA, "%",
        new String[] {"TABLE"})) {
      while (rows.next()) {
        String name = rows.getString("TABLE_NAME");
        if (name != null && !declared.contains(name.toUpperCase(Locale.ROOT))) {
          names.add(name);
        }
      }
    }
    names.sort(String::compareToIgnoreCase);
    for (String name : names) {
      leftovers.add(new Table(name, count(connection, name)));
    }
    return leftovers;
  }

  private static long count(Connection connection, String name) {
    // the name came out of INFORMATION_SCHEMA rather than off a request, and it is quoted anyway
    try (Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM \"" + name + "\"")) {
      return rows.next() ? rows.getLong(1) : 0;
    } catch (SQLException ex) {
      // a table that cannot be counted is still a table, and "unknown" is better than hiding it
      return -1;
    }
  }

  /**
   * Drop one leftover, by name, after checking it is still one.
   *
   * <b>The check is the point.</b> The name arrives from a form, and a form is untrusted no matter
   * which screen it came from -- so this re-derives the leftover list and refuses anything not on
   * it. Without that, the field is an arbitrary DROP TABLE and the most powerful button in the
   * admin section becomes the only injection in the program. A table the code declares can never
   * be dropped here however the request is shaped.
   */
  public static void drop(Connection connection, String name) throws SQLException {
    if (name == null || name.isBlank()) {
      throw new SQLException("no table named");
    }
    boolean known = false;
    for (Table table : find(connection)) {
      if (table.name().equalsIgnoreCase(name)) {
        known = true;
        name = table.name();   // the database's own spelling, never the form's
        break;
      }
    }
    if (!known) {
      throw new SQLException("'" + name + "' is not a table this server has stopped using");
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE \"" + name + "\"");
    }
  }
}
