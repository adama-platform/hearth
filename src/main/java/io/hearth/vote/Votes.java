package io.hearth.vote;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A decision several people — and several agents — are making together.
 *
 * <b>The shape is a pool of options that evolves.</b> Not a ballot with a fixed slate: an agent
 * proposes Thursday, another proposes the Thursday after, somebody's agent says both are bad and
 * offers a Sunday, and the pool grows. Narrowing is a deliberate step that keeps the top few and
 * records what was dropped, so what humans finally choose between is a short list with a reason
 * behind it rather than forty dates.
 *
 * <b>Options and history are two JSON blobs on one row.</b> Options are the current state; history
 * is append-only and holds every proposal, ballot and narrowing in order. That is a deliberate
 * choice against a table of ballots: the shape of what an agent wants to say is still being
 * discovered, and "Thursday works but Friday is better" should not need a migration. One row is
 * also one lock — two agents voting in the same second cannot interleave into half a ballot.
 *
 * <b>The history is the feature.</b> What everybody asks afterwards is not what won but why, and a
 * tally that cannot show its working is one nobody trusts. That goes double when half the voters
 * are agents acting for people who were asleep at the time.
 *
 * <b>One ballot per voter per option, last one wins.</b> Changing your mind is normal and an agent
 * that learns something new should be able to revise; the history keeps both, so a vote that
 * flipped late is visible rather than silently overwritten.
 */
public class Votes {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** how many options a vote may hold, so a runaway agent cannot make an unreadable ballot */
  public static final int MAX_OPTIONS = 60;

  /** how many entries the history keeps; older ones fall off the front */
  public static final int MAX_HISTORY = 2000;

  private final Store store;

  public Votes(Store store) {
    this.store = store;
  }

  /** where a vote is in its life */
  public enum State {
    /** anybody may add options and vote */
    open,
    /** narrowed to a short list; voting continues on what is left */
    narrowed,
    /** there is an outcome */
    decided,
    /** it is over and there is not one */
    abandoned;

    public static State of(String raw) {
      if (raw == null) {
        return null;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }

    public boolean acceptsBallots() {
      return this == open || this == narrowed;
    }
  }

  /**
   * How somebody feels about one option.
   *
   * <b>Four values rather than a number.</b> A score invites an agent to invent a scale and two
   * agents to invent different ones; these four are the distinctions that actually change an
   * outcome. `blocked` is the important one: it is not a strong `no`, it means *this cannot
   * happen*, and one of them removes an option however many yes votes it has. Scheduling without a
   * way to say "I am out of the country" produces dates nobody can make.
   */
  public enum Ballot {
    yes(2),
    fine(1),
    no(0),
    blocked(-1);

    public final int weight;

    Ballot(int weight) {
      this.weight = weight;
    }

    public static Ballot of(String raw) {
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

  /** a refusal somebody or something should be told about in words */
  public static class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  public record Record(long id, String slug, String title, String question, State state,
                       String options, String history, String outcome, Long openedBy,
                       Timestamp updatedAt) {
    public JsonNode optionsJson() {
      return read(options, JSON.createArrayNode());
    }

    public JsonNode historyJson() {
      return read(history, JSON.createArrayNode());
    }

    private static JsonNode read(String raw, JsonNode fallback) {
      try {
        return raw == null || raw.isBlank() ? fallback : JSON.readTree(raw);
      } catch (Exception ex) {
        return fallback;
      }
    }
  }

  // ---- reading ---------------------------------------------------------------------------------

  public List<Record> all(boolean openOnly) throws SQLException {
    ArrayList<Record> rows = new ArrayList<>();
    String sql = "SELECT * FROM " + Schema.VOTES
        + (openOnly ? " WHERE state IN ('open','narrowed')" : "")
        + " ORDER BY updated_at DESC";
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(sql);
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
             "SELECT * FROM " + Schema.VOTES + " WHERE slug = ?")) {
      statement.setString(1, slug.trim().toLowerCase(Locale.ROOT));
      try (ResultSet found = statement.executeQuery()) {
        return found.next() ? read(found) : null;
      }
    }
  }

  // ---- opening ---------------------------------------------------------------------------------

