# Hearth

**One person's infrastructure, on one machine, with AI as the interface.**

A gym log, a ranch to-do list, habits that graduate, and a way to get five friends to agree on a
Thursday. One Java jar, one directory, no company.

```bash
java -jar hearth.jar --root /var/hearth
```

That is the entire operation. No database to install, no daemon to supervise. Backup is copying a
directory.

This is a **Ranch OS**. It is built for the person who wrote it, and it is open source because the
whole of it fits in a context window — so somebody else with a ranch and a barbell can ask an AI to
make it theirs, rather than asking it to understand a platform.

## The three things it does

### The gym

Save a Hevy API key on your own page, and an agent connected to your account can read your workouts
and build routines *as you*.

```
gym_workouts          what was actually lifted, newest first
gym_exercises         every exercise template, with ids
gym_exercise_create   invent one
gym_routine_create    build a routine from exercises and sets
```

`gym_exercise_create` is the point. Hevy's built-in list covers barbells and almost none of the
mobility work worth programming — 90/90 hip switches, couch stretch holds, carries with an odd
implement. **A routine you cannot express is a routine you do not do**, so the tool tells the model
to make the exercise rather than approximate with a lift that happens to be listed. Enums are checked
here rather than by Hevy: a refusal naming the field costs a model one turn, a 400 costs it several.

> Hevy's API is theirs, and they say of it: *"we make no guarantees that we won't completely change
> the structure or abandon the project entirely so use it at your own risk."* Hevy Pro only.

### The ranch

Tasks, habits, and a daily sheet.

```
day_sheet        what has to happen today · what is coming · what to pull from
task_add         a task, or a habit with a cadence
process_save     a state machine: surveyed -> materials -> built -> checked
habit_mark       kept today
habit_graduate   it did its job
```

A **challenge** is a habit with an `ends_on`: "thirty days of mobility" is a different thing from "do
mobility forever", and the difference is that it finishes. It graduates itself the day after its
last, rather than sitting on the sheet being missed.

**Done/not-done is a lie about most ranch work.** A fence repair has steps, and which step it is at
is the useful fact — so a task can walk a named process, and a state that is not in that process is
refused rather than stored where no screen will list it.

**Habits graduate.** That is the difference between a habit tracker and a checklist: a habit exists
to stop needing to exist. Graduating takes it off the sheet and keeps every mark, because deleting
it throws away the only evidence the effort was worth anything. Marks are one row per day, not a
counter — the question is *which days*, and a counter gives the number and never the shape.

Overdue work sits in today's list rather than an overdue section, because an overdue section is
where things go to be ignored.

**A daily email at six** with what is on and what is coming — and **none at all when there is nothing
on the docket**, because a daily message that arrives empty gets filtered within a fortnight, and
then the one that mattered goes to the same folder.

### Getting people together

A vote is a **pool of options that evolves**, not a ballot with a fixed slate. An agent proposes
Thursday, another proposes the Thursday after, a third says both are bad and offers a Sunday.

```
vote_open · vote_propose · vote_cast · vote_narrow · when_free
```

Ballots are `yes` / `fine` / `no` / `blocked`. **How a vote decides is a property of the group:**
`consensus` (the default) lets one `blocked` remove an option, which is right for five friends where
the point is that everybody comes. `majority` maximises who can make it, which is right for twenty,
where somebody is always away and consensus converges on nothing.

**A vote can name a host, and their block is final in either mode** — a majority can outvote anybody
about whether an evening is convenient, and cannot vote somebody into having twelve people in their
kitchen.

Narrowing keeps the best few and records what was dropped and why. Deciding is deliberately not an
agent's job, and the tool says so in as many words.

**Then the invite goes out, host first.** `vote_ask_host` emails one person — nobody else is told —
because "will you host on the 9th" can be answered no and "we're at Ana's on the 9th" cannot. Only
once they accept will `vote_invite` mail everybody, and a no puts the vote back to narrowed rather
than ending it.

**Availability without handing over your calendar.** Publish an ICS link if you trust this with it,
or write down a rough weekly shape if you do not. Every answer says *which of the two it is* and what
to do with it — an agent handed a rough shape and left to assume it is a calendar will confidently
propose a night you have had booked for a month.

A shared ICS is fetched, parsed and cached for an hour. **Only the busy windows are kept, never the
summaries** — what a scheduler needs is when you are not free, and what an ICS carries is who you
were meeting.

**A repeating event is a maybe, not a wall.** A standing Tuesday call is real and is also exactly
the kind of thing somebody moves for a friend's fortieth. Treating repeats as walls answers "no
evening works" for any group of five — right, and useless. The rule the whole calendar path follows
is that *an uncertain conflict is never reported as a certain one*.

