package io.hearth.auth;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * The emails table, as operations rather than SQL scattered through handlers.
 *
 * Every method here takes an already-normalized address. Normalization happens once, at the edge,
 * in {@link Tokens#normalizeEmail}; doing it in two places is how you end up with two rows for the
 * same person.
 */
public class Users {
  private final Store store;

  public Users(Store store) {
    this.store = store;
  }

  private static final String COLUMNS =
      "id, email, password_hash, created_at, verified_at, last_login_at, approved_at, approved_by,"
          + " signup_events, signup_signals, signup_ip, sessions_valid_after,"
          + " failed_attempts, locked_until, disabled";

  public UserRecord byEmail(String normalizedEmail) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.EMAILS + " WHERE email = ?")) {
      statement.setString(1, normalizedEmail);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public UserRecord byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.EMAILS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /**
   * Create an account. passwordHash may be null: a passwordless account is the normal case, not a
   * half-finished one.
   *
   * The account is created unapproved. Whether it is then approved on the spot -- because the
   * address is on the bootstrap admin list -- is a decision for {@link Access}, made by the caller,
   * so that this method has exactly one behaviour.
   */
  public UserRecord create(String normalizedEmail, String passwordHash, boolean verified, Signup signup) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.EMAILS + " (email, password_hash, verified_at,"
                 + " signup_events, signup_signals, signup_ip) VALUES (?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, normalizedEmail);
      statement.setString(2, passwordHash);
      statement.setTimestamp(3, verified ? new Timestamp(System.currentTimeMillis()) : null);
      statement.setInt(4, signup == null ? 0 : signup.events());
      statement.setString(5, signup == null ? null : trim(signup.signals(), 160));
      statement.setString(6, signup == null ? null : trim(signup.ip(), 64));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        return byId(keys.getLong(1));
      }
    }
  }

  /** what the browser did while the signup form was open, kept for later audits */
  public record Signup(int events, String signals, String ip) {
  }

  /** an admin says yes; approvedBy is null when the config granted it rather than a person */
  public void approve(long id, Long approvedBy) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.EMAILS + " SET approved_at = COALESCE(approved_at, CURRENT_TIMESTAMP),"
                 + " approved_by = COALESCE(approved_by, ?), updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      if (approvedBy == null) {
        statement.setNull(1, java.sql.Types.BIGINT);
      } else {
        statement.setLong(1, approvedBy);
      }
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  /** take approval away again; existing sessions are the caller's problem to revoke */
  public void unapprove(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.EMAILS + " SET approved_at = NULL, approved_by = NULL,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  /** everybody still waiting, oldest first, because they have been waiting longest */
  public List<UserRecord> awaitingApproval(int limit) throws SQLException {
    ArrayList<UserRecord> waiting = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.EMAILS
                 // MODE=STRICT wants standard SQL here; LIMIT is an H2 extension it refuses
                 + " WHERE approved_at IS NULL AND disabled = FALSE ORDER BY created_at"
                 + " FETCH FIRST ? ROWS ONLY")) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          waiting.add(read(rows));
        }
      }
    }
    return waiting;
  }

  /** everybody, newest first, for the admin listing */
  public List<UserRecord> recent(int limit) throws SQLException {
    ArrayList<UserRecord> people = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.EMAILS
                 + " ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY")) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          people.add(read(rows));
        }
      }
    }
    return people;
  }

  private static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  public void setPassword(long id, String passwordHash) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.EMAILS + " SET password_hash = ?, updated_at = CURRENT_TIMESTAMP,"
                 + " sessions_valid_after = CURRENT_TIMESTAMP WHERE id = ?")) {
      // changing the password also draws a line under every session issued before now
      statement.setString(1, passwordHash);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  public void markVerified(long id) throws SQLException {
    update("UPDATE " + Schema.EMAILS + " SET verified_at = COALESCE(verified_at, CURRENT_TIMESTAMP),"
        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
  }

  public void markSignedIn(long id) throws SQLException {
    update("UPDATE " + Schema.EMAILS + " SET last_login_at = CURRENT_TIMESTAMP, failed_attempts = 0,"
        + " locked_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
  }

  /** count a failed attempt and lock the account once the threshold is crossed */
  public void recordFailure(long id, LoginSecurity security) throws SQLException {
    if (security.lockoutThreshold <= 0) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.EMAILS + " SET failed_attempts = failed_attempts + 1,"
                 + " locked_until = CASE WHEN failed_attempts + 1 >= ? THEN DATEADD('SECOND', ?, CURRENT_TIMESTAMP)"
                 + " ELSE locked_until END, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setInt(1, security.lockoutThreshold);
      statement.setLong(2, security.lockoutSeconds);
      statement.setLong(3, id);
      statement.executeUpdate();
    }
  }

  /** the operator kill switch for one account */
  public void setDisabled(long id, boolean disabled) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.EMAILS + " SET disabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setBoolean(1, disabled);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  /**
   * Remove an account outright.
   *
   * This is what rejection means. "Not yet" is what leaving somebody unapproved means, and the two
   * needed different words: an admin who says no is saying the address does not belong here, and
   * keeping the row would leave a stranger's profile and answers sitting in the database for nobody
   * to ever read again. The caller is responsible for the rows that hang off this one -- roles,
   * sessions, profile, answers -- because those live in other DAOs and each has to emit its own
   * event.
   */
  public void delete(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.EMAILS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.EMAILS, id, MutationEvent.Kind.delete, null);
  }

  /**
   * Forget the IP address a sign-up came from, once it has stopped being useful.
   *
   * It is recorded to make sense of a burst of registrations from one place, which is a question
   * somebody asks in the days afterwards and never a year later. Keeping it beyond that is holding
   * personal data with no purpose left, which is the one thing every data protection regime agrees
   * about -- and the privacy policy this software ships did not even mention it, because whoever
   * wrote the policy was describing what the product was for rather than what the table held.
   *
   * Run by the reaper, so it happens without anybody remembering to.
   */
  public int forgetOldSignupIps(long olderThanMillis) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.EMAILS + " SET signup_ip = NULL"
                 + " WHERE signup_ip IS NOT NULL AND created_at < ?")) {
      statement.setTimestamp(1, new java.sql.Timestamp(olderThanMillis));
      return statement.executeUpdate();
    }
  }

  /**
   * How many people are actually in this community.
   *
   * Approved, not disabled. It is the denominator of anything that says "so many of the members",
   * and counting everybody with a row would put strangers waiting on a decision into the total --
   * which makes every such figure quietly pessimistic and unfixably so.
   */
  public int approvedCount() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM "
             + Schema.EMAILS + " WHERE approved_at IS NOT NULL AND disabled = FALSE");
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + Schema.EMAILS);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private void update(String sql, long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  private static UserRecord read(ResultSet rows) throws SQLException {
    long approvedBy = rows.getLong("approved_by");
    boolean approvedByNull = rows.wasNull();
    return new UserRecord(
        rows.getLong("id"),
        rows.getString("email"),
        rows.getString("password_hash"),
        rows.getTimestamp("created_at"),
        rows.getTimestamp("verified_at"),
        rows.getTimestamp("last_login_at"),
        rows.getTimestamp("approved_at"),
        approvedByNull ? null : approvedBy,
        rows.getInt("signup_events"),
        rows.getString("signup_signals"),
        rows.getString("signup_ip"),
        rows.getTimestamp("sessions_valid_after"),
        rows.getInt("failed_attempts"),
        rows.getTimestamp("locked_until"),
        rows.getBoolean("disabled"));
  }
}
