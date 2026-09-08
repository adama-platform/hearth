package io.hearth.smtp;

import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * What arrived, what was decided, and what the far end said about it.
 *
 * <b>This is the inspection surface</b>, and the reason it exists is that a forwarder is invisible
 * when it works and unfalsifiable when it does not. "I never got your email" has about six causes
 * -- a rule that did not match, a rule that matched the wrong thing, a DMARC refusal at this end, a
 * DMARC refusal at Google's, a broken chain, a bounced delivery -- and without a record of each
 * message they are indistinguishable from each other and from the mail never having been sent.
 *
 * <b>Metadata and a short preview, not the message.</b> A forwarder that keeps copies is a mail
 * store that nobody agreed to run, on a box whose backups nobody thought about. What is kept is
 * what answers the question: the envelope, the three verdicts as they were on arrival, the rule
 * that matched, where it went and what came back. The preview exists because a rule that matched
 * the wrong message is only obvious next to the message, and it is capped at two kilobytes for the
 * same reason the rest is metadata.
 *
 * <b>Pruned on write.</b> A log that grows without bound is a disk that fills at three in the
 * morning, and this table gets a row per message per recipient forever otherwise.
 */
public class MailLog {
  /** how many rows one domain keeps; a few weeks of one person's mail */
  public static final int KEPT = 2000;
  /** how much of the message rides along, for recognising it rather than reading it */
  public static final int PREVIEW_CHARS = 2000;

  private final Store store;

  public MailLog(Store store) {
    this.store = store;
  }

  /** what happened to one message for one recipient */
  public enum Outcome {
    /** handed to the destination's mail exchanger, which took responsibility for it */
    forwarded,
    /** kept here, in the mailbox of whoever owns the address it was sent to */
    delivered,
    /** a rule said to drop it, and it was accepted and let go */
    dropped,
    /** refused at the door, before it was accepted; the sender was told */
    refused,
    /** the far end said "not now", and the sending server was told exactly that */
    deferred,
    /** the far end refused it, and the sending server was told exactly that */
    failed,
    /** nothing matched, so nothing was said about it */
    unrouted
  }

  public record Entry(long id, Timestamp receivedAt, String domain, String envelopeFrom,
                      String recipient, String headerFrom, String subject, String messageId,
                      int sizeBytes, String remoteIp, String spf, String dkim, String dmarc,
                      String arc, Long ruleId, String ruleName, String action, String destination,
                      String relayHost, String tls, String outcome, String detail, int attempts,
                      String preview) {

    /** did the three checks say this message was who it claimed to be? */
    public boolean authenticated() {
      return "pass".equals(dmarc) || ("pass".equals(dkim) && "pass".equals(spf));
    }

    public boolean troubled() {
      return Outcome.failed.name().equals(outcome) || Outcome.refused.name().equals(outcome)
          || Outcome.unrouted.name().equals(outcome);
    }
  }

  /** a row to write; a builder rather than a twenty-argument call nobody can read at a glance */
  public static class Draft {
    String domain = "";
    String envelopeFrom = "";
    String recipient = "";
    String headerFrom = "";
    String subject = "";
    String messageId = "";
    int sizeBytes;
    String remoteIp = "";
    String spf = "none";
    String dkim = "none";
    String dmarc = "none";
    String arc = "";
    Long ruleId;
    String ruleName = "";
    String action = "";
    String destination = "";
    String relayHost = "";
    String tls = "";
    String outcome = "";
    String detail = "";
    int attempts;
    String preview = "";

    public Draft domain(String value) {
      this.domain = orEmpty(value);
      return this;
    }

    public Draft envelope(String from, String to) {
      this.envelopeFrom = orEmpty(from);
      this.recipient = orEmpty(to);
      return this;
    }

    public Draft message(String headerFrom, String subject, String messageId, int bytes) {
      this.headerFrom = cut(headerFrom, 320);
      this.subject = cut(subject, 512);
      this.messageId = cut(messageId, 255);
      this.sizeBytes = bytes;
      return this;
    }

    public Draft from(String ip) {
      this.remoteIp = cut(ip, 64);
      return this;
    }

    public Draft checks(AuthResult result) {
      this.spf = result.spf().name();
      this.dkim = result.dkim().name();
      this.dmarc = result.dmarc().name();
      return this;
    }

    public Draft arc(String state) {
      this.arc = cut(state, 32);
      return this;
    }

    public Draft rule(Mailboxes.Rule rule) {
      if (rule != null) {
        this.ruleId = rule.id();
        this.ruleName = cut(rule.name().isBlank() ? rule.describe() : rule.name(), 160);
        this.action = rule.action().name();
        this.destination = cut(rule.forwardTo(), 320);
      }
      return this;
    }

    public Draft delivery(Relay.Sent sent) {
      if (sent != null) {
        this.relayHost = cut(sent.host(), 255);
        this.tls = cut(sent.tls(), 32);
        this.detail = cut(sent.detail(), 1024);
      }
      return this;
    }

    public Draft outcome(Outcome outcome, String detail) {
      this.outcome = outcome.name();
      if (detail != null && !detail.isBlank()) {
        this.detail = cut(detail, 1024);
      }
      return this;
    }

    public Draft attempts(int count) {
      this.attempts = count;
      return this;
    }

    public Draft preview(String text) {
      this.preview = cut(text, PREVIEW_CHARS);
      return this;
    }

    private static String orEmpty(String value) {
      return value == null ? "" : value;
    }

