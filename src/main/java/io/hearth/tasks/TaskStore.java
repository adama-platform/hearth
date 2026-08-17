package io.hearth.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects, definitions, tasks and everything that has been recorded against them.
 *
 * <b>One store for four tables, because they are one subject.</b> Splitting it would put the query
 * that builds a history two directories from the one that writes an entry, and the interesting
 * questions here -- what has this come to, what is due today, whose is this -- all cross the tables.
 *
 * <b>Ownership is checked by the caller and enforced in the WHERE clause.</b> A member's training
 * log is the most private thing in this package, and "the handler remembers to check" is how that
 * eventually stops being true. Every read that could return somebody else's takes a viewer and puts
 * it in the query.
 */
public class TaskStore {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String PROJECT_COLUMNS =
      "id, owner_id, name, slug, summary, task_word, tasks_word, phases, hide_done_hours,"
          + " archived, created_by, created_at, updated_at";
  private static final String DEF_COLUMNS =
      "id, owner_id, parent_id, name, slug, measure, summary, instructions, reference_url, tags,"
          + " target, rest_seconds, shared, retired_at, created_by, created_at, updated_at";
  private static final String TASK_COLUMNS =
      "id, project_id, def_id, title, notes, phase, group_name, group_mode, position, done_at,"
          + " repeat_days, due_on, assigned_to, created_by, created_at, updated_at";
  private static final String ENTRY_COLUMNS =
      "id, task_id, def_id, project_id, user_id, set_index, weight, reps, seconds, distance,"
          + " difficulty, time_cost, impact, note, recorded_at";

  /** enough for a routine; past this it is a spreadsheet */
  public static final int MAX_TASKS = 500;
  /** how much of a history one screen carries */
  public static final int RECENT = 60;

  private final Store store;

  public TaskStore(Store store) {
    this.store = store;
  }

  // ---- projects --------------------------------------------------------------------------------

