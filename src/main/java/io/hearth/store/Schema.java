package io.hearth.store;

import java.util.List;

/**
 * The schema, in code. This file is the source of truth; the database on disk is a cache of it.
 *
 * Two tables today.
 *
 * emails -- one row per person who can sign in. password_hash is nullable on purpose: passwordless
 * is the intended default, so an account that has only ever logged in by emailed code has no
 * password to steal. The rest of the columns exist so that the things a login system has to do --
 * lock out a brute forcer, invalidate sessions when a password changes, disable an account, know
 * whether an address was ever proven -- are answerable without adding a table later.
 *
 * sessions -- one row per live login. What is stored is the SHA-256 of the token, never the token,
 * so a database dump does not hand out logins. expires_at NULL means the session does not expire on
 * its own, which is a reasonable thing for a high trust community to want; the active-session cap
 * and the reaper are what keep that from being unbounded.
 *
 * To add a column: put it where it belongs in the list and bump VERSION. The upgrader will insert
 * it in that position on existing databases. See {@link SchemaUpgrader}.
 *
 * One rule when you do. A column added in a later version must be nullable or carry a default,
 * because there is no correct value to put in the rows that already exist. The NOT NULL columns
 * below that have no default -- email, token_hash, user_id -- are founding columns of their tables
 * and only ever appear inside CREATE TABLE. Declare a new one like that and the upgrader will refuse
 * to start with a message naming the column, rather than letting the database do it in SQL.
 */
public class Schema {
  /** bumped whenever the tables below change; recorded in schema_meta for the boot audit */
  public static final int VERSION = 50;

  public static final String EMAILS = "emails";
  public static final String SESSIONS = "sessions";
  public static final String ROLES = "roles";
  public static final String CONTENT = "content";
  public static final String TEMPLATES = "templates";
  public static final String MUTATIONS = "mutations";
  public static final String USER_KEYS = "user_keys";
  public static final String VOTES = "votes";
  public static final String AVAILABILITY = "availability";
  public static final String TASKS = "tasks";
  public static final String PROCESSES = "processes";
  public static final String HABIT_MARKS = "habit_marks";
  public static final String CALENDARS = "calendars";
  public static final String PROFILES = "profiles";
  public static final String BANS = "bans";
  public static final String OAUTH_CLIENTS = "oauth_clients";
  public static final String CONTENT_VERSIONS = "content_versions";
  public static final String ROLE_DEFS = "role_defs";
  public static final String PUSH_SUBS = "push_subs";
  public static final String THEMES = "themes";
  public static final String LEGAL = "legal";
  public static final String SYSTEM_TEMPLATES = "system_templates";
  public static final String ATTACHMENTS = "attachments";
  public static final String CONFIG = "config";
  public static final String REWRITES = "rewrites";
  public static final String MAILBOXES = "mailboxes";
  public static final String MAIL_RULES = "mail_rules";
  public static final String MAIL_LOG = "mail_log";
  public static final String MAIL_MESSAGES = "mail_messages";
  public static final String CALENDAR_EVENTS = "calendar_events";
  public static final String CALENDAR_FEEDS = "calendar_feeds";
  public static final String META = "schema_meta";