    private static String cut(String value, int limit) {
      String clean = orEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
      return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
  }

  /**
   * Write one row and prune the domain back to its ceiling.
   *
   * The prune is here rather than on a timer because this is the only thing that makes the table
   * grow, and a sweeper that runs hourly is a thread and a schedule to hold in your head for
   * something a `DELETE` in the same transaction does exactly.
   */
  public long record(Draft draft) throws SQLException {
    long id;
    try (Connection connection = store.connection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO " + Schema.MAIL_LOG + " (domain, envelope_from, recipient, header_from,"
              + " subject, message_id, size_bytes, remote_ip, spf, dkim, dmarc, arc, rule_id,"
              + " rule_name, action, destination, relay_host, tls, outcome, detail, attempts,"
              + " preview) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          java.sql.Statement.RETURN_GENERATED_KEYS)) {
        int at = 1;
        statement.setString(at++, draft.domain);
        statement.setString(at++, draft.envelopeFrom);
        statement.setString(at++, draft.recipient);
        statement.setString(at++, draft.headerFrom);
        statement.setString(at++, draft.subject);
        statement.setString(at++, draft.messageId);
        statement.setInt(at++, draft.sizeBytes);
        statement.setString(at++, draft.remoteIp);
        statement.setString(at++, draft.spf);
        statement.setString(at++, draft.dkim);
        statement.setString(at++, draft.dmarc);
        statement.setString(at++, draft.arc);
        if (draft.ruleId == null) {
          statement.setNull(at++, java.sql.Types.BIGINT);
        } else {
          statement.setLong(at++, draft.ruleId);
        }
        statement.setString(at++, draft.ruleName);
        statement.setString(at++, draft.action);
        statement.setString(at++, draft.destination);
        statement.setString(at++, draft.relayHost);
        statement.setString(at++, draft.tls);
        statement.setString(at++, draft.outcome);
        statement.setString(at++, draft.detail);
        statement.setInt(at++, draft.attempts);
        statement.setString(at, draft.preview);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          id = keys.next() ? keys.getLong(1) : 0;
        }
      }
      prune(connection, draft.domain);
    }
    return id;
  }

  private void prune(Connection connection, String domain) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM " + Schema.MAIL_LOG + " WHERE domain = ? AND id NOT IN"
            + " (SELECT id FROM " + Schema.MAIL_LOG + " WHERE domain = ? ORDER BY id DESC "
            + store.dialect().limit(KEPT) + ")")) {
      statement.setString(1, domain);
      statement.setString(2, domain);
      statement.executeUpdate();
    }
  }

  /** the most recent messages for a domain, newest first */
  public List<Entry> recent(String domain, int limit) throws SQLException {
    return query("SELECT * FROM " + Schema.MAIL_LOG + " WHERE domain = ? ORDER BY id DESC "
        + store.dialect().limit(Math.max(1, Math.min(limit, 500))), domain);
  }

  /** only what went wrong, which is the list somebody actually opens this screen for */
  public List<Entry> troubles(String domain, int limit) throws SQLException {
    return query("SELECT * FROM " + Schema.MAIL_LOG + " WHERE domain = ? AND outcome IN"
        + " ('failed', 'refused', 'unrouted', 'deferred') ORDER BY id DESC "
        + store.dialect().limit(Math.max(1, Math.min(limit, 500))), domain);
  }

  public Entry byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAIL_LOG + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  private List<Entry> query(String sql, String domain) throws SQLException {
    ArrayList<Entry> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, domain == null ? "" : domain.toLowerCase(java.util.Locale.ROOT));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(read(found));
        }
      }
    }
    return rows;
  }

  /**
   * Take one person's address out of the log entirely.
   *
   * <b>Deleted rather than blanked, and that is the difference from every other erasure here.</b>
   * A page keeps its words and loses its author because the words are other people's conversation;
   * a log row is nothing *but* who wrote to whom, so there is no residue worth keeping and an
   * anonymised row would still say that somebody at this address received mail on a Tuesday.
   */
  public int forget(String email) throws SQLException {
    if (email == null || email.isBlank()) {
      return 0;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.MAIL_LOG + " WHERE LOWER(envelope_from) = ?"
                 + " OR LOWER(recipient) = ? OR LOWER(header_from) = ?"
                 + " OR LOWER(destination) = ?")) {
      String clean = email.trim().toLowerCase(java.util.Locale.ROOT);
      statement.setString(1, clean);
      statement.setString(2, clean);
      statement.setString(3, clean);
      statement.setString(4, clean);
      return statement.executeUpdate();
    }
  }

  private static Entry read(ResultSet found) throws SQLException {
    long rule = found.getLong("rule_id");
    boolean noRule = found.wasNull();
    return new Entry(found.getLong("id"), found.getTimestamp("received_at"),
        found.getString("domain"), found.getString("envelope_from"), found.getString("recipient"),
        found.getString("header_from"), found.getString("subject"), found.getString("message_id"),
        found.getInt("size_bytes"), found.getString("remote_ip"), found.getString("spf"),
        found.getString("dkim"), found.getString("dmarc"), found.getString("arc"),
        noRule ? null : rule, found.getString("rule_name"), found.getString("action"),
        found.getString("destination"), found.getString("relay_host"), found.getString("tls"),
        found.getString("outcome"), found.getString("detail"), found.getInt("attempts"),
        found.getString("preview"));
  }
}
