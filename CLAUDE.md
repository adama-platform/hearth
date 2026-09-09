# Hearth — Claude Code Instructions

## What this is

**One person's infrastructure, on one machine, with AI as the interface.** A Ranch OS: a gym log, a
ranch to-do list, habits that graduate, and a way to get a few friends to agree on a Thursday. One
jar, one directory, no company.

Three things it does, and everything else is underneath them:

1. **The gym.** A per-person Hevy key and MCP tools over their API -- read workouts, build routines,
   and *invent exercises*, because the mobility work worth programming is not in anybody's standard
   list.
2. **The ranch.** Tasks that walk a named state machine, habits with a cadence that can graduate,
   and a daily sheet with a horizon to pull from.
3. **Getting people together.** Votes an agent opens and other agents vote in, converging a pool of
   options; availability given as either a calendar link or a rough weekly shape, with the agent
   told which.

Underneath: accounts and approval, a website, dynamic pages, user tables, files, mail both ways,
push, TLS.

**It was a community server and is not one now.** A discussion board, a calendar, an invitation
funnel, a members directory -- all of it is in the git history and none of it is coming back. It was
software looking for a community rather than a person with a problem.

**Multi-user stays, deliberately.** Not to host anybody: to invite four friends into the parts that
need more than one person. A friend needs `agent_connect` before their agent can act, and that stays
a permission rather than a membership baseline.

**AI is the interface, not a feature.** The screens exist and work, but the design target is an agent
doing the typing. A tool description here *is* a prompt; the guidance is generated from what exists
rather than written down; and anything a person can do, an agent should be able to do *as* that
person. There is no argument anywhere for *whose* gym, list or ballot -- every call uses the id of
whoever connected the agent.

No money will ever move through this. The scale target is one person and a handful of friends: when
a simple approach works at that scale and a general one scales further, take the simple one and say
why in a comment.

**Open source so somebody else can bend it.** The useful thing is not the shape -- it is that the
whole of it fits in a context window, so a person with a different ranch can ask an AI to make it
theirs rather than asking it to understand a platform.

## Ground rules

- **A finding gets reproduced, fixed, and then written into the code that fixes it and the test that
  proves it.** There is no standing list of known-broken things and there have been two -- a
  `PROBLEMS.md` and later an `AUDIT.md` -- both closed and both deleted. The reasoning lives where
  somebody will actually meet it: in the comment above the clause that does the work, and in the
  javadoc of the test that fails if it comes back. Reproduce it from the outside first, fix it with
  a test that fails before and passes after, and say in the comment what the wrong version did.