  public static final Table EMAILS_TABLE = Table.named(EMAILS)
      .column(Column.id("id"))
      // stored lowercased and trimmed; the unique constraint is the one that matters
      .column(Column.of("email", "VARCHAR(320)").notNull().unique())
      // null means this account has no password and can only sign in by emailed code
      .column(Column.of("password_hash", "VARCHAR(512)"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      // proof the address is reachable; set when a registration or login code is redeemed
      .column(Column.of("verified_at", "TIMESTAMP"))
      .column(Column.of("last_login_at", "TIMESTAMP"))
      // nobody gets in until an admin says so; null means still waiting
      .column(Column.of("approved_at", "TIMESTAMP"))
      .column(Column.of("approved_by", "BIGINT"))
      // what the browser did while the signup form was open. Kept, not just checked: a wave of
      // accounts that all scored the bare minimum is a pattern you can only see afterwards.
      .column(Column.of("signup_events", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("signup_signals", "VARCHAR(160)"))
      .column(Column.of("signup_ip", "VARCHAR(64)"))
      // every session issued before this instant is dead, which is how a password change or a
      // "sign me out everywhere" works without hunting down rows
      .column(Column.of("sessions_valid_after", "TIMESTAMP"))
      .column(Column.of("failed_attempts", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("locked_until", "TIMESTAMP"))
      // an operator kill switch for a single account
      .column(Column.of("disabled", "BOOLEAN").notNull().withDefault("FALSE"))
      // When a push last went to this person, and when they last did something about it.
      //
      // Two columns rather than a table, because the question is "how long does a notification
      // take to work" and the answer only needs the last one -- a log of every push would be among
      // the busiest writes in the server to answer a question a histogram of the most recent
      // answers already answers. Written from memory every few minutes; see PushLedger.
      .column(Column.of("last_push_at", "TIMESTAMP"))
      .column(Column.of("last_push_acted_at", "TIMESTAMP"))
      .index("idx_emails_email", "email")
      .build();

  public static final Table SESSIONS_TABLE = Table.named(SESSIONS)
      .column(Column.id("id"))
      // SHA-256 of the token, hex. The token itself exists only in the cookie and in memory.
      .column(Column.of("token_hash", "VARCHAR(64)").notNull().unique())
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("last_seen_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      // null means no fixed lifetime; the cap and the reaper bound it instead
      .column(Column.of("expires_at", "TIMESTAMP"))
      .column(Column.of("revoked_at", "TIMESTAMP"))
      // recorded so a person can look at their own session list and recognize what is theirs
      .column(Column.of("ip", "VARCHAR(64)"))
      .column(Column.of("user_agent", "VARCHAR(256)"))
      // An agent acts as the person who authorized it, so it is the same user_id -- the bit is what
      // keeps "who did this" answerable afterwards. Without it an audit cannot tell a person from
      // the model they connected, which is the one question anybody will actually ask.
      .column(Column.of("robot", "BOOLEAN").notNull().withDefault("FALSE"))
      // which client is holding it: "grok", "claude", or whatever registered
      .column(Column.of("agent", "VARCHAR(128)"))
      .index("idx_sessions_user", "user_id")
      .index("idx_sessions_expires", "expires_at")
      .build();

  /**
   * Who is allowed to do what. One row per grant, so revoking is a delete and the history of who
   * granted what to whom is right there.
   *
   * 'role' is a reserved word in H2, hence role_name. The admin role is the only one the server
   * itself understands today; anything else is a label waiting for a feature.
   */
  public static final Table ROLES_TABLE = Table.named(ROLES)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("role_name", "VARCHAR(64)").notNull())
      .column(Column.of("granted_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("granted_by", "BIGINT"))
      .index("idx_roles_user", "user_id")
      .unique("uq_roles_user_role", "user_id", "role_name")
      .build();

  /**
   * Static pages. The raw source lives here; rendering happens on the way out and is cached.
   *
   * Three kinds, which is the whole authoring story: markdown wrapped in a template, an HTML
   * fragment wrapped in a template, or a full page that is served as-is. Storing the source rather
   * than the rendered output means a template change re-renders every page that used it, and means
   * an admin editing a page sees what they typed rather than what the renderer made of it.
   */
  public static final Table CONTENT_TABLE = Table.named(CONTENT)
      .column(Column.id("id"))
      // the path this page answers on, e.g. "/about"; unique per database
      .column(Column.of("uri", "VARCHAR(512)").notNull().unique())
      // The name this page keeps when everything else about it changes.
      //
      // A uri is an address and an id is a row number in one database; neither survives a page
      // being exported, edited somewhere else and brought back, which is the whole point of the
      // JSON bundle. The uuid is what says "this is the same page" across two installs, so an
      // import is a merge rather than a pile of duplicates. Stamped once and never rewritten.
      .column(Column.of("uuid", "VARCHAR(36)").notNull().withDefault("''"))
      .column(Column.of("title", "VARCHAR(256)").notNull().withDefault("''"))
      // markdown | html | page
      .column(Column.of("kind", "VARCHAR(16)").notNull().withDefault("'markdown'"))
      // the template to wrap this in; ignored when kind is 'page'
      .column(Column.of("template_name", "VARCHAR(64)"))
      // where this page sits in the navigation tree, e.g. "guides/getting-started"; empty means
      // it is not in the navigation at all, which the listing warns about
      .column(Column.of("nav_folder", "VARCHAR(256)").notNull().withDefault("''"))
      // values for the fields the chosen template declares, as a JSON object keyed by field name.
      // A blob because the shape is the template's business and changes when the template does.
      .column(Column.of("fields", "VARCHAR(65536)").notNull().withDefault("'{}'"))
      .column(Column.of("body", "VARCHAR(1048576)").notNull().withDefault("''"))
      .column(Column.of("published", "BOOLEAN").notNull().withDefault("TRUE"))
      // The day this went out, which is not the day the row was written.
      //
      // A page drafted in January and published in March is a March page, and a listing ordered by
      // creation would file it two months back where nobody looks. It is mutable because the other
      // direction happens too: a community moving twenty years of a newsletter into this wants
      // 2011 to say 2011. Left alone it is the first save, which is right for everything written
      // here in the ordinary way.
      .column(Column.of("published_at", "TIMESTAMP"))
      // Human only: invisible to every AI read and refused on every AI write. The default is FALSE
      // because a community that turns this on for everything has no use for the feature; it is for
      // the handful of pages where a model reading them is the problem.
      .column(Column.of("human_only", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_content_uri", "uri")
      .index("idx_content_uuid", "uuid")
      .index("idx_content_published_at", "published_at")
      .index("idx_content_template", "template_name")
      .build();

  /**
   * Page templates, by name.
   *
   * Separate from the templates compiled into the jar: those are the server's own pages and cannot
   * change at runtime. These are the operator's, and changing one invalidates every cached page that
   * named it -- the cascade that makes editing a site layout feel immediate.
   */
  /**
   * A uri that accepts a POST and runs a program.
   *
   * In the system database rather than the data one, because a mutation is operator machinery like
   * a template -- somebody with `tables_write` writes it, and it is versioned with the rest of what
   * the community declared rather than living beside the rows it changes.
   */
  public static final Table MUTATIONS_TABLE = Table.named(MUTATIONS)
      .column(Column.id("id"))
      .column(Column.of("uri", "VARCHAR(512)").notNull().unique())
      .column(Column.of("body", "VARCHAR(1048576)").notNull().withDefault("''"))
      // off is the safe half of the switch: a mutation somebody is midway through writing should
      // not be answering POSTs, and deleting it to stop it would lose the draft
      .column(Column.of("enabled", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_mutations_uri", "uri")
      .build();

  /**
   * Credentials one person handed this server for a service somewhere else.
   *
   * <b>A table of its own, not a column on `profiles`.</b> A profile is what other members read;
   * this is a secret, and the two should not be one SELECT away from each other. Every read of it
   * is deliberate.
   *
   * <b>Stored as given, and there is no honest alternative.</b> A session token can be a hash
   * because it is only ever compared; this has to be *presented* to Hevy on every call, so it is a
   * password this server holds on somebody's behalf. That is a real thing to be uncomfortable
   * about, which is why it is one row per person per service, cleared with one button, and never
   * printed on any screen -- `set` or `not set` is the half worth knowing, the same rule the
   * settings report follows.
   */
  public static final Table USER_KEYS_TABLE = Table.named(USER_KEYS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      // which service, from a closed list in code; a free-form name here would be a table of
      // arbitrary credentials with nothing checking what any of them are for
      .column(Column.of("service", "VARCHAR(32)").notNull())
      .column(Column.of("secret", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .unique("uq_user_keys", "user_id", "service")
      .build();

  /**
   * One decision being made, with its options and everything that has happened to it.
   *
   * <b>Two blobs, on purpose.</b> The options are the current state; the history is append-only and
   * holds every proposal, every ballot and every narrowing in order. Modelling ballots as rows
   * would mean a schema for a thing whose shape is still being discovered -- an agent that wants to
   * say "Thursday works but Friday is better" should not need a migration -- and the whole vote is
   * small enough to rewrite atomically. One row is also one lock: two agents voting at the same
   * moment cannot interleave into a half-applied ballot.
   *
   * <b>Append-only history is the point rather than an implementation detail.</b> The question
   * everybody asks afterwards is not "what won" but "why", and a tally that cannot show its working
   * is one nobody trusts -- especially when half the voters are agents.
   */
  public static final Table VOTES_TABLE = Table.named(VOTES)
      .column(Column.id("id"))
      .column(Column.of("slug", "VARCHAR(64)").notNull().unique())
      .column(Column.of("title", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("question", "VARCHAR(4096)").notNull().withDefault("''"))
      // open -> narrowed -> decided -> abandoned; a closed list in code
      .column(Column.of("state", "VARCHAR(16)").notNull().withDefault("'open'"))
      // what is being voted on right now, as a JSON array of options
      .column(Column.of("options", "VARCHAR(1048576)").notNull().withDefault("'[]'"))
      // every proposal, ballot and narrowing in order, as a JSON array
      .column(Column.of("history", "VARCHAR(1048576)").notNull().withDefault("'[]'"))
      // the option that won, once there is one
      .column(Column.of("outcome", "VARCHAR(256)").notNull().withDefault("''"))
      // consensus | majority -- how this vote decides. See Votes.Mode.
      .column(Column.of("mode", "VARCHAR(16)").notNull().withDefault("'consensus'"))
      // who is hosting, if anybody. Their block is final whatever the mode.
      .column(Column.of("host_id", "BIGINT"))
      // has the host said yes? An invite fans out to everybody only once they have.
      .column(Column.of("host_accepted", "BOOLEAN").notNull().withDefault("FALSE"))
      // has the invitation gone out, so it goes out once
      .column(Column.of("invited_at", "TIMESTAMP"))
      .column(Column.of("opened_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_votes_state", "state")
      .build();

  /**
   * When somebody is free, without handing anybody their calendar.
   *
   * <b>Two answers, and which one you get is part of the answer.</b> Somebody who trusts an agent
   * with their calendar publishes an ICS link and the agent reads real engagements. Somebody who
   * does not writes down a weekly shape -- "most evenings, never Wednesday" -- and the agent is
   * told that is what it is holding. Presenting a rough shape as though it were a calendar is how
   * an agent confidently proposes a night somebody has had booked for a month.
   */
  public static final Table AVAILABILITY_TABLE = Table.named(AVAILABILITY)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull().unique())
      // a JSON object of weekday -> free windows, in the person's own words and their own clock
      .column(Column.of("weekly", "VARCHAR(65536)").notNull().withDefault("'{}'"))
      // anything an agent should know that a grid cannot say
      .column(Column.of("notes", "VARCHAR(4096)").notNull().withDefault("''"))
      // Can this person have people round, and how movable is their week?
      //
      // These two are what an agent is *seeded* with, and they are the difference between a useful
      // first proposal and a round of guessing. One person hosts and has a calendar full of things
      // that could shift; another is free most evenings and immovable on three. Neither fact is in
      // a grid of free/busy and both decide what to propose first.
      .column(Column.of("hosts", "BOOLEAN").notNull().withDefault("FALSE"))
      // mostly_free | it_depends | tightly_booked
      .column(Column.of("flexibility", "VARCHAR(16)").notNull().withDefault("'it_depends'"))
      // an ICS url, if they are willing to share one
      .column(Column.of("ics_url", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .build();

  /**
   * A named state machine a task can walk.
   *
   * <b>Because done/not-done is a lie about most ranch work.</b> A calf is not "fed or not"; a
   * fence repair goes surveyed -> materials -> built -> checked, and knowing which of those it is
   * in is the whole value. Defining the process once and pointing several tasks at it is what stops
   * that becoming forty tasks called "step 2".
   *
   * The states are a JSON array of names in order. Order matters: it is what "advance" means, and
   * what a sheet sorts by.
   */
  public static final Table PROCESSES_TABLE = Table.named(PROCESSES)
      .column(Column.id("id"))
      .column(Column.of("slug", "VARCHAR(64)").notNull().unique())
      .column(Column.of("title", "VARCHAR(200)").notNull().withDefault("''"))
      .column(Column.of("states", "VARCHAR(8192)").notNull().withDefault("'[]'"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .build();

  /**
   * One thing to do, or one habit to keep.
   *
   * <b>Tasks and habits are one table because they are one list.</b> The daily sheet does not care
   * which a thing is; it cares what has to happen today. Splitting them would mean two queries,
   * two screens, and a person having to know which kind of thing they are looking for before they
   * can look for it.
   *
   * <b>A habit graduates rather than being deleted.</b> That is the difference between a habit
   * tracker and a checklist: the point of a habit is to stop needing to be tracked, and deleting it
   * throws away the evidence that it worked. Graduated habits leave the sheet and keep their marks.
   */
  public static final Table TASKS_TABLE = Table.named(TASKS)
      .column(Column.id("id"))
      .column(Column.of("title", "VARCHAR(400)").notNull().withDefault("''"))
      .column(Column.of("detail", "VARCHAR(65536)").notNull().withDefault("''"))
      // task | habit
      .column(Column.of("kind", "VARCHAR(16)").notNull().withDefault("'task'"))
      // for a task: open | done | dropped, or a state of its process
      .column(Column.of("state", "VARCHAR(64)").notNull().withDefault("'open'"))
      // the process this task walks, if it walks one
      .column(Column.of("process", "VARCHAR(64)").notNull().withDefault("''"))
      // daily | weekly | none -- how often a habit has to happen
      .column(Column.of("cadence", "VARCHAR(16)").notNull().withDefault("'none'"))
      // how many times a week a weekly habit needs doing
      .column(Column.of("per_week", "INTEGER").notNull().withDefault("1"))
      // a habit that has done its job; it leaves the sheet and keeps its history
      .column(Column.of("graduated_at", "TIMESTAMP"))
      // A challenge: a habit with an end. "Thirty days of mobility" is a different thing from "do
      // mobility forever", and the difference is that it finishes -- so it carries its own dates
      // and graduates itself when the last day passes rather than sitting there being missed.
      .column(Column.of("starts_on", "DATE"))
      .column(Column.of("ends_on", "DATE"))
      // when this has to happen, for a task with a date
      .column(Column.of("due_on", "DATE"))
      // what this belongs to: gym, ranch, whatever somebody types
      .column(Column.of("area", "VARCHAR(64)").notNull().withDefault("''"))
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("done_at", "TIMESTAMP"))
      .index("idx_tasks_user", "user_id")
      .index("idx_tasks_state", "state")
      .build();

  /**
   * One day a habit was kept.
   *
   * A row per day rather than a counter, because the question a habit tracker exists to answer is
   * "which days" -- a streak, a gap, a month where it fell apart. A counter can produce the number
   * and can never produce the shape, and the shape is what tells somebody whether to graduate it.
   */
  public static final Table HABIT_MARKS_TABLE = Table.named(HABIT_MARKS)
      .column(Column.id("id"))
      .column(Column.of("task_id", "BIGINT").notNull())
      .column(Column.of("on_day", "DATE").notNull())
      .column(Column.of("note", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .unique("uq_habit_marks", "task_id", "on_day")
      .build();

  /**
   * Somebody's calendar, fetched and kept for an hour.
   *
   * <b>Cached because the alternative is fetching five calendars every time an agent asks a
   * question.</b> An hour is long enough that a conversation about next Thursday costs one fetch
   * per person, and short enough that something booked this morning is visible this afternoon.
   *
   * <b>Only the busy windows are kept, never the text.</b> What a scheduler needs is when somebody
   * is not free; what an ICS carries is who they were meeting and about what. Storing the summaries
   * would make this table the most sensitive thing on the machine in exchange for nothing.
   */
  public static final Table CALENDARS_TABLE = Table.named(CALENDARS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull().unique())
      .column(Column.of("url", "VARCHAR(1024)").notNull().withDefault("''"))
      // a JSON array of {start, end} in epoch millis, and nothing else from the file
      .column(Column.of("busy", "VARCHAR(262144)").notNull().withDefault("'[]'"))
      .column(Column.of("fetched_at", "TIMESTAMP"))
      // what went wrong last time, so a broken link is visible rather than silently empty
      .column(Column.of("trouble", "VARCHAR(512)").notNull().withDefault("''"))
      .build();

  /**
   * The addresses that exist at a domain this server accepts mail for.
   *
   * <b>A mailbox is a place, not a person.</b> It carries an optional owner because
   * `jeff@` is somebody's and `receipts@` is nobody's, and a rule can act on either. What it
   * deliberately does not carry is a password or a delivery store: nothing here holds mail, so
   * there is nothing to sign into.
   *
   * The domain is a column because one database can serve several domains -- `use_database_domain`
   * makes one account space out of two hostnames, and `jeff@` at one of them is not `jeff@` at the
   * other. A mailbox table without it would silently merge two people's mail.
   */
  public static final Table MAILBOXES_TABLE = Table.named(MAILBOXES)
      .column(Column.id("id"))
      .column(Column.of("domain", "VARCHAR(255)").notNull())
      // stored lowercased; the pair below is the constraint that matters
      .column(Column.of("local_part", "VARCHAR(64)").notNull())
      .column(Column.of("label", "VARCHAR(160)").notNull().withDefault("''"))
      // whose address this is, when it is anybody's; null for a role address
      .column(Column.of("user_id", "BIGINT"))
      .column(Column.of("enabled", "BOOLEAN").notNull().withDefault("TRUE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .unique("uq_mailboxes_address", "domain", "local_part")
      .index("idx_mailboxes_domain", "domain")
      .build();

  /**
   * What happens to a message, decided in order, first match winning.
   *
   * <b>Two actions today and the shape is what matters.</b> `forward` and `drop` are the whole
   * vocabulary; the ordering, the match and the audit trail are the parts that would be painful to
   * add later. A rule that matched on nothing would match everything, so a rule with no conditions
   * at all is refused rather than stored -- the catch-all is written as `*` on purpose, because
   * somebody has to type it.
   */
  public static final Table MAIL_RULES_TABLE = Table.named(MAIL_RULES)
      .column(Column.id("id"))
      .column(Column.of("domain", "VARCHAR(255)").notNull())
      // lower is earlier; ties break on id, so two rules at the same position are still ordered
      .column(Column.of("position", "INTEGER").notNull().withDefault("100"))
      .column(Column.of("name", "VARCHAR(160)").notNull().withDefault("''"))
      // the local part this matches, or `*` for every address at the domain
      .column(Column.of("match_to", "VARCHAR(64)").notNull().withDefault("'*'"))
      // optional narrowing: a substring of the envelope sender, and of the subject
      .column(Column.of("match_from", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("match_subject", "VARCHAR(255)").notNull().withDefault("''"))
      .column(Column.of("action", "VARCHAR(16)").notNull().withDefault("'drop'"))
      .column(Column.of("forward_to", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("enabled", "BOOLEAN").notNull().withDefault("TRUE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_mail_rules_domain", "domain")
      .build();

  /**
   * Every message that arrived, what was decided about it, and what happened next.
   *
   * <b>This is the inspection surface, and it is metadata plus a short preview.</b> Not the
   * message: a forwarder that keeps copies is a mail store nobody asked for, and the thing worth
   * being able to answer is "did that get through, and why not" -- which needs the envelope, the
   * authentication verdicts, the rule that matched and the far end's own words back. The preview
   * exists because a rule that matched the wrong thing is only obvious next to the message it
   * matched, and it is capped hard for the same reason the rest of this is metadata.
   *
   * Pruned to the most recent rows per domain on write. A log that grows without bound is a disk
   * that fills on a Sunday.
   */
  public static final Table MAIL_LOG_TABLE = Table.named(MAIL_LOG)
      .column(Column.id("id"))
      .column(Column.of("received_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("domain", "VARCHAR(255)").notNull())
      .column(Column.of("envelope_from", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("recipient", "VARCHAR(320)").notNull().withDefault("''"))
      // what the message says about itself, which routinely disagrees with the envelope
      .column(Column.of("header_from", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("subject", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("message_id", "VARCHAR(255)").notNull().withDefault("''"))
      .column(Column.of("size_bytes", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("remote_ip", "VARCHAR(64)").notNull().withDefault("''"))
      // the three verdicts as they were at the moment it arrived, not re-derived later
      .column(Column.of("spf", "VARCHAR(16)").notNull().withDefault("'none'"))
      .column(Column.of("dkim", "VARCHAR(16)").notNull().withDefault("'none'"))
      .column(Column.of("dmarc", "VARCHAR(16)").notNull().withDefault("'none'"))
      // what this server sealed on the way out, so a chain problem at the far end is traceable here
      .column(Column.of("arc", "VARCHAR(32)").notNull().withDefault("''"))
      .column(Column.of("rule_id", "BIGINT"))
      .column(Column.of("rule_name", "VARCHAR(160)").notNull().withDefault("''"))
      .column(Column.of("action", "VARCHAR(16)").notNull().withDefault("''"))
      .column(Column.of("destination", "VARCHAR(320)").notNull().withDefault("''"))
      // where it actually went and how the conversation was protected
      .column(Column.of("relay_host", "VARCHAR(255)").notNull().withDefault("''"))
      .column(Column.of("tls", "VARCHAR(32)").notNull().withDefault("''"))
      .column(Column.of("outcome", "VARCHAR(24)").notNull().withDefault("''"))
      // the far end's own sentence, kept verbatim: a paraphrase of a 550 is a lost afternoon
      .column(Column.of("detail", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("attempts", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("preview", "VARCHAR(2048)").notNull().withDefault("''"))
      .index("idx_mail_log_domain", "domain")
      .index("idx_mail_log_received", "received_at")
      .build();

  /**
   * One delivered message, for one person.
   *
   * <b>The bodies are here and the raw message is on disk.</b> A row carries what a screen needs --
   * the headers somebody reads, the plain text, the sanitized HTML and a manifest of the parts --
   * and the `.eml` under the root carries the octets exactly as they arrived. That split is what
   * makes "download the attachment" and "show me the original" answerable without keeping two
   * copies of a photograph: the parts are re-read from the file when somebody asks for one.
   *
   * <b>`delivered_to` is the column the whole reply behaviour rests on.</b> A message that arrived
   * at `receipts@` is replied to *from* `receipts@`, whatever else the person owns -- so the
   * conversation continues from the address the other side already knows, and the signature and
   * SPF align with it.
   *
   * Read and archived are timestamps rather than a state column: "when did I read this" is a
   * question somebody asks and a state machine cannot answer.
   */
  public static final Table MAIL_MESSAGES_TABLE = Table.named(MAIL_MESSAGES)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("mailbox_id", "BIGINT"))
      .column(Column.of("domain", "VARCHAR(255)").notNull())
      // the address it actually arrived at, which is what a reply goes out as
      .column(Column.of("delivered_to", "VARCHAR(320)").notNull())
      .column(Column.of("received_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("envelope_from", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("from_name", "VARCHAR(255)").notNull().withDefault("''"))
      .column(Column.of("from_address", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("to_header", "VARCHAR(2048)").notNull().withDefault("''"))
      .column(Column.of("cc_header", "VARCHAR(2048)").notNull().withDefault("''"))
      .column(Column.of("reply_to", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("subject", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("message_id", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("in_reply_to", "VARCHAR(512)").notNull().withDefault("''"))
      // the whole chain, so a reply threads in everybody else's client
      .column(Column.of("references_header", "VARCHAR(4096)").notNull().withDefault("''"))
      .column(Column.of("spf", "VARCHAR(16)").notNull().withDefault("'none'"))
      .column(Column.of("dkim", "VARCHAR(16)").notNull().withDefault("'none'"))
      .column(Column.of("dmarc", "VARCHAR(16)").notNull().withDefault("'none'"))
      .column(Column.of("size_bytes", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("text_body", "VARCHAR(524288)").notNull().withDefault("''"))
      // already through the sanitizer; nothing renders what arrived
      .column(Column.of("html_body", "VARCHAR(524288)").notNull().withDefault("''"))
      // a JSON array of every part worth naming, allowed or refused, with the reason
      .column(Column.of("parts", "VARCHAR(65536)").notNull().withDefault("'[]'"))
      .column(Column.of("attachments", "INTEGER").notNull().withDefault("0"))
      // a text/calendar part arrived with it, so the screen offers to put it in the calendar
      .column(Column.of("has_calendar", "BOOLEAN").notNull().withDefault("FALSE"))
      // whether the HTML asked for anything from somebody else's server, which is never fetched
      .column(Column.of("blocked_remote", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("read_at", "TIMESTAMP"))
      // out of the inbox. Zero inbox is the whole point, so this is the column the listing filters
      .column(Column.of("archived_at", "TIMESTAMP"))
      .column(Column.of("replied_at", "TIMESTAMP"))
      .index("idx_mail_messages_user", "user_id")
      .index("idx_mail_messages_archived", "archived_at")
      .build();

  /**
   * One event in somebody's calendar.
   *
   * <b>Keyed by the iCalendar UID, not by our own id.</b> That is what makes an update an update:
   * an organizer who moves a meeting sends the same UID with a higher SEQUENCE, and a calendar that
   * matched on anything else would show the old time and the new one side by side. The pair
   * (user, uid) is unique for exactly that reason.
   *
   * Unlike {@link #CALENDARS}, which holds busy windows scraped from somebody else's calendar and
   * deliberately keeps no words, this is the person's own calendar and holds what they wrote.
   */
  public static final Table CALENDAR_EVENTS_TABLE = Table.named(CALENDAR_EVENTS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("uid", "VARCHAR(255)").notNull())
      // an organizer's revision counter; a lower one arriving later is a stale copy and is ignored
      .column(Column.of("sequence_number", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("summary", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("description", "VARCHAR(65536)").notNull().withDefault("''"))
      .column(Column.of("location", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("starts_at", "TIMESTAMP").notNull())
      .column(Column.of("ends_at", "TIMESTAMP").notNull())
      .column(Column.of("all_day", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("rrule", "VARCHAR(1024)").notNull().withDefault("''"))
      // dates an occurrence of a repeat was taken out; kept verbatim so a round trip is lossless
      .column(Column.of("exdates", "VARCHAR(8192)").notNull().withDefault("''"))
      .column(Column.of("status", "VARCHAR(32)").notNull().withDefault("'CONFIRMED'"))
      .column(Column.of("organizer", "VARCHAR(320)").notNull().withDefault("''"))
      // a JSON array of {address, name, partstat, role}
      .column(Column.of("attendees", "VARCHAR(16384)").notNull().withDefault("'[]'"))
      // what this person said about it, which is what a REPLY carries back
      .column(Column.of("my_answer", "VARCHAR(24)").notNull().withDefault("'NEEDS-ACTION'"))
      // where it came from: typed here, or an invitation that arrived
      .column(Column.of("source", "VARCHAR(24)").notNull().withDefault("'typed'"))
      .column(Column.of("from_message", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .unique("uq_calendar_events_uid", "user_id", "uid")
      .index("idx_calendar_events_user", "user_id")
      .index("idx_calendar_events_start", "starts_at")
      .build();

  /**
   * The secret in a calendar subscription URL.
   *
   * <b>Hashed, like a session token</b>, for the reason invariant 19 gives: the URL is pasted into
   * a phone and lives in its settings forever, so a stolen database file must not be a list of
   * working calendar feeds. One per person, replaceable, and revoking it is a delete.
   */
  public static final Table CALENDAR_FEEDS_TABLE = Table.named(CALENDAR_FEEDS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull().unique())
      .column(Column.of("token_hash", "VARCHAR(64)").notNull())
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("last_read_at", "TIMESTAMP"))
      .index("idx_calendar_feeds_hash", "token_hash")
      .build();

  /**
   * One address that answers with another, and the proposals waiting to become one.
   *
   * <b>An address that has been published is a promise.</b> Somebody bookmarked it, somebody linked
   * to it, and a search engine has it indexed with whatever standing the page earned. Moving the
   * page and leaving a 404 behind throws all of that away silently -- the link still exists, it
   * simply stops working, and nobody finds out except the person who followed it.
   *
   * <b>`state` is what makes this two features in one table.</b> A `proposed` row is a suggestion
   * this server made when somebody moved a published page; an `active` row is a redirect that
   * actually fires. They are the same shape and accepting is a flip, which is why there is no
   * second table: a proposal is a rewrite that is not live yet, and pretending otherwise would mean
   * two schemas, two screens and two chances to disagree about what a redirect is.
   *
   * `from_uri` is unique because an address answers one way. `to_uri` is empty exactly when the
   * status is 410 -- a page that is deliberately gone rather than moved.
   */
  public static final Table REWRITES_TABLE = Table.named(REWRITES)
      .column(Column.id("id"))
      // the address somebody asks for; unique, because an address answers one way
      .column(Column.of("from_uri", "VARCHAR(512)").notNull().unique())
      // where they are sent, or empty for 410, where the answer is that there is nowhere
      .column(Column.of("to_uri", "VARCHAR(512)").notNull().withDefault("''"))
      // 301 unless somebody chose otherwise; see Rewrites.Status for why that is the default
      .column(Column.of("status", "INTEGER").notNull().withDefault("301"))
      // proposed (waiting for a person) or active (firing); see the class note
      .column(Column.of("state", "VARCHAR(16)").notNull().withDefault("'active'"))
      .column(Column.of("enabled", "BOOLEAN").notNull().withDefault("TRUE"))
      // why this exists, in whatever words whoever made it used
      .column(Column.of("note", "VARCHAR(512)").notNull().withDefault("''"))
      // which page moved, when this was proposed rather than typed
      .column(Column.of("content_id", "BIGINT"))
      // How many times it has fired, and when it last did.
      //
      // The number that says whether a redirect is still earning its place. A rewrite nobody has
      // followed in a year is one somebody can delete; one that fires every day is holding a link
      // somewhere this server cannot see. Written from memory on a timer rather than per request --
      // a redirect is a fast path and a write on it would be the slowest thing about it.
      .column(Column.of("hits", "BIGINT").notNull().withDefault("0"))
      .column(Column.of("last_used_at", "TIMESTAMP"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      .index("idx_rewrites_state", "state")
      .build();

  public static final Table TEMPLATES_TABLE = Table.named(TEMPLATES)
      .column(Column.id("id"))
      .column(Column.of("name", "VARCHAR(64)").notNull().unique())
      // the same merge key content carries, for the same reason: a bundle without the templates
      // its pages name rebuilds a site that renders as bare bodies
      .column(Column.of("uuid", "VARCHAR(36)").notNull().withDefault("''"))
      // the fields a page using this template must fill in, as a JSON array of declarations
      .column(Column.of("parameters", "VARCHAR(65536)").notNull().withDefault("'[]'"))
      .column(Column.of("body", "VARCHAR(1048576)").notNull().withDefault("''"))
      // Does this template also publish a listing of everything using it?
      //
      // This is what turns the content table into a blog without anybody adding a blog. A template
      // called "post" with directory on gets an index at directory_path, paginated, and every page
      // that names that template is an entry in it -- so writing a post is writing a page, and the
      // listing is a property of the shape rather than a second thing to maintain.
      .column(Column.of("directory", "BOOLEAN").notNull().withDefault("FALSE"))
      // where the first page of the listing lives, e.g. /blog
      .column(Column.of("directory_path", "VARCHAR(256)").notNull().withDefault("''"))
      // how page N is addressed. {page} is substituted; /blog/page/{page} and /blog?page={page}
      // both work, and the pattern is what decides which.
      .column(Column.of("directory_pattern", "VARCHAR(256)").notNull().withDefault("''"))
      // The index's own markup, which is a second template.
      //
      // One body cannot be both. A page template renders one thing with a title and a body; an
      // index renders a list with pagination -- and asking one file to be both meant every
      // directory template opening with a branch on `{{#directory}}`, which is the shape somebody
      // writes once and nobody can edit six months later. Ticking the box seeds this with a
      // working listing, so a community gets two templates that both do something rather than a
      // second empty box.
      .column(Column.of("directory_body", "VARCHAR(1048576)").notNull().withDefault("''"))
      .column(Column.of("directory_page_size", "INTEGER").notNull().withDefault("10"))
      // newest first is right for a blog and wrong for a handbook, so it is a setting
      .column(Column.of("directory_order", "VARCHAR(16)").notNull().withDefault("'newest'"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .build();

  /**
   * What somebody says about themselves.
   *
   * Separate from emails rather than more columns on it, because the two answer different questions
   * and change at different times: emails is the credential, profiles is the person. An admin
   * deciding whether to approve somebody reads this table, which is the point -- the browser checks
   * on the register form filter bots, and a profile is what filters strangers.
   */
  public static final Table PROFILES_TABLE = Table.named(PROFILES)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull().unique())
      .column(Column.of("display_name", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("headline", "VARCHAR(256)").notNull().withDefault("''"))
      // markdown, rendered when shown; the same renderer the content table uses
      .column(Column.of("about", "VARCHAR(8192)").notNull().withDefault("''"))
      .column(Column.of("location", "VARCHAR(128)").notNull().withDefault("''"))
      // The address, the two coordinates and the seven geo_ columns that were here are gone.
      //
      // They existed to work out how far somebody would travel to a proposed event, and there are
      // no events any more. Nothing had read or written them since the reduction -- PeopleStore
      // still carried the SELECT list for them as a constant nothing referenced, which is what a
      // dead column looks like from the inside.
      //
      // A column nothing uses is not free. It is a sentence in the privacy policy that has to stay
      // true, a column every erasure test has to keep walking, and a street address sitting in a
      // file for no reason at all.
      //
      // An upgraded database still has them, because the upgrader adds and never drops. Getting
      // rid of them there is what /admin/system/cleanup is for.
      .column(Column.of("links", "VARCHAR(1024)").notNull().withDefault("''"))
      // how far through the welcome they actually got: 0 never started, 1 told us their name,
      // 2 answered or skipped the questions, 3 reached the end. Only ever forwards, and written
      // when a step is *finished* rather than when it is on screen -- so re-opening the welcome
      // cannot take somebody backwards, which is the one thing that would make this number a lie.
      // The last screen counts on arrival, because there is nothing on it to do.
      .column(Column.of("orientation_step", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_profiles_user", "user_id")
      .build();



  /**
   * Addresses this server will not spend anything on.
   *
   * Checked before a code is minted or mailed, which is the point: a banned address should cost a
   * lookup and nothing else. No account row, no pending code, no outbound mail, no row in the
   * signup table for an admin to look at later.
   */
  /**
   * What this community decided about itself, as name and value.
   *
   * The product half of a domain's configuration lives here rather than in the file, so the people
   * running a community can change what it is called, how long a conversation lives and what an
   * invitation says without an SSH key. The security half deliberately does not: sign-in policy,
   * credentials, what a program may do and how many bytes a request may carry stay in a file an
   * operator owns and reviews by reading.
   *
   * A name is the dotted path that value has in a config file, which is what lets a row be applied
   * by writing it into a copy of the file's JSON and re-parsing -- so the check that refuses a bad
   * value at boot is the same check that refuses it in the admin section. A row exists only where
   * somebody has actually decided something; absent means the file's value, or the built-in.
   */
  public static final Table CONFIG_TABLE = Table.named(CONFIG)
      .column(Column.id("id"))
      .column(Column.of("name", "VARCHAR(128)").notNull().unique())
      // Not "value": H2 runs in MODE=STRICT, which reserves the SQL standard's keywords, and VALUE
      // is one of them. The name is uglier and the alternative is a column this schema could not
      // create on any database that follows the standard.
      //
      // VARCHAR rather than CLOB for the same reason every other long field here is: H2 reports a
      // CLOB back as CHARACTERLARGEOBJECT, which the upgrader reads as a type that has changed
      // under it and refuses to start on. The ceiling matches the other prose columns.
      .column(Column.of("value_text", "VARCHAR(1048576)").notNull().withDefault("''"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_config_name", "name")
      .build();

  public static final Table BANS_TABLE = Table.named(BANS)
      .column(Column.id("id"))
      .column(Column.of("email", "VARCHAR(320)").notNull().unique())
      .column(Column.of("reason", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      .index("idx_bans_email", "email")
      .build();

  /**
   * Every version a page has ever had.
   *
   * This is meant to replace reaching for git to keep track of a website, so it stores the *whole*
   * page -- body, title, template, folder, template field values, published and human-only flags --
   * as one canonical JSON document. Anything less and "what did this page look like in March" has
   * an answer that is missing the part somebody actually changed.
   *
   * A row is either a snapshot of that document or a patch against the version before it, which is
   * the difference between a history that costs a megabyte per typo and one that does not. The
   * snapshot is the anchor: reconstruction walks back to the nearest one and replays forward, so a
   * corrupt patch can lose the versions after it but never the snapshot itself.
   */
  public static final Table CONTENT_VERSIONS_TABLE = Table.named(CONTENT_VERSIONS)
      .column(Column.id("id"))
      .column(Column.of("content_id", "BIGINT").notNull())
      // 1, 2, 3... per page, so a person can say "version 4" and mean something
      .column(Column.of("version", "INTEGER").notNull())
      // snapshot | patch
      .column(Column.of("kind", "VARCHAR(16)").notNull().withDefault("'snapshot'"))
      // the whole document, or the patch that produces it from the version before
      .column(Column.of("payload", "VARCHAR(1048576)").notNull().withDefault("''"))
      // what changed, in words, computed once at write time because it is read far more often
      .column(Column.of("summary", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      // kept alongside the id so a listing does not need a join, and so a deleted account does not
      // erase the authorship of everything they ever wrote
      .column(Column.of("created_by_email", "VARCHAR(320)").notNull().withDefault("''"))
      .index("idx_content_versions_content", "content_id")
      .build();







  /**
   * The OAuth clients allowed to ask for an agent token.
   *
   * No secret column: these are public clients doing PKCE, which is what OAuth 2.1 wants for
   * anything that cannot keep a secret -- and a hosted model connector cannot. The redirect URIs are
   * the thing that actually has to be right, so they are stored explicitly and matched exactly.
   */
  public static final Table OAUTH_CLIENTS_TABLE = Table.named(OAUTH_CLIENTS)
      .column(Column.id("id"))
      .column(Column.of("client_id", "VARCHAR(64)").notNull().unique())
      .column(Column.of("name", "VARCHAR(128)").notNull().withDefault("''"))
      // which vendor profile it registered under; "custom" when an operator added it by hand
      .column(Column.of("vendor", "VARCHAR(32)").notNull().withDefault("'custom'"))
      // a JSON array; every redirect must be listed in full, and is compared exactly
      .column(Column.of("redirect_uris", "VARCHAR(4096)").notNull().withDefault("'[]'"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("disabled", "BOOLEAN").notNull().withDefault("FALSE"))
      .build();







  /**
   * A file somebody uploaded: what it is, where it sits, and who may read it.
   *
   * <b>The row is the record; the bytes are not in it.</b> A photograph in a database column is
   * read into memory to be served, copied by every backup of the schema, and impossible to hand to
   * a web server or an object store later. So this table holds everything *about* an upload and the
   * blob lives under `<root>/attachments`, at a path computed from the id -- see
   * {@link io.hearth.attach.DiskAttachments}.
   *
   * <b>The extension is authoritative and the browser's content type is thrown away.</b> What a
   * browser sends with an upload is a claim by whoever uploaded it, and believing it is how a
   * community's own domain ends up serving attacker-written HTML with every member's cookie on it.
   * The extension is checked against a closed table, and `mime` is what that table said.
   */
  public static final Table ATTACHMENTS_TABLE = Table.named(ATTACHMENTS)
      .column(Column.id("id"))
      // the merge key content already has, for the same reason: an id is a row number in one
      // database and this may be exported one day
      .column(Column.of("uuid", "VARCHAR(36)").notNull().withDefault("''"))
      // what it was called when it arrived; shown to people and used to name a download, never a
      // path -- a filename is a place for "../", for a null byte, and for a direction override
      .column(Column.of("filename", "VARCHAR(190)").notNull().withDefault("''"))
      .column(Column.of("extension", "VARCHAR(8)").notNull().withDefault("''"))
      .column(Column.of("mime", "VARCHAR(128)").notNull().withDefault("''"))
      // image | video | audio | document | other, from the same table the mime came from
      .column(Column.of("kind", "VARCHAR(16)").notNull().withDefault("'other'"))
      .column(Column.of("bytes", "BIGINT").notNull().withDefault("0"))
      // sha-256 of the contents: what makes "this is already here" answerable, and what a later
      // integrity check would compare against
      .column(Column.of("digest", "VARCHAR(64)").notNull().withDefault("''"))
      // where it lives, so a second storage mode can be added without guessing about old rows
      .column(Column.of("storage", "VARCHAR(16)").notNull().withDefault("'disk'"))
      // a folder tree written as a path: "suppers/2026-05". Empty means the top.
      .column(Column.of("folder", "VARCHAR(256)").notNull().withDefault("''"))
      // space-separated words; searched with a prefilter and checked properly in Java, the same
      // way a place's declared fields are
      .column(Column.of("tags", "VARCHAR(512)").notNull().withDefault("''"))
      // what it is a picture of. This is the alt text, which is why it is a column rather than a
      // nicety: an image embedded in a page with nothing to say about it is an image that is not
      // there at all for whoever is not looking at it.
      .column(Column.of("description", "VARCHAR(512)").notNull().withDefault("''"))
      // Public means anybody may fetch it; private needs a signed-in, approved member. Private is
      // the default, because the failure of guessing wrong that way round is a photograph nobody
      // outside can see, and the other way round is a photograph everybody outside can.
      .column(Column.of("public", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("uploaded_by", "BIGINT"))
      .column(Column.of("uploaded_by_email", "VARCHAR(320)").notNull().withDefault("''"))
      .index("idx_attachments_folder", "folder")
      .index("idx_attachments_kind", "kind")
      .index("idx_attachments_uuid", "uuid")
      .build();

  /**
   * What a role means, as opposed to who holds it.
   *
   * `roles` is the grant; this is the definition. Splitting them is what makes a role editable at
   * all -- changing what "editor" means is one row here rather than a sweep over everybody who is
   * one.
   *
   * The admin row is rewritten at every boot and refuses to be edited, because a community that can
   * accidentally edit its way out of having an administrator has locked itself out of its own
   * server.
   */
  public static final Table ROLE_DEFS_TABLE = Table.named(ROLE_DEFS)
      .column(Column.id("id"))
      .column(Column.of("name", "VARCHAR(64)").notNull().unique())
      .column(Column.of("label", "VARCHAR(64)").notNull().withDefault("''"))
      .column(Column.of("description", "VARCHAR(512)").notNull().withDefault("''"))
      // a JSON array of Permission names; unknown names are ignored rather than fatal, so a role
      // written by a newer version does not stop an older one from starting
      .column(Column.of("permissions", "VARCHAR(4096)").notNull().withDefault("'[]'"))
      .column(Column.of("builtin", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("color", "VARCHAR(16)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_role_defs_name", "name")
      .build();


  /**
   * One browser's push subscription, belonging to one session.
   *
   * Bound to the session rather than the account on purpose. A person's phone and their laptop are
   * two sessions and two subscriptions, and signing out on the laptop should silence the laptop and
   * nothing else. It also gives the delete a natural cascade: no session, no subscription, no way to
   * reach that browser -- which is the property that makes "sign me out" mean something on a device
   * somebody no longer has.
   *
   * The VAPID pair is here too, per subscription, so revoking a session destroys the only key the
   * push service will accept for it.
   */
  public static final Table PUSH_SUBS_TABLE = Table.named(PUSH_SUBS)
      .column(Column.id("id"))
      .column(Column.of("session_id", "BIGINT").notNull())
      .column(Column.of("user_id", "BIGINT").notNull())
      // the push service's URL for this browser; unique because re-subscribing returns the same one
      .column(Column.of("endpoint", "VARCHAR(2048)").notNull())
      // the browser's own P-256 public key and auth secret, both base64url
      .column(Column.of("p256dh", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("auth", "VARCHAR(64)").notNull().withDefault("''"))
      .column(Column.of("vapid_public", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("vapid_private", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("last_push_at", "TIMESTAMP"))
      // a push service saying 404 or 410 means the browser is gone; two strikes and we stop
      .column(Column.of("failures", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("last_detail", "VARCHAR(256)").notNull().withDefault("''"))
      .index("idx_push_subs_session", "session_id")
      .index("idx_push_subs_user", "user_id")
      .build();



  // 'key' and 'value' are reserved words in H2's strict mode, hence the prefixes
  public static final Table META_TABLE = Table.named(META)
      .column(Column.of("meta_key", "VARCHAR(64)").notNull().unique())
      .column(Column.of("meta_value", "VARCHAR(256)").notNull())
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .build();

  /**
   * The colours one community chose, one row per scope, and only once somebody has chosen.
   *
   * A blob rather than a column per colour: a palette is read and written whole, and twelve columns
   * would be twelve schema versions the first time somebody wants a thirteenth colour.
   */
  public static final Table THEMES_TABLE = Table.named(THEMES)
      .column(Column.id("id"))
      // "site" or "admin"; the enum is the truth and this is how it is spelt on disk
      .column(Column.of("scope", "VARCHAR(16)").notNull().unique())
      .column(Column.of("colors", "VARCHAR(4096)").notNull().withDefault("'{}'"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_themes_scope", "scope")
      .build();

  /**
   * An override of a document that ships with the software.
   *
   * No row means the community is using the standard text, which is the point: the default lives in
   * the jar and improves when the jar does, and a table seeded with copies at boot would freeze
   * every community's privacy policy on the day it started.
   */
  public static final Table LEGAL_TABLE = Table.named(LEGAL)
      .column(Column.id("id"))
      .column(Column.of("slug", "VARCHAR(64)").notNull().unique())
      .column(Column.of("body", "VARCHAR(200000)").notNull().withDefault("''"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_legal_slug", "slug")
      .build();

  /**
   * What this community says instead of the standard wording, one row per message it sends.
   *
   * <b>A row exists only once somebody has changed something.</b> The shipped words are in the jar,
   * so a community that has never opened the screen still sends good messages and upgrading the
   * software improves them -- the same argument the legal documents make, and the reason neither is
   * seeded at boot. Seeding would freeze every community's wording on the day it was created.
   *
   * Three columns rather than one blob because they are three different things with three different
   * lengths, and because the editor puts them in three boxes: what the subject line says, the
   * sentence at the top, and the paragraphs under it. The layout around them -- the tables, the
   * button, the plain-text half, the footer that says why this arrived -- stays in code, because a
   * community one paste away from an unreadable message in Outlook is not a community that has been
   * given control, it is one that has been handed a loaded foot-gun.
   */
  public static final Table SYSTEM_TEMPLATES_TABLE = Table.named(SYSTEM_TEMPLATES)
      .column(Column.id("id"))
      // the flow this is the wording for; one of io.hearth.mail.SystemTemplate
      .column(Column.of("slug", "VARCHAR(64)").notNull().unique())
      .column(Column.of("subject", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("lead", "VARCHAR(2048)").notNull().withDefault("''"))
      .column(Column.of("body", "VARCHAR(16384)").notNull().withDefault("''"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_system_templates_slug", "slug")
      .build();








  public static final List<Table> TABLES =
      List.of(META_TABLE, EMAILS_TABLE, SESSIONS_TABLE, ROLES_TABLE, TEMPLATES_TABLE, CONTENT_TABLE,
          PROFILES_TABLE, BANS_TABLE, OAUTH_CLIENTS_TABLE,
          CONTENT_VERSIONS_TABLE,
          ROLE_DEFS_TABLE, PUSH_SUBS_TABLE,
          THEMES_TABLE, LEGAL_TABLE, SYSTEM_TEMPLATES_TABLE,
          ATTACHMENTS_TABLE,
          CONFIG_TABLE, MUTATIONS_TABLE, USER_KEYS_TABLE,
          VOTES_TABLE, AVAILABILITY_TABLE,
          PROCESSES_TABLE, TASKS_TABLE, HABIT_MARKS_TABLE, CALENDARS_TABLE,
          REWRITES_TABLE,
          MAILBOXES_TABLE, MAIL_RULES_TABLE, MAIL_LOG_TABLE, MAIL_MESSAGES_TABLE,
          CALENDAR_EVENTS_TABLE, CALENDAR_FEEDS_TABLE);

  private Schema() {
  }
}
