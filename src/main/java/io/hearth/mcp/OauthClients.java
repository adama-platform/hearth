package io.hearth.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The connectors allowed to ask for an agent token.
 *
 * A client is a name and a set of redirect URIs, and the redirect URIs are the part that matters:
 * everything else in the authorization flow is defence in depth behind the question "are we willing
 * to hand a code to this address". They are stored in full and compared exactly.
 *
 * There is no client secret. These are public clients -- a hosted connector cannot keep a secret,
 * and pretending otherwise buys nothing -- so PKCE is what proves the client redeeming the code is
 * the one that started the flow. That is what OAuth 2.1 asks for and it is the honest model here.
 */
public class OauthClients {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String COLUMNS =
      "id, client_id, name, vendor, redirect_uris, created_at, created_by, disabled";

  private final Store store;

  public OauthClients(Store store) {
    this.store = store;
  }

  /** one registered connector */
  public record ClientRecord(long id, String clientId, String name, Vendor vendor,
                             List<String> redirectUris, Timestamp createdAt, Long createdBy,
                             boolean disabled) {
    public boolean allows(String redirectUri) {
      return redirectUri != null && redirectUris.contains(redirectUri);
    }

    public String redirectList() {
      return String.join("\n", redirectUris);
    }
  }

  public ClientRecord byClientId(String clientId) throws SQLException {
    if (clientId == null) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.OAUTH_CLIENTS + " WHERE client_id = ?")) {
      statement.setString(1, clientId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public ClientRecord byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.OAUTH_CLIENTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public List<ClientRecord> all(int limit) throws SQLException {
    ArrayList<ClientRecord> clients = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.OAUTH_CLIENTS
                 + " ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY")) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          clients.add(read(rows));
        }
      }
    }
    return clients;
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.OAUTH_CLIENTS);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** register a connector; the caller has already checked every redirect against the policy */
  public ClientRecord register(String name, Vendor vendor, List<String> redirectUris, Long actor)
      throws SQLException {
    String clientId = newClientId();
    ArrayNode array = JSON.createArrayNode();
    for (String uri : redirectUris) {
      array.add(uri);
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.OAUTH_CLIENTS + " (client_id, name, vendor, redirect_uris, created_by)"
                 + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, clientId);
      statement.setString(2, cap(name, 128));
      statement.setString(3, vendor.name());
      statement.setString(4, array.toString());
      if (actor == null) {
        statement.setNull(5, java.sql.Types.BIGINT);
      } else {
        statement.setLong(5, actor);
      }
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        store.changed(Schema.OAUTH_CLIENTS, keys.getLong(1), MutationEvent.Kind.insert, actor);
      }
    }
    return byClientId(clientId);
  }

  public void setDisabled(long id, boolean disabled, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.OAUTH_CLIENTS + " SET disabled = ? WHERE id = ?")) {
      statement.setBoolean(1, disabled);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
    store.changed(Schema.OAUTH_CLIENTS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Forget a connector entirely.
   *
   * Revoking the tokens it holds is the caller's job, and the admin path does it first -- deleting
   * the registration on its own would leave live agent tokens whose client nobody can look up.
   */
  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.OAUTH_CLIENTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.OAUTH_CLIENTS, id, MutationEvent.Kind.delete, actor);
  }

  private static ClientRecord read(ResultSet rows) throws SQLException {
    ArrayList<String> uris = new ArrayList<>();
    try {
      JsonNode node = JSON.readTree(rows.getString("redirect_uris"));
      if (node.isArray()) {
        for (JsonNode item : node) {
          if (item.isTextual()) {
            uris.add(item.textValue());
          }
        }
      }
    } catch (Exception ex) {
      // a client whose redirect list will not parse is a client that can authorize nothing, which
      // is the safe reading of a corrupted row
    }
    Long createdBy = rows.getLong("created_by");
    if (rows.wasNull()) {
      createdBy = null;
    }
    return new ClientRecord(rows.getLong("id"), rows.getString("client_id"), rows.getString("name"),
        Vendor.of(rows.getString("vendor")), List.copyOf(uris), rows.getTimestamp("created_at"),
        createdBy, rows.getBoolean("disabled"));
  }

  private static String newClientId() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return "hc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String cap(String value, int max) {
    if (value == null || value.isBlank()) {
      return "unnamed connector";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
