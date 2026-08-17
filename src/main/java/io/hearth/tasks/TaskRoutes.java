package io.hearth.tasks;

import io.hearth.auth.Accounts;
import io.hearth.auth.SessionRecord;
import io.hearth.auth.UserRecord;
import io.hearth.common.Verbose;
import io.hearth.template.Templates;
import io.hearth.vhost.DomainConfig;
import io.hearth.web.AccountRoutes;
import io.hearth.web.Chrome;
import io.hearth.web.Cookies;
import io.hearth.web.Forms;
import io.hearth.web.Landing;
import io.hearth.web.Navigation;
import io.hearth.web.Responses;
import io.hearth.web.WebHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects, what is on them, and the screen somebody actually uses at the gym.
 *
 * <pre>
 *   /tasks                    every project you can open
 *   /tasks/&lt;id&gt;               one project: what is due, or the board
 *   /tasks/&lt;id&gt;/task/&lt;id&gt;     one item: log a set, rate it, read the form notes
 *   /tasks/library            definitions -- yours, and the community's
 *   /tasks/library/&lt;id&gt;       one definition and what it has come to
 * </pre>
 *
 * <b>Written for a phone held in one hand at the end of a set.</b> That is not a slogan here, it is
 * the specification: the thing somebody does forty times in an hour is tick a set off, and it has to
 * be one tap on a big target with the previous set's numbers already in the boxes. Everything
 * else -- editing the routine, reading the form notes, looking at six months of history -- is
 * something done once, sitting down, and can cost a page load.
 *
 * <b>No JavaScript is required for any of it.</b> Each set is its own form that posts and comes
 * back, which is a page load per set and completely fine at this scale; the alternative is an app
 * that stops working in a basement gym with one bar of signal, which is precisely where it is used.
 *
 * <b>Whose is this</b> is asked once, at the top, and answered by ownership: a project with an
 * owner is that person's and nobody else opens it, a project with none is the community's and every
 * approved member does. There is no sharing in between, deliberately -- "my training log, but Sam
 * can see it" is a feature with a permissions screen behind it, and this does not have one yet.
 */
public class TaskRoutes {
  private static final Logger LOG = LoggerFactory.getLogger(TaskRoutes.class);

  private final Templates templates;
  private final Verbose verbose;

  public TaskRoutes(Templates templates, Verbose verbose) {
    this.templates = templates;
    this.verbose = verbose;
  }

  /**
   * Does this path belong here?
   *
   * A prefix rather than an exact match, because every page below the root is a real address --
   * one project, one item, one definition. The routing table holds exact paths, so a section with
   * sub-pages has to answer this for itself, exactly as the board does.
   */
  public static boolean owns(DomainConfig config, String path) {
    String root = config.urls.tasks;
    return path.equals(root) || path.startsWith(root + "/");
  }

