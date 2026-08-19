# Hearth — Claude Code Instructions

## What this is

A single-jar Java community server. One process, one jar, one config directory, hosting a handful
of communities of 100 to 1,000 people each: static website, generated-and-cached content, member
accounts, sessions, admin section. [MISSION.md](MISSION.md) is why it exists and what it refuses to
become; [README.md](README.md) is what it does today and the road.

The mission constrains the code in ways worth knowing before changing it. The scale target is a
design input. Nothing online is worth building unless it makes an in-person gathering more likely --
and *more frequent* beats *more impressive*, because friendship is made of hours and a monthly
dinner is 36 of them a year. No money will ever move through this server. AI is here to take the two
jobs that killed small communities -- the organizing nobody volunteers for, and the first read of
the thing that traumatizes whoever reads it cold -- under a leash the community can read.

The scale target (100 to 1,000 members per community) is a design constraint, not a caveat. When a choice
comes up between a simple approach that works at that scale and a general approach that scales
further, take the simple one and say why in a comment.

## Ground rules

- **A finding gets reproduced, fixed, and then written into the code that fixes it and the test that
  proves it.** There was a `PROBLEMS.md` for a while, holding sixteen findings and the reasoning for
  each; every one of them is now closed, and the reasoning lives where somebody will actually meet
  it -- in the comment above the clause that does the work, and in the javadoc of the test that
  fails if it comes back. A standing list of known-broken things is worth keeping only while things
  on it stay broken; after that it is a second place to look that says what the code already says.
  What that file was *right* about is the discipline: reproduce it from the outside first, fix it
  with a test that fails before and passes after, and say in the comment what the wrong version did.
- **What has never been verified is listed under [Not verified](#not-verified) below**, because that
  is different from a defect and gets a different kind of attention.
- This repository is **Hearth**. Edit `CLAUDE.md`, `README.md`, `MANUAL.md`, `API.md`,
  `LANDING.md` and
  `MISSION.md` here as things change. `LANDING.md` is the source copy for the public site, written
  for somebody who has never seen this software: it is checked by `just docs` like the rest, and a
  feature described there in the present tense had better be one that exists, because the roadmap
  in the present tense is the one mistake that would cost this project its audience.
  `API.md` is a *contract with somebody else's code*: changing
  an endpoint, an error code or the bundle format means changing it in the same commit, because the
  reader is a tool that is already relying on it. `MANUAL.md` is the operator's guide: any new config key, admin page, or failure
  mode an operator can hit belongs in it, and it is where the troubleshooting answers live. See
  **Keeping the documents true** below for when to re-read them rather than patch them.
- `../adama/` and `../goatbot/` are **read-only inspiration**. Never edit them. Pull patterns,
  don't copy licenses or package names. Primary reference for HTTP is `adama/web`.
- Java 21 (the JDK available here). Netty for HTTP, H2 for storage, Mustache for pages, Jackson for
  JSON, scrypt for passwords. Maven, one module. There is no MySQL and nothing that needs installing
  alongside the jar -- that is the mission, not a detail.
- Two-space indent, braces on the same line, `final`-free locals except where they matter — match
  what's already in `src/main/java/io/hearth/`.
- Comments explain *why*, not *what*. Class javadoc says what the class is for and what it refuses
  to do. Adama's style: a short paragraph at the top of each class.

## Build and run — use the justfile

`justfile` is the primary interface. **`just validate` is the gate**: clean build, full test suite,
packaged jar, then a live smoke test against the running jar over real HTTP. Run it before claiming
anything works, and after any change to the request path or the scanner.

```bash
just                      # list recipes
just validate             # THE gate: clean + package (runs tests) + live smoke + docs
just test                 # unit + HTTP tests
just test-one ServerHttpTests
just coverage             # jacoco; fails below the floor (80% line, 70% branch)
just package              # tests + ./hearth.jar
just package-fast         # skip tests; for iterating, never for validating
just run                  # serve ./configs into ./stores on 8080, verbose
just reset-stores         # delete the local databases and start over
just check DIR            # load a config tree and exit; never opens a socket
just docs                 # do the documents still describe this program?
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
versions match `Schema.VERSION`, and any test count the docs quote matches what the suite actually
ran. It deliberately checks nothing subjective — a false alarm
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
  Server.java              entry point; boot order IS the security model
  cli/Args.java            hand-rolled flag parsing; unknown flags are errors
  cli/Root.java            the one --root directory: config.cfg, domains/, dbs/, certs/, attachments/
  attach/Kinds.java        the closed table of what may be uploaded, and what each thing is
  attach/AttachmentConfig.java  the allow list, the ceilings, and the hotlink rule
  attach/Attachments.java  the record of every upload; folders, tags, and who may read it
  attach/AttachmentStore.java   where the bytes live: three methods, so the second answer is possible
  attach/DiskAttachments.java   <root>/attachments/<ext>/<id % 100>/<id>.blob, written atomically
  attach/BlobCache.java    the recently-served bytes, bounded by bytes and keeping what is asked for
  attach/Uploads.java      multipart, in memory, one file per submission
  attach/AttachmentRoutes.java  /attachment/<id>.<ext>, and the one path allowed a big body
  attach/AttachmentSweep.java   mark and sweep: every column that can hold a url, then the leftovers
  web/UploadGate.java      the ceiling, decided from the request line rather than after the body
  cli/Ask.java             terminal prompts, shared by every walkthrough
  cli/Install.java         --install: a systemd unit, a start script that swaps in a staged jar,
                           and the root half written out to be read before it is run
  cli/Setup.java           --setup, --domain-setup, --setup-email, --test-email
  common/ServerConfig.java config.cfg: ports, TLS, HTTP/2, limits, the clock
  common/Boot.java         ANSI boot output (respects NO_COLOR, non-tty)
  common/Verbose.java      the --verbose channel; lazy suppliers on the request path
  common/ConfigObject.java strict typed reader over Jackson; unknown keys are fatal
  common/ConfigException.java
  vhost/Hosts.java         Host header canonicalization; a security boundary
  vhost/DomainConfig.java  one loaded .cfg file, immutable; has() is the one surface question
  vhost/Surface.java       the parts of the product a community can switch off in one word
  vhost/DomainScanner.java the boot-time scan of <root>/domains (flat directory of *.cfg)
  vhost/DomainTree.java    immutable label tree; deepest-applicable-config resolution
  vhost/SiteUrls.java      per-domain account paths, validated and collision-checked
  async/AsyncQueue.java    one worker, one every 1.5s, a thousand waiting, and backoff on a failure
  events/EventBus.java     the interface; LocalEventBus is the in-process ring buffer
  events/MutationEvent.java domain + table + key + kind; flat so it can leave the JVM later
  cache/CachePolicy.java   ttl + ceiling, configured as a catch-all
  cache/TtlCache.java      the cache; invalidateIf() is the cascade
  cache/Caches.java        the per-domain policies
  store/Database.java      the swap point for MySQL/PostgreSQL; Dialect holds the differences
  store/H2Database.java    the only implementation today
  store/Schema.java        THE schema, in code; the database on disk is a cache of it
  store/SchemaUpgrader.java diffs live schema vs code, renames what was renamed, adds columns IN POSITION
  store/Store.java         one H2 database + its boot audit
  store/Stores.java        domain -> database, including use_database_domain sharing
  auth/LoginSecurity.java  every policy knob, parsed once at boot
  auth/Users.java          the emails table, including approval
  auth/Roles.java          who holds which role
  auth/RoleDefs.java       what a role means; admin is built in and refuses to be edited
  auth/Permission.java     the closed list of things anybody can be allowed to do
  auth/Access.java         who is an admin, who is approved, and the config escape hatch
  auth/Bans.java           refused addresses; cached in memory, invalidated off the bus
  auth/Sessions.java       write-through cache, reaper, session cap
  auth/PendingCodes.java   emailed codes in flight; memory only, never on disk
  auth/Accounts.java       users + sessions + codes + policy, per database
  auth/AuthSystem.java     domain -> Accounts
  auth/Passwords.java      scrypt
  auth/Tokens.java         session tokens, code generation, email normalization
  content/ContentStore.java the content and templates tables; every write emits an event
  content/TextPatch.java   the line diff the history rests on; exhaustively property tested
  content/ContentVersions.java  every version of every page, snapshot or patch
  content/Proposals.java   the history looking forwards: suggested edits waiting for a yes
  content/TemplateField.java the fields a template declares; the page editor renders them
  content/Site.java        rendering + the three caches + the event listener that invalidates them
  content/Feeds.java       pages built from the calendar, the address book and the members
  content/Bundle.java      every page and template as one JSON file, and the merge that brings it back
  content/Markdown.java    commonmark with every extension on; two renderers, one per kind of author
  tasks/Measure.java       the seven things a set can be, and what "more" means for each
  tasks/Records.java       a project, what a task is, one occasion of it, and what was recorded
  tasks/TaskStore.java     all four tables; ownership is in the WHERE clause, not in a handler
  tasks/TaskRoutes.java    /tasks: the projects, the board, and the screen a set is logged on
  people/ProfileRecord.java what somebody says about themselves
  people/Question.java     one question; the definition is a JSON blob
  people/AnswerSheet.java  one person's answers, as a blob plus counts
  people/PeopleStore.java  profiles, questions, answers; every write emits an event
  people/SurveyIndexer.java async recount of remaining questions, coalesced
  people/Invites.java      invitations and the funnel: sent, opened, converted
  people/Invitations.java  the one path that sends one: admin, member, and the reminder loop
  people/InviteConfig.java what an invitation says, and when it says it again
  mail/InviteMail.java     the HTML that survives Outlook, Gmail and dark mode
  board/Board.java         posts, threads, expiry and the packed watcher list
  board/Poll.java          what a conversation is deciding, and the arithmetic that decides it
  board/Polls.java         polls, what is on the table, and everybody's votes
  board/PollClock.java     counting one whose moment has come, and the event it becomes
  board/Inbox.java         notifications; they expire with the thread they are about
  board/BoardRoutes.java   the feed and one thread; POST changes things and redirects
  board/BoardConfig.java   whether a domain has a board, and how long a thread lives
  board/BoardCache.java    the feed and the rendered threads; dropped by the event bus
  board/Subject.java       what a comment is a comment on: a post, an event, a place
  board/CommentBox.java    one comment thread, wherever it is: the model and the POST
  board/CommentGroups.java clumping a long conversation by the age of each subtree's root
  board/CommentPhase.java  before the event, on the day, or afterwards -- computed, never stored
  board/Signals.java       votes and flags: what people think, and what they want somebody to read
  live/Signal.java         one thing that happened, in as few bytes as possible; never content
  live/LiveHub.java        the sequence, the waiters and presence -- all in memory
  live/Live.java           one hub per community
  live/LiveRoutes.java     /~live: the stream, the poll, and the one shipped script
  board/NotifyPrefs.java   how each person wants to hear about it; two settings, not one
  board/Notifier.java      the one place anything is sent; one thread for the whole box
  sms/Sms.java             the seam for text messages, which nothing implements yet
  sms/NoSms.java           and the implementation that says so out loud
  calendar/Calendar.java   events on days, RSVPs, and the seat counting
  calendar/CalendarRoutes.java  the list, one event, and answering
  calendar/Ics.java        iCalendar, written and read: the format every calendar already speaks
  calendar/Invitations.java  sending an event to everybody's calendar, and chasing the quiet ones
  calendar/IcsReplies.java   an answer that arrived by email, checked hard and turned into an RSVP
  calendar/IcsRequests.java  an invitation somebody mailed in, become an event, with its place found
  availability/Availability.java   when each person could come, their calendars, and what those said
  availability/BusyCalendar.java   somebody else's feed reduced to "cannot come then", and nothing else
  availability/CalendarFetch.java  the one place a member's typing becomes an outbound request
  availability/Heatmap.java        the weekly fold: how many would like an hour, how many are clear
  availability/AvailabilityIndexer.java  nightly pull, rebuild on change; never on a request path
  availability/Availabilities.java one grid per community
  availability/AvailabilityRoutes.java  /when: your week, and -- for planners -- everybody's
  places/Geocodes.java     every address wanting a point, put on the queue and written down later
  places/Placement.java    how the last lookup ended, and which of the two failures is worth retrying
  places/Geocoder.java     the seam: an address to a point, from somebody whose terms allow keeping it
  places/WebGeocoder.java  the three services that do, and the one-a-second their policies ask for
  places/GpsConfig.java    the gps block; off unless asked for, and fatal when the terms are unmet
  mail/Mime.java           the one message with a shape: multipart, so text/calendar draws buttons
  smtp/MimeParts.java      enough MIME to find the calendar part of a real reply, and no more
  smtp/CommunityMailReceiver.java  inbound mail: a calendar answer first, everything else printed
  calendar/CalendarConfig.java  whether a domain has a calendar
  people/MemberRoutes.java the directory of who is here, and one person's page
  people/SurveyForm.java   the boxes, and the merge rule, written once for both pages that ask
  people/SurveyRoutes.java /survey: what is left to answer, and everything you have said
  people/OrientationRoutes.java /welcome: a name first, then the community's questions
  people/ProfileText.java  somebody's own words, cut to a size that fits a listing
  people/Home.java         where somebody actually lives; its own record so a profile cannot carry it
  people/Distances.java    how far people would come, as buckets and never as a list of names
  people/Names.java        what to call somebody on a page another member is reading; never an address
  people/SurveyForm.java   the boxes, the merge rule, and the three-at-a-time chunk
  people/DataExport.java   everything held about one person, as one file, built when they ask
  people/Erasure.java      removing somebody from every table that names them, once
  people/InvitePixel.java  the tracking pixel, and what it honestly means
  analytics/AccessLog.java the last 5000 requests, with the queries the dashboard asks
  analytics/UserAgents.java browser/bot classification; unknowns registered verbatim
  certs/CertStore.java     <root>/certs: the ACME account and every key and chain
  certs/Challenges.java    HTTP-01 answers in flight; served by this server, not a bucket
  certs/Acme.java          the CA seam, so renewal logic is testable without a network
  certs/AcmeIssuer.java    the real thing, over acme4j; HTTP-01 only, so no wildcards
  certs/CertificateManager.java  what to order and when; one background thread, after bind
  certs/CertSetup.java     --setup-certs, the walkthrough that stops a rate-limit lockout
  certs/TlsContexts.java   which certificate to present per hostname; live, so renewals land
  web/BounceHandler.java   the redirect-only listener, for load balancers
  mcp/McpConfig.java       whether a domain talks to models, and on what terms; off by default
  mcp/Vendor.java          known connectors and the redirect prefixes they may come back to
  mcp/OauthClients.java    the registered connectors table
  mcp/AuthCodes.java       authorization codes in flight; memory only, single use, PKCE S256
  mcp/McpRoutes.java       discovery, registration, consent, token, and the JSON-RPC endpoint
  mcp/McpTools.java        the tools a model is offered; a description here IS a prompt
  mcp/AiSurface.java       the single gate: human-only and read-only are enforced here, once
  mcp/AiLog.java           the last 1000 agent actions, arguments and results kept as JSON
  api/ApiConfig.java       whether a program may hold a token here, and for how long
  api/ApiTokens.java       the tokens a person hands out: sessions with a bit, capped and expiring
  api/ApiRoutes.java       /api for a person, /api/v1 for a program; two halves, two credentials
  mail/Mailer.java         the closed list of email flows
  mail/DevBoxMailer.java   prints them to the terminal, copy-paste shaped
  mail/AmazonSes.java      real email, one signed POST, no AWS SDK
  mail/SignatureV4.java    AWS request signing; checked against Amazon's worked example
  mail/MailBrand.java      a community's colours and where its terms are; rides on the envelope
  mail/MailLayout.java     one shape for every message, and the footer that is not optional
  mail/Messages.java       what each flow says, in both halves, for every mailer
  mail/SystemTemplate.java every message this server sends, and the wording it ships with
  mail/SystemTemplates.java what a community says instead, if anything; a replace, never an engine
  mail/SesConfig.java      the per-domain ses block
  mail/Mailers.java        domain -> mailer, so one box can mix real and terminal
  smtp/SmtpServer.java     inbound mail; its own event loop, off unless asked for
  smtp/SmtpSession.java    the RFC 5321 state machine, minus what nothing needs yet
  smtp/SmtpRouting.java    which community a message is for, and the refusal to relay
  smtp/Envelope.java       one message as it arrived; envelope kept apart from headers
  smtp/MailReceiver.java   what happens to it once it has; the seam
  smtp/TerminalMailReceiver.java  prints it, the inbound twin of DevBoxMailer
  smtp/SmtpConfig.java     the smtp block in config.cfg
  smtp/SenderCheck.java    all three checks, and the one thing that gets refused
  smtp/Spf.java            RFC 7208; the ten-lookup cap is the security property
  smtp/Dkim.java           RFC 6376; canonicalization is the whole difficulty
  smtp/Dmarc.java          RFC 7489; alignment is what makes the other two mean anything
  smtp/AuthResult.java     what each said, and the Authentication-Results header
  smtp/SmtpDns.java        the resolver seam, so every check is testable without a network
  template/Templates.java  mustache, compiled at boot
  web/AccountRoutes.java   register / login / logout / forgot / reset
  web/HomeRoutes.java      /home: the dashboard -- what is waiting, what is being said, what is next
  web/ThemeRoutes.java     /~theme.js: light or dark, decided by the person and kept in the browser
  web/AdminView.java       the admin URL space: sections, panels, forms, sidebar
  web/AdminRoutes.java     the admin shell and its sections
  web/Flash.java           the one-shot "that worked", keyed by session, never in a URL
  web/SelfRoutes.java      /self: profile, inbox, notifications and invitations
  web/Icons.java           inline SVG; the whole icon set, no image requests
  web/ThirdParty.java      /3rd/<pkg>/<version>/<file>, vendored into the jar, never a CDN
  web/PwaRoutes.java       /~app, the manifest and the worker; subscribing a session to push, the
                           install screen, and the self-test that proves a notification arrives
  web/AppIcon.java         the home screen icon, drawn rather than stored, in the community's colours
  push/PushCrypto.java     RFC 8291 message encryption, checked against the published vector
  push/Vapid.java          the signed claim that says who is sending; a keypair per session
  push/PushSubs.java       which browsers we can reach, one row per session
  push/PushLedger.java     when a push went out and when somebody acted; buffered, flushed on a timer
  analytics/Machine.java   what the box is doing: /proc, and a day of it in memory
  push/WebPush.java        one signed, encrypted POST to a push service
  places/Places.java       the address book; kinds of place declare their own fields
  places/PlaceRoutes.java  /places, one kind, one place; the type names the template
  places/PlacesConfig.java whether a domain keeps an address book
  settings/Setting.java    one thing a community may decide, and how a form asks for it
  settings/Settings.java   the closed catalogue: what moved to the database, and what it means
  settings/SettingStore.java  the config table; a row exists only where somebody decided something
  theme/Theme.java         six colours twice, and the CSS every layout interpolates
  theme/Themes.java        the palettes for one community, cached because every render asks
  legal/LegalDoc.java      the two documents, and the text they ship with
  legal/LegalDocs.java     what a community said instead, if anything
  legal/LegalRoutes.java   /legal, open to everybody, in the administration's colours
  web/FormMint.java        per-submission opaque field names + the script proof
  web/Signals.java         interaction counts posted by the page
  web/Navigation.java      the nav, per domain and per viewer
  web/Landing.java         where to go after signing in; the open-redirect refusal lives here
  web/Forms.java           form, query and cookie reading; all of it untrusted. two ceilings:
                           get/raw for short fields, text() for prose
  web/Cookies.java         Set-Cookie building and the double-submit CSRF check
  web/WebConfig.java       server knobs
  web/WebServer.java       Netty bootstrap + lifecycle
  web/Initializer.java     pipeline; HTTP/1.1 today, marked for SNI + h2
  web/WebHandler.java      the request path, eight ordered steps
  web/WebRequestShield.java scanner-noise filter
  web/Responses.java       the only place that writes bytes; security headers live here
  web/Html.java            jsoup: what a member may write, and the whitespace nobody needs
  web/Canonical.java       one community, one address; the scheme, port, path and query it keeps
  web/Chrome.java          the icon and the palette every page carries
  web/Pages.java           home / not found / bad host, via mustache
src/main/resources/legal/      the terms and the privacy policy this server ships with
src/main/resources/live/       live.js, served from /~live rather than inlined
src/main/resources/theme/      theme.js: the three lines that set light or dark before first paint
src/main/resources/templates/  layout.mustache + one file per page
src/main/resources/templates/admin/  shell.mustache (top bar + sidebar), one page per section,
                                     one *_panel per refreshable view, one *_form per editor
src/test/java/io/hearth/
  testkit/TestServer.java  a real server on an ephemeral port, with real databases
  testkit/Http.java        HTTP client (JDK client + raw socket)
  testkit/Browser.java     cookie-keeping, form-filling client for the account flows
  testkit/Configs.java     throwaway configs directories
  testkit/CapturingMailer.java  reads codes back the way a person reads the terminal
  testkit/McpClient.java   a connector: registers, walks consent, redeems with PKCE, speaks JSON-RPC
site/                      checked-in example root, used by tests and by hand
justfile                   the primary interface; `just validate` is the gate
```

Request path today: `GET`/`HEAD` everywhere, `POST` only on paths a domain's `urls` declared (405
otherwise, with `Allow`), HTTP/1.0 and 1.1 only (505 otherwise), bodies capped at 1MB by the
aggregator (413), malformed requests 400.

