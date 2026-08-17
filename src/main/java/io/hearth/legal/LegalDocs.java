package io.hearth.legal;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.Map;

/**
 * What this community has said instead of the standard text, if anything.
 *
 * A row exists only once somebody has overridden a document, and that is the whole design: the
 * default is in the jar, so a community that has never opened this screen still has terms and a
 * privacy policy, and upgrading the software improves them. Writing the defaults into the database
 * at boot would freeze them at whatever they said the day the community started, which is exactly
 * how a privacy policy ends up describing a feature that no longer exists.
 *
 * Cached in memory for the same reason {@link io.hearth.theme.Themes} is: every email footer and
 * every page can reach for these, and there are two of them.
 */
public class LegalDocs {
  /** an override, or the standard text when nobody has written one */
  public record Text(LegalDoc doc, String markdown, boolean overridden, Timestamp updatedAt,
                     Long updatedBy) {
  }

  private final Store store;
  private final Map<LegalDoc, Text> cached = new EnumMap<>(LegalDoc.class);

  public LegalDocs(Store store) {
    this.store = store;
  }

  public void load() throws SQLException {
    EnumMap<LegalDoc, Text> loaded = new EnumMap<>(LegalDoc.class);
    for (LegalDoc doc : LegalDoc.values()) {
      loaded.put(doc, read(doc));
    }
    synchronized (cached) {
      cached.clear();
      cached.putAll(loaded);
    }
  }

  public Text of(LegalDoc doc) {
    synchronized (cached) {
      Text text = cached.get(doc);
      if (text != null) {
        return text;
      }
    }
    try {
      Text text = read(doc);
      synchronized (cached) {
        cached.put(doc, text);
      }
      return text;
    } catch (SQLException ex) {
      return standard(doc);
    }
  }

  /** save an override; an empty body means "go back to what the software ships with" */
  public void save(LegalDoc doc, String markdown, Long actor) throws SQLException {
    if (markdown == null || markdown.isBlank()) {
      reset(doc, actor);
      return;
    }
    int updated;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.LEGAL + " SET body = ?, updated_at = CURRENT_TIMESTAMP,"
                 + " updated_by = ? WHERE slug = ?")) {
      statement.setString(1, markdown);
      setActor(statement, 2, actor);
      statement.setString(3, doc.slug);
      updated = statement.executeUpdate();
    }
    if (updated == 0) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.LEGAL + " (slug, body, updated_by) VALUES (?, ?, ?)")) {
        statement.setString(1, doc.slug);
        statement.setString(2, markdown);
        setActor(statement, 3, actor);
        statement.executeUpdate();
      }
    }
    refresh(doc);
    store.changed(Schema.LEGAL, doc.ordinal(), MutationEvent.Kind.update, actor);
  }

  /** drop the override, so the document goes back to the text in the jar */
  public void reset(LegalDoc doc, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.LEGAL + " WHERE slug = ?")) {
      statement.setString(1, doc.slug);
      statement.executeUpdate();
    }
    synchronized (cached) {
      cached.put(doc, standard(doc));
    }
    store.changed(Schema.LEGAL, doc.ordinal(), MutationEvent.Kind.delete, actor);
  }

  private void refresh(LegalDoc doc) throws SQLException {
    Text text = read(doc);
    synchronized (cached) {
      cached.put(doc, text);
    }
  }

  private Text read(LegalDoc doc) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT body, updated_at, updated_by FROM " + Schema.LEGAL + " WHERE slug = ?")) {
      statement.setString(1, doc.slug);
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          String body = rows.getString(1);
          if (body != null && !body.isBlank()) {
            // wasNull() answers about the *last* column read, so it is asked before the timestamp
            // is fetched. Reading them the other way round reports on the timestamp and turns a
            // null author into user 0, which is somebody.
            long by = rows.getLong(3);
            Long author = rows.wasNull() ? null : by;
            return new Text(doc, body, true, rows.getTimestamp(2), author);
          }
        }
      }
    }
    return standard(doc);
  }

  private static Text standard(LegalDoc doc) {
    return new Text(doc, doc.standard(), false, null, null);
  }

  private static void setActor(PreparedStatement statement, int index, Long actor)
      throws SQLException {
    if (actor == null) {
      statement.setNull(index, java.sql.Types.BIGINT);
    } else {
      statement.setLong(index, actor);
    }
  }
}
