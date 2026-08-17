package io.hearth.store;

import io.hearth.common.Verbose;
import io.hearth.events.EventBus;
import io.hearth.events.MutationEvent;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * One H2 database, on disk, under the stores path. Opened at boot and held open for the life of
 * the process.
 *
 * The audit is the point of the boot-time open. By the time the socket is listening, every
 * database has been opened, its actual tables read back, compared against the code, upgraded if
 * needed, and reported. An operator running with --verbose sees exactly what happened to their
 * data before any request touches it.
 */
public class Store implements AutoCloseable {
  /** the domain whose database this is; several domains may point at it */
  public final String databaseDomain;
  public final File file;
  private final Database database;
  private final EventBus events;
  private final Audit audit;

  private Store(String databaseDomain, File file, Database database, EventBus events, Audit audit) {
    this.databaseDomain = databaseDomain;
    this.file = file;
    this.database = database;
    this.events = events;
    this.audit = audit;
  }

  /**
   * Open (creating if absent) the database for a domain and bring its schema up to date.
   *
   * AUTO_SERVER is off and the URL is file-local: this is an embedded database owned by exactly
   * one process, which is the whole operational premise of a single jar.
   */
  public static Store open(File storesRoot, String databaseDomain, Verbose verbose) throws SchemaException {
    return open(storesRoot, databaseDomain, EventBus.NONE, verbose);
  }

  public static Store open(File storesRoot, String databaseDomain, EventBus events, Verbose verbose) throws SchemaException {
    File file = new File(storesRoot, databaseDomain);
    boolean existed = new File(storesRoot, databaseDomain + ".mv.db").exists();
    verbose.say((existed ? "opening" : "creating") + " database for " + databaseDomain + " at " + file + ".mv.db");
    Database database = new H2Database(file, "store-" + databaseDomain);
    try (Connection connection = database.connection()) {
      String priorVersion = SchemaUpgrader.storedVersion(connection);
      SchemaUpgrader upgrader = new SchemaUpgrader(verbose);
      upgrader.upgrade(connection, databaseDomain);
      Audit audit = new Audit(databaseDomain, file, existed, priorVersion, Schema.VERSION,
          upgrader.applied(), upgrader.notes());
      audit.narrate(verbose);
      return new Store(databaseDomain, file, database, events, audit);
    } catch (SQLException ex) {
      database.close();
      throw new SchemaException("could not open the database for " + databaseDomain + ": " + ex.getMessage(), ex);
    } catch (SchemaException ex) {
      database.close();
      throw ex;
    }
  }

  public Connection connection() throws SQLException {
    return database.connection();
  }

  public Dialect dialect() {
    return database.dialect();
  }

  /**
   * Announce that a row changed.
   *
   * Every DAO calls this after a successful write rather than emitting from the handler, so that a
   * caller cannot forget and so that the event is tied to the write actually landing.
   */
  public MutationEvent changed(String table, Object key, MutationEvent.Kind kind, Long actor) {
    return events.emit(databaseDomain, table, String.valueOf(key), kind, actor);
  }

  public EventBus events() {
    return events;
  }

  public Audit audit() {
    return audit;
  }

  @Override
  public void close() {
    database.close();
  }

  /** what opening a database found and did, kept so the boot report can be printed after the fact */
  public record Audit(String databaseDomain, File file, boolean existed, String priorVersion, int version,
                      List<String> applied, List<String> notes) {
    public boolean changed() {
      return !applied.isEmpty();
    }

    public String summary() {
      if (!existed) {
        return "created, schema v" + version;
      }
      if (applied.isEmpty()) {
        return "opened, schema v" + version + ", already current";
      }
      return "opened, schema v" + (priorVersion == null ? "?" : priorVersion) + " -> v" + version
          + ", " + applied.size() + " change(s)";
    }

    public void narrate(Verbose verbose) {
      if (!verbose.on) {
        return;
      }
      verbose.detail(databaseDomain + ": " + summary());
      for (String ddl : applied) {
        verbose.detail("  " + ddl);
      }
      for (String note : notes) {
        verbose.detail("  note: " + note);
      }
    }
  }
}