## Invariants — do not break these without saying so out loud

1. **Configs load at boot, before the socket opens.** `DomainScanner` runs once; the *shape* of
   `DomainTree` -- which domains exist, what covers what, which names are junctions -- is immutable.
   Nothing on the request path opens a file to learn about a domain.

   **The product half of a domain's config is now a database table and can change while the server
   runs** (invariants 267-272). That was the conversation this clause asked for, and the way it was
   settled keeps what the clause was actually protecting: a write rebuilds the whole immutable
   `DomainConfig` once and swaps it into the tree, so a reader still takes a reference to a finished
   object and never reads a file, or a table, to learn about a domain. The work is on the write, the
   same trade the theme cache makes. What stays in the file is everything security-bearing, and that
   is still boot-only.
2. **A domain is served only if it has a `<domain>.cfg`.** There is no default host and no fallback
   site. Unconfigured means refused.
3. **Config problems are fatal at boot.** Bad JSON, wrong types, unknown keys, filenames that
   aren't valid domains, symlinks, a `static-root` escaping the configs directory — all refuse to
   start. A half-applied policy is worse than no server.
4. **All bytes leave through `Responses`.** Security headers are applied in one place so a new
   handler can't forget them, and the response's protocol version is normalized there so a
   client-invented version token never appears in our status line. Inline scripts are allowed by
   nonce, never by `'unsafe-inline'`. `form-action` and `base-uri` are `'self'` because
   `default-src` does not cover them, which is what stops an injected form posting elsewhere.
   `script-src` also carries `'self'`, so a nonced module can
   `import()` a vendored library from `/3rd` -- deliberately not `'strict-dynamic'`, which would let
   any nonced script pull in anything from anywhere.
5. **The Host header is untrusted input.** It only ever becomes a lookup key via
   `Hosts.normalize`, which rejects anything ambiguous rather than guessing.
6. **`--verbose` explains, never changes behavior** — except for deliberately withholding
   diagnostics from responses when it's off (see `Pages.hello`).
7. **The disk is for startup, with exactly two exceptions.** Configs, templates and the schema are
   read once, before the socket opens, and never again. Emailed codes live in memory only — a
   ten-minute credential is not worth a row. The exceptions, both deliberate and both named here so
   nobody has to wonder whether they are a bug:
   - **H2**, doing what a database does.
   - **The certificate cache.** `CertificateManager` writes a key and a chain per domain, minutes
     after boot and again months later on renewal. There is no way around it: a certificate arrives
     when the authority issues it, and the whole point is that nobody has to be there. It is one
     background thread writing to one directory that nothing on the request path reads — the TLS
     layer holds its contexts in memory and is handed new ones, so a request never waits on a file.
8. **Secrets are never stored in the form they are presented in.** Session tokens go into the
   database as SHA-256, passwords as scrypt. A stolen database file must not be a list of logins.
9. **Every mutation is write-through, database first.** A revocation that lands in memory and then
   loses a race with a crash is a token that still works, and "sign me out everywhere" has to mean
   it. `last_seen_at` is the deliberate exception, flushed by the reaper.
10. **No account enumeration.** Asking for a code, or getting a password wrong, looks identical
    whether or not the address has an account. The create-or-sign-in decision happens after the code
    comes back.
11. **Signing in returns you to where you were going.** `AccountRoutes.render` puts a validated `next`
back on the form action, which is already how the mint ticket survives the flow, so it lives through
email → code → session without a cookie or a field to clean up. `finishSignIn` honours it. The OAuth
flow depends on this: a connector opens a popup at `/mcp/authorize`, an admin who is not signed in
gets sent to `/login`, and dropping them on the home page afterwards leaves the popup waiting
forever with nothing to communicate back.

**A session is handed out in exactly one place.** `AccountRoutes.finishSignIn` is the only code
    that mints one. A session means *authenticated* -- they proved the address -- and never implies
    approved. Approval is enforced in `WebHandler`, which gates the community while leaving
    `urls.self`, `urls.survey`, `urls.orientation` and the account pages reachable -- those are
    what an admin reads before saying yes. **That list is `Route.isReachableUnapproved()`, a closed
    list rather than "is this path in the routing table"**; the latter answered yes for every
    surface there is. Conflating the two left the approver with nothing
    to read, which is how the split got made.
12. **Code that exists in two languages needs a test that runs both.** The form proof is written in
    Java and in the shipped JavaScript. The original test compared the server to a Java
    reimplementation -- two functions agreeing with each other and with nothing a browser runs -- and
    the real script was silently wrong for months of nonces. `ProofContractTests` extracts the
    function from the template that ships and executes it under node. Any future two-language
    algorithm gets the same treatment -- and so does any shipped script whose *promise* is
    behavioural rather than visible: `KindSwapScriptTests` drives the place editor's kind swap
    under node against a small stub document, because "changing the kind loses nothing" cannot be
    checked by reading the file.
13. **Bot resistance is not a security boundary.** Minted field names, the script proof, and the
    interaction counts raise the cost of cheap traffic. Approval is the boundary. Never let a change
    turn one into the other -- if something is only safe because a bot could not figure out the form,
    it is not safe.
14. **Every write emits a mutation event, from the DAO.** Not from the handler: a caller can forget,
    and the event has to be tied to the write actually landing. Domain, table, primary key, kind.
15. **Caches invalidate from events, never from the code that wrote.** `ContentStore` does not know
    what caches pages; `Site` does not know what writes them. The TTL is a backstop, not the
    mechanism.
16. **One cache key per entry.** An early version keyed rendered pages by both id and uri and
    invalidated one of them, which served stale pages. If a value needs finding two ways, invalidate
    with `invalidateIf` on something inside the value.
17. **The access log records the domain before anything can refuse.** Shielded and malformed requests
    are the interesting traffic; logging them without a domain hides them from every dashboard.
18. **No bytes on disk except the database, the certificate cache, and what people upload.** The
    third one was argued about and is in: a photograph in a database column is read into memory to
    be served, copied by every backup of the schema, and impossible to hand to a web server later.
    It is a directory of files nothing on the request path scans -- a path is *computed* from an id
    -- which is a different thing from the asset directory this rule exists to refuse. There is
    still no static-root, and every byte of chrome is still inline.

18. **No bytes on disk except the database** -- and one deliberate exception that is not really
    one. Vendored browser libraries under `/3rd` are classpath resources baked into the jar, so
    there is still a single artifact to deploy and nothing beside it to forget to copy, which was
    always the actual rule. No static-root, no asset directory, no icon files.
    Images are inline SVG from `Icons`, styling is in the layout or the operator's template, and a
    page costs one request. That is a resource budget, not a preference -- adding a file to serve
    means arguing for it.
19. **The survey indexer owns the counts.** `PeopleStore.recordCounts` deliberately emits no event,
    because the indexer reacts to answer events and would wake itself forever. The counts are a
    cache in a column and `reindexEverybody` rebuilds them from the blobs.

20. **Every sub-view has its own URL.** A panel that refreshes in place is a path
    (`/admin/system/logs/results`), not a query flag. The page embeds it by calling the same method
    the panel's URL calls, so the two cannot drift, and the access log shows the panel as itself.
    The previous `?fragment=1` design broke outright -- see invariant 22 for why -- and was invisible
    in a log besides.
21. **Identity in the path, filters in the query, mutations in a POST that redirects.**
    `/admin/content/edit/41`, `/admin/content/list?q=about`, `POST /admin/content` → 303. A refusal
    redirects too; the reason arrives through `Flash`, keyed by session and read once. Nothing that
    changes state, and nothing meant for one person, ever appears in a URL.
22. **No *escaped* template value is ever interpolated into a `<script>` block.** Mustache escapes
    for HTML, and HTML entities are *not* decoded inside a script -- `{{url}}` holding `/x?a=1`
    arrives in the script as `/x?a&#61;1`. Configuration goes in a `data-` attribute, which the
    parser decodes on the way in; a server-built JSON payload goes in raw with `{{{blob}}}`, which
    has no such problem. This is how the live buttons broke, and it will break the same way again --
    `ShippedScriptTests` enforces it, and also runs every shipped script past node's parser, because
    a template is the one place here with no compiler standing between a typo and a deploy.
23. **A listing is not a form.** Creating or editing anything is a page transition to its own URL
    (`/admin/content/new`, `/admin/survey/edit/7`). A form sharing a page with a listing has
    ambiguous state the moment the listing moves under it.
24. **Rejecting is not unapproving.** Unapproved means "not yet" and keeps everything. Rejecting
    means no: the account, the profile and the answers all go, because a rejected stranger's data
    has no future reader. An admin can never be rejected -- remove the role first. Turning an
    account off is the reversible middle option, and it keeps everything.
25. **A ban is cheap and invisible.** Checked before a code is minted, before anything is mailed,
    and before a row is written -- but a banned address sees the page a fresh one sees, because a
    ban that answers differently is an oracle for who has been banned and for who has an account
    here. `BanTests` compares the two pages after normalizing what legitimately differs.

190. **`/api/v1` is bearer-only, and the cookie is not consulted there.** That is the security
    property, not a convenience: a JSON endpoint that took the browser's session would be a forgery
    hole with no form and no token to protect it. The person-facing half at `/api` is the opposite
    -- cookie, CSRF token, 303 after a POST -- and the two share nothing but a path prefix.
191. **A token is shown once, on a page, never in a URL.** `Flash` carries it through the redirect,
    which is the same one-shot the rest of the admin flow uses. Handing it back on the POST's own
    response would mean a refresh mints a second one; putting it in the redirect would put a
    credential in the history and the access log.
192. **A third token is refused, never rotated.** Silently retiring the oldest would stop whatever
    has been using it for a month, and the person would find out from something breaking rather than
    from this screen.
193. **A push writes only what differs.** A page whose every field matches is skipped entirely: no
    save, no event, no version. A tool that pushed a repository on every commit would otherwise fill
    the history with edits nobody made -- and `?dry=1` answers the same JSON without writing, because
    a diff nobody can see before it lands is a diff nobody reviews.
194. **`/admin/system/settings` is a report, and everything on it names its key.** The *operator's*
    configuration is read once before the socket opens and never again, which means an operator
    cannot otherwise see it -- those values exist only as fields on objects in a running process. No
    credential is ever printed: `set` or `not set` is the half worth knowing, because a credential
    on a screen is a credential in a screenshot. This is still true of everything that stayed in the
    file; `/admin/configuration` is the other screen, and it is an editor because what is on it is
    the community's rather than the operator's.

26. **An agent is a session with a bit set, never a parallel notion of identity.** An MCP token is
    a row in `sessions` belonging to the admin who authorized it, with `robot = true` and the
    connector's name in `agent`. That is what makes revocation, expiry, the reaper and the cap work
    on it without a second implementation of "still valid" that would eventually disagree with the
    first. The bit is what keeps "who did this" answerable: an agent can never do anything the
    person could not, and is never mistaken for that person afterwards.
27. **Every AI rule is enforced in `AiSurface`, once.** The tools are shaped for a model; the stores
    know nothing about models; the one place in between answers "is AI allowed to touch this". A
    rule enforced in fifteen tools is a rule that will be forgotten in the sixteenth.
28. **Human only is asymmetric, on purpose.** Reads are *invisible* -- a locked page is absent from
    listings, searches, fetches and the navigation, not "forbidden". Writes are *refused out loud*.
    The symmetry is tempting and wrong both ways: a locked uri that looked empty to a write would be
    silently overwritten by an agent asked to "add an about page", and a write that claimed success
    while doing nothing would teach the model it succeeded. An agent can never set or clear the bit.
29. **Deleting a survey question is soft.** Its answers live inside every respondent's blob, so
    purging is a rewrite of every answer sheet in the community -- too big to do inside the click
    that meant "stop asking this", and irreversible on a button people press by accident. Deleting
    hides and stops counting; `purgeQuestion` does the cascade from its own page and reports how
    many sheets it rewrote.
151. **The privacy policy this software ships is a specification.** It is published on every
    community's own site, under the name of whoever runs it, with a regulator named two paragraphs
    below -- so every promise in it has to be a thing the code does. "Show you what we hold", "give
    you a copy in a portable form" and "delete your account and what is attached to it" are
    `DataExport` and `Erasure`, reachable by the member themselves and by an administrator, and the
    ninety-day sweep of the sign-up IP is there because the policy could not honestly describe a
    column kept forever. **Changing the policy is changing a requirement; changing what is stored
    means reading the policy again.**
152. **An erasure is checked by looking, not by remembering.** Deleting somebody used to remove the
    account, the profile and the answers and leave the address in `posts.author_email`,
    `comments.author_email`, `rsvps.user_email` and `invites.created_by_email` -- because the sweep
    listed the tables somebody thought of. `RightsTests` walks *every column of every table* looking
    for the address afterwards, which is the only form of that test worth writing.
153. **The words stay; the person goes.** A post and a comment keep their text and lose their
    author, because a thread other people replied to is theirs as well, and cutting one person out
    of it leaves holes in everybody's memory of a Tuesday. Taking the words down too is a separate
    decision, offered to an administrator and only worth making when somebody asked for it. A ban
    survives an erasure, and the policy says so: a community that cannot keep out somebody it
    removed cannot protect anybody.
154. **Vendoring is redistribution.** Every third-party bundle in the jar travels with its licence,
    checked into git even though the bundles are not, served in full at `/3rd/licenses`, and listed
    in `THIRD-PARTY.md`. The obligation belongs to whoever ships the jar -- which is the operator --
    and it costs one page.

146. **A member is named, never addressed.** `people/Names` is the one answer to "what do we call
    this person on a page somebody else is looking at", and the answer is never their email address
    -- not on the board, not in a guest list, not in a notification, not to a model. Somebody with
    no name yet is "a member" rather than the part of their address before the `@`, because a local
    part is still most of an address and usually most of a real name. The admin section is the
    exception it always was: approving somebody is a decision about an address.
147. **A permission that is offered has to be asked for somewhere that matters.** `board_moderate`
    gated an admin screen while the board itself asked "is this an admin", and `content_publish` was
    a checkbox that decided nothing. A permission nobody checks is worse than no permission at all,
    because somebody grants it and believes the split exists.
148. **A summary page summarises what *you* can reach.** The overview is behind `admin_enter`, which
    every permission implies, so it is the page everybody with any role lands on -- and each block on
    it asks for what its own section asks for. A narrow role gets a page that says little and says
    why, rather than the membership and a live view of who is online.
149. **A control that would refuse is not drawn, and a link into a section somebody cannot open is
    not a link.** A button that says no teaches people the software is broken; a link that answers
    404 is a door drawn on a wall. Both are checked with the same `can` the handler uses, so they
    cannot drift from it.
150. **A column whose name has stopped being true gets renamed.** `Column.renamedFrom` declares it
    and the upgrader performs it, before it looks for anything missing -- because a renamed column
    is indistinguishable from a missing one, and adding it would leave every value behind in the old
    one. It is the only name-changing thing the upgrader does and it moves no data; both names
    present is reported rather than guessed at.

188. **An address nothing answers is a 404, and `/` is the only exception.** Every path used to be
    served the front page with a 200 -- a lie to a person (the link appears to work and shows
    something else), to a search engine (every typo is a page) and to anything automated (a 404 is
    how a client learns an address is wrong). The page wears the community's colours and carries the
    way back, including the sign-in form with the errand on it, because a lapsed session on the way
    to a link is the ordinary reason to meet it. `Pages.missing` is that page; `Pages.notFound` is
    the other one, for a *domain* this server knows nothing about, and it carries no community.
189. **Absolute-form request targets are stripped to their path.** `GET http://example.org/about`
    is a request line RFC 9112 requires a server to accept, and the authority in it is never a way
    to pick a virtual host -- the Host header is, and `Hosts` is where. It mattered to nothing while
    every path was answered the same way; the moment a path that answers nothing became a 404, it
    decided whether a proxied request found the page at all.

136. **`/` is the community's front page; `/home` is the member's dashboard.** They are different
    pages for different people and the bar's "Home" points at whichever one applies. Most
    communities will put something at `/` aimed at whoever arrives, and a dashboard that greeted
    strangers -- or a dashboard nobody could find -- is what one address for both produces.
    `after-login` therefore defaults to `/home`, and **signing out goes to `/`**, because sending
    somebody with no session to a page that requires one is a bounce straight back to the form.
137. **The dashboard owns no state.** Everything on it is a read of what another page owns: the
    survey's count, the inbox's count, the board's feed, the calendar's next seven days, the
    profile's orientation step. There is no dashboard table and nothing reachable only from it.
