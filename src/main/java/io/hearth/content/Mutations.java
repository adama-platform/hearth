package io.hearth.content;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Addresses that accept a POST and run a program, and nothing else in this server does.
 *
 * <b>A page renders; a mutation changes something.</b> Splitting them by HTTP method rather than by
 * a flag on a page is what keeps the rule sayable: a GET never writes, so a crawler, a preloader,
 * or somebody's link checker cannot change a row by looking at the site. That is also why a page's
 * table functions are read-only -- the ability to write lives at a different address, behind a
 * different method, with a CSRF token on it.
 *
 * <b>Off by default, and that is the useful half of the switch.</b> A mutation somebody is halfway
 * through writing should not be answering POSTs, and deleting it to stop it would lose the draft.
 *
 * <b>The uri is validated against the same rules a page's is</b>, and checked against the content
 * table -- an address that is both a page and a mutation is one somebody will eventually reach with
 * the wrong method and get a confusing answer from.
 */
public class Mutations {
  private final Store store;

  public Mutations(Store store) {
    this.store = store;
  }

  public record Record(long id, String uri, String body, boolean enabled,
                       Timestamp updatedAt, Long updatedBy) {
  }

  public List<Record> all() throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MUTATIONS + " ORDER BY uri");
         ResultSet found = statement.executeQuery()) {
      while (found.next()) {
        rows.add(read(found));
      }
    }
    return rows;
  }

  public Record byUri(String uri) throws SQLException {
    if (uri == null || uri.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MUTATIONS + " WHERE uri = ?")) {
      statement.setString(1, uri.trim());
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  public Record byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MUTATIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  /** create or replace one, by uri; every write announces itself, from here rather than a handler */
  public long save(long id, String uri, String body, boolean enabled, Long actor)
      throws SQLException {
    long saved;
    try (Connection connection = store.connection()) {
      if (id > 0) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + Schema.MUTATIONS
                + " SET uri = ?, body = ?, enabled = ?, updated_at = ?, updated_by = ?"
                + " WHERE id = ?")) {
          statement.setString(1, uri);
          statement.setString(2, body);
          statement.setBoolean(3, enabled);
          statement.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
          setActor(statement, 5, actor);
          statement.setLong(6, id);
          statement.executeUpdate();
        }
        saved = id;
      } else {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + Schema.MUTATIONS + " (uri, body, enabled, updated_by)"
                + " VALUES (?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS)) {
          statement.setString(1, uri);
          statement.setString(2, body);
          statement.setBoolean(3, enabled);
          setActor(statement, 4, actor);
          statement.executeUpdate();
          try (ResultSet keys = statement.getGeneratedKeys()) {
            saved = keys.next() ? keys.getLong(1) : 0;
          }
        }
      }
    }
    store.changed(Schema.MUTATIONS, saved,
        id > 0 ? MutationEvent.Kind.update : MutationEvent.Kind.insert, actor);
    return saved;
  }

  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.MUTATIONS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.MUTATIONS, id, MutationEvent.Kind.delete, actor);
  }

  private static void setActor(PreparedStatement statement, int at, Long actor)
      throws SQLException {
    if (actor == null) {
      statement.setNull(at, java.sql.Types.BIGINT);
    } else {
      statement.setLong(at, actor);
    }
  }

  private static Record read(ResultSet found) throws SQLException {
    long by = found.getLong("updated_by");
    return new Record(found.getLong("id"), found.getString("uri"), found.getString("body"),
        found.getBoolean("enabled"), found.getTimestamp("updated_at"),
        found.wasNull() ? null : by);
  }

  /** the same shape a page's address has to have, said once */
  public static String checkUri(String uri) {
    if (uri == null || uri.isBlank()) {
      return "a mutation needs an address";
    }
    String clean = uri.trim();
    if (!clean.startsWith("/") || clean.length() > 512) {
      return "an address is an absolute path like /sign-up, at most 512 characters";
    }
    if (clean.contains("?") || clean.contains("#") || clean.contains(" ")) {
      return "an address is a path: no query string, no fragment, no spaces";
    }
    return null;
  }

  public static String normalize(String uri) {
    return uri == null ? "" : uri.trim().toLowerCase(Locale.ROOT);
  }
}
