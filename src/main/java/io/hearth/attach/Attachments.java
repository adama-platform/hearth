package io.hearth.attach;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The record of everything uploaded: what it is, where it sits, and who may read it.
 *
 * The bytes are not here -- see {@link AttachmentStore}. What is here is everything a page needs to
 * decide whether to serve them, and everything a person needs to find one again six months later:
 * a folder, some tags, and a sentence about what it is a picture of.
 *
 * <b>Folders are a string, not a tree.</b> "suppers/2026-05" is a path with no table behind it,
 * which means moving a folder is an update on a prefix and an empty folder simply stops existing.
 * A table of folders would be a second thing to keep in step with the one that matters, and the
 * first time they disagreed somebody would have files in a folder nothing lists.
 *
 * <b>Tags are words in a column.</b> Searched with a `LIKE` prefilter and then checked properly in
 * Java, the same way a place's declared fields are -- because `LIKE '%cake%'` matches a tag called
 * "cheesecake" and somebody looking for cake pictures does not want the difference explained to
 * them by a search that quietly includes it.
 */
public class Attachments {
  private static final String COLUMNS =
      "id, uuid, filename, extension, mime, kind, bytes, digest, storage, folder, tags,"
          + " description, public, created_at, updated_at, uploaded_by, uploaded_by_email";
  /** the most rows any listing returns; the scale target is a design input */
  public static final int CEILING = 2000;

  private final Store store;

  public Attachments(Store store) {
    this.store = store;
  }