138. **Nothing on it that is already done.** Each panel disappears when it is empty rather than
    saying "nothing to do", because a permanent empty panel is one people stop reading -- which
    would take the ones that are not empty with it.
139. **How far somebody got through the welcome is recorded, and only ever forwards.** Written when
    a step is *finished* rather than when it is on screen, so re-opening the welcome cannot take
    somebody backwards. The last screen counts on arrival, because there is nothing on it to do and
    skipping the questions is a decision rather than a failure to finish. What that number is for is
    the dashboard: somebody who jumped out half way is asked to come back, once, in the one place
    they will see.
140. **A click is worth more than an open, and both are worth less than a join.** The pixel can be
    fetched by a mail client with nobody looking; a link is followed by a person. So the funnel is
    sent → opened → clicked → joined, and each pair says which problem a community has: never
    opened is a message that did not arrive, opened but never clicked is one that did not persuade,
    clicked but never joined is a sign-up form that lost somebody who was already convinced. The
    click is recorded on the register page itself rather than through a redirect of our own -- a
    tracking hop between a person and the form is the wrong trade for a number.
141. **Light unless somebody says otherwise, and it is their choice rather than their laptop's.**
    `prefers-color-scheme` used to pick, which meant a community that chose its colours had them
    shown to half its members in a scheme nobody had looked at. The dark palette is now behind
    `:root[data-theme="dark"]`, set before first paint by `/~theme.js` from `localStorage`. A file
    rather than an inline script because inline needs a nonce and every page needs this; not
    deferred, because deferred means a white flash on every navigation for everybody who chose dark;
    and not a cookie, because the privacy policy says two cookies and both are necessary.

185. **A page's merge key is a uuid, stamped once and never rewritten.** A uri is an address and an
    id is a row number in one database; neither survives the round trip a bundle exists for, because
    the other end is a different install -- or the same one, three months and two renames later. It
    is deliberately *not* on `ContentRecord`: a version snapshot is the whole document, and identity
    is not part of a document, so restoring March must not be able to change which page this is.
186. **An import is a merge, and a collision is an adoption.** Same key, same page, whatever its
    address has become. A key nobody has seen arriving at an address already taken is the first
    import into a site somebody has been writing by hand, and two pages at one address is the worst
    answer available -- so the page here takes the key. Every write goes through the ordinary save,
    so it is versioned, it emits its event, and a bad import is undone page by page.
187. **There is no AI tool for a bundle.** It is the one view of the content table that ignores
    human-only, and invariant 28 survives by that view not existing for a model rather than by a
    check inside one.

182. **A feed page's uri is a pattern with exactly one hole in it.** `/whats-on/{{page}}`,
    `/people/{{member_id}}`. The request fills the hole and the hole becomes an argument to the
    query. One token, because a URL language with two variables is a router and a router is the
    shortest path to a page nobody can debug -- and page one of a listing is always the bare path,
    the same rule invariant 127 makes for a directory index and for the same reason.
183. **A feed inherits its audience; it never invents one.** Places and members need an approved
    member exactly as `/places` and `/members` do, and an anonymous request is sent to the sign-in
    form carrying the errand rather than a 404 that lies about a page in the navigation. Events are
    the exception and it is the point of it: a stranger sees what the community opened, and nothing
    else is on the page. **The audience is half the cache key** -- handing a member the page built
    for a stranger, or the reverse, is the check cached away.
184. **A listing's invalidation is broad on purpose.** One answer to an event moves a row on every
    listing that shows it, on a page number that depends on how many events there are. Working out
    which cached page went stale is more code and more ways to be wrong than dropping every page
    built from that source -- which for a few hundred rows is one rebuild, twice a day.

132. **The survey is a place, not a tab.** `/survey` opens on what is outstanding and `?all=1` is
    everything somebody has said, editable. It moved off a profile because that is where it read as
    a chore with a badge on it, and because a link to it is a thing an administrator can send: "we
    added a question about lifts, three minutes". One page, one form, one merge.
133. **A name is the one required field in this server.** Everything printed on somebody's behalf
    says who it is from, and an address is not a person -- so a profile refuses to save without one
    and an invitation refuses to be written without one, the admin included. Asked first, on its own
    screen, at the one moment when asking is not an interruption.
134. **The welcome is the survey, not a copy of it.** Step two of `/welcome` renders the same
    questions through the same helper, so a community that adds a question next month has changed
    what newcomers are asked without anybody editing a welcome flow. It is skippable everywhere
    after the name, and it is never shown twice -- "have they told us who they are" is asked of the
    profile rather than kept as a flag that would drift from it.
135. **The welcome cannot erase an answer.** Every box on it is a question nobody has answered, so
    an empty one is somebody skipping. `SurveyForm.merge` takes that as a flag, and it is the only
    difference between the two callers.

30. **Saving answers is a merge, never a replace.** The page shows what is unanswered, so a
    submission mentions a handful of the questions that exist. Treating that as the new state of the
    sheet would erase last month's answers every time somebody answered a new question -- and the
    page would look like it worked. A key that is absent is left alone; a key mapped to null is an
    erasure, which somebody can legitimately want.

31. **Certificate work happens after the socket is open, never during boot.** HTTP-01 validation is
    the certificate authority fetching a path from this very server, so an order placed before the
    listener is accepting waits on a listener that is waiting on the order. `CertificateManager`
    starts from the ready path and sweeps on a delay. goatbot did not have this constraint because
    it uploaded the answer to a bucket something else served; removing that bucket is what moves the
    ordering.
32. **The ACME challenge is answered before anything can refuse it.** It is the first branch in
    `WebHandler`, ahead of the scanner shield, the method gate and host resolution -- each of which
    can say no for a reason that has nothing to do with certificates, and a token *is* a long random
    path. A failed validation is not a 404 somebody can debug; it is an opaque CA error an hour
    later, possibly with a rate limit attached.
33. **No certificate is worth failing to start over.** A domain that will not validate means one
    domain has no certificate, written down loudly and retried on the next sweep. The server serves
    plain HTTP throughout.

34. **Port 80 never becomes a redirect.** It serves the site *and* answers the ACME challenge, so a
    domain that has not got a certificate yet needs it working in plain HTTP or it never will.
    Bouncing plain traffic to https is what `http-bounce-port` is for: a separate listener, off
    unless asked for, that does nothing else. Turning port 80 into a redirect would quietly break
    renewal for every domain three months later.
35. **"Ready" means every listener is bound.** The latch counts down after http, https and bounce
    are all up. A readiness signal that means "one of three ports" is a race that something will
    eventually lose — starting with the certificate manager, whose correctness rests on the socket
    being open before it orders anything.
36. **Report what happened, not what is about to.** The boot output used to promise a certificate
    check "in a few seconds", which is not a report and cannot be one — the order has not run yet.
    `CertificateManager` calls back per domain when a certificate actually lands or actually fails,
    and the boot output prints that as it arrives.

142. **A refusal for want of a session always carries where they were going.** Every handler that
    bounces an anonymous request builds its destination with `Landing.here(req)` -- **path and
    query**, because `/survey?all=1` and `/survey` are different pages to the person looking at
    them. `AccountRoutes` threads it through both steps of the sign-in, through a wrong code, and
    onto the links between the sign-in, sign-up and forgot-password forms, because somebody bounced
    to a form they cannot use presses the other one. A refusal that loses the errand is what turns a
    two second interruption into somebody giving up.
143. **`Landing.here` validates a URL this server itself wrote.** It is about to be echoed into a
    `Location` header and then into a page, and "we generated it" is precisely the assumption that
    turns a request line somebody else typed into a header injection. An address that cannot be
    echoed carries nothing rather than carrying something repaired.
144. **The admin section keeps answering 404, and puts the way back on the page.** A 303 to the
    sign-in form would tell whoever asked that the path is guarded, which is the one thing
    invariant 65 exists to withhold -- so the not-found page offers to sign an anonymous visitor in
    and return them, and somebody who *is* signed in and may not enter sees exactly what a missing
    page looks like.
145. **A session whose account has gone is signed out, not left waiting.** It used to fall into the
    approval gate, which asks whether that person is approved, gets a no for somebody who no longer
    exists, and shows the waiting page -- somebody waiting forever on a decision about an account
    that is not there. The cookie is cleared on the way past so the next request is honestly
    anonymous.

37. **`?next=` is a same-site path or it is nothing.** It is how a sign-in mid-OAuth comes back to
    the consent screen, and it is the classic open redirect: mail somebody
    `/login?next=https://not-your-community.example/login`, they sign in on a page they trust and
    land on a copy asking again. `Landing.safe` refuses a scheme, a host, `//host`, `/\host` (which
    some browsers normalize into the previous one), control characters, and anything that could
    escape the page it is echoed into. Refusing is the only correct answer -- repairing a redirect
    target means guessing what somebody meant by a URL that is already wrong. Approval still
    outranks it: an unapproved person goes to `urls.self` whatever they asked for -- or to
    `urls.orientation`, if they have not yet said what to call them. An honoured `next` outranks
    the welcome, because a connector's popup waiting on a consent screen must not be handed a
    welcome flow instead.

38. **Too long must never become empty.** `Forms.get` and `raw` cap at 512 characters, which is
    right for an email or a uri and catastrophic for a page body: every long field went through
    them, every caller turned the resulting null into `""`, and writing more than a paragraph
    silently stored nothing over what was there. Prose now goes through `Forms.text` (a megabyte,
    matching the column), and an oversize value is *recorded* rather than dropped so the handler can
    refuse out loud. **Check `form.tooLong()` immediately before a write, never earlier** — the list
    fills in as fields are read, so checking it before the handler has read anything checks an empty
    list. That mistake is how the same bug came back in a different shape while being fixed.
39. **A test that writes "hello" proves that "hello" fits.** Five hundred tests missed the above
    because every one of them posted a sentence. Anything that stores what a person typed gets a
    test with a realistic amount of it in — `EditFlowTests` uses paragraphs, and one case runs past
    the whole-form ceiling on purpose.

40. **One `--root`, and everything under it.** Three independent path flags could point at three
    different installations, and nothing noticed until the wrong people could sign in. Ports and
    limits live in `config.cfg` so the command that starts a production server never grows past
    `--root /var/hearth`. A removed flag refuses **by name** rather than falling into "unknown
    argument": the person hitting it is upgrading, and a server that quietly ignored `http-port`
    would serve somewhere other than the service file says.
195. **`--install` needs no root and starts nothing.** It writes into a directory the operator
    already owns, and the half that needs root is written out as `install.sh` for them to read
    first -- a program that wanted root to tell you what it was about to do is one you must trust
    before you can check it. It asks nothing, so unlike the walkthroughs it does not need a
    terminal: there is nothing here for somebody to think about, and running it from a provisioning
    script is a legitimate thing to want.
196. **A second `--install` stages a jar; it never overwrites the running one.** Overwriting works
    on Linux -- the process holds the inode -- and leaves the file on disk and the software in
    memory disagreeing, which is the state every confusing incident report starts in. `run.sh`
    swaps `hearth.new.jar` in on the way up, keeps `hearth.prev.jar`, and checks the staged file
    starts with the two bytes every jar starts with, so a half-finished upload cannot replace a
    server that works.
197. **The unit asks for `CAP_NET_BIND_SERVICE` and bounds the set to it.** Ports 80 and 443 are the
    only privilege this server needs; "starts as root and drops it" is a larger thing to trust than
    one capability granted by name.

41. **A walkthrough writes a file you could have written by hand, and says what it wrote.** Anything
    else has replaced understanding with a wizard, and the first time it is wrong there is nowhere
    to go. They refuse without a terminal, because each exists to make somebody think about
    something -- credentials in a file, an admin address, what `wildcard` means -- and a pipe cannot
    think.
42. **HTTP/2 changes the transport, not the request.** ALPN picks it during the TLS handshake, each
    stream becomes a channel, and `Http2StreamFrameToHttpObjectCodec` turns its frames into the same
    FullHttpRequest the HTTP/1.1 path produces -- so `WebHandler` is reached unchanged. A second
    handler for the second protocol would be two places to fix every bug, and the one that gets
    fixed is always the one somebody is looking at.

43. **A version is the whole page.** Body, title, template, folder, field values, published and
    human-only, as one canonical document. "What did this look like in March" is not answerable if
    the answer omits the facet that changed -- and this is meant to replace keeping a website in
    git, which would not have dropped it either.
44. **A history that cannot rebuild a version says so.** `reconstruct` refuses rather than returning
    the newest version at or below the one asked for, which is an older page presented as the one
    requested. A plausible wrong answer is worse than an admitted gap, because nobody checks it.
45. **Recording a version must never fail a save.** `ContentVersions.record` swallows and logs.
    Losing a history entry is a bad day; losing somebody's edit because the history table had a
    problem is a worse one.

46. **An open is weak evidence of delivery, never a read -- and it is wrong in both directions.**
    It under-reports, because most clients block remote images, so a missing open means *no
    evidence* rather than "unread". It also **over-reports**, which is newer and worse: Apple Mail
    Privacy Protection fetches remote images by itself before anybody opens anything, and Apple Mail
    is around half of all opens. So a recorded open frequently means a machine looked. The admin
    screen explains both directions and points at *joined*, which is the only number there a
    machine cannot produce on somebody's behalf. The pixel answers before the scanner shield (a token is a long random path), always
    returns an image even for an unknown token (a mail client must not get an error page), and is
    never cached (a cached pixel is one open recorded forever).
47. **An invitation converts on sign-up, not on click.** A click proves somebody opened a link; what
    an invitation is for is a member. Matching is by address and claims only the oldest outstanding
    invitation, so inviting one person three times is one conversion rather than three.

48. **Posts expire by default.** A board that keeps everything becomes an archive nobody reads and
    a liability somebody eventually has to think about. A community that wants a permanent record
    sets `board.expiry-days` to 0; the default is that a thread has a life, and notifications age
    out with the thread they are about.
49. **Threading is a sort key, not a recursion.** Every comment carries a dotted path of zero-padded
    positions, so one ordered query returns the whole tree in reading order and depth falls out of
    the path. A query per level would make the comment box slower exactly as a conversation got
    interesting. Depth is capped -- past it a reply attaches at the cap rather than nesting, which
    keeps a staircase from forming without losing the reply.
50. **Joining a conversation is what makes you a watcher.** There is no subscribe button, because a
    board where you have to remember to press one is a board where people miss the reply to their
    own comment. The notify set is who was watching *before* the reply, which is exactly who should
    be told and never includes the person replying -- a board that tells you about your own comment
    has an unread count that means nothing within a day.
51. **A removed comment keeps its row.** The replies underneath are other people's words, and
    deleting the parent would orphan them.

52. **Nothing is delivered from the request path.** A reply in a thread with forty watchers is
    forty signed HTTPS requests to Amazon, and doing that inside the POST makes the reply box get
    slower exactly as a thread gets popular -- the same failure the board's caching exists to
    prevent, arriving through a different door. `Notifier` is one background thread for the whole
    box, so "immediate" means the next pass, within a minute, which nobody can tell from instant.
53. **The delivery queue is a query, not a queue.** `notified_at IS NULL` is true whenever it is
    asked, survives a restart, and cannot disagree with the rows it describes. An in-memory queue
    loses everything on a restart and a durable one has to be reconciled with the notifications
    themselves.
54. **Everything a pass considers gets stamped**, including a notification belonging to somebody
    who wants no email at all. Leaving those unstamped grows a queue of rows nothing will ever act
    on, and every pass costs more than the last. Stamped means *handled*, not *sent*.
55. **A digest is stamped before it is sent.** Two copies of Tuesday's summary is worse than
    missing Tuesday, and nothing is actually lost either way -- the inbox on the site is the
    record, and this is only the reminder. Everybody's *first* digest fires on the next pass rather
    than waiting a day, because the notification that triggered it is what starts the clock.
56. **A channel nothing delivers on is never offered.** `NoSms` refuses and says why; the settings
    page asks `available()` and prints the reason instead of a checkbox. A stub returning "ok"
    would put a preference in the UI, a phone number in the database and a green tick in a log,
    and the first person to discover it does nothing would be somebody who needed a message.
57. **Two notification settings, because there are two events.** A reply *to you* is a conversation
    waiting on an answer and defaults to immediate; activity in a thread you watch is news and
    defaults to a daily summary. One setting means either mailing somebody about every comment in a
    busy thread or making them wait a day to learn a question was addressed to them.

58. **A restore is a save, not a rewind.** The old version becomes the newest one and everything
    before it stays, including the edit being undone. A history that loses the mistake also loses
    the evidence of what was tried, and an undo that quietly deleted three versions would be the
    most dangerous button on the page. This is `git revert`, not `git reset --hard`, and the
    difference is the reason to keep a history at all. Restoring brings back the *words*, never the
    uri -- the old address may now hold something else.
59. **A page's identity is its id when it has one.** `ContentStore.save` matched on uri alone, so
    changing a page's address in the editor did not rename it: it created a second page and left
    the first one serving, with the history stranded under the old id. That is the one failure a
    version history cannot survive. The uri fallback stays for callers with no id -- the model tools
    save by uri, and "create it or update it" is exactly what they mean.
60. **An admin may pin, lock and remove. Never edit.** Rewriting what somebody said while leaving
    their name on it is the one moderation power the person it is used on cannot undo, so it is not
    on offer anywhere. Authors edit their own words and nobody else's, which the DAO enforces in
    the `WHERE` clause rather than in a check a future caller could forget.
61. **An edit says it was edited.** A post that shifts under the people who already read it and
    replied to what it used to say is a small lie to all of them, and the stamp costs one column.

62. **The calendar is days, not instants.** A community event is "Saturday the 14th", and a
    timestamp forces a clock time onto everything and a timezone question onto every reader. The
    time of day is free text shown as written, because "doors at 7, music at 8" is a real answer no
    time column holds.
63. **Capacity counts seats, not answers.** Somebody bringing three people takes four chairs;
    counting rows overfills the room by exactly the number of guests. Past the limit an answer
    becomes a waitlist entry rather than a refusal, and **the page shows what the server decided,
    never what was clicked** -- a tick shown to somebody who is actually waiting is a lie about a
    seat. A maybe is never a seat. Promotion is by longest wait and skips a party that does not fit
    rather than stopping, so one person waiting for six does not block five waiting for one.
    Lowering a capacity never takes back a place somebody already has: withdrawing is a thing a
    person does, not a sweep.
64. **Cancelling keeps the page; deleting does not.** The people who said they were coming are
    exactly the people who need to see that it is not happening, so calling something off leaves
    the page and the guest list where everybody already looked for them.

65. **A section somebody may not open answers 404, and is absent from the sidebar.** A 403 confirms
    that the thing exists, and a directory of doors that say no advertises to somebody what this
    community has that they are not trusted with.
66. **The built-in admin role cannot be edited, deleted, or duplicated.** It is rewritten at every
    boot, `everything` is stripped from every other role, and `admin_emails` answers yes without
    consulting the database at all -- an escape hatch that reads the thing it exists to rescue you
    from is not one.