  public Records.Project saveProject(Long id, Long ownerId, String name, String summary,
                                     String taskWord, String tasksWord, List<String> phases,
                                     int hideDoneHours, Long actor) throws SQLException {
    String slug = slugOf(name);
    long projectId;
    if (id == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.PROJECTS + " (owner_id, name, slug, summary, task_word,"
                   + " tasks_word, phases, hide_done_hours, created_by)"
                   + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
        setNullable(statement, 1, ownerId);
        statement.setString(2, cap(name, 128));
        statement.setString(3, slug);
        statement.setString(4, cap(summary, 1024));
        statement.setString(5, cap(taskWord, 32));
        statement.setString(6, cap(tasksWord, 32));
        statement.setString(7, phasesJson(phases));
        statement.setInt(8, Math.max(0, hideDoneHours));
        setNullable(statement, 9, actor);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          keys.next();
          projectId = keys.getLong(1);
        }
      }
    } else {
      projectId = id;
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.PROJECTS + " SET name = ?, slug = ?, summary = ?, task_word = ?,"
                   + " tasks_word = ?, phases = ?, hide_done_hours = ?,"
                   + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setString(1, cap(name, 128));
        statement.setString(2, slug);
        statement.setString(3, cap(summary, 1024));
        statement.setString(4, cap(taskWord, 32));
        statement.setString(5, cap(tasksWord, 32));
        statement.setString(6, phasesJson(phases));
        statement.setInt(7, Math.max(0, hideDoneHours));
        statement.setLong(8, projectId);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.PROJECTS, projectId,
        id == null ? MutationEvent.Kind.insert : MutationEvent.Kind.update, actor);
    return project(projectId);
  }

  public Records.Project project(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROJECT_COLUMNS + " FROM " + Schema.PROJECTS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readProject(rows) : null;
      }
    }
  }

  /**
   * Every project this person may open: their own, and the community's.
   *
   * The `owner_id IS NULL` half is what makes a committee's list work at all -- it belongs to the
   * community rather than to whoever typed it, so it does not disappear when they leave.
   */
  public List<Records.Project> projectsFor(long viewer, boolean includeArchived)
      throws SQLException {
    ArrayList<Records.Project> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROJECT_COLUMNS + " FROM " + Schema.PROJECTS
                 + " WHERE (owner_id = ? OR owner_id IS NULL)"
                 + (includeArchived ? "" : " AND archived = FALSE")
                 + " ORDER BY owner_id NULLS LAST, name")) {
      statement.setLong(1, viewer);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readProject(rows));
        }
      }
    }
    return found;
  }

  /** every project on the box, for the one screen an administrator inspects from */
  public List<Records.Project> allProjects() throws SQLException {
    ArrayList<Records.Project> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + PROJECT_COLUMNS + " FROM " + Schema.PROJECTS
                 + " ORDER BY owner_id NULLS FIRST, name");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        found.add(readProject(rows));
      }
    }
    return found;
  }

  public void archiveProject(long id, boolean archived, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.PROJECTS + " SET archived = ?, updated_at = CURRENT_TIMESTAMP"
                 + " WHERE id = ?")) {
      statement.setBoolean(1, archived);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
    store.changed(Schema.PROJECTS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Remove a project and the tasks on it.
   *
   * <b>The entries stay.</b> They carry their own def and project ids, and they are the record of
   * what somebody actually did -- deleting a list should not delete six months of having done the
   * things on it. They become history with no list attached, which is exactly what they are.
   */
  public void deleteProject(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE " + Schema.TASK_ENTRIES + " SET task_id = NULL WHERE task_id IN"
              + " (SELECT id FROM " + Schema.TASKS + " WHERE project_id = ?)")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.TASKS + " WHERE project_id = ?")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.PROJECTS + " WHERE id = ?")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.PROJECTS, id, MutationEvent.Kind.delete, actor);
  }

  // ---- definitions -----------------------------------------------------------------------------

  public Records.Def saveDef(Long id, Long ownerId, Long parentId, String name, Measure measure,
                             String summary, String instructions, String referenceUrl, String tags,
                             String target, int restSeconds, boolean shared, Long actor)
      throws SQLException {
    long defId;
    if (id == null) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + Schema.TASK_DEFS + " (owner_id, parent_id, name, slug, measure,"
                   + " summary, instructions, reference_url, tags, target, rest_seconds, shared,"
                   + " created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
               Statement.RETURN_GENERATED_KEYS)) {
        setNullable(statement, 1, ownerId);
        setNullable(statement, 2, parentId);
        statement.setString(3, cap(name, 128));
        statement.setString(4, slugOf(name));
        statement.setString(5, measure.name());
        statement.setString(6, cap(summary, 512));
        statement.setString(7, cap(instructions, 16384));
        statement.setString(8, cap(referenceUrl, 512));
        statement.setString(9, cap(tags, 512));
        statement.setString(10, target == null || target.isBlank() ? "{}" : cap(target, 2048));
        statement.setInt(11, Math.max(0, Math.min(3600, restSeconds)));
        statement.setBoolean(12, shared);
        setNullable(statement, 13, actor);
        statement.executeUpdate();
        try (ResultSet keys = statement.getGeneratedKeys()) {
          keys.next();
          defId = keys.getLong(1);
        }
      }
    } else {
      defId = id;
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.TASK_DEFS + " SET name = ?, slug = ?, measure = ?, summary = ?,"
                   + " instructions = ?, reference_url = ?, tags = ?, target = ?, rest_seconds = ?,"
                   + " shared = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setString(1, cap(name, 128));
        statement.setString(2, slugOf(name));
        statement.setString(3, measure.name());
        statement.setString(4, cap(summary, 512));
        statement.setString(5, cap(instructions, 16384));
        statement.setString(6, cap(referenceUrl, 512));
        statement.setString(7, cap(tags, 512));
        statement.setString(8, target == null || target.isBlank() ? "{}" : cap(target, 2048));
        statement.setInt(9, Math.max(0, Math.min(3600, restSeconds)));
        statement.setBoolean(10, shared);
        statement.setLong(11, defId);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.TASK_DEFS, defId,
        id == null ? MutationEvent.Kind.insert : MutationEvent.Kind.update, actor);
    return def(defId);
  }

  public Records.Def def(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + DEF_COLUMNS + " FROM " + Schema.TASK_DEFS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readDef(rows) : null;
      }
    }
  }

  /** somebody's own definitions, plus every shared one they may adopt */
  public List<Records.Def> defsFor(long viewer, boolean includeRetired) throws SQLException {
    ArrayList<Records.Def> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + DEF_COLUMNS + " FROM " + Schema.TASK_DEFS
                 + " WHERE (owner_id = ? OR (owner_id IS NULL AND shared = TRUE))"
                 + (includeRetired ? "" : " AND retired_at IS NULL")
                 + " ORDER BY owner_id NULLS FIRST, name")) {
      statement.setLong(1, viewer);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readDef(rows));
        }
      }
    }
    return found;
  }

  /** the community's library, which anybody may adopt from */
  public List<Records.Def> sharedDefs() throws SQLException {
    ArrayList<Records.Def> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + DEF_COLUMNS + " FROM " + Schema.TASK_DEFS
                 + " WHERE owner_id IS NULL AND shared = TRUE AND retired_at IS NULL"
                 + " ORDER BY name");
         ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        found.add(readDef(rows));
      }
    }
    return found;
  }

  public void retireDef(long id, boolean retired, Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASK_DEFS + " SET retired_at = " + (retired
                 ? "CURRENT_TIMESTAMP" : "NULL") + " WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASK_DEFS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Take a copy of a shared definition, pointing back at it.
   *
   * A pointer rather than a copy of the words: the community's form notes stay one thing, so
   * improving them improves everybody's. What somebody gets of their own is the row that can hold
   * their target and their own instructions, which is where "the same movement, but I do six" goes
   * without forking the whole thing.
   */
  public Records.Def adopt(long parentId, long ownerId, Long actor) throws SQLException {
    Records.Def parent = def(parentId);
    if (parent == null) {
      return null;
    }
    return saveDef(null, ownerId, parent.id(), parent.name(), parent.measure(), parent.summary(),
        "", parent.referenceUrl(), parent.tags(), parent.target(), parent.restSeconds(), false,
        actor);
  }

  /**
   * The definition somebody should actually read, following the parent when this one is silent.
   *
   * Only the fields worth inheriting: what it is and how to do it. The target is not inherited
   * once somebody has set their own, because that is the whole reason they took a copy.
   */
  public Records.Def resolved(Records.Def def) throws SQLException {
    if (def == null || def.parentId() == null) {
      return def;
    }
    Records.Def parent = def(def.parentId());
    if (parent == null) {
      return def;
    }
    return new Records.Def(def.id(), def.ownerId(), def.parentId(), def.name(), def.slug(),
        def.measure(),
        def.summary().isBlank() ? parent.summary() : def.summary(),
        def.instructions().isBlank() ? parent.instructions() : def.instructions(),
        def.referenceUrl().isBlank() ? parent.referenceUrl() : def.referenceUrl(),
        def.tags().isBlank() ? parent.tags() : def.tags(),
        def.target(),
        // the rest is inherited when this copy has not set one: it is a property of the movement
        def.restSeconds() > 0 ? def.restSeconds() : parent.restSeconds(),
        def.shared(), def.retiredAt(), def.createdBy(), def.createdAt(), def.updatedAt());
  }

  // ---- tasks -----------------------------------------------------------------------------------

  public Records.Task addTask(long projectId, Long defId, String title, String notes, String phase,
                              int repeatDays, LocalDate dueOn, Long assignedTo, Long actor)
      throws SQLException {
    return addTask(projectId, defId, title, notes, phase, "", null, repeatDays, dueOn, assignedTo,
        actor);
  }

  public Records.Task addTask(long projectId, Long defId, String title, String notes, String phase,
                              String groupName, Records.Grouping grouping, int repeatDays,
                              LocalDate dueOn, Long assignedTo, Long actor) throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.TASKS + " (project_id, def_id, title, notes, phase,"
                 + " group_name, group_mode, position, repeat_days, due_on, assigned_to,"
                 + " created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, projectId);
      setNullable(statement, 2, defId);
      statement.setString(3, cap(title, 256));
      statement.setString(4, cap(notes, 4096));
      statement.setString(5, cap(phase, 64));
      statement.setString(6, cap(groupName, 64));
      statement.setString(7, grouping == null ? "" : grouping.name());
      statement.setInt(8, nextPosition(projectId, phase));
      statement.setInt(9, Math.max(0, repeatDays));
      statement.setDate(10, dueOn == null ? null : java.sql.Date.valueOf(dueOn));
      setNullable(statement, 11, assignedTo);
      setNullable(statement, 12, actor);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.insert, actor);
    return task(id);
  }

  public Records.Task task(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TASK_COLUMNS + " FROM " + Schema.TASKS + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readTask(rows) : null;
      }
    }
  }

  public List<Records.Task> tasksIn(long projectId) throws SQLException {
    ArrayList<Records.Task> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + TASK_COLUMNS + " FROM " + Schema.TASKS + " WHERE project_id = ?"
                 + " ORDER BY position, id " + store.dialect().limit(MAX_TASKS))) {
      statement.setLong(1, projectId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readTask(rows));
        }
      }
    }
    return found;
  }

  public void editTask(long id, String title, String notes, int repeatDays, LocalDate dueOn,
                       Long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET title = ?, notes = ?, repeat_days = ?, due_on = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, cap(title, 256));
      statement.setString(2, cap(notes, 4096));
      statement.setInt(3, Math.max(0, repeatDays));
      statement.setDate(4, dueOn == null ? null : java.sql.Date.valueOf(dueOn));
      statement.setLong(5, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Put a task in a group, or take it out of one.
   *
   * A group is a name shared by rows on one project, so joining one is writing the name down and
   * leaving is clearing it. There is nothing to create and nothing left behind when the last member
   * leaves, which is the point of it being a column.
   */
  public void group(long id, String groupName, Records.Grouping grouping, Long actor)
      throws SQLException {
    boolean joining = groupName != null && !groupName.isBlank() && grouping != null;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET group_name = ?, group_mode = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, joining ? cap(groupName, 64) : "");
      statement.setString(2, joining ? grouping.name() : "");
      statement.setLong(3, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * The others in the same group, in the order they are done.
   *
   * By position, which is the order they were added -- for a `sequenced` group that order is the
   * whole meaning, and for a `related` one it is the round.
   */
  public List<Records.Task> groupWith(Records.Task task) throws SQLException {
    ArrayList<Records.Task> found = new ArrayList<>();
    if (!task.grouped()) {
      return found;
    }
    for (Records.Task other : tasksIn(task.projectId())) {
      if (other.grouped() && other.groupName().equals(task.groupName())) {
        found.add(other);
      }
    }
    return found;
  }

  /** move it to another column of the board, or to another place in this one */
  public void moveTask(long id, String phase, Long actor) throws SQLException {
    Records.Task task = task(id);
    if (task == null) {
      return;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.TASKS + " SET phase = ?, position = ?,"
                 + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      statement.setString(1, cap(phase, 64));
      statement.setInt(2, nextPosition(task.projectId(), phase));
      statement.setLong(3, id);
      statement.executeUpdate();
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
  }

  /**
   * Tick it off, or un-tick it.
   *
   * <b>A repeating task is not finished, it is due again.</b> Ticking one moves its due date on
   * rather than closing it, because a routine is a thing that comes back and a list somebody has to
   * rewrite every Sunday is a list they stop rewriting in March.
   */
  public Records.Task complete(long id, boolean done, LocalDate today, Long actor)
      throws SQLException {
    Records.Task task = task(id);
    if (task == null) {
      return null;
    }
    if (done && task.repeats()) {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.TASKS + " SET done_at = NULL, due_on = ?,"
                   + " updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setDate(1, java.sql.Date.valueOf(task.nextDue(today)));
        statement.setLong(2, id);
        statement.executeUpdate();
      }
    } else {
      try (Connection connection = store.connection();
           PreparedStatement statement = connection.prepareStatement(
               "UPDATE " + Schema.TASKS + " SET done_at = " + (done ? "CURRENT_TIMESTAMP" : "NULL")
                   + ", updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      }
    }
    store.changed(Schema.TASKS, id, MutationEvent.Kind.update, actor);
    return task(id);
  }

  public void deleteTask(long id, Long actor) throws SQLException {
    try (Connection connection = store.connection()) {
      // the entries survive: they are what happened, and deleting a list item should not delete
      // the record of having done it
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE " + Schema.TASK_ENTRIES + " SET task_id = NULL WHERE task_id = ?")) {
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

  private int nextPosition(long projectId, String phase) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COALESCE(MAX(position), -1) + 1 FROM " + Schema.TASKS
                 + " WHERE project_id = ? AND phase = ?")) {
      statement.setLong(1, projectId);
      statement.setString(2, phase == null ? "" : phase);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  // ---- entries ---------------------------------------------------------------------------------

  /**
   * Write down what happened, with the time it happened at.
   *
   * The def and project ids are copied onto the row rather than joined through the task, because
   * the task may be deleted and this is the record that has to outlive it.
   */
  public Records.Entry record(Long taskId, Long defId, Long projectId, long userId, int setIndex,
                              Double weight, Integer reps, Integer seconds, Double distance,
                              Integer difficulty, Integer timeCost, Integer impact, String note)
      throws SQLException {
    long id;
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.TASK_ENTRIES + " (task_id, def_id, project_id, user_id,"
                 + " set_index, weight, reps, seconds, distance, difficulty, time_cost, impact,"
                 + " note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
             Statement.RETURN_GENERATED_KEYS)) {
      setNullable(statement, 1, taskId);
      setNullable(statement, 2, defId);
      setNullable(statement, 3, projectId);
      statement.setLong(4, userId);
      statement.setInt(5, Math.max(0, setIndex));
      setDouble(statement, 6, weight);
      setInt(statement, 7, reps);
      setInt(statement, 8, seconds);
      setDouble(statement, 9, distance);
      setInt(statement, 10, rating(difficulty));
      setInt(statement, 11, rating(timeCost));
      setInt(statement, 12, rating(impact));
      statement.setString(13, cap(note, 1024));
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    store.changed(Schema.TASK_ENTRIES, id, MutationEvent.Kind.insert, userId);
    return entry(id);
  }

  public Records.Entry entry(long id) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + ENTRY_COLUMNS + " FROM " + Schema.TASK_ENTRIES + " WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? readEntry(rows) : null;
      }
    }
  }

  public void deleteEntry(long id, long userId) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.TASK_ENTRIES + " WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      statement.executeUpdate();
    }
    store.changed(Schema.TASK_ENTRIES, id, MutationEvent.Kind.delete, userId);
  }

  /** what one person recorded against one definition, newest first */
  public List<Records.Entry> entriesForDef(long defId, long userId, int limit)
      throws SQLException {
    return entries("def_id = ? AND user_id = ?", defId, userId, limit);
  }

  /** what one person recorded against one task */
  public List<Records.Entry> entriesForTask(long taskId, long userId, int limit)
      throws SQLException {
    return entries("task_id = ? AND user_id = ?", taskId, userId, limit);
  }

  /** everything one person has recorded lately, whatever it was against */
  public List<Records.Entry> recentFor(long userId, int limit) throws SQLException {
    ArrayList<Records.Entry> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + ENTRY_COLUMNS + " FROM " + Schema.TASK_ENTRIES + " WHERE user_id = ?"
                 + " ORDER BY recorded_at DESC, id DESC " + store.dialect().limit(limit))) {
      statement.setLong(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readEntry(rows));
        }
      }
    }
    return found;
  }

  private List<Records.Entry> entries(String where, long first, long second, int limit)
      throws SQLException {
    ArrayList<Records.Entry> found = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + ENTRY_COLUMNS + " FROM " + Schema.TASK_ENTRIES + " WHERE " + where
                 + " ORDER BY recorded_at DESC, set_index "
                 + store.dialect().limit(Math.min(limit, 500)))) {
      statement.setLong(1, first);
      statement.setLong(2, second);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(readEntry(rows));
        }
      }
    }
    return found;
  }

  /**
   * What one definition has come to for one person.
   *
   * Computed here rather than kept in columns: a handful of hundreds of rows is one indexed scan,
   * and a cached average is a number that is wrong for as long as nobody notices. The occasions are
   * counted by distinct day-and-task rather than by row, because three sets on Tuesday is one
   * Tuesday.
   */
  public Records.History historyOf(Records.Def def, long userId) throws SQLException {
    List<Records.Entry> all = entriesForDef(def.id(), userId, 500);
    if (all.isEmpty()) {
      return new Records.History(def, 0, 0, null, null, null, null, null, null, null, null,
          List.of());
    }
    java.util.LinkedHashSet<String> occasions = new java.util.LinkedHashSet<>();
    double best = Double.NEGATIVE_INFINITY;
    Double last = null;
    double difficulty = 0;
    double time = 0;
    double impact = 0;
    int rated = 0;
    java.util.LinkedHashMap<String, Double> perOccasion = new java.util.LinkedHashMap<>();
    Double bestMax = null;
    Timestamp bestMaxAt = null;
    for (Records.Entry entry : all) {
      // the best single set ever, rather than the best day: a one-rep max is a claim about one lift
      Double estimate = def.measure().oneRepMax(entry.weight(), entry.reps());
      if (estimate != null && (bestMax == null || estimate > bestMax)) {
        bestMax = estimate;
        bestMaxAt = entry.recordedAt();
      }
      String day = entry.recordedAt().toInstant().toString().substring(0, 10);
      occasions.add(day);
      Double effort = entry.effort(def.measure());
      if (effort != null) {
        perOccasion.merge(day, effort, Double::sum);
      }
      if (entry.rated()) {
        rated++;
        difficulty += entry.difficulty() == null ? 0 : entry.difficulty();
        time += entry.timeCost() == null ? 0 : entry.timeCost();
        impact += entry.impact() == null ? 0 : entry.impact();
      }
    }
    for (Map.Entry<String, Double> day : perOccasion.entrySet()) {
      best = Math.max(best, day.getValue());
      if (last == null) {
        // the list is newest first, so the first day seen is the most recent one
        last = day.getValue();
      }
    }
    return new Records.History(def, occasions.size(), all.size(),
        all.get(0).recordedAt(),
        best == Double.NEGATIVE_INFINITY ? null : best, last, bestMax, bestMaxAt,
        rated == 0 ? null : difficulty / rated,
        rated == 0 ? null : time / rated,
        rated == 0 ? null : impact / rated,
        all.subList(0, Math.min(all.size(), RECENT)));
  }

  // ---- erasure ---------------------------------------------------------------------------------

  /**
   * Somebody leaving takes their log with them.
   *
   * All of it, unlike their words on the board: a training log is nobody else's memory of anything,
   * and there is no thread with a hole in it afterwards. Their own projects and definitions go too;
   * a shared definition they wrote stays, because the community adopted it, with their name off it.
   */
  public int forget(long userId) throws SQLException {
    int gone;
    try (Connection connection = store.connection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.TASK_ENTRIES + " WHERE user_id = ?")) {
        statement.setLong(1, userId);
        gone = statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.TASKS + " WHERE project_id IN (SELECT id FROM "
              + Schema.PROJECTS + " WHERE owner_id = ?)")) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.PROJECTS + " WHERE owner_id = ?")) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + Schema.TASK_DEFS + " WHERE owner_id = ?")) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      for (String table : new String[]{Schema.PROJECTS, Schema.TASK_DEFS}) {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + table + " SET created_by = NULL WHERE created_by = ?")) {
          statement.setLong(1, userId);
          statement.executeUpdate();
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE " + Schema.TASKS + " SET created_by = NULL, assigned_to = NULL"
              + " WHERE created_by = ? OR assigned_to = ?")) {
        statement.setLong(1, userId);
        statement.setLong(2, userId);
        statement.executeUpdate();
      }
    }
    return gone;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private static Integer rating(Integer value) {
    if (value == null) {
      return null;
    }
    return Math.max(1, Math.min(5, value));
  }

  private static void setNullable(PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, java.sql.Types.BIGINT);
    } else {
      statement.setLong(index, value);
    }
  }

  private static void setInt(PreparedStatement statement, int index, Integer value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, java.sql.Types.INTEGER);
    } else {
      statement.setInt(index, value);
    }
  }

  private static void setDouble(PreparedStatement statement, int index, Double value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, java.sql.Types.DOUBLE);
    } else {
      statement.setDouble(index, value);
    }
  }

  private static Long longOrNull(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }

  private static Integer intOrNull(ResultSet rows, String column) throws SQLException {
    int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }

  private static Double doubleOrNull(ResultSet rows, String column) throws SQLException {
    double value = rows.getDouble(column);
    return rows.wasNull() ? null : value;
  }

  private static Records.Project readProject(ResultSet rows) throws SQLException {
    return new Records.Project(rows.getLong("id"), longOrNull(rows, "owner_id"),
        rows.getString("name"), rows.getString("slug"), rows.getString("summary"),
        rows.getString("task_word"), rows.getString("tasks_word"),
        phasesOf(rows.getString("phases")), rows.getInt("hide_done_hours"),
        rows.getBoolean("archived"), longOrNull(rows, "created_by"),
        rows.getTimestamp("created_at"), rows.getTimestamp("updated_at"));
  }

  private static Records.Def readDef(ResultSet rows) throws SQLException {
    Measure measure = Measure.of(rows.getString("measure"));
    return new Records.Def(rows.getLong("id"), longOrNull(rows, "owner_id"),
        longOrNull(rows, "parent_id"), rows.getString("name"), rows.getString("slug"),
        measure == null ? Measure.none : measure, rows.getString("summary"),
        rows.getString("instructions"), rows.getString("reference_url"), rows.getString("tags"),
        rows.getString("target"), rows.getInt("rest_seconds"), rows.getBoolean("shared"),
        rows.getTimestamp("retired_at"),
        longOrNull(rows, "created_by"), rows.getTimestamp("created_at"),
        rows.getTimestamp("updated_at"));
  }

  private static Records.Task readTask(ResultSet rows) throws SQLException {
    java.sql.Date due = rows.getDate("due_on");
    return new Records.Task(rows.getLong("id"), rows.getLong("project_id"),
        longOrNull(rows, "def_id"), rows.getString("title"), rows.getString("notes"),
        rows.getString("phase"), rows.getString("group_name"),
        Records.Grouping.of(rows.getString("group_mode")),
        rows.getInt("position"), rows.getTimestamp("done_at"),
        rows.getInt("repeat_days"), due == null ? null : due.toLocalDate(),
        longOrNull(rows, "assigned_to"), longOrNull(rows, "created_by"),
        rows.getTimestamp("created_at"), rows.getTimestamp("updated_at"));
  }

  private static Records.Entry readEntry(ResultSet rows) throws SQLException {
    return new Records.Entry(rows.getLong("id"), longOrNull(rows, "task_id"),
        longOrNull(rows, "def_id"), longOrNull(rows, "project_id"), rows.getLong("user_id"),
        rows.getInt("set_index"), doubleOrNull(rows, "weight"), intOrNull(rows, "reps"),
        intOrNull(rows, "seconds"), doubleOrNull(rows, "distance"),
        intOrNull(rows, "difficulty"), intOrNull(rows, "time_cost"), intOrNull(rows, "impact"),
        rows.getString("note"), rows.getTimestamp("recorded_at"));
  }

  static List<String> phasesOf(String json) {
    ArrayList<String> phases = new ArrayList<>();
    try {
      for (com.fasterxml.jackson.databind.JsonNode node : JSON.readTree(
          json == null || json.isBlank() ? "[]" : json)) {
        String name = node.asText("").trim();
        if (!name.isEmpty() && phases.size() < 8) {
          phases.add(name);
        }
      }
    } catch (Exception ex) {
      return List.of();
    }
    return phases;
  }

  static String phasesJson(List<String> phases) {
    ArrayNode array = JSON.createArrayNode();
    if (phases != null) {
      for (String phase : phases) {
        if (phase != null && !phase.isBlank() && array.size() < 8) {
          array.add(cap(phase, 64));
        }
      }
    }
    return array.toString();
  }

  static String slugOf(String name) {
    String clean = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    return clean.isEmpty() ? "x" : cap(clean, 128);
  }

  private static String cap(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
