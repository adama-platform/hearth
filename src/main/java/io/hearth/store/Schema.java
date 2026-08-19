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
  public static final int VERSION = 39;

  public static final String EMAILS = "emails";
  public static final String SESSIONS = "sessions";
  public static final String ROLES = "roles";
  public static final String CONTENT = "content";
  public static final String TEMPLATES = "templates";
  public static final String PROFILES = "profiles";
  public static final String QUESTIONS = "questions";
  public static final String ANSWERS = "answers";
  public static final String BANS = "bans";
  public static final String OAUTH_CLIENTS = "oauth_clients";
  public static final String CONTENT_VERSIONS = "content_versions";
  public static final String INVITES = "invites";
  public static final String POSTS = "posts";
  public static final String COMMENTS = "comments";
  public static final String POLLS = "polls";
  public static final String POLL_OPTIONS = "poll_options";
  public static final String POLL_VOTES = "poll_votes";
  public static final String PROJECTS = "projects";
  public static final String TASK_DEFS = "task_defs";
  public static final String TASKS = "tasks";
  public static final String TASK_ENTRIES = "task_entries";
  public static final String NOTIFICATIONS = "notifications";
  public static final String NOTIFY_PREFS = "notify_prefs";
  public static final String CALENDAR = "calendar";
  public static final String RSVPS = "rsvps";
  public static final String ROLE_DEFS = "role_defs";
  public static final String PROPOSALS = "proposals";
  public static final String SIGNALS = "signals";
  public static final String PUSH_SUBS = "push_subs";
  public static final String PLACE_TYPES = "place_types";
  public static final String PLACES = "places";
  public static final String THEMES = "themes";
  public static final String LEGAL = "legal";
  public static final String SYSTEM_TEMPLATES = "system_templates";
  public static final String PUBLIC_RSVPS = "public_rsvps";
  public static final String AVAILABILITY = "availability";
  public static final String CALENDAR_LINKS = "calendar_links";
  public static final String CALENDAR_CACHE = "calendar_cache";
  public static final String ATTACHMENTS = "attachments";
  public static final String CONFIG = "config";
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
      // Where they actually live, and it is not the column above it.
      //
      // `location` is what somebody puts on their profile for other members to read -- a town, a
      // neighbourhood, a joke. This is a street address, given for one purpose: working out how far
      // people would have to travel to a proposed event. It is never rendered on any page, never in
      // the admin section, never handed to a model and never in another member's export. The only
      // thing that leaves this column is a distance, counted into a bucket with everybody else's.
      .column(Column.of("address", "VARCHAR(256)").notNull().withDefault("''"))
      // What that resolved to. A point rather than the text is what every calculation uses, which
      // is also what lets somebody give a town instead and still be counted -- roughly, and
      // labelled as roughly.
      .column(Column.of("latitude", "DOUBLE PRECISION"))
      .column(Column.of("longitude", "DOUBLE PRECISION"))
      // 'precise' when it came from the private address, 'city' when it came from the public
      // location line. The difference matters to whoever reads a distance histogram: a city-level
      // point is a claim about a town, not about a doorstep.
      .column(Column.of("geo_precision", "VARCHAR(16)").notNull().withDefault("''"))
      // How the last attempt ended, which is not the same question as where they are.
      //
      // Three failures are possible and only two of them are worth retrying. `not_found` is the
      // service saying it has never heard of that address: asking it again tomorrow gets the same
      // answer, so it is left alone until the address changes or the operator switches service.
      // `unreachable` is the service not answering, which says nothing about the address at all
      // and is retried on a widening schedule. Collapsing the two -- which the first version of
      // this did -- either retries a typo forever or gives up on everybody because somebody's DNS
      // was down for an hour.
      .column(Column.of("geo_state", "VARCHAR(16)").notNull().withDefault("''"))
      // which service produced that answer, so switching service re-opens everything it could not
      // find. Without this, changing to a better geocoder leaves every previous failure sitting
      // there permanently, which is the one thing somebody switches in order to fix.
      .column(Column.of("geo_service", "VARCHAR(32)").notNull().withDefault("''"))
      .column(Column.of("geo_tries", "INTEGER").notNull().withDefault("0"))
      // when it may be asked about again; null means as soon as anything is looking
      .column(Column.of("geo_next_at", "TIMESTAMP"))
      // renamed because it stopped being true: it is stamped by a failure as well as by a
      // success, and a column called geocoded_at holding "we tried and could not" is a column
      // that will be read wrongly by somebody in six months
      .column(Column.of("geo_tried_at", "TIMESTAMP").renamedFrom("geocoded_at"))
      // what happened last time, when it did not work; shown to its owner and to nobody else
      .column(Column.of("geo_note", "VARCHAR(256)").notNull().withDefault("''"))
      // one per line, so an admin can see where somebody is from without a second table
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
   * The questions admins ask.
   *
   * The question itself is a JSON blob: prompt, kind, options, whether it is required. A blob rather
   * than columns because the shape differs per kind -- a rating has bounds, a dropdown has options,
   * free text has neither -- and because the set is meant to change often enough that a schema
   * change per question type would be the thing standing in the way.
   *
   * position and published are real columns because ordering and filtering are queries, not
   * inspection of a blob.
   */
  public static final Table QUESTIONS_TABLE = Table.named(QUESTIONS)
      .column(Column.id("id"))
      .column(Column.of("definition", "VARCHAR(16384)").notNull().withDefault("'{}'"))
      .column(Column.of("position", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("published", "BOOLEAN").notNull().withDefault("TRUE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      // Soft delete. Purging a question has to strip its key from every answer sheet, and doing that
      // inside the request that clicked "delete" is a cascade nobody asked for -- so deleting hides
      // it, and an admin commits the cleanup deliberately from its own page.
      .column(Column.of("deleted_at", "TIMESTAMP"))
      .column(Column.of("deleted_by", "BIGINT"))
      .index("idx_questions_position", "position")
      .build();

  /**
   * What each person answered, as one blob per person.
   *
   * One row per user, keyed by question id inside the blob. A row per answer would be the obvious
   * relational shape and would make "how many are left" a join; this shape makes it a count that
   * something has to maintain, which is what the survey indexer does. The trade is deliberate:
   * reading somebody's answers is one row, and the expensive part happens off the request path.
   *
   * answered and remaining are maintained by that indexer and are what the notification bubble
   * reads. They are a cache in a column, and like every cache here they can be rebuilt from source.
   */
  public static final Table ANSWERS_TABLE = Table.named(ANSWERS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull().unique())
      .column(Column.of("blob", "VARCHAR(65536)").notNull().withDefault("'{}'"))
      .column(Column.of("answered", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("remaining", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("indexed_at", "TIMESTAMP"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_answers_user", "user_id")
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
   * Invitations, and what became of them.
   *
   * The whole point is the funnel: sent, opened, converted. Each stage is a column rather than a
   * derived join, because the question "which invitations are going nowhere" is asked constantly
   * and should not cost a scan of the accounts table.
   *
   * `converted_user` is what ties a member back to the invitation that brought them, which is the
   * number anybody sending invitations actually wants -- not how many were sent, but how many
   * became somebody who signed in.
   */
  public static final Table INVITES_TABLE = Table.named(INVITES)
      .column(Column.id("id"))
      // the address it was sent to, normalized the same way an account address is
      .column(Column.of("email", "VARCHAR(320)").notNull())
      // the unguessable token in the link and the pixel; unique per invitation
      .column(Column.of("token", "VARCHAR(64)").notNull().unique())
      .column(Column.of("note", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("created_by_email", "VARCHAR(320)").notNull().withDefault("''"))
      // what the message calls them. Written at creation rather than joined at send: the name in
      // an invitation should be the one they had when they sent it, and a name that changed after
      // the fact must not rewrite a message that already went out.
      .column(Column.of("created_by_name", "VARCHAR(128)").notNull().withDefault("''"))
      // when the invitation was actually mailed, which is not the same as when it was written
      .column(Column.of("sent_at", "TIMESTAMP"))
      .column(Column.of("send_detail", "VARCHAR(512)").notNull().withDefault("''"))
      // the first and last time the tracking pixel was fetched
      .column(Column.of("opened_at", "TIMESTAMP"))
      .column(Column.of("last_opened_at", "TIMESTAMP"))
      .column(Column.of("opens", "INTEGER").notNull().withDefault("0"))
      // and the first and last time somebody followed the link. A click is worth much more than an
      // open: an open can be a mail client fetching images by itself, and a click is a person
      // deciding. "Opened but never clicked" and "clicked but never joined" are different problems
      // -- the first is a message that did not land, the second is a sign-up form that did not.
      .column(Column.of("clicked_at", "TIMESTAMP"))
      .column(Column.of("last_clicked_at", "TIMESTAMP"))
      .column(Column.of("clicks", "INTEGER").notNull().withDefault("0"))
      // and the account it turned into, if it did
      .column(Column.of("converted_at", "TIMESTAMP"))
      .column(Column.of("converted_user", "BIGINT"))
      .column(Column.of("revoked_at", "TIMESTAMP"))
      .index("idx_invites_token", "token")
      // the engagement loop: a welcome, a friendly nudge, an apology. Which touch has gone out and
      // when the next one is due, so a restart cannot send Tuesday's reminder twice.
      .column(Column.of("touches", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("last_touch_at", "TIMESTAMP"))
      .column(Column.of("next_touch_at", "TIMESTAMP"))
      .index("idx_invites_email", "email")
      .build();

  /**
   * A post on the board.
   *
   * Posts expire by default, which is the decision the whole feature is shaped around. A discussion
   * board that keeps everything forever becomes an archive nobody reads and a liability somebody
   * eventually has to think about; one where threads age out stays a conversation. `expires_at` is
   * set when the post is written, from the domain's default, and an admin can push it out or take
   * it away entirely for the handful of posts worth keeping.
   *
   * `watchers` is a JSON array of user ids in one column rather than a join table. At this scale a
   * thread has tens of watchers, the list is read on every comment and written on some of them, and
   * a row per watcher would turn one update into a query, a diff and a batch. Packing it means the
   * whole watcher set arrives with the post.
   *
   * `comment_count` and `last_activity_at` are denormalized for the same reason: a feed of forty
   * posts should be one query, not forty-one.
   */
  public static final Table POSTS_TABLE = Table.named(POSTS)
      .column(Column.id("id"))
      .column(Column.of("author_id", "BIGINT").notNull())
      // kept alongside the id so a feed needs no join, and so a removed account does not blank the
      // authorship of a conversation other people are still having
      .column(Column.of("author_email", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("title", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("body", "VARCHAR(65536)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      // when the conversation last moved, which is what the feed sorts on
      .column(Column.of("last_activity_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      // null means it never expires; an admin decision, not the default
      .column(Column.of("expires_at", "TIMESTAMP"))
      .column(Column.of("comment_count", "INTEGER").notNull().withDefault("0"))
      // a JSON array of user ids
      .column(Column.of("watchers", "VARCHAR(16384)").notNull().withDefault("'[]'"))
      .column(Column.of("pinned", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("locked", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("removed_at", "TIMESTAMP"))
      // shown next to the byline. A post that changed silently is a small lie to everybody who
      // already read it and replied to what it used to say
      .column(Column.of("edited_at", "TIMESTAMP"))
      .index("idx_posts_activity", "last_activity_at")
      .index("idx_posts_expires", "expires_at")
      .build();

  /**
   * One comment, in a thread.
   *
   * `path` is what makes threading one query instead of a recursion: a dotted sequence of
   * zero-padded positions ("0001.0003"), so ordering by it yields the whole tree already in reading
   * order, with depth falling out of the number of segments. The alternative -- fetching a level,
   * then its children, then theirs -- is a query per level and a comment box that gets slower as a
   * conversation gets interesting.
   */
  public static final Table COMMENTS_TABLE = Table.named(COMMENTS)
      .column(Column.id("id"))
      /*
       * What this is a comment on.
       *
       * `post` (the board), `event` or `place`. It defaults to `post` on purpose: every row that
       * existed before this column did was a board comment, and a default is the only backfill an
       * upgrader can perform without knowing what it is looking at.
       *
       * The id beside it was called `post_id` for exactly as long as it only ever held one, and
       * then kept the name through two more kinds because the upgrader could not rename anything.
       * It can now, so the name is true again -- see {@link Column#renamedFrom}.
       */
      .column(Column.of("subject_kind", "VARCHAR(16)").notNull().withDefault("'post'"))
      .column(Column.of("subject_id", "BIGINT").notNull().renamedFrom("post_id"))
      .column(Column.of("parent_id", "BIGINT"))
      // the sort key for the whole tree; see above
      .column(Column.of("path", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("depth", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("author_id", "BIGINT").notNull())
      .column(Column.of("author_email", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("body", "VARCHAR(16384)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      // a removed comment keeps its row so the replies underneath it do not become orphans
      .column(Column.of("removed_at", "TIMESTAMP"))
      .column(Column.of("edited_at", "TIMESTAMP"))
      .index("idx_comments_subject", "subject_id")
      .index("idx_comments_path", "path")
      .build();

  /**
   * Somebody's inbox.
   *
   * Notifications expire like the posts they are about -- an inbox that accumulates forever is one
   * nobody opens. The text is written at the moment it happens rather than rendered later, because
   * a notification about a comment that has since been removed should still say what it said.
   */
  public static final Table NOTIFICATIONS_TABLE = Table.named(NOTIFICATIONS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      // reply | mention | post; the kind decides the wording and, later, the delivery rule
      .column(Column.of("kind", "VARCHAR(32)").notNull().withDefault("'reply'"))
      .column(Column.of("post_id", "BIGINT"))
      .column(Column.of("comment_id", "BIGINT"))
      // what this is about, when the thing is not a post. Its own column rather than a reused one,
      // because a column holding two unrelated things is a bug with a schedule.
      .column(Column.of("subject", "VARCHAR(80)").notNull().withDefault("''"))
      // what the note calls whoever did it. It held an address until members stopped being shown
      // each other's addresses, and the name followed the value rather than outliving it.
      .column(Column.of("actor_name", "VARCHAR(320)").notNull().withDefault("''")
          .renamedFrom("actor_email"))
      .column(Column.of("text", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("read_at", "TIMESTAMP"))
      .column(Column.of("expires_at", "TIMESTAMP"))
      // when it left the building. NULL means nothing has been sent about this yet, which is what
      // the notifier looks for -- so a delivery that happened is a fact on the row rather than a
      // window of time somebody has to reason about after a restart
      .column(Column.of("notified_at", "TIMESTAMP"))
      .index("idx_notifications_user", "user_id")
      .build();

  /**
   * What people think of something, and what they want somebody to look at.
   *
   * <b>One table for votes and flags, because they are the same shape and different weights.</b> A
   * row is one person's opinion about one thing: up, down, or "somebody should look at this". Two
   * tables would be two sets of the same uniqueness rule, and the uniqueness rule is the whole
   * correctness of a vote -- one person, one opinion, changeable.
   *
   * <b>Attributed rather than anonymous.</b> Every row knows who cast it, which is what makes
   * changing your mind possible and what makes a flag answerable: a moderator looking at something
   * flagged four times needs to know whether that is four people or one person and three tabs. It
   * is never shown to anybody but the moderators.
   *
   * The subject is the same `board.Subject` a comment uses -- post, event or place -- plus
   * `comment`, so a single comment inside a thread can be voted on and flagged on its own. That is
   * the granularity moderation actually needs: the problem is almost never the whole thread.
   */
  public static final Table SIGNALS_TABLE = Table.named(SIGNALS)
      .column(Column.id("id"))
      // post | comment | event | place
      .column(Column.of("subject_kind", "VARCHAR(16)").notNull().withDefault("'post'"))
      .column(Column.of("subject_id", "BIGINT").notNull())
      .column(Column.of("user_id", "BIGINT").notNull())
      // up | down | flag
      .column(Column.of("kind", "VARCHAR(8)").notNull().withDefault("'up'"))
      // why they flagged it, in their words. Empty for a vote, which needs no explanation.
      .column(Column.of("reason", "VARCHAR(512)").notNull().withDefault("''"))
      // a flag that somebody has dealt with keeps its row: the record of what was looked at and
      // what was decided is worth more than a tidy table
      .column(Column.of("cleared_at", "TIMESTAMP"))
      .column(Column.of("cleared_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_signals_subject", "subject_kind", "subject_id")
      .index("idx_signals_open", "kind", "cleared_at")
      // one person, one opinion of each kind, on each thing -- changeable, never doubled
      .unique("uq_signals_one", "subject_kind", "subject_id", "user_id", "kind")
      .build();

  /**
   * How somebody wants to hear about things, one row per person.
   *
   * Two settings rather than one, because there are two genuinely different events. A reply
   * directly to you is a conversation waiting on an answer; activity in a thread you are watching
   * is news. Collapsing them means either mailing somebody about every comment in a busy thread or
   * making them wait a day to learn that a question was addressed to them.
   *
   * A person with no row is not a person with no preferences -- {@link
   * io.hearth.board.NotifyPrefs#DEFAULTS} is the answer, and a row is written the first time
   * somebody changes something. Writing a row per account at sign-up would put a table of defaults
   * on disk that nobody has read.
   */
  public static final Table NOTIFY_PREFS_TABLE = Table.named(NOTIFY_PREFS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull().unique())
      // off | immediate | daily | weekly, for a reply in a thread they watch
      .column(Column.of("reply_mode", "VARCHAR(16)").notNull().withDefault("'daily'"))
      // and for a reply aimed at them, which deserves to arrive sooner
      .column(Column.of("response_mode", "VARCHAR(16)").notNull().withDefault("'immediate'"))
      .column(Column.of("email", "BOOLEAN").notNull().withDefault("TRUE"))
      // groundwork: there is no SMS provider yet, so this is stored and honoured by nothing
      .column(Column.of("sms", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("phone", "VARCHAR(32)"))
      // the digest watermarks, so a restart cannot turn one daily summary into two
      .column(Column.of("last_daily_at", "TIMESTAMP"))
      .column(Column.of("last_weekly_at", "TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_notify_prefs_user", "user_id")
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
   * Things that are happening, on a day or across a span of them.
   *
   * Days rather than instants. A community event is "Saturday the 14th", possibly "the 14th to the
   * 16th", and storing a timestamp would force a time on everything and a timezone question onto
   * every reader. An optional start_time is a string shown as written, because "doors at 7, music
   * at 8" is a real answer that no time column can hold.
   *
   * Capacity is nullable and means unlimited when absent, which is the common case for a community
   * of under five hundred people meeting in somebody's back room.
   */
  public static final Table CALENDAR_TABLE = Table.named(CALENDAR)
      .column(Column.id("id"))
      .column(Column.of("title", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("body", "VARCHAR(65536)").notNull().withDefault("''"))
      .column(Column.of("location", "VARCHAR(512)").notNull().withDefault("''"))
      // the address book entry this is at, when it is somewhere the community already knows about.
      // Null means the location is the free text above and nothing more, which is right for a
      // one-off in somebody's garden.
      .column(Column.of("place_id", "BIGINT"))
      // suggested | accepted | declined. An event somebody proposed is the same row as an event
      // somebody made, because it becomes one by being accepted -- a separate table would mean
      // copying every field across at the moment of approval, and forgetting one.
      .column(Column.of("state", "VARCHAR(16)").notNull().withDefault("'accepted'"))
      .column(Column.of("decided_by", "BIGINT"))
      .column(Column.of("decided_at", "TIMESTAMP"))
      // why it was declined, said to the person who suggested it
      .column(Column.of("decided_note", "VARCHAR(512)").notNull().withDefault("''"))
      // inclusive on both ends; a one-day event has the same date twice
      .column(Column.of("starts_on", "DATE").notNull().withDefault("CURRENT_DATE"))
      .column(Column.of("ends_on", "DATE").notNull().withDefault("CURRENT_DATE"))
      // free text, shown as written: "doors at 7" is more use than 19:00
      .column(Column.of("start_time", "VARCHAR(64)").notNull().withDefault("''"))
      // null means no limit
      .column(Column.of("capacity", "INTEGER"))
      .column(Column.of("published", "BOOLEAN").notNull().withDefault("FALSE"))
      // Can somebody who is not a member come?
      //
      // Off by default, because a community's calendar is its own business and a public event is a
      // decision somebody makes about one evening rather than a setting they leave on. What it
      // changes is who may read the event and its file: with it on, an answer arriving from an
      // address nobody here recognises is written down instead of ignored, which is how the people
      // who turned up to a thing become the people who were asked to join.
      .column(Column.of("open_to_public", "BOOLEAN").notNull().withDefault("FALSE"))
      // denormalized so a month view is one query rather than one per event
      .column(Column.of("going_count", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("maybe_count", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("waitlist_count", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("created_by_email", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("cancelled_at", "TIMESTAMP"))
      // The identity of this event to every calendar program in the world.
      //
      // Generated once and never changed, because that is what UID means: a REPLY arriving by
      // email six weeks later says only this string, and an event whose uid moved is an event no
      // answer can find its way back to. `sequence` is the other half -- it goes up whenever the
      // details change, and a calendar client ignores an update whose sequence is not newer.
      .column(Column.of("uid", "VARCHAR(190)").notNull().withDefault("''"))
      .column(Column.of("sequence", "INTEGER").notNull().withDefault("0"))
      // when the invitations for the current sequence went out, so a reschedule can tell that it
      // owes everybody a fresh one
      .column(Column.of("invited_at", "TIMESTAMP"))
      .index("idx_calendar_starts", "starts_on")
      .index("idx_calendar_uid", "uid")
      .build();

  /**
   * One person's answer about one event.
   *
   * A row per person rather than a packed list like the board's watchers, because this one is
   * queried from both ends: an event needs its guest list, and a person needs the events they said
   * yes to. Packing would make the second question a scan of every event.
   *
   * The waitlist is a state on this row rather than a separate table, so promoting somebody is one
   * update and cannot leave them on two lists at once.
   */
  public static final Table RSVPS_TABLE = Table.named(RSVPS)
      .column(Column.id("id"))
      .column(Column.of("event_id", "BIGINT").notNull())
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("user_email", "VARCHAR(320)").notNull().withDefault("''"))
      // going | maybe | not | waitlist
      .column(Column.of("answer", "VARCHAR(16)").notNull().withDefault("'going'"))
      // how many they are bringing, themselves included
      .column(Column.of("party", "INTEGER").notNull().withDefault("1"))
      .column(Column.of("note", "VARCHAR(512)").notNull().withDefault("''"))
      // how the answer arrived: `web` from a button here, `email` from a calendar program replying
      // to the invitation. Worth keeping because the two need different follow-ups -- somebody who
      // answered from their calendar may never have seen this site.
      .column(Column.of("source", "VARCHAR(16)").notNull().withDefault("'web'"))
      // a time they would rather it happened, from a calendar program's COUNTER. Not a decision:
      // the organiser accepts it or does not, and until then the event has not moved.
      .column(Column.of("proposed_on", "DATE"))
      .column(Column.of("proposed_time", "VARCHAR(64)").notNull().withDefault("''"))
      // said they were coming and was not there. Recorded by a person who was, never inferred.
      .column(Column.of("no_show", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_rsvps_event", "event_id")
      .index("idx_rsvps_user", "user_id")
      .build();

  /**
   * Somebody outside the community who said they were coming.
   *
   * A separate table from `rsvps` rather than a nullable `user_id`, because everything in that one
   * is keyed on a member: the seat counting, the guest list, the no-show marking, the export and
   * the erasure. Letting a row in there stand for somebody with no account would mean every one of
   * those queries growing a clause, and the first one that forgot would be the one that counted a
   * stranger into a room with twelve chairs.
   *
   * What this is really for is the invitation chain. An answer from an address nobody recognises is
   * the strongest signal a community gets that somebody wants in -- they found out about the thing,
   * they said they were coming, and nobody has ever asked them to join. This is the list an
   * administrator reads before deciding whether to.
   */
  public static final Table PUBLIC_RSVPS_TABLE = Table.named(PUBLIC_RSVPS)
      .column(Column.id("id"))
      .column(Column.of("event_id", "BIGINT").notNull())
      .column(Column.of("email", "VARCHAR(320)").notNull())
      // whatever their calendar program said their name was, which is usually a real one
      .column(Column.of("name", "VARCHAR(190)").notNull().withDefault("''"))
      // going | maybe | not
      .column(Column.of("answer", "VARCHAR(16)").notNull().withDefault("'going'"))
      .column(Column.of("party", "INTEGER").notNull().withDefault("1"))
      .column(Column.of("note", "VARCHAR(512)").notNull().withDefault("''"))
      // email from a calendar reply, web from the page itself
      .column(Column.of("source", "VARCHAR(16)").notNull().withDefault("'email'"))
      // when somebody here sent them an invitation to join, so the list can say who has been asked
      .column(Column.of("invited_at", "TIMESTAMP"))
      // and when they joined, at which point this became an ordinary RSVP and stopped being a lead
      .column(Column.of("converted_at", "TIMESTAMP"))
      .column(Column.of("converted_user_id", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_public_rsvps_event", "event_id")
      .index("idx_public_rsvps_email", "email")
      .build();

  /**
   * When somebody would ideally be free, week after week.
   *
   * <b>A weekly shape, not a diary.</b> "Tuesday evenings" is a fact about a person that stays true
   * for years; "the 14th at 7" is a fact about one Tuesday and belongs in a calendar. Keeping the
   * two apart is what makes this worth filling in once -- and it is why the exceptions come from
   * somebody's own calendar rather than from asking them to maintain a second one here.
   *
   * Minutes from midnight rather than a time, because the arithmetic this feeds is arithmetic on
   * minutes and a TIME column would be converted on every read of every row.
   */
  public static final Table AVAILABILITY_TABLE = Table.named(AVAILABILITY)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      // ISO: 1 is Monday, 7 is Sunday. Same as java.time.DayOfWeek, so nothing has to translate.
      .column(Column.of("day_of_week", "INTEGER").notNull().withDefault("1"))
      .column(Column.of("starts_at", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("ends_at", "INTEGER").notNull().withDefault("0"))
      // "after the school run", "only if it is not a match night" -- shown to whoever is planning
      .column(Column.of("note", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_availability_user", "user_id")
      .build();

  /**
   * A calendar somebody pointed us at, so the exceptions look after themselves.
   *
   * The whole value of this is that it is *not* maintained. Somebody pastes the address of the
   * calendar they already keep, and the weeks they are away stop being weeks the community plans
   * around them.
   */
  public static final Table CALENDAR_LINKS_TABLE = Table.named(CALENDAR_LINKS)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      .column(Column.of("url", "VARCHAR(1024)").notNull())
      // what they call it: "work", "the band". Only ever shown to them.
      .column(Column.of("label", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("active", "BOOLEAN").notNull().withDefault("TRUE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_calendar_links_user", "user_id")
      .build();

  /**
   * What one calendar said, the last time anybody asked it.
   *
   * <b>A cache with a table, because the thing it is caching is somebody else's server.</b> A grid
   * that fetched on render would be a page whose speed depended on Google having a good day, and a
   * community of two hundred people opening it would be two hundred requests to the same handful of
   * hosts. So it is pulled once a day for everybody, in the small hours, and every read after that
   * is a read of this table.
   *
   * The blob holds busy blocks and never event titles. What somebody is doing on Thursday is not
   * this community's business; that they cannot come is.
   */
  public static final Table CALENDAR_CACHE_TABLE = Table.named(CALENDAR_CACHE)
      .column(Column.id("id"))
      .column(Column.of("user_id", "BIGINT").notNull())
      // sha-256 of the url: the url itself can be a secret address with a token in it, and this is
      // what the lookup keys on so that the long value is never in an index or a log
      .column(Column.of("url_hash", "VARCHAR(64)").notNull())
      .column(Column.of("fetched_at", "TIMESTAMP"))
      // when this stops being believed; the daily pass rewrites it, and a miss is simply no data
      .column(Column.of("expires_at", "TIMESTAMP"))
      // ok | error -- an error is kept and shown to the person whose calendar it is, because a link
      // that silently stopped working is a member the grid quietly starts lying about
      .column(Column.of("status", "VARCHAR(16)").notNull().withDefault("'ok'"))
      .column(Column.of("detail", "VARCHAR(512)").notNull().withDefault("''"))
      // a JSON array of [startEpochSecond, endEpochSecond] pairs, and nothing else
      .column(Column.of("busy", "VARCHAR(262144)").notNull().withDefault("'[]'"))
      .column(Column.of("blocks", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_calendar_cache_user", "user_id")
      .index("idx_calendar_cache_url", "url_hash")
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
   * A suggested edit, waiting for somebody to say yes.
   *
   * The version history looking forwards. A proposal holds the same canonical document a version
   * holds, so approving one is a save and the history records it exactly like any other -- which
   * means an approved proposal is indistinguishable afterwards from an edit somebody made directly,
   * and that is correct. What was proposed, by whom, and who approved it lives here.
   *
   * The base version is stored so an approval can notice the page moved underneath it. Silently
   * applying a suggestion written against last week's text is how a review queue reverts somebody
   * else's work while looking like it worked.
   */
  public static final Table PROPOSALS_TABLE = Table.named(PROPOSALS)
      .column(Column.id("id"))
      // null means the proposal is for a brand new page
      .column(Column.of("content_id", "BIGINT"))
      .column(Column.of("uri", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("title", "VARCHAR(256)").notNull().withDefault("''"))
      // the whole proposed page, in the same canonical form a version uses
      .column(Column.of("document", "VARCHAR(1048576)").notNull().withDefault("''"))
      // which version it was written against, so a stale one can be spotted
      .column(Column.of("base_version", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("note", "VARCHAR(1024)").notNull().withDefault("''"))
      // open | approved | declined | withdrawn
      .column(Column.of("state", "VARCHAR(16)").notNull().withDefault("'open'"))
      .column(Column.of("proposed_by", "BIGINT"))
      .column(Column.of("proposed_by_email", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("decided_at", "TIMESTAMP"))
      .column(Column.of("decided_by", "BIGINT"))
      .column(Column.of("decided_by_email", "VARCHAR(320)").notNull().withDefault("''"))
      .column(Column.of("decision_note", "VARCHAR(1024)").notNull().withDefault("''"))
      .index("idx_proposals_state", "state")
      .index("idx_proposals_content", "content_id")
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

  /**
   * A kind of place, and what a community wants to record about that kind.
   *
   * The dynamic half is the point. "Ranch" wants grass-finished, cuts sold, whether they deliver;
   * "vendor" wants what the discount is and who to ask for; "venue" wants how many fit and whether
   * there is parking. None of those belong in this schema, and a community that had to ask for a
   * column would simply keep the information in somebody's head instead. So the fields are declared
   * per type as a JSON blob, exactly the way a content template declares the boxes its editor
   * shows, and the same {@link io.hearth.content.TemplateField} reads both.
   */
  public static final Table PLACE_TYPES_TABLE = Table.named(PLACE_TYPES)
      .column(Column.id("id"))
      // lowercase and dashed; it is the URL segment for the public listing
      .column(Column.of("slug", "VARCHAR(64)").notNull().unique())
      .column(Column.of("label", "VARCHAR(64)").notNull().withDefault("''"))
      // the plural, because "3 ranches" reads better than "3 ranchs"
      .column(Column.of("plural", "VARCHAR(64)").notNull().withDefault("''"))
      .column(Column.of("description", "VARCHAR(1024)").notNull().withDefault("''"))
      // a JSON array of TemplateField: what this kind of place has, beyond an address
      .column(Column.of("fields", "VARCHAR(16384)").notNull().withDefault("'[]'"))
      // which content template renders one of these; empty means the built-in page
      .column(Column.of("template_name", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("icon", "VARCHAR(32)").notNull().withDefault("''"))
      .column(Column.of("published", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("sort", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_place_types_slug", "slug")
      .build();

  /**
   * One address, and whatever its type says to record about it.
   *
   * The address is deliberately loose text rather than a parsed structure. Addresses are not a
   * schema -- they differ by country, half of the useful ones are "the barn behind the white
   * house", and a community that has to fight a form to write one down will not write it down.
   * Coordinates are separate and optional, for the one thing a string cannot do: point at a map.
   */
  public static final Table PLACES_TABLE = Table.named(PLACES)
      .column(Column.id("id"))
      .column(Column.of("type_slug", "VARCHAR(64)").notNull())
      // unique within its type, so /places/ranch/oak-hill is stable when the name is edited
      .column(Column.of("slug", "VARCHAR(128)").notNull())
      .column(Column.of("name", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("address", "VARCHAR(1024)").notNull().withDefault("''"))
      .column(Column.of("locality", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("region", "VARCHAR(128)").notNull().withDefault("''"))
      .column(Column.of("postcode", "VARCHAR(32)").notNull().withDefault("''"))
      .column(Column.of("country", "VARCHAR(64)").notNull().withDefault("''"))
      .column(Column.of("latitude", "DOUBLE PRECISION"))
      .column(Column.of("longitude", "DOUBLE PRECISION"))
      // the same bookkeeping a member's address carries, for the same reason and read through the
      // same record: an address in the book that cannot be found is a fact worth showing an admin,
      // and one the service could not be asked about is a fact worth retrying
      .column(Column.of("geo_state", "VARCHAR(16)").notNull().withDefault("''"))
      .column(Column.of("geo_service", "VARCHAR(32)").notNull().withDefault("''"))
      .column(Column.of("geo_tries", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("geo_next_at", "TIMESTAMP"))
      .column(Column.of("geo_tried_at", "TIMESTAMP"))
      .column(Column.of("geo_note", "VARCHAR(256)").notNull().withDefault("''"))
      .column(Column.of("url", "VARCHAR(512)").notNull().withDefault("''"))
      .column(Column.of("phone", "VARCHAR(64)").notNull().withDefault("''"))
      .column(Column.of("email", "VARCHAR(320)").notNull().withDefault("''"))
      // the values for the fields this place's type declares, keyed by field name
      .column(Column.of("fields", "VARCHAR(65536)").notNull().withDefault("'{}'"))
      // markdown, shown on the place's own page
      .column(Column.of("body", "VARCHAR(65536)").notNull().withDefault("''"))
      .column(Column.of("published", "BOOLEAN").notNull().withDefault("FALSE"))
      // the same asymmetry content has: invisible to AI, and refused out loud on a write
      .column(Column.of("human_only", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_by", "BIGINT"))
      .index("idx_places_type", "type_slug")
      .unique("uq_places_type_slug", "type_slug", "slug")
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

  /**
   * A question a conversation is trying to settle.
   *
   * <b>It belongs to a post rather than standing on its own.</b> A vote with no discussion around
   * it is a survey, and this server already has one of those; what makes this different is that it
   * lives inside an argument people are having, and the argument is where the reasons are. Deleting
   * the conversation takes the vote with it, which is right -- a decision with its reasoning
   * removed is not worth keeping.
   *
   * <b>Two kinds, and the second is two votes in one.</b> A `choice` poll is a straight either-or.
   * A `schedule` poll asks two questions at once -- which day, and which place -- because those are
   * not separable in practice: a hall that is free on Thursday and a friend's kitchen that is free
   * on Saturday is one decision, not two. Its answer becomes an event, which is why creating one
   * needs the permission to create events.
   */
  public static final Table POLLS_TABLE = Table.named(POLLS)
      .column(Column.id("id"))
      .column(Column.of("post_id", "BIGINT").notNull())
      // 'choice' or 'schedule'
      .column(Column.of("kind", "VARCHAR(16)").notNull())
      .column(Column.of("question", "VARCHAR(512)").notNull().withDefault("''"))
      // 'open', 'closed', 'converted', 'cancelled'
      .column(Column.of("state", "VARCHAR(16)").notNull().withDefault("'open'"))
      // when it stops taking votes and is counted. Null means it waits for somebody to close it --
      // which is a real thing to want for a question with no deadline, and a bad default for one
      // that has to become an event.
      .column(Column.of("closes_at", "TIMESTAMP"))
      // may anybody add another time? A poll where only the author can is a poll that measures the
      // author's imagination; one where anybody can is a poll that can be filled with noise. It is
      // a choice per poll rather than a rule for the server.
      .column(Column.of("open_options", "BOOLEAN").notNull().withDefault("TRUE"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("closed_at", "TIMESTAMP"))
      // what it turned into, and what went wrong if it did not
      .column(Column.of("event_id", "BIGINT"))
      .column(Column.of("outcome", "VARCHAR(512)").notNull().withDefault("''"))
      .index("idx_polls_post", "post_id")
      .index("idx_polls_state", "state")
      .build();

  /**
   * One thing that can be voted for.
   *
   * <b>Three facets in one table</b> -- a plain choice, a day, a place -- because they are the same
   * row with a different column filled in, and three tables would be three vote tables and three
   * counting functions, the third of which would eventually disagree with the first two.
   *
   * <b>Removed rather than deleted.</b> Somebody's vote refers to it, and a removed option whose
   * votes vanished would silently change every other option's share. It stops being offered and
   * stops being counted, and the row stays so the history of the decision is readable.
   */
  public static final Table POLL_OPTIONS_TABLE = Table.named(POLL_OPTIONS)
      .column(Column.id("id"))
      .column(Column.of("poll_id", "BIGINT").notNull())
      // 'choice', 'time' or 'place'
      .column(Column.of("facet", "VARCHAR(16)").notNull())
      .column(Column.of("label", "VARCHAR(256)").notNull().withDefault("''"))
      // a day, for a time option. A day and not an instant, for the same reason the calendar is
      // days: "Saturday the 14th" is what a community decides, and a timestamp forces a clock time
      // onto everything and a timezone question onto every reader.
      .column(Column.of("on_day", "DATE"))
      // free text beside the day, shown as written: "from 7", "afternoon"
      .column(Column.of("at_time", "VARCHAR(64)").notNull().withDefault("''"))
      // a place option is a pointer into the address book and never free text: the whole value of
      // it is that the winner can become an event's location without anybody retyping an address
      .column(Column.of("place_id", "BIGINT"))
      .column(Column.of("position", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("added_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("removed_at", "TIMESTAMP"))
      .index("idx_poll_options_poll", "poll_id")
      .build();

  /**
   * One person's opinion of one option.
   *
   * <b>The weight is what makes the two shapes one table.</b> A choice or a place is either-or, so
   * a person has at most one row per facet and voting again moves it. A time is not: a week has
   * several evenings and somebody can be free on three of them, so every time option takes its own
   * up, down or nothing -- which is what produces a histogram rather than a single peak. Nothing
   * is stored for "no opinion"; the absence of a row is the absence of an opinion, and writing a
   * zero would make "did not say" and "said neither" the same fact.
   */
  public static final Table POLL_VOTES_TABLE = Table.named(POLL_VOTES)
      .column(Column.id("id"))
      .column(Column.of("poll_id", "BIGINT").notNull())
      .column(Column.of("option_id", "BIGINT").notNull())
      .column(Column.of("facet", "VARCHAR(16)").notNull())
      .column(Column.of("user_id", "BIGINT").notNull())
      // +1 or -1; a time option takes both, a choice or a place only ever takes +1
      .column(Column.of("weight", "INTEGER").notNull().withDefault("1"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_poll_votes_poll", "poll_id")
      .index("idx_poll_votes_option", "option_id")
      .build();

  /**
   * Somewhere tasks live, and what that somebody calls them.
   *
   * <b>Owned by a person or by the community, and the difference is one nullable column.</b> A
   * member's training plan and a committee's list of things to do before the summer party are the
   * same shape -- a handful of items, some of them repeating, each with a record of when it was
   * last done. Two tables would be two of everything downstream, and the second would grow a chart
   * the first never got.
   *
   * <b>The words are the project's.</b> One community's "tasks" are another's "exercises",
   * "chores", "jobs" or "steps", and a screen that insists on one of those is a screen people have
   * to translate every time they read it. The phases are the project's too: an empty list is a
   * plain todo list, and a list of names is a board.
   */
  public static final Table PROJECTS_TABLE = Table.named(PROJECTS)
      .column(Column.id("id"))
      // null means the community's own; anything else belongs to one person and nobody else reads
      // it without being told to
      .column(Column.of("owner_id", "BIGINT"))
      .column(Column.of("name", "VARCHAR(128)").notNull())
      .column(Column.of("slug", "VARCHAR(128)").notNull())
      .column(Column.of("summary", "VARCHAR(1024)").notNull().withDefault("''"))
      // what this project calls one of its items, and several of them
      .column(Column.of("task_word", "VARCHAR(32)").notNull().withDefault("'task'"))
      .column(Column.of("tasks_word", "VARCHAR(32)").notNull().withDefault("'tasks'"))
      // a JSON array of phase names. Empty is a todo list; ["To do","Doing","Done"] is a board.
      .column(Column.of("phases", "VARCHAR(1024)").notNull().withDefault("'[]'"))
      // How long a finished item stays on the screen before it drops out of the way.
      //
      // It is never deleted -- the entries are the whole point, and a chart of the last six months
      // needs six months of them. This decides what is *in front of somebody today*, which is a
      // different question and the one a phone screen is actually asking.
      .column(Column.of("hide_done_hours", "INTEGER").notNull().withDefault("24"))
      .column(Column.of("archived", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_projects_owner", "owner_id")
      .build();

  /**
   * What a task <i>is</i>, as opposed to one occasion of doing it.
   *
   * <b>The definition is the durable thing and the doing is the disposable one.</b> "Bulgarian
   * split squat" is a fact about the world: how it is performed, what to watch for, what it is
   * measured in. Tuesday's three sets of it are a fact about Tuesday. Keeping them in one row
   * meant either rewriting the instructions on every occasion or having no instructions at all,
   * and it made "how has this gone over six months" a question about strings.
   *
   * <b>A definition can have a parent, which is what makes sharing work.</b> A community publishes
   * "Bulgarian split squat" once, with the form notes and a link to a video; a member's own
   * definition points at it and may override the target. Improving the community's notes improves
   * everybody's, and somebody who wants it slightly different has somewhere to put that without
   * forking the whole thing.
   */
  public static final Table TASK_DEFS_TABLE = Table.named(TASK_DEFS)
      .column(Column.id("id"))
      // null for a shared definition anybody may adopt; otherwise whose it is
      .column(Column.of("owner_id", "BIGINT"))
      // the definition this one derives from, if any
      .column(Column.of("parent_id", "BIGINT"))
      .column(Column.of("name", "VARCHAR(128)").notNull())
      .column(Column.of("slug", "VARCHAR(128)").notNull())
      // one of Measure: none, weight_reps, bodyweight_reps, weighted_bodyweight, duration,
      // duration_weight, distance_duration, weight_distance
      .column(Column.of("measure", "VARCHAR(32)").notNull().withDefault("'none'"))
      .column(Column.of("summary", "VARCHAR(512)").notNull().withDefault("''"))
      // How it is done, in markdown. This is the field the whole definition/instance split exists
      // for: enough for somebody to look up the form without leaving the page, written once.
      .column(Column.of("instructions", "VARCHAR(16384)").notNull().withDefault("''"))
      // where the form was learned from -- a video, an article
      .column(Column.of("reference_url", "VARCHAR(512)").notNull().withDefault("''"))
      // what it is for, as free tags: "legs", "admin", "before the party"
      .column(Column.of("tags", "VARCHAR(512)").notNull().withDefault("''"))
      // the default sets and targets, as JSON, so an instance starts filled in
      .column(Column.of("target", "VARCHAR(2048)").notNull().withDefault("'{}'"))
      // How long to rest between sets of this, in seconds; 0 for "no particular rest".
      //
      // On the definition rather than on the task because it is a property of the movement -- a
      // heavy squat wants three minutes and a set of curls wants forty seconds, and that is true
      // in every routine the movement ever appears in. Somebody who wants it different for one
      // block takes a copy of the definition, which is what taking a copy is for.
      .column(Column.of("rest_seconds", "INTEGER").notNull().withDefault("0"))
      // may anybody in this community adopt it?
      .column(Column.of("shared", "BOOLEAN").notNull().withDefault("FALSE"))
      .column(Column.of("retired_at", "TIMESTAMP"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_task_defs_owner", "owner_id")
      .index("idx_task_defs_parent", "parent_id")
      .build();

  /**
   * One item in one project: a thing to do, or a thing to do again.
   *
   * <b>It carries no measurements.</b> Those are entries, because a task can repeat -- and a row
   * that held "last weight" would be a row that forgot the week before. What it holds is where it
   * is: which phase, in what order, whether it is done, and when.
   */
  public static final Table TASKS_TABLE = Table.named(TASKS)
      .column(Column.id("id"))
      .column(Column.of("project_id", "BIGINT").notNull())
      // null for a one-off nobody will do twice; otherwise what it is an occasion of
      .column(Column.of("def_id", "BIGINT"))
      .column(Column.of("title", "VARCHAR(256)").notNull())
      .column(Column.of("notes", "VARCHAR(4096)").notNull().withDefault("''"))
      // which of the project's phases it sits in; empty on a plain todo list
      .column(Column.of("phase", "VARCHAR(64)").notNull().withDefault("''"))
      // Things done together. Everything on one project sharing a group name is one group, which
      // is a column rather than a table because a group has no properties of its own worth
      // keeping -- it is a name and the fact that two rows share it.
      .column(Column.of("group_name", "VARCHAR(64)").notNull().withDefault("''"))
      // 'related' -- a superset: alternate between them, and the rest comes after the round rather
      // than after each set. 'sequenced' -- a circuit or a warm-up into a working set, where the
      // order is the point. Empty when this is not in a group.
      .column(Column.of("group_mode", "VARCHAR(16)").notNull().withDefault("''"))
      .column(Column.of("position", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("done_at", "TIMESTAMP"))
      // A task that comes back. Days between repeats: 7 is weekly, 0 is a one-off. When it is
      // ticked, the next one is due this many days later -- which is what makes a routine a routine
      // rather than a list somebody rewrites every Sunday.
      .column(Column.of("repeat_days", "INTEGER").notNull().withDefault("0"))
      .column(Column.of("due_on", "DATE"))
      .column(Column.of("assigned_to", "BIGINT"))
      .column(Column.of("created_by", "BIGINT"))
      .column(Column.of("created_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .column(Column.of("updated_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_tasks_project", "project_id")
      .index("idx_tasks_def", "def_id")
      .build();

  /**
   * One recorded set, or one occasion of ticking something off.
   *
   * <b>Everything with a timestamp lives here and nothing else does.</b> This is the table a chart
   * is built from, and the reason the split above exists: a definition can be reworded and a task
   * can be deleted without touching what actually happened on a Tuesday in March. `def_id` is
   * copied onto the row for exactly that reason -- history survives its task.
   *
   * <b>The feedback is three numbers and they are the point.</b> How hard, how long, how much good
   * it did, each one to five. Somebody tuning a routine towards high impact for little time cannot
   * do it from weights alone, because the weight only says what happened and not whether it was
   * worth doing. Three separate axes because they come apart: the thing that is exhausting and
   * useless is exactly the thing worth finding.
   */
  public static final Table TASK_ENTRIES_TABLE = Table.named(TASK_ENTRIES)
      .column(Column.id("id"))
      .column(Column.of("task_id", "BIGINT"))
      // kept even when the task goes, so a history outlives the list it was on
      .column(Column.of("def_id", "BIGINT"))
      .column(Column.of("project_id", "BIGINT"))
      .column(Column.of("user_id", "BIGINT").notNull())
      // which set of the occasion this was; 0 for a plain tick
      .column(Column.of("set_index", "INTEGER").notNull().withDefault("0"))
      // the four columns every measure is made of; each null unless its measure asks for it
      .column(Column.of("weight", "DOUBLE PRECISION"))
      .column(Column.of("reps", "INTEGER"))
      .column(Column.of("seconds", "INTEGER"))
      .column(Column.of("distance", "DOUBLE PRECISION"))
      // one to five, or null for "did not say" -- which is different from three
      .column(Column.of("difficulty", "INTEGER"))
      .column(Column.of("time_cost", "INTEGER"))
      .column(Column.of("impact", "INTEGER"))
      .column(Column.of("note", "VARCHAR(1024)").notNull().withDefault("''"))
      // when it happened, which is the whole reason this table exists
      .column(Column.of("recorded_at", "TIMESTAMP").notNull().withDefault("CURRENT_TIMESTAMP"))
      .index("idx_task_entries_user", "user_id")
      .index("idx_task_entries_task", "task_id")
      .index("idx_task_entries_def", "def_id")
      .build();

  public static final List<Table> TABLES =
      List.of(META_TABLE, EMAILS_TABLE, SESSIONS_TABLE, ROLES_TABLE, TEMPLATES_TABLE, CONTENT_TABLE,
          PROFILES_TABLE, QUESTIONS_TABLE, ANSWERS_TABLE, BANS_TABLE, OAUTH_CLIENTS_TABLE,
          CONTENT_VERSIONS_TABLE, INVITES_TABLE, POSTS_TABLE, COMMENTS_TABLE,
          NOTIFICATIONS_TABLE, NOTIFY_PREFS_TABLE, CALENDAR_TABLE, RSVPS_TABLE,
          ROLE_DEFS_TABLE, PROPOSALS_TABLE, PUSH_SUBS_TABLE, PLACE_TYPES_TABLE,
          PLACES_TABLE, THEMES_TABLE, LEGAL_TABLE, SIGNALS_TABLE, SYSTEM_TEMPLATES_TABLE,
          PUBLIC_RSVPS_TABLE, AVAILABILITY_TABLE, CALENDAR_LINKS_TABLE, CALENDAR_CACHE_TABLE,
          ATTACHMENTS_TABLE, POLLS_TABLE, POLL_OPTIONS_TABLE, POLL_VOTES_TABLE,
          PROJECTS_TABLE, TASK_DEFS_TABLE, TASKS_TABLE, TASK_ENTRIES_TABLE, CONFIG_TABLE);

  private Schema() {
  }
}
