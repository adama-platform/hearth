package io.hearth.mail;

import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this community says instead of the standard wording, if anything.
 *
 * A row exists only once somebody has changed something. Everything else is the same argument as
 * the legal documents: the default is in the jar, so a community that has never opened the screen
 * still sends good messages, and upgrading the software improves them rather than leaving every
 * install frozen on the wording of the day it started.
 *
 * Cached in memory, and dropped when a row changes rather than on a timer -- these are read on the
 * way out of every message and written about twice a year.
 *
 * <b>Substitution is a replace, not an engine.</b> `{{community}}` becomes a string and that is the
 * whole of it: no sections, no partials, no lookups. What an administrator types into a box must
 * never be something the server evaluates, and a template language that could reach past the values
 * it was handed is a way into everything else from a text field. The same rule the legal documents
 * follow, for the same reason.
 */
public class SystemTemplates {
  /** one flow's wording, whether or not anybody has changed it */
  public record Wording(SystemTemplate template, String subject, String lead, String body,
                        boolean overridden, Timestamp updatedAt, Long updatedBy) {
  }

  private final Store store;
  private final Map<SystemTemplate, Wording> cached = new EnumMap<>(SystemTemplate.class);

  public SystemTemplates(Store store) {
    this.store = store;
  }

  public void load() throws SQLException {
    EnumMap<SystemTemplate, Wording> loaded = new EnumMap<>(SystemTemplate.class);
    for (SystemTemplate template : SystemTemplate.values()) {
      loaded.put(template, read(template));
    }
    synchronized (cached) {
      cached.clear();
      cached.putAll(loaded);
    }
  }

  public Wording of(SystemTemplate template) {
    synchronized (cached) {
      Wording wording = cached.get(template);
      if (wording != null) {
        return wording;
      }
    }
    try {
      Wording wording = read(template);
      synchronized (cached) {
        cached.put(template, wording);
      }
      return wording;
    } catch (SQLException ex) {
      // a community's own wording is worth a query; a message that does not go out because of one
      // is not, so the shipped words are the fallback
      return standard(template);
    }
  }

  private static Wording standard(SystemTemplate template) {
    return new Wording(template, template.subject, template.lead, template.body, false, null, null);
  }

  private Wording read(SystemTemplate template) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT subject, lead, body, updated_at, updated_by FROM " + Schema.SYSTEM_TEMPLATES
                 + " WHERE slug = ?")) {
      statement.setString(1, template.name());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return standard(template);
        }
        return new Wording(template, rows.getString("subject"), rows.getString("lead"),
            rows.getString("body"), true, rows.getTimestamp("updated_at"),
            rows.getLong("updated_by"));
      }
    }
  }

  /**
   * Write one, or take the override away.
   *
   * Saving something identical to the shipped wording deletes the row rather than storing a copy,
   * so "has this community changed anything" stays answerable and an upgrade still improves the
   * text somebody never actually meant to freeze.
   */
  public Wording save(SystemTemplate template, String subject, String lead, String body,
                      Long actor) throws SQLException {
    boolean same = template.subject.equals(trim(subject)) && template.lead.equals(trim(lead))
        && template.body.equals(trim(body));
    if (same) {
      return reset(template, actor);
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.SYSTEM_TEMPLATES
                 + " (slug, subject, lead, body, updated_at, updated_by)"
                 + " KEY (slug) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)")) {
      statement.setString(1, template.name());
      statement.setString(2, trim(subject));
      statement.setString(3, trim(lead));
      statement.setString(4, trim(body));
      if (actor == null) {
        statement.setNull(5, java.sql.Types.BIGINT);
      } else {
        statement.setLong(5, actor);
      }
      statement.executeUpdate();
    }
    store.changed(Schema.SYSTEM_TEMPLATES, template.ordinal(), MutationEvent.Kind.update, actor);
    synchronized (cached) {
      cached.remove(template);
    }
    return of(template);
  }

  public Wording reset(SystemTemplate template, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.SYSTEM_TEMPLATES + " WHERE slug = ?")) {
      statement.setString(1, template.name());
      statement.executeUpdate();
    }
    store.changed(Schema.SYSTEM_TEMPLATES, template.ordinal(), MutationEvent.Kind.delete, actor);
    synchronized (cached) {
      cached.remove(template);
    }
    return standard(template);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * Put the values into the words.
   *
   * A parameter nobody supplied becomes an empty string rather than being left as `{{name}}` on
   * screen: a message with a hole in it is bad and a message with the machinery showing is worse,
   * and the preview on the editing screen is where somebody finds out which they have.
   */
  public static String fill(String text, Map<String, String> values) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length());
    int at = 0;
    while (at < text.length()) {
      int open = text.indexOf("{{", at);
      if (open < 0) {
        out.append(text, at, text.length());
        break;
      }
      int close = text.indexOf("}}", open);
      if (close < 0) {
        out.append(text, at, text.length());
        break;
      }
      out.append(text, at, open);
      String name = text.substring(open + 2, close).trim();
      String value = values.get(name);
      out.append(value == null ? "" : value);
      at = close + 2;
    }
    return out.toString().replaceAll("[ \\t]+\\n", "\n").trim();
  }

  /** the values every message has, whatever it is about */
  public static Map<String, String> common(MailBrand brand) {
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    values.put("community", brand.nameOr());
    values.put("domain", brand.domain());
    values.put("site", brand.siteUrl());
    return values;
  }
}
