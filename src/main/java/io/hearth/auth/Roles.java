package io.hearth.auth;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The roles table: who is allowed to do what.
 *
 * One row per grant rather than a column on the account, because a person can hold several roles and
 * because "who granted this, and when" is the first question asked when something goes wrong.
 *
 * {@code admin} is the only role the server itself understands. It grants everything, including
 * approving other people. Anything else stored here is a label waiting for a feature to care about
 * it, which is deliberate -- adding a role should not require a schema change.
 */
public class Roles {
  public static final String ADMIN = "admin";

  private final Store store;

  public Roles(Store store) {
    this.store = store;
  }

  /** every role held by one person */
  public TreeSet<String> of(long userId) throws SQLException {
    TreeSet<String> roles = new TreeSet<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT role_name FROM " + Schema.ROLES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          roles.add(rows.getString(1));
        }
      }
    }
    return roles;
  }

  public boolean has(long userId, String role) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT 1 FROM " + Schema.ROLES + " WHERE user_id = ? AND role_name = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, normalize(role));
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  /** grant a role; granting one somebody already holds is a no-op, not an error */
  public void grant(long userId, String role, Long grantedBy) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.ROLES + " (user_id, role_name, granted_at, granted_by)"
                 + " KEY (user_id, role_name) VALUES (?, ?, CURRENT_TIMESTAMP, ?)")) {
      statement.setLong(1, userId);
      statement.setString(2, normalize(role));
      if (grantedBy == null) {
        statement.setNull(3, java.sql.Types.BIGINT);
      } else {
        statement.setLong(3, grantedBy);
      }
      statement.executeUpdate();
    }
  }

  public void revoke(long userId, String role) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.ROLES + " WHERE user_id = ? AND role_name = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, normalize(role));
      statement.executeUpdate();
    }
  }

  /** every role one person holds, for when the account itself is going away */
  public void revokeAll(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.ROLES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.ROLES, userId, MutationEvent.Kind.delete, null);
  }

  /** everybody holding a role, for an admin listing */
  public List<Long> holdersOf(String role) throws SQLException {
    ArrayList<Long> holders = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT user_id FROM " + Schema.ROLES + " WHERE role_name = ? ORDER BY granted_at")) {
      statement.setString(1, normalize(role));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          holders.add(rows.getLong(1));
        }
      }
    }
    return holders;
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + Schema.ROLES);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** role names are lowercase; "Admin" and "admin" must not be two different powers */
  static String normalize(String role) {
    return role == null ? null : role.trim().toLowerCase(Locale.ROOT);
  }
}
