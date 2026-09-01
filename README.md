# Hearth

**A multi-user platform for a small number of people to coordinate using AI.**

One Java jar. A door somebody decides who comes through, a website an AI can be handed the keys to,
and pages whose body is a program — so an idea can be tried on real people the same afternoon it is
had.

```bash
java -jar hearth.jar --root /var/hearth
```

That is the entire operation. No database to install, no daemon to supervise, no cluster, no
company. Backup is copying a directory.

## Social presence, redefined

A group's presence online used to mean a site somebody maintained, or a feed somebody else ranked.
Hearth is neither. It is three things that only matter together:

**A door.** Accounts, and a human deciding who comes through. Everything else assumes it: a
directed AI acting for a member is only safe because membership means something.

**An AI-directed content management system.** The site is a database, not a folder of files, and
every part of it — pages, templates, the fields a template declares, the navigation, and the dynamic
pages themselves — is reachable by a model over MCP. `site_spec` hands it the whole surface,
generated from what exists right now rather than written down, so the functions it is told about are
the functions there are. The point is not that a model can type faster. It is that *the whole site is
addressable*, so "make a page for Thursday and link it from the front" is one instruction rather
than a project. An assistant acts **as the person who connected it** and can do nothing that person
could not.

**An app platform for validating ideas quickly.** A page's body can be JavaScript, run on every
request. Two functions: `render(text)` builds the document, `meta(key, value)` sets the title and
whatever the template asked for.

```js
meta('title', 'Who is bringing what');
['bread', 'soup', 'the chairs'].forEach(function (job) { render('<li>' + job + '</li>'); });
```

**And somewhere to keep things.** Under Content › Tables you declare a table — fields, and which of
them are indexed — and it becomes a small set of functions the page can call:

```js
signups_get_id(id)            // one row, or null
signups_list_job('bread')     // every row with that indexed value
signups_page(idAfter, 20)     // the next 20 after an id
signups_all()                 // all of it
query('page', 0)              // ?page=2 arrives as the number 2
```

A page names a *function*, never a column — there is no filter argument and no fragment of SQL, so
every query is one somebody wrote down. Those tables live in a **file of their own** beside the
accounts database, because their shape is whatever was typed this afternoon and the system schema's
is not.

**A page reads; a mutation writes.** Writing lives at a different address behind a different method:
declare one under Content › Mutations and it answers POST, for an approved member with a valid form
token, running a program that can merge:

```js
var result = signups_merge_by_id(form('id'), { job: form('job') });
// { success: true }  or  { success: false, reasons: [...] }
meta('redirect', '/thanks');
```

A merge changes only the keys it names, reports *every* reason rather than the first, and writes
nothing unless all of them pass. Splitting it this way means a crawler, a preloader or a link
checker cannot change anything by reading the site.

**Every row has a `hidden` flag** an admin can set and no program can see — it is filtered out of
every read and the flag is not in the rows a page gets, so there is nothing to test against. It is
how something goes into the system before it is ready to be seen, and it is not a delete: the row
keeps its id. There is a table editor under Tables for browsing, filtering and editing rows by
hand.

