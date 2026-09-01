package io.hearth.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a role means.
 *
 * `roles` says who holds what; this says what holding it lets you do. Splitting them is what makes
 * a role editable: changing what "editor" means is one row, not a sweep over everybody who is one.
 *
 * **admin is built in and not editable.** It holds {@link Permission#everything}, cannot have a
 * permission taken away, and cannot be deleted -- because a community that can accidentally edit
 * its way out of having an administrator has locked itself out of its own server, and the fix
 * involves somebody with shell access. The config's `admin_emails` is the second lock on that door.
 *
 * Every other role is data. A community can invent `greeter` or `librarian` and decide what those
 * mean, and the permission list is a closed enum so a role editor can render all of it as
 * checkboxes without knowing what any of them do.
 */
public class RoleDefs {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String COLUMNS =
      "id, name, label, description, permissions, builtin, color, created_at, updated_at";

  /** the role the server itself understands; everything else is a community's own invention */
  public static final String ADMIN = Roles.ADMIN;
  /** the role a new community most often wants second, seeded on first boot */
  public static final String EDITOR = "editor";

  private final Store store;

  public RoleDefs(Store store) {
    this.store = store;
  }

  public record Def(long id, String name, String label, String description,
                    Set<Permission> permissions, boolean builtin, String color,
                    Timestamp createdAt, Timestamp updatedAt) {
    public boolean allows(Permission permission) {
      return permissions.contains(Permission.everything) || permissions.contains(permission);
    }

    public String labelOr() {
      return label == null || label.isBlank() ? name : label;
    }

    public int count() {
      return permissions.contains(Permission.everything)
          ? Permission.values().length - 1 : permissions.size();
    }
  }

  public static final String PLACES = "place-manager";

  public void seed() throws SQLException {
    upsert(ADMIN, "Administrator", "Everything, always. Cannot be edited or removed.",
        EnumSet.of(Permission.everything), true, "purple", true);
    if (byName(EDITOR) == null) {
      upsert(EDITOR, "Editor", "Writes and publishes the site.",
          EnumSet.of(Permission.admin_enter, Permission.content_read, Permission.content_write,
              Permission.content_publish, Permission.templates_write,
              Permission.navigation_write, Permission.attachments_write),
          false, "blue", false);
    }
  }

  public List<Def> all() throws SQLException {
    ArrayList<Def> defs = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ROLE_DEFS + " ORDER BY builtin DESC, name ASC")) {
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          defs.add(read(rows));
        }
      }
    }
    return defs;
  }

  public Def byName(String name) throws SQLException {
    if (name == null) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ROLE_DEFS + " WHERE name = ?")) {
      statement.setString(1, name.trim().toLowerCase());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public Def byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ROLE_DEFS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /**
   * Everything a set of role names adds up to.
   *
   * A union, never an intersection: holding two roles can only ever give you more. A role somebody
   * holds that has no definition contributes nothing rather than failing -- a stale grant should
   * not be able to take the admin section down.
   */
  public Set<Permission> permissionsFor(Collection<String> roleNames) throws SQLException {
    EnumSet<Permission> allowed = EnumSet.noneOf(Permission.class);
    if (roleNames == null || roleNames.isEmpty()) {
      return allowed;
    }
    for (Def def : all()) {
      if (roleNames.contains(def.name())) {
        allowed.addAll(def.permissions());
      }
    }
    return allowed;
  }

  /** create or edit; admin refuses to be edited into something else */
  public void save(String name, String label, String description, Set<Permission> permissions,
                   String color, Long actor) throws SQLException {
    String clean = normalize(name);
    if (clean == null) {
      throw new SQLException("a role needs a name");
    }
    if (ADMIN.equals(clean)) {
      throw new SQLException("the admin role is built in and cannot be edited");
    }
    LinkedHashSet<Permission> wanted = new LinkedHashSet<>(permissions);
    // everything is the admin role's alone; granting it elsewhere would create a second god role
    // that nothing protects from being edited or deleted
    wanted.remove(Permission.everything);
    // writing implies reading, and anything at all implies reaching the admin section -- otherwise
    // a role grants a power behind a door it cannot open
    LinkedHashSet<Permission> closed = new LinkedHashSet<>();
    for (Permission permission : wanted) {
      closed.addAll(permission.implies());
    }
    wanted = closed;
    upsert(clean, label, description, wanted, false, color, true);
    store.changed(Schema.ROLE_DEFS, clean, MutationEvent.Kind.update, actor);
  }

  /** the built-in role cannot go; anything else can, and the grants go with it */
  public void delete(String name, Long actor) throws SQLException {
    String clean = normalize(name);
    if (clean == null || ADMIN.equals(clean)) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement grants = connection.prepareStatement(
             "DELETE FROM " + Schema.ROLES + " WHERE role_name = ?");
         PreparedStatement def = connection.prepareStatement(
             "DELETE FROM " + Schema.ROLE_DEFS + " WHERE name = ? AND builtin = FALSE")) {
      grants.setString(1, clean);
      grants.executeUpdate();
      def.setString(1, clean);
      def.executeUpdate();
    }
    store.changed(Schema.ROLE_DEFS, clean, MutationEvent.Kind.delete, actor);
  }

  private void upsert(String name, String label, String description, Set<Permission> permissions,
                      boolean builtin, String color, boolean overwrite) throws SQLException {
    String packed = pack(permissions);
    int updated = 0;
    if (overwrite) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.ROLE_DEFS + " SET label = ?, description = ?, permissions = ?,"
                   + " color = ?, updated_at = CURRENT_TIMESTAMP WHERE name = ?")) {
        statement.setString(1, cap(label, 64));
        statement.setString(2, cap(description, 512));
        statement.setString(3, packed);
        statement.setString(4, cap(color, 16));
        statement.setString(5, name);
        updated = statement.executeUpdate();
      }
    }
    if (updated == 0) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.ROLE_DEFS + " (name, label, description, permissions,"
                   + " builtin, color) VALUES (?, ?, ?, ?, ?, ?)")) {
        statement.setString(1, name);
        statement.setString(2, cap(label, 64));
        statement.setString(3, cap(description, 512));
        statement.setString(4, packed);
        statement.setBoolean(5, builtin);
        statement.setString(6, cap(color, 16));
        try {
          statement.executeUpdate();
        } catch (SQLException ex) {
          // two boots racing to seed is not an error worth failing a start over
          if (byName(name) == null) {
            throw ex;
          }
        }
      }
    }
  }

  static String pack(Set<Permission> permissions) {
    ArrayNode array = JSON.createArrayNode();
    for (Permission permission : Permission.values()) {
      if (permissions.contains(permission)) {
        array.add(permission.name());
      }
    }
    return array.toString();
  }

  static Set<Permission> unpack(String packed) {
    EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
    if (packed == null || packed.isBlank()) {
      return permissions;
    }
    try {
      JsonNode node = JSON.readTree(packed);
      if (node.isArray()) {
        for (JsonNode item : node) {
          Permission permission = Permission.of(item.asText());
          if (permission != null) {
            permissions.add(permission);
          }
        }
      }
    } catch (Exception ex) {
      // an unreadable blob is a role that grants nothing, which is the safe direction
      return EnumSet.noneOf(Permission.class);
    }
    return permissions;
  }

  /** lowercase, letters digits and dashes; the name is used in URLs and in grants */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String clean = raw.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-").replaceAll("-+", "-");
    clean = clean.replaceAll("^-|-$", "");
    return clean.isEmpty() || clean.length() > 64 ? null : clean;
  }

  private static Def read(ResultSet rows) throws SQLException {
    return new Def(rows.getLong("id"), rows.getString("name"), rows.getString("label"),
        rows.getString("description"), unpack(rows.getString("permissions")),
        rows.getBoolean("builtin"), rows.getString("color"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