**You also say how movable you are**, which free/busy cannot: `can_host`, and one of `mostly_free` /
`it_depends` / `tightly_booked`. That is what an agent is seeded with. One friend hosts and has a
full calendar; another is free most evenings and immovable on three — the same first proposal is
right for one and wrong for the other.

### Weighed, not filtered

**There is always an imperfect night.** So `vote_get` doesn't hand back a list of evenings nobody
objects to — it hands back every option with what it *costs* and who it costs it to:

```
Thursday 9th — 4 can come, 1 would have to move something they could probably move.
  Ana   can_come                     their calendar is clear
  Zed   would_have_to_move_something a repeating commitment — often movable
  Bo    cannot_come                  a one-off in their calendar
```

Every number that made the ranking comes back beside it, because a ranking nobody can interrogate
gets one wrong answer before it's never trusted again. **A ballot always beats a calendar** — in
both directions. Someone saying yes on an evening their calendar objects to has already moved it;
someone blocking a clear-looking evening knows something the file doesn't. And **silence counts for
nothing rather than against**.

## Your mail, through this machine

**The problem this solves is a household, not an inbox.** Moving your own email to something you
built on a Sunday is a decision you get to make; moving your wife's is not. So: point MX at this
machine, name the addresses that exist, and write a rule that sends hers straight on to the Google
Workspace she has no intention of leaving. She sees no difference. You get everything in front of it.

**Two actions, and the rules are consulted in order with the first match winning.** `forward` sends a
message on to another domain's own mail exchangers; `drop` accepts it and lets it go. An address
nothing claims is refused at RCPT, before the message body arrives — so a mistyped address comes back
to whoever typed it instead of vanishing, and a directory harvester costs one line per guess.

**Nothing is bounced, and that is the design rather than a gap.** The message is delivered onward
*before* this server answers the sending machine, and the far end's verdict is what comes back: a 451
becomes a 451, a 550 becomes a 550. That removes the queue, the spool and the bounce generator in one
stroke — the sender's own server writes the failure report, to the address it really sent from. A
message is either delivered or never accepted; there is no third state where this box is the only
thing holding it.

### Why forwarded mail usually lands in spam, and what is done about it

Forwarding breaks the two things a receiver uses to decide whether a message is real, and every piece
below is one repair:

| | |
| --- | --- |
| **The message is not touched** | No footer, no subject tag, no re-encoding. The sender's DKIM signature covers the body; one changed byte destroys it, and a receiver cannot tell a helpful forwarder from somebody rewriting the message. Headers are prepended and nothing else happens. |
| **SRS** | SPF asks whether *this* machine may send for the domain in the return path, and the answer for a forwarded message is always no. So the return path — which nobody reads — is rewritten to an address here, keyed with a MAC so nothing but a real bounce reverses. The `From:` header a person reads is untouched. |
| **ARC** | A sealed record of what SPF, DKIM and DMARC said *on arrival here*, which is what Gmail honours from a forwarder. A chain is only ever started, never extended: adding `cv=pass` to somebody else's would be vouching for arithmetic nobody did. |
| **DKIM** | This server signs as the domain the message arrived for, with one key published on each domain. |
| **TLS** | STARTTLS with the certificate verified against the exchanger's name, required by default. The log records `verified` or `encrypted` rather than recording "TLS" for both. |
| **Loops** | A message that has already been through thirty relays is stopped permanently, not retried for four days. |

**`/admin/mail/setup` writes the rest of it down for you** — the four DNS records with your actual
key in them, the one Google Workspace setting that matters (the inbound gateway) and the two obvious
ones that are traps, and the `dig` commands for everything this server cannot see from where it is
standing. It is generated from what is running rather than written down, so it cannot go stale.
`--setup-mail` prints the same thing at a terminal and generates the two secrets, because a default
SRS secret is a MAC anybody can compute and a default DKIM key is a private key in a git repository.

**The mail log is the answer to "I never got your email."** Every message, the three verdicts as they
were on arrival, which rule matched, where it went and the far end's reply word for word. Metadata
and a short preview — a forwarder that kept copies would be a mail store nobody agreed to run.

## What else is in the jar

| | |
| --- | --- |
| **Accounts** | Sign in by emailed code or password. Every account waits for a human. Roles, permissions, bans. |
| **A website** | Pages and templates in a database, versioned as whole documents, with directory indexes and a JSON bundle that merges back. |
| **Dynamic pages** | A page body that is a program, run in V8 per request with a fresh isolate and a one-second ceiling. |
| **Tables** | Declare fields and indexes; a page gets read-only functions for them. Mutations are how anything writes. |
| **A model endpoint** | MCP with OAuth. Everything above is reachable by an agent acting as the person who connected it. |
| **Files, mail, push, TLS** | Uploads, SES out and SMTP in with SPF/DKIM/DMARC, an installable app, and certificates it renews itself. |
| **Mail routing** | Addresses, ordered rules, SRS, ARC and a signed relay — see above. |

