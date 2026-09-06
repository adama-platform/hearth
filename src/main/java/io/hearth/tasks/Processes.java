package io.hearth.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hearth.events.MutationEvent;
import io.hearth.store.Schema;
import io.hearth.store.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * A named sequence of states a task can walk.
 *
 * <b>Because done/not-done is a lie about most work worth tracking.</b> A fence repair goes
 * surveyed → materials → built → checked; a calf goes tagged → weaned → sold. Which step a thing is
 * at is the useful fact, and "not done" throws it away.
 *
 * <b>Defined once, pointed at by many.</b> The alternative is forty tasks called "step 2", which is
 * what a spreadsheet degenerates into and the reason this is a stored thing rather than a
 * convention.
 *
 * <b>The order is the meaning.</b> States are a list, not a set: it is what "the next step" means
 * and what a sheet sorts by. Nothing here enforces that a task moves forward one at a time --
 * skipping back is a normal thing to need and a machine that refuses it is a machine somebody works
 * around by deleting the task.
 */
public class Processes {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** enough for anything worth calling a process; more is a sign it should be several */
  public static final int MAX_STATES = 12;

  private final Store store;

  public Processes(Store store) {
    this.store = store;
  }

  public record Record(long id, String slug, String title, List<String> states) {
  }

  public List<Record> all() throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.PROCESSES + " ORDER BY slug");
         ResultSet found = statement.executeQuery()) {
      while (found.next()) {
        rows.add(read(found));
      }
    }
    return rows;
  }

  public Record bySlug(String slug) throws SQLException {
    if (slug == null || slug.isBlank()) {
      return null;
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT * FROM " + Schema.PROCESSES + " WHERE slug = ?")) {
      statement.setString(1, slug.trim().toLowerCase(Locale.ROOT));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  /** the states of a process, or an empty list if there is no such process */
  public List<String> statesOf(String slug) throws SQLException {
    Record process = bySlug(slug);
    return process == null ? List.of() : process.states();
  }

  /**
   * Define or redefine one.
   *
   * <b>Redefining does not migrate the tasks walking it.</b> A task sitting in a state that has
   * been removed keeps that state and the sheet still shows it -- the alternative is quietly moving
   * somebody's work to a step it has not reached, which is a worse thing than an odd-looking row.
   * The refusal for a *new* task naming a state that does not exist is where the check belongs.
   */
  public Record save(String slug, String title, List<String> states, long actor)
      throws SQLException, Tasks.Refused {
    String clean = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
    if (!clean.matches("[a-z][a-z0-9-]{1,63}")) {
      throw new Tasks.Refused("a process name is lowercase letters, digits and dashes");
    }
    LinkedHashSet<String> ordered = new LinkedHashSet<>();
    for (String state : states == null ? List.<String>of() : states) {
      String name = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
      if (name.isEmpty()) {
        continue;
      }
      if (!name.matches("[a-z][a-z0-9_-]{0,31}")) {
        throw new Tasks.Refused("'" + state + "' is not a state name: lowercase letters, digits,"
            + " dashes and underscores");
      }
      if (name.equals(Tasks.DONE) || name.equals(Tasks.DROPPED)) {
        throw new Tasks.Refused("'" + name + "' is a state every task already has");
      }
      ordered.add(name);
    }
    if (ordered.size() < 2) {
      throw new Tasks.Refused("a process needs at least two states; anything with one is just a"
          + " task, which is already open or done");
    }
    if (ordered.size() > MAX_STATES) {
      throw new Tasks.Refused("a process with more than " + MAX_STATES + " states is several"
          + " processes wearing a coat");
    }
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "MERGE INTO " + Schema.PROCESSES + " (slug, title, states) KEY (slug)"
                 + " VALUES (?, ?, ?)")) {
      statement.setString(1, clean);
      statement.setString(2, title == null || title.isBlank() ? clean : title.trim());
      statement.setString(3, Tasks.array(new ArrayList<>(ordered)).toString());
      statement.executeUpdate();
    }
    store.changed(Schema.PROCESSES, clean, MutationEvent.Kind.update, actor);
    return bySlug(clean);
  }

  public void delete(String slug, long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM " + Schema.PROCESSES + " WHERE slug = ?")) {
      statement.setString(1, slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT));
      statement.executeUpdate();
    }
    store.changed(Schema.PROCESSES, slug, MutationEvent.Kind.delete, actor);
  }

  private static Record read(ResultSet found) throws SQLException {
    JsonNode states;
    try {
      states = JSON.readTree(found.getString("states"));
    } catch (Exception ex) {
      states = JSON.createArrayNode();
    }
    return new Record(found.getLong("id"), found.getString("slug"), found.getString("title"),
        Tasks.listOf(states));
  }
}