  public void handle(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                     FullHttpRequest req, WebHandler.Recorder recorder) {
    try {
      SessionRecord session = AccountRoutes.currentSession(accounts, req);
      UserRecord me = session == null ? null : accounts.users.byId(session.userId());
      if (me == null) {
        recorder.status(303);
        Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
            new String[]{HttpHeaderNames.LOCATION.toString(),
                Landing.carry(config.urls.login, Landing.here(req))});
        return;
      }
      String path = Forms.path(req.uri());
      String root = config.urls.tasks;
      String rest = path.length() > root.length() ? path.substring(root.length() + 1) : "";

      if (HttpMethod.POST.equals(req.method())) {
        act(config, accounts, ctx, req, me, recorder);
        return;
      }
      if (rest.startsWith("library")) {
        String[] parts = rest.split("/");
        if (parts.length > 1) {
          definition(config, accounts, ctx, req, me, longOf(parts[1]), recorder);
        } else {
          library(config, accounts, ctx, req, me, recorder);
        }
        return;
      }
      if (rest.isEmpty()) {
        projects(config, accounts, ctx, req, me, recorder);
        return;
      }
      String[] parts = rest.split("/");
      long projectId = longOf(parts[0]);
      if (parts.length >= 3 && parts[1].equals("task")) {
        one(config, accounts, ctx, req, me, projectId, longOf(parts[2]), recorder);
        return;
      }
      project(config, accounts, ctx, req, me, projectId, recorder);
    } catch (SQLException ex) {
      LOG.error("tasks-failed", ex);
      recorder.status(500);
      Responses.send(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, null, Responses.EMPTY);
    }
  }

  /**
   * May this person open this project?
   *
   * The whole access model, in one method, asked before anything else on every path. A project with
   * an owner belongs to that person; one without belongs to the community. Administrators are
   * deliberately *not* an exception here -- see {@code AdminRoutes} for the screen where inspecting
   * somebody's log is a decision somebody takes on purpose rather than a side effect of being able
   * to reach this URL.
   */
  private static boolean mayOpen(Records.Project project, UserRecord me) {
    return project != null
        && (project.ownerId() == null || project.ownerId() == me.id());
  }

  // ---- pages -----------------------------------------------------------------------------------

  private void projects(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                        FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, "Projects", csrf);
    long now = System.currentTimeMillis();
    LocalDate today = LocalDate.now(config.zone);

    ArrayList<Map<String, Object>> mine = new ArrayList<>();
    ArrayList<Map<String, Object>> shared = new ArrayList<>();
    for (Records.Project project : accounts.tasks.projectsFor(me.id(), false)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", project.id());
      row.put("name", project.name());
      row.put("summary", project.summary());
      row.put("url", config.urls.tasks + "/" + project.id());
      row.put("word", project.many());
      int open = 0;
      int due = 0;
      for (Records.Task task : accounts.tasks.tasksIn(project.id())) {
        if (task.done() && project.shouldHide(task.doneAt(), now)) {
          continue;
        }
        if (!task.done()) {
          open++;
          if (task.dueOn() != null && !task.dueOn().isAfter(today)) {
            due++;
          }
        }
      }
      row.put("open", open);
      row.put("due", due);
      row.put("anyDue", due > 0);
      (project.isShared() ? shared : mine).add(row);
    }
    model.put("mine", mine);
    model.put("anyMine", !mine.isEmpty());
    model.put("shared", shared);
    model.put("anyShared", !shared.isEmpty());
    model.put("libraryUrl", config.urls.tasks + "/library");
    model.put("mayShare", accounts.access.can(me, io.hearth.auth.Permission.tasks_share));

    // what has been recorded lately, across everything, because a phone opening this page is
    // usually asking "did I already do that today"
    ArrayList<Map<String, Object>> lately = new ArrayList<>();
    for (Records.Entry entry : accounts.tasks.recentFor(me.id(), 8)) {
      Records.Def def = entry.defId() == null ? null : accounts.tasks.def(entry.defId());
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("what", def == null ? "something" : def.name());
      row.put("said", def == null ? "" : entry.describe(def.measure()));
      row.put("when", ago(now - entry.recordedAt().getTime()));
      lately.add(row);
    }
    model.put("lately", lately);
    model.put("anyLately", !lately.isEmpty());

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("tasks", model));
  }

  private void project(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, UserRecord me, long projectId,
                       WebHandler.Recorder recorder) throws SQLException {
    Records.Project project = accounts.tasks.project(projectId);
    if (!mayOpen(project, me)) {
      notHere(config, accounts, ctx, req, recorder);
      return;
    }
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, project.name(), csrf);
    long now = System.currentTimeMillis();
    LocalDate today = LocalDate.now(config.zone);

    model.put("id", project.id());
    model.put("name", project.name());
    model.put("summary", project.summary());
    model.put("word", project.one());
    model.put("words", project.many());
    model.put("isShared", project.isShared());
    model.put("board", project.isBoard());
    model.put("backUrl", config.urls.tasks);
    model.put("libraryUrl", config.urls.tasks + "/library");
    model.put("mayEdit", project.ownerId() != null
        || accounts.access.can(me, io.hearth.auth.Permission.tasks_share));

    List<Records.Task> tasks = accounts.tasks.tasksIn(projectId);
    int hidden = 0;
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    for (Records.Task task : tasks) {
      if (task.done() && project.shouldHide(task.doneAt(), now)) {
        hidden++;
        continue;
      }
      rows.add(taskRow(config, accounts, project, task, me, csrf, today));
    }
    model.put("hidden", hidden);
    model.put("anyHidden", hidden > 0);

    // Groups first: everything sharing a name comes out as one block, in the order it is done.
    // Ungrouped items are left where they were, so a routine with one superset in it does not
    // rearrange itself around that fact.
    model.put("groups", groupsOf(rows));

    if (project.isBoard()) {
      // a column per phase, which is what the project said its phases were
      ArrayList<Map<String, Object>> columns = new ArrayList<>();
      for (String phase : project.phases()) {
        ArrayList<Map<String, Object>> inPhase = new ArrayList<>();
        for (Map<String, Object> row : rows) {
          if (phase.equals(row.get("phase"))) {
            inPhase.add(row);
          }
        }
        LinkedHashMap<String, Object> column = new LinkedHashMap<>();
        column.put("name", phase);
        column.put("tasks", inPhase);
        column.put("count", inPhase.size());
        column.put("any", !inPhase.isEmpty());
        columns.add(column);
      }
      model.put("columns", columns);
      model.put("phases", project.phases());
    } else {
      ArrayList<Map<String, Object>> open = new ArrayList<>();
      ArrayList<Map<String, Object>> done = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        (Boolean.TRUE.equals(row.get("done")) ? done : open).add(row);
      }
      model.put("open", open);
      model.put("anyOpen", !open.isEmpty());
      model.put("done", done);
      model.put("anyDone", !done.isEmpty());
    }

    // the definitions this person could add, so adding one is a dropdown rather than typing a name
    ArrayList<Map<String, Object>> defs = new ArrayList<>();
    for (Records.Def def : accounts.tasks.defsFor(me.id(), false)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", def.id());
      row.put("name", def.name());
      row.put("measure", def.measure().label);
      defs.add(row);
    }
    model.put("defs", defs);
    model.put("anyDefs", !defs.isEmpty());

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("project", model));
  }

  /**
   * The grouped items, gathered; the rest left alone.
   *
   * Order within a group is the order they were added, which for a sequenced group *is* the
   * meaning and for a superset is the round. Ungrouped rows are not disturbed -- a routine with one
   * superset in it should not rearrange itself around that fact.
   */
  private static List<Map<String, Object>> groupsOf(List<Map<String, Object>> rows) {
    LinkedHashMap<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
    LinkedHashMap<String, String> modes = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Object name = row.get("group");
      if (name == null) {
        continue;
      }
      byName.computeIfAbsent(String.valueOf(name), key -> new ArrayList<>()).add(row);
      modes.putIfAbsent(String.valueOf(name), String.valueOf(row.get("groupHint")));
    }
    ArrayList<Map<String, Object>> groups = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : byName.entrySet()) {
      LinkedHashMap<String, Object> group = new LinkedHashMap<>();
      group.put("name", entry.getKey());
      group.put("hint", modes.get(entry.getKey()));
      group.put("label", entry.getValue().get(0).get("groupLabel"));
      group.put("sequenced", Boolean.TRUE.equals(entry.getValue().get(0).get("sequenced")));
      group.put("tasks", entry.getValue());
      group.put("count", entry.getValue().size());
      groups.add(group);
    }
    return groups;
  }

  private Map<String, Object> taskRow(DomainConfig config, Accounts accounts,
                                      Records.Project project, Records.Task task, UserRecord me,
                                      String csrf, LocalDate today) throws SQLException {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    Records.Def def = task.defId() == null ? null
        : accounts.tasks.resolved(accounts.tasks.def(task.defId()));
    row.put("id", task.id());
    row.put("title", task.title());
    row.put("notes", task.notes());
    row.put("phase", task.phase());
    if (task.grouped()) {
      row.put("group", task.groupName());
      row.put("groupLabel", task.grouping().label());
      row.put("groupHint", task.grouping().hint());
      row.put("sequenced", task.grouping() == Records.Grouping.sequenced);
    }
    row.put("done", task.done());
    row.put("repeats", task.repeats());
    row.put("repeatDays", task.repeatDays());
    row.put("due", task.dueOn() == null ? null : task.dueOn().toString());
    row.put("overdue", task.overdue(today));
    row.put("csrf", csrf);
    row.put("action", config.urls.tasks);
    row.put("url", config.urls.tasks + "/" + project.id() + "/task/" + task.id());
    row.put("projectId", project.id());
    if (def != null) {
      row.put("measure", def.measure().name());
      row.put("measureLabel", def.measure().label);
      row.put("hasSets", def.measure().hasSets());
      row.put("defId", def.id());
      // the last thing recorded, which on a phone is what somebody is trying to beat
      List<Records.Entry> last = accounts.tasks.entriesForTask(task.id(), me.id(), 1);
      if (!last.isEmpty()) {
        row.put("last", last.get(0).describe(def.measure()));
      }
    }
    // and the phases it could move to, for a board
    if (project.isBoard()) {
      ArrayList<Map<String, Object>> moves = new ArrayList<>();
      for (String phase : project.phases()) {
        if (!phase.equals(task.phase())) {
          moves.add(Map.of("name", phase));
        }
      }
      row.put("moves", moves);
    }
    return row;
  }

  /**
   * One item, and the screen a set is actually logged on.
   *
   * Everything about it is arranged around one gesture: the boxes come pre-filled from the last
   * set, and the button under them is the biggest thing on the page.
   */
  private void one(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, long projectId, long taskId,
                   WebHandler.Recorder recorder) throws SQLException {
    Records.Project project = accounts.tasks.project(projectId);
    Records.Task task = accounts.tasks.task(taskId);
    if (!mayOpen(project, me) || task == null || task.projectId() != projectId) {
      notHere(config, accounts, ctx, req, recorder);
      return;
    }
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, task.title(), csrf);
    Records.Def def = task.defId() == null ? null
        : accounts.tasks.resolved(accounts.tasks.def(task.defId()));
    Measure measure = def == null ? Measure.none : def.measure();

    model.put("id", task.id());
    model.put("projectId", projectId);
    model.put("title", task.title());
    model.put("notes", task.notes());
    model.put("done", task.done());
    model.put("repeats", task.repeats());
    model.put("due", task.dueOn() == null ? null : task.dueOn().toString());
    model.put("backUrl", config.urls.tasks + "/" + projectId);
    model.put("projectName", project.name());
    model.put("word", project.one());
    if (def != null) {
      model.put("defId", def.id());
      model.put("defName", def.name());
      model.put("summary", def.summary());
      model.put("instructions", io.hearth.content.Markdown.toSafeHtml(def.instructions()));
      model.put("anyInstructions", !def.instructions().isBlank());
      model.put("reference", def.referenceUrl().isBlank() ? null : def.referenceUrl());
      model.put("defUrl", config.urls.tasks + "/library/" + def.id());
    }
    model.put("measure", measure.name());
    model.put("measureLabel", measure.label);
    model.put("hasSets", measure.hasSets());
    model.put("boxes", measure.boxes());
    if (def != null && def.hasRest()) {
      model.put("restSeconds", def.restSeconds());
      model.put("restSaid", def.restSaid());
    }

    // The others in the group, so a superset is one screen away from the next movement rather than
    // three taps back through the project.
    if (task.grouped()) {
      model.put("groupName", task.groupName());
      model.put("groupLabel", task.grouping().label());
      model.put("groupHint", task.grouping().hint());
      // a superset's rest belongs after the round, so the timer is not offered between the parts
      model.put("restsHere", task.grouping() != Records.Grouping.related);
      ArrayList<Map<String, Object>> siblings = new ArrayList<>();
      List<Records.Task> whole = accounts.tasks.groupWith(task);
      for (int k = 0; k < whole.size(); k++) {
        Records.Task other = whole.get(k);
        if (other.id() == task.id()) {
          // and for a sequenced group, which one comes next
          if (task.grouping() == Records.Grouping.sequenced && k + 1 < whole.size()) {
            model.put("nextName", whole.get(k + 1).title());
            model.put("nextUrl",
                config.urls.tasks + "/" + projectId + "/task/" + whole.get(k + 1).id());
          }
          continue;
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("title", other.title());
        row.put("url", config.urls.tasks + "/" + projectId + "/task/" + other.id());
        row.put("done", other.done());
        siblings.add(row);
      }
      model.put("siblings", siblings);
      model.put("anySiblings", !siblings.isEmpty());
    } else {
      model.put("restsHere", true);
    }

    // today's sets, and what was done before -- the two things somebody is looking at between sets
    List<Records.Entry> entries = accounts.tasks.entriesForTask(taskId, me.id(), 200);
    String today = java.time.LocalDate.now(config.zone).toString();
    ArrayList<Map<String, Object>> todaySets = new ArrayList<>();
    ArrayList<Map<String, Object>> before = new ArrayList<>();
    for (Records.Entry entry : entries) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", entry.id());
      row.put("what", entry.describe(measure));
      row.put("when", stamp(entry.recordedAt(), config));
      row.put("note", entry.note().isBlank() ? null : entry.note());
      row.put("difficulty", entry.difficulty());
      row.put("impact", entry.impact());
      row.put("csrf", csrf);
      row.put("action", config.urls.tasks);
      boolean isToday = java.time.Instant.ofEpochMilli(entry.recordedAt().getTime())
          .atZone(config.zone).toLocalDate().toString().equals(today);
      (isToday ? todaySets : before).add(row);
    }
    java.util.Collections.reverse(todaySets);
    // how long since the last set, which is what a rest timer counts from. Rendered server-side so
    // the page is honest with no JavaScript at all; the shipped script only makes it tick.
    if (!entries.isEmpty()) {
      long since = (System.currentTimeMillis() - entries.get(0).recordedAt().getTime()) / 1000;
      model.put("sinceLast", Math.max(0, since));
      model.put("sinceLastSaid", Measure.duration.describe(null, null, (int) Math.max(0, since),
          null));
    }
    model.put("today", todaySets);
    model.put("anyToday", !todaySets.isEmpty());
    model.put("setNumber", todaySets.size() + 1);
    model.put("before", before.subList(0, Math.min(before.size(), 12)));
    model.put("anyBefore", !before.isEmpty());

    // the boxes start where the last set left off, because the common case is the same again
    if (!entries.isEmpty()) {
      Records.Entry last = entries.get(0);
      model.put("lastWeight", last.weight());
      model.put("lastReps", last.reps());
      model.put("lastSeconds", last.seconds());
      model.put("lastDistance", last.distance());
    } else if (def != null) {
      model.put("lastWeight", def.targetDouble("weight"));
      model.put("lastReps", def.targetInt("reps", 0) == 0 ? null : def.targetInt("reps", 0));
      model.put("lastSeconds",
          def.targetInt("seconds", 0) == 0 ? null : def.targetInt("seconds", 0));
    }
    model.put("action", config.urls.tasks);

    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("task", model));
  }

  private void library(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, "What things are", csrf);
    ArrayList<Map<String, Object>> mine = new ArrayList<>();
    ArrayList<Map<String, Object>> shared = new ArrayList<>();
    java.util.HashSet<Long> adopted = new java.util.HashSet<>();
    for (Records.Def def : accounts.tasks.defsFor(me.id(), false)) {
      if (def.parentId() != null) {
        adopted.add(def.parentId());
      }
    }
    for (Records.Def def : accounts.tasks.defsFor(me.id(), false)) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("id", def.id());
      row.put("name", def.name());
      row.put("summary", def.summary());
      row.put("measure", def.measure().label);
      row.put("tags", def.tagList());
      row.put("url", config.urls.tasks + "/library/" + def.id());
      row.put("derived", def.parentId() != null);
      row.put("csrf", csrf);
      row.put("action", config.urls.tasks);
      if (def.isCommunitys()) {
        row.put("taken", adopted.contains(def.id()));
        shared.add(row);
      } else {
        mine.add(row);
      }
    }
    model.put("mine", mine);
    model.put("anyMine", !mine.isEmpty());
    model.put("shared", shared);
    model.put("anyShared", !shared.isEmpty());
    model.put("measures", Measure.all());
    model.put("action", config.urls.tasks);
    model.put("backUrl", config.urls.tasks);
    model.put("mayShare", accounts.access.can(me, io.hearth.auth.Permission.tasks_share));
    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("library", model));
  }

  private void definition(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                          FullHttpRequest req, UserRecord me, long defId,
                          WebHandler.Recorder recorder) throws SQLException {
    Records.Def raw = accounts.tasks.def(defId);
    if (raw == null || (raw.ownerId() != null && raw.ownerId() != me.id())
        || (raw.ownerId() == null && !raw.shared())) {
      notHere(config, accounts, ctx, req, recorder);
      return;
    }
    Records.Def def = accounts.tasks.resolved(raw);
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, def.name(), csrf);
    model.put("id", def.id());
    model.put("name", def.name());
    model.put("summary", def.summary());
    model.put("instructions", io.hearth.content.Markdown.toSafeHtml(def.instructions()));
    model.put("anyInstructions", !def.instructions().isBlank());
    model.put("reference", def.referenceUrl().isBlank() ? null : def.referenceUrl());
    model.put("measure", def.measure().name());
    model.put("measureLabel", def.measure().label);
    model.put("effortLabel", def.measure().effortLabel());
    model.put("tags", def.tagList());
    model.put("mine", raw.ownerId() != null && raw.ownerId() == me.id());
    model.put("isShared", raw.isCommunitys());
    model.put("action", config.urls.tasks);
    model.put("backUrl", config.urls.tasks + "/library");
    model.put("measures", Measure.all());
    model.put("rawInstructions", raw.instructions());
    model.put("rawSummary", raw.summary());
    model.put("rawTags", raw.tags());
    model.put("rawReference", raw.referenceUrl());
    model.put("restSeconds", raw.restSeconds());
    model.put("restSaid", def.hasRest() ? def.restSaid() : null);

    historyInto(model, accounts, def, me.id(), config);
    recorder.status(200);
    send(ctx, req, accounts, csrf, templates.render("definition", model));
  }

  /** what this has come to, for the person looking at it and nobody else */
  static void historyInto(Map<String, Object> model, Accounts accounts, Records.Def def,
                          long userId, DomainConfig config) throws SQLException {
    Records.History history = accounts.tasks.historyOf(def, userId);
    model.put("occasions", history.occasions());
    model.put("sets", history.sets());
    model.put("anyHistory", history.any());
    model.put("verdict", history.verdict());
    model.put("lastAt", history.lastAt() == null ? null : stamp(history.lastAt(), config));
    if (def.measure().hasOneRepMax()) {
      model.put("oneRepMax", history.estimatedMax());
      model.put("oneRepMaxLabel", def.measure().oneRepMaxLabel());
      model.put("oneRepMaxAt", history.bestOneRepMaxAt() == null ? null
          : stamp(history.bestOneRepMaxAt(), config));
      model.put("honestReps", Measure.HONEST_REPS);
    }
    model.put("difficulty", round(history.averageDifficulty()));
    model.put("timeCost", round(history.averageTime()));
    model.put("impact", round(history.averageImpact()));
    model.put("rated", history.averageImpact() != null);
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    double best = history.bestEffort() == null ? 0 : history.bestEffort();
    for (Records.Entry entry : history.recent()) {
      LinkedHashMap<String, Object> row = new LinkedHashMap<>();
      row.put("what", entry.describe(def.measure()));
      row.put("when", stamp(entry.recordedAt(), config));
      row.put("note", entry.note().isBlank() ? null : entry.note());
      Double effort = entry.effort(def.measure());
      row.put("share", effort == null || best <= 0 ? 0
          : (int) Math.max(2, Math.min(100, effort / best * 100)));
      rows.add(row);
    }
    model.put("entries", rows);
  }

  // ---- doing things ----------------------------------------------------------------------------

  private void act(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                   FullHttpRequest req, UserRecord me, WebHandler.Recorder recorder)
      throws SQLException {
    Forms form = Forms.of(req, Forms.MAX_CONTENT_BYTES);
    String where = config.urls.tasks;
    if (form.bodyTooLarge()
        || !Cookies.csrfMatches(form.get(Cookies.CSRF_FIELD),
            Forms.cookie(req, Cookies.CSRF_COOKIE))) {
      redirect(ctx, req, recorder, where);
      return;
    }
    LocalDate today = LocalDate.now(config.zone);
    switch (String.valueOf(form.get("action"))) {
      case "new_project" -> {
        boolean forCommunity = form.get("shared") != null
            && accounts.access.can(me, io.hearth.auth.Permission.tasks_share);
        String name = form.get("name");
        if (name != null && !name.isBlank()) {
          Records.Project project = accounts.tasks.saveProject(null,
              forCommunity ? null : me.id(), name, form.text("summary"),
              form.get("task_word"), form.get("tasks_word"),
              TaskStore.phasesOf(phasesFrom(form.get("phases"))),
              intOf(form.get("hide_done_hours"), 24), me.id());
          where = config.urls.tasks + "/" + project.id();
        }
      }
      case "edit_project" -> {
        Records.Project project = accounts.tasks.project(idOf(form.get("project")));
        if (mayOpen(project, me) && mayEdit(accounts, me, project)) {
          accounts.tasks.saveProject(project.id(), project.ownerId(), form.get("name"),
              form.text("summary"), form.get("task_word"), form.get("tasks_word"),
              TaskStore.phasesOf(phasesFrom(form.get("phases"))),
              intOf(form.get("hide_done_hours"), project.hideDoneHours()), me.id());
          where = config.urls.tasks + "/" + project.id();
        }
      }
      case "delete_project" -> {
        Records.Project project = accounts.tasks.project(idOf(form.get("project")));
        if (mayOpen(project, me) && mayEdit(accounts, me, project)) {
          accounts.tasks.deleteProject(project.id(), me.id());
        }
      }
      case "add_task" -> {
        Records.Project project = accounts.tasks.project(idOf(form.get("project")));
        if (mayOpen(project, me)) {
          Long defId = form.get("def") == null || form.get("def").isBlank() ? null
              : idOf(form.get("def"));
          Records.Def def = defId == null ? null : accounts.tasks.def(defId);
          String title = form.get("title");
          if (title == null || title.isBlank()) {
            title = def == null ? null : def.name();
          }
          if (title != null && !title.isBlank()) {
            accounts.tasks.addTask(project.id(), def == null ? null : def.id(), title,
                form.text("notes"), project.firstPhase(),
                intOf(form.get("repeat_days"), 0), dayOf(form.get("due_on")), null, me.id());
          }
          where = config.urls.tasks + "/" + project.id();
        }
      }
      case "edit_task" -> {
        Records.Task task = accounts.tasks.task(idOf(form.get("task")));
        Records.Project project = task == null ? null : accounts.tasks.project(task.projectId());
        if (mayOpen(project, me)) {
          accounts.tasks.editTask(task.id(), form.get("title"), form.text("notes"),
              intOf(form.get("repeat_days"), task.repeatDays()), dayOf(form.get("due_on")),
              me.id());
          where = config.urls.tasks + "/" + project.id() + "/task/" + task.id();
        }
      }
      case "group_task" -> {
        Records.Task task = accounts.tasks.task(idOf(form.get("task")));
        Records.Project project = task == null ? null : accounts.tasks.project(task.projectId());
        if (mayOpen(project, me)) {
          String name = form.get("group_name");
          Records.Grouping mode = Records.Grouping.of(form.get("group_mode"));
          accounts.tasks.group(task.id(), name, mode, me.id());
          where = config.urls.tasks + "/" + project.id() + "/task/" + task.id();
        }
      }
      case "move_task" -> {
        Records.Task task = accounts.tasks.task(idOf(form.get("task")));
        Records.Project project = task == null ? null : accounts.tasks.project(task.projectId());
        if (mayOpen(project, me) && project.hasPhase(form.get("phase"))) {
          accounts.tasks.moveTask(task.id(), form.get("phase"), me.id());
          // reaching the last column is what finished means on a board, so it is ticked too
          if (project.lastPhase().equals(form.get("phase"))) {
            accounts.tasks.complete(task.id(), true, today, me.id());
          }
          where = config.urls.tasks + "/" + project.id();
        }
      }
      case "complete" -> {
        Records.Task task = accounts.tasks.task(idOf(form.get("task")));
        Records.Project project = task == null ? null : accounts.tasks.project(task.projectId());
        if (mayOpen(project, me)) {
          boolean done = !"0".equals(form.get("done"));
          accounts.tasks.complete(task.id(), done, today, me.id());
          if (done) {
            // A plain tick is a data point too. Without this a todo list would record nothing at
            // all, and the whole point of the feedback is that it works on chores as well as
            // exercises -- "that took an hour and achieved nothing" is worth knowing.
            accounts.tasks.record(task.id(), task.defId(), project.id(), me.id(), 0,
                null, null, null, null, ratingOf(form.get("difficulty")),
                ratingOf(form.get("time_cost")), ratingOf(form.get("impact")), form.get("note"));
          }
          where = config.urls.tasks + "/" + project.id();
        }
      }
      case "log_set" -> {
        Records.Task task = accounts.tasks.task(idOf(form.get("task")));
        Records.Project project = task == null ? null : accounts.tasks.project(task.projectId());
        if (mayOpen(project, me) && task.defId() != null) {
          Records.Def def = accounts.tasks.resolved(accounts.tasks.def(task.defId()));
          int setIndex = accounts.tasks.entriesForTask(task.id(), me.id(), 200).size();
          accounts.tasks.record(task.id(), task.defId(), project.id(), me.id(), setIndex,
              doubleOf(form.get("weight")), intOrNull(form.get("reps")),
              secondsOf(form.get("seconds")), doubleOf(form.get("distance")),
              ratingOf(form.get("difficulty")), ratingOf(form.get("time_cost")),
              ratingOf(form.get("impact")), form.get("note"));
          verbose.detail(() -> "tasks: " + me.email() + " logged a set of " + def.name());
          where = config.urls.tasks + "/" + project.id() + "/task/" + task.id();
        }
      }
      case "delete_entry" -> {
        Records.Entry entry = accounts.tasks.entry(idOf(form.get("entry")));
        if (entry != null && entry.userId() == me.id()) {
          accounts.tasks.deleteEntry(entry.id(), me.id());
          where = entry.taskId() == null || entry.projectId() == null ? config.urls.tasks
              : config.urls.tasks + "/" + entry.projectId() + "/task/" + entry.taskId();
        }
      }
      case "delete_task" -> {
        Records.Task task = accounts.tasks.task(idOf(form.get("task")));
        Records.Project project = task == null ? null : accounts.tasks.project(task.projectId());
        if (mayOpen(project, me)) {
          accounts.tasks.deleteTask(task.id(), me.id());
          where = config.urls.tasks + "/" + project.id();
        }
      }
      case "new_def", "edit_def" -> {
        where = saveDefinition(config, accounts, form, me);
      }
      case "adopt" -> {
        Records.Def parent = accounts.tasks.def(idOf(form.get("def")));
        if (parent != null && parent.isCommunitys() && parent.shared()) {
          Records.Def taken = accounts.tasks.adopt(parent.id(), me.id(), me.id());
          where = config.urls.tasks + "/library/" + taken.id();
        }
      }
      case "retire_def" -> {
        Records.Def def = accounts.tasks.def(idOf(form.get("def")));
        if (def != null && def.ownerId() != null && def.ownerId() == me.id()) {
          accounts.tasks.retireDef(def.id(), true, me.id());
        }
        where = config.urls.tasks + "/library";
      }
      default -> {
      }
    }
    redirect(ctx, req, recorder, where);
  }

  private String saveDefinition(DomainConfig config, Accounts accounts, Forms form, UserRecord me)
      throws SQLException {
    Long id = form.get("def") == null || form.get("def").isBlank() ? null : idOf(form.get("def"));
    Records.Def existing = id == null ? null : accounts.tasks.def(id);
    if (id != null && (existing == null
        || (existing.ownerId() == null
            ? !accounts.access.can(me, io.hearth.auth.Permission.tasks_share)
            : existing.ownerId() != me.id()))) {
      return config.urls.tasks + "/library";
    }
    String name = form.get("name");
    Measure measure = Measure.of(form.get("measure"));
    if (name == null || name.isBlank() || measure == null || form.tooLong() != null) {
      return config.urls.tasks + "/library";
    }
    // Sharing a definition with the whole community is a different act from writing one for
    // yourself, so it takes a permission -- a library everybody may add to is a library nobody
    // can find anything in.
    boolean toCommunity = form.get("share") != null
        && accounts.access.can(me, io.hearth.auth.Permission.tasks_share);
    Long owner = existing != null ? existing.ownerId() : (toCommunity ? null : me.id());
    Records.Def saved = accounts.tasks.saveDef(id, owner,
        existing == null ? null : existing.parentId(), name, measure, form.get("summary"),
        form.text("instructions"), form.get("reference_url"), form.get("tags"),
        targetFrom(form), intOf(form.get("rest_seconds"), existing == null ? 0
            : existing.restSeconds()),
        owner == null && toCommunity, me.id());
    return config.urls.tasks + "/library/" + saved.id();
  }

  private static boolean mayEdit(Accounts accounts, UserRecord me, Records.Project project)
      throws SQLException {
    return project.ownerId() != null
        || accounts.access.can(me, io.hearth.auth.Permission.tasks_share);
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private Map<String, Object> base(DomainConfig config, Accounts accounts, FullHttpRequest req,
                                   String title, String csrf) throws SQLException {
    Map<String, Object> model = new HashMap<>();
    Chrome.site(model, config, accounts, req);
    model.put("title", title);
    model.put("community", config.name);
    model.put("csrf", csrf);
    model.put("nav", Navigation.forRequest(config, accounts, req));
    model.put("tasksUrl", config.urls.tasks);
    model.put("action", config.urls.tasks);
    return model;
  }

  private void send(ChannelHandlerContext ctx, FullHttpRequest req, Accounts accounts, String csrf,
                    byte[] html) {
    Responses.send(ctx, req, HttpResponseStatus.OK, "text/html; charset=utf-8", html,
        new String[]{HttpHeaderNames.SET_COOKIE.toString(), Cookies.csrf(accounts.security, csrf)});
  }

  private void notHere(DomainConfig config, Accounts accounts, ChannelHandlerContext ctx,
                       FullHttpRequest req, WebHandler.Recorder recorder) throws SQLException {
    // 404 rather than 403: whether somebody else's training log exists is itself their business
    String csrf = Cookies.stableToken(req);
    Map<String, Object> model = base(config, accounts, req, "Not here", csrf);
    model.put("heading", "That is not here");
    model.put("message", "It may have been removed, or it may not be yours to open.");
    recorder.status(404);
    Responses.sendHtml(ctx, req, HttpResponseStatus.NOT_FOUND, templates.render("message", model));
  }

  private static void redirect(ChannelHandlerContext ctx, FullHttpRequest req,
                               WebHandler.Recorder recorder, String where) {
    recorder.status(303);
    Responses.send(ctx, req, HttpResponseStatus.SEE_OTHER, null, Responses.EMPTY,
        new String[]{HttpHeaderNames.LOCATION.toString(), where});
  }

  private static String phasesFrom(String raw) {
    if (raw == null || raw.isBlank()) {
      return "[]";
    }
    com.fasterxml.jackson.databind.node.ArrayNode array =
        new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
    for (String part : raw.split(",")) {
      if (!part.isBlank()) {
        array.add(part.trim());
      }
    }
    return array.toString();
  }

  private static String targetFrom(Forms form) {
    com.fasterxml.jackson.databind.node.ObjectNode target =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    Integer sets = intOrNull(form.get("target_sets"));
    Integer reps = intOrNull(form.get("target_reps"));
    Double weight = doubleOf(form.get("target_weight"));
    if (sets != null) {
      target.put("sets", sets);
    }
    if (reps != null) {
      target.put("reps", reps);
    }
    if (weight != null) {
      target.put("weight", weight);
    }
    return target.toString();
  }

  static String stamp(Timestamp when, DomainConfig config) {
    return when == null ? null : java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
        .format(java.time.Instant.ofEpochMilli(when.getTime()).atZone(config.zone));
  }

  static String ago(long millis) {
    long minutes = millis / 60_000;
    if (minutes < 1) {
      return "just now";
    }
    if (minutes < 60) {
      return minutes + "m ago";
    }
    if (minutes < 1440) {
      return (minutes / 60) + "h ago";
    }
    return (minutes / 1440) + "d ago";
  }

  private static String round(Double value) {
    return value == null ? null : String.valueOf(Math.round(value * 10) / 10.0);
  }

  private static long idOf(String raw) {
    return longOf(raw);
  }

  private static long longOf(String raw) {
    try {
      return Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static Integer intOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static int intOf(String raw, int fallback) {
    Integer value = intOrNull(raw);
    return value == null ? fallback : value;
  }

  private static Integer ratingOf(String raw) {
    Integer value = intOrNull(raw);
    return value == null || value < 1 || value > 5 ? null : value;
  }

  private static Double doubleOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Seconds, from something somebody typed.
   *
   * "90" is ninety seconds and "1:30" is the same ninety seconds, and both are things a person
   * types into a box labelled time. Refusing the second would be technically defensible and
   * annoying at the exact moment somebody is out of breath.
   */
  private static Integer secondsOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String clean = raw.trim();
    if (clean.contains(":")) {
      String[] parts = clean.split(":");
      Integer minutes = intOrNull(parts[0]);
      Integer seconds = parts.length > 1 ? intOrNull(parts[1]) : 0;
      if (minutes == null) {
        return null;
      }
      return minutes * 60 + (seconds == null ? 0 : seconds);
    }
    return intOrNull(clean);
  }

  private static LocalDate dayOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