  /**
   * Start a vote.
   *
   * The slug is the handle every later call uses, so it is checked to the same shape a table name
   * is: something an agent can hold without quoting.
   */
  public Record open(String slug, String title, String question, List<String> firstOptions,
                     long actor, String actorName) throws SQLException, Refused {
    String clean = normalize(slug);
    if (!clean.matches("[a-z][a-z0-9-]{1,63}")) {
      return refuse("a vote's name is lowercase letters, digits and dashes, 2 to 64 characters");
    }
    if (bySlug(clean) != null) {
      return refuse("there is already a vote called '" + clean + "'");
    }
    if (title == null || title.isBlank()) {
      return refuse("a vote needs a title");
    }
    ArrayNode options = JSON.createArrayNode();
    ArrayNode history = JSON.createArrayNode();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (String label : firstOptions == null ? List.<String>of() : firstOptions) {
      addOption(options, seen, label, actor, actorName, history);
    }
    note(history, "opened", actor, actorName, Map.of("title", title));
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO " + Schema.VOTES
                 + " (slug, title, question, state, options, history, opened_by)"
                 + " VALUES (?, ?, ?, 'open', ?, ?, ?)")) {
      statement.setString(1, clean);
      statement.setString(2, title.trim());
      statement.setString(3, question == null ? "" : question.trim());
      statement.setString(4, options.toString());
      statement.setString(5, history.toString());
      statement.setLong(6, actor);
      statement.executeUpdate();
    }
    store.changed(Schema.VOTES, clean, MutationEvent.Kind.insert, actor);
    return bySlug(clean);
  }

  // ---- the pool --------------------------------------------------------------------------------

  /**
   * Offer another option.
   *
   * Anybody may, including after voting has started, and that is the design: the pool evolves. A
   * duplicate label is refused rather than merged, because two options that read the same split the
   * vote and nobody notices.
   */
  public synchronized Record propose(String slug, String label, String detail, long actor,
                                     String actorName) throws SQLException, Refused {
    Record vote = require(slug);
    if (!vote.state().acceptsBallots()) {
      return refuse("'" + slug + "' is " + vote.state() + "; it is not taking options any more");
    }
    ArrayNode options = (ArrayNode) vote.optionsJson();
    ArrayNode history = (ArrayNode) vote.historyJson();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (JsonNode option : options) {
      seen.add(option.path("label").asText("").toLowerCase(Locale.ROOT));
    }
    ObjectNode added = addOption(options, seen, label, actor, actorName, history);
    if (detail != null && !detail.isBlank()) {
      added.put("detail", detail.trim());
    }
    save(vote, options, history, vote.state(), vote.outcome(), actor);
    return bySlug(slug);
  }

  private ObjectNode addOption(ArrayNode options, LinkedHashSet<String> seen, String label,
                               long actor, String actorName, ArrayNode history) throws Refused {
    if (label == null || label.isBlank()) {
      throw new Refused("an option needs a label");
    }
    String clean = label.trim();
    if (clean.length() > 200) {
      throw new Refused("an option's label is at most 200 characters; put the rest in detail");
    }
    if (!seen.add(clean.toLowerCase(Locale.ROOT))) {
      throw new Refused("'" + clean + "' is already an option");
    }
    if (options.size() >= MAX_OPTIONS) {
      throw new Refused("this vote already has " + MAX_OPTIONS + " options, which is more than"
          + " anybody can choose between. Narrow it before adding more.");
    }
    ObjectNode option = options.addObject();
    option.put("id", "o" + (options.size()));
    option.put("label", clean);
    option.put("proposed_by", actorName);
    note(history, "proposed", actor, actorName, Map.of("option", clean));
    return option;
  }

  // ---- voting ----------------------------------------------------------------------------------

  /**
   * Cast or change a ballot.
   *
   * <b>One per voter per option, and the last one wins.</b> An agent that learns something after
   * voting should be able to revise, and the history keeps both, so a late change is visible rather
   * than silently replacing what came before.
   */
  public synchronized Record cast(String slug, String optionLabel, Ballot ballot, String because,
                                  long actor, String actorName) throws SQLException, Refused {
    Record vote = require(slug);
    if (!vote.state().acceptsBallots()) {
      return refuse("'" + slug + "' is " + vote.state() + "; it is not taking votes any more");
    }
    if (ballot == null) {
      return refuse("a vote is one of: yes, fine, no, blocked");
    }
    ArrayNode options = (ArrayNode) vote.optionsJson();
    ObjectNode option = findOption(options, optionLabel);
    if (option == null) {
      return refuse("'" + slug + "' has no option called '" + optionLabel + "'. Propose it first,"
          + " or vote on one of: " + labels(options));
    }
    ObjectNode ballots = option.has("ballots") && option.get("ballots").isObject()
        ? (ObjectNode) option.get("ballots") : option.putObject("ballots");
    ObjectNode entry = ballots.putObject(String.valueOf(actor));
    entry.put("who", actorName);
    entry.put("vote", ballot.name());
    if (because != null && !because.isBlank()) {
      entry.put("because", because.trim());
    }
    ArrayNode history = (ArrayNode) vote.historyJson();
    LinkedHashMap<String, Object> what = new LinkedHashMap<>();
    what.put("option", option.path("label").asText());
    what.put("vote", ballot.name());
    if (because != null && !because.isBlank()) {
      what.put("because", because.trim());
    }
    note(history, "voted", actor, actorName, what);
    save(vote, options, history, vote.state(), vote.outcome(), actor);
    return bySlug(slug);
  }

  // ---- converging ------------------------------------------------------------------------------

  /**
   * Keep the best few and say what was dropped.
   *
   * This is the step that makes the thing usable by people: agents can propose forty dates between
   * them, and nobody is going to read forty. What is kept is recorded along with what went, so the
   * humans choosing between three dates can see the other thirty existed and why they are gone.
   *
   * A blocked option is dropped whatever its score. One person saying "I cannot" is not outvoted by
   * three saying "I would like to" -- that produces a date somebody cannot attend, which is the one
   * outcome worse than no date.
   */
  public synchronized Record narrow(String slug, int keep, long actor, String actorName)
      throws SQLException, Refused {
    Record vote = require(slug);
    if (!vote.state().acceptsBallots()) {
      return refuse("'" + slug + "' is " + vote.state() + "; there is nothing to narrow");
    }
    int wanted = Math.max(2, Math.min(10, keep));
    List<Tally> tallies = tally(vote);
    ArrayList<Tally> viable = new ArrayList<>();
    ArrayList<String> dropped = new ArrayList<>();
    for (Tally each : tallies) {
      if (each.blocked() > 0) {
        dropped.add(each.label() + " (blocked by " + each.blockedBy() + ")");
      } else {
        viable.add(each);
      }
    }
    if (viable.isEmpty()) {
      return refuse("every option is blocked by somebody. Propose something else before narrowing.");
    }
    while (viable.size() > wanted) {
      Tally worst = viable.remove(viable.size() - 1);
      dropped.add(worst.label() + " (scored " + worst.score() + ")");
    }
    LinkedHashSet<String> kept = new LinkedHashSet<>();
    for (Tally each : viable) {
      kept.add(each.label());
    }
    ArrayNode options = JSON.createArrayNode();
    for (JsonNode option : vote.optionsJson()) {
      if (kept.contains(option.path("label").asText())) {
        options.add(option);
      }
    }
    ArrayNode history = (ArrayNode) vote.historyJson();
    note(history, "narrowed", actor, actorName,
        Map.of("kept", new ArrayList<>(kept), "dropped", dropped));
    save(vote, options, history, State.narrowed, vote.outcome(), actor);
    return bySlug(slug);
  }

  /** settle it */
  public synchronized Record decide(String slug, String optionLabel, long actor, String actorName)
      throws SQLException, Refused {
    Record vote = require(slug);
    if (vote.state() == State.decided) {
      return refuse("'" + slug + "' was already decided: " + vote.outcome());
    }
    ArrayNode options = (ArrayNode) vote.optionsJson();
    ObjectNode option = findOption(options, optionLabel);
    if (option == null) {
      return refuse("'" + slug + "' has no option called '" + optionLabel + "'. It is one of: "
          + labels(options));
    }
    ArrayNode history = (ArrayNode) vote.historyJson();
    note(history, "decided", actor, actorName, Map.of("option", option.path("label").asText()));
    save(vote, options, history, State.decided, option.path("label").asText(), actor);
    return bySlug(slug);
  }

  public synchronized Record abandon(String slug, String why, long actor, String actorName)
      throws SQLException, Refused {
    Record vote = require(slug);
    ArrayNode history = (ArrayNode) vote.historyJson();
    note(history, "abandoned", actor, actorName, Map.of("why", why == null ? "" : why));
    save(vote, (ArrayNode) vote.optionsJson(), history, State.abandoned, "", actor);
    return bySlug(slug);
  }

  // ---- counting --------------------------------------------------------------------------------

  /** one option's standing, highest first */
  public record Tally(String label, int score, int yes, int fine, int no, int blocked,
                      String blockedBy, int voters) {
  }

  /**
   * Where the vote stands.
   *
   * Sorted by score, then by how many outright yes votes -- two options on the same score are not
   * equal if one has enthusiasm behind it and the other has shrugs. The blocked count is carried
   * separately rather than folded into the score, because it is a veto and not a low number.
   */
  public List<Tally> tally(Record vote) {
    ArrayList<Tally> tallies = new ArrayList<>();
    for (JsonNode option : vote.optionsJson()) {
      int score = 0;
      int yes = 0;
      int fine = 0;
      int no = 0;
      int blocked = 0;
      int voters = 0;
      ArrayList<String> blockers = new ArrayList<>();
      JsonNode ballots = option.path("ballots");
      java.util.Iterator<String> names = ballots.fieldNames();
      while (names.hasNext()) {
        JsonNode each = ballots.get(names.next());
        Ballot ballot = Ballot.of(each.path("vote").asText());
        if (ballot == null) {
          continue;
        }
        voters++;
        switch (ballot) {
          case yes -> yes++;
          case fine -> fine++;
          case no -> no++;
          case blocked -> {
            blocked++;
            blockers.add(each.path("who").asText("somebody"));
          }
        }
        score += ballot.weight;
      }
      tallies.add(new Tally(option.path("label").asText(), score, yes, fine, no, blocked,
          String.join(", ", blockers), voters));
    }
    tallies.sort((left, right) -> {
      if (left.blocked() != right.blocked()) {
        return Integer.compare(left.blocked(), right.blocked());
      }
      if (left.score() != right.score()) {
        return Integer.compare(right.score(), left.score());
      }
      return Integer.compare(right.yes(), left.yes());
    });
    return tallies;
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Record require(String slug) throws SQLException, Refused {
    Record vote = bySlug(slug);
    if (vote == null) {
      throw new Refused("there is no vote called '" + slug + "'");
    }
    return vote;
  }

  private static Record refuse(String message) throws Refused {
    throw new Refused(message);
  }

  private static ObjectNode findOption(ArrayNode options, String label) {
    if (label == null) {
      return null;
    }
    String wanted = label.trim().toLowerCase(Locale.ROOT);
    for (JsonNode option : options) {
      if (option.path("label").asText("").toLowerCase(Locale.ROOT).equals(wanted)
          || option.path("id").asText("").equals(label.trim())) {
        return (ObjectNode) option;
      }
    }
    return null;
  }

  private static String labels(ArrayNode options) {
    ArrayList<String> out = new ArrayList<>();
    for (JsonNode option : options) {
      out.add(option.path("label").asText());
    }
    return out.isEmpty() ? "(none yet)" : String.join(", ", out);
  }

  /** one line in the history; the whole point is that it is never rewritten */
  private static void note(ArrayNode history, String what, long actor, String actorName,
                           Map<String, Object> detail) {
    ObjectNode entry = JSON.createObjectNode();
    entry.put("at", System.currentTimeMillis());
    entry.put("what", what);
    entry.put("by", actorName == null ? String.valueOf(actor) : actorName);
    entry.set("detail", JSON.valueToTree(detail));
    history.add(entry);
    while (history.size() > MAX_HISTORY) {
      history.remove(0);
    }
  }

  private void save(Record vote, ArrayNode options, ArrayNode history, State state, String outcome,
                    long actor) throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.VOTES + " SET options = ?, history = ?, state = ?, outcome = ?,"
                 + " updated_at = ? WHERE id = ?")) {
      statement.setString(1, options.toString());
      statement.setString(2, history.toString());
      statement.setString(3, state.name());
      statement.setString(4, outcome == null ? "" : outcome);
      statement.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
      statement.setLong(6, vote.id());
      statement.executeUpdate();
    }
    store.changed(Schema.VOTES, vote.slug(), MutationEvent.Kind.update, actor);
  }

  private static Record read(ResultSet found) throws SQLException {
    long by = found.getLong("opened_by");
    boolean noActor = found.wasNull();
    return new Record(found.getLong("id"), found.getString("slug"), found.getString("title"),
        found.getString("question"), State.of(found.getString("state")),
        found.getString("options"), found.getString("history"), found.getString("outcome"),
        noActor ? null : by, found.getTimestamp("updated_at"));
  }

  public static String normalize(String slug) {
    return slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
  }
}