67. **A permission implies what it needs.** Writing implies reading; anything implies reaching the
    admin section. A role granting a power behind a door it cannot open is indistinguishable from a
    bug, and cannot be diagnosed from the screen that created it.
68. **A stale suggestion is flagged, never blocked.** Applying an edit written against last week's
    text would revert somebody's work while looking like it worked; refusing outright would mean a
    busy page could never accept one. Only a reviewer can tell which it is, so the queue tells them.
69. **Vendored libraries are inside the jar, never fetched at runtime.** A page that loads from
    somebody else's server has told them a member was reading it, and stops working when they have a
    bad day. The version is in the path so the URL is immutable and a year of caching is honest.
72. **Signing out deletes the session.** Revocation leaves a row for a day, which was harmless when
    a session was only a cookie and is not now that one owns a push subscription: for that day the
    server holds a key that can put a notification on a device somebody just signed out of.
    Revocation stays where it is right -- an admin disabling an account, a password change -- where
    a row saying when and why is worth keeping.
73. **A push subscription cannot outlive its session.** Deleted with it, swept if it ever escapes,
    and its VAPID keypair dies with it -- so "sign me out" means unreachable, not merely unwatched.
74. **A push says who and where, never what.** It crosses somebody else's infrastructure and lands
    on a lock screen. Its job is to bring a person back, not to tell them the thing -- which matters
    most in exactly the community where it matters most.
75. **Every step of subscribing is a no-op the second time.** The shell runs it on every load;
    register, getSubscription and the POST all repair rather than duplicate. A browser whose
    subscription was rotated must fix itself on the next visit rather than going quietly silent.
80. **This server never relays.** Inbound mail is accepted only for a domain with a config file,
    matched exactly, and refused at RCPT before a body arrives. An open relay is found within days,
    used to send spam in somebody else's name, and ends with the machine on every blocklist there is
    -- with the community's own mail undeliverable behind it. A wildcard is a decision about serving
    web pages and is never permission to receive mail.
81. **One message, one community.** Recipients on two domains are two deliveries. Otherwise a
    handler cannot say which community it is acting for, which is the question everything downstream
    asks first.
82. **Advertise only what is honoured.** EHLO names SIZE and 8BITMIME because both are true, and
    does not name AUTH, STARTTLS or PIPELINING because none are -- a sending server believes an
    EHLO line, and a capability that is not there fails in a way nobody can diagnose.
83. **The ten-lookup cap in SPF is the security property, not a tuning knob.** `include:` and
    `redirect=` recurse, so an unbounded record is amplification: every message would make this
    server hammer somebody else's DNS on the sender's behalf. Counted across the whole evaluation,
    not per level.
84. **A DNS failure is temporary, never a forgery.** `temperror` throughout, so an unreachable
    nameserver bounces nothing. The opposite -- treating silence as a fail -- rejects real mail
    every time somebody's DNS hiccups, and does it invisibly.
85. **Only what the domain owner asked for gets refused.** `p=reject` and nothing else. An SPF
    failure alone means a mailing list, far more often than it means a forgery.
86. **A section permission is permission to see a screen, never to press what is on it.** Every
    action posts to the section path, so a handler that checks nothing further inherits the mildest
    permission on that screen -- which is how `people_read` reached `grant_admin`. An action nobody
    listed requires `everything`, so a new button fails closed.
121. **`script-src` always carries `'self'`.** Every script this server ships is a same-origin file,
    so `'none'` refuses our own -- which is how a page that needed one loaded no JavaScript at all
    and the bell never lit anywhere. The nonce is added on top when a page has an inline script; inline is
    still nonce-only.
122. **`[hidden]` has to win.** A rule like `[data-composer]{display:flex}` beats the attribute's
    own `display:none`, so anything shipped hidden needs `[hidden]{display:none!important}` on the
    page. A send bar with nowhere to send is worse than no send bar.
123. **A section whose surface is off must still answer.** Its path stays in `SiteUrls.routes()`,
    because that table is what stops two sections sharing an address -- so the request path asks
    `Route.isAccountPage()` rather than "is it in the table", and every switch over a route has a
    default. A handler that writes nothing holds the connection open, which is invisible in a log
    and indistinguishable from a hung server.
124. **Nobody may grant a permission they do not hold.** Otherwise `people_roles` is the whole
    server by a longer route: invent a role holding everything except the word `everything`, grant
    it to yourself, and the escalation is sideways rather than upwards, which is why it does not
    look like one.
125. **The members directory is for members, and carries no email addresses.** Signed in, approved,
    checked in the handler rather than in a template. The admin section shows addresses because
    approving somebody is a decision about an address; a member looking at the directory is looking
    at people, and a member list is the easiest thing in the world to screenshot.
126. **A listing truncates; the page it links to does not.** And it truncates *rendered and
    flattened* text rather than markdown -- cutting `**a very long` in half leaves an unclosed
    emphasis that swallows the rest of the page.
127. **A directory index is a property of a template.** Tick the box and every published page using
    that template is an entry in a paginated listing at an address the template owns. Page one is
    always the bare path whatever the pattern says, because two addresses for one page is two
    entries in a search engine. Ordered by when a page was created, never when it was edited: a
    blog that reshuffles because somebody fixed a typo is one nobody can find anything in.

203. **The clock is config, per box and per community, and it is not the JVM's.** This is a program
    for people who meet in a room, so "Tuesday evening" is a fact about a place -- and a rented box
    in another continent would put every evening a few hours out while looking like a calendar bug.
    `config.cfg` sets it, a domain overrides it, `DomainConfig.zone` is the only answer anything
    asks, and a zone that is not one is fatal at boot like every other config problem. Anything
    reaching for `ZoneId.systemDefault()` on a request path is a bug.
204. **A shared database takes the owning domain's clock**, for the same reason it takes its login
    policy and its admin list: one account space cannot have two answers to what today is.

110. **A surface is off in one word, and off everywhere at once.** `"disabled": ["places"]` beats the
    block's own `enabled`, and `DomainConfig.has(Surface)` is the only question any handler or menu
    asks -- so there is no way to check one and forget the other. An unknown name is fatal at boot,
    because a typo in that list is a surface somebody believes is off and is not.
111. **Everything is on until an operator turns it off.** The decision worth writing down is the
    refusal, so that is the one that takes a line in the file. The single exception is the model
    endpoint, which is off until asked for.
112. **The live channel is counted, never logged.** An open tab asks it every few seconds forever,
    so inside an hour it is nine tenths of every request -- and a filter on the query side would
    still let it push the pages people read out of the five thousand the ring holds. It never
    enters the ring; one counter says everything that traffic has to say.
113. **The overview leads with who is here.** It used to lead with the last ten row changes, which
    is a debugging view wearing a dashboard's clothes -- interesting exactly once. Presence is the
    only number on that page that changes while you watch it and the only one you can act on,
    because every name is somebody you could say something to.
114. **An invitation is one address in one box.** There is one message for the whole community,
    written once, with a default that ships filled in. A per-invitation note meant every admin
    composing a sentence under pressure, and a community whose invitations all read differently is
    one that looks like it is run by nobody.
115. **A calendar anybody can suggest to is a group deciding what it does.** Suggestions are on by
    default and the queue is what makes that safe: a suggestion costs a reviewer a screen to look at
    rather than control of the front page. Accepting publishes it, because accepted-and-invisible is
    the worst of both. Declining keeps the row and the reason.
116. **A location is a place when the community already wrote one down.** `place_id` plus free text,
    so "The Oak, back room" is a link to everything known about the pub and the bit that is only
    true this time. A place is worth more than a string that was typed slightly differently on four
    events.
117. **A link that performs an action is a button.** New, edit, accept, decline. A sentence with an
    arrow after it is a navigation affordance being asked to do a verb's job, and on a phone it is
    also a 20px tap target.
118. **A model may keep the calendar and write on the board; it may not moderate or decide.**
    Accepting a suggestion is the community saying this is what we are doing, and pinning, locking
    and removing are powers a community gave a person. There is no tool for any of them -- which is
    a stronger rule than a check, because there is nothing to route around.
119. **What a model writes belongs to a person.** A post it makes is attributed to the administrator
    whose connection it is holding. There is no "the AI" account, because a community has to be able
    to ask who put this here and get somebody they can talk to.
120. **16px on every field, 44px on everything you can press, a visible focus ring on everything.**
    Below 16px iOS zooms the page and never zooms back; 44px is the width of a thumb; and a
    community's own site is the last place to be clever about keyboard users.

128. **A comment is the same thing wherever it is written.** One table, one `Subject`, one set of
    rules: reading order, threading, the author editing their own, a moderator taking one down.
    Three tables would have been three notification paths and three moderation buttons, and the
    third would have behaved slightly differently from the first two.
129. **Comments do not expire.** A thread is what the community decided, and a memory with a
    fortnight's horizon is not one. What a long thread gets instead is clumping: subtrees are
    grouped by the age of their *root*, oldest first, and everything but the last couple of clumps
    arrives folded with a count on it. Grouping by each comment's own timestamp would scatter a
    reply away from the thing it replies to.
130. **Moderating comments is per section.** `board_moderate`, `calendar_moderate`,
    `places_moderate`. Keeping the address book tidy is a job somebody was given for the address
    book, not a power over everything anybody has ever written. Writing a section implies moderating
    it; the reverse is deliberately not true.
131. **A page acts only on its own comments.** Every action checks that the comment's subject is the
    page's subject, so an id handed to one section cannot be used to moderate another -- which the
    per-section permission would otherwise silently allow.

99. **A signal carries no content, ever.** The live channel says "comments:412 moved" and stops. The
    client then fetches that room the ordinary way, with its ordinary session, through the ordinary
    authorisation -- so the live path never becomes a second and weaker way into the same data, and
    a bug in the fan-out leaks a row id at worst. There is deliberately no addressing left: every
    signal names a row every member could already fetch, so "who may hear this" has one answer, and
    a channel with no audience list has nowhere for a mistake about audiences to live.
100. **Two transports, because one of them is always broken somewhere.** Server-sent events are the
    right answer and are the first thing a corporate proxy buffers into uselessness. The long poll
    is the same signals through a normal request, and the client falls back to it when a stream
    never opens. Both are answered by the same hub; neither is a different feature.
101. **Presence never touches the disk.** It is a fact about the last few seconds, it is wrong the
    moment it is written down, and it would otherwise be among the highest-volume writes in the
    server. A restart forgets who was online and everybody who still is says so again within a
    heartbeat.
102. **A beat is not a broadcast.** Every open tab beats every twenty seconds. Presence is
    re-announced at most every forty-five, because only the edges are interesting, and a fan-out per
    beat would be the loudest thing in the server saying nothing anybody needed.
103. **One connection per domain, not per tab.** The tabs elect a leader over a `BroadcastChannel`;
    the leader connects and rebroadcasts. Deliberately not the service worker, which is the obvious
    place: a worker is terminated whenever the browser feels like it, and a long-lived stream inside
    one dies with it silently on some browsers and not others. The worker still receives push when
    every tab is closed -- that is what it is for.
104. **A live update never destroys work in progress.** The board morphs by key, and skips any
    subtree containing the caret, an open `<details>`, or a field somebody has changed. Losing a
    half-typed reply because a stranger posted is worse than showing a stale page for a second.
108. **You are only told about a room you have been in.** Notifying every member of every message
    is how a community teaches everybody to filter it. Recipients are the people with a read mark
    for that room, and there is one unread note per person per room until they look.

90. **One community, one address.** Anything that resolved to a config under a name that is not
    that config's domain answers `308` to the same path on the domain itself, keeping the scheme,
    the port, the path and the query. Two live spellings of one community is two sets of links
    people paste to each other and two host-scoped session cookies, so signing in at one leaves you
    signed out at the other. The ACME challenge, the pixel and `/3rd` are answered earlier and are
    never redirected -- an authority validating `www` fetches its token *from www*, and a redirect
    is not an answer to that.
91. **The renderer is chosen by who is holding the pen, never by where the text is going.**
    `Markdown.toHtml` passes raw HTML through, which is right for somebody who could replace the
    whole document anyway. `Markdown.toSafeHtml` is for a member: a profile, a post, a comment. The
    filter runs on the *rendered* HTML rather than on the markdown source, because filtering markdown
    would mean understanding markdown and every escape somebody found would be a hole. That is the
    fix for the worst thing this project has shipped, and `InjectionTests` is what keeps it fixed.
92. **Whitespace between inline elements is content.** The compactor is a parser and not a regular
    expression for exactly one reason: `<p>a</p> <p>b</p>` has a space nobody will ever see and
    `<a>a</a> <a>b</a>` has the gap between two words. `pre` and `textarea` are left alone, and a
    script's contents are data rather than text, so the form proof passes through untouched.
93. **A palette is six hex strings or it is the default.** It is interpolated raw into a `<style>`
    block, so a value that could carry a `}` would be somebody else's CSS on every page. Every value
    goes through `Theme.isColour`, and a slot that fails keeps what it had rather than failing the
    save.
94. **Red means refused and green means it worked, and nobody may change that.** The semantic
    colours are constants. A community that could recolour them could end up with a red "approved".
95. **The two legal documents ship in the jar and are published from the first day.** A row exists
    only when a community has overridden one, so a community that has never opened the screen still
    has terms, and upgrading the software improves them. Writing the defaults into the database at
    boot would freeze every community's privacy policy on the day it was created.
96. **`/legal` is open to everybody.** Every email links to the terms and most of those go to
    somebody with no account yet. "The terms you are accepting are behind a login" is not a
    defensible sentence, so the path is a constant rather than an entry in `urls`, and the route is
    answered before the approval gate.
97. **Every message says what it is, why it arrived, and what interacting means.** The footer is
    built by `MailLayout` and is not optional, in both halves -- a message whose HTML carries the
    promise and whose text does not says nothing to whoever reads the text, and spam filters read
    the text.
98. **There is one email layout.** There used to be two -- a plain one for codes and a designed one
    for invitations -- and the moment communities could choose colours that would have become "the
    one message that ignores them". The invitation is now a caller like any other.

87. **A wildcard is not a way to serve subdomains, because no certificate can cover one.**
    HTTP-01 validation cannot issue a wildcard -- that needs DNS-01, which needs credentials for
    whoever runs the DNS, which is the dependency this project removed. So `wildcard: true` serves
    `www` over plain HTTP forever and the operator finds out from a browser warning. `subdomains` is
    the answer: a written-down list, ordered along with the domain, and the walkthrough defaults
    wildcard to **off** and says why.
88. **A named subdomain is the same community, never a second one.** One config, one database, one
    set of accounts. That is what makes it safe to accept mail for -- and what makes it wrong to put
    in `byDomain`.
89. **A walkthrough run twice must not undo the first run.** Every question pre-fills from the file
    it is about to rewrite, so an answer somebody accepts by pressing return keeps what is there.
    Without that the second run is a trap: it looks like a review and behaves like a reset.

76. **A kind of place declares what it records.** The fields are data, not columns, so a community
    invents "grass finished" without anybody touching this program -- and the declaration is the
    only thing that says what a place of that kind has, so a value for a field the kind never
    declared is dropped on a form and refused to a model.
77. **Removing a kind of place never removes an address.** They move to `Places.DEFAULT_TYPE`,
    seeded at boot and refusing to be removed itself, and come off the listing -- deleting somebody's
    forty ranches because they removed a heading is not a thing software should do, and a
    confirmation dialog is not consent for it.
78. **Changing what something is un-publishes it**, because the fields it carries were declared by
    the kind it used to be. What that kind recorded is *kept* in the blob rather than dropped: a
    form posting an undeclared key is noise, but a value stored under a previous kind is somebody's
    typing, and moving an address to another heading must not destroy it. `mergeValues` is that
    distinction, and `valuesToBlob` is the other one.
79. **The place editor swaps its questions in the browser, and carries what it is holding.** Every
    kind's declarations and every value this address has go out in `data-` attributes; the script
    harvests the boxes on screen before rewriting them and ships the lot in a hidden field, so an
    answer typed under one kind and swapped away from -- which is in no database and no visible box
    -- survives the save. It harvests on the way *out* of a kind as well as on input, because
    autofill and some paste paths change a value without firing one. Progressive: with no script the
    server-rendered boxes are already right and the merge against what is stored does the rest.
70. **Three messages, and the third says it is the last.** A fourth is nagging, and a sequence with
    no visible end is one people report as spam -- which costs the whole sending domain rather than
    the one message. The sequence stops the moment somebody joins or the invitation is revoked, and
    an invitation that was never sent is never reminded.
71. **One invitation, however many messages it took.** One row, one token, one link. An open
    recorded against the third message is an open of the invitation, and somebody who joins after
    the second is one conversion -- which is the same rule that stops resending inflating the rate.

**Roles and permissions.** `Permission` is a closed enum -- a permission that can be invented at a
call site is one nobody can audit. `RoleDefs` says what a role means; `roles` says who holds it, and
splitting them is what makes a role editable without a sweep over its holders. `Access.can` is the
single question every gate asks, so one place knows how a config admin, a granted role and a
permission add up.

`admin` is seeded at boot, holds `everything`, and refuses to be edited or deleted -- a community
that can edit its way out of having an administrator has locked itself out of its own server, and
`admin_emails` is the second lock. `everything` is stripped from any other role, so there is never a
second god role that nothing protects.

Ticking a permission implies the ones it needs (`Permission.implies`): anything implies
`admin_enter`, writing implies reading, bulk implies single. Without that, a role grants a power
behind a door it cannot open, which looks exactly like a bug and cannot be diagnosed from the role
editor. **A section somebody may not open is absent from the sidebar and answers 404** -- a 403
confirms what is behind it, and a sidebar full of doors that say no is worse than a small one.

**Suggested edits** are `Proposals`: the same canonical document a version is, written by somebody
who may not publish it. Approving is a plain save, so the history records it like any other edit and
an approved suggestion is afterwards indistinguishable from a direct one. The stored `base_version`
is what makes a stale suggestion *visible* -- flagged, never blocked, because only a reviewer can
tell whether two edits conflict, and refusing outright would mean a busy page could never take one.
Declining keeps the row: a queue where work quietly disappears is one nobody uses twice.

**Third-party browser libraries** are vendored into the jar by `just third-party` and served by
`ThirdParty` at `/3rd/<package>/<version>/<file>`. This is the one exception to invariant 18 and
barely one: they are classpath resources, so there is still a single artifact to deploy and nothing
beside it to keep in sync. The rule that mattered was never "the jar is small" -- it was "a running
server does not depend on files somebody could forget to copy". Answered before host resolution,
because they are the same public bytes for every community. The path check is character-by-character
rather than normalizing, since this is the one place a request path becomes a classpath lookup.

**Invitations are one row and up to three messages.** `Invitations` is the single path that sends
one -- an admin, a member, and the reminder loop all go through it, so the refusals (already a
member, banned, over the daily limit) cannot be enforced in two ways that eventually disagree. The
touch is decided from the row rather than passed in, so the same call sends a welcome the first time
and a reminder the second.

