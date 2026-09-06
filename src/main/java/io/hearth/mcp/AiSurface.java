package io.hearth.mcp;

import io.hearth.auth.Accounts;
import io.hearth.content.ContentRecord;
import io.hearth.content.TemplateField;
import io.hearth.content.TemplateField;
import io.hearth.content.TemplateRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything an agent is allowed to do, and the only way it can do it.
 *
 * This class exists to be the single narrow place where "is AI allowed to touch this" is answered.
 * The tools above it are shaped for a model; the stores below it know nothing about models. Putting
 * the check anywhere else -- in each tool, say -- would mean the rule is enforced N times and can be
 * forgotten once, which for a rule like this is the same as not having it.
 *
 * **Human only** is asymmetric on purpose, and the asymmetry is worth stating plainly:
 *
 * - **Reads are invisible.** A locked page is not in a listing, not in a search result, and not
 *   fetchable. Not "forbidden" -- absent. An agent has no way to learn it exists.
 * - **Writes are refused, out loud.** Saving to a locked uri comes back as a refusal that says so.
 *
 * The tempting symmetry -- refuse quietly, or pretend the write worked -- is wrong both ways. If a
 * locked uri simply looked empty to a write, an agent asked to "add an about page" would happily
 * overwrite the one thing somebody locked; and a write that claims success while doing nothing
 * teaches a model that it succeeded. Leaking the existence of a uri to something that was already
 * being told "no" is a much smaller problem than either.
 */
public class AiSurface {
  /** how much of a body to hand back in a listing before it stops being a listing */
  private static final int SNIPPET = 400;
  private static final int MAX_ROWS = 200;

  private final Accounts accounts;
  private final boolean readOnly;



  public AiSurface(Accounts accounts, boolean readOnly) {
    this.accounts = accounts;
    this.readOnly = readOnly;
  }

  /** a refusal an agent can read and act on */
  public static class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  /**
   * May the person this connection belongs to do this?
   *
   * <b>An agent can never do anything the person could not.</b> That is invariant 26 stated as a
   * rule about sessions; this is it enforced as a rule about tools. Before this, every tool was
   * reachable by any connection, which was defensible only while the endpoint was admin-only and
   * every admin held everything -- and stopped being defensible the moment the board grew things a
   * plain member may do and a moderator may not.
   *
   * The refusal names the permission, because the model's next useful move is to tell the person
   * holding the connection what they are missing rather than to try a different phrasing.
   */
  private void assertCan(io.hearth.auth.Permission permission) throws SQLException, Refused {
    io.hearth.auth.UserRecord me = actorId == null ? null : accounts.users.byId(actorId);
    if (me == null) {
      throw new Refused("This connection is not acting for anybody.");
    }
    if (!accounts.access.can(me, permission)) {
      throw new Refused("The person this connection belongs to is not allowed to do that. It"
          + " needs '" + permission.label + "'. Do not try another way round it -- tell them, and"
          + " somebody who can grant it will.");
    }
  }

  /**
   * The quiet half of the same question.
   *
   * <b>A read is narrowed, never refused.</b> When the endpoint was admin-only, every connection
   * could see drafts, suggestions and unpublished places, because every connection belonged to
   * somebody who could see them on a screen. Now that a member can connect, the honest answer to
   * "list the events" from a member's agent is *the events they can see*, not a refusal -- a
   * refusal would make the tool useless for the person it belongs to, and returning everything
   * would hand them a draft they could not open in a browser.
   *
   * This is the same asymmetry human-only already has (invariant 28): what somebody may not see is
   * absent rather than forbidden.
   */
  /** the same question, for the listing that decides which tools to offer at all */
  public boolean may(io.hearth.auth.Permission permission) throws SQLException {
    return mayI(permission);
  }

  private boolean mayI(io.hearth.auth.Permission permission) throws SQLException {
    io.hearth.auth.UserRecord me = actorId == null ? null : accounts.users.byId(actorId);
    return me != null && accounts.access.can(me, permission);
  }

  private void assertWritable() throws Refused {
    if (readOnly) {
      throw new Refused("This site is connected read only. An admin can change that with"
          + " mcp.read-only in the domain config.");
    }
  }

  // ---- getting people together -----------------------------------------------------------------

  /**
   * Votes, opened and voted in by agents acting for people.
   *
   * <b>Every agent acts as its person and votes as them.</b> There is no argument for whose ballot
   * this is, exactly as there is none for whose Hevy key -- so an agent can be as pushy as it likes
   * and still only ever cast one vote, the one belonging to whoever connected it.
   *
   * <b>Reading is open to anybody connected; writing is too.</b> No permission gates a vote,
   * deliberately: the whole feature is friends' agents converging on a date, and a permission would
   * mean an admin granting each friend the right to have an opinion.
   */
  private String actorName() throws SQLException {
    io.hearth.auth.UserRecord me = actorId == null ? null : accounts.users.byId(actorId);
    if (me == null) {
      return "somebody";
    }
    String name = accounts.people.profileOf(me.id()).nameOr("");
    // a display name if they have one, never the email: a vote is read by other people's agents
    return name.isBlank() ? "member " + me.id() : name;
  }

