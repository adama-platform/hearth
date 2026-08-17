package io.hearth.theme;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * The palettes for one community, kept in memory because every page render asks for one.
 *
 * A colour is on the critical path of every single response, so this is a read-through cache with a
 * map of two entries in front of it, loaded at boot and replaced on save. A query per page load to
 * fetch six hex strings would be the most-executed statement in the server and would buy nothing --
 * this is the one table where "it never changes and everything reads it" is simply true.
 *
 * There is no event-bus invalidation and no TTL, and that is deliberate rather than an oversight: a
 * theme changes from exactly one place, the save below, which replaces the cached value in the same
 * breath. The cascade machinery exists for values written by one component and cached by another,
 * which is not this.
 *
 * A scope with no row is the default, and no row is written until somebody changes something. That
 * keeps "has anybody actually chosen colours here" answerable, which the admin screen says out loud
 * -- a table seeded with the defaults at boot would make every community look like it had.
 */
public class Themes {
  private final Store store;
  private final Map<Theme.Scope, Theme> cached = new EnumMap<>(Theme.Scope.class);

  public Themes(Store store) {
    this.store = store;
  }

  /** read both scopes once, before the socket opens */
  public void load() throws SQLException {
    EnumMap<Theme.Scope, Theme> loaded = new EnumMap<>(Theme.Scope.class);
    for (Theme.Scope scope : Theme.Scope.values()) {
      loaded.put(scope, read(scope));
    }
    synchronized (cached) {
      cached.clear();
      cached.putAll(loaded);
    }
  }

  /** never null, never blocking, never wrong for longer than one save takes */
  public Theme of(Theme.Scope scope) {
    synchronized (cached) {
      Theme theme = cached.get(scope);
      if (theme != null) {
        return theme;
      }
    }
    // a store that has not been loaded yet, which happens in a test that builds a DAO by hand.
    // Reading through rather than refusing means nothing has to remember to call load().
    try {
      Theme theme = read(scope);
      synchronized (cached) {
        cached.put(scope, theme);
      }
      return theme;
    } catch (SQLException ex) {
      return Theme.defaultFor(scope);
    }
  }

  /** the CSS for a scope; what every layout interpolates */
  public String css(Theme.Scope scope) {
    return of(scope).css();
  }

  public void save(Theme theme, Long actor) throws SQLException {
    String json = theme.toJson();
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.THEMES + " SET colors = ?, updated_at = CURRENT_TIMESTAMP"
                 + " WHERE scope = ?")) {
      statement.setString(1, json);
      statement.setString(2, theme.scope.name());
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.THEMES + " (scope, colors) VALUES (?, ?)")) {
        statement.setString(1, theme.scope.name());
        statement.setString(2, json);
        statement.executeUpdate();
      }
    }
    synchronized (cached) {
      cached.put(theme.scope, theme);
    }
    store.changed(Schema.THEMES, theme.scope.ordinal(), MutationEvent.Kind.update, actor);
  }

  /** put a scope back to what it shipped with, by deleting the row rather than writing defaults */
  public void reset(Theme.Scope scope, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.THEMES + " WHERE scope = ?")) {
      statement.setString(1, scope.name());
      statement.executeUpdate();
    }
    synchronized (cached) {
      cached.put(scope, Theme.defaultFor(scope));
    }
    store.changed(Schema.THEMES, scope.ordinal(), MutationEvent.Kind.delete, actor);
  }

  private Theme read(Theme.Scope scope) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT colors FROM " + Schema.THEMES + " WHERE scope = ?")) {
      statement.setString(1, scope.name());
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          return Theme.fromJson(scope, rows.getString(1));
        }
      }
    }
    return Theme.defaultFor(scope);
  }
}
