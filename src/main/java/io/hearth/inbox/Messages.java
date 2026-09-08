package io.hearth.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Locale;

/**
 * Somebody's mail, and the two questions a zero-inbox screen asks of it.
 *
 * <b>"What is in the inbox" is one query and it is the only one on the front screen.</b> Everything
 * else -- read, replied, archived -- is a timestamp rather than a state, because "when did I read
 * this" is a question people ask and a status column cannot answer. Archived is what takes a
 * message out of the inbox, and it is the column the listing filters on.
 *
 * <b>There is no folder.</b> A message is in the inbox or it is not. Folders are how an inbox
 * becomes a filing system nobody maintains, and the whole design here is that the list is meant to
 * reach zero every day: read it, then reply or delete it, and either way it leaves.
 *
 * <b>Delete means delete.</b> Not a flag, not a bin: the row goes and the file on disk goes with
 * it. A deleted-items folder that fills up for years is a copy of everything you decided you did
 * not want, sitting on a machine in your house.
 */
public class Messages {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Store store;

  public Messages(Store store) {
    this.store = store;
  }

  /** one part of a message, as the screen lists it */
  public record Attachment(String path, String filename, String contentType, int size,
                           boolean allowed, String reason, int width, int height) {
    public String describeSize() {
      if (size < 1024) {
        return size + " B";
      }
      if (size < 1024 * 1024) {
        return Math.round(size / 1024.0) + " KB";
      }
      return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }

    public boolean isImage() {
      return allowed && contentType.startsWith("image/");
    }
  }

