package io.hearth.hevy;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Keys somebody handed this server for a service somewhere else.
 *
 * <b>Held in the clear, because there is no honest alternative.</b> A session token is stored as a
 * hash because it is only ever compared against one somebody presents; this has to be *presented*
 * to Hevy on every call, so it is a password this server keeps on a person's behalf. Everything
 * here is shaped around being uneasy about that: one row per person per service, never printed on
 * any screen, cleared with one button, and read only at the moment a call is about to be made.
 *
 * <b>The service is a closed list.</b> {@link Service} rather than a string, so this cannot quietly
 * become a bag of arbitrary credentials with nothing checking what any of them are for.
 *
 * <b>Nobody else's key is reachable.</b> Every method takes the user id it is acting for, and the
 * only caller that supplies one is a request that already established who is asking. There is no
 * "list all keys" and there is no way to read one back out to a screen -- {@link #has} is the whole
 * question a screen is allowed to ask.
 */
public class UserKeys {
  private final Store store;

  public UserKeys(Store store) {
    this.store = store;
  }

  /** the services this server will hold a key for */
  public enum Service {
    hevy("Hevy", "https://hevy.com/settings?developer");

    public final String label;
    /** where a person goes to get one, printed beside the box */
    public final String where;

    Service(String label, String where) {
      this.label = label;
      this.where = where;
    }

    public static Service of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }

  /** the key itself, for the moment a call is about to be made; null when there is none */
  public String secretFor(long userId, Service service) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT secret FROM " + Schema.USER_KEYS + " WHERE user_id = ? AND service = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, service.name());
      try (ResultSet found = statement.executeQuery()) {
        if (!found.next()) {
          return null;
        }
        String secret = found.getString("secret");
        return secret == null || secret.isBlank() ? null : secret;
      }
    }
  }

  /** what a screen is allowed to ask: is there one? */
  public boolean has(long userId, Service service) throws SQLException {
    return secretFor(userId, service) != null;
  }

  /** the last four characters, which is enough to tell two keys apart and not enough to use one */
  public String hint(long userId, Service service) throws SQLException {
    String secret = secretFor(userId, service);
    return secret == null || secret.length() < 4 ? "" : "…" + secret.substring(
        secret.length() - 4);
  }

  public void save(long userId, Service service, String secret) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.USER_KEYS + " (user_id, service, secret, updated_at)"
                 + " KEY (user_id, service) VALUES (?, ?, ?, ?)")) {
      statement.setLong(1, userId);
      statement.setString(2, service.name());
      statement.setString(3, secret.trim());
      statement.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis()));
      statement.executeUpdate();
    }
    store.changed(Schema.USER_KEYS, userId + ":" + service, MutationEvent.Kind.update, userId);
  }

  /** remove it; deleting the row rather than blanking it, so "no key" is one state and not two */
  public void clear(long userId, Service service) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.USER_KEYS + " WHERE user_id = ? AND service = ?")) {
      statement.setLong(1, userId);
      statement.setString(2, service.name());
      statement.executeUpdate();
    }
    store.changed(Schema.USER_KEYS, userId + ":" + service, MutationEvent.Kind.delete, userId);
  }

  /** everything this person handed over, for an export or an erasure */
  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.USER_KEYS + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }
}