Nothing on disk but the databases, the certificates and what you upload. No third-party request of
any kind.

## Multi-user, on purpose

Not to host a community — to invite four friends into the parts that need more than one person.
**Every approved member can connect an agent**, because agents are how everybody who is not the owner
uses this at all; approval is the boundary, and an agent can still only do what its person can do.
Disabling an account stops their agent at its next request.

There are eleven permissions, down from nineteen: writing pages, templates, navigation and files is
one job at this scale, and so are the settings, the colours and the legal pages. Two stayed split
because the blast radius genuinely differs — dropping a table destroys collected data, and granting
roles is the sideways path to becoming an administrator.

Your gym, your tasks and your keys are yours. There is no argument anywhere for *whose* — every call
uses the id of whoever connected the agent, so no phrasing of any request reads somebody else's
list.

## Small on purpose

**Me, and a handful of people I know.** One H2 file per domain, caches in memory, no queue, no
second process. That is not modesty — it is what makes handing an agent the keys reasonable. The AI
log keeps the last 1,000 actions with arguments and results, and that is a number a person can
actually get to the bottom of.

**Never** rank a feed. **Never** move money. **Never** track anybody. **Never** require a company to
exist. **Never** grow past what one person can check.

## Getting started

```bash
just validate     # clean build, full suite, package, smoke the running jar, check tests and docs
just run          # serve the checked-in ./site root on 8080, narrating every decision
just              # list the rest
```

Then open <http://localhost:8080/register>, type any email, and the code prints in the terminal.

Setting up a real one is a series of walkthroughs, each writing one file and telling you what it
wrote:

```bash
java -jar hearth.jar --root /var/hearth --setup                       # ports and TLS
java -jar hearth.jar --root /var/hearth --domain-setup example.org    # a domain
java -jar hearth.jar --root /var/hearth --setup-certs                 # certificates
java -jar hearth.jar --root /var/hearth --setup-email example.org     # real email out
java -jar hearth.jar --root /var/hearth --setup-mail mail.example.org # mail in, and forwarding
```

Then `--install <dir>` writes a systemd unit and a start script into a directory you already own,
and stops — it needs no root and starts nothing.

There is no version number and no release recipe. `just package` produces the jar; copy it to the
box. It reports `MAIN`, and the commit is the identity.

## The admin section

`/admin` — **Overview**, **People** (with **Bans**, **Roles**), **Content** (with **Templates**,
**Tables**, **Mutations**, **Directories**, **Navigation**, **Files**, **Unused** files, and bundles
for **Import & export**), **Settings** (with **Setup**), **Customization** (look) holding
**Appearance**, **Legal** and **Messages**, and **System** — **Machine**, **Settings**, **Events**,
**Analytics**, **Caching**, **AI**, **Logs**, **Clean up**.

**Mail** is its own top-level section — the rules, plus **Addresses**, the **Mail log**, and **DNS &
Workspace**. It takes its own permission: being trusted with the website is not being trusted with
the post, and that screen can redirect somebody's mail and shows who has been writing to whom.

Your own page at `/self` has **Today** (the sheet), **Profile**, **Connections** (your Hevy key and
your availability) and **Your data**.

A section you may not open is absent from the sidebar and answers 404 rather than 403 — a 403
confirms what is behind the door.

## Where it is today

**1275-odd tests**, mostly not unit tests: the testkit boots the whole server on an ephemeral port
with real databases and drives it over HTTP. `just validate` is the gate — it builds clean, runs
everything, packages the jar, then makes real HTTP requests against that jar running as a server.

**What has never been verified** is written down as such in [CLAUDE.md](CLAUDE.md#not-verified) —
including that nothing here has ever talked to Hevy's real API, and that no forwarded message has
ever reached a real Google Workspace.

## The documents

- **[MISSION.md](MISSION.md)** — why this exists and what it refuses to become.
- **[CLAUDE.md](CLAUDE.md)** — every invariant, why it exists, and what broke when it did not hold.
- **README.md** — this.

`just docs` and `just suite` are part of `just validate`: they fail the build when a link, a flag, a
recipe, a schema version, an invariant number or a test count has drifted, or when a test class is
sitting there with nothing in it.

## Contributing

**A finding gets reproduced from the outside first, fixed with a test that fails before and passes
after, and then written into the comment above the code that fixes it.** New checks belong in the
justfile — if a check is not reachable from `just validate`, it is not part of the definition of
"working".

## License

MIT — see [LICENSE](LICENSE). Third-party components are listed in [THIRD-PARTY.md](THIRD-PARTY.md)
and served from a running server at `/3rd/licenses`.

## Lineage

The HTTP layer takes its shape from [Adama](https://github.com/adama-platform/adama)'s `web` module,
and the boot output and single-jar habit from goatbot.
