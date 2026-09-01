# Hearth

A single Java jar that gives a small community a website of its own, behind a door somebody decides
who comes through.

```bash
java -jar hearth.jar --root /var/hearth
```

That is the entire operation. No database to install, no daemon to supervise, no migration tool, no
cluster, no company. Backup is copying a directory.

A hearth is the thing a small group gathers around, and it is one fire rather than a facility —
which is the design in two words.

## What is in the jar

| | |
| --- | --- |
| **Accounts** | Passwordless sign-in by emailed code, or a password, or both. Every account waits for a human to approve it. Roles, permissions, bans. |
| **A website** | Pages and templates the community writes itself, every save versioned as a whole document, directory indexes so a template can behave like a blog, and the whole site as one JSON file that merges back. |
| **Files** | Photographs, video, a recording, the PDF of the menu — with the extension deciding what a thing is and the browser's claim thrown away. |
| **Email out** | Amazon SES, one signed POST, no AWS SDK, in the community's own colours. |
| **Email in** | SMTP with SPF, DKIM and DMARC checked and stamped. Nothing acts on a message today; it is received, authenticated and printed. |
| **An app** | Installable from the browser, with push notifications and a self-test that proves one arrives. |
| **TLS** | Certificates it obtains and renews by itself over HTTP-01, HTTP/2 by ALPN. No DNS records to add. |
| **A model endpoint** | MCP, off by default, offering the content and template tools — an assistant writes the site as the person who authorized it and can do nothing they could not. |
| **Terms and a privacy policy** | Published from the first day, with data export and erasure that the policy can honestly describe. |

And a shape it holds to: nothing on disk but the database, the certificates and what people upload;
every byte of every page from the machine the community runs on; no third-party request of any kind.

## It used to be much bigger

This project had a discussion board with polls and threaded comments, a calendar with RSVPs and real
emailed invitations, an address book, an availability grid folded out of members' own calendars, a
members directory, an invitation funnel with conversion tracking, projects and a training log, a
live channel, and a JSON API. About 26,000 lines of it.

All of it worked. It was removed anyway, and the reason is the only one that matters for software
one person operates:

> **Surface you cannot validate is surface you cannot safely run.**

[MISSION.md](MISSION.md) is the long version — what the project was for, what it still believes, and
what it no longer claims to do. This README is what the software actually does today.

## Built small, deliberately

**100 to 1,000 people.** An architecture, not a limit waiting to be lifted. Dunbar's 150 is the
ceiling on stable relationships; functioning groups average around 50 active members. A community of
20,000 is an audience with a comment section.

Everything follows from that number. One H2 file. Caches in memory. A ring buffer for the event log.
No sharding, no queue, no second process. **A ten-year-old machine should serve this community
faster than a SaaS platform serves it today** — not because we are clever, but because they are
doing a hundred things for a hundred thousand groups and we are doing one thing for one.

Multi-tenant from the start, because one box should host the handful of communities you care about.
A domain is served if and only if there is a config file for it.

## What it will never do

- **Never rank a feed by engagement.**
- **Never move money.** Not tickets, dues or tips — that deletes PCI scope, chargebacks, refunds and
  the whole class of feature that turns an organizer into a merchant.
- **Never track anybody.** Two cookies, both needed to keep you signed in. No analytics, no
  advertising, no third-party requests of any kind. That is why there is no cookie banner: consent
  walls exist for cookies that need consent, and there are none here.
- **Never require a company to exist** for a community to keep existing.
- **Never grow past what one person can check.**

### What it costs

A single jar with no cluster has a single point of failure, and one process means one blast radius.
That is the deal a group of 200 people should want: operational simplicity is worth more to them
than nines they cannot measure.

## Where it is today

Working, and serving real traffic over HTTP/2 and TLS with certificates it gets for itself.

**1000-odd tests**, and mostly not unit tests: the testkit boots the whole server on an ephemeral
port with real databases and drives it over HTTP — including, for the account flows, a client that
keeps cookies and does what the page's own script does.