`touches` and `next_touch_at` live on the row rather than being derived from `sent_at` plus a
cadence, because the cadence is configurable and changing it must not retroactively decide somebody
was owed a reminder last Tuesday. The row is stamped **whatever the mailer said** -- a failure that
left it untouched would be retried every minute forever, which turns one bad address into a
reputation problem. Three is the whole sequence: the third message says it is the last one, and
going back on that is how a community earns a spam complaint, which costs the sending domain rather
than the message.

The reminder loop rides the `Notifier` pass. It is the same job -- something a person started that
finishes itself later -- and a second thread would be a second place to get "did this already go
out" wrong. Note that the board gate applies only to the board's half: a community with the board
switched off still sends invitations.

`InviteMail` is the one flow with designed HTML, and it is written the way email has to be written:
tables rather than divs (Outlook has no flexbox), inline styles only (Gmail discards a stylesheet),
a bulletproof button (a table cell with a background, not a styled anchor), 600px fluid, a
preheader, a declared colour-scheme, the link repeated as text for clients that disable links, and
always a plain-text half. Every one of those is a rule because something breaks without it.

**The app.** `/~app` is a shell that holds the site in an iframe, with `/manifest.webmanifest` and
`/sw.js` beside it, `/~app/help` explaining how to install it and how to prove notifications work,
and `/~app/icon-*.png` drawn on the way out by `AppIcon`. The shell exists so the worker, the permission and the subscription live in a
document that survives navigation -- a multi-page app re-registers on every load, and "has this
browser subscribed" then has a different answer each time. The ordinary site is unchanged and still
works with no JavaScript; the shell is an additional way in. The frame is same-origin through
`Landing.safe`, because a shell that would frame anything is a phishing kit with a manifest.

`/sw.js` is served from the root so its scope is the whole site, and caches nothing: a stale members
list is worse than an honest failure, and every page here is one request against a local database.

**Push is bound to a session, and that is the whole design.** One subscription per signed-in
browser, owned by the session row, with its **own VAPID keypair** -- unusual, since the spec only
needs one per application, and deliberate: revoking a login destroys the only key the push service
will accept for that browser rather than merely stopping us from using it. Signing out therefore
**deletes** the session rather than revoking it, because a revoked row lingers for a day and for
that day we still hold a working key for a device somebody just signed out of. `sweepOrphans` in the
reaper is the belt to that delete's braces.

Everything on the subscribe path is re-entrant: registering returns the existing worker, subscribing
returns the existing subscription, and posting it again updates one row. A browser whose
subscription was rotated repairs itself on the next visit instead of going silently dead.

`PushCrypto` is hand-rolled for the same reason `SignatureV4` is, and checked the same way -- against
RFC 8291's published vector, value by value. That matters more here than anywhere: a wrong derivation
is self-consistent, and its failure mode is a push service accepting a message no browser can
decrypt. The round-trip test caught something the vector could not (the two sides agree ECDH from
opposite halves of the pair), and the vector caught what a round trip never would.

**HTML goes through a parser twice, in opposite directions.** `Html.clean` is a jsoup `Safelist`
pass over rendered markdown a *member* wrote, and it is the fix for stored HTML injection -- relaxed minus
images, because a remote image in a post is a request to somebody else's server carrying every
reader's address. `Html.compact` takes the template's own whitespace back out on the way to the
browser, from inside `Templates.render`, which is the one funnel every page goes through and is
before `Site` caches anything. `compact-html` in `config.cfg` turns it off, and exists because this
rewrites every byte a browser sees: an operator staring at output that looks wrong should be able to
take one variable out of the question.

**Colours are `theme`, and light is the default.** Six slots -- accent, text, background, panel, quiet text, lines -- twice,
once for a light screen and once for a dark one, in two scopes. `site` is what members see and what
every email is built from; `admin` is the admin section *and the legal pages*, because a community's
promises are not its decoration and a community that themes itself into something unreadable has not
themed its terms into something unreadable. Cached in memory rather than read per request, and with
no event-bus invalidation on purpose: a theme changes from one place, the save, which replaces the
cached value in the same breath.

The dark half sits behind `:root[data-theme="dark"]` rather than behind `prefers-color-scheme`, and
`/~theme.js` -- the one shipped script that is not deferred -- sets that attribute from
`localStorage` before the first paint. Email is unaffected: it has only ever used the light palette,
because clients handle the media query badly.

Every layout interpolates `{{{palette}}}` raw into its `<style>` block. That is safe for exactly one
reason -- `Theme.isColour` -- and `Chrome.site`/`Chrome.admin` is the one call that puts both the
icon and the palette into a model, because the failure mode of forgetting the second is a page that
renders with no custom properties at all and is *nearly* readable.

**The messages are `mail/SystemTemplate`.** Thirteen flows, a closed enum for the same reasons the
legal documents are one: they exist before anybody has written anything, the defaults are considered
rather than placeholders, and a row appears only once a community has changed something -- so an
upgrade improves the wording of every community that never opened the screen. Each flow declares its
parameters and `/admin/messages` prints them beside a filled-in preview, because a template naming
something that does not exist renders as a hole and nobody notices until it has gone out.

The split is **the words are the community's; the shape is not**. Subject, opening line, one
paragraph. The layout, the button, the code block, the plain-text half and the footer stay in
`MailLayout` -- an administrator who could edit those is one paste away from a message that renders
as markup in Outlook, or one whose text half quietly lost the promise the HTML half makes.

Substitution is `SystemTemplates.fill`, a literal replace with no sections, no partials and no
lookups, for the same reason `LegalDoc.fill` is: what somebody types into a text box must never be
something this server evaluates.

**The legal documents are `legal`.** Two of them, a closed enum rather than "pages an admin can
write", because every email links to them, they exist before anybody has written anything, and the
default text is considered rather than a placeholder. The defaults live in `src/main/resources/legal`
and load once at class initialisation; a database row exists only when a community has overridden
one. They say plainly that the software's authors and the hosting provider are not parties to
anything, which in a self-hosted community is the fact somebody has to be told, and the admin screen
says out loud that nobody who wrote them is a lawyer.

`{{community}}` and `{{domain}}` inside the text are a literal replace and not a template engine:
these are edited by administrators in a markdown box, and anything that could *evaluate* what they
typed would be a way to reach the model from a text field.

**The cookie notice is a line in the footer, not a banner.** There are two cookies here -- the
session and the CSRF token -- both strictly necessary for a service the person asked for, which
under the ePrivacy rules is the category that does not require consent. A consent wall would be
theatre: it would ask permission for something that needs none, and it would need JavaScript and a
place to store the dismissal, which for a site with two cookies means adding a third. So every page
carries one quiet sentence and a link, and the privacy policy carries the table.

**The survey has a page and a front door.** `people/SurveyForm` holds the two things both callers
need -- what a question's boxes look like, and what a submission means -- because the merge rule is
the whole correctness of the thing and the second copy is the one that gets it wrong.
`people/SurveyRoutes` is `/survey`, two views of one form. `people/OrientationRoutes` is `/welcome`,
which asks for a name and then walks into that same survey. Both are reachable by somebody who has
not been approved yet, because between them they are what an admin reads before saying yes.

**The dashboard is `web/HomeRoutes`.** `/home` for a signed-in member: what is waiting for them,
the next seven days, and the threads they are in with the rest of the board underneath. It reads
what other pages own and stores nothing of its own. `/` stays whatever the community wrote there.

**Members are `people/MemberRoutes`.** `/members` is the directory and `/members/<id>` is one
person; both require a session and approval, checked first in the handler. The listing carries what
lets you recognise somebody -- name, roughly where, the first line or two -- and everything else is
one tap away. Neither page carries an email address.

**A template can publish a directory index**, which is what lets the content table behave like a
blog without anybody building one. `directory` on a template gives it an address; a request for that
address renders the template itself with `entries`, `page`, `pages` and the navigation URLs, so an
operator decides what a listing looks like exactly as they decide what a page looks like. Page one
is the bare path; `directory_pattern` decides how page two is addressed, and `{page}` is the number.
Not cached, because a listing changes whenever any page in it changes -- one indexed scan of a few
hundred rows is cheaper than that cascade. Drafts and human-only pages are never in it.

**A surface is a word in the config.** `Surface` is the closed list -- board, calendar, places,
members, survey, invites, app, ai -- and `"disabled": ["places"]` is how a community says it does not have
one.
It wins over the block's own `enabled`, and `config.has(Surface.places)` is the only question anything
asks, so a surface cannot be off in the file and on in one handler that forgot. Everything is on
unless named, because the decision worth writing down is the refusal.

**The engagement loop has a screen.** `/admin/engagement` is every rule for getting somebody here
and getting them back, in one place: the invitation cadence, the five-minute bell fuse, who gets
told about a room, what a push is allowed to say. Almost none of it is a switch, deliberately --
this is the part of a community that goes wrong by being tuned, and a page of sliders would invite
an afternoon of moving them.

**Availability is a weekly shape plus a diary nobody maintains.** A *window* is "Tuesday evenings"
and stays true for years; a *link* is the calendar somebody already keeps, read once a night, and it
is where the exceptions come from. The grid folds every occurrence of an hour inside the horizon into
one cell with two numbers -- how many would like it, how many are clear at *every* occurrence -- so
the question it answers is "could this be our Tuesday" rather than "is next Tuesday free".

`BusyCalendar` keeps two numbers per busy block and throws the rest of the feed away, including the
title. That is a storage decision made once so that "who may see what somebody is doing on Thursday"
is never a question anybody has to answer. `CalendarFetch` is the one place a member's typing becomes
an outbound request, which makes it the one place SSRF can happen; the rules are https-only,
public-addresses-only *after resolution*, no redirects, a timeout and a ceiling.

**The calendar is a queue as well as a calendar.** `calendar.suggestions` (on by default) lets any
approved member put an event forward; `Calendar.State` is `suggested | accepted | declined` on the
same row, because accepting one changes a word rather than copying every field somewhere and
forgetting one. `calendar_review` decides; `calendar_write` implies it. An event's location is a
`place_id` into the address book plus free text beside it.

**Comments are `board`, wherever they appear.** `Subject` is `post`, `event` or `place`; `Board`
holds all three in the one `comments` table; `CommentBox` builds the model and handles the POST, and
`CommentGroups` clumps a long thread by the age of each subtree's root. The board's own thread page
does not route through `CommentBox` -- it has nested reply forms, watchers, locking and expiry, and
folding those in would mean a helper with four flags for the two callers that do not use them. It
does use `CommentGroups`, so the clumping rule exists once.

The subject's id lives in the `comments` table's `post_id` column, whose name has stopped being
literally true. That is deliberate: it is the only column an upgrader that adds columns and never
rewrites rows could backfill correctly, and a name with a history beats a rename that half happens.
Nothing outside the DAO reads it.

**Everything live rides the event bus.** `LiveHub.LIVE_TABLES` is the allow-list -- posts, comments,
calendar, rsvps, places -- and adding a table to it is the whole of making a new page update itself.
A mutation event already names the table and the row, which is all a "re-fetch what you are showing"
signal needs, so a write from a model or the admin shows up live without that code knowing any of
this exists. Nothing publishes to the hub by hand.

**The bell** is on every page except a content page. It lights from the live channel and goes to
somebody's notifications, which is where the same thing is written down.

**Sessions write `last_seen_at` at most every ten minutes.** The live channel turned that constant
from a detail into a decision: every open tab now touches its session every twenty seconds forever,
so the old one-minute rule would have made "somebody left a browser open" the largest source of
writes in the server.

**The address book.** `places` and `place_types`**The address book.** `places` and `place_types`: one table of addresses and one of *kinds*, where a
kind declares its own fields as a JSON blob of `TemplateField` -- the same machinery a content
template uses, because two ways to describe a form would eventually disagree about "required". A
supper club invents `ranch` with grass-finished and cuts-sold; an MS group invents `vendor` with the
discount and who to ask for. Neither belongs in the schema, and a community that had to ask for a
column would keep it in somebody's head instead.

A kind names a content template, and every place of that kind renders through it via
`Site.renderWithTemplate` -- sharing the template cache and its invalidation, so editing a template
updates the ranch pages at the same moment it updates the content pages. Without a template there is
a built-in page, because a community has not written one on its first day.

Slugs, unique within a kind, so `/places/ranch/oak-hill` survives the name being edited. Identity on
save is the id when there is one -- the rule content learned the hard way.

**Search checks values, not the blob as text.** Searching "grass" must find a ranch that recorded
grass-finished and must *not* find one that recorded grain -- and the blob is
`{"grass_finished":"grain finished"}`, so a raw `LIKE` matches the field's *name* and returns exactly
the place somebody was trying to exclude. The SQL is a prefilter; the values are checked properly in
Java.

The AI tools follow the same asymmetry as content: human-only places are absent from listings and
refused out loud on a write, a model can never set or clear the bit, and a field the kind never
declared is **refused rather than dropped** -- a write that claimed success while silently discarding
half of it would teach the model it had succeeded.

**Inbound mail** is `smtp`, adapted from adama's module: the same Netty bootstrap and pipeline shape
-- CRLF framing, an idle timeout, a string codec, a session -- with SPF, DKIM, DMARC, MIME parsing,
STARTTLS, AUTH and the document bridge all left out. What is here is the protocol, the limits, the
routing and a receiver that prints. **Off by default**: port 25 needs root, an unconfigured listener
on it is found by scanners within the hour, and it binds on its own event loop so a slow mail
conversation can never hold up a page.

`SmtpRouting` is the one piece that must never be wrong. A recipient is accepted only if its domain
has a config file, resolved through the same `DomainTree` the web side uses -- and **exactly**, never
by wildcard. `wildcard: true` is a reasonable thing to want for a website and catastrophic for mail:
it would accept for every domain under that suffix, which is an open relay built by accident. A
**named** `subdomains` entry is accepted, because a written-down list is neither a wildcard nor an
accident, and `accepts-mail: false` opts a domain out entirely -- for a community whose website is
here and whose mail is somewhere else. The refusal happens at RCPT, before any body arrives, so a
relay attempt costs one line instead of a megabyte.

**Every message is checked with SPF, DKIM and DMARC**, adapted from adama's validators and turned
from callbacks into straight recursion because this runs on a worker pool rather than the event
loop -- DNS blocks, and one slow nameserver must not stall every other conversation. The findings
are stamped onto the front of the message as `Authentication-Results` whatever the outcome, because
the checks are not only for refusing.

The three answer different questions and only together answer the useful one. SPF authenticates the
envelope sender, which nobody reads, and breaks on forwarding. DKIM authenticates whichever domain
chose to sign, which need not be the one in the message, and survives forwarding. **DMARC is the
alignment**: it requires the authenticated domain to line up with the `From:` header, which is the
identity a person actually sees, and lets the owner say what to do when it does not.

**Only `p=reject` refuses.** Refusing on an SPF failure alone would reject every message that came
through a mailing list, and a community whose mail silently stops working is worse off than one
receiving the occasional visible forgery. A DNS failure is `temperror` everywhere rather than a
fail, because an unreachable nameserver must never read as a forgery.

`SmtpDns` is an interface first so every test is a test of what happens when DNS says a particular
thing; nothing in the SMTP tests touches the network. The DKIM tests generate a keypair, sign a real
message and publish the public half in the fake resolver -- and both halves of the verifier were
checked by breaking them: making the RSA verify always succeed, and skipping the body-hash
comparison, each fail a different test.

`stripDelimiter` on the frame decoder is true, and that is load-bearing: the session compares a body
line against `"."` and writes its own CRLF back, so a line still carrying its delimiter would never
terminate a message and the sending server would wait, time out, and retry the whole thing forever.

## The virtual hosting rules

**Flat on disk, tree in memory.** `<root>/domains` is a flat directory of `<domain>.cfg` JSON
files; `domains/junior.example.org.cfg` configures `junior.example.org`. The filename is the domain —
there is deliberately no `domain` key inside the file.

Scanning (`DomainScanner`):

- Only `*.cfg` files are read. Directories are ignored — that's where `static-root` points. Other
  files (`README.md`, `.gitignore`) are ignored with a verbose note.
- The name minus `.cfg` must be a valid lowercase domain or the scan fails. Uppercase is rejected
  rather than folded, so `Example.ORG.cfg` can't become a second spelling of `example.org`.
- `enabled: false` loads but warns — an operator should see their own kill switch at boot.

Resolution (`DomainTree`):

- Labels are inserted reversed, so the tree is rooted at the top level domain.
- A request descends from the TLD as far as its labels allow, keeping the deepest node whose config
  *applies*. Applies means: it's the exact domain asked for, or it set `wildcard: true`.
- A node with no config is a **junction** — it exists because something lives under it, and it
  serves nothing. `example.org.cfg` alone puts `org` in the tree; `org` and `elsewhere.org` are
  still 404.
- A non-wildcard node does not stop the descent from having already matched a wildcard ancestor.
  With `org` (wildcard) and `example.org` (not), `www.example.org` resolves to `org`.
- **Named subdomains are consulted first**, because somebody wrote them down and that is the most
  specific statement there is. `subdomains: ["www"]` on `example.org` answers for `www.example.org`
  with *that same config* -- one community, one database, one set of accounts.

`subdomains` are kept in a map beside the tree rather than inserted into it, and deliberately do not
appear in `all()` or `size()`. Everything that walks `all()` -- `Stores`, `Mailers`, `Notifier` --
must see one entry per *community*; an alias in there would give `www.example.org` a database of its
own and a second set of accounts, which is the opposite of what listing it meant. A host with its
own config file always wins over somebody else naming it, and the scanner refuses to start on the
clash rather than picking a winner.

Keys are documented in [MANUAL.md](MANUAL.md) and parsed in `DomainConfig.of`.
Adding a key means: read it there, add it to that table, and add a test — the strict reader will
reject it in existing configs otherwise, which is the intended failure mode.

## Accounts, storage, and policy

**One database per domain**, an H2 file under `<root>/dbs` named for the domain. `use_database_domain`
points a domain at another's; that is one level deep, validated at boot, and means one account space
— same people, same sessions. The **owning** domain's `login_security` governs a shared database,
because one database cannot have two answers to "how long is a session".

**The schema is `Schema.java`.** No migration scripts. Add a column where it belongs in the list,
bump `VERSION`, restart. `SchemaUpgrader` reads INFORMATION_SCHEMA, diffs, and adds what is missing
**in the declared position** via H2's `ALTER TABLE ... BEFORE`, so an upgraded database is shaped
like a fresh one. It will not drop or rename (reported, left alone) and will not retype (fatal).

A column added in a later version must be nullable or carry a default — there is no correct value
for rows that already exist. Declare one that isn't and the upgrader refuses to start, naming the
column. `SchemaUpgraderTests` builds old-shaped databases by hand and proves the ordering.

