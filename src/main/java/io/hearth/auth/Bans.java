package io.hearth.auth;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Addresses this server will not spend anything on.
 *
 * The point is the cost, not the insult. A banned address is checked before a code is minted, before
 * anything is mailed, and before a row is written -- so somebody hammering the register form costs a
 * hash lookup rather than a scrypt hash, an email, and a row for an admin to review later.
 *
 * The list is cached in memory and kept honest by the event bus, because it is consulted on the
 * cheapest path in the system and a query there would defeat the purpose.
 */
public class Bans {
  private final Store store;
  private final String domain;
  private final Set<String> cached = ConcurrentHashMap.newKeySet();
  private volatile boolean loaded;

  public Bans(Store store, String domain) {
    this.store = store;
    this.domain = domain;
    store.events().subscribe(event -> {
      if (event.domain().equals(domain) && event.touches(Schema.BANS)) {
        // the set is small and changes rarely; reloading it whole is simpler than reconciling
        loaded = false;
      }
    });
  }

  /** is this address banned? the question asked on the hot path */
  public boolean isBanned(String normalizedEmail) {
    if (normalizedEmail == null) {
      return false;
    }
    ensureLoaded();
    return cached.contains(normalizedEmail);
  }

  private void ensureLoaded() {
    if (loaded) {
      return;
    }
    synchronized (this) {
      if (loaded) {
        return;
      }
      try {
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        for (BanRecord ban : all(5000)) {
          fresh.add(ban.email());
        }
        cached.clear();
        cached.addAll(fresh);
        loaded = true;
      } catch (SQLException ex) {
        // a failed load must not accidentally unban everybody; leave the old set and try again
        loaded = false;
      }
    }
  }

  public List<BanRecord> all(int limit) throws SQLException {
    ArrayList<BanRecord> bans = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT id, email, reason, created_at, created_by FROM " + Schema.BANS
                 + " ORDER BY created_at DESC " + store.dialect().limit(limit));
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        long by = rows.getLong("created_by");
        bans.add(new BanRecord(rows.getLong("id"), rows.getString("email"), rows.getString("reason"),
            rows.getTimestamp("created_at"), rows.wasNull() ? null : by));
      }
    }
    return bans;
  }

  public void ban(String normalizedEmail, String reason, Long by) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             store.dialect().upsert(Schema.BANS, new String[]{"email", "reason", "created_by"},
                 new String[]{"email"}))) {
      statement.setString(1, normalizedEmail);
      statement.setString(2, reason == null ? "" : reason.length() > 256 ? reason.substring(0, 256) : reason);
      if (by == null) {
        statement.setNull(3, java.sql.Types.BIGINT);
      } else {
        statement.setLong(3, by);
      }
      statement.executeUpdate();
    }
    store.changed(Schema.BANS, normalizedEmail, MutationEvent.Kind.insert, by);
  }

  public void lift(long id, Long by) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.BANS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.BANS, id, MutationEvent.Kind.delete, by);
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + Schema.BANS);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** one banned address */
  public record BanRecord(long id, String email, String reason, Timestamp createdAt, Long createdBy) {
  }
}