  public List<Map<String, Object>> listVotes(boolean openOnly) throws SQLException, Refused {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.vote.Votes.Record vote : accounts.votes.all(openOnly)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("vote", vote.slug());
      row.put("title", vote.title());
      row.put("state", vote.state().name());
      row.put("options", vote.optionsJson().size());
      if (!vote.outcome().isBlank()) {
        row.put("outcome", vote.outcome());
      }
      rows.add(row);
    }
    return rows;
  }

  /**
   * One vote in full: where it stands and how it got there.
   *
   * The history comes back with it rather than behind a second tool, because the question an agent
   * needs answered before voting is not "what are the options" but "what has already been said" --
   * and an agent that has to ask twice will often not.
   */
  public Map<String, Object> getVote(String slug) throws SQLException, Refused {
    io.hearth.vote.Votes.Record vote = accounts.votes.bySlug(slug);
    if (vote == null) {
      throw new Refused("there is no vote called '" + slug + "'");
    }
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    out.put("vote", vote.slug());
    out.put("title", vote.title());
    out.put("question", vote.question());
    out.put("state", vote.state().name());
    out.put("outcome", vote.outcome());
    ArrayList<Map<String, Object>> standing = new ArrayList<>();
    for (io.hearth.vote.Votes.Tally tally : accounts.votes.tally(vote)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("option", tally.label());
      row.put("score", tally.score());
      row.put("yes", tally.yes());
      row.put("fine", tally.fine());
      row.put("no", tally.no());
      row.put("blocked", tally.blocked());
      if (tally.blocked() > 0) {
        row.put("blocked_by", tally.blockedBy());
      }
      row.put("voters", tally.voters());
      standing.add(row);
    }
    out.put("standing", standing);
    out.put("options", JSON_NODES.convertValue(vote.optionsJson(), List.class));
    out.put("history", JSON_NODES.convertValue(vote.historyJson(), List.class));
    return out;
  }

  public Map<String, Object> openVote(Map<String, Object> args) throws SQLException, Refused {
    assertWritable();
    ArrayList<String> first = new ArrayList<>();
    Object raw = args.get("options");
    if (raw instanceof List<?> list) {
      for (Object one : list) {
        first.add(String.valueOf(one));
      }
    }
    try {
      accounts.votes.open(str(args, "vote"), str(args, "title"), str(args, "question"),
          first, gymActor(), actorName());
    } catch (io.hearth.vote.Votes.Refused refused) {
      throw new Refused(refused.getMessage());
    }
    return getVote(str(args, "vote"));
  }

  public Map<String, Object> proposeOption(String slug, String label, String detail)
      throws SQLException, Refused {
    assertWritable();
    try {
      accounts.votes.propose(slug, label, detail, gymActor(), actorName());
    } catch (io.hearth.vote.Votes.Refused refused) {
      throw new Refused(refused.getMessage());
    }
    return getVote(slug);
  }

  public Map<String, Object> castBallot(String slug, String option, String ballot, String because)
      throws SQLException, Refused {
    assertWritable();
    try {
      accounts.votes.cast(slug, option, io.hearth.vote.Votes.Ballot.of(ballot), because,
          gymActor(), actorName());
    } catch (io.hearth.vote.Votes.Refused refused) {
      throw new Refused(refused.getMessage());
    }
    return getVote(slug);
  }

  public Map<String, Object> narrowVote(String slug, int keep) throws SQLException, Refused {
    assertWritable();
    try {
      accounts.votes.narrow(slug, keep, gymActor(), actorName());
    } catch (io.hearth.vote.Votes.Refused refused) {
      throw new Refused(refused.getMessage());
    }
    return getVote(slug);
  }

  public Map<String, Object> decideVote(String slug, String option) throws SQLException, Refused {
    assertWritable();
    try {
      accounts.votes.decide(slug, option, gymActor(), actorName());
    } catch (io.hearth.vote.Votes.Refused refused) {
      throw new Refused(refused.getMessage());
    }
    return getVote(slug);
  }

  /**
   * When the people in this vote are free.
   *
   * <b>The kind rides on every answer.</b> An agent handed a rough weekly shape and left to assume
   * it is a calendar will confidently propose a night somebody has had booked for a month. So each
   * person comes back with what kind of answer it is and one sentence about what to do with it.
   *
   * <b>No addresses.</b> A display name and what they said about their week; an agent coordinating
   * a board game night has no business collecting the other players' email addresses.
   */
  public List<Map<String, Object>> whenPeopleAreFree() throws SQLException, Refused {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (io.hearth.auth.UserRecord person : accounts.users.recent(200)) {
      if (!person.isApproved()) {
        continue;
      }
      io.hearth.vote.Availability.Record found = accounts.availability.of(person.id());
      if (found.kind() == io.hearth.vote.Availability.Kind.unknown) {
        // somebody who has said nothing is absent rather than listed as unknown: a row saying
        // "we do not know" invites an agent to fill the gap with a guess
        continue;
      }
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("who", accounts.people.profileOf(person.id()).nameOr("member " + person.id()));
      row.put("kind", found.kind().name());
      row.put("what_to_do_with_it", found.advice());
      if (found.hasWeekly()) {
        row.put("weekly", JSON_NODES.convertValue(found.weeklyJson(), Map.class));
      }
      if (!found.notes().isBlank()) {
        row.put("notes", found.notes());
      }
      if (found.hasCalendar()) {
        row.put("ics_url", found.icsUrl());
      }
      rows.add(row);
    }
    return rows;
  }

  // ---- the gym ---------------------------------------------------------------------------------

  /**
   * Hevy, as the person who connected this agent.
   *
   * <b>The key belongs to the actor and there is no argument for whose.</b> Every method here uses
   * {@code actorId} and nothing else, so there is no phrasing of any request that reads somebody
   * else's workouts. That is the same rule the rest of this surface follows and it matters more
   * here: this is a credential for a service outside this server, and a "user" parameter would be
   * one prompt away from spending it on the wrong person.
   *
   * <b>Read only is honoured.</b> Creating an exercise or a routine writes to Hevy, not to this
   * server, and a connection marked read-only must not do it -- "read only" that lets an agent
   * change something somewhere else is a promise with a hole in it.
   *
   * <b>Hevy's answers are passed through as JSON.</b> Their API is explicitly unstable by their own
   * description, so mapping it onto records here would be a second thing to fix every time they
   * move. A model reads JSON perfectly well.
   */
  private long gymActor() throws Refused {
    if (actorId == null) {
      throw new Refused("this connection is not acting for anybody");
    }
    return actorId;
  }

  public Map<String, Object> hevyWorkouts(int page, int pageSize)
      throws SQLException, Refused {
    return call(() -> accounts.hevy.workouts(gymActor(), page, pageSize));
  }

  /**
   * One Hevy call, with their refusals turned into ours.
   *
   * A {@code Hevy.Refused} already says the useful thing -- no key, key rejected, host unreachable
   * -- and rethrowing it as the surface's own refusal is what puts it in front of the model as a
   * tool error rather than as a stack trace. Nothing is added: the message was written to be read
   * by whoever has to act on it.
   */
  private Map<String, Object> call(HevyCall body) throws SQLException, Refused {
    try {
      return wrap(body.run());
    } catch (io.hearth.hevy.Hevy.Refused refused) {
      throw new Refused(refused.getMessage());
    }
  }

  private interface HevyCall {
    com.fasterxml.jackson.databind.JsonNode run()
        throws io.hearth.hevy.Hevy.Refused, SQLException, Refused;
  }

  public Map<String, Object> hevyWorkout(String workoutId) throws SQLException, Refused {
    return call(() -> accounts.hevy.workout(gymActor(), workoutId));
  }

  public Map<String, Object> hevyRoutines(int page, int pageSize) throws SQLException, Refused {
    return call(() -> accounts.hevy.routines(gymActor(), page, pageSize));
  }

  public Map<String, Object> hevyExercises(int page, int pageSize) throws SQLException, Refused {
    return call(() -> accounts.hevy.exerciseTemplates(gymActor(), page, pageSize));
  }

  public Map<String, Object> hevyExerciseHistory(String templateId)
      throws SQLException, Refused {
    return call(() -> accounts.hevy.exerciseHistory(gymActor(), templateId));
  }

  public Map<String, Object> hevyFolders(int page, int pageSize) throws SQLException, Refused {
    return call(() -> accounts.hevy.routineFolders(gymActor(), page, pageSize));
  }

  /**
   * Invent an exercise.
   *
   * <b>This is the point of the whole gym surface.</b> Hevy's built-in list is fine for barbells
   * and useless for the mobility work that actually needs doing -- a 90/90 hip switch, a
   * loaded-carry variation nobody has named. A routine you cannot express is a routine you do not
   * do, so the ability to create the exercise has to be as reachable as the ability to use one.
   *
   * Every enum is checked here rather than sent onward and refused by Hevy, because a refusal that
   * names the field and lists what is allowed costs a model one turn and a 400 costs it several.
   */
  public Map<String, Object> hevyCreateExercise(Map<String, Object> changes)
      throws SQLException, Refused {
    assertWritable();
    long actor = gymActor();
    String title = str(changes, "title");
    if (title == null || title.isBlank()) {
      throw new Refused("an exercise needs a title");
    }
    String type = lower(changes, "exercise_type");
    String equipment = lower(changes, "equipment_category");
    String muscle = lower(changes, "muscle_group");
    checkOneOf("exercise_type", type, io.hearth.hevy.Hevy.EXERCISE_TYPES);
    checkOneOf("equipment_category", equipment, io.hearth.hevy.Hevy.EQUIPMENT);
    checkOneOf("muscle_group", muscle, io.hearth.hevy.Hevy.MUSCLE_GROUPS);
    ArrayList<String> others = new ArrayList<>();
    Object raw = changes.get("other_muscles");
    if (raw instanceof java.util.List<?> list) {
      for (Object one : list) {
        String value = String.valueOf(one).trim().toLowerCase(java.util.Locale.ROOT);
        checkOneOf("other_muscles", value, io.hearth.hevy.Hevy.MUSCLE_GROUPS);
        others.add(value);
      }
    }
    LinkedHashMap<String, Object> exercise = new LinkedHashMap<>();
    exercise.put("title", title.trim());
    exercise.put("exercise_type", type);
    exercise.put("equipment_category", equipment);
    exercise.put("muscle_group", muscle);
    exercise.put("other_muscles", others);
    return call(() -> accounts.hevy.createExercise(actor, Map.of("exercise", exercise)));
  }

  /**
   * Build a routine.
   *
   * The body is handed on close to Hevy's own shape rather than remodelled, because their schema is
   * the thing a model has been told about by `gym_spec` and translating between two vocabularies is
   * a place for the two to disagree. What is checked is what a model gets wrong: an exercise with
   * no template id, and a set type that is not one of the four.
   */
  public Map<String, Object> hevyCreateRoutine(Map<String, Object> changes)
      throws SQLException, Refused {
    assertWritable();
    long actor = gymActor();
    return call(() -> accounts.hevy.createRoutine(actor, Map.of("routine", routineBody(changes))));
  }

  public Map<String, Object> hevyUpdateRoutine(String routineId, Map<String, Object> changes)
      throws SQLException, Refused {
    assertWritable();
    long actor = gymActor();
    return call(() -> accounts.hevy.updateRoutine(actor, routineId,
        Map.of("routine", routineBody(changes))));
  }

  public Map<String, Object> hevyCreateFolder(String title) throws SQLException, Refused {
    assertWritable();
    long actor = gymActor();
    if (title == null || title.isBlank()) {
      throw new Refused("a folder needs a title");
    }
    return call(() -> accounts.hevy.createRoutineFolder(actor,
        Map.of("routine_folder", Map.of("title", title.trim()))));
  }

  private Map<String, Object> routineBody(Map<String, Object> changes) throws Refused {
    String title = str(changes, "title");
    if (title == null || title.isBlank()) {
      throw new Refused("a routine needs a title");
    }
    LinkedHashMap<String, Object> routine = new LinkedHashMap<>();
    routine.put("title", title.trim());
    if (changes.get("notes") != null) {
      routine.put("notes", String.valueOf(changes.get("notes")));
    }
    if (changes.get("folder_id") != null) {
      routine.put("folder_id", changes.get("folder_id"));
    }
    ArrayList<Object> exercises = new ArrayList<>();
    Object raw = changes.get("exercises");
    if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) {
      throw new Refused("a routine needs at least one exercise, each with an"
          + " exercise_template_id from gym_exercises");
    }
    for (Object one : list) {
      if (!(one instanceof Map<?, ?> map)) {
        throw new Refused("each exercise has to be an object");
      }
      Object templateId = map.get("exercise_template_id");
      if (templateId == null || String.valueOf(templateId).isBlank()) {
        throw new Refused("every exercise needs an exercise_template_id -- find one with"
            + " gym_exercises, or make one with gym_exercise_create");
      }
      LinkedHashMap<String, Object> exercise = new LinkedHashMap<>();
      exercise.put("exercise_template_id", String.valueOf(templateId));
      exercise.put("superset_id", map.get("superset_id"));
      exercise.put("rest_seconds", map.get("rest_seconds"));
      exercise.put("notes", map.get("notes"));
      ArrayList<Object> sets = new ArrayList<>();
      Object rawSets = map.get("sets");
      if (rawSets instanceof java.util.List<?> setList) {
        for (Object each : setList) {
          if (!(each instanceof Map<?, ?> set)) {
            throw new Refused("each set has to be an object");
          }
          String type = set.get("type") == null ? "normal"
              : String.valueOf(set.get("type")).trim().toLowerCase(java.util.Locale.ROOT);
          checkOneOf("set type", type, io.hearth.hevy.Hevy.SET_TYPES);
          LinkedHashMap<String, Object> clean = new LinkedHashMap<>();
          clean.put("type", type);
          for (String field : new String[]{"weight_kg", "reps", "distance_meters",
              "duration_seconds", "custom_metric", "rep_range"}) {
            if (set.get(field) != null) {
              clean.put(field, set.get(field));
            }
          }
          sets.add(clean);
        }
      }
      if (sets.isEmpty()) {
        throw new Refused("'" + templateId + "' has no sets; a routine with an exercise and no"
            + " sets is one Hevy will accept and nobody can perform");
      }
      exercise.put("sets", sets);
      exercises.add(exercise);
    }
    routine.put("exercises", exercises);
    return routine;
  }

  private static void checkOneOf(String field, String value, java.util.List<String> allowed)
      throws Refused {
    if (!io.hearth.hevy.Hevy.isOneOf(allowed, value)) {
      throw new Refused("'" + field + "' has to be one of: " + String.join(", ", allowed)
          + " (got '" + value + "')");
    }
  }

  private static String lower(Map<String, Object> changes, String key) {
    String value = str(changes, key);
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** Hevy's JSON, handed on as-is; see the class note about their API being explicitly unstable */
  private static Map<String, Object> wrap(com.fasterxml.jackson.databind.JsonNode node)
      throws Refused {
    try {
      return JSON_NODES.convertValue(node, LinkedHashMap.class);
    } catch (Exception ex) {
      throw new Refused("Hevy's answer could not be read back");
    }
  }

  // ---- content ---------------------------------------------------------------------------------

  /**
   * Every page an agent may know about.
   *
   * The filter is applied here rather than in SQL because "invisible to AI" is a property of this
   * surface, not of the content table -- the admin listing right next door has to show these pages,
   * and a WHERE clause in the store would eventually be copied into a query that should not have it.
   */
  public List<ContentRecord> visibleContent() throws SQLException {
    // a draft is a page somebody has not published; an agent whose person could not open it in a
    // browser has no business reading it through a tool
    boolean drafts = mayI(io.hearth.auth.Permission.content_read);
    ArrayList<ContentRecord> visible = new ArrayList<>();
    for (ContentRecord page : accounts.site.store().allContent(MAX_ROWS)) {
      if (!page.humanOnly() && (drafts || page.published())) {
        visible.add(page);
      }
    }
    return visible;
  }

  public List<Map<String, Object>> listContent(String folder, Boolean published) throws SQLException {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ContentRecord page : visibleContent()) {
      if (folder != null && !folder.isBlank() && !folder.equalsIgnoreCase(page.navFolder())) {
        continue;
      }
      if (published != null && published != page.published()) {
        continue;
      }
      rows.add(summarize(page));
    }
    return rows;
  }

  /** contains-of across uri, title and body; the same search a person gets in the admin */
  public List<Map<String, Object>> searchContent(String query) throws SQLException {
    String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (ContentRecord page : visibleContent()) {
      if (needle.isEmpty() || contains(needle, page.uri(), page.title(), page.body())) {
        Map<String, Object> row = summarize(page);
        row.put("excerpt", excerpt(page.body(), needle));
        rows.add(row);
      }
    }
    return rows;
  }

  /** one page in full, or null when there is no such page an agent may see */
  public Map<String, Object> getContent(String uri) throws SQLException {
    ContentRecord page = accounts.site.store().byUri(uri);
    if (page == null || page.humanOnly()) {
      return null;
    }
    // The listing was narrowed and this was not, which is the oldest shape of this bug: a filter on
    // the index and none on the direct fetch, so anything unpublished was one exact uri away. A
    // draft is a page somebody has not published, and an agent whose person cannot open it in a
    // browser must not read it here either.
    if (!page.published() && !mayI(io.hearth.auth.Permission.content_read)) {
      return null;
    }
    Map<String, Object> full = summarize(page);
    full.put("body", page.body());
    // parsed rather than the raw blob: this is the read that sits under content_meta, and handing
    // back a JSON string the model has to parse and re-serialise is how a value gets mangled on
    // the way through something that was only asked to change the title
    full.put("fields", valuesOf(page));
    return full;
  }

  /** a page's stored field values, as values rather than as the blob they live in */
  private static Map<String, String> valuesOf(ContentRecord page) {
    TreeMap<String, String> values = new TreeMap<>();
    try {
      com.fasterxml.jackson.databind.JsonNode node =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(page.fields() == null || page.fields().isBlank() ? "{}" : page.fields());
      node.fieldNames().forEachRemaining(name -> values.put(name, node.get(name).asText("")));
    } catch (Exception ex) {
      // an unreadable blob reads as empty rather than failing a read somebody asked for
      values.clear();
    }
    return values;
  }

  /**
   * Create or update a page.
   *
   * Update semantics are a merge against what is there: a field the caller did not mention keeps
   * its value. A model asked to "fix the typo in the third paragraph" should not have to resend the
   * title, the template and the folder to avoid clearing them, and one that forgets should not
   * silently wipe them.
   */
  public Map<String, Object> saveContent(String uri, Map<String, Object> changes)
      throws SQLException, Refused {
    return writePage(uri, changes, true);
  }

  /**
   * Change what a page <em>is</em> without touching what it <em>says</em>.
   *
   * The title, the folder, whether it is published, which template wraps it, and the values of the
   * fields that template declares. Not the body -- and the guarantee is structural rather than a
   * promise in a description: this method does not read one, so no phrasing of the arguments can
   * make it write one.
   *
   * That distinction is worth a second tool rather than a flag on the first. A body is the thing a
   * person wrote and the expensive thing to lose; a model asked to file forty pages into folders,
   * or to fill in a subtitle a new template started asking for, should not be holding forty bodies
   * it has to hand back unchanged. Every one of those is a chance to hand back something subtly
   * different -- a re-wrapped line, a normalised quote -- and the page's history would record it as
   * an edit somebody made. The narrow tool cannot make that mistake, and the log afterwards says
   * which of the two things happened.
   *
   * It refuses to create: a page that does not exist has no body to preserve, so "meta only" is not
   * a coherent thing to ask for, and the model wanted content_save.
   */
  public Map<String, Object> saveContentMeta(String uri, Map<String, Object> changes)
      throws SQLException, Refused {
    if (changes.containsKey("body")) {
      throw new Refused("content_meta does not write a body -- it is the tool for changing"
          + " everything else about a page while leaving what it says exactly as it is."
          + " Use content_save to write a body.");
    }
    if (uri != null && accounts.site.store().byUri(uri) == null) {
      throw new Refused("there is no page at '" + uri + "'. content_meta changes a page that"
          + " exists; content_save is what creates one.");
    }
    return writePage(uri, changes, false);
  }

  private Map<String, Object> writePage(String uri, Map<String, Object> changes, boolean allowBody)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.content_write);
    if (uri == null || !uri.startsWith("/") || uri.length() > 512) {
      throw new Refused("uri must be an absolute path like /about");
    }
    ContentRecord existing = accounts.site.store().byUri(uri);
    if (existing != null && existing.humanOnly()) {
      throw new Refused("'" + uri + "' is marked human only. AI cannot read or change it,"
          + " and an admin has to unlock it first.");
    }
    ContentRecord.Kind kind = changes.containsKey("kind")
        ? ContentRecord.Kind.of(str(changes, "kind"))
        : (existing == null ? ContentRecord.Kind.markdown : existing.kind());
    // An agent may write a program, and this is where that was decided.
    //
    // It was refused when the kind landed, on the grounds that "write me an about page" should not
    // be a way to get code executed. That reasoning was about the *blast radius*, and the radius is
    // what has since been drawn: a program gets `render`, `meta`, `query` and whatever table
    // functions this community declared. No network, no filesystem, no way back into this server,
    // no writes to any table, a fresh isolate, and a second to finish in. There is nothing in that
    // list an agent can reach that a page it wrote in HTML could not.
    //
    // What still holds is everything that was already true of any write: it acts as the person who
    // connected it, it cannot touch a human-only page, and every call is in the AI log under two
    // names. A program is reviewable in a way a request is not -- the source sits in the content
    // table, versioned like every other page, and `content_get` reads it back.
    //
    // The one thing an agent must not be able to do is widen its own reach, which is why there is
    // no tool for making a table -- only for writing a page that reads the ones a person declared.
    // Same shape as invariant 105: the safety is that the tool does not exist.
    String template = changes.containsKey("template")
        ? str(changes, "template")
        : (existing == null ? null : existing.templateName());
    if (template != null && !template.isBlank() && accounts.site.store().templateByName(template) == null) {
      throw new Refused("there is no template called '" + template + "'");
    }
    String fields = mergedValues(existing, kind,
        kind.wantsTemplate() ? template : null, changes);
    ContentRecord page = new ContentRecord(
        existing == null ? 0 : existing.id(),
        uri,
        changes.containsKey("title") ? str(changes, "title") : (existing == null ? "" : existing.title()),
        kind,
        kind.wantsTemplate() ? template : null,
        changes.containsKey("folder") ? str(changes, "folder") : (existing == null ? "" : existing.navFolder()),
        fields,
        allowBody && changes.containsKey("body")
            ? str(changes, "body") : (existing == null ? "" : existing.body()),
        changes.containsKey("published") ? bool(changes, "published") : (existing == null || existing.published()),
        // an agent can never set this bit; only a person at the admin screen can
        existing != null && existing.humanOnly(),
        null, null, actor());
    ContentRecord saved = accounts.site.store().save(page, actor(), actorEmail);
    Map<String, Object> result = summarize(saved);
    result.put("created", existing == null);
    // what the values actually are afterwards, rather than what was sent: the merge means those
    // are different answers, and a model that set one of four fields should be able to see the
    // other three survived without fetching the page back
    result.put("fields", valuesOf(saved));
    return result;
  }

  /**
   * A page's field values after a write, which is a merge and never a replacement.
   *
   * Three things live in one blob and only one of them is usually being changed: the values of the
   * fields the template declares, and -- on a listing page -- the knobs that decide how many rows
   * it shows and in what order. A model setting a subtitle is not saying anything about page_size,
   * so replacing the blob with what it sent would silently reset a listing somebody tuned. This is
   * invariant 30's rule arriving in a second place: a submission mentions a handful of the keys
   * that exist, and treating that as the new state of the record erases the rest while looking
   * like it worked.
   *
   * So: start from what is stored, apply what was sent, and leave everything else alone. A key
   * mapped to null is an erasure, because somebody can legitimately want one.
   *
   * A name the template never declared is <b>refused rather than dropped</b>, the same asymmetry
   * places uses. A form posting an unknown key is noise from a screen that has moved on; a model
   * passing one has misunderstood something, and dropping it quietly would report success for a
   * write that did not happen and teach it the field exists.
   */
  private String mergedValues(ContentRecord existing, ContentRecord.Kind kind,
                              String templateName, Map<String, Object> changes)
      throws SQLException, Refused {
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    if (existing != null) {
      values.putAll(valuesOf(existing));
    }
    List<TemplateField> declared = List.of();
    if (templateName != null && !templateName.isBlank()) {
      TemplateRecord template = accounts.site.store().templateByName(templateName);
      if (template != null) {
        declared = template.fields();
      }
    }
    if (changes.containsKey("fields")) {
      Object raw = changes.get("fields");
      if (!(raw instanceof Map<?, ?> given)) {
        throw new Refused("fields is an object of value by field name, like"
            + " {\"subtitle\":\"A quiet week\"}");
      }
      for (Map.Entry<?, ?> entry : given.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (!declares(declared, key)) {
          throw new Refused(templateName == null || templateName.isBlank()
              ? "this page has no template, so it has no fields to set"
              : "the " + templateName + " template does not declare '" + key + "'. It declares "
                  + (declared.isEmpty() ? "no fields at all" : String.join(", ", names(declared)))
                  + " -- add it with template_save if the template should be asking for it.");
        }
        values.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
      }
    }
    // a field the template asks for and nobody has filled in exists as an empty string, which is
    // what the page editor stores too -- the required check below is what makes that visible
    for (TemplateField field : declared) {
      values.putIfAbsent(field.name(), "");
    }
    for (TemplateField field : declared) {
      if (field.required() && values.getOrDefault(field.name(), "").isBlank()) {
        throw new Refused("'" + field.labelOr() + "' is required by the " + templateName
            + " template and is empty. Pass it in fields, like {\"" + field.name()
            + "\":\"...\"}.");
      }
    }
    com.fasterxml.jackson.databind.node.ObjectNode node =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    values.forEach(node::put);
    return node.toString();
  }

  private static boolean declares(List<TemplateField> declared, String name) {
    for (TemplateField field : declared) {
      if (field.name().equals(name)) {
        return true;
      }
    }
    return false;
  }


  private static List<String> names(List<TemplateField> declared) {
    ArrayList<String> names = new ArrayList<>();
    for (TemplateField field : declared) {
      names.add(field.name());
    }
    return names;
  }

  public Map<String, Object> deleteContent(String uri) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.content_write);
    ContentRecord page = accounts.site.store().byUri(uri);
    if (page == null) {
      throw new Refused("there is no page at '" + uri + "'");
    }
    if (page.humanOnly()) {
      throw new Refused("'" + uri + "' is marked human only. AI cannot read or change it.");
    }
    accounts.site.store().deleteContent(page.id(), actor());
    return Map.of("uri", uri, "deleted", true);
  }

  private static Map<String, Object> summarize(ContentRecord page) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("uri", page.uri());
    row.put("title", page.title());
    row.put("kind", page.kind().name());
    row.put("template", page.templateName() == null ? "" : page.templateName());
    row.put("folder", page.navFolder() == null ? "" : page.navFolder());
    row.put("published", page.published());
    row.put("updated", page.updatedAt() == null ? "" : page.updatedAt().toString());
    row.put("length", page.body() == null ? 0 : page.body().length());
    return row;
  }

  // ---- templates -------------------------------------------------------------------------------

  public List<Map<String, Object>> listTemplates() throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (TemplateRecord template : accounts.site.store().allTemplates(MAX_ROWS)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", template.name());
      row.put("fields", fieldNames(template));
      row.put("used_by", accounts.site.store().urisUsingTemplate(template.name()).size());
      row.put("length", template.body() == null ? 0 : template.body().length());
      rows.add(row);
    }
    return rows;
  }

  public Map<String, Object> getTemplate(String name) throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    TemplateRecord template = accounts.site.store().templateByName(name);
    if (template == null) {
      return null;
    }
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("name", template.name());
    row.put("body", template.body());
    row.put("fields", fieldDeclarations(template));
    row.put("used_by", accounts.site.store().urisUsingTemplate(template.name()));
    return row;
  }

  /**
   * Write a template.
   *
   * Saving one re-renders every page that uses it, so the result says how many -- a model that just
   * changed forty pages should be told it changed forty pages, and so should whoever reads the log
   * afterwards.
   */
  public Map<String, Object> saveTemplate(String name, String body) throws SQLException, Refused {
    return saveTemplate(name, body, Map.of());
  }

  /**
   * @param index the directory half, when a model is writing one: `directory`, `directory_path`,
   *     `directory_pattern`, `directory_body`, `directory_page_size`, `directory_order`. Absent
   *     keys keep whatever the template already says, so writing a body does not switch somebody's
   *     blog off.
   */
  public Map<String, Object> saveTemplate(String name, String body, Map<String, Object> index)
      throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.templates_write);
    if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
      throw new Refused("a template name is letters, digits, underscore or hyphen");
    }
    if (body == null) {
      throw new Refused("body is required");
    }
    TemplateRecord existing = accounts.site.store().templateByName(name);
    // Absent keeps what is declared, present replaces it wholesale. Absent has to keep, because a
    // model fixing a typo in a body must not silently strip every box off the page editor -- the
    // same reason the index half below keeps. Present has to replace rather than merge, because
    // that is what the admin screen does with the same declarations, and a declaration list whose
    // meaning depended on which of two screens wrote it is the "two ways to describe a form"
    // problem this project has already paid for once.
    String parameters = index.containsKey("fields")
        ? TemplateField.toBlob(declarationsFrom(index.get("fields")))
        : (existing == null ? "[]" : existing.parameters());
    boolean directory = index.containsKey("directory") ? bool(index, "directory")
        : existing != null && existing.directory();
    String path = index.containsKey("directory_path") ? str(index, "directory_path")
        : (existing == null ? "" : existing.directoryPath());
    if (directory && (path == null || !path.startsWith("/"))) {
      throw new Refused("a directory index needs directory_path, starting with '/'");
    }
    String pattern = index.containsKey("directory_pattern") ? str(index, "directory_pattern")
        : (existing == null ? "" : existing.directoryPattern());
    if (directory && pattern != null && !pattern.isBlank()
        && !pattern.contains(TemplateRecord.PAGE_TOKEN)) {
      throw new Refused("directory_pattern needs " + TemplateRecord.PAGE_TOKEN
          + " in it, which is where the page number goes");
    }
    String indexBody = index.containsKey("directory_body") ? str(index, "directory_body")
        : (existing == null ? "" : existing.directoryBody());
    accounts.site.store().saveTemplate(name, body, parameters, directory,
        path == null ? "" : path, pattern == null ? "" : pattern,
        indexBody == null ? "" : indexBody,
        intOr(index, "directory_page_size", existing == null ? 10 : existing.pageSize()),
        "oldest".equals(index.get("directory_order")) ? "oldest" : "newest", actor());
    List<String> affected = accounts.site.store().urisUsingTemplate(name);
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("created", existing == null);
    result.put("re_rendered", affected.size());
    result.put("pages", affected);
    TemplateRecord saved = accounts.site.store().templateByName(name);
    if (saved != null && saved.publishesDirectory()) {
      result.put("index_at", saved.directoryPath());
      result.put("index_is_its_own_template", saved.hasOwnIndex());
    }
    return result;
  }

  /**
   * How to build a site here, in one answer.
   *
   * <b>A tool description is a prompt, and this is the longest one.</b> A model asked to build a
   * community's website has no screen to read: it cannot see that picking "HTML Member Listing"
   * makes the uri field mean something different, or that a place listing can be narrowed to one
   * kind. Every one of those rules lives in exactly one place in the code, and this hands that
   * place to whoever is writing the pages -- so an agent's first attempt is a working site rather
   * than six refusals it has to learn from.
   */
  /**
   * Everything a model needs to write a dynamic page, generated rather than written down.
   *
   * <b>The table functions come from the catalogue, so this cannot go stale.</b> That is the point
   * of putting it here instead of in a paragraph somebody maintains: a table created five minutes
   * ago appears in the next call, and one that was dropped stops appearing. A model told about a
   * function that no longer exists writes a page that throws on its first line.
   *
   * <b>It says what is absent as loudly as what is present.</b> A model asked for a page that
   * "saves the form" will otherwise spend its turns looking for the write API, and the honest
   * answer -- there isn't one, and here is why -- is worth more than the silence.
   */
  private Map<String, Object> javascriptGuide() {
    LinkedHashMap<String, Object> guide = new LinkedHashMap<>();
    guide.put("what_it_is", "a page whose kind is 'javascript' has a body that is RUN on every"
        + " request rather than rendered. What it renders is the page. Use it when the answer"
        + " depends on something -- a query parameter, a table -- and markdown when it does not.");
    guide.put("render", "render(text) appends to the document. Call it as often as you like; the"
        + " pieces are joined in order. It is the only way to produce output: the value of the last"
        + " expression is ignored.");
    guide.put("meta", "meta(key, value) sets the page title and any field the template declared."
        + " meta('title', 'Today') wins over the stored title. meta('body', ...) is ignored, so it"
        + " cannot throw away what render() built.");
    guide.put("query", "query(name) returns a query-string parameter already converted to the"
        + " strictest type it honestly is -- ?page=2 arrives as the NUMBER 2, ?on=true as a"
        + " boolean, anything else as a string. query(name, fallback) returns the fallback when the"
        + " parameter is absent or is not the same type as the fallback, so query('page', 0) is"
        + " always a number and arithmetic on it is safe.");
    ArrayList<Map<String, Object>> tables = new ArrayList<>();
    if (accounts.tables != null) {
      for (io.hearth.tables.UserTable table : accounts.tables.all()) {
        LinkedHashMap<String, Object> one = new LinkedHashMap<>();
        one.put("table", table.name());
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("id", "a number, always present, and what paging and get_id use");
        for (io.hearth.tables.UserField field : table.fields()) {
          fields.put(field.name(), field.type().inJavaScript);
        }
        one.put("hidden_rows", "every table also has a 'hidden' flag an admin can set. Rows with"
            + " it are absent from every function above and the flag is not in the rows you get,"
            + " so there is nothing to filter on and nothing to test. Do not write a page that"
            + " tries to show or count them.");
        one.put("row_shape", fields);
        one.put("functions", table.functions());
        one.put("indexed", table.indexes());
        tables.add(one);
      }
    }
    guide.put("tables", tables);
    guide.put("table_functions", "<table>_get_id(id) gives one row or null."
        + " <table>_list_<field>(value) gives every row whose indexed field equals value, and"
        + " exists only for fields somebody indexed. <table>_page(idAfter, count) gives the next"
        + " count rows after that id -- start at 0 and pass the last id you saw. <table>_all()"
        + " gives every row. Asking for a table that does not exist throws on that line.");
    guide.put("csrf", "csrf() returns the token a form must carry to reach a mutation. Put it in a"
        + " hidden input called 'csrf'. A form without it is refused.");
    guide.put("no_writes_from_a_page", "a page cannot write, and the merge function is not even"
        + " defined in one -- a dynamic page runs for every request including a crawler's, so a"
        + " page that could insert would fill its table with whatever fetched it. Writing happens"
        + " at a mutation.");
    ArrayList<Map<String, Object>> mutations = new ArrayList<>();
    try {
      for (io.hearth.content.Mutations.Record mutation : accounts.mutations.all()) {
        LinkedHashMap<String, Object> one = new LinkedHashMap<>();
        one.put("uri", mutation.uri());
        one.put("answering", mutation.enabled());
        mutations.add(one);
      }
    } catch (SQLException ex) {
      // a guide missing its mutations is worth more than no guide
    }
    guide.put("mutations", mutations);
    guide.put("what_a_mutation_is", "an address that answers POST and runs a program, declared by a"
        + " person at /admin/mutations. It is the only way anything writes. It gets everything a"
        + " page gets, plus form(name, fallback) for what was submitted and"
        + " <table>_merge_by_id(id, change) for each table. You cannot create one; ask an"
        + " administrator to.");
    guide.put("merging", "<table>_merge_by_id(id, {field: value}) changes only the keys named and"
        + " leaves the rest alone, which is what makes it safe from a form showing some of a row."
        + " It returns {success: true} or {success: false, reasons: [...]} -- every reason, and"
        + " nothing is written unless all of them pass. It cannot change 'hidden'.");
    guide.put("mutation_answers", "meta('redirect', '/thanks') answers 303 so a refresh cannot"
        + " repeat the write, which is what a form wants. Anything render()ed is the response"
        + " otherwise, and a mutation that does neither answers {\"success\": true}.");
    guide.put("no_reach", "there is no fetch, no require, no filesystem, no timers, and no way back"
        + " into this server. Do not write a page that tries; nothing was bound, so it is a"
        + " ReferenceError rather than a refusal.");
    guide.put("limits", "one second per request, then it is stopped and the page says so. Every"
        + " request gets a fresh interpreter, so nothing you set survives to the next one -- there"
        + " are no globals to cache anything in. A dynamic page is never cached.");
    guide.put("errors", "a thrown error becomes a visible notice on the page with your line number,"
        + " and nothing you rendered before it is kept. Read it back with content_get if a page you"
        + " wrote is not doing what you expected.");
    guide.put("example", "meta('title', 'Sign-ups');\n"
        + "var job = query('job', 'bread');\n"
        + "signups_list_job(job).forEach(function (r) {\n"
        + "  render('<li>' + r.who + '</li>');\n"
        + "});");
    return guide;
  }

  public Map<String, Object> siteSpec() throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
    ArrayList<Map<String, Object>> kinds = new ArrayList<>();
    for (io.hearth.content.ContentRecord.Kind kind
        : io.hearth.content.ContentRecord.Kind.values()) {
      LinkedHashMap<String, Object> one = new LinkedHashMap<>();
      one.put("kind", kind.name());
      one.put("label", kind.label);
      one.put("what_it_is", kind.describe);
      one.put("uri", kind.uriRule());
      one.put("wraps_in_a_template", kind.wantsTemplate());
      kinds.add(one);
    }
    spec.put("page_kinds", kinds);
    spec.put("published_date", "every page has one; it defaults to the first save and orders every"
        + " listing. Set it when moving old writing in, so 2011 says 2011.");
    spec.put("directory_index", "a template can publish an index of every page using it, at an"
        + " address of its own. It is a second template with its own body -- see template_save.");
    spec.put("template_fields", "a template can declare fields -- a subtitle, a hero line, a date"
        + " -- that every page using it is asked for. Declare them with template_save (fields) and"
        + " use one as {{field_name}} in the template body. A page fills them in with content_save"
        + " or content_meta (fields); template_get says what a template declares and which are"
        + " required. A required field left empty refuses the save rather than rendering a hole.");
    spec.put("changing_details", "content_meta changes a page's title, folder, template, published"
        + " state or field values and cannot write a body at all. Prefer it whenever the words are"
        + " not what is changing: there is no way for it to damage what somebody wrote, and the log"
        + " afterwards says which of the two kinds of edit this was.");
    spec.put("attachments", "uploaded files are served at /attachment/<id>.<ext> and can be"
        + " embedded in any page body.");
    spec.put("javascript", javascriptGuide());
    return spec;
  }

  public Map<String, Object> deleteTemplate(String name) throws SQLException, Refused {
    assertWritable();
    assertCan(io.hearth.auth.Permission.templates_write);
    TemplateRecord template = accounts.site.store().templateByName(name);
    if (template == null) {
      throw new Refused("there is no template called '" + name + "'");
    }
    List<String> affected = accounts.site.store().urisUsingTemplate(name);
    if (!affected.isEmpty()) {
      throw new Refused("'" + name + "' is still used by " + affected.size()
          + " page(s); move them off it first: " + String.join(", ", affected));
    }
    accounts.site.store().deleteTemplate(template.id(), actor());
    return Map.of("name", name, "deleted", true);
  }

  private static List<String> fieldNames(TemplateRecord template) {
    ArrayList<String> names = new ArrayList<>();
    for (TemplateField field : template.fields()) {
      names.add(field.name() + ":" + field.type().name() + (field.required() ? " (required)" : ""));
    }
    return names;
  }

  /**
   * The declarations in full, which is what makes editing them possible.
   *
   * The listing gets the one-line form because it is a listing. This is the read that sits under a
   * write: a model adding one field to a template has to resend the others, so anything this drops
   * -- the label, the help text -- is something the next save silently deletes. Invariant 248 is
   * usually about a filter missing from a by-id fetch; the same rule applies to a read that is
   * lossy where its write is total.
   */
  private static List<Map<String, Object>> fieldDeclarations(TemplateRecord template) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (TemplateField field : template.fields()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("name", field.name());
      row.put("type", field.type().name());
      row.put("label", field.label() == null ? "" : field.label());
      row.put("help", field.help() == null ? "" : field.help());
      row.put("required", field.required());
      rows.add(row);
    }
    return rows;
  }

  /**
   * Model-supplied field declarations, checked rather than coerced.
   *
   * {@link TemplateField#parse} is deliberately forgiving because it reads a blob this server
   * wrote and a half-readable declaration should not take a page down. This reads an argument, and
   * the opposite rule applies: an unusable name or an invented type is refused by name so the model
   * learns what it did, because {@code Type.of} would quietly turn "boolean" into a text box and
   * the author would find out from a form that renders wrong.
   */
  private static List<TemplateField> declarationsFrom(Object raw) throws Refused {
    ArrayList<TemplateField> fields = new ArrayList<>();
    if (raw == null) {
      return fields;
    }
    if (!(raw instanceof List<?> given)) {
      throw new Refused("fields is a list of declarations, each with a name and a type");
    }
    if (given.size() > TemplateField.MAX_FIELDS) {
      throw new Refused("a template declares at most " + TemplateField.MAX_FIELDS + " fields");
    }
    for (Object item : given) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new Refused("each field is an object like"
            + " {\"name\":\"subtitle\",\"type\":\"text\",\"label\":\"Subtitle\"}");
      }
      Object name = map.get("name");
      String fieldName = name == null ? null : String.valueOf(name).trim();
      if (!TemplateField.isValidName(fieldName)) {
        throw new Refused("'" + fieldName + "' is not a usable field name -- lowercase letters,"
            + " digits and underscore, starting with a letter, up to 32 characters");
      }
      for (TemplateField already : fields) {
        if (already.name().equals(fieldName)) {
          throw new Refused("'" + fieldName + "' is declared twice; each field is named once");
        }
      }
      Object type = map.get("type");
      TemplateField.Type kind = typeOf(type == null ? null : String.valueOf(type));
      Object required = map.get("required");
      fields.add(new TemplateField(fieldName, kind,
          map.get("label") == null ? "" : String.valueOf(map.get("label")),
          map.get("help") == null ? "" : String.valueOf(map.get("help")),
          Boolean.TRUE.equals(required) || "true".equals(String.valueOf(required))));
    }
    return fields;
  }

  private static TemplateField.Type typeOf(String raw) throws Refused {
    if (raw == null || raw.isBlank()) {
      return TemplateField.Type.text;
    }
    for (TemplateField.Type type : TemplateField.Type.values()) {
      if (type.name().equalsIgnoreCase(raw.trim())) {
        return type;
      }
    }
    ArrayList<String> known = new ArrayList<>();
    for (TemplateField.Type type : TemplateField.Type.values()) {
      known.add(type.name());
    }
    throw new Refused("'" + raw + "' is not a field type here; it is one of "
        + String.join(", ", known));
  }

  // ---- navigation ------------------------------------------------------------------------------

  public Map<String, Object> navigation() throws SQLException, Refused {
    // operator machinery: a member has no screen for any of this
    assertCan(io.hearth.auth.Permission.content_read);
    LinkedHashMap<String, Object> tree = new LinkedHashMap<>();
    ArrayList<String> loose = new ArrayList<>();
    for (ContentRecord page : visibleContent()) {
      if (page.isOutsideNavigation()) {
        loose.add(page.uri());
        continue;
      }
      @SuppressWarnings("unchecked")
      List<String> bucket = (List<String>) tree.computeIfAbsent(page.navFolder(), key -> new ArrayList<String>());
      bucket.add(page.uri());
    }
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("folders", tree);
    result.put("outside_navigation", loose);
    return result;
  }

  // ---- survey ----------------------------------------------------------------------------------












  // ---- plumbing --------------------------------------------------------------------------------

  private Long actor() {
    return actorId;
  }

  /** the community's clock, because "closes on Friday" is a fact about a place */
  private java.time.ZoneId zone = java.time.ZoneOffset.UTC;
  private io.hearth.vhost.DomainConfig domainConfig;

  /** the community this connection is for, which is what a page's clock and colours come from */
  public AiSurface inCommunity(io.hearth.vhost.DomainConfig config) {
    this.domainConfig = config;
    this.zone = config == null ? java.time.ZoneOffset.UTC : config.zone;
    return this;
  }

  private Long actorId;

  private String actorEmail;

  /** whose authority this surface acts under; set once when the request is authenticated */
  // ---- the address book -------------------------------------------------------------------------

  /**
   * The kinds of place this community keeps, and what each records.
   *
   * The field list is included because a model that cannot see what a "ranch" has cannot fill one
   * in -- and inventing keys that the type never declared produces a place whose extras are
   * silently dropped on save.
   */
  // ---- events -------------------------------------------------------------------------------






  // ---- the board ------------------------------------------------------------------------------





  // ---- polls -----------------------------------------------------------------------------------










  // ---- tasks, routines and what was recorded ---------------------------------------------------

















  private static final com.fasterxml.jackson.databind.ObjectMapper JSON_NODES =
      new com.fasterxml.jackson.databind.ObjectMapper();




  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String text(Map<String, Object> changes, String key, String fallback) {
    Object value = changes.get(key);
    return value == null ? fallback : String.valueOf(value);
  }









  private static String str(Map<String, Object> changes, String key, String fallback) {
    Object value = changes.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  private static boolean bool(Map<String, Object> changes, String key, boolean fallback) {
    Object value = changes.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }

  public AiSurface actingAs(long userId) {
    this.actorId = userId;
    return this;
  }

  /** and under whose name, so the version history says who an agent was acting as */
  public AiSurface actingAs(long userId, String email) {
    this.actorId = userId;
    this.actorEmail = email;
    return this;
  }

  private static boolean contains(String needle, String... haystacks) {
    for (String haystack : haystacks) {
      if (haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static String excerpt(String body, String needle) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    int at = needle.isEmpty() ? 0 : body.toLowerCase(Locale.ROOT).indexOf(needle);
    if (at < 0) {
      at = 0;
    }
    int from = Math.max(0, at - 80);
    int to = Math.min(body.length(), from + SNIPPET);
    return (from > 0 ? "…" : "") + body.substring(from, to) + (to < body.length() ? "…" : "");
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static boolean bool(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private static int intOr(Map<String, Object> map, String key, int fallback) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static List<String> strings(Map<String, Object> map, String key) {
    Object value = map.get(key);
    ArrayList<String> list = new ArrayList<>();
    if (value instanceof List<?> items) {
      for (Object item : items) {
        if (item != null && !String.valueOf(item).isBlank()) {
          list.add(String.valueOf(item).trim());
        }
      }
    } else if (value != null) {
      for (String line : String.valueOf(value).split("\\R")) {
        if (!line.isBlank()) {
          list.add(line.trim());
        }
      }
    }
    return list;
  }
}