**The tables.** `emails` (people; `password_hash` is nullable because passwordless is the default),
`sessions` (live logins; `token_hash` only, never the token), `roles`, `bans`, `content`,
`templates`, `profiles`, `questions`, `answers`. `schema_meta` records the version for the boot
audit. A brand new table's own NOT NULL columns belong in `SchemaUpgraderTests.FOUNDING`; everything
declared after a table exists has to be addable to a populated one.

**Sessions** are a write-through cache in front of the table. Reads hit a `ConcurrentHashMap` with
no I/O; writes hit the database first. The reaper flushes `last_seen_at`, deletes dead rows, evicts
cold cache entries, and applies the per-person cap — where the cap only ever takes sessions older
than the grace window, so a new sign-in never knocks somebody out mid-task.

**Approval.** Every account starts unapproved and cannot hold a session. `admin_emails` in the
config lists addresses that are admins by fiat -- approved, holding the admin role, un-revocable from
inside the running system. Without that list, requiring approval would mean nobody can ever sign in
on a fresh install. `Access` is the only place that answers "is this person an admin" or "may this
person sign in"; `UserRecord.canSignIn` deliberately does not know about approval.

The admin therefore has three different "no", and they are deliberately not the same button. **Not
yet** is leaving somebody unapproved. **No** is rejecting: the account, profile and answers are
deleted, because keeping a rejected stranger's data means keeping data nobody will read again.
**Not right now** is turning the account off, which keeps everything and ends every session. Only
rejection can also **ban** the address, which is the cheap refusal for somebody not worth reviewing
twice. An admin can be none of these until their role is removed -- the two spellings of admin are
drawn differently in the listing (config admins red, promoted admins purple) because one is a fact
about a file on the box and the other is a decision somebody made in this UI.

**The register page has no form in its HTML.** `FormMint` issues a per-page-load mapping from
logical field names to opaque ones (prefix + HMAC of a per-form secret), the page assembles the form
in JavaScript from a JSON blob, and the submission carries a proof the script ran, a honeypot the
script leaves empty, and `Signals` counts of what the browser did. One submission per minted form.
Zero interaction events is refused; the counts are stored on the account for later.

Testing that means the test client has to do what the script does -- see `Browser` in the testkit,
which reads the blob, translates names, and computes the proof. `withoutProof()`, `withoutSignals()`
and `fillingTheTrap()` are how the refusals get tested.

**The event bus.** Every DAO write calls `store.changed(table, key, kind, actor)`, which emits a
`MutationEvent` naming the domain, table and primary key. `LocalEventBus` keeps the last 1000 in a
ring for `/admin/events` and notifies listeners inline on the writing thread -- so the read after a
write is already correct, with no stale window. `EventBus` is an interface because that is the
scaling escape hatch: several processes behind a sticky load balancer break on cache coherence
first, and an external bus implementation is the fix.

**Caches** all take a `CachePolicy` from the domain's `cache` config. `TtlCache.invalidateIf` is how
a template change drops every page that used it. Cache stats are on the admin overview, because a
cache nobody can see the hit rate of is a cache nobody can tune.

**The database is behind `Database` + `Dialect`.** H2 runs in `MODE=STRICT` on purpose: it refuses
H2's own extensions, so the SQL is much more likely to work unchanged on MySQL or PostgreSQL. When
strict mode rejects something (`LIMIT ?` is the one that bites), the workaround goes in `Dialect`,
not inline.

**Content and templates** live in tables and are edited at `/admin/content` and `/admin/templates`.
`Site` renders and caches; the cascade is in its event listener.

**Every save is versioned.** `ContentVersions` keeps a snapshot every ten versions and a patch
between them -- and a snapshot whenever a patch would not be smaller, since a patch larger than the
document is a worse snapshot. `TextPatch` is a line diff with an explicit longest-common-subsequence
rather than Myers, chosen because the point is that you can read it and believe it; it is bounded at
5000 lines and the caller's answer to the bound is a snapshot. The round trip is checked against
thousands of generated edits, and one test asserts a one-line change makes a small patch -- which is
what caught an emit loop that was correct and useless, deleting and re-inserting the whole tail of
every document. A round-trip test alone cannot see that.

The history is at `/admin/content/history/<id>`, and one version at
`/admin/content/history/<id>/version/<n>` -- a real path like every other sub-view, which is what
the preview modal fetches. Previews render through the *current* template, because that is what
restoring the version would produce.

**The board caches two things**, and both are cached because they are expensive and viewer
independent: the feed's rows, and a thread's markdown rendered to HTML. Everything per person --
whether you are watching, whether you may reply -- is computed from the cached value at render time,
because a cache keyed by viewer in a community of five hundred is five hundred copies of the same
paragraph. Invalidation rests on one fact: every change to a comment also touches its post, since
`Board` updates the comment count and last activity in the same breath. So a listener on the posts
table sees every change to a thread's contents and one keyed by post id is enough -- watching the
comments table instead would need a query on the invalidation path, and for an insert, a comment no
cached entry has ever heard of.

**Notifications leave through `Notifier` and nowhere else.** One background thread for the whole
box: it reads the unsent rows, groups them by person, asks `NotifyPrefs` what that person wants, and
either sends now, holds for a digest, or stamps and drops. A database shared by two domains is
delivered for once, under the domain that owns it -- the same rule that makes shared databases take
their policy from the owner, for the same reason: two mails for one reply, from two different
communities, is not a thing anybody wants twice.

Everybody's page has a Notifications tab. SMS is groundwork only: `Sms` is a seam, `NoSms` is the
only implementation, and the settings page says so rather than storing a preference nothing honours.

**The calendar** is `/events` (`urls.calendar`), admin-created and member-answered. `Calendar` is
one DAO for both halves: the events, and the RSVPs. The counts on an event row are a cache of the
answers, recomputed after every change rather than incremented, because an increment that drifts is
a room that is full when it is not -- and for a few dozen answers, recounting is one query.

**Analytics** is `AccessLog`, a 5000-entry ring. `WebHandler.Recorder` writes exactly one entry per
request, including the user id from session resolution -- which is why session resolution happens on
the request path rather than deeper in.

**The admin URL space** is `AdminView`. Sections nest -- Overview, People (> Bans), Content
(> Templates, Navigation), Survey, Projects, System (> Events, Analytics, Caching, Async, Logs) -- and `Section.slug`
is the whole truth about paths. Every section is a real server load; the refreshable part of one is a
**panel** at its own path (`/admin/people/list`, `/admin/system/logs/results`), rendered by the same
method the section page calls to embed it. Creating and editing are page transitions
(`/admin/content/new`, `/admin/content/edit/41`), never a form above a listing. Mutations are
`POST` to the section path with no query at all, answered with a 303 and a `Flash`.

Adding a section means: a `Section` constant, a `<name>.mustache`, an entry in `Templates.PAGES`, and
-- if it refreshes in place -- an entry in `AdminView.PANELS` plus a `<name>_panel.mustache`. The
shell carries about seventy lines of vanilla JavaScript that swaps any `[data-panel]` container and
drives any `[data-filters]` form; a new panel gets that behaviour for free and needs no script of
its own. **Nothing in that script reads a template value** -- see invariant 22.

**Templates can declare fields.** `TemplateField` is a name, a type, a label and whether it is
required; the list lives as a JSON blob on the template row, and the page editor renders a box per
field with the values stored as a blob on the content row. That is why a template author can ask for
a headline without anybody touching the schema.

**Content carries a navigation folder.** A page with no folder is reachable by its uri and nothing
else, which the listing flags and `/admin/navigation` collects, because a page nobody can navigate
to is a page nobody will find.

**Profiles and the survey.** Every account starts unapproved and can reach only `urls.self`, where
they write a profile and answer the community's questions -- which is what the admin reads at
`/admin/people/review/<id>` before saying yes. Questions are rows whose definition is a JSON blob;
answers are one row per person whose blob is keyed by question id, so an answer survives a reword
and stays uncounted after a delete.

`SurveyIndexer` maintains the "how many are left" counts on a single background thread, driven by
the event bus: an answer event recounts one person, a question event recounts everybody. Sweeps
coalesce, because the natural way to build a survey is to sit there adding questions. `settle()`
exists for tests; nothing on the request path waits on it. Somebody who has never answered has no
answers row at all, so `remainingFor` counts rather than reads on a cache miss -- they are exactly
the person the bubble is for.

**The API is two halves and they must never be confused.** `/api` is a page for a person,
authenticated by the session cookie like any other page, where a token is minted and revoked.
`/api/v1/...` is for a program, authenticated by `Authorization: Bearer` and **never** by a cookie --
a JSON endpoint that accepted the browser's session would be a cross-site forgery hole with no form
and no token in it, reachable from any page a member happens to have open.

The token is copied by hand on purpose: a CLI prints an address, somebody opens it, reads what is
being asked for, presses a button and copies a string back. No callback, no local listener, no
redirect -- so it works from a machine with no browser, over SSH, and from a phone, and there is
nothing to get wrong about which program received what.

A token is a session with `robot` set and an `api:` label, which is what keeps it apart from a
model's connection in every list and every revocation. Two per person, refused rather than rotated;
thirty days, or whatever `api.token-days` says.

**The model endpoint.** `mcp.enabled` is false by default -- the only default in this server not
tuned for a high trust community, because what it hands out is the ability to act as somebody. When
on, a domain serves OAuth 2.1 discovery, registration, consent and token endpoints plus JSON-RPC at
`mcp.path` (`/mcp`). The flow is: a connector registers itself with a redirect this domain already
trusts, **somebody holding `agent_connect` looks at a consent screen and agrees**, a code comes
back, and PKCE proves the client redeeming it started the flow.

It is no longer admin-only. `agent_connect` is a permission an admin grants in a role, so a
community can let members bring their own assistants -- which is what makes the board a place
several people's agents can coordinate in. It is deliberately not a baseline (a connection is a
standing credential held by somebody else's software) and deliberately not `admin_enter` (what an
agent can do is what its person can do). `ai_manage` stays what it was: the screen listing every
connector in the community. Codes are memory-only, single use, and bound to the client and
the redirect they were issued for.

Redirect matching is an explicit prefix list and nothing else -- no wildcards, no host-suffix
matching, no "same registrable domain". Every one of those has been a real OAuth advisory, and the
list is short enough that exactness costs nothing. `Vendor` ships prefixes for Grok, Claude and
ChatGPT; **those are a starting point, not gospel**, and an operator adds to them with
`mcp.extra-redirect-prefixes` when a connector moves.

Tools go through `AiSurface` and nothing else. A tool description **is a prompt** -- the model reads
nothing else about this server -- so they say what a thing is for and when not to use it. The set is
deliberately small and job-shaped ("search the site") rather than row-shaped, because a model given
`execute_sql` will eventually execute some SQL.

`AiLog` keeps the last 1000 actions with arguments and results as **JSON**, rendered indented in the
admin. Storing the structure rather than a formatted string is the point: the pretty form is a view,
and a log that threw away the shape could never be filtered or exported. In memory like the access
log; every action that changed a row also emitted a mutation event, so the durable record of what
changed is elsewhere.

**Certificates.** `<root>/certs` holds them, and `enable-certs` in `config.cfg` turns them on: the directory holds the ACME
account and a key, chain and bundle per domain, and is a cache in the sense that it can be rebuilt
-- but it also holds the account key, so losing it means registering again and starting the rate
limit clock over. Adapted from goatbot's agent with the S3 bucket, the Route53 client and the
`openssl` shell-out removed: the server that wants the certificate answers the challenge itself.

HTTP-01 only, **which means no wildcards** -- a wildcard needs DNS-01, which needs credentials for
whoever runs the DNS, which is the dependency this removes. A domain served by wildcard gets a
certificate for its own name; junctions and anything `localhost` are skipped, because no authority
will ever issue for them and asking is how you meet a rate limit for nothing.

`--setup-certs` is a walkthrough rather than a flag because the failure it prevents is expensive.
Authorities rate limit failures hard, so somebody restarting a server whose DNS is not ready yet can
lock themselves out for an afternoon. It says what must be true, resolves each domain as a hint,
offers staging first, prints the terms with their URL, and refuses to run without a terminal --
because the point is to make somebody think, and a pipe cannot.

**Three listeners, for three different reasons.** `http-port` (80) is always on: it serves the
site and answers the ACME challenge, which is why it can never become a redirect. `https-port`
(443, with `enable-https`) terminates TLS, picking the certificate by SNI from `TlsContexts` --
a live map, not a snapshot, so a renewal months from now is presented without a restart.
`http-bounce-port` (9999, only when asked for) is a whole listener that does nothing but 308 to
https, for load balancers that want somewhere to send plain traffic. A host with no certificate gets
a self-signed fallback rather than a dropped connection, because a refused connection looks exactly
like a firewall problem and a browser warning names the real one.

**Icons are inline SVG** in `Icons`, drawn with `currentColor` so light and dark need no second
copy. No sprite sheet, no icon font, no build step.

**Mail** goes through `Mailer`, a closed list of flows. `DevBoxMailer` prints to the terminal;
`AmazonSes` sends for real. Which one is used is per domain, because the credentials are -- so one
box can have a live community and one being set up, which is the normal state. `Mailers` dispatches
on the envelope's domain so nothing upstream knows mailers are plural.

**Every message has one shape.** `MailLayout` builds it and `Messages` says what each flow
actually says, in both halves. The wording lives there rather than in `AmazonSes` because it is not
a property of the transport -- a community's sign-in code should read the same whether it went
through Amazon or was printed to a terminal, and the version that only exists in the provider
implementation is the one that quietly drifts. Email is HTML from 2004 with a different renderer in
every client, so: tables not divs, inline styles only, a bulletproof button, 600px fluid, a
preheader, a declared colour scheme, every link repeated as text, and always a plain-text half.

The colours come from the community's `site` palette, carried on the envelope as a `MailBrand`
rather than looked up by the mailer -- the mailer knows how to reach Amazon, not which colours a
community chose. Only the *light* palette: email clients handle `prefers-color-scheme` badly and
inconsistently, and a dark background chosen here would arrive at half the readers as a black box.
Button text is black or white by relative luminance, so a community that picks a pale accent gets
readable buttons rather than white on white -- and whoever chose the colour is the last person who
would notice.

SES is one signed POST rather than the AWS SDK: forty megabytes of dependency for one request is not
a trade a single-jar server should make. `SignatureV4` is adapted from adama's, minus a bug worth
not copying -- adama's SES caller puts the region in the URL and a hardcoded `us-east-2` in the
signature, so it works in one region and fails opaquely everywhere else. It is checked against
Amazon's own worked example, because SigV4 fails closed and silently and a bare 403 tells you
nothing about which canonical string was wrong.

Defaults are for a high trust community and every one tightens with a line of JSON. See
[MANUAL.md](MANUAL.md) for the full key list.

## Testing

JUnit 4. Tests live beside the package they cover and are named `*Tests`. Surefire runs from the
project basedir, so tests may read the checked-in `site/` tree (guard with `isDirectory()` so they don't fail
elsewhere).

**Prefer testing the server over testing a class.** `src/test/java/io/hearth/testkit/` exists so
that a test can boot the real thing and talk to it:

- `TestServer` — boots the full path (scan → table → bind) on 127.0.0.1 port 0, `AutoCloseable`.
  `ofConfigs(dir)` for the real boot, `of(table)` to skip the filesystem, `of(table, config)` to
  drive a limit.
- `Configs` — builds throwaway config trees in temp directories. `Configs.standard()` is the tree
  most tests want. Write the tree an operator would write; that puts the scanner under test too.
- `Http` — the client. `get/head/send` go through the JDK's `HttpClient` (realistic: connection
  reuse, chunking). `Http.raw(port, bytes)` goes through a socket for things a normal client won't
  send — malformed request lines, a missing Host, pipelining, checking that the socket closed.
- `Verbose.capturing()` — a verbose sink that captures instead of printing, so narration can be
  asserted on and doesn't spray across the build log. Never use `new Verbose(true)` in a test.

Setting `Host` explicitly is the point of a virtual hosting test, and the JDK client refuses that
header unless it's allowlisted — surefire sets `jdk.httpclient.allowRestrictedHeaders=host` in
`pom.xml`, and `ServerHttpTests.boot()` asserts it took effect so a broken build fails loudly
instead of quietly testing one hostname.

`WebHandlerTests` uses Netty's `EmbeddedChannel` for fast handler-level checks. That's the
exception, not the pattern — anything about status codes, headers, or connection behavior belongs in
`ServerHttpTests` or `ServerLifecycleHttpTests` where it goes over a socket.

Cover the refusals, not just the happy path. Every invariant above should have a test that proves
the server says no, and an assertion with an `|| raw.isEmpty()` style escape hatch is a test that
proves nothing — verify what actually comes back, then assert exactly that.

`just coverage` enforces a floor (80% line, 70% branch) so coverage can't quietly rot. The
uncovered remainder is `Server`'s bind path and `Boot`'s failure output, which `just smoke` covers
against the real jar instead. Raise the floor when the honest number moves up; don't lower it.

155. **An invitation is a file, not a link.** `text/calendar; method=REQUEST` as a third alternative
    inside `multipart/alternative` is what makes a mail client draw accept/maybe/decline; the same
    bytes as an attachment are a file somebody has to notice. That distinction is the whole feature,
    and it is why the SES path uses the raw API rather than the simple one.
156. **The invitation refuses to go out when the answer could not come back.** Every one of them says
    "answer from your calendar", and a calendar answers by email *to this server*. With inbound mail
    off, pressing Accept goes nowhere: the person believes they answered, the guest list never
    hears, and the nudge loop chases somebody who did reply. `calendar.invites` being on is not the
    same as it being possible.
157. **A reply is a claim about identity arriving over SMTP**, so `IcsReplies` checks all of it: the
    sender is a member, the `ATTENDEE` in the file *is* the sender, the UID names an event here, the
    sequence is not older than ours, and the message passed SPF/DKIM/DMARC. Without the second of
    those, anybody who can send an email can accept on anybody's behalf and the guest list is
    fiction.
158. **Everything a calendar reply gets wrong is accepted and ignored, never bounced.** A `550` to a
    calendar client teaches it this address is broken, and the bounce lands on somebody who did
    nothing but press a button. The honest failure is that their answer did not register and the
    nudge asks them again.
159. **A COUNTER is a suggestion, never a change.** An attendee proposing a new day records it on
    their own RSVP; the organiser takes it or does not, and taking it is an ordinary reschedule with
    a fresh sequence. A calendar where any attendee can move the event is not one anybody can plan
    around.
160. **The sequence is what makes an update land.** A client ignores a REQUEST whose sequence is not
    higher than the one it holds -- which is right when a message is delivered twice and a trap if
    you never raise it. Changing an event marks its invitations stale; sending again is what moves
    it in everybody's calendar.
161. **Nudges are for silence, never for an answer.** Somebody who said no is never chased. Whether
    a nudge is due is computed from the event's date and their silence rather than from a stamp, so
    a pass that runs twice sends nothing twice and a server that was off for a day does not send
    yesterday's.
162. **A no-show is recorded by somebody who was there, never inferred.** It needs `calendar_write`,
    it is only offered once the event has happened, and it is a note for the people organising
    rather than a mark against anybody.

