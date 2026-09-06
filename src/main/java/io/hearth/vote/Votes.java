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

  /**
   * How a vote decides, which is a property of the group rather than of the software.
   *
   * <b>Consensus works for five people and fails for twenty.</b> With five friends, one `blocked`
   * removing an evening is right: the point is that everybody comes. With twenty, somebody is
   * always away, every option gets a veto, and the vote converges on nothing -- so the group that
   * wanted a party gets no party because one person is in another country.
   *
   * So the mode is chosen when the vote is opened. `majority` maximises attendance: a block counts
   * as a strong no and the option with the most people who can come wins. `consensus` is the
   * default because the small case is the common one here and it is the one where an unattendable
   * date is the worse failure.
   */
  public enum Mode {
    /** one block removes an option: everybody comes, or it is not the evening */
    consensus,
    /** the most people who can make it wins; a block is a strong no rather than a veto */
    majority;

    public static Mode of(String raw) {
      if (raw == null) {
        return consensus;
      }
      try {
        return valueOf(raw.trim().toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return consensus;
      }
    }
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
                       Mode mode, Long hostId, boolean hostAccepted, Timestamp invitedAt,
                       Timestamp updatedAt) {
    public boolean hasHost() {
      return hostId != null && hostId > 0;
    }

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
    return open(slug, title, question, firstOptions, Mode.consensus, null, actor, actorName);
  }

  /**
   * Start a vote, saying how it decides and who is hosting.
   *
   * <b>The host is part of the vote, not a note about it.</b> Somebody has to have the room, the
   * table and the willingness, and their "I cannot" is final in either mode -- a majority cannot
   * vote somebody into hosting. That is why {@link #tally} treats a host block as removing the
   * option even under `majority`.
   */
  public Record open(String slug, String title, String question, List<String> firstOptions,
                     Mode mode, Long hostId, long actor, String actorName)
      throws SQLException, Refused {
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
                 + " (slug, title, question, state, options, history, opened_by, mode, host_id)"
                 + " VALUES (?, ?, ?, 'open', ?, ?, ?, ?, ?)")) {
      statement.setString(1, clean);
      statement.setString(2, title.trim());
      statement.setString(3, question == null ? "" : question.trim());
      statement.setString(4, options.toString());
      statement.setString(5, history.toString());
      statement.setLong(6, actor);
      statement.setString(7, (mode == null ? Mode.consensus : mode).name());
      if (hostId == null || hostId <= 0) {
        statement.setNull(8, java.sql.Types.BIGINT);
      } else {
        statement.setLong(8, hostId);
      }
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
      if (each.ruledOut(vote.mode())) {
        dropped.add(each.label() + (each.hostBlocked()
            ? " (the host cannot)" : " (blocked by " + each.blockedBy() + ")"));
      } else {
        viable.add(each);
      }
    }
    if (viable.isEmpty()) {
      return refuse(vote.mode() == Mode.consensus
          ? "every option is blocked by somebody. Propose something else, or open this as a"
              + " majority vote if the group is too large for everybody to make one evening."
          : "the host cannot do any of these. Propose something else.");
    }
    while (viable.size() > wanted) {
      Tally worst = viable.remove(viable.size() - 1);
      dropped.add(worst.label() + (vote.mode() == Mode.majority
          ? " (" + worst.canCome() + " could come)" : " (scored " + worst.score() + ")"));
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

  /**
   * The host says yes or no, and that is the gate the invitation waits behind.
   *
   * <b>Nobody else has been told at this point.</b> That is the whole reason this step exists: "will
   * you have people round on the 9th" is a question one person can answer no to, and "we are
   * meeting at Ana's on the 9th" is not. Sending the second when you meant the first is how
   * somebody finds out they are hosting from a group email.
   *
   * A no puts the vote back to narrowed rather than abandoning it: the group still wants the
   * evening, they just need a different one or a different host.
   */
  public synchronized Record hostAnswer(String slug, boolean yes, String why, long actor,
                                        String actorName) throws SQLException, Refused {
    Record vote = require(slug);
    if (!vote.hasHost()) {
      return refuse("'" + slug + "' has no host to answer for it");
    }
    if (vote.hostId() != actor) {
      return refuse("only the host can answer that");
    }
    ArrayNode history = (ArrayNode) vote.historyJson();
    note(history, yes ? "host_accepted" : "host_declined", actor, actorName,
        Map.of("why", why == null ? "" : why));
    if (yes) {
      saveFull(vote, (ArrayNode) vote.optionsJson(), history, vote.state(), vote.outcome(),
          true, vote.invitedAt(), actor);
    } else {
      saveFull(vote, (ArrayNode) vote.optionsJson(), history, State.narrowed, "",
          false, null, actor);
    }
    return bySlug(slug);
  }

  /** the invitation went out; recorded so it goes out once */
  public synchronized Record markInvited(String slug, long actor, String actorName)
      throws SQLException, Refused {
    Record vote = require(slug);
    ArrayNode history = (ArrayNode) vote.historyJson();
    note(history, "invited", actor, actorName, Map.of("outcome", vote.outcome()));
    saveFull(vote, (ArrayNode) vote.optionsJson(), history, vote.state(), vote.outcome(),
        vote.hostAccepted(), new Timestamp(System.currentTimeMillis()), actor);
    return bySlug(slug);
  }

  /** is this vote ready for people to be told about it? */
  public boolean readyToInvite(Record vote) {
    return vote.state() == State.decided
        && !vote.outcome().isBlank()
        && vote.invitedAt() == null
        && (!vote.hasHost() || vote.hostAccepted());
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
                      String blockedBy, int voters, boolean hostBlocked, int canCome) {
    /**
     * Is this option out, whatever the numbers?
     *
     * Under consensus, any block. Under majority, only the host's -- a majority cannot vote
     * somebody into having people round their house.
     */
    public boolean ruledOut(Mode mode) {
      return hostBlocked || (mode == Mode.consensus && blocked > 0);
    }
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
    String host = vote.hasHost() ? String.valueOf(vote.hostId()) : null;
    for (JsonNode option : vote.optionsJson()) {
      int score = 0;
      int yes = 0;
      int fine = 0;
      int no = 0;
      int blocked = 0;
      int voters = 0;
      boolean hostBlocked = false;
      ArrayList<String> blockers = new ArrayList<>();
      JsonNode ballots = option.path("ballots");
      java.util.Iterator<String> names = ballots.fieldNames();
      while (names.hasNext()) {
        String voter = names.next();
        JsonNode each = ballots.get(voter);
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
            if (voter.equals(host)) {
              hostBlocked = true;
            }
          }
        }
        score += ballot.weight;
      }
      // how many people could actually turn up, which is what majority maximises. `no` is somebody
      // who would rather not and still could; `blocked` is somebody who cannot.
      int canCome = yes + fine + no;
      tallies.add(new Tally(option.path("label").asText(), score, yes, fine, no, blocked,
          String.join(", ", blockers), voters, hostBlocked, canCome));
    }
    // The order is the mode.
    //
    // Consensus sorts blocked options to the bottom, because one veto ends them. Majority sorts by
    // how many people can come, because that is the thing it is maximising -- an evening four out
    // of twenty cannot make still beats one six cannot. A host block sinks an option in either.
    Mode mode = vote.mode();
    tallies.sort((left, right) -> {
      if (left.hostBlocked() != right.hostBlocked()) {
        return Boolean.compare(left.hostBlocked(), right.hostBlocked());
      }
      if (mode == Mode.consensus) {
        if (left.blocked() != right.blocked()) {
          return Integer.compare(left.blocked(), right.blocked());
        }
        if (left.score() != right.score()) {
          return Integer.compare(right.score(), left.score());
        }
        return Integer.compare(right.yes(), left.yes());
      }
      if (left.canCome() != right.canCome()) {
        return Integer.compare(right.canCome(), left.canCome());
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
    saveFull(vote, options, history, state, outcome, vote.hostAccepted(), vote.invitedAt(), actor);
  }

  private void saveFull(Record vote, ArrayNode options, ArrayNode history, State state,
                        String outcome, boolean hostAccepted, Timestamp invitedAt, long actor)
      throws SQLException {
    try (Connection connection = store.connection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE " + Schema.VOTES + " SET options = ?, history = ?, state = ?, outcome = ?,"
                 + " host_accepted = ?, invited_at = ?, updated_at = ? WHERE id = ?")) {
      statement.setString(1, options.toString());
      statement.setString(2, history.toString());
      statement.setString(3, state.name());
      statement.setString(4, outcome == null ? "" : outcome);
      statement.setBoolean(5, hostAccepted);
      statement.setTimestamp(6, invitedAt);
      statement.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
      statement.setLong(8, vote.id());
      statement.executeUpdate();
    }
    store.changed(Schema.VOTES, vote.slug(), MutationEvent.Kind.update, actor);
  }

  private static Record read(ResultSet found) throws SQLException {
    long by = found.getLong("opened_by");
    boolean noActor = found.wasNull();
    long host = found.getLong("host_id");
    boolean noHost = found.wasNull();
    return new Record(found.getLong("id"), found.getString("slug"), found.getString("title"),
        found.getString("question"), State.of(found.getString("state")),
        found.getString("options"), found.getString("history"), found.getString("outcome"),
        noActor ? null : by, Mode.of(found.getString("mode")), noHost ? null : host,
        found.getBoolean("host_accepted"), found.getTimestamp("invited_at"),
        found.getTimestamp("updated_at"));
  }

  public static String normalize(String slug) {
    return slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
  }
}
