package io.hearth.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools an agent is offered, and what each one does.
 *
 * A tool description is a prompt. The model reads nothing else about this server, so the wording
 * here is the entire briefing it gets -- which is why these say what a thing is *for* and when not
 * to use it, rather than restating the parameter names.
 *
 * The set is deliberately small. Every tool is one a person would recognize as a job ("search the
 * site", "ask the community something") rather than a row operation, because a model given
 * `execute_sql` will eventually execute some SQL.
 */
public class McpTools {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AiSurface surface;

  public McpTools(AiSurface surface) {
    this.surface = surface;
  }

  /** one callable tool: its name, its briefing, and the shape of its arguments */
  public record Tool(String name, String title, String description, ObjectNode schema) {
  }

  /**
   * What each tool needs the acting person to be allowed to do, or null for anybody connected.
   *
   * <b>Offered means usable.</b> A tool a connection can never call is invariant 38 in a model's
   * hands: a control that would refuse teaches whoever meets it that the software is broken, and a
   * model meeting one spends its turns finding a phrasing that works. This is the same map the
   * surface enforces with -- listed here so the two cannot drift, and enforced there because a
   * listing is a courtesy and the surface is the boundary.
   *
   * Reads that a member legitimately has a narrowed version of are absent from this map on
   * purpose: they are offered to everybody and answer with less. Refusing to list events for
   * somebody who can see the calendar would be worse than useless.
   */
  private static final java.util.Map<String, io.hearth.auth.Permission> NEEDS =
      java.util.Map.ofEntries(
          java.util.Map.entry("content_save", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("content_meta", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("content_delete", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("template_list", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("template_get", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("template_save", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("template_delete", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("navigation_get", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("site_spec", io.hearth.auth.Permission.content_read)
          // The gym is the person's own, always.
          //
          // No permission gates it, and that is deliberate rather than an omission: the key belongs
          // to whoever connected the agent, every call uses their id, and there is no argument
          // anywhere for whose. Somebody with no Hevy key gets a refusal that tells them where to
          // get one, which is the right answer and not a permission problem.
          );

  /** what a tool needs, for the listing and for the screen that explains a connection */
  public static io.hearth.auth.Permission needs(String tool) {
    return NEEDS.get(tool);
  }

  public List<Tool> all() {
    ArrayList<Tool> tools = new ArrayList<>();

    tools.add(new Tool("content_list", "List pages",
        "Every page on this site, with its uri, title, template and folder. Start here when you"
            + " need to know what exists. Bodies are not included -- use content_get for one page"
            + " or content_search to find pages by what is in them."
            + " Pages an admin has marked human only are not listed and cannot be reached.",
        schema(prop("folder", "string", "only pages in this navigation folder"),
            prop("published", "boolean", "true for published only, false for drafts only"))));

    tools.add(new Tool("content_search", "Search pages",
        "Find pages whose uri, title or body contains some text. Returns an excerpt around the"
            + " match. Use this rather than listing everything and reading each page.",
        required(schema(prop("query", "string", "text to look for; matching is case insensitive")),
            "query")));

    tools.add(new Tool("content_get", "Read a page",
        "One page in full, including its body exactly as stored. The body is markdown, an HTML"
            + " fragment, or a whole HTML document depending on the page's kind.",
        required(schema(prop("uri", "string", "the page's path, e.g. /about")), "uri")));

    tools.add(new Tool("content_save", "Write a page",
        "Create a page, or change one that exists. Only the fields you pass are changed -- anything"
            + " you leave out keeps its current value, so you can fix a body without resending the"
            + " title. Read the page first if you are editing it; you are replacing the body"
            + " wholesale, not patching it.",
        required(schema(
            prop("uri", "string", "the page's path, e.g. /about; creates it if there is none"),
            prop("title", "string", "shown in the browser tab and available to the template"),
            prop("body", "string", "the page source, in the form its kind implies"),
            prop("kind", "string", "markdown, html, page (a whole HTML document), or javascript"
                + " (a program run on every request -- call site_spec first, its javascript"
                + " section lists every function available and this community's own tables)"),
            prop("template", "string", "the template to wrap it in; ignored when kind is page"),
            prop("folder", "string", "navigation folder; empty leaves it out of the navigation"),
            objectProp("fields", "values for the fields this page's template declares, as"
                + " {\"field_name\": \"value\"}. Read them from template_get. Only the ones you"
                + " pass are changed; a name the template does not declare is refused rather than"
                + " ignored."),
            prop("published", "boolean", "unpublished pages are not served")),
            "uri")));

    tools.add(new Tool("content_meta", "Change a page's details, not its words",
        "Retitle a page, move it between navigation folders, publish or unpublish it, change which"
            + " template wraps it, or fill in the fields that template declares -- while leaving"
            + " the body exactly as it is. This tool cannot write a body at all, which is the"
            + " reason to use it: filing pages or filling in a subtitle should not involve handing"
            + " back somebody's prose and hoping it came through unchanged. Use content_save when"
            + " the words themselves are what is changing. The page has to exist already.",
        required(schema(
            prop("uri", "string", "the page's path, e.g. /about"),
            prop("title", "string", "shown in the browser tab and available to the template"),
            prop("template", "string", "the template to wrap it in"),
            prop("folder", "string", "navigation folder; empty leaves it out of the navigation"),
            objectProp("fields", "values for the fields the template declares, as"
                + " {\"field_name\": \"value\"}; only the ones you pass are changed"),
            prop("published", "boolean", "unpublished pages are not served")),
            "uri")));

    tools.add(new Tool("content_delete", "Delete a page",
        "Remove a page for good. There is no undo and no version history, so prefer setting"
            + " published to false if there is any chance somebody wants it back.",
        required(schema(prop("uri", "string", "the page's path")), "uri")));










    // ---- how to build anything here ------------------------------------------------------------
    //
    // Two dead section headers stood here, for the polls and the training log, describing rules a
    // model would need for features that were removed a while ago.

    // ---- the ranch -----------------------------------------------------------------------------
    //
    // The list is the person's own and there is no argument anywhere for whose. A to-do list is one
    // of the more revealing things anybody keeps.

    tools.add(new Tool("day_sheet", "What today looks like",
        "THE ONE TO START WITH. Three lists: what has to happen today (overdue work and habits due"
            + " now), what is coming up in the next month, and the pool to pull from when today is"
            + " done. Overdue things are in today's list rather than a separate one, because a"
            + " separate overdue section is where things go to be ignored.\n"
            + "Habits carry their streak and how many times they have been kept this week, so you"
            + " can see which are holding and which are slipping without asking again.",
        schema()));

    tools.add(new Tool("task_list", "Everything on the list",
        "Every task and habit, including finished and graduated ones if you ask. Use day_sheet for"
            + " what to actually do; use this to find something by name or to review.",
        schema(prop("include_finished", "boolean", "true to include done, dropped and graduated"))));

    tools.add(new Tool("task_add", "Add a task, a habit, or a challenge",
        "Put something on the list.\n"
            + "A TASK is a piece of work. Give it a due_on if it has a date; leave it off and it"
            + " sits in the pull-from pool. Give it a `process` if it has steps worth tracking --"
            + " see process_list -- and it starts at that process's first state instead of being"
            + " open/done.\n"
            + "A HABIT is something to keep doing. It needs a cadence: `daily`, or `weekly` with"
            + " per_week. Habits are marked with habit_mark and can graduate when they have done"
            + " their job.\n"
            + "A CHALLENGE is a habit with an end: give it ends_on. \"Thirty days of mobility\" is a"
            + " different thing from \"do mobility forever\", and the difference is that it"
            + " finishes -- a challenge graduates itself the day after it ends rather than sitting"
            + " on the sheet being missed. starts_on keeps it off the sheet until it begins.\n"
            + "`area` groups things -- gym, ranch, house, whatever the person already says.",
        required(schema(prop("title", "string", "what it is, in the person's own words"),
                prop("detail", "string", "anything that will not fit in the title"),
                prop("kind", "string", "task (default) or habit"),
                prop("cadence", "string", "habits only: daily or weekly"),
                prop("per_week", "integer", "weekly habits: how many times a week, 1 to 7"),
                prop("due_on", "string", "tasks only: a date, YYYY-MM-DD"),
                prop("starts_on", "string", "challenges: when it begins, YYYY-MM-DD"),
                prop("ends_on", "string", "challenges: the last day, YYYY-MM-DD"),
                prop("process", "string", "a process from process_list, for multi-step work"),
                prop("area", "string", "gym, ranch, house...")),
            "title")));

    tools.add(new Tool("task_change", "Change a task",
        "Edit a task or habit. Only the fields you send are changed -- everything else is left"
            + " alone, so sending a new due date does not blank the detail.",
        required(schema(prop("id", "integer", "from day_sheet or task_list"),
                prop("title", "string", ""), prop("detail", "string", ""),
                prop("due_on", "string", "YYYY-MM-DD"), prop("area", "string", ""),
                prop("cadence", "string", "daily or weekly"),
                prop("per_week", "integer", "1 to 7")),
            "id")));

    tools.add(new Tool("task_move", "Finish it, drop it, or move it a step",
        "Set a task's state. Every task can go to `done` or `dropped`. A task with a process can"
            + " also go to any of that process's states -- day_sheet tells you the next one. A"
            + " state that is not in the process is refused rather than stored, because a typo puts"
            + " work in a state no screen lists and nobody finds it again.\n"
            + "Habits are not finished this way. Mark them with habit_mark.",
        required(schema(prop("id", "integer", "from day_sheet"),
                prop("state", "string", "done, dropped, open, or a state of its process")),
            "id", "state")));

    tools.add(new Tool("habit_mark", "Mark a habit kept",
        "Record that a habit was done, today by default. Marking twice on one day is the same as"
            + " once. Comes back with the streak and the week so far, so you can tell the person"
            + " how it is going without another call.",
        required(schema(prop("id", "integer", "from day_sheet"),
                prop("day", "string", "YYYY-MM-DD; omit for today"),
                prop("note", "string", "anything worth remembering about this one")),
            "id")));

    tools.add(new Tool("habit_history", "How a habit is going",
        "Every day a habit was kept, with the streak and the last week and month. Use this before"
            + " suggesting a habit graduates -- the shape matters more than the count, and a habit"
            + " with a good number and a two-week hole in it has not finished its job.",
        required(schema(prop("id", "integer", "from day_sheet"),
                prop("days", "integer", "how far back to look, up to 400")),
            "id")));

    tools.add(new Tool("habit_graduate", "Retire a habit that has done its job",
        "Take a habit off the sheet, keeping every mark. This is what a habit is FOR -- it exists"
            + " to stop needing to exist, and graduating is the success case rather than deleting."
            + " Suggest it when a habit has been kept without effort for long enough that tracking"
            + " it is no longer doing anything; check habit_history first.",
        required(schema(prop("id", "integer", "from day_sheet")), "id")));

    tools.add(new Tool("process_list", "The state machines tasks can walk",
        "Named sequences of states, for work that is not done-or-not: a fence goes surveyed ->"
            + " materials -> built -> checked. Read this before giving a task a process.",
        schema()));

    tools.add(new Tool("process_save", "Define a state machine",
        "Create or replace a process: a name, a title, and the states in ORDER -- the order is what"
            + " 'the next step' means. Two states at least; `done` and `dropped` are not states you"
            + " define, every task already has them.\n"
            + "Redefining one does not move the tasks already walking it, on purpose: quietly"
            + " moving somebody's work to a step it has not reached is worse than an odd row.",
        required(schema(prop("process", "string", "short name, e.g. fence-repair"),
                prop("title", "string", "what it is called"),
                stringArrayProp("states", "in order, e.g. [surveyed, materials, built, checked]")),
            "process", "states")));

    // ---- getting people together -----------------------------------------------------------------
    //
    // These carry the most instruction of any tool here, because the failure mode is social rather
    // than technical: an agent that proposes a night its person cannot make, or that decides on
    // everybody's behalf without asking, produces a real argument between real people. So the
    // descriptions say when to stop and let the humans choose.

    tools.add(new Tool("vote_list", "Votes in progress",
        "Decisions being made here. Start with this when asked to help arrange something -- there"
            + " may already be a vote open about it, and a second one about the same evening is how"
            + " a group ends up meeting twice.",
        schema(prop("open_only", "boolean", "true for votes still taking ballots"))));

    tools.add(new Tool("vote_get", "One vote, weighed, and how it got here",
        "Everything about one vote: the options, where each stands, what each one would COST, and"
            + " the full history of who proposed and voted what and why. READ THIS BEFORE VOTING."
            + " The history is the point -- somebody may already have explained why Thursday is"
            + " impossible, and a ballot that ignores what was said is how a vote goes round in"
            + " circles.\n"
            + "`weighed` is the part to reason with. THERE IS ALWAYS AN IMPERFECT NIGHT: the job is"
            + " not to find one nobody objects to, it is to find the one that costs least and say"
            + " what it costs. Each option carries how many can come, how many would have to move"
            + " something, how many cannot, and a per-person breakdown saying WHY -- a vote, a"
            + " one-off in a calendar, or a repeating commitment.\n"
            + "A repeating commitment is NOT a refusal. It is a real thing and it is the kind of"
            + " thing people move for something that matters. Say so when you propose: \"Ana has"
            + " her usual Tuesday thing, but it is her sister's birthday so she may shift it.\"",
        required(schema(prop("vote", "string", "the vote's name, from vote_list")), "vote")));

    tools.add(new Tool("vote_open", "Start a vote",
        "Open a decision for people and their agents to converge on. Give it a short name (lowercase"
            + " letters, digits and dashes), a title, the question in a sentence, and as many first"
            + " options as you have. Others can add more -- the pool is meant to grow.\n"
            + "Check when_free first if this is about a date: proposing options nobody can make"
            + " wastes everybody's turns.",
        required(schema(prop("vote", "string", "short name, e.g. board-games-october"),
                prop("title", "string", "what this is deciding"),
                prop("question", "string", "the question in a sentence"),
                prop("mode", "string",
                    "how it decides. `consensus` (the default) means one `blocked` removes an"
                        + " option -- right for a handful of people where the point is that"
                        + " everybody comes. `majority` means the option the most people can make"
                        + " wins -- use it for a larger group, where somebody is always away and"
                        + " consensus converges on nothing."),
                prop("host", "string",
                    "the display name of whoever is having people round, if somebody is. Their"
                        + " `blocked` is final in EITHER mode -- a majority cannot vote somebody"
                        + " into hosting -- and the invitation waits until they have said yes."),
                stringArrayProp("options", "the first options; more can be added later")),
            "vote", "title")));

    tools.add(new Tool("vote_ask_host", "Ask the host, and nobody else",
        "Email the person hosting to ask whether they will have people round on the chosen date."
            + " NOBODY ELSE IS TOLD by this. That ordering is the point: \"will you host on the"
            + " 9th\" is a question one person can say no to, and \"we are meeting at Ana's on the"
            + " 9th\" is not. Do this after a person has picked the option, then wait.",
        required(schema(prop("vote", "string", "the vote's name")), "vote")));

    tools.add(new Tool("vote_host_answer", "Answer as the host",
        "Record that YOUR person -- the host -- will or will not host it. Only the host can answer."
            + " A no puts the vote back to narrowed so the group can pick another date or another"
            + " house; it does not abandon it.",
        required(schema(prop("vote", "string", "the vote's name"),
                prop("yes", "boolean", "true if they will host"),
                prop("why", "string", "worth saying, especially for a no")),
            "vote", "yes")));

    tools.add(new Tool("vote_invite", "Send the invitation",
        "Email everybody about the evening. This is the last step and it is refused until there is"
            + " an outcome AND, if there is a host, until they have said yes -- because the whole"
            + " reason to name a host is that one person is asked before twelve are told. It sends"
            + " once.",
        required(schema(prop("vote", "string", "the vote's name"),
                prop("where", "string", "where it is happening, if that is settled")),
            "vote")));

    tools.add(new Tool("vote_propose", "Add an option",
        "Put another option into a vote, at any point -- including after voting has started, which"
            + " is normal here. If everything on the table is blocked, propose something else"
            + " rather than arguing for a blocked option.",
        required(schema(prop("vote", "string", "the vote's name"),
                prop("option", "string", "a short label, e.g. 'Thursday 9 October, 7pm'"),
                prop("detail", "string", "anything that will not fit in the label"),
                prop("starts_at", "string",
                    "GIVE THIS WHENEVER THE OPTION IS A REAL TIME. An ISO instant like"
                        + " 2026-10-09T19:00:00Z. It is what lets vote_get check the option"
                        + " against everybody's calendar and tell you who would have to move"
                        + " something; without it the option is weighed on ballots alone."),
                prop("ends_at", "string", "ISO instant; three hours is assumed if you leave it")),
            "vote", "option")));

    tools.add(new Tool("vote_cast", "Vote",
        "Cast your person's ballot on one option. One of:\n"
            + "  yes     -- they want this\n"
            + "  fine    -- they can do this\n"
            + "  no      -- they would rather not\n"
            + "  blocked -- they CANNOT do this\n"
            + "`blocked` is a veto, not a strong no: one of them removes an option however many"
            + " yes votes it has, because a date somebody cannot attend is worse than no date. Use"
            + " it only for a real impossibility, and always give a reason -- other agents read"
            + " it.\n"
            + "You vote as the person who connected you and nobody else. Voting again on the same"
            + " option replaces your last ballot, and both stay in the history.",
        required(schema(prop("vote", "string", "the vote's name"),
                prop("option", "string", "the option's label"),
                prop("ballot", "string", "yes, fine, no or blocked"),
                prop("because", "string", "why -- other agents read this and it changes outcomes")),
            "vote", "option", "ballot")));

    tools.add(new Tool("vote_narrow", "Cut it down to a shortlist",
        "Drop everything except the best few options, recording what went and why. Blocked options"
            + " go whatever their score. Do this when the pool has grown past what a person will"
            + " read -- the purpose is to hand humans a short list they can choose between, not to"
            + " pick a winner.",
        required(schema(prop("vote", "string", "the vote's name"),
                prop("keep", "integer", "how many to keep, 2 to 10")),
            "vote")));

    tools.add(new Tool("vote_decide", "Settle it",
        "Record the outcome. DO NOT DO THIS ON YOUR OWN INITIATIVE. Narrowing is an agent's job;"
            + " deciding is the humans'. Call this when a person has told you what was chosen, and"
            + " otherwise leave the vote narrowed and tell them it is ready for them.",
        required(schema(prop("vote", "string", "the vote's name"),
                prop("option", "string", "the option that won")),
            "vote", "option")));

    tools.add(new Tool("when_free", "When people are free",
        "What everybody here has said about their availability, AND how movable they each are."
            + " Start here before proposing anything -- this is what stops the first round being"
            + " guesswork.\n"
            + "`can_host` says whether they will have people round. `flexibility` is the seed that"
            + " a free/busy grid cannot give you: `mostly_free` means propose freely and let the"
            + " calendar show the exceptions; `tightly_booked` means assume a conflict and expect"
            + " something may have to move; `it_depends` is in between. One person may host and"
            + " have a full calendar while another is free most evenings and immovable on three --"
            + " the same proposal is right for one and wrong for the other.\n"
            + "`repeating_commitments` is counted separately from `fixed_commitments` on purpose."
            + " Repeating ones are movable; treating them as walls means proposing nothing at all"
            + " for a group of five, because everybody has a standing something.\n"
            + "Each person also comes back with a `kind` and a sentence saying what to do with it,"
            + " and you must read that:\n"
            + "  calendar -- an ICS url they share. Fetch it YOURSELF and read real engagements;"
            + " this server does not hold a copy.\n"
            + "  weekly   -- a rough shape they typed. It is NOT a calendar. It says what they"
            + " usually can do, not what they have already agreed to. Anything built on it is a"
            + " proposal to confirm, never a commitment.\n"
            + "  both     -- the calendar says what is impossible, the shape says what is welcome.\n"
            + "Somebody who has said nothing is simply absent from this list. Do not fill that gap"
            + " with a guess: propose options and let their agent vote.",
        schema()));

    // ---- the gym -------------------------------------------------------------------------------
    //
    // These descriptions carry more than the others because a model has no screen and Hevy's
    // vocabulary is not guessable: that an exercise is a *template* with an id, that a routine is
    // a list of those with sets attached, and above all that when the exercise it wants does not
    // exist it can make one. That last is the whole reason this surface exists -- the mobility
    // work worth programming is not in anybody's standard list.

    tools.add(new Tool("gym_workouts", "Recent workouts",
        "Workouts already performed, newest first, with every exercise and set. This is the record"
            + " of what was actually done -- read it before writing a routine so the weights and"
            + " volumes you choose follow from what the person has been lifting.",
        schema(prop("page", "integer", "1 is the most recent page"),
            prop("page_size", "integer", "up to 10"))));

    tools.add(new Tool("gym_workout", "One workout",
        "A single workout in full, by its id, which gym_workouts gives you.",
        required(schema(prop("workout_id", "string", "the workout's id")), "workout_id")));

    tools.add(new Tool("gym_exercises", "Exercise templates",
        "Every exercise available to this account -- Hevy's built-in list plus any custom ones."
            + " Each has an `id` (Hevy calls it exercise_template_id), a title, a type, an"
            + " equipment category and a muscle group. A routine refers to exercises by that id, so"
            + " search here first. The list is long: page through it, or ask for a large page_size."
            + " If what you want is not here, make it with gym_exercise_create rather than"
            + " substituting something close.",
        schema(prop("page", "integer", "1 is the first page"),
            prop("page_size", "integer", "up to 100"))));

    tools.add(new Tool("gym_exercise_history", "History of one exercise",
        "Every set ever performed of one exercise, by template id. Use it to pick a working weight"
            + " rather than guessing.",
        required(schema(prop("exercise_template_id", "string", "from gym_exercises")),
            "exercise_template_id")));

    tools.add(new Tool("gym_exercise_create", "Invent an exercise",
        "Create a custom exercise on this Hevy account, and then use its id in a routine."
            + " THIS IS THE IMPORTANT ONE. Hevy's built-in list covers barbells and machines and"
            + " almost none of the mobility, rehab and positional work worth programming -- 90/90"
            + " hip switches, couch stretch holds, loaded carries with an odd implement. Do not"
            + " approximate with a lift that happens to be listed: make the exercise, name it what"
            + " it is, and use it. Custom exercises are permanent and appear in the app.\n"
            + "exercise_type decides what the app asks for on each set, so choose it from the"
            + " movement rather than from habit: `duration` for a held stretch, `reps_only` for"
            + " unloaded mobility, `weight_reps` for a normal lift, `distance_duration` for a"
            + " carry or a walk, `bodyweight_reps` for push-ups and pull-ups.",
        required(schema(
                prop("title", "string", "what it is called, e.g. '90/90 Hip Switch'"),
                prop("exercise_type", "string",
                    "one of: " + String.join(", ", io.hearth.hevy.Hevy.EXERCISE_TYPES)),
                prop("equipment_category", "string",
                    "one of: " + String.join(", ", io.hearth.hevy.Hevy.EQUIPMENT)
                        + " -- use `none` for bodyweight and floor work"),
                prop("muscle_group", "string",
                    "the main one; one of: "
                        + String.join(", ", io.hearth.hevy.Hevy.MUSCLE_GROUPS)),
                stringArrayProp("other_muscles", "anything else it works, from the same list")),
            "title", "exercise_type", "equipment_category", "muscle_group")));

    tools.add(new Tool("gym_routines", "Routines",
        "The routines saved on this account, with the exercises and sets in each.",
        schema(prop("page", "integer", "1 is the first page"),
            prop("page_size", "integer", "up to 10"))));

    tools.add(new Tool("gym_routine_create", "Build a routine",
        "Create a routine from a list of exercises, each with its sets. Every exercise needs an"
            + " exercise_template_id from gym_exercises (or one you just made with"
            + " gym_exercise_create), and at least one set -- an exercise with no sets is one Hevy"
            + " accepts and nobody can perform.\n"
            + "A set is {type, and whichever of weight_kg / reps / duration_seconds /"
            + " distance_meters the exercise's type calls for}. type is one of "
            + String.join(", ", io.hearth.hevy.Hevy.SET_TYPES) + "; use `warmup` for the ramp-up"
            + " sets so the app counts volume properly. rest_seconds and notes go on the exercise,"
            + " not the set.",
        required(schema(
                prop("title", "string", "what the routine is called"),
                prop("notes", "string", "anything the person should read before starting"),
                prop("folder_id", "integer", "a folder from gym_folders; omit for My Routines"),
                objectArrayProp("exercises",
                    "in order: {exercise_template_id, rest_seconds, notes, sets: [...]}")),
            "title", "exercises")));

    tools.add(new Tool("gym_routine_update", "Replace a routine",
        "Overwrite an existing routine with a new set of exercises. The whole routine is replaced,"
            + " so send everything you want it to end up with -- read it back with gym_routines"
            + " first if you are changing part of it.",
        required(schema(prop("routine_id", "string", "from gym_routines"),
                prop("title", "string", "what the routine is called"),
                prop("notes", "string", "anything the person should read before starting"),
                objectArrayProp("exercises", "the complete new list, as in gym_routine_create")),
            "routine_id", "title", "exercises")));

    tools.add(new Tool("gym_folders", "Routine folders",
        "The folders routines can be filed in, with their ids.",
        schema(prop("page", "integer", "1 is the first page"))));

    tools.add(new Tool("gym_folder_create", "Make a routine folder",
        "Create a folder to file routines in, e.g. one per training block.",
        required(schema(prop("title", "string", "what the folder is called")), "title")));

    tools.add(new Tool("site_spec", "How to build a site here",
        "Everything you need to write pages that work: every kind of page, the address rule for"
            + " each, how a template declares fields, and -- in its `javascript` section -- every"
            + " function a dynamic page can call, including this community's own tables and the"
            + " shape of their rows. That part is generated from what exists right now, so read it"
            + " again rather than remembering it. Read this before writing anything other than a"
            + " plain markdown page.",
        schema()));

    tools.add(new Tool("navigation_get", "Read the navigation",
        "The navigation tree: which pages sit in which folder, and which pages sit outside it. A"
            + " page outside the navigation is reachable by its uri and by nothing else, which is"
            + " usually an oversight worth reporting.",
        schema()));

    tools.add(new Tool("template_list", "List templates",
        "The templates pages can be wrapped in, with the fields each one declares and how many"
            + " pages use it.",
        schema()));

    tools.add(new Tool("template_get", "Read a template",
        "One template's mustache source, the fields it declares in full, and the uris that depend"
            + " on it. Read this before template_save if you are changing the fields: saving them"
            + " replaces the declaration wholesale, so send back the ones you are keeping.",
        required(schema(prop("name", "string", "the template's name")), "name")));

    tools.add(new Tool("template_save", "Write a template",
        "Create or replace a template. Saving one immediately re-renders every page that uses it,"
            + " so check template_get first and know what you are about to change. Use {{{body}}}"
            + " with three braces for the page content; two braces will escape the markup and show"
            + " it as text."
            + " A template can also publish a **directory index**: an address of its own where"
            + " every published page using it appears in a paginated listing. That index is a"
            + " *second* template with a second job -- a list rather than a document -- so it has"
            + " its own body. Pass directory_body to write it; leave it out and a working listing"
            + " is written for you. The index is given {{#entries}} (uri, title, at, excerpt,"
            + " folder, and any field this template declares), count, page, pages,"
            + " prevUrl/nextUrl/firstUrl/lastUrl and {{#numbers}}."
            + " A template can also **declare fields** -- the things it wants from every page"
            + " beyond a body, like a subtitle or a hero line. Declaring one puts a box on the"
            + " page editor and makes {{field_name}} available in this template; pages fill it in"
            + " with content_save or content_meta. Leave fields out and what is declared stays as"
            + " it is; pass it and it becomes exactly what you sent, so read template_get first.",
        required(schema(
            prop("name", "string", "letters, digits, underscore or hyphen"),
            prop("body", "string", "the mustache template source"),
            objectArrayProp("fields", "what every page using this template is asked for, as"
                + " [{\"name\":\"subtitle\",\"type\":\"text\",\"label\":\"Subtitle\","
                + "\"help\":\"one line under the title\",\"required\":false}]."
                + " name is lowercase letters, digits and underscore. type is one of text,"
                + " multiline, markdown, number, bool, url, date. Removing a field stops pages"
                + " being asked for it and does not delete what they already recorded."),
            prop("directory", "boolean", "publish an index of every page using this template"),
            prop("directory_path", "string", "where the index lives, e.g. /blog"),
            prop("directory_pattern", "string",
                "how page two is addressed, with {page} in it; page one is always the bare path"),
            prop("directory_body", "string", "the index's own mustache source"),
            prop("directory_page_size", "integer", "entries per page"),
            prop("directory_order", "string", "newest or oldest, by each page's published date")),
            "name", "body")));

    tools.add(new Tool("template_delete", "Delete a template",
        "Remove a template. Refused while any page still uses it.",
        required(schema(prop("name", "string", "the template's name")), "name")));









    return tools;
  }

  /** the tools/list payload, narrowed to what this connection can actually call */
  public ArrayNode listing() throws SQLException {
    ArrayNode array = JSON.createArrayNode();
    for (Tool tool : offered()) {
      ObjectNode node = array.addObject();
      node.put("name", tool.name());
      node.put("title", tool.title());
      node.put("description", tool.description());
      node.set("inputSchema", tool.schema());
    }
    return array;
  }

  /** every tool this connection may call; a write surface is absent rather than refusing */
  public List<Tool> offered() throws SQLException {
    ArrayList<Tool> offered = new ArrayList<>();
    for (Tool tool : all()) {
      io.hearth.auth.Permission needed = NEEDS.get(tool.name());
      if (needed == null || surface.may(needed)) {
        offered.add(tool);
      }
    }
    return offered;
  }

  public boolean has(String name) {
    return all().stream().anyMatch(tool -> tool.name().equals(name));
  }

  /** what a tool call produced, plus the short line that goes in the AI log */
  public record Result(Object payload, String subject, String detail) {
  }

  /**
   * Run a tool.
   *
   * Everything reachable from here goes through {@link AiSurface}, which is where the human-only
   * rule and the read-only rule live. Nothing in this class talks to a store.
   */
  public Result call(String name, JsonNode arguments) throws SQLException, AiSurface.Refused {
    Map<String, Object> args = asMap(arguments);
    switch (name) {
      case "day_sheet" -> {
        Map<String, Object> sheet = surface.daySheet();
        return new Result(sheet, null, "read today's sheet");
      }
      case "task_list" -> {
        List<Map<String, Object>> tasks = surface.listTasks(
            optBoolean(args, "include_finished") != null && optBoolean(args, "include_finished"));
        return new Result(Map.of("tasks", tasks, "count", tasks.size()), null,
            tasks.size() + " item(s)");
      }
      case "task_add" -> {
        String title = optString(args, "title");
        return new Result(surface.addTask(args), title, "added '" + title + "'");
      }
      case "task_change" -> {
        long id = optInt(args, "id", 0);
        return new Result(surface.changeTask(id, args), String.valueOf(id), "changed task " + id);
      }
      case "task_move" -> {
        long id = optInt(args, "id", 0);
        String state = optString(args, "state");
        return new Result(surface.moveTask(id, state), String.valueOf(id),
            "moved task " + id + " to " + state);
      }
      case "habit_mark" -> {
        long id = optInt(args, "id", 0);
        return new Result(surface.markHabit(id, optString(args, "day"), optString(args, "note")),
            String.valueOf(id), "marked habit " + id);
      }
      case "habit_history" -> {
        long id = optInt(args, "id", 0);
        return new Result(surface.habitHistory(id, optInt(args, "days", 60)),
            String.valueOf(id), "read the history of habit " + id);
      }
      case "habit_graduate" -> {
        long id = optInt(args, "id", 0);
        return new Result(surface.graduateHabit(id), String.valueOf(id),
            "graduated habit " + id);
      }
      case "process_list" -> {
        List<Map<String, Object>> processes = surface.listProcesses();
        return new Result(Map.of("processes", processes, "count", processes.size()), null,
            processes.size() + " process(es)");
      }
      case "process_save" -> {
        String slug = optString(args, "process");
        return new Result(surface.saveProcess(slug, optString(args, "title"), args.get("states")),
            slug, "defined the process " + slug);
      }
      case "vote_list" -> {
        List<Map<String, Object>> votes = surface.listVotes(optBoolean(args, "open_only") != null
            && optBoolean(args, "open_only"));
        return new Result(Map.of("votes", votes, "count", votes.size()), null,
            votes.size() + " vote(s)");
      }
      case "vote_get" -> {
        String slug = optString(args, "vote");
        return new Result(surface.getVote(slug), slug, "read the vote " + slug);
      }
      case "vote_open" -> {
        String slug = optString(args, "vote");
        return new Result(surface.openVote(args), slug, "opened the vote " + slug);
      }
      case "vote_ask_host" -> {
        String slug = optString(args, "vote");
        return new Result(surface.askHost(slug), slug, "asked the host about " + slug);
      }
      case "vote_host_answer" -> {
        String slug = optString(args, "vote");
        boolean yes = Boolean.TRUE.equals(optBoolean(args, "yes"));
        return new Result(surface.answerAsHost(slug, yes, optString(args, "why")), slug,
            (yes ? "accepted" : "declined") + " hosting " + slug);
      }
      case "vote_invite" -> {
        String slug = optString(args, "vote");
        return new Result(surface.sendInvitations(slug, optString(args, "where")), slug,
            "sent the invitation for " + slug);
      }
      case "vote_propose" -> {
        String slug = optString(args, "vote");
        String option = optString(args, "option");
        return new Result(surface.proposeOption(slug, option, optString(args, "detail"),
            optString(args, "starts_at"), optString(args, "ends_at")),
            slug, "proposed '" + option + "' in " + slug);
      }
      case "vote_cast" -> {
        String slug = optString(args, "vote");
        String option = optString(args, "option");
        String ballot = optString(args, "ballot");
        return new Result(surface.castBallot(slug, option, ballot, optString(args, "because")),
            slug, "voted " + ballot + " on '" + option + "' in " + slug);
      }
      case "vote_narrow" -> {
        String slug = optString(args, "vote");
        return new Result(surface.narrowVote(slug, optInt(args, "keep", 3)), slug,
            "narrowed " + slug);
      }
      case "vote_decide" -> {
        String slug = optString(args, "vote");
        String option = optString(args, "option");
        return new Result(surface.decideVote(slug, option), slug,
            "decided " + slug + ": " + option);
      }
      case "when_free" -> {
        List<Map<String, Object>> people = surface.whenPeopleAreFree();
        return new Result(Map.of("people", people, "count", people.size()), null,
            "read availability for " + people.size() + " person/people");
      }
      case "gym_workouts" -> {
        Map<String, Object> answer = surface.hevyWorkouts(
            optInt(args, "page", 1), optInt(args, "page_size", 10));
        return new Result(answer, null, "read recent workouts");
      }
      case "gym_workout" -> {
        String id = optString(args, "workout_id");
        return new Result(surface.hevyWorkout(id), id, "read workout " + id);
      }
      case "gym_exercises" -> {
        Map<String, Object> answer = surface.hevyExercises(
            optInt(args, "page", 1), optInt(args, "page_size", 100));
        return new Result(answer, null, "listed exercise templates");
      }
      case "gym_exercise_history" -> {
        String id = optString(args, "exercise_template_id");
        return new Result(surface.hevyExerciseHistory(id), id, "read the history of " + id);
      }
      case "gym_exercise_create" -> {
        String title = optString(args, "title");
        return new Result(surface.hevyCreateExercise(args), title, "created the exercise " + title);
      }
      case "gym_routines" -> {
        Map<String, Object> answer = surface.hevyRoutines(
            optInt(args, "page", 1), optInt(args, "page_size", 10));
        return new Result(answer, null, "listed routines");
      }
      case "gym_routine_create" -> {
        String title = optString(args, "title");
        return new Result(surface.hevyCreateRoutine(args), title, "built the routine " + title);
      }
      case "gym_routine_update" -> {
        String id = optString(args, "routine_id");
        return new Result(surface.hevyUpdateRoutine(id, args), id, "replaced routine " + id);
      }
      case "gym_folders" -> {
        Map<String, Object> answer = surface.hevyFolders(optInt(args, "page", 1), 10);
        return new Result(answer, null, "listed routine folders");
      }
      case "gym_folder_create" -> {
        String title = optString(args, "title");
        return new Result(surface.hevyCreateFolder(title), title, "made the folder " + title);
      }
      case "content_list" -> {
        List<Map<String, Object>> pages = surface.listContent(
            optString(args, "folder"), optBoolean(args, "published"));
        return new Result(Map.of("pages", pages, "count", pages.size()),
            null, pages.size() + " page(s)");
      }
      case "content_search" -> {
        String query = optString(args, "query");
        List<Map<String, Object>> hits = surface.searchContent(query);
        return new Result(Map.of("matches", hits, "count", hits.size()),
            query, hits.size() + " match(es) for '" + query + "'");
      }
      case "content_get" -> {
        String uri = optString(args, "uri");
        Map<String, Object> page = surface.getContent(uri);
        if (page == null) {
          // deliberately the same answer whether the page is missing or locked to humans
          throw new AiSurface.Refused("there is no page at '" + uri + "'");
        }
        return new Result(page, uri, "read " + uri);
      }
      case "content_save" -> {
        String uri = optString(args, "uri");
        Map<String, Object> saved = surface.saveContent(uri, args);
        return new Result(saved, uri,
            (Boolean.TRUE.equals(saved.get("created")) ? "created " : "updated ") + uri);
      }
      case "content_meta" -> {
        String uri = optString(args, "uri");
        Map<String, Object> saved = surface.saveContentMeta(uri, args);
        // the log line says which of the two kinds of write this was, because "updated /about"
        // reading the same for a retitle and a rewrite is the thing somebody auditing an agent
        // afterwards most needs told apart
        return new Result(saved, uri, "changed the details of " + uri + ", body untouched");
      }
      case "content_delete" -> {
        String uri = optString(args, "uri");
        return new Result(surface.deleteContent(uri), uri, "deleted " + uri);
      }
      case "site_spec" -> {
        Map<String, Object> spec = surface.siteSpec();
        return new Result(spec, null, "the shape of a page here");
      }
      case "navigation_get" -> {
        return new Result(surface.navigation(), null, "read the navigation");
      }
      case "template_list" -> {
        List<Map<String, Object>> templates = surface.listTemplates();
        return new Result(Map.of("templates", templates, "count", templates.size()),
            null, templates.size() + " template(s)");
      }
      case "template_get" -> {
        String templateName = optString(args, "name");
        Map<String, Object> template = surface.getTemplate(templateName);
        if (template == null) {
          throw new AiSurface.Refused("there is no template called '" + templateName + "'");
        }
        return new Result(template, templateName, "read template " + templateName);
      }
      case "template_save" -> {
        String templateName = optString(args, "name");
        Map<String, Object> saved = surface.saveTemplate(templateName, optString(args, "body"),
            args);
        return new Result(saved, templateName,
            "saved template " + templateName + ", re-rendering " + saved.get("re_rendered") + " page(s)");
      }
      case "template_delete" -> {
        String templateName = optString(args, "name");
        return new Result(surface.deleteTemplate(templateName), templateName,
            "deleted template " + templateName);
      }
      default -> throw new AiSurface.Refused("there is no tool called '" + name + "'");
    }
  }

  // ---- schema helpers ----------------------------------------------------------------------------

  private static ObjectNode schema(ObjectNode... properties) {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    for (ObjectNode property : properties) {
      props.set(property.get("__name").asText(), strip(property));
    }
    return schema;
  }

  private static ObjectNode required(ObjectNode schema, String... names) {
    ArrayNode required = schema.putArray("required");
    for (String name : names) {
      required.add(name);
    }
    return schema;
  }

  private static ObjectNode prop(String name, String type, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", type);
    node.put("description", description);
    if (type.equals("array")) {
      node.putObject("items").put("type", "string");
    }
    return node;
  }

  /**
   * A list of objects, which {@link #prop} cannot express.
   *
   * prop() declares {@code items: string} for every array, which is right for the several tools
   * taking a list of names and wrong for a list of declarations -- and a schema that promises
   * strings while the handler reads objects is a model told to send the one thing that will be
   * refused.
   */
  private static ObjectNode objectArrayProp(String name, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", "array");
    node.put("description", description);
    node.putObject("items").put("type", "object");
    return node;
  }

  /** an array of plain strings, e.g. the other muscle groups an exercise works */
  private static ObjectNode stringArrayProp(String name, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", "array");
    node.put("description", description);
    node.putObject("items").put("type", "string");
    return node;
  }

  /** a free-form object, for the fields a community invented and this code has never heard of */
  private static ObjectNode objectProp(String name, String description) {
    ObjectNode node = JSON.createObjectNode();
    node.put("__name", name);
    node.put("type", "object");
    node.put("description", description);
    node.putObject("additionalProperties").put("type", "string");
    return node;
  }

  private static ObjectNode strip(ObjectNode property) {
    ObjectNode copy = property.deepCopy();
    copy.remove("__name");
    return copy;
  }

  // ---- argument reading --------------------------------------------------------------------------

  private static Map<String, Object> asMap(JsonNode node) {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    if (node == null || !node.isObject()) {
      return map;
    }
    node.fields().forEachRemaining(entry -> map.put(entry.getKey(), unwrap(entry.getValue())));
    return map;
  }

  private static Object unwrap(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isArray()) {
      ArrayList<Object> list = new ArrayList<>();
      node.forEach(item -> list.add(unwrap(item)));
      return list;
    }
    // An object argument used to fall through to asText(), which for a container node is the empty
    // string -- so every nested object a tool declared arrived as "". place_save has advertised a
    // `fields` object since the address book shipped and reads it with an `instanceof Map` that
    // could never be true, which meant a model filling in a kind's own fields was told it had
    // worked and nothing was written. That is the precise failure invariant 132 refuses for an
    // *undeclared* field, arriving through the plumbing instead: silent success for a write that
    // did not happen. Objects are now objects, and ToolArgumentTests holds both halves down.
    if (node.isObject()) {
      LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
      node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), unwrap(entry.getValue())));
      return fields;
    }
    return node.asText();
  }

  /** a list of strings from an argument that may be one, several, or absent */
  private static List<String> strings(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return List.of();
    }
    ArrayList<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
    } else {
      // a model that sent one string where a list was asked for meant one option, and refusing
      // that is a refusal about JSON rather than about the community
      out.add(String.valueOf(value));
    }
    return out;
  }

  private static Double optDouble(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String optString(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static int optInt(Map<String, Object> args, String key, int fallback) {
    Object value = args.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static Boolean optBoolean(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean flag) {
      return flag;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static long requireLong(Map<String, Object> args, String key) throws AiSurface.Refused {
    Object value = args.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (RuntimeException ex) {
      throw new AiSurface.Refused(key + " is required and must be a number");
    }
  }
}
