package io.hearth.smtp;

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
 * The addresses that exist here, and the ordered rules that say what happens to mail for them.
 *
 * <b>A mailbox is a place, not an account.</b> Making `jeff@` a place is a separate act from
 * deciding what arrives there, and keeping them separate is what lets one rule cover twelve
 * addresses and one address be covered by a rule somebody wrote for a sender. Nothing here holds
 * mail: a mailbox with no rule that matches it is an address that accepts and discards, which is
 * said out loud on the screen rather than left to be discovered.
 *
 * <b>The rules are ordered and the first match wins</b>, which is the only evaluation order a person
 * can hold in their head. The alternative -- every matching rule applies -- means two rules that
 * both say `forward` deliver two copies, and nobody notices until the second copy is somewhere
 * awkward.
 *
 * <b>Two actions, and the vocabulary is deliberately tiny.</b> `forward` sends it on; `drop` accepts
 * it and lets it go. What is missing is anything that *bounces*: once this server has said 250 the
 * sending server has been told the message arrived, and generating a rejection afterwards means
 * mailing a report to a return path that a spammer chose. See {@link Forwarding} for the whole of
 * that argument.
 */
public class Mailboxes {
  /** how many rules one domain may have; a list longer than this is not a rule set, it is a bug */
  public static final int MAX_RULES = 200;
  /** the catch-all, written out so somebody has to type it */
  public static final String EVERYONE = "*";

  private final Store store;

  public Mailboxes(Store store) {
    this.store = store;
  }

  /** one address that exists at a domain */
  public record Box(long id, String domain, String localPart, String label, Long userId,
                    boolean enabled, Timestamp updatedAt) {
    public String address() {
      return localPart + "@" + domain;
    }
  }

  /** what a rule does when it matches */
  public enum Action {
    /** send it on to somewhere else, by that domain's own MX */
    forward,
    /** accept it and let it go; the sender is told nothing, because it was accepted */
    drop;

    public static Action of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }

  /** one rule: what it matches, and what it then does */
  public record Rule(long id, String domain, int position, String name, String matchTo,
                     String matchFrom, String matchSubject, Action action, String forwardTo,
                     boolean enabled, Timestamp updatedAt, Long updatedBy) {

    /**
     * Does this rule apply to this message?
     *
     * Every condition that is set has to hold; a condition left blank is not a condition. The
     * recipient is matched on its local part alone, because the domain already decided which rule
     * set is being consulted -- matching the whole address here would mean writing the domain twice
     * and getting it wrong once.
     */
    public boolean matches(String localPart, String envelopeFrom, String subject) {
      if (!enabled) {
        return false;
      }
      if (!EVERYONE.equals(matchTo) && !matchTo.equalsIgnoreCase(localPart)) {
        return false;
      }
      if (!matchFrom.isBlank()
          && !lower(envelopeFrom).contains(matchFrom.toLowerCase(Locale.ROOT))) {
        return false;
      }
      return matchSubject.isBlank()
          || lower(subject).contains(matchSubject.toLowerCase(Locale.ROOT));
    }

    private static String lower(String value) {
      return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** one line for a screen or a log, in the order somebody reads it */
    public String describe() {
      StringBuilder out = new StringBuilder();
      out.append(EVERYONE.equals(matchTo) ? "anything" : matchTo + "@");
      if (!matchFrom.isBlank()) {
        out.append(" from ").append(matchFrom);
      }
      if (!matchSubject.isBlank()) {
        out.append(" about ").append(matchSubject);
      }
      out.append(action == Action.forward ? " -> " + forwardTo : " -> dropped");
      return out.toString();
    }
  }

  // ---- mailboxes --------------------------------------------------------------------------------

  public List<Box> boxes(String domain) throws SQLException {
    ArrayList<Box> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAILBOXES + " WHERE domain = ? ORDER BY local_part")) {
      statement.setString(1, normalize(domain));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(readBox(found));
        }
      }
    }
    return rows;
  }

  public Box box(String domain, String localPart) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAILBOXES + " WHERE domain = ? AND local_part = ?")) {
      statement.setString(1, normalize(domain));
      statement.setString(2, normalize(localPart));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? readBox(found) : null;
      }
    }
  }

  public Box boxById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAILBOXES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? readBox(found) : null;
      }
    }
  }

  public long saveBox(long id, String domain, String localPart, String label, Long userId,
                      boolean enabled, Long actor) throws SQLException {
    long saved;
    try (Connection connection = store.connection()) {
      if (id > 0) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + Schema.MAILBOXES + " SET local_part = ?, label = ?, user_id = ?,"
                + " enabled = ?, updated_at = ? WHERE id = ?")) {
          statement.setString(1, normalize(localPart));
          statement.setString(2, label == null ? "" : label);
          setNullable(statement, 3, userId);
          statement.setBoolean(4, enabled);
          statement.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
          statement.setLong(6, id);
          statement.executeUpdate();
        }
        saved = id;
      } else {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + Schema.MAILBOXES + " (domain, local_part, label, user_id, enabled)"
                + " VALUES (?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
          statement.setString(1, normalize(domain));
          statement.setString(2, normalize(localPart));
          statement.setString(3, label == null ? "" : label);
          setNullable(statement, 4, userId);
          statement.setBoolean(5, enabled);
          statement.executeUpdate();
          try (ResultSet keys = statement.getGeneratedKeys()) {
            saved = keys.next() ? keys.getLong(1) : 0;
          }
        }
      }
    }
    store.changed(Schema.MAILBOXES, saved,
        id > 0 ? MutationEvent.Kind.update : MutationEvent.Kind.insert, actor);
    return saved;
  }

  public void deleteBox(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.MAILBOXES + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.MAILBOXES, id, MutationEvent.Kind.delete, actor);
  }

  /**
   * Take somebody's name off every address that was theirs, without deleting the address.
   *
   * An erasure removes a person, and `receipts@` outliving them is correct -- the address is the
   * domain owner's, not the member's. What must not survive is the link, so this nulls the owner
   * rather than dropping the row.
   */
  public int forgetOwner(long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.MAILBOXES + " SET user_id = NULL WHERE user_id = ?")) {
      statement.setLong(1, userId);
      return statement.executeUpdate();
    }
  }

  // ---- rules ------------------------------------------------------------------------------------

  /**
   * Every rule for a domain, in the order they are consulted.
   *
   * Ties break on id so the order is total. Two rules at position 100 would otherwise come back in
   * whatever order the database felt like, and a rule set that reorders itself between restarts is
   * one nobody can debug.
   */
  public List<Rule> rules(String domain) throws SQLException {
    ArrayList<Rule> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAIL_RULES + " WHERE domain = ?"
                 + " ORDER BY position, id")) {
      statement.setString(1, normalize(domain));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          rows.add(readRule(found));
        }
      }
    }
    return rows;
  }

  public Rule ruleById(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.MAIL_RULES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? readRule(found) : null;
      }
    }
  }

  /**
   * The rule that decides one message, or null when nothing matches.
   *
   * Null is a real answer and the caller has to have one for it: nothing matching means nobody said
   * what to do, which is not the same as being told to drop it.
   */
  public Rule decide(String domain, String localPart, String envelopeFrom, String subject)
      throws SQLException {
    for (Rule rule : rules(domain)) {
      if (rule.matches(localPart, envelopeFrom, subject)) {
        return rule;
      }
    }
    return null;
  }

  public long saveRule(long id, String domain, int position, String name, String matchTo,
                       String matchFrom, String matchSubject, Action action, String forwardTo,
                       boolean enabled, Long actor) throws SQLException {
    long saved;
    try (Connection connection = store.connection()) {
      if (id > 0) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + Schema.MAIL_RULES + " SET position = ?, name = ?, match_to = ?,"
                + " match_from = ?, match_subject = ?, action = ?, forward_to = ?, enabled = ?,"
                + " updated_at = ?, updated_by = ? WHERE id = ?")) {
          statement.setInt(1, position);
          statement.setString(2, orEmpty(name));
          statement.setString(3, normalize(matchTo));
          statement.setString(4, orEmpty(matchFrom).toLowerCase(Locale.ROOT));
          statement.setString(5, orEmpty(matchSubject));
          statement.setString(6, action.name());
          statement.setString(7, normalize(forwardTo));
          statement.setBoolean(8, enabled);
          statement.setTimestamp(9, new Timestamp(System.currentTimeMillis()));
          setNullable(statement, 10, actor);
          statement.setLong(11, id);
          statement.executeUpdate();
        }
        saved = id;
      } else {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + Schema.MAIL_RULES + " (domain, position, name, match_to, match_from,"
                + " match_subject, action, forward_to, enabled, updated_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS)) {
          statement.setString(1, normalize(domain));
          statement.setInt(2, position);
          statement.setString(3, orEmpty(name));
          statement.setString(4, normalize(matchTo));
          statement.setString(5, orEmpty(matchFrom).toLowerCase(Locale.ROOT));
          statement.setString(6, orEmpty(matchSubject));
          statement.setString(7, action.name());
          statement.setString(8, normalize(forwardTo));
          statement.setBoolean(9, enabled);
          setNullable(statement, 10, actor);
          statement.executeUpdate();
          try (ResultSet keys = statement.getGeneratedKeys()) {
            saved = keys.next() ? keys.getLong(1) : 0;
          }
        }
      }
    }
    store.changed(Schema.MAIL_RULES, saved,
        id > 0 ? MutationEvent.Kind.update : MutationEvent.Kind.insert, actor);
    return saved;
  }

  public void deleteRule(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.MAIL_RULES + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.MAIL_RULES, id, MutationEvent.Kind.delete, actor);
  }

  public int countRules(String domain) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + Schema.MAIL_RULES + " WHERE domain = ?")) {
      statement.setString(1, normalize(domain));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? found.getInt(1) : 0;
      }
    }
  }

  // ---- validation -------------------------------------------------------------------------------

  /**
   * Is this a local part somebody may have?
   *
   * Stricter than RFC 5321 allows, and on purpose: this is a name an admin types into a form, and
   * every character permitted here is one that has to be safe in a header, in a log line and in an
   * SRS address. The dotted and plus-suffixed forms real mail uses are all inside this.
   */
  public static String checkLocalPart(String raw) {
    String clean = normalize(raw);
    if (clean.isEmpty()) {
      return "an address needs a name before the @";
    }
    if (clean.length() > 64) {
      return "that name is longer than an address is allowed to be";
    }
    if (clean.startsWith(".") || clean.endsWith(".") || clean.contains("..")) {
      return "a name cannot start or end with a dot, or carry two in a row";
    }
    for (int k = 0; k < clean.length(); k++) {
      char ch = clean.charAt(k);
      boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
          || ch == '.' || ch == '-' || ch == '_' || ch == '+';
      if (!ok) {
        return "a name may hold letters, numbers, and . - _ + and nothing else";
      }
    }
    return null;
  }

  /** the whole of what makes a rule storable, said in one place so the form and an import agree */
  public static String checkRule(String matchTo, Action action, String forwardTo) {
    if (action == null) {
      return "a rule either forwards or drops";
    }
    if (!EVERYONE.equals(normalize(matchTo))) {
      String bad = checkLocalPart(matchTo);
      if (bad != null) {
        return bad;
      }
    }
    if (action != Action.forward) {
      return null;
    }
    String destination = normalize(forwardTo);
    if (destination.isEmpty()) {
      return "a rule that forwards needs somewhere to forward to";
    }
    if (!SmtpRouting.looksLikeAddress(destination)) {
      return "'" + destination + "' is not an address this server can send to";
    }
    return null;
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String orEmpty(String raw) {
    return raw == null ? "" : raw.trim();
  }

  private static void setNullable(PreparedStatement statement, int at, Long value)
      throws SQLException {
    if (value == null) {
      statement.setNull(at, java.sql.Types.BIGINT);
    } else {
      statement.setLong(at, value);
    }
  }

  private static Box readBox(ResultSet found) throws SQLException {
    long owner = found.getLong("user_id");
    boolean noOwner = found.wasNull();
    return new Box(found.getLong("id"), found.getString("domain"), found.getString("local_part"),
        found.getString("label"), noOwner ? null : owner, found.getBoolean("enabled"),
        found.getTimestamp("updated_at"));
  }

  private static Rule readRule(ResultSet found) throws SQLException {
    long by = found.getLong("updated_by");
    boolean nobody = found.wasNull();
    // An action the database holds that this software no longer understands drops the message
    // rather than forwarding it. Failing closed is the only safe direction here: the alternative is
    // a rule whose meaning was lost sending somebody's mail somewhere nobody chose.
    Action action = Action.of(found.getString("action"));
    return new Rule(found.getLong("id"), found.getString("domain"), found.getInt("position"),
        found.getString("name"), found.getString("match_to"), found.getString("match_from"),
        found.getString("match_subject"), action == null ? Action.drop : action,
        found.getString("forward_to"), found.getBoolean("enabled"),
        found.getTimestamp("updated_at"), nobody ? null : by);
  }
}