176. **The wording of a message is a community's; the shape of it is not.** Three boxes -- subject,
    opening line, one paragraph -- and everything else stays in `MailLayout`. A default lives in the
    jar rather than in the table, so a community that never opened the screen keeps getting the
    improvements, and saving the shipped text back deletes the row rather than storing a copy of it.
177. **A flow declares what it can say.** `availableParameters()` is printed on the editing screen
    and filled into a preview with believable values, because `{{first_name}}` in a message that has
    no such value renders as nothing at all -- a hole that goes out to everybody with nothing to
    notice it. Braces are never printed at a reader either; the preview is where somebody finds out
    which they have.
178. **A nudge is not an invitation.** `Mailer.Note` is an enum rather than a pair of booleans
    because the pair had a state nobody meant and no room for the one that exists: a reminder sent
    to somebody who has not answered used the invitation's wording, which is how a community ends up
    telling the same person "you are invited" for the third time.

168. **No recurring events.** Every event is written down on purpose, once. A series expressed as a
    rule keeps happening whether or not anybody decided it should, and this is for a community where
    somebody says "same again next month" and means it -- so the second one is a second event with
    its own guest list and its own answers.
169. **The calendar has its own address, and it is derived rather than defaulted.** Everything else
    this server sends is one-way and can come from `no-reply@`; an invitation is a conversation and
    its address has to be one this machine *receives* at. Absent from a config it is `events@` on
    the domain the community already sends from -- which is the domain whose SPF, DKIM and MX are
    already set up -- so an existing community upgrades without touching a file.
170. **An invitation mailed in becomes an event, under the same permission the screen needs.**
    `calendar_write` lands it accepted; an approved member gets what they would get from the site,
    a suggestion in the queue; anybody else is ignored. It keeps the UID it arrived with, so the
    sender's next update finds it rather than making a second event.
171. **A location is matched before it is created.** Name and address first, normalised so that
    "The Oak", "the oak " and "St Mary's" versus "St Marys" are one place; then, when geocoding is
    on, anything within three hundred metres. Only then is a place written down, unpublished --
    because a place a machine made from one line of an email is a draft rather than a decision.
172. **Geocoding is on by default -- see invariant 227, which reversed this.** What the original
    rule was protecting is intact: it still sends a member-typed address to another company, it is
    still one word to switch off, and the privacy policy still describes it. What changed is that
    the default service costs nothing and needs no account, and that the addresses became the
    feature rather than a detail of it. The three services offered are the three whose terms permit *keeping* the answer;
    Google, Mapbox and HERE all restrict it, and HERE -- which has the largest free tier -- caps
    caching at thirty days. `--setup-gps` says so, because that mistake is made before anybody types
    anything and nothing else would ever tell them.
173. **Nominatim's policy is enforced, not hoped for -- and where it is enforced moved.** The
    User-Agent identifies the application unconditionally, so there is no such thing here as an
    anonymous client. `gps.contact` makes that identification *useful*, and it is fatal at boot for
    anybody who wrote a gps block and asked for a reachable contact by omission -- but not for the
    default, because refusing to boot an install that never mentioned gps is not a default. It is
    nagged for on the settings screen and on the Async screen instead. A client that cannot be
    reached can be blocked without warning, which from inside looks exactly like geocoding quietly
    stopping. One request a second applies to every service, because
    a server that is polite everywhere is easier to keep right than one that remembers who cares.
174. **Never geocode on the request path -- and as of the queue, that is finally true.** It is a
    network call to somebody else's server with somebody else's latency. A save writes the row and
    puts an ask on `AsyncQueue`; the coordinate lands a minute later. Places used to be geocoded
    *inside* an admin's save, which honoured the letter of this rule and not the point of it. A
    mailed-in event still matches against what is stored before it asks anybody anything.
175. **Moving an event asks what happens to the answers, and never decides.** Forty people said yes
    to a Tuesday and it is now a Thursday: keeping the answers claims forty people agreed to an
    evening they did not, and clearing them starts from nothing. Which is right depends on how far
    it moved, so the screen asks. Clearing keeps the **no**s -- that is probably still true, and
    asking again teaches people that answering does not stick.

179. **An event is members-only until somebody says otherwise, one event at a time.** Open to the
    public is a button rather than a checkbox on the form, because it changes who may read the event
    and where an answer may come from. Turning it off keeps what already arrived -- those people
    still said it -- and the file at `/events/<id>.ics` follows the same rule the page does, checked
    where it is served rather than inherited, because a file is exactly as public as whoever can
    fetch it.
180. **An answer from outside is not a seat.** `public_rsvps` is its own table rather than a nullable
    `user_id` on `rsvps`: the seat counting, the guest list, the export, the erasure and the no-show
    marking are all keyed on a member, and the first query that forgot the extra clause would be the
    one that counted a stranger into a room with twelve chairs. Capacity is a promise to the people
    a community can actually reach.
181. **What it is really for is the invitation.** Somebody who heard about a thing, said they were
    coming, and has never been asked to join is the strongest lead a small community gets. The
    address is shown in the admin section, where a decision about an address is what is being made,
    and never on the event page -- invariant 146 does not have an exception for guests. Approval is
    the moment their answers become answers, because before it they are not somebody this community
    can reach.

198. **Somebody who has said nothing is still counted, from an assumption said out loud.** Weekday
    evenings and most of a weekend day. It is wrong about individuals and roughly right about
    groups, and it is the whole reason the grid is worth opening before anybody has filled anything
    in -- a tool that only worked once everybody had used it would never be used by anybody. What
    somebody enters *replaces* the assumption rather than merging with it, so the people who care
    most are the ones who move the picture.
199. **An hour is clear only if it is clear every week in the horizon.** Softening that into an
    average is how a screen confidently recommends the one evening half the group cannot do. The
    grid is a fold, not a forecast: for one particular date, put the event up and read the answers.
200. **The grid is counts and never names.** Who is free on Thursday is a question about individuals
    that nobody agreed to answer, and a screen that answered it is a screen people stop putting
    their calendars into.
201. **A calendar is read once a night, never on a request path, and what it said is cached in a
    table.** Fetching on render is a page whose speed depends on somebody else's server; two hundred
    members opening it is two hundred requests to the same host. A link that fails keeps its last
    good answer and shows the reason to its owner -- a link that silently stopped working is a
    member the grid quietly starts lying about.
202. **A member-supplied url is an instruction to make a request.** https only, public addresses
    only *after the name resolves* (a name pointing at 127.0.0.1 is the whole trick), no redirects,
    a timeout and a size ceiling. Every one of those is a published attack, and every one of them
    arrives as somebody pasting a "calendar link".

211. **The garbage collector's marking is the dangerous half, so it reads everything.** A sweep that
    keeps too much wastes disk; a sweep that misses one place a url can hide deletes a photograph
    that is on a page, and nobody finds out for six months. It reads every text column in the
    database -- including a page's *history* and a *suggested edit*, which are the two nobody thinks
    of: delete something only an old version points at and restoring that version produces a broken
    page, which makes a history that cannot be restored.
212. **A partial scan offers nothing.** If any source could not be read, the answer is "I do not
    know", and a delete button on top of that offers to remove files it never looked for.
213. **Nothing uploaded in the last day is ever swept, and the scan runs again on the button.** A
    file uploaded twenty minutes ago is on somebody's clipboard on its way into a page that does not
    exist yet; and the screen somebody is looking at was drawn a minute ago, so the decision is made
    against the database as it is when they press it rather than as it was when they looked.

205. **The extension decides what an upload is; the browser's content type is thrown away.** A
    declared type is a claim by whoever is uploading, and believing it means a member uploads
    `photo.png`, calls it `text/html`, and this community's own domain serves attacker-written HTML
    to people signed in to it. The allow list is closed, `text/html` is not on it for any extension
    or any configuration, and anything not embeddable is sent as a download.
206. **Nothing about an attachment's address is a path.** The id is a long, the extension is looked
    up in a table, and the file is found by computing from both -- so no string from a request goes
    anywhere near the filesystem, and the filename somebody chose is never used as one.
207. **Private is the default and it answers 404.** Whether a private file exists is itself private,
    and a sign-in form is no use to the `<img>` tag that asked. Attachments are served *before* the
    approval gate, because a public one is public and a poster on the front page must not stop
    rendering for somebody whose account has not been approved yet -- the route's own check is the
    stricter one.
208. **`Cache-Control: private` on every attachment, always.** Browsers may keep one; a shared cache
    may never. These are frequently a photograph of somebody's children, and a copy in a proxy is a
    copy in a place nobody in this community chose. The url carries an id, so `immutable` and a long
    max-age cost nothing.
209. **The referrer check is a bandwidth measure, not a boundary.** It stops a community's server
    being a free image host for somebody else's forum. A request with *no* referrer is honoured,
    because browsers omit it constantly and refusing those would mean refusing members to
    inconvenience somebody who can forge the header -- invariant 13, applied.
210. **One path is allowed a body bigger than a form, and the pipeline decides that from the request
    line.** Netty's aggregator takes one number, so raising it for uploads would raise it for every
    path -- and then a stranger POSTing 25MB to `/` would have all of it buffered before anything
    could refuse. `UploadGate` sits in front of the aggregator and refuses on the declared length
    unless the path is the upload one.

214. **The only number worth having about a notification is how long it takes to work.** Not how
    many went out -- that goes up whether or not anybody looked. A tap is the one honest signal the
    server gets, and the delay is drawn as buckets rather than an average, because the average of
    "three people in a minute and one tomorrow" is six hours and describes nobody.
215. **Push stamps are buffered in memory and written on a timer.** Two writes on the path that
    fires whenever anything happens on the board would be among the busiest in the server, to
    answer a question that does not mind being five minutes stale. The buffer dedupes: three
    devices buzzing is one notification as far as the person experiencing it is concerned.
216. **A push says who it is for.** A browser can hold two people's subscriptions -- a shared
    laptop, somebody who signed out and in as somebody else -- and a service worker has no session
    to ask. An id, and never what the notification is about.
217. **A directory index is a second template.** One body cannot be both a document and a list, and
    a file that opens with `{{#directory}}` is a file nobody can edit six months later. Ticking the
    box seeds a working listing; a template written before this existed is left alone and keeps
    rendering exactly as it did.
218. **A page's published date is a date, and it is mutable.** A page drafted in January and
    published in March is a March page, and a community bringing twenty years of a newsletter in
    wants 2011 to say 2011. Absent means the first save, which is right for everything written here
    in the ordinary way -- and the column stays null rather than being filled with a copy of
    `created_at` that would then have to be kept in step with it.
219. **A tool description is a prompt, and `site_spec` is the longest one.** A model has no screen:
    it cannot see that picking a member listing changes what the uri field means, or that a place
    listing can be narrowed to one kind. Every rule of that sort lives in exactly one place in the
    code and is handed to whoever is writing pages, so an agent's first attempt is a working site
    rather than six refusals.

220. **The manifest is declared on every page, and its icons are fetchable.** Both were wrong and
    the symptom was the same: no browser ever offered to install this. A browser offers to install
    what the page in front of it declares, and it was declared on `/~app` alone -- an address nobody
    reaches without already knowing about it. The icons were the inline SVG favicon as `data:`
    URIs, which is correct by the specification and refused in practice: Chrome downloads manifest
    icons and iOS wants a PNG before it puts anything on a home screen. `AppIcon` draws them at
    request time in the community's accent colour, so invariant 18 still holds -- there is no image
    file, and a community that changes its colours changes its icon.
221. **The worker has a fetch handler and still caches nothing.** A browser will not install an app
    whose worker cannot answer a navigation with the network down. Everything passes straight
    through; the only thing built inside the worker is a "no connection" page, which is the one
    document for which stale is not a possible state. An offline cache of real pages would serve a
    stale members list, which is what the original rule was refusing.
222. **The self-test reports two facts, never one.** "The push service accepted it" and "this
    device showed it" are different, and every push problem lives in the gap between them -- three
    permissions, an operating system that can silence a whole app, and a focus mode nobody
    remembers turning on. The worker posts a message to the page when one lands, so the page can
    say which of the two happened rather than saying "sent" and leaving somebody to guess.
223. **An overflow container clips an absolutely positioned descendant.** The nav bar scrolled
    sideways, which was right when it held a row of links and fatal once it held a menu: the panel
    opened every time and was invisible. Worth writing down because the markup and the JavaScript
    were both correct and the bug was one property on an ancestor.

224. **Nothing that waits on somebody else's server happens on a request path, and the queue is how
    that is true rather than how it is described.** A geocode used to happen inside the save: an
    admin adding forty places on a Sunday made forty requests as fast as they could type, and
    waited on each. `AsyncQueue` is one worker for the whole box, because a rate limit is a promise
    this server makes as somebody else's *client* and two threads would be two clients ignoring one
    policy. One every 1.5 seconds -- slower than any of the three services allow, because with
    somewhere to wait the cost is a few minutes on a batch nobody is watching and the alternative
    risk is a block found out days later.
225. **A thousand waiting, and then it says no out loud.** An unbounded queue is a memory leak with
    a schedule. The refusal is recorded on the Async screen like any other outcome, so "why has
    that address not resolved" is answered on a page rather than in somebody's head.
232. **An address that could not be placed says *which* of the two failures it was.** "The service
    has never heard of this" and "the service did not answer" look identical on a row with no
    coordinates, and treating them as one thing is wrong in both directions at once: a typo is
    re-asked every minute forever, and an outage leaves every address indistinguishable from one
    nobody has got to yet, so nothing can report that anything is wrong. `Placement` is that
    distinction, shared by members and by the address book so the retry policy exists once.
233. **Not found is closed; unreachable is scheduled.** Nothing re-asks a not-found on its own,
    because the same question to the same service tomorrow gets the same answer and costs a slot to
    learn nothing. Unreachable is retried at 15 minutes, an hour, four hours, then daily -- and the
    ceiling is a day rather than "give up", so a service that comes back next week is picked up
    within a day of coming back. An unreachable never clears a point it already has: it is not a
    statement about the address.
234. **The service that answered is stored with the answer.** Changing `gps.service` is what
    re-opens every address the old one could not find, which is the usual reason somebody changes
    it. Without that column the switch fixes nothing and there is nothing on any screen to explain
    why.
235. **A row's retry counter counts episodes, not attempts.** The queue retries a failed job five
    times seconds apart, which is the answer to a service having a bad minute; the row's schedule is
    the answer to it having a bad afternoon. Incrementing per attempt would send an address to the
    day-long wait the first time somebody's DNS hiccuped, so `Geocodes` records the failure once per
    job and rethrows -- the queue still learns to slow down for everybody.

226. **"No answer" is not a failure, and only one of the two backs off.** A service saying it has
    never heard of an address is complete and correct; treating it as an error would put the whole
    queue into backoff because one member typed a street name wrong. `Geocoder.Unavailable` is that
    distinction made into a type -- it used to be swallowed into a null, which was right when a
    geocode happened inside a save and is wrong the moment there is a retry policy.
227. **Geocoding is on by default, which reverses invariant 172.** The cost argument does not apply
    to Nominatim -- free, no account, no key -- and the privacy argument is answered by the
    addresses being the point of the feature rather than a side effect of it. What survives is the
    enforcement: the User-Agent identifies the application unconditionally, `gps.contact` is nagged
    for on two screens, and a *written* `gps` block naming nominatim without one is still fatal at
    boot. A default that refuses to boot is not a default; a decision somebody typed still gets the
    strict answer.
228. **A member's address is a different record from their profile, and that is the security
    design.** `ProfileRecord` goes to the directory, to somebody's page, to the review screen, to an
    export and past a model. If the address were a field on it, keeping it private would mean
    remembering to omit one field at seven call sites and the eighth would ship. `Home` is read by
    its own method reading its own columns, and `PROFILE_COLUMNS` does not name them -- so a profile
    physically cannot carry it.
229. **What leaves an address is a distance in a bucket.** No names, no order, no map, no nearest
    member, and no way to ask for one person. An administrator's download of somebody else's data
    says the field was withheld rather than being quietly short, because a data export somebody is
    checking against the policy has to be honest about what it is not showing.
230. **Somebody who said nothing is not placed.** The opposite of invariant 198, and deliberately:
    there is a defensible assumption about when people are free and there is none about where they
    live. A town on a profile counts as a rough point and says it is rough; silence counts as
    nothing, and the chart prints how many of the community it is actually describing.
231. **The admin sidebar is one nav in two shapes, and it ships open.** On a phone the same list was
    a wrapped strip of thirty links above every page -- a screenful to scroll past before reading
    anything. It is now a `<details>` that the script *closes* when the screen is narrow, rather
    than one the script opens: a browser with no JavaScript gets the full list expanded, which is
    exactly what it had, instead of a menu button that does nothing and a section nobody can reach.

248. **A narrowed listing needs the same narrowing on the fetch beside it.** Four reads filtered
    their index and not their by-id twin, so anything hidden was one exact address away:
    `content_get` by uri, `event_context`'s guest list, the kinds of place, and the survey's draft
    questions. It is the oldest shape of this bug and it is invisible from the listing's own test,
    so `ToolSurfaceTests` checks each pair together.
249. **What is advertised and what is executable are checked against each other, for every tool.**
    Two hand-maintained lists in two files agree until somebody adds a tool. The failure that
    matters is not a tool wrongly hidden -- it is one advertised as absent and callable anyway. The
    test walks every declared tool both ways round, and a new tool with neither a permission nor a
    line on the deliberately-open list fails it.

262. **A structured argument has to arrive as structure, and the transport is where that was
    wrong.** `unwrap` handled null, boolean, number and array and then fell through to `asText()`,
    which for a container node is the empty string -- so every nested object any tool declared
    reached the surface as `""`. `place_save` advertised a `fields` object from the day the address
    book shipped and read it behind an `instanceof Map` that could never be true, which meant a
    model filling in a kind's own fields was answered with a success and wrote nothing. That is
    invariant 76's failure arriving through the plumbing instead of the handler, and it is worse
    there, because the handler's refusal was correct and unreachable. `ToolArgumentTests` checks
    every structured argument both ways: that a value lands, and that a bad one is still refused.
263. **A page's declared field values are given to its own template.** They used to reach a
    directory listing's entries and the feeds and stop -- so a template could declare `subtitle`,
    the editor could draw the box, the row could hold the value, and `{{subtitle}}` on the page
    rendered as nothing. The manual has promised that `{{headline}}` works since the feature
    shipped; it was the code that was wrong. Built-ins win the name clash (`putIfAbsent`), so a
    field somebody called `title` cannot shadow the page's own.
264. **Declaring a template's fields is absent-keeps, present-replaces.** Absent has to keep, or a
    model fixing a typo in a body strips every box off the page editor. Present has to replace
    rather than merge, because that is what the admin screen does with the same declarations, and a
    list whose meaning depended on which of two screens wrote it is the "two ways to describe a
    form" problem this project has already paid for once. Which makes `template_get` load-bearing:
    it answers with the declarations **in full**, because a read that is lossy under a write that is
    total is a read that deletes labels.