- **What has never been verified is listed under [Not verified](#not-verified) below**, because that
  is different from a defect and gets a different kind of attention.
- This repository is **Hearth**. There are four documents and they are kept true: `CLAUDE.md`,
  `README.md`, `MISSION.md` and `SECURITY.md`. `just docs` checks the mechanical parts of all four.
  **`SECURITY.md` is the security model, not a list of defects** -- what is defended, from whom, and
  what an operator has to do that the software cannot do for them. A finding does not go in it; a
  finding gets fixed, with the reasoning in the comment and the proof in the test, exactly like every
  other kind of finding. The check that keeps it honest is that every setting it tells somebody to
  set has to be one the server actually reads: a hardening checklist naming a key that does not exist
  is worse than no checklist, because somebody sets it and believes they are protected. There is no
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
just reset-data           # delete the local databases and start over
just check DIR            # load a config tree and exit; never opens a socket
just docs                 # do the documents still describe this program?
just suite                # did every test actually run?
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

`SECURITY.md` rots in its own way: it makes claims about *behaviour under attack*, which is the kind
of claim that stays plausible long after the code changed. Re-read it whenever a guard moves.

`README.md` is the vision and the current state, and it is the one that rots fastest because it is
the one that makes claims. Its status line, its road, and its feature descriptions are all
assertions with an expiry date. `MISSION.md` changes rarely — it is why, not what — but when a
commitment there stops being true in the code, that is the most important documentation bug the
project can have, and it gets fixed in the code rather than in the document.

**Shipping.** `just package` produces `hearth.jar`; copy it to the box. There is no release recipe
and no version number, and both are decisions rather than gaps.

`Server.VERSION` is the literal string `MAIN`. Nobody resolves this jar from a repository, so a
version number on it would promise a thing nobody is tracking -- that 0.3.1 differs from 0.3.0 in
some describable way, that upgrading between them is a decision. What is true is that the jar was
built from main, and that is what it says; the commit is the identity when a bug report needs one.
There used to be a `${revision}` property, a SNAPSHOT default, a `--version` self-check and a
release recipe that tagged and published to GitHub -- every part of it serving a distribution model
this project does not have. Those recipes are gone; do not write them as commands here, because a
backticked recipe name in these documents is checked against the justfile.

`just package` warns when `src/main/resources/3rd` is missing, because those files are not in git
and a jar built without them ships an editor that silently falls back to a textarea. It warns rather
than fetching: a build that quietly reaches the network fails differently on a machine that cannot.

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
  calendar/CalendarRoutes.java    an agenda, an invitation to answer, and the URL a phone subscribes to
  calendar/Events.java            one person's calendar, keyed on the UID an organizer sends
  calendar/IcsFile.java           RFC 5545 both ways; round-tripping is the property that matters
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
  cli/Setup.java                  --setup, --domain-setup, --setup-email, --setup-mail,
                                  --test-email
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
  content/Mutations.java          addresses that answer POST and run a program; the only way anything writes
  content/Site.java               rendering + the three caches + the event listener that invalidates them
  content/TemplateField.java  the fields a template declares; the page editor renders them
  content/TemplateRecord.java one template, and whether it publishes a directory index
  content/TextPatch.java          the line diff the history rests on; exhaustively property tested
  events/EventBus.java            the interface; LocalEventBus is the in-process ring buffer
  events/EventListener.java   what a cache implements to hear about a write
  events/LocalEventBus.java   the in-process ring buffer, notified inline on the writing thread
  events/MutationEvent.java   domain + table + key + kind; flat so it can leave the JVM later
  hevy/Hevy.java                  Hevy's API on somebody's behalf; one host, hard-coded
  hevy/UserKeys.java              keys held for a service somewhere else; one row per person per service
  inbox/Delivery.java             a message that arrived, turned into one somebody can read
  inbox/InboxRoutes.java          read, then reply or delete; there is nowhere else for it to go
  inbox/MailHtml.java             the most hostile input here, and nothing remote is ever fetched
  inbox/MessageFiles.java         the octets on disk, so an attachment can be re-read and refused
  inbox/Messages.java             what is in the inbox, which is the only question the front screen asks
  inbox/MimeTree.java             every part with its bytes; lenient in, strict out
  inbox/Outgoing.java             a message this server wrote, plain text and base64 always
  inbox/Postman.java              sending as somebody, with SPF, DKIM and DMARC all aligned
  inbox/PushOnArrival.java        the first thing here that produces a notification
  inbox/Safety.java               what may leave, and what a file has to be before it does
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
  smtp/Arc.java                   RFC 8617; a chain is started, never extended on faith
  smtp/AuthResult.java            what each said, and the Authentication-Results header
  smtp/Dkim.java                  RFC 6376; canonicalization is the whole difficulty
  smtp/DkimSigner.java            the same canonicalization run backwards, so both halves agree
  smtp/Dmarc.java                 RFC 7489; alignment is what makes the other two mean anything
  smtp/Envelope.java              one message as it arrived; envelope kept apart from headers
  smtp/ForwardConfig.java         the smtp.forwarding block: the two secrets and the TLS floor
  smtp/Forwarding.java            forwards before it answers, so there is no queue and no bounce
  smtp/MailKeys.java              one signing key, and the DNS record that publishes it
  smtp/MailLog.java               what arrived, what was decided, and what the far end said
  smtp/MailReceiver.java          what happens to it once it has; the seam
  smtp/Mailboxes.java             the addresses that exist, and the ordered rules over them
  smtp/MimeParts.java             enough MIME to find the calendar part of a real reply, and no more
  smtp/Relay.java                 one message out to somebody else's exchanger, over TLS
  smtp/SenderCheck.java           all three checks, and the one thing that gets refused
  smtp/SmtpConfig.java            the smtp block in config.cfg
  smtp/SmtpDns.java               the resolver seam, so every check is testable without a network
  smtp/SmtpRouting.java           which community a message is for, and the refusal to relay
  smtp/SmtpServer.java            inbound mail; its own event loop, off unless asked for
  smtp/SmtpSession.java           the RFC 5321 state machine, minus what nothing needs yet
  smtp/Spf.java                   RFC 7208; the ten-lookup cap is the security property
  smtp/Srs.java                   the return-path rewriting that keeps a forward passing SPF
  smtp/TerminalMailReceiver.java  prints it, the inbound twin of DevBoxMailer
  smtp/Workspace.java             what has to be true in DNS and at Google, generated from here
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
  tasks/Docket.java               what is on today, mailed at six, and never when there is nothing
  tasks/Processes.java            named state machines a task can walk; the order is the meaning
  tasks/Tasks.java                tasks and habits in one list, and the sheet that says what today is
  template/Templates.java         mustache, compiled at boot
  theme/Theme.java                six colours twice, and the CSS every layout interpolates
  theme/Themes.java               the palettes for one community, cached because every render asks
  vote/Availability.java          a weekly shape or an ICS link, and telling an agent which it is
  vote/Calendars.java             fetched, kept an hour, reduced to busy windows and nothing else
  vote/Ics.java                   enough of RFC 5545 to know when somebody is busy, and no more
  vote/Votes.java                 a pool of options that evolves; two blobs and an append-only history
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
  smtp/StubExchanger.java  a mail exchanger on a real socket that keeps what it was sent
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

73. **A run leaves nothing registered on the runtime it closes.** Binding the host function
    registers a callback context, and a runtime closed while it still holds one never gives that
    memory back -- 135KB a request, measured, which is a gigabyte every six thousand of them and a
    box that falls over one evening for no visible reason. `removeCallbackContext` in a `finally`,
    because a page that threw leaks exactly as much as one that worked.
74. **That leak is asserted on the warning, not on memory.** The obvious test -- run it a lot and
    watch RSS -- passes identically with the fix and without it under surefire, whose smaller heap
    makes V8 collect often enough to hide the whole thing. A test that cannot tell the two apart is
    worse than none.

### The gym, the ranch, and getting people together

75. **There is no argument for whose.** Not for a Hevy key, not for a task list, not for a ballot.
    Every call uses the actor's id, so no phrasing of any request reads somebody else's -- which is
    the only defence that survives a model being told to try harder. "Not yours" and "not there"
    answer the same, because the alternative confirms that task 41 belongs to somebody.
76. **A key is held in the clear because it must be presented.** A session token can be a hash; this
    cannot. So it is never printed back (last four characters only), never in an export, one row,
    one button to clear it, and gone on erasure.
77. **The host is a constant and there is no `get(url)`.** The path comes from a closed set of
    methods, an id that is not `[A-Za-z0-9_-]` is refused rather than escaped, and redirects are not
    followed. Invariant 111 is about a member-supplied url; this is the opposite case and stays that
    way by construction.
78. **Somebody else's unstable API is passed through as JSON.** Hevy say they may change or abandon
    it. Mapping their shapes onto records here would be a second thing to fix every time theirs
    moves, and a model reads JSON perfectly well.
79. **An enum is checked here, not by them.** A refusal naming the field and listing what is allowed
    costs a model one turn; a 400 from somebody else's server costs it several and the message is
    not ours to write.
80. **`blocked` is a veto, not a low score.** One removes an option however many yes votes it has,
    because a date somebody cannot attend is worse than no date. Without it the arithmetic produces
    evenings that read as popular and that somebody is out of the country for.
81. **The vote history is append-only and is the feature.** What everybody asks afterwards is not
    what won but why, and a tally that cannot show its working is one nobody trusts -- doubly so
    when half the voters are agents acting for people who were asleep.
82. **Narrowing is an agent's job; deciding is a person's.** The tool says so in as many words. An
    agent that settles an evening on its own initiative produces a real argument between real
    people.
83. **An availability answer says what kind of answer it is.** A rough weekly shape handed over
    without that label is a calendar as far as an agent is concerned, and it will propose a night
    somebody has had booked for a month. This server never fetches the ICS: that would mean holding
    a copy of the calendar the person was avoiding sharing.
84. **Somebody who has said nothing is absent, not listed as unknown.** A row saying "we do not
    know" invites an agent to fill the gap with a guess.
85. **Done/not-done is a lie about most work.** A task can walk a named process, and a state that is
    not in that process is refused rather than stored -- a typo otherwise puts work in a state no
    screen lists, found months later. A task with a process starts at that process's *first* state,
    or it would claim to have steps and be unable to name one.
86. **A habit graduates rather than being deleted.** A habit exists to stop needing to exist, and
    the marks are the evidence it worked. Deleting throws that away; graduating keeps all of it and
    takes it off the sheet.
87. **Marks are one row per day, not a counter.** The question is *which days* -- a streak, a gap,
    the month it fell apart. A counter gives the number and never the shape, and the shape is what
    decides whether to graduate.
88. **A streak counts back from today, and today being undone does not break it.** Otherwise every
    streak reads zero every morning, which is wrong and is the most discouraging thing a tracker can
    do.
89. **Overdue work is in today's list.** A separate overdue section is where things go to be
    ignored; in today's list it is simply what has to happen, which is true.

90. **How a vote decides is a property of the group.** Consensus works for five people and fails for
    twenty, where somebody is always away, every option collects a veto and the group that wanted a
    party gets none. `majority` maximises who can come; `consensus` is the default because the small
    case is the common one and an unattendable date is the worse failure there.
91. **The host's block is final in either mode.** A majority can outvote anybody about whether an
    evening is convenient and cannot vote somebody into having twelve people in their kitchen.
    Attendance is a preference; hosting is work somebody agrees to do.
92. **One person is asked before twelve are told.** "Will you host on the 9th" can be answered no;
     "we are meeting at Ana's on the 9th" cannot. The invitation is refused until the host has said
     yes, and a no puts the vote back to narrowed rather than ending it -- the group still wants an
     evening.
93. **This server fetches a calendar; it never keeps one.** Only busy windows are stored, never the
     summaries: what a scheduler needs is when somebody is not free, and what an ICS carries is who
     they were meeting. Storing the words would make that table the most sensitive thing on the
     machine in exchange for nothing.
94. **A calendar link is a member-supplied url** and gets everything invariant 150 asks for: https,
     resolved and refused if private, no redirects, a timeout and a ceiling. An hour's cache,
     refreshed lazily, because five people and a conversation is five fetches.
95. **A repeating event is a maybe, not a wall.** RRULE is expanded over a bounded window and every
    occurrence is marked soft. That is the difference between a scheduler that works and one that
    does not: everybody has a standing something, so treating repeats as walls answers "no evening
    works" for any group of five -- right, and useless. VTIMEZONE and EXDATE are still unread, which
    is acceptable only because of the next one.
96. **An uncertain conflict is never reported as a certain one.** The rule the whole calendar path
    follows. A missed window proposes a time somebody then blocks, which is the mechanism working; a
    wrongly-firm one silently removes a good evening nobody ever knew was considered. It is also why
    firmness has to survive the cache -- it did not, and every maybe came back a certainty an hour
    after it was fetched.
97. **There is always an imperfect night, so weigh rather than filter.** What comes out is the
    evening that costs least, what it costs, and to whom -- with every number that made the ranking
    beside it, because a ranking nobody can interrogate gets one wrong answer before it is never
    trusted again.
98. **A ballot beats a calendar, in both directions.** A calendar is an inference; a vote is a
    person speaking. Somebody saying yes on an evening their calendar objects to has already moved
    it, and somebody blocking a clear-looking evening knows something the file does not.
99. **Silence counts for nothing, not against.** Somebody who has not voted and shares no calendar
    moves no score, because a ranking that reads silence as agreement or as refusal is making a
    statement about people who have not spoken.
100. **Hosting and flexibility are the seed, and free/busy cannot say either.** One person hosts and
     has a full calendar; another is free most evenings and immovable on three. The same first
     proposal is right for one and wrong for the other, and an agent given only free/busy makes it
     for both.
101. **A challenge finishes itself.** A habit with an end graduates the day after its last, because
     a thirty-day challenge still asking on day forty is the stale checkbox this exists to replace.
102. **Nothing on the docket means no email.** A daily message that arrives with nothing in it is
     filtered within a fortnight, and then the one that mattered goes to the same folder.

### Tables

103. **A second database file, and that is the whole safety argument.** The system schema is code,
    upgraded by diffing, never dropped from; a user table's shape is whatever somebody typed this
    afternoon and the operations are CREATE, ALTER and DROP. One file would put a `DROP TABLE` on
    the connection holding every account in the community. `<domain>.data.mv.db` sits beside
    `<domain>.mv.db`: deleting it loses every user table and nothing else.
104. **Names are validated, then prefixed, and both matter.** Validation makes a name splice-able at
    all; the `t_`/`f_` prefixes are what make it *safe*, because MODE=STRICT reserves the standard's
    keywords and `value`, `order` and `key` are the first three things anybody names a column.
    Prefixing kills the class rather than keeping a denylist that is wrong at the next upgrade.
105. **A page names a function, never a column.** Every function is generated from a stored
    definition's own strings, so there is no filter argument, no operator and no fragment of SQL. An
    index is a declaration rather than a hint: declaring one is what creates the `_list_` function,
    which makes the set of indexes exactly the set of questions anybody may ask.
106. **A write invalidates the id, both sides of every index that moved, and the listings.** Naming
    only the *new* index value leaves the row cached under the value it used to have, which is a
    member still listed in the group they just left. That is why an update reads the old row first.
107. **A page reads; it never writes.** A dynamic page runs for every request including a crawler's,
    so a page that could insert is a table filling itself with whatever fetched it. Writing is the
    admin section's, where there is somebody to hold responsible.
108. **Asking for a table that is gone throws.** The tempting alternative -- an empty list -- reads
    exactly like "no rows yet", so a page whose table was dropped this morning renders an empty
    listing and nobody finds out.
109. **One function crosses into Java and it takes a string.** `__data(json) -> json`, dispatched on
    the Java side, so no object graph is converted across the boundary and adding a capability is a
    case in a switch rather than a binding with new lifetime rules.
110. **A query parameter arrives as the strictest type it honestly is.** `?page=2` is the number 2,
    because every page that reads it does arithmetic and `"2" + 1` is `"21"` -- a plausible wrong
    answer, which is the worst kind. A leading zero or a `+` stays text, because that is somebody's
    identifier rather than a number.

111. **A hidden row is absent, not flagged.** The published read filters it out *and* does not carry
    the flag, because absent is stronger than false: there is nothing for a page to test, so no page
    can be written that behaves differently for a row somebody might later hide. It is not a delete
    -- the row keeps its id and unhiding is a checkbox.
112. **The cache key carries what the read was allowed to see.** Without it an admin browsing the
    table editor fills the cache with hidden rows and the next page render serves them: a visibility
    rule turned into a race, correct on a quiet machine and wrong under traffic.
113. **A GET never writes.** Pages render and mutations write, split by method and by address, so a
    crawler, a preloader or a link checker cannot change a row by reading the site. A page's
    prologue does not contain the merge function at all -- absent rather than refusing.
114. **A mutation needs an approved member and a token.** A public POST that writes is a queue
    somebody else fills; without the CSRF check another site's form posts here with a member's
    cookies. `csrf()` exists so a page can render a form that works.
115. **A mutation that is off answers 404.** Whether a draft exists at an address is not something an
    anonymous POST should be able to discover.
116. **A merge names its keys and leaves the rest.** That is what makes it safe from a form showing
    three of nine fields; treating the delta as the whole row blanks the six nobody submitted while
    looking like it worked.
117. **Every reason, and nothing written unless all of them pass.** A caller sending four fields with
    two wrong is told about both, because the alternative is finding them one save at a time -- and
    a partial merge would leave a row half-updated with `success:false` beside it.
118. **A program can never change `hidden`.** It is refused by name rather than ignored, so a caller
    finds out rather than wondering why it had no effect.

### Settings

119. **What lives in the database is decided by what a setting is about, not how awkward it is to
    change.** Product and presentation are the community's. Anything deciding who gets in, what a
    credential is, what a program may do, or how many bytes a request may carry is the operator's
    and stays in a file. `admin_emails` is the sharpest case and it stays.
120. **A setting's key is the path it had in the config file**, and a value is applied by writing it
    into a copy of that file's JSON and parsing the whole thing again — so the check that refuses a
    bad value at boot is the same check that refuses one typed into the admin section.
121. **The file seeds; the database overrides; clearing reverts.** A row exists only where somebody
    decided something, and the rebuild always starts from the file, never from the last rebuild.
122. **A write rebuilds and swaps; a read is still a field access.** Triggered from the DAO rather
    than the handler, for the reason invariant 45 gives.
123. **A shared database is one set of settings, and one clock** — the same rule that makes it one
    account space.
124. **No agent tool reaches any of it, and the proof is that there is no tool** — not one that
    refuses, which would still appear in a listing and cost a model turns.

### The model endpoint

125. **An agent is a session with a bit set, never a parallel notion of identity.** That is what
    makes revocation, expiry, the reaper and the cap work without a second implementation of "still
    valid" that would eventually disagree with the first.
126. **Every AI rule is enforced in `AiSurface`, once.** A rule enforced in fifteen tools is a rule
    that will be forgotten in the sixteenth.
127. **Human only is asymmetric, on purpose.** Reads are *invisible* — absent from listings, searches
    and fetches. Writes are *refused out loud*. An agent can never set or clear the bit. A locked
    page that merely looked empty to a write would be overwritten by an agent asked to "add an
    about page"; a write claiming success while doing nothing teaches a model it succeeded.
128. **The connection is a permission, not a rank.** `agent_connect` is granted in a role and
    re-checked at consent, at redemption and on every call, so taking it away stops an agent at its
    next request.
129. **A write is refused by name; a read is narrowed.** Refusing a member's assistant a listing
    would make the tool useless; answering in full would hand them a draft they cannot open.
130. **A tool that could only ever refuse is not offered at all**, and a narrowed listing needs the
    same narrowing on the fetch-by-id beside it — the oldest shape of this bug.
131. **What is advertised and what is executable are checked against each other, for every tool.**
    Two hand-maintained lists agree until somebody adds a tool.
132. **A structured argument has to arrive as structure.** `unwrap` once fell through to `asText()`,
    which for a container node is the empty string, so every nested object arrived as `""` and the
    handler's correct refusal was unreachable.
133. **There is no AI tool for a bundle.** It is the one view of the content table that ignores
    human-only, and invariant 127 survives by that view not existing for a model.
134. **A tool description is a prompt.** The model reads nothing else about this server, so they say
    what a thing is *for* and when not to use it.
135. **Redirect matching is an explicit prefix list and nothing else** — no wildcards, no host-suffix
    matching. A prefix with no path is normalized to end at the authority boundary, because
    `startsWith` has no idea where a hostname ends, and a code sent to the wrong host is an agent
    token handed to whoever owns it.

### Uploads

136. **The extension decides what an upload is; the browser's content type is thrown away.** The
    allow list is closed, `text/html` is not on it for any extension or configuration, and `svg` is
    deliberately absent — it is a document that can carry script and arrives looking like a picture.
137. **Nothing about an attachment's address is a path.** The id is a long, the extension is looked
    up in a table, and the file is computed from both.
138. **Private is the default and it answers 404.** Whether a private file exists is itself private,
    and a sign-in form is no use to the `<img>` tag that asked.
139. **`Cache-Control: private` on every attachment, always.** These are frequently photographs of
    somebody's children.
140. **The referrer check is a bandwidth measure, not a boundary.** A request with no referrer is
    honoured, because browsers omit it constantly.
141. **One path is allowed a body bigger than a form, and the pipeline decides that from the request
    line**, before the aggregator buffers anything.
142. **The garbage collector's marking is the dangerous half, so it reads everything** — including a
    page's history, which is the one nobody thinks of.
143. **A partial scan offers nothing.** If any source could not be read, the answer is "I do not
    know", and a delete button on top of that offers to remove files it never looked for.

### Push

144. **A push subscription cannot outlive its session**, and its VAPID keypair dies with it — so
    "sign me out" means unreachable, not merely unwatched.
145. **A push says who and where, never what.** It crosses somebody else's infrastructure and lands
    on a lock screen.
146. **Every step of subscribing is a no-op the second time**, so a browser whose subscription was
    rotated repairs itself rather than going silently dead.
147. **The manifest is declared on every page, and its icons are fetchable.** `AppIcon` draws them at
    request time, so invariant 151 holds and a community that changes its colours changes its icon.
148. **The worker has a fetch handler and still caches nothing.** A browser will not install an app
    whose worker cannot answer a navigation offline; the only thing built inside it is a "no
    connection" page, for which stale is not a possible state.
149. **The self-test reports two facts, never one**: "the push service accepted it" and "this device
    showed it" are different, and every push problem lives in the gap.

### Outbound requests

150. **A member-supplied url is an instruction to make a request.** https only, public addresses only
    *after resolution*, no redirects, a timeout and a ceiling. What actually closes DNS rebinding is
    https plus certificate verification — relaxing either re-opens it.

### Assets

151. **No bytes on disk except the database, the certificate cache, and what people upload.** Images
    are inline SVG from `Icons`; a page costs one request. Vendored browser libraries under `/3rd`
    are classpath resources baked into the jar — one artifact to deploy, nothing beside it to
    forget to copy, which was always the actual rule.
152. **Vendoring is redistribution.** Every third-party bundle travels with its licence, checked into
    git even though the bundles are not, and served at `/3rd/licenses`.

### Certificates

153. **Certificate work happens after the socket is open, never during boot.** HTTP-01 validation is
    the authority fetching a path from this very server.
154. **The ACME challenge is answered before anything can refuse it** — ahead of the shield, the
    method gate and host resolution, each of which can say no for a reason unrelated to
    certificates.
155. **No certificate is worth failing to start over.** A domain that will not validate gets a loud
    complaint and a retry; the server serves plain HTTP throughout.
156. **Port 80 never becomes a redirect.** It serves the site *and* answers the challenge; turning
     it into a redirect would quietly break renewal three months later.
157. **"Ready" means every listener is bound.**
158. **Report what happened, not what is about to.** The boot output prints each certificate as it
     actually lands or actually fails.
159. **A wildcard is not a way to serve subdomains**, because HTTP-01 cannot issue one. `subdomains`
     is the answer: a written-down list, ordered along with the domain.
160. **A named subdomain is the same community, never a second one** — one config, one database, one
     set of accounts, which is what makes it safe to accept mail for.

### Mail

161. **This server never relays.** Inbound mail is accepted only for a domain with a config file,
     matched exactly, and refused at RCPT before a body arrives. An open relay is found within days.
162. **One message, one community.** Recipients on two domains are two deliveries.
163. **Advertise only what is honoured.** EHLO names SIZE and 8BITMIME and nothing else.
164. **The ten-lookup cap in SPF is the security property**, counted across the whole evaluation:
     an unbounded record is amplification on the sender's behalf.
165. **A DNS failure is temporary, never a forgery.** `temperror` throughout, so an unreachable
     nameserver bounces nothing.
166. **Only what the domain owner asked for gets refused** — `p=reject` and nothing else. An SPF
     failure alone means a mailing list far more often than a forgery.
167. **Nothing vouching for a message is not the same as nothing objecting to it.** The fallback for
     a domain with no DMARC record is SPF or DKIM actually *passing*; it once also accepted anything
     reporting `=none`, which is present for exactly those domains and made the other clauses dead.
168. **There is one email layout**, and every message says what it is, why it arrived and what
     interacting means. The footer is built by `MailLayout` and is not optional, in both halves —
     spam filters read the text.
169. **The wording of a message is a community's; the shape of it is not.** Three boxes; the layout,
     the button, the plain-text half and the footer stay in `MailLayout`.
170. **A flow declares what it can say.** `availableParameters()` is printed beside a filled-in
     preview, because a template naming something that does not exist renders as a hole and nobody
     notices until it has gone out.

### Forwarding

171. **A forwarded message is not modified.** Not a footer, not a subject tag, not a re-encode, not
     a reordered header. The sender's DKIM signature covers the body and most of the headers, and it
     is the strongest thing a forwarded message carries; one changed byte destroys it and leaves a
     message failing both SPF and DKIM at the far end, which a receiver cannot tell apart from
     tampering. Headers are prepended and nothing else happens.
172. **The return path is rewritten and the visible sender is not.** SPF asks about the envelope, a
     person reads the header. Rewriting the one nobody reads is what makes a forward pass at the far
     end; rewriting the other would be lying about who wrote the message.
173. **An SRS address this server did not write reverses to nothing.** The MAC is the only thing
     standing between a rewritten return path and an open relay, and it is checked in constant time
     -- "it is only four characters" is exactly the case where guessing is cheapest. A stamp older
     than three weeks stops reversing, because a return path that works forever is a forwarding
     address somebody harvests once and uses for years.
174. **A chain is started, never extended on faith.** Adding `cv=pass` to somebody else's ARC chain
     means asserting a verdict on arithmetic this server did not do, and `cv=fail` means reporting a
     failure nobody observed. Mail arriving straight from a sender has no chain, so the common case
     is sealed and the uncommon one is left honest and logged. This is invariant 96 in another
     costume.
175. **Nothing is bounced, because nothing is accepted that cannot be delivered.** The message goes
     out before the 250 goes back, and the far end's verdict is handed straight to the sending
     server -- a 451 becomes a 451 and a 550 becomes a 550. That removes the queue, the spool and
     the bounce generator together: the *sender's* server writes the failure report, to the address
     it really sent from, rather than this one mailing a report to a return path a spammer chose.
176. **An address nothing claims is refused at RCPT**, before the message arrives, so a mistyped
     address comes back to whoever typed it and a directory harvester costs one line per guess. A
     domain with no addresses and no rules accepts everything, so turning this on is a decision
     rather than an outage.
177. **Rules are ordered and the first match wins.** It is the only evaluation order a person can
     hold in their head, and "every matching rule applies" means two forwards deliver two copies to
     somewhere awkward. An action the database holds that this software does not understand drops
     the message rather than forwarding it -- a rule whose meaning was lost must not send mail
     somewhere nobody chose.
178. **Dropping accepts; only the door refuses.** A 550 for a message somebody simply does not want
     tells the sender their address is wrong when it is right.
179. **A forwarder must not pass on what failed the sender's own policy.** It would be delivering,
     in this machine's name, a message the domain owner asked the world to refuse -- and it is this
     machine's address the receiver records. `enforce-dmarc` is off by default everywhere and on for
     a forwarder.
180. **The mail log is metadata and a short preview, never the message.** A forwarder that keeps
     copies is a mail store nobody agreed to run. An erasure deletes those rows outright rather than
     blanking them: every other row here keeps its words and loses its author because the words are
     somebody else's conversation, and a log row is nothing but who wrote to whom.
181. **The instructions are generated from what is running.** A selector, a key and a hostname
     written into a document are wrong the first time somebody changes one. What this server cannot
     see from the inside -- MX, reverse DNS, a setting in somebody else's admin console -- it prints
     the command for rather than showing a tick it would be guessing at.


### Mailboxes

182. **A mailbox is a place; an account is a person; a rule joins them.** An administrator can give
     one person several addresses, and every message remembers which of them it arrived at --
     because a reply goes out *from* that address, so a conversation continues from the address the
     other side already knows and the signature aligns with it.
183. **A `deliver` rule needs an owned address, and is refused at the form otherwise.** A message put
     in a mailbox nobody owns sits in a table no screen lists, which is mail lost rather than
     delivered. Caught while somebody is looking at the rule editor, not the first time real mail
     arrives.
184. **The inbox has no folders, no bin and no "later".** A message is in the list or it is not, and
     the two ways out are to answer it or to be rid of it. Every mechanism for keeping something in
     a list without deciding about it is the mechanism by which an inbox reaches four thousand.
     Delete is a delete: the row and the file, together.
185. **Reply-all is the default and every address of ours comes out of it.** The people on a thread
     are on it deliberately; replying to yourself puts a copy in the inbox you are emptying.
186. **Nothing in a message is ever fetched from anybody else's server.** A remote image is a
     tracking pixel that tells the sender the moment you opened it, from where, on what. Every one
     is removed and *counted*, so the screen can say how many -- a fact about the sender rather than
     a silent decision. `cid:` images are parts of the same message and are rewritten to this
     server.
187. **The allow list is closed, and the bytes have to agree with the name.** A deny list of
     dangerous extensions is wrong the day somebody finds the next one, and there have been dozens.
     The extension read is the *last* one, because `invoice.pdf.exe` is an executable. An image is
     opened far enough to know it is one: a sixty-thousand-pixel PNG is nine kilobytes on the wire
     and fourteen gigabytes in a decoder.
188. **A refused part is listed, never dropped.** With its name, its size and the sentence saying
     why, and the original is still downloadable. Silently removing an attachment is how somebody
     misses a contract and never learns there was one.
189. **A part is served with the type this server chose, as a download, with `nosniff`.** A message
     may claim anything about its own attachment, and a browser that believes it is the whole
     attack. Only an image that passed the dimension check is ever shown inline.
190. **The bodies are in the row and the octets are on disk.** One file per message: it is what an
     attachment is re-read from and what "show me the original" hands back, and storing the parts
     separately as well would double the disk a photograph occupies to save a click nobody makes
     twice.
191. **A push says who wrote and where to go, and never the subject.** Invariant 145 applied to the
     thing it was always about: this crosses somebody else's push service and lands on a lock screen
     anybody in the room can read. This is also the producer push never had.
192. **Every read of a message carries the person's id in the query.** Not checked afterwards --
     part of the lookup, so there is no path through the DAO that returns another person's mail.
     Somebody else's message and one that never existed answer identically.

### The calendar

193. **An event is keyed on the organizer's UID, never on ours.** That is what makes an update an
     update: a meeting that moves arrives with the same UID and a higher SEQUENCE, and matching on
     anything else shows the old time and the new one side by side -- which is how people stop
     trusting a calendar.
194. **A lower sequence arriving later is a stale copy.** Mail is not ordered, and taking the most
     recent delivery as the truth means a message delayed twenty minutes undoes a change everybody
     has already seen.
195. **An organizer's update never overwrites what this person said.** Their REQUEST names
     everybody's PARTSTAT as they last heard it, which is out of date the moment it is sent; taking
     it as truth silently un-accepts a meeting somebody accepted.
196. **An invitation is imported, never applied.** It lands unanswered. A reader that accepts on
     somebody's behalf fills their week with meetings they never agreed to.
197. **A cancellation keeps the row and changes its status.** A meeting that vanishes from a morning
     somebody planned around reads as "that never existed" rather than "that was called off", and
     the second is the thing they need to know. It stays in the feed for that reason and is off the
     agenda for the same one.
198. **A REPLY carries one attendee: the person answering.** An organizer's software takes a REPLY
     as authoritative, so sending the whole list back resets what everybody else had said.
199. **The answer is recorded whether or not the message goes.** A calendar that refuses to remember
     "I am not going" because a mail server was busy is lying to the person holding it. The screen
     says which of the two happened.
200. **The feed token is hashed at rest and shown once.** It goes into a phone's settings and stays
     there for years, so a stolen database file must not be a list of working subscriptions. A wrong
     token and a revoked one answer identically -- this is the one URL on the server anybody can
     guess at.
201. **A repeat is expanded over the window being drawn, not over its own first hundred and twenty
     days.** A weekly standup set up two years ago has a start far behind today, and the other
     reading makes it vanish from the calendar of everybody who has been attending it. The
     iteration is capped separately from the results, or fast-forwarding a year runs out of budget
     before it arrives.
202. **A subscription is read-only, and that is said out loud.** Every calendar client on earth
     reads an ICS URL and none of them writes back to one. Two-way sync needs CalDAV, which this
     server does not speak.


### Appearance and the law

203. **A palette is six hex strings or it is the default.** It is interpolated raw into a `<style>`
     block, so every value goes through `Theme.isColour` and a slot that fails keeps what it had.
204. **Red means refused and green means it worked, and nobody may change that.**
205. **Light unless somebody says otherwise, and it is their choice rather than their laptop's.**
     `/~theme.js` sets the attribute before first paint — a file rather than an inline script
     because inline needs a nonce, and not deferred because deferred is a white flash.
206. **The two legal documents ship in the jar and are published from the first day.** A row exists
     only when a community has overridden one, so upgrading the software improves them.
207. **`/legal` is open to everybody.** Every email links to the terms and most go to somebody with
     no account yet.
208. **The cookie notice is a line in the footer, not a banner.** Two cookies, both strictly
     necessary, which is the category that needs no consent.
209. **The privacy policy this software ships is a specification.** Every promise in it is a thing
     the code does: `DataExport` and `Erasure`, reachable by the member and by an administrator.
     Changing the policy is changing a requirement.
210. **An erasure is checked by looking, not by remembering.** `RightsTests` walks *every column of
     every table* afterwards looking for the address, which is the only form of that test worth
     writing.

### Storage

211. **The schema is code.** Add a column where it belongs, bump `VERSION`, restart. A column added
     later must be nullable or carry a default — there is no correct value for existing rows.
212. **A column whose name has stopped being true gets renamed.** `Column.renamedFrom` declares it
     and the upgrader performs it, before it looks for anything missing.
213. **The upgrader adds, never drops or retypes.** A column the code no longer declares is reported
     and left alone, which is what makes the reduction safe for an existing database.
214. **A test that writes "hello" proves that "hello" fits.** Anything that stores what a person
     typed gets a test with a realistic amount of it in.
215. **Boot never drops anything; a person does.** The other half of invariant 213. Leftover tables
     are listed at `/admin/system/cleanup` with their row counts and dropped one at a time, by
     somebody holding `everything`. An operator who upgrades, hits a regression and rolls the jar
     back must still have their data, so the upgrader can never be the thing that deletes it.
216. **The table name on that screen is untrusted.** `Leftovers.drop` re-derives the leftover list
     and refuses anything not on it, using the database's own spelling rather than the form's.
     Without that the most powerful button in the admin section is an arbitrary `DROP TABLE` with a
     text field in front of it.
217. **A column nothing reads is not free.** It is a sentence in the privacy policy that has to stay
     true and a column every erasure test keeps walking. The ten address and geo columns outlived
     their feature by a whole reduction, with a dead `SELECT` list in `PeopleStore` naming them.

### Installing

218. **A walkthrough writes a file you could have written by hand, and says what it wrote.** They
     refuse without a terminal, because each exists to make somebody think and a pipe cannot think.
219. **A walkthrough run twice must not undo the first run.** Every question pre-fills from the file
     it is about to rewrite.
220. **`--install` needs no root and starts nothing.** The half that needs root is written out as
     `install.sh` to be read first.
221. **A second `--install` stages a jar; it never overwrites the running one.** Overwriting leaves
     the file on disk and the software in memory disagreeing.
222. **The unit asks for `CAP_NET_BIND_SERVICE` and bounds the set to it.**
223. **16px on every field, 44px on everything you can press, a visible focus ring on everything.**
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

Every table also carries a **`hidden`** column, implicit like `id`. A published read filters those
rows out and does not carry the flag at all; the admin's table editor at `/admin/tables/rows/<table>`
sees everything. `UserTables.See` is that distinction and it is part of every cache key, because an
admin's read must not put a held-back row where a page will find it.

**Mutations** (`content/Mutations.java`, `/admin/mutations`) are addresses that answer POST and run
a program. They are the only thing in this server that writes from a script: a page's prologue does
not define `<t>_merge_by_id` at all. A mutation needs an approved member and a CSRF token, is off
until somebody enables it, and answers 404 while off. A merge changes only the keys it names,
returns `{success, reasons}` with every reason, and cannot touch `hidden`.

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
**Forwarding** is `smtp.forwarding`, off separately: receiving is one decision and acting on what you
receive by connecting to other people's servers in your name is another. When it is on, `Mailboxes`
holds the addresses that exist at a domain and the ordered rules over them -- two actions, `forward`
and `drop`, first match winning -- and `Forwarding` is the receiver that reads them. A domain with no
addresses and no rules still lands in `TerminalMailReceiver`, so turning this on is per domain rather
than per box.

The scenario it was built for is moving one person's mail to this machine while somebody else in the
house stays on Google Workspace: MX points here, a rule forwards her address on, and neither of them
notices. That works only if the forwarded message still looks real at the far end, which is what most
of the code is about. **`Srs`** rewrites the return path to this domain so SPF passes, and reverses a
bounce coming home. **`DkimSigner`** and **`Arc`** sign the message and seal what the three checks
said on arrival, which is the record Gmail honours from a forwarder. **`Relay`** delivers over
verified TLS and hands the far end's verdict straight back, so there is no queue and nothing ever
bounces. **`MailLog`** keeps what happened, and **`Workspace`** generates the DNS and the Google
settings that have to be true elsewhere -- printed by `--setup-mail` and shown at
`/admin/mail/setup`.

The one signing key lives at `<root>/mail/dkim.key`, 0600 and refused if anybody else can read it. It
signs as whichever domain the message arrived for, so the same public record goes into DNS on each of
them.

**Delivered mail** is `smtp.forwarding` plus a `deliver` rule: the third action, beside `forward`
and `drop`, and the one that needs the address to belong to somebody. `Delivery` parses the message
once at arrival -- the MIME tree, both bodies, the sanitizer, every part through `Safety` -- and
stores the result, so opening a message costs a query rather than a parse. The octets go to
`<root>/mail/store/<id % 100>/<id>.eml` and are what an attachment is re-read from.

`/self/mail` is the reader and it has no folders. A message is in the inbox or it is not; reply or
delete are the two ways out, reply-all is the default, and delete removes the row and the file
together. A reply goes out from `delivered_to` through `Postman`, signed as that domain with the
envelope sender the same address -- no SRS, because the From header is already ours, which is the
difference from forwarding. `PushOnArrival` is the first producer push has ever had.

**The calendar** is `/self/calendar`: an agenda rather than a grid, invitations that arrive by mail
and wait to be answered, and an ICS URL a phone subscribes to. `IcsFile` reads and writes RFC 5545
both ways and `Events` keys on the organizer's UID so an update is an update. Answering sends an
iTIP REPLY to the organizer. There is no CalDAV, so a subscription is read-only -- said on the
screen rather than discovered.

**Mail is its own top-level admin section** and takes its own permission, `mail_route`: the screens
below can silently redirect somebody's post and show who has been writing to whom, which is not the
same decision as being trusted with the community's colours. Four sections -- `mail` (the rules),
`mailboxes` (`/admin/mail/addresses`), `maillog` (`/admin/mail/log`, with a panel and a per-message
view) and `mailsetup` (`/admin/mail/setup`, the generated DNS and Workspace guidance).

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

- **SPF, DKIM and DMARC have never seen real mail.** Tested hard against the RFCs with a fake
  resolver. `enforce-dmarc` is off by default because of this.
- **The organizational-domain rule is a guess.** Relaxed alignment strictly needs the Public Suffix
  List; this errs toward not aligning, so a wrong guess marks a message unaligned rather than
  passing a forgery.
- **The certificate path has never met a real authority.**
- **Nothing has been raced on purpose.** No test runs two writers at the same row. The caches are
  concurrent maps and the counters are atomic, and that is an argument rather than evidence.
- **Nothing has been run against a real dataset.** Every query is written for a few hundred rows.
- **No forwarded message has ever reached a real Google Workspace.** The whole outbound path -- SRS,
  the signature, the ARC seal, the relay, the verdict pass-through -- is tested against a stub
  exchanger on a socket that speaks SMTP and keeps what it was sent. That the message is unmodified,
  that the signature verifies, and that a 550 comes back as a 550 are all proven; that Gmail then
  files it in an inbox is not, and it is the kind of thing only real mail can settle.
- **Nothing has been checked against a second DKIM implementation.** The signer is checked against
  this repository's own verifier, which is the strongest cheap test available and is still two halves
  of one understanding of RFC 6376. An `opendkim-testmsg` run against a real message would be worth
  more than every test in `SigningTests`.
- **The ARC chain has never been verified by anybody.** It is produced to the RFC and read by
  nothing here; whether Google accepts the seal is unproven, and a chain nobody validates is
  indistinguishable from a chain nobody produced.
- **No real mail client has ever read a calendar this server wrote.** The ICS is round-tripped
  through this repository's own parser, which proves the two halves agree and not that Apple
  Calendar accepts it. The same caveat as the DKIM signer, for the same reason.
- **No inbound message from a real mail system has ever been parsed.** `MimeTree` is tested against
  messages this repository composed, including deliberately malformed ones. Real mail is stranger
  than anything anybody makes up on purpose, and the first week of it will find something.
- **Nothing has been tried with a large mailbox.** Every query is written for a person with a few
  thousand messages and the listing caps at a hundred; nobody has run it against ten years of mail.
- **Port 25 outbound is blocked by default at most hosting providers**, so the forwarder may not be
  able to connect at all until somebody asks them to open it. This is not a defect and it is the
  first thing to check.
- **The suite has flaky timeouts under load.** A handful of HTTP tests occasionally hit the client's
  ten-second ceiling; the set moves between runs and every one of them passes when its class is run
  alone. It has not been chased down, and it means a red suite needs reading rather than trusting.
- **Nothing here has ever talked to Hevy's real API.** Every refusal, the key handling, the enum
  checks and the request shape are tested against a stub that answers in their shapes; that their
  server accepts what this sends is unproven. A mock that agrees with whatever the code does would
  prove only that the code agrees with itself, which is why this is written down instead. Their API
  is also explicitly unstable by their own description, so this is the entry most likely to become a
  defect without anybody touching this repository.
- **No agent has driven the voting to a real decision with real people.** Two agents converging is
  tested; five friends and their assistants actually arranging an evening is not, and the failure
  modes there are social rather than technical.
- **The habit arithmetic has not seen a year.** Streaks, weekly cadence and graduation are tested
  against days this code made up. Nothing has run across a daylight-saving change or a new year.
- **The vendored browser libraries are not in git** (`src/main/resources/3rd/`). A fresh clone that
  runs `just package` gets a warning and a jar whose rich editor falls back to a textarea; `just
  third-party` fetches them. Their **licences are in git**.

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
