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
  public static final int VERSION = 42;

  public static final String EMAILS = "emails";
  public static final String SESSIONS = "sessions";
  public static final String ROLES = "roles";
  public static final String CONTENT = "content";
  public static final String TEMPLATES = "templates";
  public static final String MUTATIONS = "mutations";
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
          CONFIG_TABLE, MUTATIONS_TABLE);

  private Schema() {
  }
}
