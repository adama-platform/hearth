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

**Done/not-done is a lie about most ranch work.** A fence repair has steps, and which step it is at
is the useful fact — so a task can walk a named process, and a state that is not in that process is
refused rather than stored where no screen will list it.

**Habits graduate.** That is the difference between a habit tracker and a checklist: a habit exists
to stop needing to exist. Graduating takes it off the sheet and keeps every mark, because deleting
it throws away the only evidence the effort was worth anything. Marks are one row per day, not a
counter — the question is *which days*, and a counter gives the number and never the shape.

Overdue work sits in today's list rather than an overdue section, because an overdue section is
where things go to be ignored.

### Getting people together

A vote is a **pool of options that evolves**, not a ballot with a fixed slate. An agent proposes
Thursday, another proposes the Thursday after, a third says both are bad and offers a Sunday.

```
vote_open · vote_propose · vote_cast · vote_narrow · when_free
```

Ballots are `yes` / `fine` / `no` / `blocked`. **`blocked` is a veto, not a low score** — one removes
an option however many yes votes it has, because a date somebody cannot attend is worse than no
date. Narrowing keeps the best few and records what was dropped and why. Deciding is deliberately
not an agent's job, and the tool says so in as many words.

**Availability without handing over your calendar.** Publish an ICS link if you trust an agent with
it, or write down a rough weekly shape if you do not. Every answer says *which of the two it is* and
what to do with it — an agent handed a rough shape and left to assume it is a calendar will
confidently propose a night you have had booked for a month. This server never fetches your ICS; it
hands over the link and nothing else.

## What else is in the jar

| | |
| --- | --- |
| **Accounts** | Sign in by emailed code or password. Every account waits for a human. Roles, permissions, bans. |
| **A website** | Pages and templates in a database, versioned as whole documents, with directory indexes and a JSON bundle that merges back. |
| **Dynamic pages** | A page body that is a program, run in V8 per request with a fresh isolate and a one-second ceiling. |
| **Tables** | Declare fields and indexes; a page gets read-only functions for them. Mutations are how anything writes. |
| **A model endpoint** | MCP with OAuth. Everything above is reachable by an agent acting as the person who connected it. |
| **Files, mail, push, TLS** | Uploads, SES out and SMTP in with SPF/DKIM/DMARC, an installable app, and certificates it renews itself. |

Nothing on disk but the databases, the certificates and what you upload. No third-party request of
any kind.

## Multi-user, on purpose

Not to host a community — to invite four friends into the parts that need more than one person. A
friend needs the `agent_connect` permission before their agent can do anything here, and that stays
a permission rather than a membership baseline: an agent acting as somebody is the sharpest thing
this hands out.

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
java -jar hearth.jar --root /var/hearth --setup-email example.org     # real email
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

Your own page at `/self` has **Today** (the sheet), **Profile**, **Connections** (your Hevy key and
your availability) and **Your data**.

A section you may not open is absent from the sidebar and answers 404 rather than 403 — a 403
confirms what is behind the door.

## Where it is today

**1150-odd tests**, mostly not unit tests: the testkit boots the whole server on an ephemeral port
with real databases and drives it over HTTP. `just validate` is the gate — it builds clean, runs
everything, packages the jar, then makes real HTTP requests against that jar running as a server.

**What has never been verified** is written down as such in [CLAUDE.md](CLAUDE.md#not-verified) —
including that nothing here has ever talked to Hevy's real API.

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
