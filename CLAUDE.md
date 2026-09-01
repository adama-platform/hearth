# Hearth — Claude Code Instructions

## What this is

**A multi-user platform for a small number of people to coordinate using AI.** One jar, one process,
one config directory, hosting a handful of groups of 100 to 1,000 people each.

Three things that only matter together:

1. **A door.** Accounts, and a human deciding who comes through. Everything else rests on it -- an
   AI acting for a member is only safe because membership means something.
2. **An AI-directed content management system.** The site is a database rather than a folder of
   files, and all of it -- pages, templates, the fields a template declares, the navigation -- is
   addressable by a model over MCP, acting *as* the person who connected it.
3. **An app platform for validating ideas quickly.** A page's body can be a program. The shortest
   path from an idea to something the group can look at, with no build and no deploy.

Around those: files, mail in and out, push, TLS, and the legal documents. Those are plumbing. When a
change has to trade against something, it trades in favour of the three above.

[MISSION.md](MISSION.md) is why it exists and what it refuses to become. [README.md](README.md) is
what it does today.

**It used to be much larger.** A discussion board, a calendar with RSVPs and emailed invitations, an
address book, an availability grid, a members directory, an invitation funnel, projects and a
training log, a live channel and a JSON API were all removed in one pass. None of it was the point,
and the reason is the governing constraint on everything below: **surface that one person cannot
validate is surface they cannot safely operate.** The bar for adding anything back is not "would
this be useful" -- it is "can one person enumerate how this fails".

That bar is sharper now than it was, not softer, because of what the AI half is. A model that can
rewrite the site is only a reasonable thing to offer while a person can still read everything it
did. Small is what makes directed AI safe here; it is not modesty.

No money will ever move through this server. The scale target (100 to 1,000 members) is a design
input: when a choice comes up between a simple approach that works at that scale and a general
approach that scales further, take the simple one and say why in a comment.

## Ground rules

- **A finding gets reproduced, fixed, and then written into the code that fixes it and the test that
  proves it.** There is no standing list of known-broken things and there have been two -- a
  `PROBLEMS.md` and later an `AUDIT.md` -- both closed and both deleted. The reasoning lives where
  somebody will actually meet it: in the comment above the clause that does the work, and in the
  javadoc of the test that fails if it comes back. Reproduce it from the outside first, fix it with
  a test that fails before and passes after, and say in the comment what the wrong version did.