  public record Record(long id, long userId, Long mailboxId, String domain, String deliveredTo,
                       Timestamp receivedAt, String envelopeFrom, String fromName,
                       String fromAddress, String toHeader, String ccHeader, String replyTo,
                       String subject, String messageId, String inReplyTo, String references,
                       String spf, String dkim, String dmarc, int sizeBytes, String textBody,
                       String htmlBody, String partsJson, int attachments, boolean hasCalendar,
                       int blockedRemote, Timestamp readAt, Timestamp archivedAt,
                       Timestamp repliedAt) {

    public boolean unread() {
      return readAt == null;
    }

    public boolean archived() {
      return archivedAt != null;
    }

    public boolean replied() {
      return repliedAt != null;
    }

    /** did the domain in the From header vouch for this? */
    public boolean authenticated() {
      return "pass".equals(dmarc) || "pass".equals(dkim) && "pass".equals(spf);
    }

    public String who() {
      return fromName == null || fromName.isBlank() ? fromAddress : fromName;
    }

    public String subjectOr() {
      return subject == null || subject.isBlank() ? "(no subject)" : subject;
    }

    public List<Attachment> parts() {
      ArrayList<Attachment> out = new ArrayList<>();
      try {
        for (JsonNode node : JSON.readTree(partsJson == null || partsJson.isBlank()
            ? "[]" : partsJson)) {
          out.add(new Attachment(node.path("path").asText(), node.path("name").asText(),
              node.path("type").asText(), node.path("size").asInt(),
              node.path("ok").asBoolean(false), node.path("why").asText(""),
              node.path("w").asInt(0), node.path("h").asInt(0)));
        }
      } catch (Exception ex) {
        // a manifest that will not parse is a message with no listed parts; the raw download is
        // still there, which is the escape hatch that makes this survivable
        return List.of();
      }
      return out;
    }

    /** a line for a listing: the first words of the message, whichever half carried them */
    public String preview(int limit) {
      String text = textBody == null || textBody.isBlank()
          ? io.hearth.web.Html.text(htmlBody) : textBody;
      String flat = text.replaceAll("\\s+", " ").trim();
      return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
  }

  /** what a delivery hands over; a builder, because a message has more fields than anybody reads */
  public static class Draft {
    long userId;
    Long mailboxId;
    String domain = "";
    String deliveredTo = "";
    String envelopeFrom = "";
    String fromName = "";
    String fromAddress = "";
    String toHeader = "";
    String ccHeader = "";
    String replyTo = "";
    String subject = "";
    String messageId = "";
    String inReplyTo = "";
    String references = "";
    String spf = "none";
    String dkim = "none";
    String dmarc = "none";
    int sizeBytes;
    String textBody = "";
    String htmlBody = "";
    String partsJson = "[]";
    int attachments;
    boolean hasCalendar;
    int blockedRemote;

    public Draft to(long userId, Long mailboxId, String domain, String address) {
      this.userId = userId;
      this.mailboxId = mailboxId;
      this.domain = orEmpty(domain);
      this.deliveredTo = orEmpty(address).toLowerCase(Locale.ROOT);
      return this;
    }

    public Draft envelope(String from) {
      this.envelopeFrom = cut(from, 320);
      return this;
    }

    public Draft sender(String name, String address) {
      this.fromName = cut(name, 255);
      this.fromAddress = cut(address, 320).toLowerCase(Locale.ROOT);
      return this;
    }

    public Draft recipients(String to, String cc, String replyTo) {
      this.toHeader = cut(to, 2048);
      this.ccHeader = cut(cc, 2048);
      this.replyTo = cut(replyTo, 320);
      return this;
    }

    public Draft about(String subject, String messageId, String inReplyTo, String references) {
      this.subject = cut(subject, 1024);
      this.messageId = cut(messageId, 512);
      this.inReplyTo = cut(inReplyTo, 512);
      this.references = cut(references, 4096);
      return this;
    }

    public Draft checks(String spf, String dkim, String dmarc) {
      this.spf = spf;
      this.dkim = dkim;
      this.dmarc = dmarc;
      return this;
    }

    public Draft body(String text, String html, int blockedRemote) {
      this.textBody = clip(text, 524_288);
      this.htmlBody = clip(html, 524_288);
      this.blockedRemote = blockedRemote;
      return this;
    }

    public Draft size(int bytes) {
      this.sizeBytes = bytes;
      return this;
    }

    public Draft parts(List<Attachment> listed, boolean hasCalendar) {
      ArrayNode array = JSON.createArrayNode();
      int count = 0;
      for (Attachment one : listed) {
        ObjectNode node = array.addObject();
        node.put("path", one.path());
        node.put("name", one.filename());
        node.put("type", one.contentType());
        node.put("size", one.size());
        node.put("ok", one.allowed());
        if (!one.reason().isBlank()) {
          node.put("why", one.reason());
        }
        if (one.width() > 0) {
          node.put("w", one.width());
          node.put("h", one.height());
        }
        count++;
      }
      this.partsJson = clip(array.toString(), 65_536);
      this.attachments = count;
      this.hasCalendar = hasCalendar;
      return this;
    }

    private static String orEmpty(String value) {
      return value == null ? "" : value;
    }

    /** a header value, flattened and cut; nothing from a message reaches a column at full length */
    private static String cut(String value, int limit) {
      String clean = orEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
      return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    /** a body, cut but not flattened: the newlines are the message */
    private static String clip(String value, int limit) {
      String clean = orEmpty(value);
      return clean.length() <= limit ? clean
          : clean.substring(0, limit) + "\n\n[this message was longer than this server stores]";
    }
  }

  // ---- writing -----------------------------------------------------------------------------------

  public long save(Draft draft) throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.MAIL_MESSAGES + " (user_id, mailbox_id, domain, delivered_to,"
                 + " envelope_from, from_name, from_address, to_header, cc_header, reply_to,"
                 + " subject, message_id, in_reply_to, references_header, spf, dkim, dmarc,"
                 + " size_bytes, text_body, html_body, parts, attachments, has_calendar,"
                 + " blocked_remote) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                 + " ?, ?, ?, ?, ?, ?, ?)",
             java.sql.Statement.RETURN_GENERATED_KEYS)) {
      int at = 1;
      statement.setLong(at++, draft.userId);
      if (draft.mailboxId == null) {
        statement.setNull(at++, java.sql.Types.BIGINT);
      } else {
        statement.setLong(at++, draft.mailboxId);
      }
      statement.setString(at++, draft.domain);
      statement.setString(at++, draft.deliveredTo);
      statement.setString(at++, draft.envelopeFrom);
      statement.setString(at++, draft.fromName);
      statement.setString(at++, draft.fromAddress);
      statement.setString(at++, draft.toHeader);
      statement.setString(at++, draft.ccHeader);
      statement.setString(at++, draft.replyTo);
      statement.setString(at++, draft.subject);
      statement.setString(at++, draft.messageId);
      statement.setString(at++, draft.inReplyTo);
      statement.setString(at++, draft.references);
      statement.setString(at++, draft.spf);
      statement.setString(at++, draft.dkim);
      statement.setString(at++, draft.dmarc);
      statement.setInt(at++, draft.sizeBytes);
      statement.setString(at++, draft.textBody);
      statement.setString(at++, draft.htmlBody);
      statement.setString(at++, draft.partsJson);
      statement.setInt(at++, draft.attachments);
      statement.setBoolean(at++, draft.hasCalendar);
      statement.setInt(at, draft.blockedRemote);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        id = keys.next() ? keys.getLong(1) : 0;
      }
    }
    store.changed(Schema.MAIL_MESSAGES, id, MutationEvent.Kind.insert, draft.userId);
    return id;
  }

  /**
   * Put the message id into the inline image URLs, once it exists.
   *
   * The only write to a body after delivery, and it happens for a message carrying an inline image
   * and for nothing else. See {@link Delivery#ID_MARKER} for why it cannot be done in one step.
   */
  public void rewriteHtml(long id, long userId, String html) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.MAIL_MESSAGES + " SET html_body = ? WHERE id = ? AND user_id = ?")) {
      statement.setString(1, html);
      statement.setLong(2, id);
      statement.setLong(3, userId);
      statement.executeUpdate();
    }
  }

  /** mark it read; the first read is the one recorded, so "when did I see this" stays true */
  public void read(long id, long userId) throws SQLException {
    stamp(id, userId, "read_at", true);
  }

  /** out of the inbox */
  public void archive(long id, long userId) throws SQLException {
    stamp(id, userId, "archived_at", false);
  }

  /** back into it, for the one somebody archived by accident */
  public void unarchive(long id, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.MAIL_MESSAGES + " SET archived_at = NULL"
                 + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.MAIL_MESSAGES, id, MutationEvent.Kind.update, userId);
  }

  public void replied(long id, long userId) throws SQLException {
    stamp(id, userId, "replied_at", false);
    archive(id, userId);
  }

  /**
   * Every write carries the user id in the WHERE clause.
   *
   * Not because the handler forgot to check -- it did check -- but because this is the one place
   * where forgetting is invisible. A row belonging to somebody else silently updated by an id in a
   * form is the oldest shape of this bug, and the clause costs nothing.
   */
  private void stamp(long id, long userId, String column, boolean onlyIfNull) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.MAIL_MESSAGES + " SET " + column + " = ?"
                 + " WHERE id = ? AND user_id = ?"
                 + (onlyIfNull ? " AND " + column + " IS NULL" : ""))) {
      statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
      statement.setLong(2, id);
      statement.setLong(3, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.MAIL_MESSAGES, id, MutationEvent.Kind.update, userId);
  }

  /** gone, and the caller deletes the file */
  public boolean delete(long id, long userId) throws SQLException {
    int gone;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.MAIL_MESSAGES + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      gone = statement.executeUpdate();
    }
    if (gone > 0) {
      store.changed(Schema.MAIL_MESSAGES, id, MutationEvent.Kind.delete, userId);
    }
    return gone > 0;
  }

  // ---- reading -----------------------------------------------------------------------------------

  /** what is in the inbox, newest first */
  public List<Record> inbox(long userId, int limit) throws SQLException {
    return query("SELECT * FROM " + Schema.MAIL_MESSAGES
        + " WHERE user_id = ? AND archived_at IS NULL ORDER BY id DESC "
        + store.dialect().limit(bounded(limit)), userId);
  }

  /** everything, for when somebody is looking for a message they have already dealt with */
  public List<Record> all(long userId, int limit) throws SQLException {
    return query("SELECT * FROM " + Schema.MAIL_MESSAGES + " WHERE user_id = ? ORDER BY id DESC "
        + store.dialect().limit(bounded(limit)), userId);
  }

  /** what somebody has already dealt with, for finding a message again */
  public List<Record> archive(long userId, int limit) throws SQLException {
    return query("SELECT * FROM " + Schema.MAIL_MESSAGES
        + " WHERE user_id = ? AND archived_at IS NOT NULL ORDER BY id DESC "
        + store.dialect().limit(bounded(limit)), userId);
  }

  /**
   * A search over the fields a person actually remembers.
   *
   * Sender, subject and the plain text. Not the HTML: searching markup finds a message because it
   * happens to contain the word "table" in a style attribute, which is a result nobody can explain.
   */
  public List<Record> search(long userId, String needle, int limit) throws SQLException {
    String like = "%" + needle.trim().toLowerCase(Locale.ROOT) + "%";
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAIL_MESSAGES + " WHERE user_id = ? AND ("
                 + " LOWER(from_address) LIKE ? OR LOWER(from_name) LIKE ?"
                 + " OR LOWER(subject) LIKE ? OR LOWER(text_body) LIKE ?)"
                 + " ORDER BY id DESC " + store.dialect().limit(bounded(limit)))) {
      statement.setLong(1, userId);
      statement.setString(2, like);
      statement.setString(3, like);
      statement.setString(4, like);
      statement.setString(5, like);
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(read(found));
        }
      }
    }
    return rows;
  }

  /**
   * One message, and it belongs to this person or it does not exist.
   *
   * The user id is part of the lookup rather than checked afterwards, so there is no path through
   * this class that returns somebody else's mail -- which is invariant 75 applied to the one table
   * where getting it wrong would be worst.
   */
  public Record byId(long id, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAIL_MESSAGES + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  public int unreadCount(long userId) throws SQLException {
    return count("SELECT COUNT(*) FROM " + Schema.MAIL_MESSAGES
        + " WHERE user_id = ? AND archived_at IS NULL AND read_at IS NULL", userId);
  }

  public int inboxCount(long userId) throws SQLException {
    return count("SELECT COUNT(*) FROM " + Schema.MAIL_MESSAGES
        + " WHERE user_id = ? AND archived_at IS NULL", userId);
  }

  /** every message id this person has, so an erasure can take the files with the rows */
  public List<Long> idsFor(long userId) throws SQLException {
    ArrayList<Long> ids = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT id FROM " + Schema.MAIL_MESSAGES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          ids.add(found.getLong("id"));
        }
      }
    }
    return ids;
  }

  /** everything of one person's, gone. The caller deletes the files first. */
  public int forget(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.MAIL_MESSAGES + " WHERE user_id = ?")) {
      statement.setLong(1, userId);
      return statement.executeUpdate();
    }
  }

  private int count(String sql, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? found.getInt(1) : 0;
      }
    }
  }

  private List<Record> query(String sql, long userId) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(read(found));
        }
      }
    }
    return rows;
  }

  private static int bounded(int limit) {
    return Math.max(1, Math.min(limit, 500));
  }

  private static Record read(ResultSet found) throws SQLException {
    long mailbox = found.getLong("mailbox_id");
    boolean noMailbox = found.wasNull();
    return new Record(found.getLong("id"), found.getLong("user_id"), noMailbox ? null : mailbox,
        found.getString("domain"), found.getString("delivered_to"),
        found.getTimestamp("received_at"), found.getString("envelope_from"),
        found.getString("from_name"), found.getString("from_address"),
        found.getString("to_header"), found.getString("cc_header"), found.getString("reply_to"),
        found.getString("subject"), found.getString("message_id"), found.getString("in_reply_to"),
        found.getString("references_header"), found.getString("spf"), found.getString("dkim"),
        found.getString("dmarc"), found.getInt("size_bytes"), found.getString("text_body"),
        found.getString("html_body"), found.getString("parts"), found.getInt("attachments"),
        found.getBoolean("has_calendar"), found.getInt("blocked_remote"),
        found.getTimestamp("read_at"), found.getTimestamp("archived_at"),
        found.getTimestamp("replied_at"));
  }
}
