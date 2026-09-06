package io.hearth.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Things to do, habits to keep, and the sheet that says what today looks like.
 *
 * <b>Tasks and habits are one table because they are one list.</b> The daily sheet does not care
 * which kind a thing is; it cares what has to happen today. Two tables would mean two queries, two
 * screens, and a person having to know what kind of thing they are looking for before they can look
 * for it -- which is precisely the friction that puts this back in a spreadsheet.
 *
 * <b>Done/not-done is a lie about most work worth tracking.</b> A fence repair goes surveyed ->
 * materials -> built -> checked, and which of those it is in is the whole value. So a task can
 * point at a {@link Processes} definition and walk it, and the sheet shows where each one has got
 * to.
 *
 * <b>A habit graduates rather than being deleted.</b> That is the difference between a habit
 * tracker and a checklist: the point of a habit is to stop needing to be tracked. Graduating takes
 * it off the sheet and keeps every mark, so the evidence that it worked survives -- deleting it
 * throws away the only proof the effort was worth anything.
 *
 * <b>Marks are one row per day, not a counter.</b> The question this exists to answer is "which
 * days" -- a streak, a gap, the month it fell apart. A counter gives the number and never the
 * shape, and the shape is what tells somebody whether to graduate a habit or start again.
 */
public class Tasks {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** how far ahead the horizon looks */
  public static final int HORIZON_DAYS = 30;

  private final Store store;
  private final ZoneId zone;

  public Tasks(Store store, ZoneId zone) {
    this.store = store;
    this.zone = zone == null ? ZoneId.systemDefault() : zone;
  }

  /** the community's clock, never the JVM's */
  public LocalDate today() {
    return LocalDate.now(zone);
  }

  public enum Kind {
    task, habit;

    public static Kind of(String raw) {
      return "habit".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? habit : task;
    }
  }

  /** how often a habit has to happen */
  public enum Cadence {
    none, daily, weekly;

    public static Cadence of(String raw) {
      if (raw == null) {
        return none;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return none;
      }
    }
  }

  /** the two states every plain task has, whatever else it might have */
  public static final String OPEN = "open";
  public static final String DONE = "done";
  public static final String DROPPED = "dropped";

  public static class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  public record Record(long id, String title, String detail, Kind kind, String state,
                       String process, Cadence cadence, int perWeek, Timestamp graduatedAt,
                       Date dueOn, String area, long userId, Timestamp doneAt,
                       Date startsOn, Date endsOn) {
    /**
     * A challenge: a habit with an end.
     *
     * "Thirty days of mobility" is a different thing from "do mobility forever", and the difference
     * is that it finishes. A challenge graduates itself when the last day passes rather than
     * sitting on the sheet being missed -- which is what a habit with no end does when somebody has
     * lost interest, and is why a spreadsheet full of stale checkboxes is dispiriting.
     */
    public boolean isChallenge() {
      return endsOn != null;
    }

    public boolean hasStarted(LocalDate today) {
      return startsOn == null || !today.isBefore(startsOn.toLocalDate());
    }

    public boolean hasEnded(LocalDate today) {
      return endsOn != null && today.isAfter(endsOn.toLocalDate());
    }

    public boolean isHabit() {
      return kind == Kind.habit;
    }

    public boolean isGraduated() {
      return graduatedAt != null;
    }

    public boolean isFinished() {
      return DONE.equals(state) || DROPPED.equals(state);
    }
  }

  // ---- reading ---------------------------------------------------------------------------------