265. **Changing what a page is has its own tool, and it cannot write a body.** `content_meta` is
    `content_save` minus the one expensive thing to lose, and the guarantee is structural rather
    than a promise in a description -- the method does not read a body, so no phrasing of the
    arguments produces one. A model asked to file forty pages or fill in a subtitle should not be
    holding forty bodies it has to hand back unchanged; every one of those is a chance to return
    something subtly different, which the history would then record as an edit somebody made. The
    log line says which of the two happened, because "updated /about" reading the same for a
    retitle and a rewrite is exactly what somebody auditing an agent needs told apart.
267. **What moved to the database is decided by what a setting is about, not by how awkward it is
    to change.** Product and presentation are the community's: its name, its clock, which parts of
    the product exist, how long a conversation lives, what an invitation says. Anything that decides
    who gets in, what a credential is, what a program connecting here may do, or how many bytes a
    request may carry is the operator's and stays in a file -- reviewed by reading it, changed by
    somebody with access to the machine, and out of reach of a browser session that has been taken
    over. `admin_emails` is the sharpest case and it stays: invariant 66 says the escape hatch must
    not read the thing it exists to rescue you from.
268. **A setting's key is the path it had in the config file, and that is the mechanism.** A value
    is applied by writing it into a copy of the file's JSON and parsing the whole thing again, so
    the check that refuses a bad value at boot is the same check that refuses one typed into the
    admin section, in the same words. The alternative was a validator per setting -- thirty chances
    to disagree with the one that decides whether the server starts. It also means a settings row
    can only reach a key the catalogue knows, because `Setting` is the only thing that knows where a
    key goes.
269. **The file seeds; the database overrides; clearing reverts.** A row exists only where somebody
    decided something, so an upgrade is safe for every community that already has these keys in a
    file, and "has anybody actually chosen this" stays answerable. A blank value deletes the row
    rather than storing an empty string -- otherwise a community that emptied a box could never get
    the default back. **The rebuild always starts from the file**, never from the last rebuild:
    keeping the overridden JSON as the source made each edit layer on the last, and clearing then
    reverted to the previous edit instead of to what the operator wrote.
270. **A write rebuilds and swaps; a read is still a field access.** There are a couple of hundred
    call sites reading `config.board.expiryDays` and friends, most on the request path. Making them
    ask a table would have been the most-executed query in the server. Instead `DomainTree` holds a
    slot per domain and a settings write puts a whole new immutable `DomainConfig` in it, so a
    request sees either all of the old config or all of the new one -- which matters because these
    values are cross-checked against one another. The rebuild is triggered from the DAO rather than
    the handler, for the reason invariant 14 gives.
271. **A shared database is one set of settings, and one clock.** The same rule that makes it one
    account space. A change made on one of its domains re-applies to all of them, because leaving
    the others on the old answer until a restart would be the sort of split-brain nobody would think
    to look for. The clock is the one value something else holds a copy of, so `Accounts` is moved
    in the same breath rather than keeping the zone it booted with.
272. **No agent tool reaches any of it, and the proof is that there is no tool.** Not a tool that
    refuses -- that would appear in a listing, cost a model turns hunting for a phrasing, and leave
    the next person one parameter away from making it real. What a community *is* is a decision the
    people in it make, and the cheapest thing in the world for a model to get wrong at scale.

266. **Field values merge; an undeclared name is refused.** One blob holds the template's declared
    values *and* a listing's own knobs (`page_size`, `sort`, `place_kind`), and a model setting a
    subtitle is saying nothing about page size -- so replacing the blob would silently reset a
    listing somebody tuned. That is invariant 30 arriving in a second place. A name the template
    never declared is refused out loud rather than dropped, the same asymmetry places uses: a form
    posting an unknown key is noise from a screen that has moved on, but a model passing one has
    misunderstood something, and dropping it quietly reports success for a write that did not
    happen.

244. **The connection is a permission, not a rank.** `agent_connect` is granted in a role, so a
    community decides per person; it is not a baseline, because a connection is a standing
    credential held by somebody else's software that can act as that person for a month; and it
    implies `admin_enter` no more than the board permissions do, because what an agent can do is
    what its person can do. It is re-checked at consent, at redemption and **on every call**, so
    taking it away stops an agent at its next request rather than at the end of the month.
245. **A write is refused by name; a read is narrowed.** Refusing a member's assistant a listing
    would make the tool useless to the person it belongs to; answering in full would hand them a
    draft they cannot open in a browser. So `visibleContent`, `listEvents`, `listPlaces` and their
    single-item twins answer with what that person could see on the site -- the same asymmetry
    human-only has, where what somebody may not see is absent rather than forbidden.
246. **A tool that could only ever refuse is not offered.** `tools/list` is narrowed by the same map
    the surface enforces with, because invariant 149 applies to a model as much as to a person: a
    control that would refuse teaches whoever meets it that the software is broken, and a model
    meeting one spends its turns hunting for a phrasing that works. The listing is a courtesy and
    the surface is the boundary -- calling an unlisted tool is still refused by name.
247. **The briefing says who the connection is for.** A model told it can shape a site, while
    holding an ordinary member's connection, spends its first three turns being refused and its
    fourth apologising. It is told whether it acts for an admin or a member, and that it can never
    do anything that person could not.

236. **A vote lives inside the argument about it.** A poll belongs to a post, goes when the post
    goes, and posts its answer back as a comment -- which reaches everybody already watching for
    free. A decision with its reasoning removed is a number nobody can explain six months later, and
    the survey is already the place for asking the community things and keeping the answers.
237. **Days are approval-voted; places and choices are either-or.** A week has several evenings and
    somebody can be free on three of them, so forcing one pick throws away most of what they know --
    and a histogram is the shape that shows whether one evening is genuinely better or the group is
    split. Nobody thinks the event should happen in three places, so that half is one vote. A *down*
    only exists on a day, because there is no such thing as being against one option and not for
    another; on an either-or it is read as taking your vote back.
238. **A tie is reported, never broken, and neither is a negative winner.** Picking the earlier day
    or the lower id would be this software deciding what the community has not -- silently, at
    midnight, into everybody's calendar. Everything voted down more than up is the group saying none
    of these, and turning that into an evening would be insisting on one nobody wants. Both close,
    both say which half and why, and both are a thing somebody can fix in ten seconds.
239. **A removed option keeps its votes.** Deleting them would silently change what every other
    option is a share of, and the row is part of how the decision reads afterwards.
240. **Permission to make an event is checked when a scheduling vote is asked, not when it closes.**
    Finding out at midnight, after people had voted, that the answer cannot become anything wastes
    the group's attention and teaches them the feature does not work. Anybody may ask the same
    question as a plain choice; what needs the permission is having the answer put itself in the
    calendar.
241. **Reading, writing and voting on the board are things being approved is enough for.** They are
    real permissions -- `Access.can` is the one question the board asks too -- and every approved
    member holds them, because the alternative is a board only administrators can read on the
    morning after an upgrade. They are not offered in the role editor, since a box that cannot
    change the answer teaches whoever ticks it that the screen does not work, and they deliberately
    do not imply `admin_enter`. `board_moderate` is not among them: it acts on somebody else's
    words.
242. **Every board and poll tool asks what the acting person may do.** Invariant 26 says an agent is
    a session with a bit set and can never do anything the person could not; that was a claim about
    sessions and is now enforced about tools. A refusal names the permission, because a model's
    useful next move is to tell somebody rather than to try a different phrasing.
243. **`alsoEachPass` adds; it used to set.** The second caller replaced the first silently -- the
    machine sampler was registered, then the geocoding sweep was, and from that commit the Machine
    graph quietly stopped being sampled. Nothing failed and nothing logged. A setter named
    `alsoEachPass` is a trap, and each passenger now runs in its own try so one throwing does not
    stop the rest.

250. **A definition is durable; an occasion of it is disposable.** "Bulgarian split squat" is a
    fact about the world -- how it is performed, what to watch for, what it is measured in.
    Tuesday's three sets are a fact about Tuesday. One row for both meant rewriting the
    instructions on every occasion or having none, and it made "how has this gone over six months"
    a question about strings. An entry carries its own `def_id` and `project_id` so a history
    outlives the list it was on.
251. **Seven measures, four columns.** Weight, reps, seconds, distance, each nullable and each
    meaningless unless the measure asks for it. Seven tables would be seven history queries and
    seven charts, the seventh of which would eventually disagree with the first. Weight is *signed*
    for weighted bodyweight because assistance and added load are one axis -- somebody's first
    unassisted rep is the moment that number crosses zero, and two measures would put that moment
    in the gap between two charts.
252. **What "more" means is asked of the measure, never assumed.** Tonnage is right for a barbell
    and nonsense for a 5k; time is right for a plank and backwards for a run. `Measure.effort` is
    per measure, and a chart that silently rewarded slower running would be worse than no chart.
253. **Three feedback numbers, and a missing one is not a middling one.** How hard, how long, how
    much good it did. They come apart on purpose: the thing that is exhausting and useless is
    exactly the thing worth finding, and neither weight nor duration can say it. Nothing is stored
    for "did not say", and no screen or tool pre-selects a value -- a form that started in the
    middle would quietly turn every unanswered set into an average one.
254. **Ownership is in the WHERE clause.** A project with an owner is that person's; one with none
    is the community's. A training log is the most private thing this server holds, and "the
    handler remembers to check" is how that eventually stops being true. **An administrator is not
    an exception on the member-facing path** -- `/tasks` answers 404 to them like anybody else, and
    the admin screen shows that somebody keeps a project and never what is in it. A community that
    genuinely needs to see one can ask for the export its owner can download.
255. **A repeating task is not finished, it is due again.** Ticking one moves its date forward
    rather than closing it, because a routine is a thing that comes back and a list somebody has to
    rewrite every Sunday is a list they stop rewriting in March. Reaching a board's last column is
    what finished means there.
256. **Hiding a finished item is a question about today, not about the data.** `hide_done_hours`
    decides what is in front of somebody on a phone; nothing is ever deleted by it, because the
    entries are the whole point and a chart of six months needs six months of them.
257. **A shared definition is adopted by pointing at it, never by copying it.** Improving the
    community's form notes has to improve everybody's copy, or a library is a place people take one
    bad copy from. What an adopter gets of their own is a row for their target and their own notes,
    which is where "the same movement, but I do six" goes without forking the whole thing.

258. **Rest is a property of the movement, so it lives on the definition.** A heavy squat wants
    three minutes in every routine it ever appears in. Somebody who wants it different for one
    block takes a copy of the definition, which is what taking a copy is for -- and a copy that has
    not set its own inherits the original's, like the instructions.
259. **The rest timer is rendered by the server and only ticked by the script.** The page says "1m
    20s since your last set, rest 3m" before anything has loaded; `/~rest.js` makes the number
    move. A gym is the worst network anybody uses regularly, and a timer that exists only once
    JavaScript has arrived is one missing at the moment it is wanted. The count works from a
    rendered-at stamp rather than from its own interval, so a tab left in a pocket comes back true.
260. **A superset rests after the round; a circuit rests between.** That is the entire difference
    between the two grouping modes, and getting it backwards turns a time-saving device into one
    that takes longer -- so the item screen refuses to offer the timer between the halves of a
    `related` group and says why. A group is a shared name on a column, not a table: joining is
    writing it down, and the last member leaving takes nothing with it.
261. **A one-rep max is offered only where a bar is loaded, and only up to twelve reps.** Every
    formula is a curve fitted to a study and every one drifts badly as the reps climb; at twenty
    the answer is about how long somebody can suffer rather than what they can lift. It returns
    nothing rather than a confident figure, because a number on a screen is a number somebody tries
    to beat. A plank has none, a 5k has none, and an assisted rep has none -- there is nothing to
    extrapolate towards from a lift that was made easier. Rounded to the half kilo and labelled a
    direction rather than a target, because the input does not carry the precision that printing
    102.83kg would claim.

163. **A signal is never a verdict.** Votes and flags change nothing: nothing is hidden, sorted,
    scored down or removed by either. A community where votes bury things has handed its judgement
    to whoever votes most, and a flag that auto-hides is a heckler's veto with a nice icon. What the
    numbers do is tell a person where to look. There is deliberately **no MCP tool that acts on a
    flag** -- a model can read the queue, summarise it and recommend, and a person decides.
164. **One person, one opinion, changeable.** Pressing up twice takes it back; pressing down after
    up replaces it. A flag is separate and can sit alongside a vote, because disliking something and
    thinking somebody should read it are different statements. Every row is attributed, because four
    flags is a different fact depending on whether it is four people or one person with three tabs.
165. **Three questions at a time, and never more.** A survey is a wall of boxes and a wall of boxes
    is a thing people close. `SurveyForm.CHUNK` is the whole rule; a community's list can be forty
    questions long as long as nobody is ever shown forty, and answering three brings the next three
    rather than a finish line.
166. **The welcome comes back.** A survey only asked on the first day measures what a community
    wanted to know the day somebody joined and then goes stale. A returning member with unanswered
    questions gets the same three-at-a-time screen, framed as what it is, once per sign-in and
    skippable in one press -- which is the difference between a nudge and a nag.
167. **Before, on the day, and afterwards are three conversations.** A comment's phase is computed
    from the event's dates rather than stored, because an event that moves changes what "before"
    meant and a stored phase would then be wrong on every row with nothing to notice it.

## Not verified

Different from a defect: nobody has proved these wrong, and nobody has proved them right either.
Each one is a thing to be careful about rather than a thing to fix.

- **The release path has never published anything.** Every refusal, the stamped build, the
  `--version` self-check and the checksums are exercised; the tag push and the two publish paths are
  not, because that needs a token and a real tag. Cut `0.0.1-rc1` first so a mistake is cheap.
- **SPF, DKIM and DMARC have never seen real mail.** Tested hard against the RFCs with a fake
  resolver, and canonicalization bugs live in real Gmail signatures and real mailing lists.
  `enforce-dmarc` is off by default because of this; the checks still run and still stamp
  `Authentication-Results`.
- **The organizational-domain rule is a guess.** DMARC relaxed alignment strictly needs the Public
  Suffix List, which is a weekly download this project will not take on for one check. It errs
  toward not aligning, so a wrong guess marks a message unaligned rather than passing a forgery.
- **The certificate path has never met a real authority.** `Acme` is a seam and the tests use a fake
  CA; `--setup-certs` exists because the first real attempt is the expensive one.
- **Nothing has been raced on purpose.** No test runs two writers at the same row. The caches are
  concurrent maps and the counters are atomic, and that is an argument rather than evidence.
- **Nothing has been run against a real dataset.** Every query is written for a few hundred rows and
  reviewed on that assumption. Several would be wrong at a hundred thousand, which is a design
  decision rather than a defect -- see the scale target at the top of this file.
- **The vendored browser libraries are not in git** (`src/main/resources/3rd/`), because a 2.8MB
  minified bundle in history is a repository nobody wants to clone. `just release` runs
  `just third-party` first, so a released jar is fine; a fresh clone that runs `just package` gets a
  jar whose rich editor silently falls back to a textarea. Their **licences are in git**, because the
  obligation to carry them exists whether or not somebody has run the fetch yet.

## What's next, and what's undecided

Next, from [MISSION.md](MISSION.md)'s road: ICS/iMIP so an event can be accepted from a calendar
client, the AI social leader made concrete, a phone-first pass, and the friction
list. Then uploaded images and real two-factor enrolment.

Open questions — **ask before deciding these**:

- **Config inheritance.** Today the deepest applicable config wins outright; ancestors in the tree
  do not merge into descendants. Merging may be what's wanted once configs grow real content — the
  tree makes it easy to add, since the descent already visits every ancestor in order.
- **Where static content lives.** `static-root` resolves under the domains directory and defaults to
  a directory named for the domain, so `domains/example.org.cfg` pairs with `domains/example.org/`.
  That keeps one path in the CLI, but mixes content into a directory otherwise full of configs. A
  separate content directory under the root is the obvious alternative. Nothing serves static files
  yet, so this is cheap to change.
- **Session rotation.** A token is issued once and lives until revoked. Rotating on privilege change
  (or on every N requests) is the obvious hardening and is not done.
- **Cookie scope across shared databases.** `junior.example.org` and `example.org` share an account
  space, but the cookie is set per host, so signing in at one does not yet carry to the other in a
  browser. A parent-domain cookie would fix it and is a real security decision, not a flag flip.
- **Roles beyond admin.** The table takes any string; nothing but `admin` means anything yet. What a
  member role would gate is undecided.
- **Approval notifications.** Nobody is told when they are approved, and no admin is told somebody is
  waiting. `Mailer` and `Notifier` both exist now, so this is a flow to design rather than plumbing
  to build.
- **Two-factor beyond email.** `password_and_code` works end to end -- password, then a mailed code,
  and no session until both land (`TwoFactorTests`). The `totp_secret` / `totp_enabled` columns
  exist but authenticator-app enrolment, recovery codes, and what happens when somebody loses their
  phone are all unset.
- **Refresh tokens.** Only `authorization_code` is implemented. Agent tokens follow the domain's
  session policy, which by default never expires, so nothing needs refreshing -- but an operator who
  sets `session-lifetime-seconds` or `mcp.token-lifetime-seconds` makes their connector re-authorize
  by hand when it lapses. Whether that wants a real refresh grant is unsettled.
- **SSE.** The transport is request/response JSON only. Every tool here answers in one shot, so
  nothing needs a stream yet; a long-running tool would change that.
- **HSTS.** Still not sent, now that TLS works. A header telling browsers to refuse plain HTTP for a
  year is not something to switch on without an operator deciding to, and getting it wrong locks
  people out of a domain for the length of the max-age.
- **An external event bus.** `EventBus` is an interface and `LocalEventBus` is the only
  implementation. Nothing has been decided about what the remote one looks like, how a process
  catches up after a disconnect, or whether events need to be ordered across processes.
- **Uploaded images.** Inline SVG covers the icon set, but a member cannot attach a photo. Whether
  binary blobs belong in the content table, and what that does to the database file, is unsettled.
- **Analytics persistence.** The access log is memory only, so a restart loses it. Whether it should
  become a table, and what that does to write volume, is unsettled.
- **Where generated content is cached.** Beside the static content, or a separate cache root?
- **The dashboard measures the wrong thing.** MISSION.md commits to one headline number -- hours
  spent in each other's company -- and the admin overview currently shows cache hit rates and event
  counts. Closing that is real work: attendance has to be recorded after an event, not just
  promised before it.
- **Splitting a community.** The mission says a group that outgrows this should split, and that
  helping it split well is a feature. Nothing does that; what carries across (members, history,
  events) and what does not is undecided.
- **A board that follows nothing.** `Inbox.Kind.post` is declared and never emitted -- there is no
  notion of following the whole board, only of watching a thread you joined. Whether a new thread
  should ever notify anybody is unsettled.
- `pom.xml` originally targeted Java 24; it's on 21 because that's the installed JDK. Bump when the
  toolchain does.