**What has never been *verified*** is written down as such in
[CLAUDE.md](CLAUDE.md#not-verified) — the release path, the mail validators against real mail,
anything under concurrency, and a handful of tests that time out under load and pass alone. That is
a different thing from a defect and gets a different kind of attention.

Everything goes through [`just`](https://github.com/casey/just):

```bash
just validate     # clean build, full suite, package, smoke the running jar, check the tests and docs
just run          # serve the checked-in ./site root on 8080, narrating every decision
just              # list the rest
```

`just validate` is the gate rather than a convenience: it builds clean, runs everything, packages
the jar, and then makes real HTTP requests against that jar running as a server. Nothing here is
claimed to work on the strength of a green test suite alone.

Then open <http://localhost:8080/register>, type any email address, and the code prints in the
terminal you started the server from. Paste it back and the account exists — but it is waiting for
an admin, unless the address is in that domain's `admin_emails`. The shipped configs list
`owner@example.com`, so that address gets straight in and can approve everybody else at `/admin`.

Setting up a real one is three commands, each of which writes one file and tells you what it wrote:

```bash
java -jar hearth.jar --root /var/hearth --setup                       # ports and TLS
java -jar hearth.jar --root /var/hearth --domain-setup example.org    # a community
java -jar hearth.jar --root /var/hearth --setup-certs                 # certificates
java -jar hearth.jar --root /var/hearth --setup-email example.org     # real email
```

Each is a walkthrough rather than a wizard: it asks, it writes a file you could have written by hand,
and it prints what it wrote. Then `--install <dir>` writes a systemd unit and a start script into a
directory you already own, and stops — it needs no root and starts nothing.

```
curl -si -H 'Host: localhost'          localhost:8080/  # 200  Localhost Community
curl -si -H 'Host: example.org'        localhost:8080/  # 200  Example Community
curl -si -H 'Host: www.example.org'    localhost:8080/  # 308  -> example.org, one community one address
curl -si -H 'Host: api.localhost'      localhost:8080/  # 404  wildcard is off there
curl -si -H 'Host: nope.org'           localhost:8080/  # 404  no config, so not served
curl -si localhost:8080/wp-login.php                    # 410  scanner shield
curl -si localhost:8080/no-such-page                    # 404  and the page carries the way back
```

### Accounts

Every domain gets an embedded H2 database under `<root>/dbs`, one file named for the domain. A domain
can point `use_database_domain` at another and share it, which means sharing accounts *and* sessions.

The schema lives in `Schema.java` and upgrades itself. Add a column, restart, and the upgrader diffs
what is on disk against what the code declares and inserts anything missing **in the declared
position**. It will not drop, rename, or retype a column — which is what makes a reduction like the
one above safe for a database that already exists: the tables for removed features are simply left
alone.

The cost of that promise is that an upgraded database keeps carrying the discussion board and the
calendar, holding rows nothing will ever read. **`/admin/system/cleanup`** is where somebody finally
throws them away: it lists every table the code no longer declares with the number of rows in it,
and drops one at a time, by name, after confirming. Nothing is hard-coded — a leftover is any table
the schema does not name, which stays correct for removals that have not happened yet. It is the
only screen in the admin section that destroys data and the only one that asks for `everything`, and
boot is still never allowed to drop anything: an operator who upgrades, hits a regression and rolls
the jar back must still have their data.

Every account starts unapproved: registering proves you can read an address and creates the row, but
an admin has to say yes before you can sign in. `admin_emails` in a domain's config names the
addresses that are admins outright — without that, requiring approval would mean nobody can ever
sign in on a fresh install.

Signing in is passwordless by default: an emailed code, and no password to leak, reuse or reset.
Sessions never expire by default — logging your neighbours out every week to defend against nothing
is how you get them to stop coming — and a session cap with a grace window keeps that bounded:

```json
"login_security": {
  "max-active-sessions": 4,
  "max-active-sessions-grace-seconds": 1800
}
```

Session tokens are stored as SHA-256, never in the clear, so a stolen database file is a list of
hashes rather than a list of logins. The lookup is a write-through in-memory cache: a hit is a hash
map probe with no I/O, a revocation hits the disk before it hits memory.

With no email provider configured, the dev box prints the mail to your terminal, spaced out for copy
and paste:

```
  +-- email ------------------------------------------------
  | 19:50:45  register
  | to:   owner@example.com
  | from: Example Community <no-reply@example.org>
  |
  | Confirm your address to finish signing up.
  |
  |     076368
  +---------------------------------------------------------
```

### Content, and the event bus that keeps it fresh

Pages live in a `content` table and are written at `/admin/content`: markdown wrapped in a template,
an HTML fragment wrapped in a template, a whole document served as-is, or **a program**. Markdown has
everything switched on — tables, task lists, footnotes, autolinks, heading anchors. Templates live in
their own table and are mustache: `{{{body}}}` is the page, `{{title}}` and `{{uri}}` are its own.

Pages are cached for an hour by default. They also update the instant you save, because every write
announces itself:

```
#3 example.org templates/1 update
#2 example.org content/1   insert
#1 example.org templates/1 insert
```

Every mutation emits an event naming the domain, the table and the primary key. Caches listen and
drop exactly what changed — and a template change cascades, dropping every page that named it. The
TTL is a backstop for an event that never arrives, not the mechanism.

**Templates can declare fields.** A name, a type, a label and whether it is required, stored as a
JSON blob on the template row; the page editor renders a box per field and the values live on the
content row. That is why a template author can ask for a headline without anybody touching the
schema — and `{{headline}}` is then available to the template.

**A template can publish a directory index**, which is what lets the content table behave like a
blog without anybody building one. Page one is always the bare path; ordered by when a page was
created, never when it was edited, because a blog that reshuffles because somebody fixed a typo is
one nobody can find anything in.

**Every save is a version, and a version is the whole page** — body, title, template, folder, field
values, flags. A snapshot every ten versions and a line patch in between. **Restore** puts a version
back as a *new* version, so the edit being undone stays in the history. That is `git revert`, not
`git reset --hard`, and it is the reason to keep a history rather than a backup.

**One kind is a program.** A *Dynamic JavaScript* page has a body that is run rather than rendered,
on every request, in V8. It gets two functions and nothing else: `render(text)` appends to the
document, and `meta(key, value)` sets the title and anything the template declared — a value set
there wins, because it ran a moment ago and the boxes above were typed once.

```js
meta('title', 'Today');
for (var i = 0; i < 3; i++) { render('<li>' + i + '</li>'); }
```

Every execution gets a **fresh isolate**, so nothing one page defines is visible to the next or to
the next request — that costs about 0.8ms and is what makes the feature explainable. It runs on a
**dedicated thread pool** created on first use, so a server with no dynamic pages pays nothing for
this at all. It gets **one second**, and V8 is interrupted if it overruns: `while(true){}` in a page
body is stopped and says so on the page rather than taking a thread with it. There is no network, no
storage, no timers and no way back into this server — not because something refuses, but because
nothing was ever bound. **No agent can write one**, in either direction: it cannot create a program
and it cannot convert a document into one.

These pages are the one thing not cached, because a page that can answer differently every time has
no business being kept under its address.

**Every page is timed, and the listing has a p99 column.** The last 50 builds of every page — markdown,
HTML, whole-document and program alike — are kept in memory, and the content listing prints the
slowest of them. That is the number worth having: what the unlucky reader waits for, not the average.
The column is only readable because the static pages have numbers too — 40ms means nothing until the
markdown page beside it is 0.3ms. A dash means nothing has asked for that page since the last
restart.

**Everything comes out as one JSON file.** Every page and template carries a merge key stamped once
and never rewritten, so a bundle downloaded today and brought back in March is a *merge* rather than
a pile of duplicates. That is what makes a git repository of markdown a way to write this site.

### Files people upload

Photographs, video, a recording, the PDF of the menu. They live as files under the root —
`attachments/jpg/42/1342.blob`, bucketed so no directory ever holds a million things — because a
photograph in a database column is read into memory to be served and copied by every backup.

**What the browser says a file is counts for nothing.** The extension decides, checked against a
closed table of things this server knows how to serve safely, and `text/html` is not on it for any
extension or configuration. Neither is `svg`: it is a document that can carry script and arrives
looking like a picture.

Private by default, and private means an approved member; public means anybody. Every one is
`Cache-Control: private` — browsers may keep a copy, shared caches never may, because these are
frequently photographs of somebody's children.

### Two places configuration lives

Some of what a community runs on is a fact about the machine and some is a fact about the community.

**The operator's** stays in a config file, read once before the socket opens: sign-in policy,
credentials, what a program connecting here may do, what a request may carry, routing, and the list
of addresses that are administrators by fiat. That last one is the sharpest case — an escape hatch
you can edit from inside the thing it rescues you from is not one.

**The community's** lives in its own database and is edited at `/admin/configuration`: what it is
called, its clock, miles or kilometres, and which parts of the product it has at all. No restart,
and a form with the meaning of each setting written beside it.

The trick that makes it safe is that a setting's key is the path it already had in the config file,
so applying one means writing it into a copy of that file's JSON and parsing the whole thing again —
**the value is checked by exactly the parser that decides whether the server boots**. A file value
is the starting point and a database row overrides it, so an existing install keeps working and
clearing a box puts the file's answer back.

`/admin/configuration/setup` is a short walkthrough for the settings where the default is a guess
about a community this software has never met. **No model can touch any of it** — there is no agent
tool for the settings, not even one that refuses.

### An app, without an app store

`/~app` is a progressive web app: a shell holding the site in an iframe, a per-domain manifest, and
a service worker at the root. Members install it from the browser and get an icon, a standalone
window, and notifications — with no developer account and no second build. The site itself is
unchanged and still works with no JavaScript.

**Push notifications belong to a session, not an account.** Each signed-in browser is one session,
one subscription and **its own VAPID keypair** — so signing out does not merely stop us sending, it
destroys the only key that push service will accept for that browser.

A push carries a title, a line and a path. **Never the contents.** The encryption is RFC 8291,
hand-rolled and checked against the RFC's own published test vector rather than merely round-tripped.

**Nothing generates a notification today.** The board and the calendar were what did. What still
works is subscribing, the keypair, the worker, and `/~app/help` — which sends a real notification to
this browser now and reports two facts separately: that the push service accepted it, and that this
device showed it. Every push problem lives in the gap between those.

### Receiving mail, and knowing who sent it

Hearth can receive email as well as send it — off by default, because port 25 needs root and an
unconfigured listener on it is found by scanners within the hour.

**It never relays.** A message is accepted only for a domain with a config file here, matched
exactly, and refused before a body arrives.

Every message is checked with **SPF, DKIM and DMARC**, and the findings are stamped onto it as an
`Authentication-Results` header. The three matter together: SPF authenticates an envelope nobody
reads and breaks on forwarding, DKIM authenticates whichever domain chose to sign and survives it,
and **DMARC is the alignment** — requiring the authenticated domain to line up with the `From:` a
person actually sees. Only a message failing a published `p=reject` is refused.

**And then it is printed.** The thing that used to act on inbound mail was the calendar, turning an
emailed Accept into an RSVP. With the calendar gone there is no consumer, so the receiver logs what
arrived. The listener, the routing and all three checks are kept because they are the expensive part
to get right.

### Connecting a model

`mcp.enabled` on a domain turns on an [MCP](https://modelcontextprotocol.io) endpoint at `/mcp`, and
a model with a connector can then read, search, write and reorganize the site and manage templates —
all of it held to what the person holding the connection may do.

**Off by default.** Every other default here is tuned for a high-trust community. This one is not,
because what it hands out is the ability to rewrite the site.

Getting a token requires a person. A connector registers itself, **somebody looks at a screen that
says what it will be able to do**, and only then does a code come back — redeemed with PKCE, single
use, bound to the client and redirect it was issued for. Where codes may be sent is an explicit
prefix list with no wildcards, and a prefix naming only a host is normalized to end at the authority
boundary, because `startsWith` has no idea where a hostname ends.

The token that comes out is **a session belonging to the person who approved it, with a robot bit
set**. That is the whole identity model: an agent can never do anything the person could not, and it
is never mistaken for that person afterwards.

Two things bound every connection. It cannot touch accounts, approvals, emails or bans at all. And
any page ticked **human only** is invisible to it — absent from listings, searches and fetches, with
writes refused out loud. That asymmetry is deliberate: a locked page that merely looked empty to a
write would get quietly overwritten by a model asked to "add an about page".

Everything it does lands in an **AI log**: the last 1,000 actions, arguments and results kept as
JSON, under two names — the person who authorized the connection and the connector that acted.
Refusals are logged as loudly as successes.

### The admin section

`/admin` (configurable) is a top bar, a nested sidebar, and a main area.

- **Overview** — what there is
- **People** — approve, promote, turn off, or reject; with **Bans** and **Roles** under it
- **Content** — pages, with **Templates**, **Directories**, **Navigation**, **Files**, **Unused
  files** and **Import & export** under it
- **Settings** — what this community is, with **Setup** under it
- **Customization** — **Appearance**, **Legal** and **Messages**
- **System** — **Machine**, **Settings**, **Events**, **Analytics**, **Caching**, **AI**, **Log**,
  and **Clean up**

Every entry there is drawn only for somebody allowed to open it. A section you may not enter is
absent from the sidebar and answers 404 rather than 403 — a 403 confirms what is behind the door.

Four rules hold it together, each of which came from something going wrong.

**Every sub-view has its own URL.** A section is a real server load, and the part of it that
refreshes in place is a *panel* at its own path — `/admin/people/list`. The section page embeds a
panel by calling the same method the panel's URL calls, so the two cannot drift.

**Identity in the path, filters in the query, changes in a POST.** `/admin/content/edit/41` names a
thing; `/admin/content/list?q=about` is a view of a list; `POST /admin/content` changes something and
answers with a redirect. Confirmations ride on the session and are read once.

**A listing is not a form.** Creating or editing anything is a page transition to its own URL.

**A section permission is permission to see a screen, never to press what is on it.** Every button
posts to the section's own path, so an action whose handler checks nothing inherits the mildest
permission that opens the screen. That has been a real hole twice — `people_read` reaching
`grant_admin`, and `content_read` reaching delete and restore — so every action names the permission
it needs, and anything unlisted needs `everything`.

About seventy lines of vanilla JavaScript in the shell drive every panel and every filter box. There
is no library.

### The register page is built in the browser

Fetch `/register` and there is no form in it. The page carries a JSON blob and a script that
assembles the form, with field names minted per page load:

```json
"f": { "email": "xa632d35958f", "code": "xa500e796095", "csrf": "xaaa183a37c2", ... }
```

Load it again and the same field is called `ddecd5859b84`. A submission also carries a value the
script computes from a server nonce, a hidden field the script always leaves empty, and counts of
the mouse, keyboard, touch, pointer, scroll and focus events the page saw — zero of everything is
refused. Each form is good for one submission. The inline script is allowed by CSP nonce, not
`'unsafe-inline'`.

None of that is a security boundary, and it would be a mistake to treat it as one: anything shipped
to a browser can be replayed by something that reads it. It makes the cheap attack stop working, and
approval is what actually decides who gets in.

### Certificates

Turn on `enable-certs` in `config.cfg` and Hearth gets and renews its own certificates, one per
domain, under `<root>/certs`.

Verification is over plain HTTP and entirely internal: the authority asks this server for a file at
`/.well-known/acme-challenge/…` and this server answers it. No DNS records to add, no bucket to
upload to — which also means **no wildcards**, since those need a DNS challenge and therefore
credentials for whoever runs your DNS. Name the subdomains you want in `subdomains` instead.

`--setup-certs` is a conversation rather than a flag because the failure it prevents is expensive:
authorities rate limit failures hard, so restarting a server whose DNS is not ready can lock you out
for an afternoon.

After that, a few seconds *after the socket is open* — validation is the authority calling back, so
it cannot happen sooner — it orders what is missing, then renews anything within 20 days of expiry.
A domain that will not validate gets a clear complaint and a retry; the server serves plain HTTP
throughout and never fails to start over a certificate.

`"enable-https": true` then serves them on **80 and 443**, negotiating **HTTP/2** during the
handshake. Each domain's certificate is chosen by the hostname asked for, so one process hosts
several communities on one address. Port 80 stays a real web server rather than becoming a redirect
— it is what answers the challenge, and turning it into a redirect would quietly break renewal three
months later.

### Virtual hosting

Flat on disk, a tree in memory. The `<root>/domains` directory holds one JSON file per virtual host,
named for the domain it configures:

```
/var/hearth/domains/
  localhost.cfg
  example.org.cfg
  junior.example.org.cfg
```

Flat because `ls domains` should be the list of everything this server serves. The filename *is* the
domain; there is no `domain` key inside the file, because two sources of truth for one fact is how
they drift apart.

At boot those names get built into a tree of DNS labels rooted at the top level domain. A request
descends as far as its labels allow and takes the deepest config that applies.
`junior.example.org` lands on its own. `www.example.org` has no config, so it lands on `example.org`
— that one sets `wildcard: true`. `api.localhost` resolves to nothing. Nodes with no config of their
own are junctions: they exist because something lives under them, and they serve nothing.

Between those two there is `subdomains: ["www", "blog"]`, which is usually what you actually want.
Each named subdomain answers with the same config — the same community, the same database, the same
accounts — and, unlike a wildcard, it gets a certificate.

Whatever the route in, a community has **one** address: any name that is not the config's own domain
answers `308` to the same path on the domain itself. The certificate challenge is answered before
any of that, because an authority validating `www` fetches its token from `www`.

Everything is scanned and loaded **before** the socket opens, and the resulting tree is immutable.
Config problems — bad JSON, an unknown key, a filename that isn't a valid domain, a symlink — stop
the server from starting instead of being logged and shrugged at.

`--verbose` narrates all of it:

```
... GET / host=a.b.example.org from /127.0.0.1:41288
      descend org -> junction, no config here
      descend example.org -> config (wildcard) example.org.cfg
      descend b.example.org -> no such branch; stopping
      most specific match: example.org
      serving example.org (Example Community) -> 200
```

## The documents

There are three, and `just docs` is part of `just validate` — it fails the build when a link, a
flag, a recipe, a schema version or a quoted test count has drifted. `just suite` sits beside it and
asks the other mechanical question: did every test actually run, or is a class sitting there with
nothing in it?

- **[MISSION.md](MISSION.md)** — why this exists, what it used to be, and why it is smaller now.
- **[CLAUDE.md](CLAUDE.md)** — every invariant, why it exists, and what broke when it did not hold.
  It is long because the decisions are the product.
- **README.md** — this.

There used to be a manual and an API contract. The manual described the same screens as this file
and was a second place to go stale; the API went with the reduction.

## Contributing

The rule that matters is short: **a finding gets reproduced from the outside first, fixed with a
test that fails before and passes after, and then written into the comment above the code that fixes
it.** There is no list of known-broken things, because a standing list is a second place to look
that says what the code already says.

Run `just validate` before claiming anything works. New checks belong in the justfile — if a check
is not reachable from `just validate`, it is not part of the definition of "working".

## License

MIT — see [LICENSE](LICENSE). Third-party components and their licences are listed in
[THIRD-PARTY.md](THIRD-PARTY.md) and served in full from a running server at `/3rd/licenses`,
because vendoring is redistribution and the obligation belongs to whoever ships the jar.

## Lineage

The HTTP layer takes its shape from [Adama](https://github.com/adama-platform/adama)'s `web`
module — the same pipeline layout, the same request shield, the same boot-then-serve ordering — and
the boot output and single-jar habit from goatbot.
