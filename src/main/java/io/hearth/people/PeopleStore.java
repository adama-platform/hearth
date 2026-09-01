package io.hearth.people;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Profiles, questions and answers.
 *
 * Every write announces itself on the event bus naming the table and the row, which is what the
 * survey indexer listens to: a question changing means everybody's remaining count is wrong, and an
 * answer changing means one person's is. Neither this class nor the indexer knows about the other.
 */
public class PeopleStore {
  /** the private half, which PROFILE_COLUMNS deliberately does not name */
  private static final String HOME_COLUMNS =
      "user_id, address, latitude, longitude, geo_precision, geo_state, geo_service, geo_tries,"
          + " geo_tried_at, geo_next_at, geo_note";
  private static final String PROFILE_COLUMNS =
      "id, user_id, display_name, headline, about, location, links, orientation_step,"
          + " created_at, updated_at";
  private static final String QUESTION_COLUMNS =
      "id, definition, position, published, updated_at, deleted_at";
  private static final String ANSWER_COLUMNS = "id, user_id, blob, answered, remaining";

  private final Store store;

  public PeopleStore(Store store) {
    this.store = store;
  }

  // ---- profiles --------------------------------------------------------------------------------

  /** somebody's profile, or a blank one; never null, because everybody has a profile conceptually */
  public ProfileRecord profileOf(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROFILE_COLUMNS + " FROM " + Schema.PROFILES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readProfile(rows) : ProfileRecord.blank(userId);
      }
    }
  }

  /**
   * How far through the welcome somebody got, recorded when they got there.
   *
   * Only ever forwards. A step is written when somebody *finishes* one, so re-opening the welcome,
   * or typing a step into the address bar, cannot make the record say they did something they did
   * not -- and the number is only useful because it is true. There is no event: nothing caches it,
   * and the dashboard reads it directly.
   */
  public void markOrientation(long userId, int step) throws SQLException {
    ProfileRecord existing = profileOf(userId);
    if (step <= existing.orientationStep()) {
      return;
    }
    if (existing.id() == 0) {
      // somebody who reached the welcome without ever saving anything; the row has to exist for
      // the number to live on it
      saveProfile(userId, "", "", "", "", "");
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROFILES + " SET orientation_step = ? WHERE user_id = ?")) {
      statement.setInt(1, step);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
  }

  public ProfileRecord saveProfile(long userId, String displayName, String headline, String about,
                                   String location, String links) throws SQLException {
    ProfileRecord existing = profileOf(userId);
    boolean isNew = existing.id() == 0;
    String sql = isNew
        ? "INSERT INTO " + Schema.PROFILES + " (display_name, headline, about, location, links, user_id)"
            + " VALUES (?, ?, ?, ?, ?, ?)"
        : "UPDATE " + Schema.PROFILES + " SET display_name = ?, headline = ?, about = ?, location = ?,"
            + " links = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?";
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, cap(displayName, 128));
      statement.setString(2, cap(headline, 256));
      statement.setString(3, cap(about, 8192));
      statement.setString(4, cap(location, 128));
      statement.setString(5, cap(links, 1024));
      statement.setLong(6, userId);
      statement.executeUpdate();
    }
    ProfileRecord saved = profileOf(userId);
    store.changed(Schema.PROFILES, saved.id(),
        isNew ? MutationEvent.Kind.insert : MutationEvent.Kind.update, userId);
    return saved;
  }

  /**
   * Drop everything one person told us about themselves.
   *
   * Called when an account is rejected or banned. A profile and a sheet of answers about somebody
   * who is no longer here is data with no reader, and the only thing it can still do is leak.
   */
  /**
   * Everybody's profile, in one query.
   *
   * The members directory is the one screen that needs all of them at once, and a profile per row
   * would be five hundred queries to draw one page. Keyed by user id so the caller can join it
   * against whoever it decided is a member -- this store knows what people wrote, not who is
   * allowed to be seen.
   */
  public java.util.Map<Long, ProfileRecord> allProfiles() throws SQLException {
    java.util.LinkedHashMap<Long, ProfileRecord> found = new java.util.LinkedHashMap<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROFILE_COLUMNS + " FROM " + Schema.PROFILES);
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        ProfileRecord profile = readProfile(rows);
        found.put(profile.userId(), profile);
      }
    }
    return found;
  }

  /**
   * Remove somebody's profile outright.
   *
   * Erasure calls this and nothing else does. The row is deleted rather than blanked, because the
   * privacy policy promises "delete your account and what is attached to it" and a row of empty
   * strings is not that -- and because {@code RightsTests} walks every column of every table
   * afterwards looking for the address, which is the only form of that test worth writing.
   */
  public void forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PROFILES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.PROFILES, userId, MutationEvent.Kind.delete, userId);
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private long count(String table) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static void setActor(PreparedStatement statement, int index, Long actor) throws SQLException {
    if (actor == null) {
      statement.setNull(index, java.sql.Types.BIGINT);
    } else {
      statement.setLong(index, actor);
    }
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static ProfileRecord readProfile(ResultSet rows) throws SQLException {
    return new ProfileRecord(
        rows.getLong("id"),
        rows.getLong("user_id"),
        rows.getString("display_name"),
        rows.getString("headline"),
        rows.getString("about"),
        rows.getString("location"),
        rows.getString("links"),
        rows.getInt("orientation_step"),
        rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

}
