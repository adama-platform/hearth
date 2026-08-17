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
   * <b>Offered means usable.</b> A tool a connection can never call is invariant 149 in a model's
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
          java.util.Map.entry("content_delete", io.hearth.auth.Permission.content_write),
          java.util.Map.entry("template_list", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("template_get", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("template_save", io.hearth.auth.Permission.templates_write),
          java.util.Map.entry("template_delete", io.hearth.auth.Permission.templates_write),
          java.util.Map.entry("navigation_get", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("site_spec", io.hearth.auth.Permission.content_read),
          java.util.Map.entry("survey_answers", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("survey_summarize", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("survey_ask", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("survey_update", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("survey_restore", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("survey_reorder", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("survey_delete", io.hearth.auth.Permission.survey_write),
          java.util.Map.entry("event_save", io.hearth.auth.Permission.calendar_write),
          java.util.Map.entry("event_delete", io.hearth.auth.Permission.calendar_write),
          java.util.Map.entry("place_save", io.hearth.auth.Permission.places_write),
          java.util.Map.entry("place_delete", io.hearth.auth.Permission.places_write),
          java.util.Map.entry("board_flagged", io.hearth.auth.Permission.board_moderate),
          java.util.Map.entry("board_list", io.hearth.auth.Permission.board_read),
          java.util.Map.entry("board_read", io.hearth.auth.Permission.board_read),
          java.util.Map.entry("board_post", io.hearth.auth.Permission.board_write),
          java.util.Map.entry("board_reply", io.hearth.auth.Permission.board_write),
          java.util.Map.entry("event_context", io.hearth.auth.Permission.board_read),
          java.util.Map.entry("poll_get", io.hearth.auth.Permission.board_read),
          java.util.Map.entry("poll_list", io.hearth.auth.Permission.board_read),
          java.util.Map.entry("poll_create", io.hearth.auth.Permission.board_write),
          java.util.Map.entry("poll_option_add", io.hearth.auth.Permission.board_write),
          java.util.Map.entry("poll_option_remove", io.hearth.auth.Permission.board_write),
          java.util.Map.entry("poll_close", io.hearth.auth.Permission.board_write),
          java.util.Map.entry("poll_vote", io.hearth.auth.Permission.board_vote),
          java.util.Map.entry("task_projects", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_project", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_project_save", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_definitions", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_definition", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_definition_save", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_add", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_remove", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_group", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_record", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_complete", io.hearth.auth.Permission.tasks_use),
          java.util.Map.entry("task_review", io.hearth.auth.Permission.tasks_use));

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
            prop("kind", "string", "markdown, html, page (a whole HTML document), or one of the"
                + " six kinds that are filled in from what the community holds: event_listing,"
                + " event, place_listing, place, member_listing, member. Call site_spec first --"
                + " each of those needs a particular shape of uri and will be refused without it."),
            prop("template", "string", "the template to wrap it in; ignored when kind is page"),
            prop("folder", "string", "navigation folder; empty leaves it out of the navigation"),
            prop("published", "boolean", "unpublished pages are not served")),
            "uri")));

    tools.add(new Tool("content_delete", "Delete a page",
        "Remove a page for good. There is no undo and no version history, so prefer setting"
            + " published to false if there is any chance somebody wants it back.",
        required(schema(prop("uri", "string", "the page's path")), "uri")));

    tools.add(new Tool("place_types", "List the kinds of place",
        "The address book is organised by kind -- a community might keep ranches, vendors and"
            + " venues -- and each kind decides what it records beyond an address. Call this first:"
            + " the fields listed here are the only ones place_save will accept, and a kind you"
            + " have not seen does not exist.",
        schema()));

    tools.add(new Tool("place_list", "List places",
        "Addresses in the book. Filter by kind, or search across names, addresses and the extra"
            + " fields -- searching for 'grass finished' will find ranches that recorded it, even"
            + " though that is a field this community invented. Places an admin has marked human"
            + " only are not listed and cannot be reached.",
        schema(prop("type", "string", "a kind's short name, from place_types"),
            prop("query", "string", "text to look for in names, addresses and fields"))));

    tools.add(new Tool("place_get", "Read one place",
        "Everything recorded about one address, including its description.",
        required(schema(prop("type", "string", "the kind's short name"),
            prop("slug", "string", "the place's short name within that kind")),
            "type", "slug")));

    tools.add(new Tool("place_save", "Write a place",
        "Add an address to the book, or change one. Only what you pass is changed. The extra"
            + " fields must be ones the kind declared -- check place_types first, because inventing"
            + " a field name is refused rather than quietly dropped. New places are unpublished"
            + " until somebody says otherwise.",
        required(schema(
            prop("type", "string", "the kind's short name, which must already exist"),
            prop("slug", "string", "short name for the URL; creates it if there is none"),
            prop("name", "string", "what it is called"),
            prop("address", "string", "street address, or however the place is found"),
            prop("locality", "string", "town or city"),
            prop("region", "string", "state or county"),
            prop("postcode", "string", "postal code"),
            prop("country", "string", "country"),
            prop("url", "string", "website"),
            prop("phone", "string", "telephone"),
            prop("email", "string", "email address"),
            prop("body", "string", "markdown shown on the place's own page"),
            prop("published", "boolean", "unpublished places are not listed"),
            objectProp("fields", "the kind's own fields, by name")),
            "type", "slug")));

    tools.add(new Tool("board_flagged", "What has been flagged",
        "Everything somebody has asked a person to look at, with what they said was wrong with it"
            + " and the words themselves. Nothing here has been hidden or removed -- a flag is a"
            + " request for attention rather than a verdict. You can read it, summarize it and say"
            + " what you would do; the decision belongs to a person, and there is deliberately no"
            + " tool here for taking anything down.",
        schema()));

    tools.add(new Tool("event_context", "Everything said about one event",
        "One event, its guest list, and the whole conversation under it -- with each comment marked"
            + " as before, during or after the day itself. Use it to answer 'what did people"
            + " actually think of the supper club', to pull the questions somebody asked before an"
            + " event out of the noise, or to summarize what came out of it afterwards.",
        required(schema(prop("id", "number", "the event's id, from calendar_list")), "id")));

    tools.add(new Tool("place_delete", "Delete a place",
        "Remove an address for good. There is no undo, so prefer setting published to false if"
            + " there is any chance somebody wants it back.",
        required(schema(prop("type", "string", "the kind's short name"),
            prop("slug", "string", "the place's short name")), "type", "slug")));

    tools.add(new Tool("event_list", "List events",
        "What is on the community's calendar. Use this before creating anything, so you do not put"
            + " a second supper club on a night that already has one.",
        schema(prop("include_past", "boolean", "include events that have already happened"))));
    tools.add(new Tool("event_get", "Read one event",
        "One event with its details and who said they are coming.",
        required(schema(prop("id", "integer", "the event's id")), "id")));
    tools.add(new Tool("event_save", "Create or change an event",
        "Put something on the calendar, or change something already on it. Omit `id` to create."
            + " `starts_on` is required and must be a real date as YYYY-MM-DD -- people turn up on"
            + " the day it says, so it is never guessed at. `place_slug` links it to an entry in"
            + " the address book; `location` is free text beside it, for a room or a garden."
            + " Nothing is visible to members until `published` is true.",
        schema(prop("id", "integer", "leave out to create a new event"),
            prop("title", "string", "what it is"),
            prop("starts_on", "string", "the first day, YYYY-MM-DD"),
            prop("ends_on", "string", "the last day; the same as starts_on for a one-day event"),
            prop("start_time", "string", "as a person would say it: 'doors at 7'"),
            prop("location", "string", "free text, shown beside the place"),
            prop("place_slug", "string", "an address book entry's slug"),
            prop("capacity", "integer", "how many can come; leave out for no limit"),
            prop("body", "string", "the details, in markdown"),
            prop("published", "boolean", "whether members can see it and answer"))));
    tools.add(new Tool("event_delete", "Delete an event",
        "Deletes it and everybody's answers. Prefer cancelling in the admin section: the people who"
            + " said they were coming are exactly the people who need to see it is not happening.",
        required(schema(prop("id", "integer", "the event's id")), "id")));

    tools.add(new Tool("board_list", "List conversations",
        "What is being discussed on the board, most recent activity first.",
        schema(prop("limit", "integer", "how many; 50 by default"))));
    tools.add(new Tool("board_read", "Read a conversation",
        "One conversation with its replies, in reading order.",
        required(schema(prop("id", "integer", "the post's id")), "id")));
    tools.add(new Tool("board_post", "Start or edit a conversation",
        "Post to the board, or edit a post. Omit `id` to start something new. This speaks in the"
            + " community's own space, so write what a member would write -- and it is attributed"
            + " to the administrator whose connection this is, not to a robot.",
        schema(prop("id", "integer", "leave out to start something new"),
            prop("title", "string", "what it is about"),
            prop("body", "string", "what to say, in markdown"))));
    tools.add(new Tool("board_reply", "Reply to a conversation",
        "Add a reply to a conversation.",
        required(schema(prop("id", "integer", "the post's id"),
            prop("body", "string", "what to say")), "id", "body")));

    // ---- polls -----------------------------------------------------------------------------
    //
    // These descriptions are longer than the others on purpose. A model has no screen: it cannot
    // see that days and places count differently, that a down vote means nothing on an either-or,
    // or that a schedule poll ends by putting something in everybody's calendar. Every one of
    // those is a rule it would otherwise have to discover by being refused.
    tools.add(new Tool("poll_create", "Put a vote in a conversation",
        "Ask the group to decide something, inside a conversation on the board.\n\n"
            + "TWO KINDS.\n"
            + "'choice' is a straight either-or: one vote each, most votes wins. Anybody may start"
            + " one.\n"
            + "'schedule' asks which day and which place together, and when it closes it becomes an"
            + " event in the calendar by itself. It needs permission to create events; if the"
            + " person this connection belongs to does not have that, use 'choice' and let somebody"
            + " put the event up by hand.\n\n"
            + "A schedule poll must say when it closes -- one that never closes never becomes"
            + " anything. Add the days and the places afterwards with poll_option_add.",
        required(schema(prop("post", "integer", "the conversation this belongs in"),
            prop("kind", "string", "'choice' or 'schedule'"),
            prop("question", "string", "what is being decided, in one line"),
            prop("closes_at", "string", "YYYY-MM-DD or YYYY-MM-DDTHH:MM in the community's own"
                + " timezone; required for a schedule poll"),
            prop("open_options", "boolean", "may anybody add options, or only you? true by"
                + " default, which is usually right -- somebody else knows a hall you do not"),
            prop("choices", "array", "for a 'choice' poll: the options, as text")),
            "post", "kind", "question")));
    tools.add(new Tool("poll_get", "Read a vote",
        "One poll: what is on the table, how the voting has gone, and what has won. Read this"
            + " before voting, so you know what has already been put forward.",
        required(schema(prop("id", "integer", "the poll's id")), "id")));
    tools.add(new Tool("poll_list", "Every vote in a conversation",
        "The polls attached to one conversation.",
        required(schema(prop("post", "integer", "the post's id")), "post")));
    tools.add(new Tool("poll_option_add", "Put something on the table",
        "Add one thing to vote on.\n\n"
            + "For a 'choice' poll: facet 'choice' and a label.\n"
            + "For a 'schedule' poll: facet 'time' with a day as YYYY-MM-DD (and optionally `at`,"
            + " free text shown as written -- 'from 7', 'afternoon'); or facet 'place' with the id"
            + " of a place from the address book. A place is never free text, because the winner"
            + " becomes the event's location and nobody should have to retype an address.\n\n"
            + "Suggest days people have not thought of. A poll with one evening on it measures"
            + " whether the person who set it up guessed well.",
        required(schema(prop("poll", "integer", "the poll's id"),
            prop("facet", "string", "'choice', 'time' or 'place'"),
            prop("label", "string", "what to call it; not needed for a place"),
            prop("day", "string", "for a time: the day, YYYY-MM-DD"),
            prop("at", "string", "for a time: the time of day, in words"),
            prop("place", "integer", "for a place: the id from the address book")),
            "poll", "facet")));
    tools.add(new Tool("poll_option_remove", "Take something off the table",
        "Remove an option. You may remove one you added, or any of them on a poll you set up;"
            + " anything else needs permission to moderate the board. Votes already cast for it are"
            + " kept, so removing one cannot silently change what the others are a share of.",
        required(schema(prop("id", "integer", "the option's id")), "id")));
    tools.add(new Tool("poll_vote", "Vote",
        "Say what you think of one option, on behalf of the person this connection belongs to.\n\n"
            + "DAYS take an up, a down, or nothing, each independently: somebody free on three"
            + " evenings should say so about all three, and a down means 'I cannot come then',"
            + " which counts against it. That is what produces a histogram rather than one peak.\n\n"
            + "CHOICES AND PLACES are either-or: one vote, and voting for a different one moves it."
            + " There is no voting against -- a down vote on one of these is read as taking your"
            + " vote back.\n\n"
            + "Voting the way you already voted takes it back.\n\n"
            + "Vote what the person you are acting for actually thinks. If you do not know, ask"
            + " them rather than guessing -- a vote is the one thing here that is meant to be"
            + " somebody's own.",
        required(schema(prop("option", "integer", "the option's id"),
            prop("weight", "integer", "1 for yes, -1 for 'not that one'; 1 by default")),
            "option")));
    tools.add(new Tool("poll_close", "Count it now",
        "Close a poll before its time and count it. For the person who asked the question, or a"
            + " moderator. A schedule poll that has a winning day becomes an event here and then."
            + " If either half is tied, nothing is created and it says which -- that is not a"
            + " failure, it is the group not having decided, and the fix is another day on the"
            + " table or two more votes.",
        required(schema(prop("id", "integer", "the poll's id")), "id")));

    // ---- tasks, routines and what was recorded ----------------------------------------------
    //
    // These are the person's own, always. There is no argument anywhere here for whose -- a
    // training log is the most private thing this server holds, and a tool with a "user" parameter
    // would be one prompt away from reading somebody else's.
    tools.add(new Tool("task_projects", "The projects you keep",
        "Every project this person can open: their own, and the community's. A project is a list, a"
            + " routine or a board, and it decides what it calls its own items -- read"
            + " `calls_one`/`calls_many` and use those words back.",
        schema()));
    tools.add(new Tool("task_project", "One project and what is on it",
        "Everything currently on one project, with what each item is measured in.",
        required(schema(prop("id", "integer", "the project's id")), "id")));
    tools.add(new Tool("task_project_save", "Make or change a project",
        "Start a project, or change its settings. `phases` turns it into a board -- give the"
            + " columns in order, and reaching the last one counts as finished; leave it out for a"
            + " plain list. `hide_done_hours` is how long a finished item stays on screen; nothing"
            + " is ever deleted by it, and the history is kept whatever it says.",
        required(schema(prop("id", "integer", "leave out to start a new one"),
            prop("name", "string", "what it is called"),
            prop("summary", "string", "one line about it"),
            prop("calls_one", "string", "what one item is: 'exercise', 'chore', 'step'"),
            prop("calls_many", "string", "and several of them"),
            prop("phases", "array", "the columns of a board, in order; omit for a plain list"),
            prop("hide_done_hours", "integer", "how long a finished item stays visible; 24 by"
                + " default")), "name")));
    tools.add(new Tool("task_definitions", "What things are",
        "The library: every definition this person can use, their own and the community's shared"
            + " ones. A definition is the durable thing -- what a movement is, how it is performed,"
            + " what it is measured in -- and a task is one occasion of doing it. History follows"
            + " the definition, so read this before writing a new one; a second 'Bench press'"
            + " splits somebody's history in two.",
        schema()));
    tools.add(new Tool("task_definition", "Read one definition",
        "One definition with its instructions, and how it has actually gone for this person.",
        required(schema(prop("id", "integer", "the definition's id")), "id")));
    tools.add(new Tool("task_definition_save", "Write down what a thing is",
        "Create or change a definition.\n\n"
            + "MEASURED IN is one of: none (just done or not); weight_reps; bodyweight_reps;"
            + " weighted_bodyweight (the weight is signed -- +10 is added, -20 is assistance, and"
            + " it is one axis because somebody's first unassisted rep is where it crosses zero);"
            + " duration; duration_weight; distance_duration; weight_distance.\n\n"
            + "WRITE THE INSTRUCTIONS PROPERLY. That field is the whole reason this is separate"
            + " from a task: it is read on a phone, mid-set, by somebody who has forgotten whether"
            + " the elbows go forward. Say how it is set up, how it is performed, what to watch"
            + " for, and what a common mistake looks like. A reference_url is where the form came"
            + " from.\n\n"
            + "Sharing one puts it in the community's library for anybody to take a copy of, and"
            + " needs permission to keep that library.",
        required(schema(prop("id", "integer", "leave out to write a new one"),
            prop("name", "string", "what it is called"),
            prop("measured_in", "string", "one of the measures above"),
            prop("summary", "string", "one line"),
            prop("instructions", "string", "how it is done, in markdown"),
            prop("reference_url", "string", "where the form came from"),
            prop("tags", "string", "comma separated: 'legs, pull' or 'admin, before the party'"),
            prop("sets", "integer", "how many sets are usual"),
            prop("reps", "integer", "how many reps are usual"),
            prop("weight", "number", "a usual starting weight"),
            prop("rest_seconds", "integer", "how long to rest between sets of this; 0 for no"
                + " particular rest. It belongs on the definition because it is a property of the"
                + " movement -- a heavy squat wants three minutes and a set of curls wants forty"
                + " seconds, in every routine it ever appears in."),
            prop("share", "boolean", "put it in the community's library")), "name")));
    tools.add(new Tool("task_add", "Put something on a project",
        "Add an item. Give a `definition` to bring its instructions, its measurements and its"
            + " history with it -- that is nearly always what you want for a routine. A one-off"
            + " with just a title is fine for a chore nobody will do twice."
            + " `repeat_days` makes it come back: ticking it moves it forward rather than closing"
            + " it, which is what makes a routine a routine.",
        required(schema(prop("project", "integer", "which project"),
            prop("definition", "integer", "what it is, from the library"),
            prop("title", "string", "or a title, for a one-off"),
            prop("notes", "string", "anything specific to this one"),
            prop("repeat_days", "integer", "comes back after this many days; 0 for a one-off"),
            prop("due", "string", "YYYY-MM-DD")), "project")));
    tools.add(new Tool("task_group", "Do several things together",
        "Put items into a group, or take one out of one. Everything on a project sharing a group"
            + " name is one group.\n\n"
            + "'related' is a SUPERSET: alternate between them, and the rest belongs after the"
            + " round rather than after each set -- which is the whole reason people superset, and"
            + " getting it the other way round turns a time-saving device into one that takes"
            + " longer. The item screen says so.\n\n"
            + "'sequenced' is a CIRCUIT or a progression: the order is the point, so doing the"
            + " third one first is doing something else. The order is the order they were added.\n\n"
            + "Send an empty `name` to take something out of its group.",
        required(schema(prop("id", "integer", "the task's id"),
            prop("name", "string", "what to call the group; empty to leave one"),
            prop("mode", "string", "'related' or 'sequenced'")), "id")));
    tools.add(new Tool("task_remove", "Take something off a project",
        "Remove an item. What was recorded against it is kept -- a history outlives the list it"
            + " was on.",
        required(schema(prop("id", "integer", "the task's id")), "id")));
    tools.add(new Tool("task_record", "Record a set",
        "Write down one set of something, with the time it happened.\n\n"
            + "Only send the fields the definition's measure asks for; the rest are ignored."
            + " weight is in kg and may be negative for assisted bodyweight work; seconds is a"
            + " count of seconds; distance is in metres.\n\n"
            + "THE THREE RATINGS ARE SOMEBODY'S OWN JUDGEMENT. difficulty, time_cost and impact are"
            + " each one to five. Ask before filling them in -- do not infer them from the weight."
            + " Leaving one out records that nobody said, which is a different fact from a middling"
            + " score, and the whole point of them is to find the thing that is exhausting and"
            + " useless.",
        required(schema(prop("task", "integer", "which item"),
            prop("weight", "number", "kg; negative means assistance"),
            prop("reps", "integer", "how many"),
            prop("seconds", "integer", "how long"),
            prop("distance", "number", "metres"),
            prop("difficulty", "integer", "1 easy to 5 brutal, if they said"),
            prop("time_cost", "integer", "1 quick to 5 ages, if they said"),
            prop("impact", "integer", "1 pointless to 5 exactly what was needed, if they said"),
            prop("note", "string", "anything worth remembering")), "task")));
    tools.add(new Tool("task_complete", "Tick something off",
        "Mark an item done. A repeating one moves to its next date instead of closing. The ratings"
            + " work exactly as they do for a set, and are worth asking for on a chore too --"
            + " 'that took an hour and achieved nothing' is the most useful thing anybody records.",
        required(schema(prop("id", "integer", "the task's id"),
            prop("difficulty", "integer", "1 to 5, if they said"),
            prop("time_cost", "integer", "1 to 5, if they said"),
            prop("impact", "integer", "1 to 5, if they said"),
            prop("note", "string", "anything worth remembering")), "id")));
    tools.add(new Tool("task_review", "How the routine is actually going",
        "Every definition this person has recorded anything against, with what it cost and what it"
            + " gave, sorted by impact for time. This is the tool for 'is my routine working' --"
            + " read it before suggesting a change, and suggest rather than change. The numbers are"
            + " small integers over a handful of occasions: enough to notice a pattern, nowhere"
            + " near enough to rank a routine, and a definition with no ratings means nobody has"
            + " said rather than that it is average.",
        schema()));

    tools.add(new Tool("site_spec", "How to build a site here",
        "Everything you need to write pages that work: every kind of page, what address each one"
            + " requires, what its body is given to render with, and the settings a listing takes."
            + " Read this before writing anything other than a plain markdown page -- a feed page"
            + " with the wrong shape of uri is refused, and the rule is different for each kind.",
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
        "One template's mustache source, plus the uris that depend on it.",
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
            + " prevUrl/nextUrl/firstUrl/lastUrl and {{#numbers}}.",
        required(schema(
            prop("name", "string", "letters, digits, underscore or hyphen"),
            prop("body", "string", "the mustache template source"),
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

    tools.add(new Tool("survey_list", "List survey questions",
        "Every question the community is being asked, with how many people have answered each."
            + " Read this before asking a new one so you are not asking something already asked.",
        schema()));

    tools.add(new Tool("survey_answers", "Every answer to one question",
        "All of the answers to a single question, as they were written, with each respondent given"
            + " a stable number rather than a name. Use this when you are working through the"
            + " survey a question at a time -- list the questions, take one, read everything people"
            + " said about it, and say what it amounts to. It is the right tool for that job and"
            + " summarize is the right one for 'what does the community think overall'.",
        required(schema(prop("id", "number", "the question's id, from survey_list")), "id")));

    tools.add(new Tool("survey_summarize", "Summarize the survey",
        "Every answer to every question, aggregated: free text verbatim, choices and ratings"
            + " tallied. This is the tool to use when somebody asks what the community thinks."
            + " Respondents are numbered rather than named.",
        schema(prop("include_text", "boolean",
            "include free-text answers verbatim; default true. Set false for counts only."))));

    tools.add(new Tool("survey_ask", "Ask a question",
        "Add a question to the survey. Everybody in the community will see it as unanswered, so"
            + " ask things worth interrupting people for, and prefer few good questions to many"
            + " thin ones.",
        required(schema(
            prop("prompt", "string", "what you are asking"),
            prop("kind", "string", "free (a text box), choice (a dropdown), or rating (a number)"),
            prop("help", "string", "a hint shown under the question"),
            prop("options", "array", "for a choice question, the options to pick from"),
            prop("min", "integer", "for a rating, the lowest value; default 1"),
            prop("max", "integer", "for a rating, the highest value; default 5"),
            prop("required", "boolean", "whether everybody has to answer it"),
            prop("published", "boolean", "false to write it without asking anybody yet"),
            prop("position", "integer", "sort order among the questions")),
            "prompt")));

    tools.add(new Tool("survey_update", "Change a question",
        "Reword or reconfigure a question. Answers already given are kept and stay attached --"
            + " rewording is safe. Changing the kind is not: existing answers were written for the"
            + " old shape.",
        required(schema(
            prop("id", "integer", "the question's id, from survey_list"),
            prop("prompt", "string", "the new wording"),
            prop("help", "string", "the new hint"),
            prop("kind", "string", "free, choice, or rating"),
            prop("options", "array", "for a choice question"),
            prop("required", "boolean", "whether everybody has to answer it"),
            prop("published", "boolean", "false stops it being asked without deleting it"),
            prop("position", "integer", "sort order")),
            "id")));

    tools.add(new Tool("survey_restore", "Ask a retired question again",
        "Put a retired question back into the survey. Its answers were never deleted, so they"
            + " start counting again exactly as they were.",
        required(schema(prop("id", "integer", "the question's id")), "id")));

    tools.add(new Tool("survey_reorder", "Put the questions in an order",
        "The order people are asked in, as a list of ids from first to last. Anything you leave"
            + " out keeps its place after the ones you named. Order matters more than it looks:"
            + " people answer three at a time, so what you put first is what most people answer.",
        required(schema(prop("ids", "array", "question ids, in the order to ask them")), "ids")));

    tools.add(new Tool("survey_delete", "Stop asking a question",
        "Retire a question. It stops being asked and stops counting immediately, and the answers"
            + " people already gave are kept until an admin commits the cleanup by hand. If you"
            + " only want to pause it, set published to false with survey_update instead.",
        required(schema(prop("id", "integer", "the question's id")), "id")));

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
      case "content_list" -> {
        List<Map<String, Object>> pages = surface.listContent(
            optString(args, "folder"), optBoolean(args, "published"));
        return new Result(Map.of("pages", pages, "count", pages.size()),
            null, pages.size() + " page(s)");
      }
      case "event_list" -> {
        List<Map<String, Object>> events =
            surface.listEvents(Boolean.TRUE.equals(optBoolean(args, "include_past")));
        return new Result(Map.of("events", events, "count", events.size()),
            null, events.size() + " event(s)");
      }
      case "event_get" -> {
        Map<String, Object> event = surface.getEvent(requireLong(args, "id"));
        if (event == null) {
          throw new AiSurface.Refused("There is no event with that id.");
        }
        return new Result(event, String.valueOf(event.get("title")), "read " + event.get("title"));
      }
      case "event_save" -> {
        Long id = args.containsKey("id") ? requireLong(args, "id") : null;
        Map<String, Object> saved = surface.saveEvent(id, args);
        return new Result(saved, String.valueOf(saved.get("title")),
            (Boolean.TRUE.equals(saved.get("created")) ? "created " : "updated ")
                + saved.get("title"));
      }
      case "event_delete" -> {
        Map<String, Object> gone = surface.deleteEvent(requireLong(args, "id"));
        return new Result(gone, String.valueOf(gone.get("title")), "deleted " + gone.get("title"));
      }
      case "board_list" -> {
        List<Map<String, Object>> posts = surface.listPosts(optInt(args, "limit", 50));
        return new Result(Map.of("posts", posts, "count", posts.size()),
            null, posts.size() + " conversation(s)");
      }
      case "board_read" -> {
        Map<String, Object> post = surface.readPost(requireLong(args, "id"));
        if (post == null) {
          throw new AiSurface.Refused("There is no such conversation.");
        }
        return new Result(post, String.valueOf(post.get("title")), "read " + post.get("title"));
      }
      case "board_post" -> {
        Long id = args.containsKey("id") ? requireLong(args, "id") : null;
        Map<String, Object> saved = surface.savePost(id, optString(args, "title"),
            optString(args, "body"));
        return new Result(saved, String.valueOf(saved.get("title")),
            (Boolean.TRUE.equals(saved.get("created")) ? "posted " : "edited ")
                + saved.get("title"));
      }
      case "poll_create" -> {
        Map<String, Object> made = surface.createPoll(requireLong(args, "post"),
            optString(args, "kind"), optString(args, "question"), optString(args, "closes_at"),
            optBoolean(args, "open_options"), strings(args, "choices"));
        return new Result(made, String.valueOf(made.get("question")),
            "asked \"" + made.get("question") + "\"");
      }
      case "poll_get" -> {
        Map<String, Object> poll = surface.readPoll(requireLong(args, "id"));
        return new Result(poll, String.valueOf(poll.get("question")), "read the vote");
      }
      case "poll_list" -> {
        List<Map<String, Object>> polls = surface.pollsFor(requireLong(args, "post"));
        return new Result(Map.of("polls", polls, "count", polls.size()),
            null, polls.size() + " vote(s)");
      }
      case "poll_option_add" -> {
        Map<String, Object> added = surface.addPollOption(requireLong(args, "poll"),
            optString(args, "facet"), optString(args, "label"), optString(args, "day"),
            optString(args, "at"), args.containsKey("place") ? requireLong(args, "place") : null);
        return new Result(added, String.valueOf(added.get("what")),
            "put \"" + added.get("what") + "\" on the table");
      }
      case "poll_option_remove" -> {
        Map<String, Object> gone = surface.removePollOption(requireLong(args, "id"));
        return new Result(gone, null, "took an option off the table");
      }
      case "poll_vote" -> {
        Map<String, Object> voted = surface.votePoll(requireLong(args, "option"),
            optInt(args, "weight", 1));
        return new Result(voted, null, "voted " + voted.get("your_vote"));
      }
      case "poll_close" -> {
        Map<String, Object> closed = surface.closePoll(requireLong(args, "id"));
        return new Result(closed, String.valueOf(closed.get("question")),
            "counted it: " + closed.get("outcome"));
      }
      case "task_projects" -> {
        List<Map<String, Object>> projects = surface.listProjects();
        return new Result(Map.of("projects", projects, "count", projects.size()),
            null, projects.size() + " project(s)");
      }
      case "task_project" -> {
        Map<String, Object> project = surface.readProject(requireLong(args, "id"));
        return new Result(project, String.valueOf(project.get("name")),
            "read " + project.get("name"));
      }
      case "task_project_save" -> {
        Map<String, Object> saved = surface.saveProject(
            args.containsKey("id") ? requireLong(args, "id") : null, optString(args, "name"),
            optString(args, "summary"), optString(args, "calls_one"),
            optString(args, "calls_many"), strings(args, "phases"),
            args.containsKey("hide_done_hours") ? optInt(args, "hide_done_hours", 24) : null);
        return new Result(saved, String.valueOf(saved.get("name")),
            (Boolean.TRUE.equals(saved.get("created")) ? "started " : "changed ")
                + saved.get("name"));
      }
      case "task_definitions" -> {
        List<Map<String, Object>> defs = surface.listDefinitions();
        return new Result(Map.of("definitions", defs, "count", defs.size()),
            null, defs.size() + " definition(s)");
      }
      case "task_definition" -> {
        Map<String, Object> def = surface.readDefinition(requireLong(args, "id"));
        return new Result(def, String.valueOf(def.get("name")), "read " + def.get("name"));
      }
      case "task_definition_save" -> {
        Map<String, Object> saved = surface.saveDefinition(
            args.containsKey("id") ? requireLong(args, "id") : null, optString(args, "name"),
            optString(args, "measured_in"), optString(args, "summary"),
            optString(args, "instructions"), optString(args, "reference_url"),
            optString(args, "tags"),
            args.containsKey("sets") ? optInt(args, "sets", 3) : null,
            args.containsKey("reps") ? optInt(args, "reps", 8) : null,
            optDouble(args, "weight"),
            args.containsKey("rest_seconds") ? optInt(args, "rest_seconds", 0) : null,
            optBoolean(args, "share"));
        return new Result(saved, String.valueOf(saved.get("name")),
            (Boolean.TRUE.equals(saved.get("created")) ? "wrote down " : "changed ")
                + saved.get("name"));
      }
      case "task_add" -> {
        Map<String, Object> added = surface.addTask(requireLong(args, "project"),
            args.containsKey("definition") ? requireLong(args, "definition") : null,
            optString(args, "title"), optString(args, "notes"),
            args.containsKey("repeat_days") ? optInt(args, "repeat_days", 0) : null,
            optString(args, "due"));
        return new Result(added, String.valueOf(added.get("title")),
            "added " + added.get("title"));
      }
      case "task_group" -> {
        Map<String, Object> grouped = surface.groupTask(requireLong(args, "id"),
            optString(args, "name"), optString(args, "mode"));
        return new Result(grouped, null, String.valueOf(grouped.get("said")));
      }
      case "task_remove" -> {
        Map<String, Object> gone = surface.removeTask(requireLong(args, "id"));
        return new Result(gone, null, "removed it from the project");
      }
      case "task_record" -> {
        Map<String, Object> recorded = surface.recordEntry(requireLong(args, "task"),
            optDouble(args, "weight"),
            args.containsKey("reps") ? optInt(args, "reps", 0) : null,
            args.containsKey("seconds") ? optInt(args, "seconds", 0) : null,
            optDouble(args, "distance"),
            args.containsKey("difficulty") ? optInt(args, "difficulty", 0) : null,
            args.containsKey("time_cost") ? optInt(args, "time_cost", 0) : null,
            args.containsKey("impact") ? optInt(args, "impact", 0) : null,
            optString(args, "note"));
        return new Result(recorded, null, "recorded " + recorded.get("recorded"));
      }
      case "task_complete" -> {
        Map<String, Object> done = surface.completeTask(requireLong(args, "id"),
            args.containsKey("difficulty") ? optInt(args, "difficulty", 0) : null,
            args.containsKey("time_cost") ? optInt(args, "time_cost", 0) : null,
            args.containsKey("impact") ? optInt(args, "impact", 0) : null,
            optString(args, "note"));
        return new Result(done, null, "ticked it off");
      }
      case "task_review" -> {
        Map<String, Object> review = surface.reviewRoutine();
        return new Result(review, null, "reviewed " + review.get("count") + " definition(s)");
      }
      case "board_reply" -> {
        Map<String, Object> made = surface.comment(requireLong(args, "id"),
            optString(args, "body"));
        return new Result(made, null, "replied");
      }
      case "place_types" -> {
        List<Map<String, Object>> types = surface.listPlaceTypes();
        return new Result(Map.of("types", types, "count", types.size()),
            null, types.size() + " kind(s) of place");
      }
      case "place_list" -> {
        List<Map<String, Object>> places =
            surface.listPlaces(optString(args, "type"), optString(args, "query"));
        return new Result(Map.of("places", places, "count", places.size()),
            optString(args, "type"), places.size() + " place(s)");
      }
      case "place_get" -> {
        Map<String, Object> place = surface.getPlace(optString(args, "type"),
            optString(args, "slug"));
        if (place == null) {
          throw new AiSurface.Refused("There is no such place, or it is human only.");
        }
        return new Result(place, optString(args, "slug"), "read " + place.get("name"));
      }
      case "place_save" -> {
        Map<String, Object> saved = surface.savePlace(optString(args, "type"),
            optString(args, "slug"), args);
        return new Result(saved, optString(args, "slug"),
            (Boolean.TRUE.equals(saved.get("created")) ? "created " : "updated ")
                + saved.get("name"));
      }
      case "place_delete" -> {
        Map<String, Object> gone = surface.deletePlace(optString(args, "type"),
            optString(args, "slug"));
        return new Result(gone, optString(args, "slug"), "deleted " + gone.get("name"));
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
      case "board_flagged" -> {
        List<Map<String, Object>> flagged = surface.flagged();
        return new Result(Map.of("flagged", flagged, "count", flagged.size()), null,
            flagged.size() + " thing(s) waiting for a person to look at");
      }
      case "event_context" -> {
        long id = requireLong(args, "id");
        Map<String, Object> context = surface.eventContext(id);
        if (context == null) {
          throw new AiSurface.Refused("there is no event with id " + id);
        }
        return new Result(context, Long.toString(id), "context for " + context.get("title"));
      }
      case "survey_list" -> {
        List<Map<String, Object>> questions = surface.listQuestions();
        return new Result(Map.of("questions", questions, "count", questions.size()),
            null, questions.size() + " question(s)");
      }
      case "survey_answers" -> {
        long id = requireLong(args, "id");
        Map<String, Object> answers = surface.answersTo(id);
        return new Result(answers, Long.toString(id),
            answers.get("answered") + " answer(s) to \"" + answers.get("prompt") + "\"");
      }
      case "survey_summarize" -> {
        boolean includeText = !args.containsKey("include_text")
            || Boolean.TRUE.equals(optBoolean(args, "include_text"));
        Map<String, Object> summary = surface.summarizeSurvey(includeText);
        return new Result(summary, null,
            "summarized " + summary.get("respondents") + " respondent(s)");
      }
      case "survey_ask" -> {
        Map<String, Object> asked = surface.askQuestion(args);
        return new Result(asked, String.valueOf(asked.get("id")),
            "asked: " + asked.get("prompt"));
      }
      case "survey_update" -> {
        long id = requireLong(args, "id");
        return new Result(surface.updateQuestion(id, args), Long.toString(id),
            "updated question " + id);
      }
      case "survey_delete" -> {
        long id = requireLong(args, "id");
        return new Result(surface.deleteQuestion(id), Long.toString(id),
            "retired question " + id);
      }
      case "survey_restore" -> {
        long id = requireLong(args, "id");
        return new Result(surface.restoreQuestion(id), Long.toString(id),
            "asking question " + id + " again");
      }
      case "survey_reorder" -> {
        List<Long> order = new java.util.ArrayList<>();
        Object raw = args.get("ids");
        if (raw instanceof List<?> list) {
          for (Object one : list) {
            order.add(Long.parseLong(String.valueOf(one).trim()));
          }
        }
        return new Result(surface.reorderQuestions(order), null,
            "reordered " + order.size() + " question(s)");
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