- **What has never been verified is listed under [Not verified](#not-verified) below**, because that
  is different from a defect and gets a different kind of attention.
- This repository is **Hearth**. There are three documents and they are kept true: `CLAUDE.md`,
  `README.md` and `MISSION.md`. `just docs` checks the mechanical parts of all three. There is no
  manual and no API contract any more: the JSON API went with the reduction, and a second document
  describing the same screens as the README is a second place to go stale.
- Java 21. Netty for HTTP, H2 for storage, Mustache for pages, Jackson for JSON, scrypt for
  passwords. Maven, one module. Nothing needs installing alongside the jar -- that is the mission,
  not a detail.
- Two-space indent, braces on the same line, `final`-free locals except where they matter — match
  what's already in `src/main/java/io/hearth/`.
- Comments explain *why*, not *what*. Class javadoc says what the class is for and what it refuses
  to do -- a short paragraph at the top of each class.

## Build and run — use the justfile

`justfile` is the primary interface. **`just validate` is the gate**: clean build, full test suite,
packaged jar, then a live smoke test against the running jar over real HTTP, then the documents
check. Run it before claiming anything works, and after any change to the request path.

```bash
just                      # list recipes
just validate             # THE gate: clean + package (runs tests) + live smoke + suite + docs
just test                 # unit + HTTP tests
just test-one ServerHttpTests
just coverage             # jacoco; fails below the floor (80% line, 70% branch)
just package              # tests + ./hearth.jar
just package-fast         # skip tests; for iterating, never for validating
just run                  # serve the checked-in ./site root on 8080, verbose
just reset-stores         # delete the local databases and start over
just check DIR            # load a config tree and exit; never opens a socket
just docs                 # do the documents still describe this program?
just suite                # did every test actually run?
just release-check        # what a release would refuse, without doing anything
just release 0.2.0        # validate, build a stamped jar, tag, publish to GitHub
just peek blog.example.com   # dump headers + body for one host
just kill                 # clean up stray dev servers
```

`hearth.jar` at the repo root is the deliverable (gitignored; `target/hearth.jar` is the build output that
gets copied there). If a change makes that jar not runnable standalone, it's a bug regardless of
whether tests pass.

New checks belong in the justfile. If a check isn't reachable from `just validate`, it isn't part
of the definition of "working".

## Keeping the documents true

Documentation here does not drift slowly. It breaks in one commit, when a feature lands and the
paragraph describing the old behaviour is still sitting there being wrong. Three have shipped:
`MANUAL.md` described a board moderation screen that did not exist; `README.md` said "no TLS" three
features after TLS landed; every doc kept recommending `--configs`, `--stores` and `--certs` for
weeks after one `--root` replaced them, including a copy-pasteable command the server refuses.

Two mechanisms, because either alone fails.

**`just docs` is part of `just validate`.** `tools/check-docs.sh` checks the parts a machine can:
every local link resolves, every path in the layout exists, every package appears in it, every flag
the docs tell somebody to type is one `Args` accepts, every `` `just <recipe>` `` exists, templates
on disk and in `Templates.PAGES` agree, every admin section is described somewhere, quoted schema
versions match `Schema.VERSION`, every invariant number a comment cites actually exists, and any
test count the docs quote matches what the suite actually ran. It deliberately checks nothing subjective — a false alarm
would be trained away within a week and take the real ones with it. **Add a check here whenever a
new kind of claim starts appearing in the docs.**

**Re-read, don't patch — on a trigger, not a feeling.** The script cannot tell that a paragraph is
describing a feature that now works differently. So sit down and actually read `README.md` and the
affected part of `MANUAL.md`:

- after **any new user-visible feature** — a route, an admin section, a config key;
- after **three features**, whether or not each seemed to need it;
- after **any change to the CLI, the boot sequence, or the schema**;
- before **any commit that claims something is done**.

Reading means opening the document and asking "would somebody following this succeed?", not
grepping for the word you changed. The three failures above would all have been caught by one
person reading one page once.

`README.md` is the vision and the current state, and it is the one that rots fastest because it is
the one that makes claims. Its status line, its road, and its feature descriptions are all
assertions with an expiry date. `MISSION.md` changes rarely — it is why, not what — but when a
commitment there stops being true in the code, that is the most important documentation bug the
project can have, and it gets fixed in the code rather than in the document.

**Releasing.** `just release <version>` is the only way a binary should leave here. It refuses a
dirty tree, a branch other than main, a divergent origin, and a tag that exists -- **and it checks
for a publishing credential before it changes anything**, because an SSH key can push a tag and
cannot create a GitHub release (releases are a REST resource; that API takes a token). Finding that
out after the tag is pushed leaves the repository claiming a release that is not there.

The version lives in one place: `${revision}` in the pom, stamped into the jar's manifest, read back
by `Server.VERSION`. A jar built by hand says `0.0.1-SNAPSHOT` and never a release number, and the
recipe runs `--version` on what it just built and refuses if the answer disagrees -- a binary that
cannot say what it is turns every bug report into archaeology.

It also runs `just third-party` first, because those files are not in git and a release built
without them ships an editor that silently falls back to a textarea.

## Layout

```
src/main/java/io/hearth/
  Server.java                     entry point; boot order IS the security model
  analytics/AccessLog.java    the last 5000 requests, with the queries the dashboard asks
  analytics/Hit.java          one request, as little of it as answers a question later
  analytics/Machine.java          what the box is doing: /proc, and a day of it in memory
  analytics/UserAgents.java   browser/bot classification; unknowns registered verbatim
  async/AsyncQueue.java           one worker, one every 1.5s, a thousand waiting, and backoff on a failure
  attach/AttachmentConfig.java    the allow list, the ceilings, and the hotlink rule
  attach/AttachmentRoutes.java    /attachment/<id>.<ext>, and the one path allowed a big body
  attach/AttachmentStore.java     where the bytes live: three methods, so the second answer is possible
  attach/AttachmentSweep.java     mark and sweep: every column that can hold a url, then the leftovers
  attach/Attachments.java         the record of every upload; folders, tags, and who may read it
  attach/BlobCache.java           the recently-served bytes, bounded by bytes and keeping what is asked for
  attach/DiskAttachments.java     <root>/attachments/<ext>/<id % 100>/<id>.blob, written atomically
  attach/Kinds.java               the closed table of what may be uploaded, and what each thing is
  attach/Uploads.java             multipart, in memory, one file per submission
  auth/Access.java                who is an admin, who is approved, and the config escape hatch
  auth/Accounts.java              users + sessions + codes + policy, per database
  auth/AuthSystem.java            domain -> Accounts
  auth/Bans.java                  refused addresses; cached in memory, invalidated off the bus
  auth/LoginSecurity.java         every policy knob, parsed once at boot
  auth/Passwords.java             scrypt
  auth/PendingCodes.java          emailed codes in flight; memory only, never on disk
  auth/Permission.java            the closed list of things anybody can be allowed to do
  auth/RoleDefs.java              what a role means; admin is built in and refuses to be edited
  auth/Roles.java                 who holds which role
  auth/SessionRecord.java     one live login; the token only ever as a hash
  auth/Sessions.java              write-through cache, reaper, session cap
  auth/Tokens.java                session tokens, code generation, email normalization
  auth/UserRecord.java        one account, and what it is allowed to be
  auth/Users.java                 the emails table, including approval
  cache/CachePolicy.java          ttl + ceiling, configured as a catch-all
  cache/Caches.java               the per-domain policies
  cache/TtlCache.java             the cache; invalidateIf() is the cascade
  certs/Acme.java                 the CA seam, so renewal logic is testable without a network
  certs/AcmeIssuer.java           the real thing, over acme4j; HTTP-01 only, so no wildcards
  certs/CertSetup.java            --setup-certs, the walkthrough that stops a rate-limit lockout
  certs/CertStore.java            <root>/certs: the ACME account and every key and chain
  certs/CertificateManager.java   what to order and when; one background thread, after bind
  certs/Challenges.java           HTTP-01 answers in flight; served by this server, not a bucket
  certs/TlsContexts.java          which certificate to present per hostname; live, so renewals land
  cli/Args.java                   hand-rolled flag parsing; unknown flags are errors
  cli/Ask.java                    terminal prompts, shared by every walkthrough
  cli/Install.java                --install: a systemd unit, a start script that swaps in a staged jar,
  cli/Root.java                   the one --root directory: config.cfg, domains/, dbs/, certs/, attachments/
  cli/Setup.java                  --setup, --domain-setup, --setup-email, --test-email
  common/Boot.java                ANSI boot output (respects NO_COLOR, non-tty)
  common/ConfigException.java a config problem, which is always fatal at boot
  common/ConfigObject.java    strict typed reader over Jackson; unknown keys are fatal
  common/PublicAddress.java       is a host on the public internet? asked by every outbound url
  common/ServerConfig.java    config.cfg: ports, TLS, HTTP/2, limits, the clock
  common/Verbose.java             the --verbose channel; lazy suppliers on the request path
  content/Bundle.java             every page and template as one JSON file, and the merge that brings it back
  content/ContentRecord.java  one page: three kinds, and the fields its template asked for
  content/ContentStore.java   the content and templates tables; every write emits an event
  content/ContentVersions.java    every version of every page, snapshot or patch
  content/RenderTimes.java        the last 50 builds of every page, and the p99 the listing prints
  content/Markdown.java           commonmark with every extension on; two renderers, one per kind of author
  content/Site.java               rendering + the three caches + the event listener that invalidates them
  content/TemplateField.java  the fields a template declares; the page editor renders them
  content/TemplateRecord.java one template, and whether it publishes a directory index
  content/TextPatch.java          the line diff the history rests on; exhaustively property tested
  events/EventBus.java            the interface; LocalEventBus is the in-process ring buffer
  events/EventListener.java   what a cache implements to hear about a write
  events/LocalEventBus.java   the in-process ring buffer, notified inline on the writing thread
  events/MutationEvent.java   domain + table + key + kind; flat so it can leave the JVM later
  js/JavaScript.java              V8: a fresh isolate per run, on its own threads, with a second to finish
  legal/LegalDoc.java             the two documents, and the text they ship with
  legal/LegalDocs.java            what a community said instead, if anything
  legal/LegalRoutes.java          /legal, open to everybody, in the administration's colours
  mail/AmazonSes.java             real email, one signed POST, no AWS SDK
  mail/DevBoxMailer.java          prints them to the terminal, copy-paste shaped
  mail/MailBrand.java             a community's colours and where its terms are; rides on the envelope
  mail/MailLayout.java            one shape for every message, and the footer that is not optional
  mail/Mailer.java                the closed list of email flows
  mail/Mailers.java               domain -> mailer, so one box can mix real and terminal
  mail/Messages.java              what each flow says, in both halves, for every mailer
  mail/Mime.java                  the one message with a shape: multipart, so text/calendar draws buttons
  mail/SesConfig.java             the per-domain ses block
  mail/SignatureV4.java           AWS request signing; checked against Amazon's worked example
  mail/SystemTemplate.java    every message this server sends, and the wording it ships with
  mail/SystemTemplates.java   what a community says instead; a replace, never an engine
  mcp/AiLog.java                  the last 1000 agent actions, arguments and results kept as JSON
  mcp/AiSurface.java              the single gate: human-only and read-only are enforced here, once
  mcp/AuthCodes.java              authorization codes in flight; memory only, single use, PKCE S256
  mcp/McpConfig.java              whether a domain talks to models, and on what terms; off by default
  mcp/McpRoutes.java              discovery, registration, consent, token, and the JSON-RPC endpoint
  mcp/McpTools.java               the tools a model is offered; a description here IS a prompt
  mcp/OauthClients.java           the registered connectors table
  mcp/Vendor.java                 known connectors and the redirect prefixes they may come back to
  people/DataExport.java          everything held about one person, as one file, built when they ask
  people/Erasure.java             removing somebody from every table that names them, once
  people/Names.java               what to call somebody on a page another member is reading; never an address
  people/PeopleStore.java         profiles, questions, answers; every write emits an event
  people/ProfileRecord.java   what somebody says about themselves
  people/ProfileText.java         somebody's own words, cut to a size that fits a listing
  push/PushCrypto.java            RFC 8291 message encryption, checked against the published vector
  push/PushLedger.java            when a push went out and when somebody acted; buffered, flushed on a timer
  push/PushSubs.java              which browsers we can reach, one row per session
  push/Vapid.java                 the signed claim that says who is sending; a keypair per session
  push/WebPush.java               one signed, encrypted POST to a push service
  settings/Setting.java           one thing a community may decide, and how a form asks for it
  settings/SettingStore.java      the config table; a row exists only where somebody decided something
  settings/Settings.java          the closed catalogue: what moved to the database, and what it means
  smtp/AuthResult.java            what each said, and the Authentication-Results header
  smtp/Dkim.java                  RFC 6376; canonicalization is the whole difficulty
  smtp/Dmarc.java                 RFC 7489; alignment is what makes the other two mean anything
  smtp/Envelope.java              one message as it arrived; envelope kept apart from headers
  smtp/MailReceiver.java          what happens to it once it has; the seam
  smtp/MimeParts.java             enough MIME to find the calendar part of a real reply, and no more
  smtp/SenderCheck.java           all three checks, and the one thing that gets refused
  smtp/SmtpConfig.java            the smtp block in config.cfg
  smtp/SmtpDns.java               the resolver seam, so every check is testable without a network
  smtp/SmtpRouting.java           which community a message is for, and the refusal to relay
  smtp/SmtpServer.java            inbound mail; its own event loop, off unless asked for
  smtp/SmtpSession.java           the RFC 5321 state machine, minus what nothing needs yet
  smtp/Spf.java                   RFC 7208; the ten-lookup cap is the security property
  smtp/TerminalMailReceiver.java  prints it, the inbound twin of DevBoxMailer
  store/Column.java           one column, its type, and the name it was renamed from
  store/Database.java             the swap point for MySQL/PostgreSQL; Dialect holds the differences
  store/Dialect.java          the differences between databases, in one place
  store/H2Database.java           the only implementation today
  store/H2Dialect.java        the only one implemented
  store/Schema.java               THE schema, in code; the database on disk is a cache of it
  store/SchemaException.java  a schema that cannot be reconciled; fatal at boot
  store/Leftovers.java            tables the code stopped declaring, and the only place that drops one
  store/SchemaUpgrader.java   diffs live schema vs code, renames what was renamed, adds columns IN POSITION
  store/Store.java                one H2 database + its boot audit
  store/Stores.java               domain -> database, including use_database_domain sharing
  store/Table.java            one table, declared in code rather than in a migration
  tables/TableBindings.java       the functions a page gets, generated from the definitions
  tables/TableCache.java          keyed by the question asked, so a write invalidates exactly that
  tables/UserField.java           one column, and the four types that land cleanly in JavaScript
  tables/UserTable.java           one table somebody invented; names validated, then prefixed
  tables/UserTables.java          the second database file: create, alter, drop, and read
  template/Templates.java         mustache, compiled at boot
  theme/Theme.java                six colours twice, and the CSS every layout interpolates
  theme/Themes.java               the palettes for one community, cached because every render asks
  vhost/DomainConfig.java         one loaded .cfg file, immutable; has() is the one surface question
  vhost/DomainScanner.java    the boot-time scan of <root>/domains (a flat directory of *.cfg)
  vhost/DomainTree.java           immutable label tree; deepest-applicable-config resolution
  vhost/Hosts.java                Host header canonicalization; a security boundary
  vhost/SiteUrls.java             per-domain account paths, validated and collision-checked
  vhost/Surface.java              the parts of the product a community can switch off in one word
  web/AccountRoutes.java          register / login / logout / forgot / reset
  web/AdminRoutes.java            the admin shell and its sections
  web/AdminView.java              the admin URL space: sections, panels, forms, sidebar
  web/AppIcon.java                the home screen icon, drawn rather than stored, in the community's colours
  web/BounceHandler.java          the redirect-only listener, for load balancers
  web/BounceInitializer.java  the pipeline for the redirect-only listener
  web/Canonical.java              one community, one address; the scheme, port, path and query it keeps
  web/Chrome.java                 the icon and the palette every page carries
  web/Cookies.java                Set-Cookie building and the double-submit CSRF check
  web/Flash.java                  the one-shot "that worked", keyed by session, never in a URL
  web/FormMint.java               per-submission opaque field names + the script proof
  web/Forms.java                  form, query and cookie reading; all of it untrusted. two ceilings:
  web/Html.java                   jsoup: what a member may write, and the whitespace nobody needs
  web/Icons.java                  inline SVG; the whole icon set, no image requests
  web/Initializer.java            pipeline; HTTP/1.1 today, marked for SNI + h2
  web/Landing.java                where to go after signing in; the open-redirect refusal lives here
  web/Navigation.java             the nav, per domain and per viewer
  web/Pages.java                  home / not found / bad host, via mustache
  web/PwaRoutes.java              /~app, the manifest and the worker; subscribing a session to push, the
  web/Responses.java              the only place that writes bytes; security headers live here
  web/SelfRoutes.java             /self: profile, inbox, notifications and invitations
  web/Signals.java                interaction counts posted by the page
  web/ThemeRoutes.java            /~theme.js: light or dark, decided by the person and kept in the browser
  web/ThirdParty.java             /3rd/<pkg>/<version>/<file>, vendored into the jar, never a CDN
  web/UploadGate.java             the ceiling, decided from the request line rather than after the body
  web/WebConfig.java              server knobs
  web/WebHandler.java             the request path, eight ordered steps
  web/WebRequestShield.java   scanner-noise filter
  web/WebServer.java              Netty bootstrap + lifecycle
src/main/resources/legal/      the terms and the privacy policy this server ships with
src/main/resources/theme/      theme.js: light or dark, set before the first paint
src/main/resources/templates/  layout.mustache + one file per page
src/main/resources/templates/admin/  shell.mustache + one page per section
src/test/java/io/hearth/
  testkit/TestServer.java  a real server on an ephemeral port, with real databases
  testkit/Http.java        HTTP client (JDK client + raw socket)
  testkit/Browser.java     cookie-keeping, form-filling client for the account flows
  testkit/Configs.java     throwaway configs directories
  testkit/CapturingMailer.java  reads codes back the way a person reads the terminal
  testkit/McpClient.java   a connector: registers, walks consent, redeems with PKCE
site/                      checked-in example root, used by tests and by hand
justfile                   the primary interface; `just validate` is the gate
```
## Invariants — do not break these without saying so out loud

### Boot and configuration

1. **Configs load at boot, before the socket opens.** `DomainScanner` runs once; the *shape* of
   `DomainTree` is immutable. Nothing on the request path opens a file to learn about a domain.
   The product half of a config is a database table and can change while the server runs
   (invariants 40-44) — but a write rebuilds the whole immutable `DomainConfig` once and swaps it
   in, so a reader still takes a reference to a finished object. The work is on the write.
2. **A domain is served only if it has a `<domain>.cfg`.** No default host, no fallback site.
3. **Config problems are fatal at boot.** Bad JSON, wrong types, unknown keys, a filename that is
   not a valid domain, a symlink — all refuse to start. A half-applied policy is worse than none.
4. **`--verbose` explains, never changes behavior** — except deliberately withholding diagnostics
   when it is off.
5. **The disk is for startup, with three exceptions**: H2, the certificate cache, and what people
   upload. Emailed codes live in memory only — a ten-minute credential is not worth a row.
6. **One `--root`, and everything under it.** A removed flag refuses *by name*, because the person
   hitting it is upgrading.
7. **A surface is off in one word, and off everywhere at once.** `DomainConfig.has(Surface)` is the
   only question any handler or menu asks. An unknown name is fatal at boot.
8. **Everything is on until an operator turns it off**, except the model endpoint.
9. **The clock is config, per box and per community, and it is not the JVM's.** Anything reaching
   for `ZoneId.systemDefault()` on a request path is a bug.

### The request path

10. **All bytes leave through `Responses`.** Security headers are applied in one place. Inline
    scripts are allowed by nonce, never `'unsafe-inline'`; `form-action` and `base-uri` are
    `'self'`; `script-src` also carries `'self'` so a nonced module can import from `/3rd`.
11. **The Host header is untrusted input.** It becomes a lookup key only via `Hosts.normalize`.
12. **A handler that writes nothing holds the connection open.** It is invisible in a log and
    indistinguishable from a hung server, so every path ends in a `Responses` call and every
    `recorder.status()` has a send after it. This bit hard during the reduction: a refactor removed
    four sends along with the dead code around them, and the result compiled, booted, and served
    nothing on those paths.
13. **The access log records the domain before anything can refuse.** Shielded and malformed
    requests are the interesting traffic.
14. **An address nothing answers is a 404, and `/` is the only exception.** `Pages.missing` wears
    the community's colours and carries the way back; `Pages.notFound` is for a domain this server
    knows nothing about and carries no community.
15. **Absolute-form request targets are stripped to their path.** The authority in a request line is
    never a way to pick a virtual host.
16. **One community, one address.** Any name that is not the config's own answers 308 to the same
    path on the domain itself. The ACME challenge and `/3rd` are answered earlier and never
    redirected — an authority validating `www` fetches its token *from www*.
17. **`/` is the community's front page.** There used to be a member dashboard at `/home`; it was a
    read of the board, the calendar and the survey and it went when they did. After-login lands on
    `/`, and so does signing out.
18. **HTTP/2 changes the transport, not the request.** ALPN picks it during the handshake and the
    same `FullHttpRequest` reaches `WebHandler`. A second handler would be two places to fix a bug.

### Accounts and access

19. **Secrets are never stored in the form they are presented in.** Session tokens as SHA-256,
    passwords as scrypt. A stolen database file must not be a list of logins.
20. **Every mutation is write-through, database first.** A revocation that loses a race with a crash
    is a token that still works. `last_seen_at` is the deliberate exception.
21. **No account enumeration.** Asking for a code, or getting a password wrong, looks identical
    whether or not the address has an account.
22. **A session is handed out in exactly one place**, `AccountRoutes.finishSignIn`. A session means
    *authenticated*, never *approved*. Approval is enforced in `WebHandler`, which leaves the
    account pages and `urls.self` reachable — `Route.isReachableUnapproved()`, a closed list rather
    than "is this path in the routing table", which once answered yes for every surface there was.
23. **Signing in returns you to where you were going.** A validated `next` rides on the form action
    through email, code and session. The OAuth flow depends on it.
24. **A refusal for want of a session always carries where they were going**, path *and* query.
25. **`Landing.here` validates a URL this server itself wrote**, because "we generated it" is
    precisely the assumption that turns a request line into a header injection.
26. **`?next=` is a same-site path or it is nothing.** Refusing is the only correct answer;
    repairing means guessing what somebody meant by a URL that is already wrong.
27. **A session whose account has gone is signed out, not left waiting.**
28. **Signing out deletes the session.** A revoked row lingers for a day, and for that day the
    server holds a key that can put a notification on a device somebody just signed out of.
29. **A ban is cheap and invisible.** Checked before a code is minted and before a row is written,
    but a banned address sees the page a fresh one sees — a ban that answers differently is an
    oracle for who has been banned and who has an account.
30. **Bot resistance is not a security boundary.** Minted field names, the script proof and the
    interaction counts raise the cost of cheap traffic. Approval is the boundary.
31. **Too long must never become empty.** `Forms.get` and `raw` cap at 512 characters, which is
    catastrophic for a page body — prose goes through `Forms.text`. Check `form.tooLong()`
    immediately before a write, never earlier: the list fills in as fields are read.

### Permissions and the admin section

32. **The built-in admin role cannot be edited, deleted, or duplicated.** It is rewritten at every
    boot, `everything` is stripped from every other role, and `admin_emails` answers yes without
    consulting the database — an escape hatch that reads the thing it rescues you from is not one.
33. **A permission implies what it needs.** Writing implies reading; anything implies reaching the
    admin section. Otherwise a role grants a power behind a door it cannot open.
34. **Nobody may grant a permission they do not hold.** Otherwise `people_roles` is the whole server
    by a longer route, and the escalation is sideways rather than upwards.
35. **A permission that is offered has to be asked for somewhere that matters.** A permission nobody
    checks is worse than none, because somebody grants it and believes the split exists. When a
    feature is removed, its permissions go with it.
36. **A section permission is permission to see a screen, never to press what is on it.** Every
    action posts to the section path, so a handler that checks nothing inherits the mildest
    permission on that screen. `neededForPerson` and `neededForContent` map every action; anything
    unlisted requires `everything`, so a new button fails closed. This has been a real hole twice —
    `people_read` reaching `grant_admin`, and `content_read` reaching delete and restore.
37. **A section somebody may not open answers 404, and is absent from the sidebar.** A 403 confirms
    what is behind the door; a sidebar of doors that say no advertises what they are not trusted
    with. The admin section answers 404 even to an anonymous request, with the way back on the page.
38. **A control that would refuse is not drawn, and a link into a section somebody cannot open is
    not a link.** Both are checked with the same `can` the handler uses.
39. **Every sub-view has its own URL.** A panel that refreshes in place is a path
    (`/admin/system/logs/results`), not a query flag, and the page embeds it by calling the same
    method the panel's URL calls.
40. **Identity in the path, filters in the query, mutations in a POST that redirects.** A refusal
    redirects too; the reason arrives through `Flash`, keyed by session and read once.
41. **A listing is not a form.** Creating or editing anything is a page transition to its own URL.
42. **Rejecting is not unapproving.** Unapproved means "not yet" and keeps everything; rejecting
    deletes the account and the profile. An admin can never be rejected — remove the role first.
    Turning an account off is the reversible middle.
43. **The settings screen at `/admin/system/settings` is a report, and everything on it names its
    key.** No credential is printed: `set` or `not set` is the half worth knowing.
44. **The admin sidebar is one nav in two shapes, and it ships open** — a `<details>` the script
    *closes* when the screen is narrow, so no-JavaScript gets the full list rather than a menu
    button that does nothing.

### Content

45. **Every write emits a mutation event, from the DAO.** Not from the handler: a caller can forget,
    and the event has to be tied to the write actually landing.
46. **Caches invalidate from events, never from the code that wrote.** The TTL is a backstop.
47. **One cache key per entry.** If a value needs finding two ways, invalidate with `invalidateIf`.
48. **A version is the whole page** — body, title, template, folder, field values, published and
    human-only, as one canonical document. Anything less and "what did this look like in March" is
    missing the part somebody changed.
49. **A history that cannot rebuild a version says so.** `reconstruct` refuses rather than returning
    an older version presented as the one asked for: a plausible wrong answer is worse than an
    admitted gap, because nobody checks it.
50. **Recording a version must never fail a save.** Losing a history entry is a bad day; losing
    somebody's edit because the history table had a problem is a worse one.
51. **A restore is a save, not a rewind.** The old version becomes the newest and everything before
    it stays, including the edit being undone. This is `git revert`, not `git reset --hard`. It
    brings back the *words*, never the uri, and it asks `content_publish` when it would change
    whether the page is live.
52. **A page's identity is its id when it has one.** Matching on uri alone meant renaming a page
    created a second one and stranded the history under the old id.
53. **A page's declared field values are given to its own template.** They used to reach a directory
    listing and stop, so `{{subtitle}}` rendered as nothing. Built-ins win the name clash.
54. **Declaring a template's fields is absent-keeps, present-replaces**, which makes `template_get`
    load-bearing: it answers with the declarations *in full*, because a read that is lossy under a
    write that is total deletes labels.
55. **Field values merge; an undeclared name is refused.** A submission mentions a handful of the
    keys that exist, and treating that as the new state erases the rest while looking like it
    worked.
56. **A page's merge key is a uuid, stamped once and never rewritten**, and an import is a merge —
    same key, same page, whatever its address has become.
57. **A directory index is a property of a template**, and a second template: one body cannot be
    both a document and a list. Page one is always the bare path, ordered by when a page was
    created rather than edited.
58. **A page's published date is a date, and it is mutable.** A page drafted in January and
    published in March is a March page.
59. **The renderer is chosen by who is holding the pen, never by where the text is going.**
    `Markdown.toHtml` passes raw HTML through, which is right for somebody who could replace the
    whole document anyway; `toSafeHtml` is for a member. The filter runs on the *rendered* HTML,
    because filtering markdown would mean understanding markdown and every escape found would be a
    hole.
60. **Whitespace between inline elements is content.** The compactor is a parser and not a regular
    expression for exactly one reason: `<p>a</p> <p>b</p>` has a space nobody sees and
    `<a>a</a> <a>b</a>` has the gap between two words.
61. **No *escaped* template value is ever interpolated into a `<script>` block.** Mustache escapes
    for HTML and HTML entities are not decoded inside a script. Configuration goes in a `data-`
    attribute; a server-built payload goes in raw with `{{{blob}}}`.
62. **Code that exists in two languages needs a test that runs both.** `ProofContractTests` extracts
    the shipped function and runs it under node; any shipped script whose promise is behavioural
    gets the same treatment.

### The JavaScript kind

63. **One page, one isolate, every time.** A fresh `V8Runtime` per execution costs about 0.8ms and
    buys the property the feature rests on: nothing a page defines can be seen by the next page or
    by the next request. The tempting optimisation is one runtime per pool thread, and it looks
    identical right up until two pages on the same thread start seeing each other's globals.
64. **The two APIs are JavaScript, not Java callbacks.** `render` and `meta` are defined by a
    one-line prologue and accumulate into ordinary arrays; the whole result comes back as one JSON
    string. Nothing crosses JNI per call and there is no callback API to hold wrong. The prologue is
    **exactly one line** so a reported error line maps to the author's by subtracting one.
65. **A runaway page is terminated, not waited for.** `V8Guard` interrupts V8 itself after a second.
    Without it `while(true){}` takes a pool thread for ever and the fourth such page takes the
    feature down; the `Future` timeout outside is only the backstop.
66. **Nothing exists until somebody writes one.** The pool and the native library load on first use,
    so a community that never uses this pays no threads and no memory. That is also why the engine
    is process-wide rather than per-domain.
67. **A failure renders as a message, never as a half-built page.** A body cut off where something
    threw looks finished and is not, and this is the one kind whose failure is the author's to fix,
    so they get the error and the line.
68. **What `meta` sets wins; what `render` built cannot be replaced.** The opposite precedence from
    the declared fields, because those were typed once and this ran a millisecond ago -- a
    `meta('title', ...)` that could not replace the stored title would not be manipulating the
    title. `body` is the exception, or a stray `meta` call silently discards every `render`.
69. **A program is never cached.** A page that can answer differently on every request has no
    business being kept under its address, and caching it would make the timings a lie.
70. **An agent may write a program, because the reach is drawn rather than assumed.** This was the
    opposite rule when the kind shipped, and what changed is that the blast radius is now a list:
    `render`, `meta`, `query`, the declared table functions, no network, no writes, one second, a
    fresh isolate. Nothing there is beyond what a page it wrote in HTML could already do, the source
    is versioned in the content table, and `site_spec` names every function rather than describing
    it -- a capability a model has to guess at is one it will guess wrong.
71. **The sandbox is what was never bound, not what refuses.** No network, no storage, no timers, no
    modules. A guarantee made of absent bindings is one you can check by reading the prologue.
72. **Every kind is timed, not just this one.** A duration is unreadable alone and obvious beside its
    neighbours: 40ms means nothing until the markdown page next to it is 0.3ms. Fifty samples in
    memory per page, p99 by nearest rank -- with fifty samples that is the slowest one, which is
    what somebody asking "how bad does it get" actually wants.

### Tables

73. **A second database file, and that is the whole safety argument.** The system schema is code,
    upgraded by diffing, never dropped from; a user table's shape is whatever somebody typed this
    afternoon and the operations are CREATE, ALTER and DROP. One file would put a `DROP TABLE` on
    the connection holding every account in the community. `<domain>.data.mv.db` sits beside
    `<domain>.mv.db`: deleting it loses every user table and nothing else.
74. **Names are validated, then prefixed, and both matter.** Validation makes a name splice-able at
    all; the `t_`/`f_` prefixes are what make it *safe*, because MODE=STRICT reserves the standard's
    keywords and `value`, `order` and `key` are the first three things anybody names a column.
    Prefixing kills the class rather than keeping a denylist that is wrong at the next upgrade.
75. **A page names a function, never a column.** Every function is generated from a stored
    definition's own strings, so there is no filter argument, no operator and no fragment of SQL. An
    index is a declaration rather than a hint: declaring one is what creates the `_list_` function,
    which makes the set of indexes exactly the set of questions anybody may ask.
76. **A write invalidates the id, both sides of every index that moved, and the listings.** Naming
    only the *new* index value leaves the row cached under the value it used to have, which is a
    member still listed in the group they just left. That is why an update reads the old row first.
77. **A page reads; it never writes.** A dynamic page runs for every request including a crawler's,
    so a page that could insert is a table filling itself with whatever fetched it. Writing is the
    admin section's, where there is somebody to hold responsible.
78. **Asking for a table that is gone throws.** The tempting alternative -- an empty list -- reads
    exactly like "no rows yet", so a page whose table was dropped this morning renders an empty
    listing and nobody finds out.
79. **One function crosses into Java and it takes a string.** `__data(json) -> json`, dispatched on
    the Java side, so no object graph is converted across the boundary and adding a capability is a
    case in a switch rather than a binding with new lifetime rules.
80. **A query parameter arrives as the strictest type it honestly is.** `?page=2` is the number 2,
    because every page that reads it does arithmetic and `"2" + 1` is `"21"` -- a plausible wrong
    answer, which is the worst kind. A leading zero or a `+` stays text, because that is somebody's
    identifier rather than a number.

### Settings

81. **What lives in the database is decided by what a setting is about, not how awkward it is to
    change.** Product and presentation are the community's. Anything deciding who gets in, what a
    credential is, what a program may do, or how many bytes a request may carry is the operator's
    and stays in a file. `admin_emails` is the sharpest case and it stays.
82. **A setting's key is the path it had in the config file**, and a value is applied by writing it
    into a copy of that file's JSON and parsing the whole thing again — so the check that refuses a
    bad value at boot is the same check that refuses one typed into the admin section.
83. **The file seeds; the database overrides; clearing reverts.** A row exists only where somebody
    decided something, and the rebuild always starts from the file, never from the last rebuild.
84. **A write rebuilds and swaps; a read is still a field access.** Triggered from the DAO rather
    than the handler, for the reason invariant 45 gives.
85. **A shared database is one set of settings, and one clock** — the same rule that makes it one
    account space.
86. **No agent tool reaches any of it, and the proof is that there is no tool** — not one that
    refuses, which would still appear in a listing and cost a model turns.

### The model endpoint

87. **An agent is a session with a bit set, never a parallel notion of identity.** That is what
    makes revocation, expiry, the reaper and the cap work without a second implementation of "still
    valid" that would eventually disagree with the first.
88. **Every AI rule is enforced in `AiSurface`, once.** A rule enforced in fifteen tools is a rule
    that will be forgotten in the sixteenth.
89. **Human only is asymmetric, on purpose.** Reads are *invisible* — absent from listings, searches
    and fetches. Writes are *refused out loud*. An agent can never set or clear the bit. A locked
    page that merely looked empty to a write would be overwritten by an agent asked to "add an
    about page"; a write claiming success while doing nothing teaches a model it succeeded.
90. **The connection is a permission, not a rank.** `agent_connect` is granted in a role and
    re-checked at consent, at redemption and on every call, so taking it away stops an agent at its
    next request.
91. **A write is refused by name; a read is narrowed.** Refusing a member's assistant a listing
    would make the tool useless; answering in full would hand them a draft they cannot open.
92. **A tool that could only ever refuse is not offered at all**, and a narrowed listing needs the
    same narrowing on the fetch-by-id beside it — the oldest shape of this bug.
93. **What is advertised and what is executable are checked against each other, for every tool.**
    Two hand-maintained lists agree until somebody adds a tool.
94. **A structured argument has to arrive as structure.** `unwrap` once fell through to `asText()`,
    which for a container node is the empty string, so every nested object arrived as `""` and the
    handler's correct refusal was unreachable.
95. **There is no AI tool for a bundle.** It is the one view of the content table that ignores
    human-only, and invariant 89 survives by that view not existing for a model.
96. **A tool description is a prompt.** The model reads nothing else about this server, so they say
    what a thing is *for* and when not to use it.
97. **Redirect matching is an explicit prefix list and nothing else** — no wildcards, no host-suffix
    matching. A prefix with no path is normalized to end at the authority boundary, because
    `startsWith` has no idea where a hostname ends, and a code sent to the wrong host is an agent
    token handed to whoever owns it.

### Uploads

98. **The extension decides what an upload is; the browser's content type is thrown away.** The
    allow list is closed, `text/html` is not on it for any extension or configuration, and `svg` is
    deliberately absent — it is a document that can carry script and arrives looking like a picture.
99. **Nothing about an attachment's address is a path.** The id is a long, the extension is looked
    up in a table, and the file is computed from both.
100. **Private is the default and it answers 404.** Whether a private file exists is itself private,
    and a sign-in form is no use to the `<img>` tag that asked.
101. **`Cache-Control: private` on every attachment, always.** These are frequently photographs of
    somebody's children.
102. **The referrer check is a bandwidth measure, not a boundary.** A request with no referrer is
    honoured, because browsers omit it constantly.
103. **One path is allowed a body bigger than a form, and the pipeline decides that from the request
    line**, before the aggregator buffers anything.
104. **The garbage collector's marking is the dangerous half, so it reads everything** — including a
    page's history, which is the one nobody thinks of.
105. **A partial scan offers nothing.** If any source could not be read, the answer is "I do not
    know", and a delete button on top of that offers to remove files it never looked for.

### Push

106. **A push subscription cannot outlive its session**, and its VAPID keypair dies with it — so
    "sign me out" means unreachable, not merely unwatched.
107. **A push says who and where, never what.** It crosses somebody else's infrastructure and lands
    on a lock screen.
108. **Every step of subscribing is a no-op the second time**, so a browser whose subscription was
    rotated repairs itself rather than going silently dead.
109. **The manifest is declared on every page, and its icons are fetchable.** `AppIcon` draws them at
    request time, so invariant 113 holds and a community that changes its colours changes its icon.
110. **The worker has a fetch handler and still caches nothing.** A browser will not install an app
    whose worker cannot answer a navigation offline; the only thing built inside it is a "no
    connection" page, for which stale is not a possible state.
111. **The self-test reports two facts, never one**: "the push service accepted it" and "this device
    showed it" are different, and every push problem lives in the gap.

### Outbound requests

112. **A member-supplied url is an instruction to make a request.** https only, public addresses only
    *after resolution*, no redirects, a timeout and a ceiling. What actually closes DNS rebinding is
    https plus certificate verification — relaxing either re-opens it.

### Assets

113. **No bytes on disk except the database, the certificate cache, and what people upload.** Images
    are inline SVG from `Icons`; a page costs one request. Vendored browser libraries under `/3rd`
    are classpath resources baked into the jar — one artifact to deploy, nothing beside it to
    forget to copy, which was always the actual rule.
114. **Vendoring is redistribution.** Every third-party bundle travels with its licence, checked into
    git even though the bundles are not, and served at `/3rd/licenses`.

### Certificates

115. **Certificate work happens after the socket is open, never during boot.** HTTP-01 validation is
    the authority fetching a path from this very server.
116. **The ACME challenge is answered before anything can refuse it** — ahead of the shield, the
    method gate and host resolution, each of which can say no for a reason unrelated to
    certificates.
117. **No certificate is worth failing to start over.** A domain that will not validate gets a loud
    complaint and a retry; the server serves plain HTTP throughout.
118. **Port 80 never becomes a redirect.** It serves the site *and* answers the challenge; turning
     it into a redirect would quietly break renewal three months later.
119. **"Ready" means every listener is bound.**
120. **Report what happened, not what is about to.** The boot output prints each certificate as it
     actually lands or actually fails.
121. **A wildcard is not a way to serve subdomains**, because HTTP-01 cannot issue one. `subdomains`
     is the answer: a written-down list, ordered along with the domain.
122. **A named subdomain is the same community, never a second one** — one config, one database, one
     set of accounts, which is what makes it safe to accept mail for.

### Mail

123. **This server never relays.** Inbound mail is accepted only for a domain with a config file,
     matched exactly, and refused at RCPT before a body arrives. An open relay is found within days.
124. **One message, one community.** Recipients on two domains are two deliveries.
125. **Advertise only what is honoured.** EHLO names SIZE and 8BITMIME and nothing else.
126. **The ten-lookup cap in SPF is the security property**, counted across the whole evaluation:
     an unbounded record is amplification on the sender's behalf.
127. **A DNS failure is temporary, never a forgery.** `temperror` throughout, so an unreachable
     nameserver bounces nothing.
128. **Only what the domain owner asked for gets refused** — `p=reject` and nothing else. An SPF
     failure alone means a mailing list far more often than a forgery.
129. **Nothing vouching for a message is not the same as nothing objecting to it.** The fallback for
     a domain with no DMARC record is SPF or DKIM actually *passing*; it once also accepted anything
     reporting `=none`, which is present for exactly those domains and made the other clauses dead.
130. **There is one email layout**, and every message says what it is, why it arrived and what
     interacting means. The footer is built by `MailLayout` and is not optional, in both halves —
     spam filters read the text.
131. **The wording of a message is a community's; the shape of it is not.** Three boxes; the layout,
     the button, the plain-text half and the footer stay in `MailLayout`.
132. **A flow declares what it can say.** `availableParameters()` is printed beside a filled-in
     preview, because a template naming something that does not exist renders as a hole and nobody
     notices until it has gone out.

### Appearance and the law

133. **A palette is six hex strings or it is the default.** It is interpolated raw into a `<style>`
     block, so every value goes through `Theme.isColour` and a slot that fails keeps what it had.
134. **Red means refused and green means it worked, and nobody may change that.**
135. **Light unless somebody says otherwise, and it is their choice rather than their laptop's.**
     `/~theme.js` sets the attribute before first paint — a file rather than an inline script
     because inline needs a nonce, and not deferred because deferred is a white flash.
136. **The two legal documents ship in the jar and are published from the first day.** A row exists
     only when a community has overridden one, so upgrading the software improves them.
137. **`/legal` is open to everybody.** Every email links to the terms and most go to somebody with
     no account yet.
138. **The cookie notice is a line in the footer, not a banner.** Two cookies, both strictly
     necessary, which is the category that needs no consent.
139. **The privacy policy this software ships is a specification.** Every promise in it is a thing
     the code does: `DataExport` and `Erasure`, reachable by the member and by an administrator.
     Changing the policy is changing a requirement.
140. **An erasure is checked by looking, not by remembering.** `RightsTests` walks *every column of
     every table* afterwards looking for the address, which is the only form of that test worth
     writing.

### Storage

141. **The schema is code.** Add a column where it belongs, bump `VERSION`, restart. A column added
     later must be nullable or carry a default — there is no correct value for existing rows.
142. **A column whose name has stopped being true gets renamed.** `Column.renamedFrom` declares it
     and the upgrader performs it, before it looks for anything missing.
143. **The upgrader adds, never drops or retypes.** A column the code no longer declares is reported
     and left alone, which is what makes the reduction safe for an existing database.
144. **A test that writes "hello" proves that "hello" fits.** Anything that stores what a person
     typed gets a test with a realistic amount of it in.
145. **Boot never drops anything; a person does.** The other half of invariant 143. Leftover tables
     are listed at `/admin/system/cleanup` with their row counts and dropped one at a time, by
     somebody holding `everything`. An operator who upgrades, hits a regression and rolls the jar
     back must still have their data, so the upgrader can never be the thing that deletes it.
146. **The table name on that screen is untrusted.** `Leftovers.drop` re-derives the leftover list
     and refuses anything not on it, using the database's own spelling rather than the form's.
     Without that the most powerful button in the admin section is an arbitrary `DROP TABLE` with a
     text field in front of it.
147. **A column nothing reads is not free.** It is a sentence in the privacy policy that has to stay
     true and a column every erasure test keeps walking. The ten address and geo columns outlived
     their feature by a whole reduction, with a dead `SELECT` list in `PeopleStore` naming them.

### Installing

148. **A walkthrough writes a file you could have written by hand, and says what it wrote.** They
     refuse without a terminal, because each exists to make somebody think and a pipe cannot think.
149. **A walkthrough run twice must not undo the first run.** Every question pre-fills from the file
     it is about to rewrite.
150. **`--install` needs no root and starts nothing.** The half that needs root is written out as
     `install.sh` to be read first.
151. **A second `--install` stages a jar; it never overwrites the running one.** Overwriting leaves
     the file on disk and the software in memory disagreeing.
152. **The unit asks for `CAP_NET_BIND_SERVICE` and bounds the set to it.**
153. **16px on every field, 44px on everything you can press, a visible focus ring on everything.**
## The virtual hosting rules

**Flat on disk, tree in memory.** `<root>/domains` is a flat directory of `<domain>.cfg` JSON files;
`domains/junior.example.org.cfg` configures `junior.example.org`. The filename *is* the domain —
there is deliberately no `domain` key inside the file, because two sources of truth for one fact is
how they drift apart.

Scanning (`DomainScanner`): only `*.cfg` files are read, directories are ignored, the name minus
`.cfg` must be a valid lowercase domain or the scan fails, and `enabled: false` loads but warns —
an operator should see their own kill switch at boot.

Resolution (`DomainTree`): labels are inserted reversed, so the tree is rooted at the top level
domain. A request descends as far as its labels allow, keeping the deepest node whose config
*applies* — the exact domain, or one that set `wildcard: true`. A node with no config is a junction:
it exists because something lives under it and it serves nothing. **Named subdomains are consulted
first**, because somebody wrote them down, and they are kept in a map beside the tree rather than in
it: everything that walks `all()` must see one entry per *community*, and an alias in there would
give `www.example.org` a database of its own.

## Accounts, storage, and policy

**One database per domain**, an H2 file under `<root>/dbs` named for the domain.
`use_database_domain` points a domain at another's; that is one level deep, validated at boot, and
means one account space. The **owning** domain's `login_security` and admin list govern it, because
one database cannot have two answers to "how long is a session".

**The schema is `Schema.java`.** No migration scripts. `SchemaUpgrader` reads INFORMATION_SCHEMA,
diffs, and adds what is missing **in the declared position** via H2's `ALTER TABLE ... BEFORE`, so
an upgraded database is shaped like a fresh one. It will not drop or rename (reported, left alone)
and will not retype (fatal).

**The tables.** `emails` (people; `password_hash` is nullable because passwordless is the default),
`sessions` (`token_hash` only, never the token), `roles`, `role_defs`, `bans`, `content`,
`content_versions`, `templates`, `profiles`, `attachments`, `push_subs`, `themes`, `legal`,
`system_templates`, `oauth_clients`, `config`, and `schema_meta`.

**Sessions** are a write-through cache in front of the table: reads hit a `ConcurrentHashMap` with
no I/O, writes hit the database first. The reaper flushes `last_seen_at`, deletes dead rows, evicts
cold entries, and applies the per-person cap — only ever taking sessions older than the grace
window, so a new sign-in never knocks somebody out mid-task.

**Approval.** Every account starts unapproved and cannot hold a session. `admin_emails` lists
addresses that are admins by fiat — approved, holding the admin role, un-revocable from inside the
running system. Without that list, requiring approval would mean nobody can ever sign in on a fresh
install. `Access` is the only place that answers "is this person an admin".

**The event bus.** Every DAO write calls `store.changed(table, key, kind, actor)`. `LocalEventBus`
keeps the last 1000 in a ring for `/admin/system/events` and notifies listeners inline on the
writing thread, so the read after a write is already correct. `EventBus` is an interface because
that is the scaling escape hatch: several processes behind a sticky load balancer break on cache
coherence first.

**The database is behind `Database` + `Dialect`.** H2 runs in `MODE=STRICT` on purpose: it refuses
H2's own extensions, so the SQL is much more likely to work unchanged on MySQL or PostgreSQL. When
strict mode rejects something, the workaround goes in `Dialect`, not inline. It also reserves the
standard's keywords, which is why the config table's column is `value_text`.

**Content and templates** live in tables and are edited at `/admin/content` and `/admin/templates`.
`Site` renders and caches; the cascade is in its event listener. **Every save is versioned** —
a snapshot every ten versions and a line patch between, and a snapshot whenever a patch would not be
smaller. `TextPatch` is a line diff with an explicit longest-common-subsequence rather than Myers,
chosen because the point is that you can read it and believe it.

**Templates can declare fields.** `TemplateField` is a name, a type, a label and whether it is
required; the list is a JSON blob on the template row and the values are a blob on the content row.
That is why a template author can ask for a headline without anybody touching the schema. **A
template can also publish a directory index**, which is what lets the content table behave like a
blog without anybody building one.

**Tables a community invented** live in a *second* database file, `<root>/dbs/<domain>.data.mv.db`,
and that separation is the whole safety argument. The system database has a schema declared in code
and an upgrader that never drops; this one is reshaped by whoever is holding the form, with CREATE,
ALTER and DROP. `UserTables` owns it, `UserTable`/`UserField` are the definitions, and the catalogue
of definitions lives in the data file too, so the pair is self-contained -- delete it and you lose
every user table and nothing else.

Names are validated to `[a-z][a-z0-9_]*` and then **prefixed** (`t_`, `f_`), because MODE=STRICT
reserves the standard's keywords and `value`, `order` and `key` are the first three things anybody
names a column. Identity columns and `LIMIT` go through `Dialect` for the same reason everything
else does -- H2 refuses its own `AUTO_INCREMENT` and `LIMIT` in strict mode.

`TableBindings` turns the catalogue into the JavaScript a page gets: `<t>_get_id`, one `<t>_list_<ix>`
per declared index, `<t>_page` and `<t>_all`, generated from the stored definition's own strings. A
page names a *function*, never a column, so there is no filter argument and no way to express a query
nobody declared. Reads only. `TableCache` keys every entry by the question that produced it, and a
write invalidates the row id, **both sides** of every index value that moved, and the listings.

**Settings** are the product half of a domain's config, in the `config` table, edited at
`/admin/configuration` with a walkthrough at `/admin/configuration/setup`. `Settings` is the closed
catalogue; a key is the dotted path it had in the file, and applying one means writing it into a
copy of that file's JSON and parsing the whole thing again.

**The model endpoint.** `mcp.enabled` is false by default — the only default here not tuned for a
high-trust community, because what it hands out is the ability to act as somebody. When on, a domain
serves OAuth 2.1 discovery, registration, consent and token endpoints plus JSON-RPC at `mcp.path`.
Tools go through `AiSurface` and nothing else, and the set is content and templates. `AiLog` keeps
the last 1000 actions with arguments and results as JSON.

**Mail** goes through `Mailer`, a closed list of flows rather than a generic `send()`, so nothing in
a handler can invent a new kind of email without the interface growing a method and somebody
noticing. `DevBoxMailer` prints; `AmazonSes` sends. Which one is used is per domain, because the
credentials are. `SignatureV4` is checked against Amazon's own worked example, because SigV4 fails
closed and silently.

**Inbound mail** is `smtp`, off by default: port 25 needs root and an unconfigured listener on it is
found by scanners within the hour. `SmtpRouting` accepts only for a domain with a config file,
matched exactly — never by wildcard, which would be an open relay built by accident. Every message
is checked with SPF, DKIM and DMARC and stamped with `Authentication-Results` whatever the outcome.
**Nothing acts on a message today**: `TerminalMailReceiver` prints it. The routing and the checks are
kept because they are the expensive part to get right and the part a future consumer would need.

## Testing

JUnit 4. Tests live beside the package they cover and are named `*Tests`. Surefire runs from the
project basedir, so tests may read the checked-in `site/` tree (guard with `isDirectory()`).

**Prefer testing the server over testing a class.** `src/test/java/io/hearth/testkit/` exists so a
test can boot the real thing and talk to it:

- `TestServer` — boots the full path (scan → table → bind) on 127.0.0.1 port 0, `AutoCloseable`.
- `Configs` — throwaway config trees. Write the tree an operator would write; that puts the scanner
  under test too.
- `Http` — `get/head/send` through the JDK client (realistic: connection reuse, chunking);
  `Http.raw(port, bytes)` through a socket for what a normal client will not send.
- `Browser` — keeps cookies and does what the register page's script does, which is the only way to
  test the account flows at all.
- `Verbose.capturing()` — captures narration instead of printing it. Never `new Verbose(true)`.

Setting `Host` explicitly is the point of a virtual hosting test, and the JDK client refuses that
header unless allowlisted — surefire sets `jdk.httpclient.allowRestrictedHeaders=host` in `pom.xml`,
and `ServerHttpTests.boot()` asserts it took effect.

Cover the refusals, not just the happy path. An assertion with an `|| raw.isEmpty()` escape hatch is
a test that proves nothing. `just coverage` enforces a floor.

**A test class with no `@Test` is skipped in silence, and `just suite` is the answer.** The
reduction removed eight of the thirteen message flows; every test in `SystemTemplateTests` named one
of them somewhere, and removing each one left a class with a `@Before`, an `@After` and nothing to
run. Surefire says nothing about a class it finds no tests in, so a kept feature lost all of its
coverage while the suite stayed green — and the file was still the right size and the right shape,
which is what would have carried it through a review. `tools/check-suite.sh` refuses an empty
`*Tests` class and, once the suite has run, refuses any test class that produced no report.

This is the failure mode of cutting a feature out mechanically, and the general lesson is worth more
than the check: **a script that deletes what mentions a dead symbol will delete things that merely
mention it.** Two of the escalation tests were removed for containing the word `places_write` in a
role they were building — the role was scenery, and the invariant they proved (nobody may grant a
permission they do not hold) is one of the load-bearing ones. After any mechanical cut, the question
is not "does it compile" but "what is no longer being checked".

## Not verified

Different from a defect: nobody has proved these wrong, and nobody has proved them right either.

- **The release path has never published anything.** Every refusal, the stamped build and the
  `--version` self-check are exercised; the tag push and the publish are not.
- **SPF, DKIM and DMARC have never seen real mail.** Tested hard against the RFCs with a fake
  resolver. `enforce-dmarc` is off by default because of this.
- **The organizational-domain rule is a guess.** Relaxed alignment strictly needs the Public Suffix
  List; this errs toward not aligning, so a wrong guess marks a message unaligned rather than
  passing a forgery.
- **The certificate path has never met a real authority.**
- **Nothing has been raced on purpose.** No test runs two writers at the same row. The caches are
  concurrent maps and the counters are atomic, and that is an argument rather than evidence.
- **Nothing has been run against a real dataset.** Every query is written for a few hundred rows.
- **Push has no producer.** Subscribing, the keypair, the worker and the self-test work; nothing in
  the server generates a notification, because the board and the calendar were what did.
- **Inbound mail is received and printed, and nothing acts on it.**
- **The suite has flaky timeouts under load.** A handful of HTTP tests occasionally hit the client's
  ten-second ceiling; the set moves between runs and every one of them passes when its class is run
  alone. It has not been chased down, and it means a red suite needs reading rather than trusting.
- **The vendored browser libraries are not in git** (`src/main/resources/3rd/`). `just release` runs
  `just third-party` first; a fresh clone that runs `just package` gets a jar whose rich editor
  falls back to a textarea. Their **licences are in git**.

## What's next, and what's undecided

**The app platform is the live question.** A dynamic page today gets `render` and `meta` and nothing
else -- no state, no request data, no idea who is asking. That is deliberate and it is also the
whole limitation: it validates *ideas*, not products. Everything an app would actually need is the
next decision, and each piece of it moves this from a CMS that runs a snippet toward a place that
runs code:

- **State.** Where would it live, who may read it, and what happens to it in an export or an
  erasure? A dynamic page that can store things is a table nobody declared.
- **The request.** Who is asking, what they submitted, what they may see. The moment a program gets
  the viewer it needs an authorization story of its own, and "it runs as whoever wrote it" stops
  being obviously right.
- **Anything outbound.** Invariant 104 already says a member-supplied url is an instruction to make
  a request. A program that could make one is that, with a loop around it.

**Ask before deciding any of those.** The bar has not moved -- can one person enumerate how it
fails -- and each answer is a new class of failure rather than a new field on an existing one.

Open questions -- **ask before deciding these**:

- **Whether push should have a producer**, and what would generate one now. A dynamic page is the
  obvious candidate and it is exactly the kind of power that needs the paragraph above settled
  first.
- **Whether inbound mail should do anything**, given nothing consumes it. The alternative is
  removing the SMTP listener too and keeping only sending.
- **Config inheritance.** The deepest applicable config wins outright; ancestors do not merge.
- **Session rotation.** A token is issued once and lives until revoked.
- **Cookie scope across shared databases.** Two domains share an account space but the cookie is set
  per host, so signing in at one does not carry to the other in a browser.
- **Two-factor beyond email.** `password_and_code` works end to end; authenticator apps and recovery
  codes do not.
- **Refresh tokens for MCP.** Only `authorization_code` is implemented.
- **HSTS.** Still not sent, now that TLS works.
- **An external event bus.** `EventBus` is an interface; `LocalEventBus` is the only implementation.
- **Analytics persistence.** The access log is memory only, so a restart loses it.