That is the shortest path from *an idea* to *a thing the group can look at*. No build, no deploy, no
second service — write it in the admin section and it is live at a URL. Read [what this cannot do
yet](#the-app-platform-is-a-first-cut) before believing too much of it.

## What is in the jar

| | |
| --- | --- |
| **Accounts** | Sign in by emailed code, by password, or both. Every account waits for a human. Roles, permissions, bans. |
| **A website** | Pages and templates in a database, every save versioned as a whole document, directory indexes so a template behaves like a blog, and the whole site as one JSON file that merges back. |
| **Dynamic pages** | A body that is a program, run in V8 on every request, with a fresh isolate and a one-second ceiling. |
| **Tables** | Declare fields and indexes; a page gets read-only functions for them. Cached per question, invalidated per write, with a row editor and a `hidden` flag. |
| **Mutations** | Addresses that answer POST and run a program. The only thing that writes, behind an approved member and a form token. |
| **A model endpoint** | MCP with OAuth: content and template tools, off by default, held to what the person who connected it may do. |
| **Files** | Photographs, video, the PDF of the menu. The extension decides what a thing is; the browser's claim is thrown away. |
| **Email, both ways** | SES out in the community's colours. SMTP in, with SPF, DKIM and DMARC checked and stamped. |
| **An app** | Installable from the browser, with push notifications and a self-test that proves one arrives. |
| **TLS** | Certificates it obtains and renews itself over HTTP-01, HTTP/2 by ALPN. No DNS records to add. |
| **Terms and a privacy policy** | Published from day one, with export and erasure the policy can honestly describe. |

Nothing on disk but the database, the certificates and what people upload. Every byte of every page
comes from the machine you run it on — no third-party request of any kind.

## Small on purpose

**100 to 1,000 people.** An architecture, not a limit waiting to be lifted. One H2 file, caches in
memory, a ring buffer for the log, no sharding, no queue, no second process.

This is not modesty, it is the thing that makes the AI half safe. A model that can rewrite the site
is only a reasonable idea when one person can read everything it did — the AI log holds the last
1,000 actions with arguments and results, and that is a number a human can actually get to the
bottom of.

**Never** rank a feed by engagement. **Never** move money. **Never** track anybody — two cookies,
both needed to keep you signed in, which is why there is no consent banner. **Never** require a
company to exist for a group to keep existing. **Never** grow past what one person can check.

## Getting started

```bash
just validate     # clean build, full suite, package, smoke the running jar, check tests and docs
just run          # serve the checked-in ./site root on 8080, narrating every decision
just              # list the rest
```

Then open <http://localhost:8080/register>, type any email address, and the code prints in the
terminal. Paste it back and the account exists — waiting for an admin, unless the address is in that
domain's `admin_emails`. The shipped config lists `owner@example.com`, which gets straight in.

Setting up a real one is a series of walkthroughs, each of which writes one file and tells you what
it wrote:

```bash
java -jar hearth.jar --root /var/hearth --setup                       # ports and TLS
java -jar hearth.jar --root /var/hearth --domain-setup example.org    # a community
java -jar hearth.jar --root /var/hearth --setup-certs                 # certificates
java -jar hearth.jar --root /var/hearth --setup-email example.org     # real email
```

Then `--install <dir>` writes a systemd unit and a start script into a directory you already own,
and stops — it needs no root and starts nothing. `--verbose` narrates every routing decision.

One box hosts several groups: `<root>/domains` is a flat directory of `<domain>.cfg` files, and a
domain is served if and only if one exists for it.

## The admin section

`/admin` is a top bar, a nested sidebar, and a main area.

- **Overview** — what there is
- **People** — approve, promote, turn off, reject; with **Bans** and **Roles**
- **Content** — pages, with **Templates**, **Tables**, **Mutations**, **Directories**,
  **Navigation**, **Files**, **Unused** files and **Import & export** (bundles)
- **Settings** — what this community is, with **Setup**
- **Customization** (look) — **Appearance**, **Legal**, **Messages**
- **System** — **Machine**, **Settings**, **Events**, **Analytics**, **Caching**, **AI**, **Logs**,
  **Clean up**

A section you may not open is absent from the sidebar and answers 404 rather than 403 — a 403
confirms what is behind the door. A control that would refuse is not drawn at all.

Every page is timed. The content listing has a **p99 column** — the slowest of the last 50 builds —
for every kind, because 40ms means nothing until the markdown page beside it reads 0.3ms.

## The app platform is a first cut

Worth saying plainly, because the pitch above is the easy part.

A dynamic page today gets `render`, `meta`, `query`, `csrf` and its community's table functions, and
**nothing else**: no network, no timers, no way back into this server, and no way to write. Not
because something refuses — because nothing was ever bound. Every execution gets a fresh V8 isolate, so nothing one page defines is
visible to the next; it runs on its own thread pool, created on first use; and it is stopped after a
second, so `while(true){}` is a page that says so rather than a server that stops answering. **No
agent may write one**, in either direction — an agent acts as somebody who probably holds
`content_write` and has no idea they lent anybody an interpreter.

So it validates *ideas*, not products. A page can read what the group has collected, answer
differently for `?page=2`, and post a form to a mutation that changes a row. What it still cannot do
is know **who is asking** — neither a page nor a mutation is told which member is on the other end,
so anything per-person is out of reach. That is the next question, and it is the one that turns this
from a CMS into a place that runs code. The bar has not moved: **can one person enumerate how it
fails.**

## Where it is today

Working, serving real traffic over HTTP/2 and TLS with certificates it gets for itself.

**1000-odd tests**, mostly not unit tests: the testkit boots the whole server on an ephemeral port
with real databases and drives it over HTTP. `just validate` is the gate rather than a convenience —
it builds clean, runs everything, packages the jar, and then makes real HTTP requests against that
jar running as a server. Nothing here is claimed to work on a green test suite alone.

**What has never been verified** is written down as such in [CLAUDE.md](CLAUDE.md#not-verified) —
the release path, the mail validators against real mail, anything under concurrency. That is a
different thing from a defect and gets a different kind of attention.

**It used to be much bigger.** A discussion board, a calendar with RSVPs, an address book, an
availability grid, a members directory, an invitation funnel, projects, a training log, a live
channel and a JSON API — about 26,000 lines, all of it working. It was removed because surface you
cannot validate is surface you cannot safely run, and because none of it was the point.

## The documents

Three, and `just docs` and `just suite` are part of `just validate`: they fail the build when a
link, a flag, a recipe, a schema version, an invariant number or a test count has drifted, or when a
test class is sitting there with nothing in it.

- **[MISSION.md](MISSION.md)** — why this exists and what it refuses to become.
- **[CLAUDE.md](CLAUDE.md)** — every invariant, why it exists, and what broke when it did not hold.
  It is long because the decisions are the product.
- **README.md** — this.

## Contributing

**A finding gets reproduced from the outside first, fixed with a test that fails before and passes
after, and then written into the comment above the code that fixes it.** There is no list of
known-broken things, because a standing list is a second place to look that says what the code
already says.

New checks belong in the justfile — if a check is not reachable from `just validate`, it is not part
of the definition of "working".

## License

MIT — see [LICENSE](LICENSE). Third-party components and their licences are in
[THIRD-PARTY.md](THIRD-PARTY.md) and served in full from a running server at `/3rd/licenses`,
because vendoring is redistribution.

## Lineage

The HTTP layer takes its shape from [Adama](https://github.com/adama-platform/adama)'s `web` module,
and the boot output and single-jar habit from goatbot.