  /** one upload */
  public record Attachment(long id, String uuid, String filename, String extension, String mime,
                           Kinds.Kind kind, long bytes, String digest, String storage,
                           String folder, String tags, String description, boolean isPublic,
                           Timestamp createdAt, Timestamp updatedAt, Long uploadedBy,
                           String uploadedByEmail) {
    /** the address it is served at */
    public String url() {
      return "/attachment/" + id + "." + extension;
    }

    public boolean embeddable() {
      Kinds.Type type = Kinds.of(extension);
      return type != null && type.embeddable();
    }

    /** what to call it on a page: what somebody said it is, or what the file was called */
    public String display() {
      return description == null || description.isBlank() ? filename : description;
    }

    public List<String> tagList() {
      return words(tags);
    }

    public String size() {
      if (bytes < 1024) {
        return bytes + " B";
      }
      if (bytes < 1024 * 1024) {
        return Math.round(bytes / 1024.0) + " KB";
      }
      return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
  }

  /**
   * Write one down.
   *
   * The id is generated here and the caller writes the bytes afterwards, which is the right order:
   * a row with no file is a broken attachment somebody can see and delete, and a file with no row
   * is an orphan nothing will ever mention.
   */
  public Attachment create(String filename, String extension, String mime, Kinds.Kind kind,
                           long bytes, String digest, String storage, String folder, String tags,
                           String description, boolean isPublic, Long actor, String actorEmail)
      throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.ATTACHMENTS + " (uuid, filename, extension, mime, kind,"
                 + " bytes, digest, storage, folder, tags, description, public, uploaded_by,"
                 + " uploaded_by_email) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, java.util.UUID.randomUUID().toString());
      statement.setString(2, Kinds.safeName(filename));
      statement.setString(3, Kinds.clean(extension));
      statement.setString(4, cap(mime, 128));
      statement.setString(5, (kind == null ? Kinds.Kind.other : kind).name());
      statement.setLong(6, bytes);
      statement.setString(7, cap(digest, 64));
      statement.setString(8, cap(storage == null ? "disk" : storage, 16));
      statement.setString(9, folderOf(folder));
      statement.setString(10, tagsOf(tags));
      statement.setString(11, cap(description, 512));
      statement.setBoolean(12, isPublic);
      if (actor == null) {
        statement.setNull(13, java.sql.Types.BIGINT);
      } else {
        statement.setLong(13, actor);
      }
      statement.setString(14, cap(actorEmail, 320));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.ATTACHMENTS, id, MutationEvent.Kind.insert, actor);
    return byId(id);
  }

  public Attachment byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ATTACHMENTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public Attachment byUuid(String uuid) throws SQLException {
    if (uuid == null || uuid.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ATTACHMENTS + " WHERE uuid = ?")) {
      statement.setString(1, uuid.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  /** the same bytes uploaded twice is one file; the caller decides whether to care */
  public Attachment byDigest(String digest) throws SQLException {
    if (digest == null || digest.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ATTACHMENTS + " WHERE digest = ?"
                 + " ORDER BY id " + store.dialect().limit(1))) {
      statement.setString(1, digest.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? read(rows) : null;
      }
    }
  }

  public List<Attachment> all(int limit) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ATTACHMENTS + " ORDER BY id DESC "
                 + store.dialect().limit(Math.min(limit, CEILING)))) {
      return readAll(statement);
    }
  }

  /**
   * Everything matching a folder, a kind and some words.
   *
   * The SQL narrows and Java decides. A `LIKE '%cake%'` on the tags matches "cheesecake", which is
   * not what anybody typing "cake" meant, and explaining that to somebody looking for photographs
   * of a cake is not a thing a search should make necessary.
   */
  public List<Attachment> search(String folder, String kind, String query, int limit)
      throws SQLException {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM " + Schema.ATTACHMENTS
        + " WHERE 1 = 1");
    ArrayList<Object> arguments = new ArrayList<>();
    if (folder != null && !folder.isBlank()) {
      // a folder covers what is under it: "suppers" finds "suppers/2026-05"
      sql.append(" AND (folder = ? OR folder LIKE ?)");
      arguments.add(folderOf(folder));
      arguments.add(folderOf(folder) + "/%");
    }
    if (kind != null && !kind.isBlank() && !kind.equals("all")) {
      sql.append(" AND kind = ?");
      arguments.add(kind);
    }
    String[] words = query == null ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\\s+");
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      sql.append(" AND (LOWER(filename) LIKE ? OR LOWER(description) LIKE ? OR LOWER(tags) LIKE ?)");
      arguments.add("%" + word + "%");
      arguments.add("%" + word + "%");
      arguments.add("%" + word + "%");
    }
    sql.append(" ORDER BY id DESC ").append(store.dialect().limit(Math.min(limit, CEILING)));
    ArrayList<Attachment> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      for (int k = 0; k < arguments.size(); k++) {
        statement.setString(k + 1, String.valueOf(arguments.get(k)));
      }
      found.addAll(readAll(statement));
    }
    return found;
  }

  /** every folder anything is in, so the picker can offer them */
  public List<String> folders() throws SQLException {
    LinkedHashSet<String> folders = new LinkedHashSet<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT DISTINCT folder FROM " + Schema.ATTACHMENTS + " WHERE folder <> ''"
                 + " ORDER BY folder");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String folder = rows.getString(1);
        folders.add(folder);
        // every parent of a folder is a folder, even when nothing sits directly in it
        int at = folder.lastIndexOf('/');
        while (at > 0) {
          folders.add(folder.substring(0, at));
          at = folder.lastIndexOf('/', at - 1);
        }
      }
    }
    ArrayList<String> sorted = new ArrayList<>(folders);
    sorted.sort(String::compareTo);
    return sorted;
  }

  /** every tag anybody has used, most-used first */
  public List<String> tags(int limit) throws SQLException {
    java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
    for (Attachment attachment : all(CEILING)) {
      for (String tag : attachment.tagList()) {
        counts.merge(tag, 1, Integer::sum);
      }
    }
    ArrayList<String> sorted = new ArrayList<>(counts.keySet());
    sorted.sort((left, right) -> {
      int byCount = Integer.compare(counts.get(right), counts.get(left));
      return byCount != 0 ? byCount : left.compareTo(right);
    });
    return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
  }

  /** edit what somebody can change about one: where it is, what it is called, who may see it */
  public Attachment update(long id, String folder, String tags, String description,
                           boolean isPublic, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.ATTACHMENTS + " SET folder = ?, tags = ?, description = ?,"
                 + " public = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, folderOf(folder));
      statement.setString(2, tagsOf(tags));
      statement.setString(3, cap(description, 512));
      statement.setBoolean(4, isPublic);
      statement.setLong(5, id);
      statement.executeUpdate();
    }
    store.changed(Schema.ATTACHMENTS, id, MutationEvent.Kind.update, actor);
    return byId(id);
  }

  /** move everything in a folder, and everything under it */
  public int moveFolder(String from, String to, Long actor) throws SQLException {
    String source = folderOf(from);
    String target = folderOf(to);
    if (source.isEmpty() || source.equals(target)) {
      return 0;
    }
    int moved;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.ATTACHMENTS + " SET folder = ?, updated_at = CURRENT_TIMESTAMP"
                 + " WHERE folder = ?")) {
      statement.setString(1, target);
      statement.setString(2, source);
      moved = statement.executeUpdate();
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.ATTACHMENTS + " SET folder = ? || SUBSTRING(folder, ?),"
                 + " updated_at = CURRENT_TIMESTAMP WHERE folder LIKE ?")) {
      statement.setString(1, target);
      statement.setInt(2, source.length() + 1);
      statement.setString(3, source + "/%");
      moved += statement.executeUpdate();
    }
    store.changed(Schema.ATTACHMENTS, 0, MutationEvent.Kind.update, actor);
    return moved;
  }

  public void delete(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.ATTACHMENTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.ATTACHMENTS, id, MutationEvent.Kind.delete, actor);
  }

  /**
   * Somebody is leaving.
   *
   * The files stay and the name comes off, which is the rule the board already follows: a
   * photograph of last summer is part of what the community remembers, and cutting one person out
   * of it leaves holes in everybody else's memory of a Tuesday. Taking their uploads down as well
   * is a separate decision, offered to an administrator.
   */
  public int forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.ATTACHMENTS + " SET uploaded_by = NULL, uploaded_by_email = ''"
                 + " WHERE uploaded_by = ?")) {
      statement.setLong(1, userId);
      return statement.executeUpdate();
    }
  }

  /** everything one person uploaded, for the export and for an administrator taking it down */
  public List<Attachment> uploadedBy(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM " + Schema.ATTACHMENTS + " WHERE uploaded_by = ?"
                 + " ORDER BY id DESC " + store.dialect().limit(CEILING))) {
      statement.setLong(1, userId);
      return readAll(statement);
    }
  }

  public long count() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM " + Schema.ATTACHMENTS);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  public long totalBytes() throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COALESCE(SUM(bytes), 0) FROM " + Schema.ATTACHMENTS);
         ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  // ---- plumbing ------------------------------------------------------------------------------

  /** a folder path with nothing in it that means anything to a filesystem */
  public static String folderOf(String raw) {
    if (raw == null) {
      return "";
    }
    ArrayList<String> parts = new ArrayList<>();
    for (String piece : raw.trim().toLowerCase(Locale.ROOT).split("/")) {
      StringBuilder clean = new StringBuilder();
      for (char ch : piece.toCharArray()) {
        if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
          clean.append(ch);
        } else if (ch == ' ') {
          clean.append('-');
        }
      }
      String part = clean.toString();
      if (!part.isEmpty() && parts.size() < 8) {
        parts.add(part.length() > 40 ? part.substring(0, 40) : part);
      }
    }
    return String.join("/", parts);
  }

  /** tags as a normalised, space-separated string */
  public static String tagsOf(String raw) {
    LinkedHashSet<String> tags = new LinkedHashSet<>(words(raw));
    StringBuilder out = new StringBuilder();
    for (String tag : tags) {
      if (out.length() + tag.length() + 1 > 512) {
        break;
      }
      out.append(out.length() == 0 ? "" : " ").append(tag);
    }
    return out.toString();
  }

  static List<String> words(String raw) {
    ArrayList<String> words = new ArrayList<>();
    if (raw == null) {
      return words;
    }
    for (String piece : raw.trim().toLowerCase(Locale.ROOT).split("[,\\s]+")) {
      StringBuilder clean = new StringBuilder();
      for (char ch : piece.toCharArray()) {
        if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
          clean.append(ch);
        }
      }
      String word = clean.toString();
      if (!word.isEmpty() && word.length() <= 40 && !words.contains(word)) {
        words.add(word);
      }
    }
    return words;
  }

  private static List<Attachment> readAll(PreparedStatement statement) throws SQLException {
    ArrayList<Attachment> all = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        all.add(read(rows));
      }
    }
    return all;
  }

  private static Attachment read(ResultSet rows) throws SQLException {
    Long by = rows.getLong("uploaded_by");
    if (rows.wasNull()) {
      by = null;
    }
    Kinds.Kind kind;
    try {
      kind = Kinds.Kind.valueOf(rows.getString("kind"));
    } catch (IllegalArgumentException ex) {
      kind = Kinds.Kind.other;
    }
    return new Attachment(rows.getLong("id"), rows.getString("uuid"), rows.getString("filename"),
        rows.getString("extension"), rows.getString("mime"), kind, rows.getLong("bytes"),
        rows.getString("digest"), rows.getString("storage"), rows.getString("folder"),
        rows.getString("tags"), rows.getString("description"), rows.getBoolean("public"),
        rows.getTimestamp("created_at"), rows.getTimestamp("updated_at"), by,
        rows.getString("uploaded_by_email"));
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
