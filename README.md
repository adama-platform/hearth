# Hearth

**Half of American adults report considerable loneliness.** The Surgeon General puts the mortality
effect of social disconnection alongside smoking fifteen cigarettes a day.

Meanwhile the software we all use got extremely good at holding us still.

Hearth is a single jar that runs a small community — a supper club, a support group, a group of
friends who want to see each other more than twice a year — and its whole job is to get those
people into the same room.

```bash
java -jar hearth.jar --root /var/hearth
```

That is the entire operation. No database to install, no daemon to supervise, no migration tool, no
cluster, no company. Backup is copying a directory.

A hearth is the thing a small group gathers around, and it is one fire rather than a facility --
which is the design in two words.

## What is in the jar

All of it built, and covered by 1737 tests that boot the real server and talk to it over a socket.

| | |
| --- | --- |
| **Accounts** | Passwordless sign-in by emailed code. Every account waits for a human to approve it. Roles, permissions, bans. |
| **A website** | Pages and templates a community writes itself, full version history, a suggested-edit queue, and a bundle you can keep in git. |
| **A calendar** | RSVPs, waitlists that count seats rather than answers, and real invitations that draw accept and decline buttons in Gmail, Outlook and Apple Mail — with the answers coming back by email and becoming the guest list. |
| **A board** | Threaded discussion, comments on events and places too, and votes that settle a day and a place and then become the event. |
| **When can everybody come** | A weekly grid folded out of members' own calendars. Counts, never names. |
| **An app** | Installable from the browser, with push notifications. No app store, no second build. |
| **Files** | Photographs, video, a recording, the PDF of the menu. |
| **Email both ways** | SES on the way out in the community's own colours; SPF, DKIM and DMARC on the way in. |
| **TLS** | Certificates it obtains and renews by itself, over HTTP/2. No DNS records to add. |
| **An address book** | Places with whatever fields the community invents, geocoded off the request path. |
| **A door for programs** | A JSON API, and an MCP endpoint an assistant connects to — always as its own person, never as more than they may do. |
| **Terms and a privacy policy** | Published from the first day, and the code does what they say. |

And a shape it holds to: nothing on disk but the database, the certificates and what people upload;
every byte of every page from the machine the community runs on; no third-party request of any kind.

## Big tech broke us. Small tech and AI can fix us.

Big tech did not set out to make anybody lonely. It optimized for attention, and attention comes out
of the same hours that used to be spent with people. What scaled was what could scale, and
connection does not scale — it is made of specific people in specific rooms at specific times. So
the third places thinned out: the pub quiz, the church hall, the club with a mailing list.

Two jobs died with them.

**The organizer** — who picks a date, books the table, sends the message, chases the stragglers,
greets the newcomer, and does it again next month when the thanks have worn off. Usually one tired
volunteer. Most groups do not die of conflict; they die because nobody sent the message.

**The moderator** — and this half is uglier and less often said out loud. The research on volunteer
moderators finds PTSD, anxiety and depression, secondary trauma from repeated exposure to the worst
things people write, and burnout as the main reason they quit. Their distress is comparable to
crisis-line volunteers. We asked unpaid people to absorb the ugliest thing on the internet as a
hobby, and were surprised when small communities kept dying.

Both jobs were unpaid. One of them was unbearable. Now:

**Small tech.** Software cheap enough to run for fifty people does not need fifty thousand to pay
for itself. No growth imperative means no ranked feed, no advertiser, no engagement metric, no
reason to keep anyone on the site. Changing the economics of a community changes its character.

**AI that takes both jobs.** The organizing nobody volunteers for can happen at 2am for nothing. And
the frightening 3am post can be read *first* by something that cannot be traumatized — which
summarizes it, says how urgent it is, and hands a human a decision instead of an ambush. The human
still decides. They just no longer meet the worst of it cold.

To be exact, because the honest version is strong enough:

> **AI does not fix loneliness. People do.** What it can do is take the two jobs that made small
> communities unsustainable, so the people who would have run one are free to be in it instead.

## How we know it is working

Not signups. Not daily actives. Not posts or time on site — all of those go up when a community gets
worse.

**Hours spent in each other's company.** Jeffrey Hall's research puts casual friendship at about 50
hours of shared time, friendship at 90, close friendship past 200 — and hours spent *working*
together barely count.

Run that arithmetic and it reorganizes the product. A monthly dinner of three hours is 36 hours a
year: five years to turn strangers into close friends. The same group meeting weekly gets there
inside a year.

> **Frequency beats production value.** Six small gatherings beat one good one. A standing Tuesday
> beats a quarterly event with a speaker.

So Hearth makes the low-effort recurring thing the path of least resistance, rather than the
impressive thing that exhausts its organizer by March.

## Built small, deliberately

**100 to 1,000 people.** An architecture, not a limit waiting to be lifted. Dunbar's 150 is the
ceiling on stable relationships; functioning groups average around 50 active members; forums start
breaking down around 80 active contributors. A community of 20,000 is an audience with a comment
section.

Everything follows from that number. One H2 file. Caches in memory. A ring buffer for the event log.
No sharding, no queue, no second process. **A ten-year-old machine should serve this community
faster than a SaaS platform serves it today** — not because we are clever, but because they are
doing a hundred things for a hundred thousand groups and we are doing one thing for one.

Nothing on disk but the database. Pages, templates and icons all live in it, icons are inline SVG,
and a page costs one request — which matters because the people we build for are frequently on bad
rural wifi, an old phone, or in a waiting room.

Multi-tenant from the start, because one box should host the handful of communities you care about.
A domain is served if and only if there is a config file for it.

## What it will never do

- **Never rank a feed by engagement.**
- **Never move money.** Not tickets, dues or tips — that deletes PCI scope, chargebacks, refunds and
  the whole class of feature that turns an organizer into a merchant. Pay the restaurant. Venmo the
  host. Hearth counts heads, not dollars.
- **Never sell position in a community's own queue.** Meetup+ subscribers jump the waitlist, so when
  a place opens at *your* event the platform moves whoever paid *the platform* first. Hearth's
  waitlist is longest-wait-that-fits, and nothing changes that order.
- **Never track anybody.** Two cookies, both needed to keep you signed in. No analytics, no
  advertising, no third-party requests of any kind — every byte of every page comes from the machine
  the community runs on. That is why there is no cookie banner: consent walls exist for cookies that
  need consent, and there are none here.
- **Never require a company to exist** for a community to keep existing.
- **Never be so good at being a website** that it becomes a substitute for the room.

**Non-goals**, so they stay non-goals: not a platform, not a Discord replacement, not something that
scales to 50,000 members. When a community outgrows this, that is a good outcome for the community,
and it should leave — or split, which is what healthy groups this size do anyway.