  public Record byId(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.TASKS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  /** everything belonging to one person, newest last so a list reads in the order it was made */
  public List<Record> all(long userId, boolean includeFinished) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    String sql = "SELECT * FROM " + Schema.TASKS + " WHERE user_id = ?"
        + (includeFinished ? "" : " AND state NOT IN ('done','dropped') AND graduated_at IS NULL")
        + " ORDER BY id";
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

  // ---- writing ---------------------------------------------------------------------------------

  public Record add(long userId, String title, Map<String, Object> fields)
      throws SQLException, Refused {
    return add(userId, title, fields, null);
  }

  /**
   * Put something on the list.
   *
   * <b>A task with a process starts at that process's first state, not at `open`.</b> Starting at
   * `open` would leave it in a state its own process does not contain, so nothing could say what
   * the next step was -- the task would claim to have steps and be unable to name one.
   */
  public Record add(long userId, String title, Map<String, Object> fields, Processes processes)
      throws SQLException, Refused {
    if (title == null || title.isBlank()) {
      throw new Refused("a task needs a title");
    }
    Kind kind = Kind.of(str(fields, "kind"));
    Cadence cadence = Cadence.of(str(fields, "cadence"));
    if (kind == Kind.habit && cadence == Cadence.none) {
      throw new Refused("a habit needs a cadence: daily, or weekly with per_week");
    }
    String process = orEmpty(str(fields, "process"));
    String start = OPEN;
    if (!process.isBlank() && processes != null) {
      List<String> states = processes.statesOf(process);
      if (!states.isEmpty()) {
        start = states.get(0);
      }
    }
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.TASKS
                 + " (title, detail, kind, state, process, cadence, per_week, due_on, area,"
                 + " user_id, starts_on, ends_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
             java.sql.Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, title.trim());
      statement.setString(2, orEmpty(str(fields, "detail")));
      statement.setString(3, kind.name());
      statement.setString(4, start);
      statement.setString(5, process);
      statement.setString(6, cadence.name());
      statement.setInt(7, Math.max(1, Math.min(7, intOf(fields, "per_week", 1))));
      setDate(statement, 8, str(fields, "due_on"));
      statement.setString(9, orEmpty(str(fields, "area")));
      statement.setLong(10, userId);
      setDate(statement, 11, str(fields, "starts_on"));
      setDate(statement, 12, str(fields, "ends_on"));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        id = keys.next() ? keys.getLong(1) : 0;
      }
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.insert, userId);
    return byId(id);
  }

  /**
   * Change what a task is.
   *
   * Absent keys are left alone, the same rule as everywhere else here -- a caller sending a new due
   * date should not blank the detail it did not mention.
   */
  public Record update(long id, Map<String, Object> fields, long actor)
      throws SQLException, Refused {
    Record task = require(id);
    ArrayList<String> sets = new ArrayList<>();
    ArrayList<Object> values = new ArrayList<>();
    if (fields.containsKey("title")) {
      String title = str(fields, "title");
      if (title == null || title.isBlank()) {
        throw new Refused("a task needs a title");
      }
      sets.add("title = ?");
      values.add(title.trim());
    }
    for (String simple : new String[]{"detail", "area", "process"}) {
      if (fields.containsKey(simple)) {
        sets.add(simple + " = ?");
        values.add(orEmpty(str(fields, simple)));
      }
    }
    if (fields.containsKey("cadence")) {
      sets.add("cadence = ?");
      values.add(Cadence.of(str(fields, "cadence")).name());
    }
    if (fields.containsKey("per_week")) {
      sets.add("per_week = ?");
      values.add(Math.max(1, Math.min(7, intOf(fields, "per_week", 1))));
    }
    for (String date : new String[]{"due_on", "starts_on", "ends_on"}) {
      if (fields.containsKey(date)) {
        sets.add(date + " = ?");
        values.add(parseDate(str(fields, date)));
      }
    }
    if (sets.isEmpty()) {
      return task;
    }
    sets.add("updated_at = ?");
    values.add(new Timestamp(System.currentTimeMillis()));
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET " + String.join(", ", sets) + " WHERE id = ?")) {
      int at = 1;
      for (Object value : values) {
        statement.setObject(at++, value);
      }
      statement.setLong(at, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
    return byId(id);
  }

  /**
   * Move a task to a state.
   *
   * <b>A task with a process may only be in one of that process's states, or finished.</b> The
   * check is the whole reason a process is a stored thing rather than a convention: without it a
   * typo puts a task in a state no screen will ever list and nobody notices until they go looking
   * for it.
   */
  public Record moveTo(long id, String state, Processes processes, long actor)
      throws SQLException, Refused {
    Record task = require(id);
    String wanted = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      throw new Refused("a state is needed");
    }
    if (!wanted.equals(DONE) && !wanted.equals(DROPPED) && !wanted.equals(OPEN)) {
      if (task.process().isBlank()) {
        throw new Refused("'" + task.title() + "' has no process, so it is open, done or dropped");
      }
      List<String> states = processes.statesOf(task.process());
      if (!states.contains(wanted)) {
        throw new Refused("'" + wanted + "' is not a state of " + task.process()
            + "; it goes " + String.join(" -> ", states));
      }
    }
    boolean finishing = wanted.equals(DONE) || wanted.equals(DROPPED);
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET state = ?, done_at = ?, updated_at = ?"
                 + " WHERE id = ?")) {
      statement.setString(1, wanted);
      statement.setTimestamp(2, finishing ? new Timestamp(System.currentTimeMillis()) : null);
      statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
      statement.setLong(4, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
    return byId(id);
  }

  /**
   * Retire a habit that has done its job.
   *
   * Not a delete, and the distinction is the point of having habits at all: a habit exists to stop
   * needing to exist, and the marks are the evidence it worked. It leaves the sheet and keeps
   * everything.
   */
  /** the same write, without the refusals: for a challenge whose last day has passed */
  private void graduateQuietly(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET graduated_at = ?, updated_at = ?"
                 + " WHERE id = ? AND graduated_at IS NULL")) {
      Timestamp now = new Timestamp(System.currentTimeMillis());
      statement.setTimestamp(1, now);
      statement.setTimestamp(2, now);
      statement.setLong(3, id);
      statement.executeUpdate();
    }
  }

  public Record graduate(long id, long actor) throws SQLException, Refused {
    Record task = require(id);
    if (!task.isHabit()) {
      throw new Refused("'" + task.title() + "' is a task, not a habit. Tasks are done, not"
          + " graduated.");
    }
    if (task.isGraduated()) {
      throw new Refused("'" + task.title() + "' has already graduated");
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET graduated_at = ?, updated_at = ? WHERE id = ?")) {
      Timestamp now = new Timestamp(System.currentTimeMillis());
      statement.setTimestamp(1, now);
      statement.setTimestamp(2, now);
      statement.setLong(3, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
    return byId(id);
  }

  public void delete(long id, long actor) throws SQLException, Refused {
    require(id);
    try (Connection connection = store.connection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.HABIT_MARKS + " WHERE task_id = ?")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.TASKS + " WHERE id = ?")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.delete, actor);
  }

  // ---- habits ----------------------------------------------------------------------------------

  /** mark a habit kept on a day; twice on one day is the same as once */
  public void mark(long id, LocalDate day, String note, long actor)
      throws SQLException, Refused {
    Record task = require(id);
    if (!task.isHabit()) {
      throw new Refused("'" + task.title() + "' is a task. Finish it with a state, not a mark.");
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.HABIT_MARKS + " (task_id, on_day, note)"
                 + " KEY (task_id, on_day) VALUES (?, ?, ?)")) {
      statement.setLong(1, id);
      statement.setDate(2, Date.valueOf(day == null ? today() : day));
      statement.setString(3, note == null ? "" : note.trim());
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
  }

  public void unmark(long id, LocalDate day, long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.HABIT_MARKS + " WHERE task_id = ? AND on_day = ?")) {
      statement.setLong(1, id);
      statement.setDate(2, Date.valueOf(day == null ? today() : day));
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
  }

  /** which days a habit was kept, most recent first */
  public List<LocalDate> marks(long id, int days) throws SQLException {
    ArrayList<LocalDate> out = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT on_day FROM " + Schema.HABIT_MARKS + " WHERE task_id = ? AND on_day >= ?"
                 + " ORDER BY on_day DESC")) {
      statement.setLong(1, id);
      statement.setDate(2, Date.valueOf(today().minusDays(Math.max(1, days))));
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          out.add(found.getDate("on_day").toLocalDate());
        }
      }
    }
    return out;
  }

  /**
   * How a habit is going.
   *
   * <b>The streak counts back from today, and a daily habit not yet done today does not break
   * it.</b> Otherwise every streak reads as zero every morning, which is both wrong and the single
   * most discouraging thing a tracker can do.
   */
  public record Standing(int streak, int last7, int last30, boolean dueToday, int neededThisWeek,
                         int keptThisWeek) {
  }

  public Standing standing(Record habit) throws SQLException {
    List<LocalDate> marks = marks(habit.id(), 400);
    java.util.HashSet<LocalDate> days = new java.util.HashSet<>(marks);
    LocalDate today = today();

    int streak = 0;
    LocalDate walk = days.contains(today) ? today : today.minusDays(1);
    while (days.contains(walk)) {
      streak++;
      walk = walk.minusDays(1);
    }
    int last7 = 0;
    int last30 = 0;
    for (LocalDate day : days) {
      long back = ChronoUnit.DAYS.between(day, today);
      if (back >= 0 && back < 7) {
        last7++;
      }
      if (back >= 0 && back < 30) {
        last30++;
      }
    }
    LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
    int keptThisWeek = 0;
    for (LocalDate day : days) {
      if (!day.isBefore(weekStart) && !day.isAfter(today)) {
        keptThisWeek++;
      }
    }
    int needed = habit.cadence() == Cadence.daily ? 7 : habit.perWeek();
    boolean dueToday = switch (habit.cadence()) {
      case daily -> !days.contains(today);
      // a weekly habit is due when there are not enough days left in the week to still make it
      case weekly -> !days.contains(today)
          && keptThisWeek + (7 - today.getDayOfWeek().getValue() + 1) <= habit.perWeek();
      case none -> false;
    };
    return new Standing(streak, last7, last30, dueToday, needed, keptThisWeek);
  }

  // ---- the sheet -------------------------------------------------------------------------------

  /** what has to happen today, and what is coming */
  public record Sheet(List<Map<String, Object>> today, List<Map<String, Object>> horizon,
                      List<Map<String, Object>> anytime, int graduated) {
  }

  /**
   * The daily grind sheet.
   *
   * <b>Three lists, and the split is the whole design.</b> `today` is what has to happen and is
   * meant to be short enough to finish. `horizon` is dated work coming up, so nothing arrives as a
   * surprise. `anytime` is the pool to pull from when today is done -- which is the thing a paper
   * checkbox grid cannot do and the reason this exists at all.
   *
   * Overdue work is in `today` rather than a fourth list. A separate overdue section is a place
   * things go to be ignored; in today's list it is simply what has to happen, which is true.
   */
  public Sheet sheet(long userId, Processes processes) throws SQLException {
    ArrayList<Map<String, Object>> today = new ArrayList<>();
    ArrayList<Map<String, Object>> horizon = new ArrayList<>();
    ArrayList<Map<String, Object>> anytime = new ArrayList<>();
    int graduated = 0;
    LocalDate now = today();

    for (Record task : all(userId, true)) {
      if (task.isGraduated()) {
        graduated++;
        continue;
      }
      if (task.isFinished()) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>(describe(task, processes));
      if (task.isHabit()) {
        // A challenge that has run out of days graduates itself.
        //
        // Not "goes stale on the sheet": the whole point of putting an end on a habit is that it
        // finishes, and a thirty-day challenge still asking on day forty is exactly the dispiriting
        // thing this is meant to replace. Done here rather than in a nightly job because the sheet
        // is the only place it matters and there is no job to supervise.
        if (task.hasEnded(now)) {
          graduateQuietly(task.id());
          graduated++;
          continue;
        }
        if (!task.hasStarted(now)) {
          row.put("starts_on", task.startsOn().toString());
          horizon.add(row);
          continue;
        }
        Standing standing = standing(task);
        row.put("streak", standing.streak());
        row.put("last_7_days", standing.last7());
        row.put("kept_this_week", standing.keptThisWeek());
        row.put("needed_per_week", standing.neededThisWeek());
        if (standing.dueToday()) {
          today.add(row);
        } else {
          anytime.add(row);
        }
        continue;
      }
      if (task.dueOn() == null) {
        anytime.add(row);
        continue;
      }
      LocalDate due = task.dueOn().toLocalDate();
      if (!due.isAfter(now)) {
        row.put("overdue_by_days", ChronoUnit.DAYS.between(due, now));
        today.add(row);
      } else if (ChronoUnit.DAYS.between(now, due) <= HORIZON_DAYS) {
        row.put("in_days", ChronoUnit.DAYS.between(now, due));
        horizon.add(row);
      } else {
        anytime.add(row);
      }
    }
    horizon.sort((left, right) -> Long.compare(
        ((Number) left.getOrDefault("in_days", 0)).longValue(),
        ((Number) right.getOrDefault("in_days", 0)).longValue()));
    return new Sheet(today, horizon, anytime, graduated);
  }

  /** one task as a model or a tool answer reads it */
  public Map<String, Object> describe(Record task, Processes processes) throws SQLException {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", task.id());
    row.put("title", task.title());
    if (!task.detail().isBlank()) {
      row.put("detail", task.detail());
    }
    row.put("kind", task.kind().name());
    row.put("state", task.state());
    if (!task.area().isBlank()) {
      row.put("area", task.area());
    }
    if (!task.process().isBlank()) {
      row.put("process", task.process());
      List<String> states = processes.statesOf(task.process());
      row.put("process_states", states);
      int at = states.indexOf(task.state());
      if (at >= 0 && at + 1 < states.size()) {
        row.put("next_state", states.get(at + 1));
      }
    }
    if (task.isHabit()) {
      row.put("cadence", task.cadence().name());
      if (task.cadence() == Cadence.weekly) {
        row.put("per_week", task.perWeek());
      }
      if (task.isChallenge()) {
        row.put("challenge", true);
        row.put("ends_on", task.endsOn().toString());
        row.put("days_left", java.time.temporal.ChronoUnit.DAYS.between(
            today(), task.endsOn().toLocalDate()));
      }
    }
    if (task.dueOn() != null) {
      row.put("due_on", task.dueOn().toString());
    }
    if (task.isGraduated()) {
      row.put("graduated", true);
    }
    return row;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Record require(long id) throws SQLException, Refused {
    Record task = byId(id);
    if (task == null) {
      throw new Refused("there is no task " + id);
    }
    return task;
  }

  private static Record read(ResultSet found) throws SQLException {
    return new Record(found.getLong("id"), found.getString("title"), found.getString("detail"),
        Kind.of(found.getString("kind")), found.getString("state"), found.getString("process"),
        Cadence.of(found.getString("cadence")), found.getInt("per_week"),
        found.getTimestamp("graduated_at"), found.getDate("due_on"), found.getString("area"),
        found.getLong("user_id"), found.getTimestamp("done_at"),
        found.getDate("starts_on"), found.getDate("ends_on"));
  }

  private static void setDate(PreparedStatement statement, int at, String raw) throws SQLException {
    Date date = parseDate(raw);
    if (date == null) {
      statement.setNull(at, java.sql.Types.DATE);
    } else {
      statement.setDate(at, date);
    }
  }

  private static Date parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Date.valueOf(LocalDate.parse(raw.trim()));
    } catch (Exception ex) {
      return null;
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static String str(Map<String, Object> fields, String key) {
    Object value = fields == null ? null : fields.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static int intOf(Map<String, Object> fields, String key, int fallback) {
    Object value = fields == null ? null : fields.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  static ArrayNode array(List<String> values) {
    ArrayNode node = JSON.createArrayNode();
    for (String value : values) {
      node.add(value);
    }
    return node;
  }

  static List<String> listOf(JsonNode node) {
    ArrayList<String> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode one : node) {
        out.add(one.asText());
      }
    }
    return out;
  }
}
