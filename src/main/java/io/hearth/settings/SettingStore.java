package io.hearth.settings;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What this community has actually decided, kept in memory because the config is read constantly.
 *
 * Every request that resolves a host ends up holding a {@code DomainConfig}, and a database round
 * trip to answer "what is this community called" would be the most-executed statement in the
 * server. So this is the same shape {@link io.hearth.theme.Themes} uses and for the same reason:
 * loaded once before the socket opens, replaced in the same breath as the write.
 *
 * <b>A row exists only where somebody decided something.</b> Absent means the config file's value,
 * or the built-in default when the file is quiet too -- which is what makes an upgrade safe for
 * every community that already has these keys in a file, and what makes "has anybody actually
 * chosen this" a question the editor can answer honestly rather than showing thirty rows of
 * defaults somebody would assume were deliberate.
 *
 * Writes go through {@link #set}, which hands the whole set to a rebuild before committing: an
 * invalid value is refused by the parser that decides whether the server boots, not by a second
 * one written for this screen.
 */
public class SettingStore {
  private final Store store;
  private final Map<String, String> cached = new TreeMap<>();
  private volatile boolean loaded;
  /**
   * What to do once a value has landed: rebuild the config and swap it in.
   *
   * A hook rather than a call from the admin handler, for the same reason a mutation event is
   * emitted by the DAO rather than by whoever called it -- a caller can forget, and a write that
   * lands without the rebuild is a screen that says "saved" over a community still running on the
   * old answer. Everything that writes here goes through {@link #set} or {@link #clear}.
   */
  private volatile Runnable afterWrite = () -> { };

  public void onChange(Runnable afterWrite) {
    this.afterWrite = afterWrite == null ? () -> { } : afterWrite;
  }

  public SettingStore(Store store) {
    this.store = store;
  }

  /** read every row once, before anything asks */
  public void load() throws SQLException {
    TreeMap<String, String> rows = new TreeMap<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT name, value_text FROM " + Schema.CONFIG);
         ResultSet results = statement.executeQuery()) {
      while (results.next()) {
        rows.put(results.getString(1), results.getString(2));
      }
    }
    synchronized (cached) {
      cached.clear();
      cached.putAll(rows);
    }
    loaded = true;
  }

  /** everything decided here, as a copy nothing else can hold a reference into */
  public Map<String, String> overrides() {
    if (!loaded) {
      try {
        load();
      } catch (SQLException ex) {
        // a store nobody loaded, which happens in a test that builds a DAO by hand. An empty
        // answer is the file's configuration, which is exactly right.
        return Map.of();
      }
    }
    synchronized (cached) {
      return new LinkedHashMap<>(cached);
    }
  }

  /** what somebody decided for one key, or null where nobody has */
  public String get(String key) {
    return overrides().get(key);
  }

  public boolean isSetupComplete() {
    return "true".equals(get(Settings.SETUP_DONE));
  }

  /**
   * Write one setting.
   *
   * A blank value deletes the row rather than storing an empty string, which is what makes the
   * editor's "clear this" put the file's value -- or the built-in -- back. Storing "" would instead
   * mean a community that emptied a box could never get the default again without somebody opening
   * the database.
   */
  public void set(String key, String value, Long actor) throws SQLException {
    if (!Settings.isKnown(key)) {
      throw new IllegalArgumentException("no such setting: " + key);
    }
    if (value == null || value.isBlank()) {
      clear(key, actor);
      return;
    }
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.CONFIG + " SET value_text = ?, updated_at = CURRENT_TIMESTAMP,"
                 + " updated_by = ? WHERE name = ?")) {
      statement.setString(1, value);
      if (actor == null) {
        statement.setNull(2, java.sql.Types.BIGINT);
      } else {
        statement.setLong(2, actor);
      }
      statement.setString(3, key);
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.CONFIG + " (name, value_text, updated_by) VALUES (?, ?, ?)")) {
        statement.setString(1, key);
        statement.setString(2, value);
        if (actor == null) {
          statement.setNull(3, java.sql.Types.BIGINT);
        } else {
          statement.setLong(3, actor);
        }
        statement.executeUpdate();
      }
    }
    synchronized (cached) {
      cached.put(key, value);
    }
    store.changed(Schema.CONFIG, key, MutationEvent.Kind.update, actor);
    afterWrite.run();
  }

  /** put a key back to whatever the file says, by removing the row rather than writing a default */
  public void clear(String key, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.CONFIG + " WHERE name = ?")) {
      statement.setString(1, key);
      statement.executeUpdate();
    }
    synchronized (cached) {
      cached.remove(key);
    }
    store.changed(Schema.CONFIG, key, MutationEvent.Kind.delete, actor);
    afterWrite.run();
  }
}