### What it costs

A single jar with no cluster has a single point of failure, and one process means one blast radius.
That is the deal a group of 200 people should want: operational simplicity is worth more to them
than nines they cannot measure. The other cost is that everything in the jar is my problem — every
policy, every session decision, every default. Which is why they get written down as they are made.

[MISSION.md](MISSION.md) is the long version: the research behind all of the above, what Hearth
takes and refuses from Mighty Networks, Meetup and Eventbrite, the three real communities it has to
work for, and the leash the AI runs on.

## Where it is today

Working, and serving real traffic over HTTP/2 and TLS with certificates it gets for itself. The
table above is what runs; the rest of this document is how each part of it decides things, because
the decisions are the product.

Three things are worth saying about the state of it rather than the shape.

**1737 tests**, and they are mostly not unit tests. The testkit boots the whole server on an
ephemeral port with real databases and drives it over HTTP — including, for the account flows, a
client that keeps cookies and does what the page's own script does. What is asserted is what an
operator would get.

**A security review of the whole tree, and all sixteen findings closed.** Every one reproduced from
the outside first, fixed with a test that fails without the fix, and explained in the comment above
the code that does the work rather than in a list somewhere.

**What has never been *verified* is written down as such**, in
[CLAUDE.md](CLAUDE.md#not-verified) — the release path, the mail validators against real mail,
anything under concurrency. That is a different thing from a defect and it gets a different kind of
attention.

What is not built yet is the interesting half — see [the road](#the-road).

Everything goes through [`just`](https://github.com/casey/just):

```bash
just validate     # clean build, full test suite, package, then smoke test the running jar
just run          # serve the checked-in ./site root on 8080, narrating every decision
just              # list the rest
```

`just validate` is the gate rather than a convenience: it builds clean, runs everything, packages
the jar, and then makes real HTTP requests against that jar running as a server. Nothing here is
claimed to work on the strength of a green test suite alone.

Then open <http://localhost:8080/register>, type any email address, and the code prints in the
terminal you started the server from. Paste it back and the account exists — but it is waiting for an
admin, unless the address is in that domain's `admin_emails`. The shipped configs list
`owner@example.com`, so that address gets straight in and can approve everybody else at
`/admin`.

`just package` drops a single `hearth.jar` at the repo root. That's the deliverable.

Setting up a real one is four commands, each of which writes one file and tells you what it wrote:

```bash
java -jar hearth.jar --root /var/hearth --setup                       # ports and TLS
java -jar hearth.jar --root /var/hearth --domain-setup example.org    # a community
java -jar hearth.jar --root /var/hearth --setup-certs                 # certificates
java -jar hearth.jar --root /var/hearth --setup-email example.org     # real email
```

Each of those is a walkthrough rather than a wizard: it asks, it writes a file you could have
written by hand, and it prints what it wrote. Anything else replaces understanding with a wizard,
and the first time it is wrong there is nowhere to go.

Then `--install <dir>` writes a systemd unit and a start script into a directory you already own,
and stops. It needs no root and starts nothing; the half that does need root is written out as
`install.sh` for you to read before you run it.

The checked-in `site/` root serves three communities, which is what `just run` is for:

```
curl -si -H 'Host: localhost'         localhost:8080/  # 200  Localhost Community
curl -si -H 'Host: example.org'       localhost:8080/  # 200  Example Community
curl -si -H 'Host: junior.example.org' localhost:8080/ # 200  Example Community Junior
curl -si -H 'Host: www.example.org'   localhost:8080/  # 308  -> example.org, one community one address
curl -si -H 'Host: api.localhost'     localhost:8080/  # 404  wildcard is off there
curl -si -H 'Host: nope.org'          localhost:8080/  # 404  no config, so not served
curl -si localhost:8080/wp-login.php                   # 410  scanner shield
curl -si localhost:8080/no-such-page                   # 404  and the page carries the way back
```

`GET` and `HEAD` are implemented everywhere; `POST` only on the paths a domain's config declared as
account pages. HTTP/1.0 and 1.1 only. Bodies are capped at 1MB. The tests boot the real server on an
ephemeral port and drive it with an HTTP client -- and, for the account flows, a client that keeps
cookies and does what the page's script does -- so what's asserted is what an operator would get.

### Accounts

Every domain gets an embedded H2 database under `<root>/dbs`, one file named for the domain. A domain
can point `use_database_domain` at another one and share it, which means sharing accounts *and*
sessions: signing in at `example.org` signs you in at `junior.example.org`, because there is
literally one `emails` table behind both.

The schema lives in `Schema.java` and upgrades itself. Add a column, restart, and the upgrader diffs
what is on disk against what the code declares and inserts anything missing **in the declared
position** -- so a database upgraded through three releases is shaped like one created today. It
will not drop, rename, or retype a column; a type change refuses to start rather than guessing.

Every account starts unapproved: registering proves you can read an address and creates the row, but
an admin has to say yes before you can sign in. `admin_emails` in a domain's config names the
addresses that are admins outright — approved, holding the admin role, and not revocable from inside
the running system. Without that, requiring approval would mean nobody can ever sign in on a fresh
install.

Signing in is passwordless by default: you get an emailed code, and there is no password to leak,
reuse, or reset. Password and password-plus-code modes are a line of JSON away. Sessions never
expire by default -- logging your neighbours out every week to defend against nothing is how you get
them to stop coming -- and a session cap with a grace window is what keeps that bounded:

```json
"login_security": {
  "max-active-sessions": 4,
  "max-active-sessions-grace-seconds": 1800
}
```

Four sessions that stick, infinite lifetime, and a fifth sign-in never knocks anybody out mid-task.

Session tokens are stored as SHA-256, never in the clear, so a stolen database file is a list of
hashes rather than a list of logins. The lookup is a write-through in-memory cache: a hit is a hash
map probe with no I/O, a revocation hits the disk before it hits memory. Every mutation goes to the
database first, because a revocation that loses a race with a crash is a token that still works.

There is no email provider, so the dev box prints the mail to your terminal, spaced out for copy and
paste:

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

`Mailer` is a short closed list of flows rather than a generic send(), so an SES or SMTP
implementation is a template per method and nothing in a handler can invent a new kind of email
without the interface growing a method and somebody noticing.

### Content, and the event bus that keeps it fresh

Pages live in a `content` table and are written at `/admin/content`: markdown wrapped in a template,
an HTML fragment wrapped in a template, or a whole document served as-is. Markdown has everything
switched on — tables, task lists, footnotes, autolinks, heading anchors. Templates live in their own
table and are mustache: `{{{body}}}` is the page, `{{title}}` and `{{uri}}` are its own.

Pages are cached for an hour by default. They also update the instant you save, because every write
announces itself:

```
#3 example.org templates/1 update
#2 example.org content/1   insert
#1 example.org templates/1 insert
```

Every mutation emits an event naming the domain, the table and the primary key. Caches listen and
drop exactly what changed — and a template change cascades, dropping every page that named it, so a
layout edit is immediate rather than an hour later. The TTL is a backstop for an event that never
arrives, not the mechanism.

The bus is an interface with an in-process implementation that keeps the last 1,000 events for
`/admin/system/events`. That is the scaling escape hatch: put several processes behind a sticky load
balancer and the first thing that breaks is cache coherence — process A edits a page, process B
keeps serving the old one. The fix is one implementation of `EventBus`, not an audit of every cache.
The database is behind an interface for the same reason: H2 runs in strict mode so its SQL is
portable, and a MySQL or PostgreSQL `Database` + `Dialect` is the only thing standing between this
and a shared database.

**Some pages are a shape rather than a document.** Pick *HTML Event Listing* — or Event, Place
Listing, Place, Member Listing, Member — and the body becomes a mustache filled in from what the
community already holds, so a group that wants its own front page for what is on does not have to
maintain a website beside a database that already knows all of it. The address is a pattern with one
hole in it (`/whats-on/{{page}}`, `/people/{{member_id}}`); the request fills the hole and the hole
becomes an argument to the query. Every listing carries a `pagination` object so the operator draws
their own navigation. Who may read one is inherited rather than invented: the address book and the
directory need a member exactly as their built-in pages do, and a stranger reading an events listing
sees the events the community opened to the public and nothing else.

**Everything comes out as one JSON file.** Every page and template carries a merge key stamped once
and never rewritten, so a bundle downloaded today and brought back in March is a *merge* — same key,
same page, whatever its address has become — rather than a pile of duplicates. That is what makes a
git repository of markdown a way to write this site, and what makes "back up the content" something
other than copying a database file.

**Every save is a version, and a version is the whole page** — body, title, template, folder, field
values, flags. A snapshot every ten versions and a line patch in between, so a typo on a long page
costs bytes and a rewrite costs the page. The editor's **history** link lists who changed what and
when; **changes** shows one save line by line; **restore** puts a version back as a *new* version,
so the edit being undone stays in the history. That is `git revert`, not `git reset --hard`, and it
is the reason to keep a history rather than a backup.

### Profiles, and asking the community things

Every account starts unapproved, and a session means *authenticated* — they proved they can read
that address — not *authorized*. An unapproved person can reach their own page at `/self` and
nothing else. That separation is the whole point: approving somebody is a judgement call, and a
judgement call needs something to judge. They write a profile and answer the community's questions,
and that is what the admin reads before saying yes.

```
newcomer@example.com
Sam Rivera · woodworker, joined via a friend · Austin
I make chairs.  · hand tools · no screws
https://example.com/sam

Answers (3 of 3 + 0 unanswered)
  What brings you here?   A friend in the Austin chapter told me
  Which chapter?          Austin
  How keen are you?       5
```

**The survey is the same mechanism pointed at engagement.** An admin asks a question at
`/admin/survey` — free text, a dropdown, or a rating — and everybody in the community has a small
number in their navigation until they answer it. Ask a question, and the whole community has one
thing to do.

Questions are rows whose definition is a JSON blob, because the shape differs per kind and the set
is meant to change constantly. Answers are one row per person, a blob keyed by question id, so an
answer survives a question being reworded and stays (uncounted) when one is retired.

Because the set keeps moving, two things are deliberate. **Saving answers is a merge**: the page
leads with what somebody has not answered yet, so a submission mentions a handful of the questions
that exist — and treating that as the new state of their sheet would erase last month's answers
every time somebody answered a new question. **Deleting a question is soft**: really removing one
means rewriting every answer sheet in the community, which is too much to do inside the click that
meant "stop asking this", so retired questions wait on their own admin page until somebody commits
the cleanup and is told how many sheets it rewrote.

Because the question set is dynamic, the counts are maintained by a background indexer rather than
computed per request: adding a question makes everybody behind by one, and doing that inside the
request that saved it would get slower as the community grows. Bursts coalesce, so building a survey
costs one re-index, not one per question.

### Things to do, and things to do again

`/tasks` is projects: a list, a routine, or a board. A project decides what it calls its own items,
because one community's "tasks" are another's "exercises", "chores" or "steps", and a screen that
insists on one of those is a screen people translate every time they read it.

**A definition is separate from an occasion of doing it, and that split is the whole design.**
"Bulgarian split squat" is a fact about the world &mdash; how it is performed, what to watch for,
what it is measured in. Tuesday's three sets are a fact about Tuesday. One row for both would mean
rewriting the instructions every time, or having none; and it would make "how has this gone over six
months" a question about strings. History follows the definition, so the same exercise in next
year's routine still knows what you lifted last March.

**Seven measurements, four columns.** Weight, reps, seconds, distance &mdash; each one nullable and
meaningless unless the measure asks for it. Weight and reps; bodyweight reps; bodyweight plus or
minus weight; time; time under load; distance and time; weight over a distance. The weighted
bodyweight case is signed on purpose: +10kg and -20kg assisted are one axis, and somebody's first
unassisted rep is the moment that number crosses zero. Two measures for that would put the
interesting week in a gap between two charts.

**What "more" means is asked of the measure rather than assumed.** Tonnage is right for a barbell
and nonsense for a 5k; seconds are right for a plank and backwards for a run. A chart that silently
rewarded slower running would be worse than no chart.

**Every occasion takes three scores, one to five: how hard, how long it took, and whether it was
worth doing.** They come apart on purpose &mdash; the thing that is exhausting and useless is
exactly the thing worth finding, and neither the weight nor the clock can say it. Nothing is stored
for "did not say", and no box is pre-selected, because a form that started in the middle would
quietly turn every unanswered set into an average one. What comes out is impact for time, which is
the number somebody tuning a routine is actually looking for.

**Rest lives on the definition, not on the occasion.** A heavy squat wants three minutes and a set
of curls wants forty seconds, and that is true in every routine the movement ever appears in. The
timer is rendered by the server &mdash; "1m 20s since your last set, rest 3m" is on the page before
any script runs &mdash; and the shipped file only makes the number move. A gym is the worst network
anybody uses regularly, and a timer that exists only once JavaScript has loaded is a timer missing at
the moment it is wanted.

**Things done together are one thing on the screen.** A **superset** is `related`: alternate between
them, and *the rest belongs after the round rather than after each set*, which is the whole reason
people superset and the thing that turns a time-saver into a time-waster when it is the wrong way
round. The item screen says so and does not offer the timer between the parts. A **circuit** or a
warm-up building into a working set is `sequenced`: the order is the point, so it is numbered and
each item says which one comes next. A group is a shared name on a column, so joining is writing it
down and leaving takes nothing with it.

**A one-rep max where a bar is loaded, and nowhere else.** Epley, from the best single set, and
**only up to twelve reps** &mdash; past that the number is about how long somebody can suffer rather
than what they can lift, and a confident figure there is one people try to beat. A plank has no
one-rep max and neither does a 5k, so the field is absent rather than zero; and an assisted rep has
none either, because there is nothing to extrapolate towards. It is shown rounded to the half kilo,
with the sentence "a direction, not a target" beside it, because the input does not carry the
precision that printing 102.83kg would claim.

**A routine comes back rather than closing.** Ticking a repeating item moves its date forward; a
list somebody has to rewrite every Sunday is a list they stop rewriting in March. Finished items
drop out of the way after however many hours the project says, and are never deleted &mdash; that
setting is about what is in front of you today, not about the data.

**The community can share too.** A project with no owner belongs to the community, which is how a
committee's list of things to do before the summer party works; and the shared library is a set of
definitions everybody can take a copy of. Adoption is a pointer rather than a copy, so improving the
form notes improves everybody's.

**Your own is yours.** A project with an owner is opened by that person and nobody else &mdash;
including administrators, on this path, who get the same 404 a stranger does. The admin screen shows
that somebody keeps a routine and how recently, and deliberately cannot open it. A community that
genuinely needs to see one can ask; its owner can download the whole thing.

**All of it is reachable by an assistant, and always as its own person.** There is no argument
anywhere in that half of the surface for *whose* &mdash; a tool with a `user` parameter is one prompt
away from reading somebody else's. The scenario it is built for is a model writing exercise
definitions with enough in them to look the form up, putting them on a project, recording what
happened with the timestamp, asking for the three scores rather than inferring them, and reading
back which exercises are earning their place.

### The discussion board

`/board` is threaded discussion for members: post, reply, and hear about what you are part of.

**Posts expire by default.** A board that keeps everything becomes an archive nobody reads and a
liability somebody has to think about eventually; one where threads age out stays a conversation.
Set `board.expiry-days` to 0 for a permanent record.

**Replying is what makes you a watcher** — there is no subscribe button, because a board where you
have to remember to press one is a board where people miss the reply to their own comment.
Everybody watching hears about a new reply except the person who wrote it, in an inbox on their own
page, and notifications age out with the thread.

Threading is a sort key rather than a recursion: each comment carries a dotted path, so the whole
tree comes back in reading order in one query no matter how deep it goes.

### Deciding something, inside the argument about it

Any member can put a **vote** in a conversation. It lives there rather than beside the survey
because that is where the reasons are &mdash; a decision with its reasoning removed is not worth
keeping, and this software is for the evening people actually spend together, which is settled by
argument and then by a count, in that order.

A **straight choice** is either-or: one vote each, most votes wins, and voting again moves your vote
rather than adding one.

A **day and a place** asks both at once, because they are one decision, and when it closes the
winners become an event. The two halves count differently and that is the whole design:

- **Days are approval-voted** &mdash; yes, no, or nothing, on each day separately. A week has
  several evenings and somebody can be free on three of them; forcing one pick throws away most of
  what they know. What comes out is a histogram, which shows whether one evening is genuinely better
  or whether the group is split. A **no** counts against, so an evening half the group has ruled out
  does not beat one nobody objects to.
- **Places are either-or.** Somebody can be free on three evenings; nobody thinks the event should
  happen in three places. A place is always one from the address book, so the winner becomes the
  event's location without anybody retyping an address.

Anybody who can vote can put another day or place on the table &mdash; somebody else knows a hall
you do not &mdash; and removing an option keeps the votes already cast for it, so taking one off
cannot silently change what the others are a share of.

**Ties are reported, never broken.** Picking the earlier day would be this software deciding what
the community has not, silently, at midnight, and putting it in everybody's calendar. So it closes,
says which half tied, and posts that back into the conversation, where somebody can add a day and
ask again. The same is true if everything on the table was voted down more than up.

Putting up a vote that turns itself into an event needs permission to create events, checked when
the vote is *asked* rather than when it closes &mdash; finding out at midnight, after people had
voted, that the answer cannot become anything wastes the group's attention and teaches them the
feature does not work.

**All of it is reachable by an agent, held to what its person may do.** The mission for this is a
community of friends where some of the organising is done by agents: one puts a question to the
group, people and other agents answer it, and the answer becomes an evening. What makes that safe
rather than alarming is that every board and poll tool asks the same `Access.can` a page asks, so an
agent can never do anything the person holding the connection could not &mdash; and when it refuses
it names the permission, so the model's next move is to tell somebody rather than to try a different
phrasing.

Authors fix their own words and it says so; admins pin, lock and remove, and deliberately cannot
edit somebody else's post — rewriting what a person said while leaving their name on it is the one
moderation power they cannot undo. The feed and every
thread's rendered markdown are cached and dropped by the event bus, so a busy conversation gets
cheaper to read rather than more expensive.

**Two notification settings, because there are two different events.** A reply *to you* is a
conversation waiting on an answer — immediate, by default. Activity in a thread you are watching is
news — a daily summary, by default. Either can be never, immediate, daily or weekly, and everything
lands in the on-site inbox regardless; the settings only decide what is also worth an email.

Nothing is delivered from the request path. One background thread reads the unsent notifications,
groups them per person, and sends or holds — because a reply in a thread with forty watchers is
forty signed requests to Amazon, and doing that inside the POST would make the reply box get slower
exactly as a thread got popular. Text messages are groundwork only: the seam exists, no provider
does, and the settings page says so rather than offering a checkbox that stores a broken promise.

### An app, without an app store

`/~app` is a progressive web app: a shell holding the site in an iframe, a per-domain manifest, and
a service worker at the root. Members install it from the browser and get an icon, a standalone
window, and notifications &mdash; with no developer account, no review process, and no second build.
The site itself is unchanged: every page still works on its own URL with no JavaScript.

**Every page declares the manifest, and the icons are real bytes at real addresses.** Both of those
were wrong and the symptom was the same: no browser ever offered to install this. The manifest was
declared only on `/~app`, which nobody reaches without already knowing it exists, and its icons were
the inline SVG favicon as `data:` URIs -- correct by the specification, refused in practice, because
Chrome downloads manifest icons and iOS wants a PNG. The icons are now drawn on the way out, in the
community's own accent colour, at `/~app/icon-192.png` and friends; there is still no image file
anywhere in the tree. The worker gained a `fetch` handler for the same reason -- a browser will not
install an app whose worker cannot answer a navigation with the network down -- and it still caches
nothing, because the only thing it can produce offline is a "no connection" page built inside the
worker, for which stale is not a possible state.

**`/~app/help` is the screen that says how, and proves it.** Per-platform install steps (Safari's
Share menu, Chrome's Install app, Add to Dock), a button that turns notifications on, and a button
that sends a real notification to this browser right now. The self-test is the point of it: "the
server accepted it" and "the phone showed it" are different facts, and every push problem lives in
the gap between them -- so the service worker tells the page when one lands, and the page says
which of the two happened. Nobody otherwise discovers that a focus mode has been eating their
notifications until the evening they needed one.

**Push notifications belong to a session, not an account.** Each signed-in browser is one session,
one subscription and **its own VAPID keypair** &mdash; so signing out does not merely stop us
sending, it destroys the only key that push service will accept for that browser. Sign-out deletes
the session rather than revoking it, precisely because a revoked row used to linger for a day while
the server still held a working key for a device somebody had just walked away from.

A push carries a title, a line and a path. **Never the contents** &mdash; it crosses somebody else's
infrastructure and lands on a lock screen, so its job is to bring you back, not to tell you the
thing. The encryption is RFC 8291, hand-rolled and checked against the RFC's own published test
vector rather than merely round-tripped.

### Receiving mail, and knowing who sent it

Hearth can receive email as well as send it &mdash; off by default, because port 25 needs root and
an unconfigured listener on it is found by scanners within the hour.

**It never relays.** A message is accepted only for a domain with a config file here, matched
exactly, and refused before a body arrives. An open relay is found within days and ends with the
machine on every blocklist there is.

Every message is checked with **SPF, DKIM and DMARC**, and the findings are stamped onto it as an
`Authentication-Results` header. The three matter together rather than separately: SPF authenticates
an envelope nobody reads and breaks on forwarding, DKIM authenticates whichever domain chose to sign
and survives it, and **DMARC is the alignment** &mdash; requiring the authenticated domain to line
up with the `From:` a person actually sees. Only a message failing a published `p=reject` is
refused; refusing on SPF alone would reject everything that came through a mailing list.

### A community writes its own calendar

Anybody here can suggest an event. It goes to a queue rather than onto the calendar, which is what
makes opening that door safe — a suggestion costs whoever looks after it a screen to look at rather
than control of the front page. Accepting one publishes it; declining keeps it, with the reason, so
the person who suggested it can see what happened rather than watching it disappear.

A calendar only an administrator can write to is a programme published at a group. A calendar
anybody can suggest to is a group deciding what it does.

An event's location can be a place from the address book — its name, its address and a link to
everything the community wrote down about it — with free text beside it for the bit that is only
true this time: "The Oak, back room".

### A door for programs, and a key you copy by hand

A command line tool prints an address, somebody opens it, reads what is being asked for, presses a
button and copies a string back. No callback, no local listener, no redirect &mdash; so it works from
a machine with no browser on it, over SSH, and from a phone, and nobody has to wonder which program
received what.

The token is a **session with a bit set**: same table, same reaper, same revocation, same answer to
"is this still valid", and everything it does is recorded as the person who made it. It can never do
more than they can. Two at a time, thirty days, both settings; a third is refused rather than
rotated, because silently retiring the oldest would stop whatever has been using it for a month.

`/api/v1` is bearer-only and the browser's cookie is never a credential there &mdash; the whole
contract is in [API.md](API.md). Pushing a bundle of
content **writes only what differs** and answers with what moved, row by row &mdash; and `?dry=1`
answers the same thing without writing, because a diff nobody can see before it lands is a diff
nobody reviews. That is what makes a git repository of markdown a way to keep this site.

### When can everybody actually come

Somebody fills in the shape of their week &mdash; Tuesday evenings, most of Saturday &mdash; once,
and it stays true for years. Then they paste the address of the calendar they already keep, and the
fortnight they are away stops being a fortnight the community plans around them. **Nothing but the
times is kept**: a feed is reduced to pairs of numbers on the way in, so what somebody is doing on
Thursday is never something this server holds and never something anybody has to decide who may see.

What comes out is a grid, and the two numbers in each cell are the point: how many people would like
that hour, and how many are clear at *every* occurrence of it for the next month. Sixteen people who
love Tuesday evenings and four who are actually free is a different fact from four people who like
Tuesdays. The three best hours are printed on the event form, which is the moment somebody is
actually choosing a night.

**Somebody who has said nothing is still counted**, from an assumption &mdash; weekday evenings and
most of a weekend day &mdash; which is wrong about individuals and right about groups. That is
deliberate: a tool that only worked once everybody had filled a form in would never be used by
anybody, and whoever cares enough to say otherwise overrides it completely.

Counts, never names.

### Files people upload

Photographs, video, a recording, the PDF of the menu. They live as files under the root &mdash;
`attachments/jpg/42/1342.blob`, bucketed so no directory ever holds a million things &mdash; because
a photograph in a database column is read into memory to be served, copied by every backup of the
schema, and impossible to hand to a web server later.

**What the browser says a file is counts for nothing.** The extension decides, checked against a
closed table of things this server knows how to serve safely, and `text/html` is not on it for any
extension or any configuration. That is not a detail: believing an upload's declared type is how a
community's own domain ends up serving attacker-written HTML to members signed in to it.

Private by default, and private means an approved member; public means anybody. Every one is
`Cache-Control: private` &mdash; browsers may keep a copy, shared caches never may, because these are
frequently photographs of somebody's children. A referrer check stops the server quietly becoming a
free image host for somebody else's forum, and a request with no referrer is still honoured, because
browsers omit it constantly and this is a bandwidth measure rather than a boundary.

Folders and tags to find one again, a memory cache so one photograph and forty browsers is one read
of the disk, and a picker in the page editor that puts the line in for you &mdash; with the sentence
somebody wrote about the picture becoming its alt text.

### Switching parts off in one word

```json
{ "disabled": ["places", "members"] }
```

Everything is on until somebody says otherwise, because the decision worth writing down is the
refusal. Off means off everywhere at once: the path stops answering and the entry disappears from
the navigation. A word this server does not recognise stops it from starting, because a typo there
is a surface somebody believes is off and is not.

### Live, without a framework or a socket

One connection per domain however many tabs are open — the tabs elect a leader between themselves —
carrying **signals with no content in them**: "comments:412 moved". The browser then re-fetches the
page it is on and swaps in only the parts that changed, and it will not touch a half-written reply
to do it: anything holding the caret, an open editor, or a field somebody has changed is left alone
and picked up on the next pass.

Server-sent events where they work, a long poll where a proxy eats them; the client falls back by
itself and both are answered by the same code. Adding a table to one list is the whole of making a
new page update itself.

Green dots never touch the disk. Presence is a fact about the last few seconds, it is wrong the
moment it is written down, and writing it would make it the busiest thing on the machine.

### Comments on everything, and they stay

The board, every event and every place take comments — the same comments, one table, one set of
rules. They do not expire: a thread is what the community decided, and a memory with a fortnight's
horizon is not one.

A long thread folds instead. Old conversations become one line with a count on them and the recent
ones are open underneath, grouped by the age of the top-level comment so a reply always sits with
the thing it replies to.

Taking somebody else's comment down is a permission **per section**, because keeping the address
book tidy is a job somebody was given for the address book rather than a power over everything
anybody has ever written.

### The calendar

`/events` is a small events board: an admin posts something happening on a day (or across a span of
them) at a place, and members say whether they are coming and how many they are bringing.

Days rather than timestamps, because a community event is "Saturday the 14th" and a timestamp
forces a clock time onto everything and a timezone question onto every reader. The time is free
text shown as written — "doors at 7, music at 8" is a real answer.

**Capacity counts seats, not answers.** Somebody bringing three takes four places. Past the limit an
answer becomes a waitlist entry and the page says so rather than showing a tick for a place that
does not exist; somebody dropping out promotes the longest wait that *fits*, so one person waiting
for six does not block five waiting for one each. Cancelling keeps the page and the guest list —
the people who said they were coming are exactly the ones who need to see it is off.

**An event is a calendar invitation, and an answer is an email.** Announcing one sends a real
`text/calendar` invitation — the kind that draws accept and decline buttons in a mail client rather
than arriving as a file somebody has to notice — and pressing Accept sends a reply *to this server*,
which checks that the sender is a member, that the attendee named in the file is the sender, and
that the message passed SPF, DKIM and DMARC, then moves the guest list. For a lot of members that
will be the only way they ever answer, which is the point: the goal is people turning up, not people
visiting a website. Rescheduling asks what happens to the answers rather than deciding; a proposal
from somebody's calendar is recorded as a suggestion and never as a change; nobody who said *no* is
ever nudged again.

The same address takes invitations *in*: put the community's calendar address on an event in your
own calendar and it appears here, with its location matched against the address book before anything
new is written down. Every event also has a file to download, and one an event's organisers opened
to the public can be taken — and answered — by somebody with no account here at all. Those answers
land on a list of their own, taking nobody's seat, and become ordinary answers on the day that
person joins.

### Invitations

`/admin/invites` writes, sends and tracks invitations, and leads with the number that matters:
**conversion** — how many became somebody who signed in.

Each message carries a tracking pixel, and the screen is careful about what it proves. An open means
the message was rendered with images on; a missing open means *no evidence*, not "unread", because
most mail clients block remote images by default. A tracking pixel reported as a read rate is one
that will be believed.

Conversion happens on sign-up rather than on click — a click proves a link was opened, and what an
invitation is for is a member. Inviting one person three times and having them join once is one
conversion, not three.

### Two places configuration lives

Some of what a community runs on is a fact about the machine and some is a fact about the community,
and they are kept apart deliberately.

**The operator's** stays in a config file, read once before the socket opens: sign-in policy,
credentials, what a program connecting here may do, what a request may carry, routing, and the list
of addresses that are administrators by fiat. That last one is the sharpest case — an escape hatch
you can edit from inside the thing it rescues you from is not one. These are reviewed by reading a
file and changed by somebody with access to the box.

**The community's** lives in its own database and is edited at `/admin/configuration`: what it is
called, its clock, miles or kilometres, which parts of the product it has at all, how long a
conversation lives, and every line that goes on an invitation. No restart, and a form with the
meaning of each setting written beside it.

The trick that makes it safe is that a setting's key is the path it already had in the config file,
so applying one means writing it into a copy of that file's JSON and parsing the whole thing again
— **the value is checked by exactly the parser that decides whether the server boots**, and a bad
one is refused in the same words with nothing written. A file value is the starting point and a
database row overrides it, so an existing install keeps working and clearing a box puts the file's
answer back.

`/admin/configuration/setup` is a four-screen walkthrough for the settings where the default is a
guess about a community this software has never met. **No model can touch any of it** — there is no
agent tool for the settings, not even one that refuses.

### Colours, and what every email carries

A community picks six colours for a light screen and six for a dark one, and they land everywhere at
once: every page, and **every message this server sends**. There used to be two email layouts — a
plain one for codes and a designed one for invitations — which meant the invitation would have been
the one message that ignored whatever a community chose. There is one now.

Red, green and amber are not on the list. They mean refused, worked and careful.

The administration gets its own palette, and it carries the legal pages: a community's promises are
not its decoration.

### Terms and a privacy policy, from the first day

Every community has these two whether or not anybody has thought about them, so Hearth ships a
considered version of each at `/legal/terms-of-service` and `/legal/privacy-policy`, readable without
signing in, and every email links to them. They say plainly that the people who wrote this software
and whoever provides the machine are not parties to anything a community does — which, when the
community is running its own server, is the fact somebody actually has to be told. They also say the
thing that matters most for a project whose whole point is meeting in person: **events are attended
at your own risk**, nobody vets who turns up, and that is not a disclaimer so much as an accurate
description.

An administrator overrides either one in markdown, and emptying the box puts the shipped text back —
so a community that never touches them keeps getting improvements to the default, and one that
rewrites them owns what it wrote. They are a starting point, not legal advice, and the screen above
the editor says so.

### Email

Codes and links print to the terminal until a domain has an email provider. `--setup-email` walks
through **Amazon SES** — one signed POST, no AWS SDK — and `--test-email` sends one real message and
tells you exactly what came back, because "accepted" and "delivered" are different things and the
SES sandbox is where most first attempts quietly stop.

Credentials live in that domain's config, written `0600`, because a single jar has no secret store
and pretending otherwise would be worse than saying so.

### Certificates

Turn on `enable-certs` in `config.cfg` and Hearth gets and renews its own certificates, one per domain, under `<root>/certs`.

Verification is over plain HTTP and entirely internal: the authority asks this server for a file at
`/.well-known/acme-challenge/…` and this server answers it. No DNS records to add, no bucket to
upload to, no second system to keep in sync — which also means no wildcards, since those need a DNS
challenge and therefore credentials for whoever runs your DNS. Name the subdomains you want in
`subdomains` instead and they are ordered along with the domain itself.

`--setup-certs` walks through registering an account once, and it is a conversation rather than a
flag because the failure it prevents is expensive: authorities rate limit failures hard, so
restarting a server whose DNS is not ready yet can lock you out for an afternoon. It says what has
to be true, resolves each domain so you can see what the machine thinks, offers the staging
authority first, and refuses to run without a terminal.

After that, a few seconds *after the socket is open* — validation is the authority calling back, so
it cannot happen sooner — it orders what is missing, then renews anything within 20 days of expiry.
A domain that will not validate gets a clear complaint and a retry; the server serves plain HTTP
throughout and never fails to start over a certificate.

`"enable-https": true` then serves them, on **80 and 443**, negotiating **HTTP/2** during the
handshake and falling back to HTTP/1.1 for anything that cannot. Each domain's certificate is chosen during the
handshake by the hostname asked for, so one process hosts several communities on one address. Port
80 stays a real web server rather than becoming a redirect — it is what answers the challenge, and
turning it into a redirect would quietly break renewal three months later. If a load balancer needs
somewhere to send plain traffic, `http-bounce-port` is a separate listener that does nothing but
redirect.

Renewals reach the listener without a restart, and the boot output reports each domain as its
certificate actually lands rather than promising one in advance.

### Connecting a model

Hearth can hand an assistant the keys to its own website. `mcp.enabled` on a domain turns on an
[MCP](https://modelcontextprotocol.io) endpoint at `/mcp`, and a model with a connector &mdash; Grok
is what this was built against &mdash; can then read, search, write and reorganize the site, manage
templates, ask the community questions, summarize every answer it gets back, and take part in the
discussion board &mdash; all of it held to what the person holding the connection may do.

**Off by default.** Every other default here is tuned for a high trust community. This one is not,
because what it hands out is the ability to rewrite the site.

Getting a token requires a person. A connector registers itself, **somebody looks at a screen that
says what it will be able to do**, and only then does a code come back &mdash; redeemed with PKCE,
single use, bound to the client and redirect it was issued for. Where codes may be sent is an
explicit prefix list with no wildcards, because an authorization code delivered to the wrong host
is an agent token handed to whoever owns it.

The token that comes out is **a session belonging to the person who approved it, with a robot bit
set**. That is the whole identity model: an agent can never do anything the person could not, and
it is never mistaken for that person afterwards. Revoking their sessions takes its tokens too.

**Any member can have one, if the community says so.** It is a permission &mdash; *Connect an
assistant that acts as you* &mdash; that an admin grants in a role, which is what makes the board a
place several people's agents can coordinate in: one puts a question to the group, people and other
agents answer it, and the answer becomes an evening. It is deliberately not automatic, because a
connection is a standing credential held by somebody else's software that can act as that person for
a month; and it is deliberately not an admin power, because what an assistant can do is exactly what
its person can do. Everybody can see and disconnect their own on their own page, and taking the
permission away stops an agent at its **next request** rather than whenever its token expires.

That last sentence is enforced rather than promised. Every tool asks the same `Access.can` a page
asks, and there are two shapes of answer. **A write is refused by name** &mdash; "it needs 'Write
and edit pages'" &mdash; so the model's next move is to tell somebody rather than to hunt for a
phrasing that works. **A read is narrowed, not refused**: a member's assistant listing pages gets
the published ones, listing events gets the announced ones. Refusing would make the tool useless to
the person it belongs to; answering in full would hand them a draft they cannot open in a browser.
And a tool that could only ever refuse is **not offered at all** &mdash; the tool list is narrowed
per connection, because a control that would say no teaches whoever meets it that the software is
broken.

Two things bound every connection, whoever holds it. It cannot touch accounts, approvals, emails or
bans at all. And any page ticked **human only** is invisible to it &mdash; absent from listings,
searches, fetches and the navigation, with writes refused out loud. That asymmetry is deliberate: a
locked page that merely looked empty to a write would get quietly overwritten by a model asked to
"add an about page".

Everything it does lands in an **AI log**: the last 1,000 actions, arguments and results kept as
JSON and shown indented, under two names &mdash; the person who authorized the connection and the
connector that acted. Refusals are logged as loudly as successes, because a model bouncing off a
locked page repeatedly is worth seeing.

### The admin section

`/admin` (configurable) is a top bar, a nested sidebar, and a main area.

- **Overview** — who is here right now, and what there is
- **People** — approve, promote, turn off, or reject, with **Bans**, **Invites** and **Roles** under it
- **Content** — pages, with **Suggestions**, **Templates**, **Directories**, **Navigation**, **Files** and **Import & export** under it
- **Board** — pin, lock and remove, with **Flagged** under it for what somebody asked to be read
- **Events** — what is on, with **Suggested** under it for what members put forward
- **Places** — the address book, with **Kinds** under it for the fields a community invented
- **Survey** — ask the community something, with **Retired** under it for the cleanup
- **Projects** — that somebody keeps one, and how recently; never what is in it
- **Engagement** — every rule for getting somebody here and getting them back, in one place
- **Settings** — what this community *is*, with **Setup** under it: the walkthrough for a new one
- **Customization** — **Appearance**, **Legal** and **Messages**: the colours, the promises, the wording
- **System** — **Machine**, **Settings**, **Events**, **Analytics**, **Caching**, **Async**, **AI**, **Log**

Every entry there is drawn only for somebody allowed to open it. A section you may not enter is
absent from the sidebar and answers 404 rather than 403 — a 403 confirms what is behind the door,
and a sidebar full of doors that say no is worse than a small one.

Four rules hold it together, each of which came from something going wrong.

**Every sub-view has its own URL.** A section is a real server load, and the part of it that
refreshes in place is a *panel* at its own path — `/admin/people/list`, `/admin/system/logs/results`.
The section page embeds a panel by calling the same method the panel's URL calls, so the two cannot
drift, and a panel shows up in the access log as itself instead of hiding inside its parent. The
previous design used a `?fragment=1` flag and broke outright: Mustache escapes `=` to `&#61;`, HTML
entities are not decoded inside a `<script>`, so the live button fetched `?fragment&#61;1` and the
whole page rendered inside the panel. Script configuration now travels in `data-` attributes, which
the parser decodes on the way in.

**Identity in the path, filters in the query, changes in a POST.** `/admin/content/edit/41` names a
thing; `/admin/content/list?q=about` is a view of a list; `POST /admin/content` changes something and
answers with a redirect. Confirmations ride on the session and are read once, so nothing meant for
one person ends up in the browser history or the log.

**A listing is not a form.** Creating or editing anything is a page transition to its own URL. A form
wedged above a list has ambiguous state the moment the list moves under it.

**Rejecting is not unapproving.** Leaving somebody unapproved means *not yet*. Rejecting means *no*,
and deletes the account, the profile and the answers, optionally banning the address. Turning an
account off is the reversible middle: everything is kept, every session ends. An admin is none of
these until their role is removed — and the two spellings of admin are drawn differently, config
admins in red and promoted admins in purple, because one is a fact about a file on the box and the
other is a decision somebody made in this UI.

About seventy lines of vanilla JavaScript in the shell drive every panel and every filter box. There
is no library.

Analytics knows which member made a request because session resolution happens on the request path
and writes the user id into the log. User agents are classified into common browsers and bots —
carefully, since Chrome claims to be Safari which claims to be KHTML — and anything unrecognised is
counted *and recorded verbatim*, so a spike is something you can look at rather than a bucket
labelled "other".

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
approval is what actually decides who gets in. The event counts are kept on the account row and
shown to admins, because a wave of signups that all scored identically is a shape you can only see
afterwards.

### Virtual hosting

Flat on disk, a tree in memory.

The `<root>/domains` directory holds one JSON file per virtual host, named for the domain it configures:

```
/var/hearth/domains/
  localhost.cfg
  example.org.cfg
  junior.example.org.cfg
```

Flat because `ls domains` should be the list of everything this server serves — easy to look at,
diff, and rsync. The filename *is* the domain; there's no `domain` key inside the file, because two
sources of truth for the same fact is how they drift apart.

At boot those names get built into a tree of DNS labels rooted at the top level domain:

```
(root)
  +-- org
  |     +-- example          <- example.org.cfg
  |           +-- junior     <- junior.example.org.cfg
  +-- localhost              <- localhost.cfg
```

A request descends from the top level domain as far as its labels allow and takes the deepest
config that applies. `junior.example.org` lands on its own. `www.example.org` has no config, so it
lands on `example.org` — that one sets `wildcard: true`. `api.localhost` resolves to nothing,
because `localhost.cfg` sets `wildcard: false`. Nodes with no config of their own, like `org` above,
are junctions: they exist because something lives under them, and they serve nothing.

Between those two there is `subdomains: ["www", "blog"]`, which is usually what you actually want.
Each named subdomain answers with the same config — the same community, the same database, the same
accounts — and, unlike a wildcard, it gets a certificate. Verification here is over plain HTTP, and
plain HTTP cannot prove a wildcard, so a wildcard domain serves `www` unencrypted forever. A
written-down list does not have that problem, and it is also the only kind of subdomain inbound mail
will accept for.

Whatever the route in, a community has **one** address: any name that is not the config's own domain
answers `308` to the same path on the domain itself, keeping the scheme, the port and the query. Two
live spellings of one community means two sets of links people paste to each other and — because a
session cookie is scoped to a host — signing in at one leaving you signed out at the other. The
certificate challenge is answered before any of that, because an authority validating `www` fetches
its token from `www`, and a redirect is not an answer.

Everything is scanned and loaded **before** the socket opens, and the resulting tree is immutable.
Nothing on the request path reads the configs directory, so the set of domains this process will
ever serve is fixed the moment it starts accepting traffic. Config problems — bad JSON, an unknown
key, a filename that isn't a valid domain, a symlink, a `static-root` escaping the configs
directory — stop the server from starting instead of being logged and shrugged at.

`--verbose` narrates all of it: every file loaded, every file skipped, and for each request every
step of the descent and why it stopped where it did.

```
... GET / host=a.b.example.org from /127.0.0.1:41288
      descend org -> junction, no config here
      descend example.org -> config (wildcard) example.org.cfg
      descend b.example.org -> no such branch; stopping
      most specific match: example.org
      serving example.org (Example Community) -> 200
```

## The road

Each step is a conversation about policy before it is code.

**The next four, in order.**

1. **The board, made worth living in.** Chat was built and taken out again: two rooms is one too
   many for a group of two hundred, and the one that dies is always the board — which is the one
   that lets somebody who was not online at nine take part as a full peer. That is an accessibility
   property rather than a taste, and it is the half worth keeping. What it needs is the frequency
   chat had: better notifications, a reason to open it daily, and something that feels alive.
2. **The social leader, made concrete.** A standing brief the community writes in its own words,
   memory of what happened, the nudges that keep a group alive, and the first read of the hard
   thing so no volunteer meets it cold.
3. **A phone-first pass.** Most community activity happens on a phone. The installable app and the
   16px-and-44px rules are the floor rather than the finish: Hearth is a website, and it had better
   be an excellent one in a car park, on an old handset, on rural wifi.
4. **The friction list** — rides, first-timer flags, the last hundred feet, and hours-together on
   the dashboard instead of cache statistics. Not recurring events: every event here is written
   down on purpose, once, because a series expressed as a rule keeps happening whether or not
   anybody decided it should.

**Also outstanding.** Refresh tokens for MCP. Two-factor beyond email — `password_and_code` works
end to end, authenticator apps and recovery codes do not. Approval notifications: nobody is told
when they are approved, and no admin is told somebody is waiting. And the headline number the
mission commits to — hours spent in each other's company — is still not on the dashboard, which
means recording attendance *after* an event rather than only promising it before.

Every one has policies attached — token lifetime, revocation, cookie scope, what an admin can see.
Those get walked through and written down as they are built, rather than inherited from whatever the
first implementation happened to do.

## The documents

There are four, and they are kept true on purpose — `just docs` is part of `just validate` and
fails the build when a link, a flag, a recipe, a schema version or a quoted test count has drifted.

- **[MISSION.md](MISSION.md)** — why this exists. The research behind the argument above, what it
  takes and refuses from Mighty Networks, Meetup and Eventbrite, the three real communities it has
  to work for, and the leash the AI runs on. It changes rarely; when a commitment in it stops being
  true in the code, that is the most important bug this project can have.
- **[MANUAL.md](MANUAL.md)** — the operator's guide. Every config key, how approval works, how to
  write content and templates, what every admin screen does, and what to check when something is
  wrong.
- **[API.md](API.md)** — the contract for programs. How a token is obtained and used, every
  endpoint, every error, and the bundle format. Written so a tool can be built against this server
  without reading its source.
- **[CLAUDE.md](CLAUDE.md)** — the working notes. Every invariant, why it exists, and what broke
  when it did not. It is long because the decisions are the product, and a decision nobody wrote
  down gets re-made wrongly in six months.

## Contributing

The rule that matters is in CLAUDE.md and it is short: **a finding gets reproduced from the outside
first, fixed with a test that fails before and passes after, and then written into the comment
above the code that fixes it.** There is no list of known-broken things, because a standing list is
a second place to look that says what the code already says.

Run `just validate` before claiming anything works. New checks belong in the justfile — if a check
is not reachable from `just validate`, it is not part of the definition of "working".

## License

MIT — see [LICENSE](LICENSE). Third-party components and their licences are listed in
[THIRD-PARTY.md](THIRD-PARTY.md) and served in full from a running server at `/3rd/licenses`,
because vendoring is redistribution and the obligation belongs to whoever ships the jar.

## Lineage

The HTTP layer takes its shape from [Adama](https://github.com/adama-platform/adama)'s `web`
module — the same pipeline layout, the same request shield, the same boot-then-serve ordering — and
the boot output and single-jar habit from goatbot. Both are read-only references here.
