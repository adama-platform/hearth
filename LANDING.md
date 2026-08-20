# Hearth — landing page content

Source copy for the public site. This file is content, not markup: headlines, body, calls to
action, objection handling, and the register of every factual claim with its source.

**Read [MISSION.md](MISSION.md) before writing a word of the page.** Everything here is downstream
of it, and the fastest way to produce copy that has to be thrown away is to write from this file
alone. [README.md](README.md) is what the software actually does today.

---

## 0. How to use this file

- **Lift the copy, do not paraphrase it.** The voice is the differentiator. Section 3 says why.
- **Every number on the page must appear in the claims register (section 8).** If it is not there,
  it does not go on the page. Several entries are marked `VERIFY` — those are load-bearing figures
  that were true when written and are somebody else's pricing page.
- **Two audiences, and they are not the same person.** Section 2. The self-host page can ship now;
  the hosting page is a pitch for something that does not exist yet and is marked throughout.
- **Nothing here may claim a feature the software does not have.** Section 6 is the list of what is
  not built. Putting it on the page is not a concession, it is the reason to believe the rest.
- When this file changes, `just docs` checks its links, its flags, its `just` recipes and its test
  count against the code, exactly as it does for the other five documents.

---

## 1. Positioning, in one paragraph

Hearth is a single Java jar that runs a small community — a supper club, a support group, a group
of friends who want to see each other more than twice a year — and its whole job is to get those
people into the same room. It is for groups of 100 to 1,000 people, which is an architecture rather
than a limit waiting to be lifted. It never ranks a feed, never moves money, never tracks anybody,
and never has to keep existing for the community to keep existing. One jar, one directory, one
process; backup is copying a directory.

**The one-sentence version:** the community software that is trying to get you out of the house.

---

## 2. The two audiences

They want opposite things from the same product, so give them different pages and let each one see
the other exists.

**Audience A — the person who can run a server.** A developer, a sysadmin, the technical person in
a group who has already been volunteered. They do not need to be sold on self-hosting; they need to
believe this specific thing is worth an evening. They convert on *time to first working community*,
on being able to read the source, and on the absence of a company that could disappear. They are
suspicious of marketing, so the honest limits section converts better than the feature list.

**Audience B — the person who wants the outcome and not the evening.** They run or care about a
group. They may be technical and simply out of hours. They convert on somebody else owning the
machine, the domain and — above all — email deliverability, which is the part that genuinely
punishes amateurs. They need to hear the exit before they hear the price.

**The bridge between the pages, in both directions, and it is the whole trust argument:** it is the
same software either way. Nobody is buying a hosted product with a self-hosted demo version. They
are buying an afternoon of somebody else's time, and they can stop buying it whenever they like and
keep everything.

---

## 3. Voice

Short declaratives. Concrete nouns. The reason attached to the claim. Where something is a
trade-off, say what it costs. The software's own documentation is written this way and the page
should read like the same hand wrote it, because the same hand did.

**Do:**

- Name the specific failure a decision prevents. "A cached pixel is one open recorded forever" beats
  "privacy-respecting analytics".
- Use the second person for the operator and the third for the community.
- Let a sentence end. Three short ones beat one with two subordinate clauses.
- Admit the cost in the same breath as the benefit. One jar means one blast radius. Say so.

**Do not:**

- **"Empower", "seamless", "delight", "reimagine", "journey", "solution", "leverage", "unlock",
  "revolutionize".** None of these have ever appeared in this project and none should start now.
- **Do not use "AI-powered" as an adjective.** The AI claim is precise and lives in section 5.7;
  weakening it into a badge is the fastest way to lose exactly the reader we want.
- **Do not promise engagement, growth, or retention.** The product's stated measure is hours spent
  in each other's company, and every one of those three goes up when a community gets worse. Saying
  so on the page is a differentiator, not a risk.
- **Do not put a testimonial, a logo wall, or a member count on the page until one is real.**
  A fabricated proof point on a page whose entire argument is honesty is a self-inflicted wound.
- Do not use exclamation marks.

---

## 4. PAGE ONE — for people who can run a server

### 4.1 Hero

> # Your community should not need a company to exist.
>
> Hearth is one jar that runs a small community — the website, the calendar, the board, the
> accounts, the email — on a machine you own. It exists to get people into the same room more often
> than they otherwise would.
>
> ```
> java -jar hearth.jar --root /var/hearth
> ```
>
> That is the entire operation. No database to install, no daemon to supervise, no migration tool,
> no cluster, no company. Backup is copying a directory.

**Primary CTA:** `Run it in five minutes` → anchor to 4.2
**Secondary CTA:** `Read the source` → repository
**Tertiary, small, below:** `Or have us run it for you` → page two

Alternative hero headlines, if the first tests badly. Keep the refusal shape; it is doing the work.

- *Half of American adults report considerable loneliness. This is a jar file.* — the strongest and
  the riskiest; it is funny, and the joke is load-bearing rather than decorative.
- *The community software that is trying to get you out of the house.*
- *One jar. One directory. One group of people who mostly live near each other.*

### 4.2 The five-minute proof

The conversion event for this audience is a working community on their own machine. Put the whole
path on the page — not a link to a quickstart, the actual commands.

> **What you need.** Java 21, a machine, and a domain name pointed at it. That is the list.
>
> ```bash
> java -jar hearth.jar --root /var/hearth --setup                    # ports and TLS
> java -jar hearth.jar --root /var/hearth --domain-setup example.org # a community
> java -jar hearth.jar --root /var/hearth
> ```
>
> Each of the setup steps is a walkthrough rather than a wizard: it asks, it writes a file you could
> have written by hand, and it prints what it wrote. Anything else has replaced understanding with a
> wizard, and the first time it is wrong there is nowhere to go.
>
> Then `--install` writes a systemd unit and a start script into a directory you already own, and
> stops. It needs no root and starts nothing. The half that does need root is written out for you to
> read before you run it — a program that wanted root in order to tell you what it was about to do
> is one you have to trust before you can check it.

Follow it with the honest next step, because this is where a first install actually stalls:

> **The two that take longer than five minutes are certificates and email.** Certificates are one
> more command and then a wait, and they are automatic forever after. Email is the hard one for
> everybody, self-hosted or not: getting a message into somebody's inbox rather than their spam
> folder is a reputation problem, not a code problem. There is a walkthrough for Amazon SES and a
> `--test-email` that sends one real message and tells you exactly what came back, because
> "accepted" and "delivered" are different things and the SES sandbox is where most first attempts
> quietly stop.

### 4.3 What is in the jar

Lift the table from [README.md](README.md) — it is already written for scanning and it is kept true
by the same gate that builds the software. Do not rewrite the cells into benefit language; the
specificity is the pitch.

Above the table:

> Not a starting point you extend. A community that installs this today has a website, a calendar
> that sends real invitations, a discussion board, member accounts with approval, an address book,
> file uploads, terms and a privacy policy, and an installable phone app — before anybody writes a
> line of anything.

Below it, the proof block:

> **1751 tests**, and most of them are not unit tests: they boot the whole server on a port and
> drive it over HTTP, including a client that keeps cookies and does what the page's own script
> does. `just validate` builds clean, runs all of it, packages the jar, and then makes real requests
> against that jar running as a server. Nothing here is claimed to work on the strength of a green
> test suite alone.

### 4.4 Why it is small on purpose

> **100 to 1,000 people.** An architecture, not a limit waiting to be lifted.
>
> Dunbar's 150 is the ceiling on stable relationships; the observed mean for a functioning group is
> around 50 active members; forums start breaking down around 80 active contributors. A community of
> 20,000 is not a community — it is an audience with a comment section.
>
> Everything follows from that number. One database file. Caches in memory. A ring buffer for the
> event log. No sharding, no queue, no second process. **A ten-year-old machine should serve this
> community faster than a SaaS platform serves it today** — not because we are clever, but because
> they are doing a hundred things for a hundred thousand groups and we are doing one thing for one.
>
> When a group outgrows it, the group splits. That is what healthy communities do at this size
> anyway, and it is a better answer than a bigger machine.

### 4.5 What it will never do

This section converts. Do not soften it and do not shorten it.

> - **Never rank a feed by engagement.**
> - **Never move money.** Not tickets, dues or tips — that refusal deletes PCI scope, chargebacks,
>   refunds, tax reporting and the whole class of feature that turns an organizer into a merchant.
>   Pay the restaurant. Venmo the host. Hearth counts heads, not dollars.
> - **Never sell position in a community's own queue.** Meetup+ subscribers jump the waitlist, so
>   when a place opens at *your* event the platform moves whoever paid *the platform* first.
>   Hearth's waitlist is longest-wait-that-fits, and nothing changes that order.
> - **Never track anybody.** Two cookies, both needed to keep you signed in. No analytics, no
>   advertising, no third-party request of any kind — every byte of every page comes from the
>   machine the community runs on. That is why there is no cookie banner: consent walls exist for
>   cookies that need consent, and there are none here.
> - **Never require a company to exist** for a community to keep existing.
> - **Never be so good at being a website** that it becomes a substitute for the room.

### 4.6 The measure

> **Not signups. Not daily actives. Not posts or time on site** — every one of those goes up when a
> community gets worse.
>
> **Hours spent in each other's company.** Casual friendship takes roughly 50 hours of shared time,
> friendship 90, close friendship past 200 — and hours spent *working* together barely count.
>
> Run that arithmetic and it reorganizes the product. A monthly dinner of three hours is 36 hours a
> year: five years to turn strangers into close friends. The same group meeting weekly gets there
> inside a year. So **frequency beats production value** — six small gatherings beat one good one,
> and a standing Tuesday beats a quarterly event with a speaker.

Pair this with the honesty note from 6.2: the number is the stated measure and the dashboard does
not show it yet.

### 4.7 The AI claim, stated exactly

Precision is the entire value here. The hedged version is weaker and the inflated version loses the
reader permanently.

> Two jobs died with the third places, and both were unpaid.
>
> **The organizer** picks a date, books the table, sends the message, chases the stragglers, greets
> the newcomer, and does it again next month when the thanks have worn off. Most groups do not die
> of conflict. They die because nobody sent the message.
>
> **The moderator** reads the worst thing anybody wrote this week. The research on volunteer
> moderators finds PTSD, anxiety and depression, secondary trauma, and burnout as the main reason
> they quit — distress comparable to crisis-line volunteers. We asked unpaid people to absorb the
> ugliest thing on the internet as a hobby and were surprised when small communities kept dying.
>
> > **AI does not fix loneliness. People do.** What it can do is take the two jobs that made small
> > communities unsustainable, so the people who would have run one are free to be in it instead.

And immediately the leash, because this audience's next thought is "so it posts as me":

> An assistant connects over MCP and is **a session with a robot bit set, belonging to the person
> who authorized it**. It can never do anything that person could not. It never speaks as the
> community. Any page marked human only is invisible to it. Everything it does is logged under two
> names — the person and the connector — and refusals are logged as loudly as successes. It is off
> by default, and it is the only default in this software not tuned for a high-trust community.

### 4.8 Objection handling

Write these as a plain FAQ. This audience reads it before the feature list.

**"What happens when you stop working on it?"**
It is MIT licensed, it is one jar with no services behind it, and the whole installation is one
directory. Nothing here calls home, so nothing stops working when a website goes away. That is not
a promise about the future, it is a property of the architecture.

**"Java? In 2026?"**
One artifact, no runtime to install alongside it, no dependency tree that rots, and a JVM that will
still run this jar in ten years. The whole point is a thing you can forget about.

**"Will it scale?"**
No, deliberately, and it says so in its own mission. Everything is written for a few hundred rows
and reviewed on that assumption. If your group is 20,000 people this is the wrong software, and we
would rather you find that out here than in six months.

**"One process is a single point of failure."**
It is, and that is the deal a group of 200 people should want: operational simplicity is worth more
to them than nines they cannot measure. Say the cost out loud rather than hiding it.

**"How do I get my data out?"**
Every page and template comes out as one JSON file that merges back — the same file whether it goes
into another Hearth or into a git repository of markdown. Everything else is one H2 database file
in the directory you are already backing up. There is no export request, no queue, no email with a
link that expires.

**"Is this GDPR-ready?"**
It ships terms and a privacy policy that are treated as a specification rather than boilerplate:
every promise in them is a thing the code does. Show me what you hold, give me a copy, delete me —
each is a real button, reachable by the member themselves and by an administrator, and the deletion
is checked by walking every column of every table looking for the address afterwards. It is not
legal advice and the screen above the editor says so.

**"Can members break it?"**
Every account starts unapproved and cannot hold a session. Approval is the boundary. The bot
resistance on the sign-up form is deliberately not a boundary, and the documentation says so in
those words, because something that is only safe while an attacker has not read the page is not
safe.

### 4.9 Closing CTA

> **Try it against the checked-in example site.** Three communities, one command, no configuration.
>
> ```bash
> just run
> ```
>
> Then open `http://localhost:8080/register`, type any address, and the sign-in code prints in the
> terminal you started it from.

`Get the jar` → releases · `Read the manual` → MANUAL.md · `Have us run it` → page two

---

## 5. PAGE TWO — managed hosting

> **STATUS: this business does not exist yet.** MISSION.md records it as "noted and deferred". Every
> price, term and service level below is marked `DECIDE` and must not be published as a commitment.
> Ship this page as a waitlist with the argument on it, not as a product with a checkout.

### 5.1 The argument

The thing being sold is not the software — the software is free and the page says so above the
fold. What is being sold is the afternoon, the domain, the certificates, the upgrades, the backups,
and the one genuinely hard part: email that arrives.

### 5.2 Hero

> # We run the box. You run the community.
>
> The same jar, the same directory, the same escape hatch — on a machine you never have to think
> about, with email that lands in the inbox and certificates that renew themselves.
>
> Priced against a server, not against your members. It costs the same whether forty people show up
> or four hundred.

**Primary CTA:** `Join the waitlist` **Secondary:** `Or run it yourself, free, forever` → page one

### 5.3 The exit, stated before the price

This goes above the pricing on the page. It is the reason to believe everything under it, and
burying it turns the pitch into every other pitch.

> **You can leave, and we will help.** It is the same software you could have installed yourself.
> Your community is one directory: a database file, your certificates, your uploads. Ask and you get
> it — not an export ticket, the actual directory — and the jar that reads it is the one on the
> releases page.
>
> The mission this software is built to says *never a system that must keep existing for the
> community to keep existing*. A hosting company is exactly the thing that clause is suspicious of,
> so the answer is that leaving has to be genuinely cheap. If it ever stops being cheap, the company
> has drifted and the software has not.

### 5.4 What is included

- A machine, sized for a community of this size, which is not a large machine. `DECIDE: spec.`
- A domain, or your existing one pointed here. DNS handled. `DECIDE: registrar cost pass-through.`
- **Email that arrives.** The sending domain, SPF, DKIM and DMARC, reputation, and the inbound side
  so calendar replies work. This is the line item worth the most and the one people underestimate.
- Certificates, obtained and renewed, with nothing to remember.
- Upgrades: a new jar, staged and swapped, with the previous one kept.
- Backups, off the machine, and a restore that has actually been tested. `DECIDE: frequency,
  retention, and whether restores are self-service.`
- Somebody to email when it is broken. `DECIDE: hours, response target — and do not write "24/7"
  unless somebody is genuinely awake.`

### 5.5 What is not included, on purpose

> - **We do not moderate your community, and we will not.** No takedowns on our judgement, no
>   content policy of ours layered over yours, beyond what the law requires of the machine's owner.
> - **We do not touch your members' money**, because the software cannot. There are no tickets,
>   dues or tips to process. Pay the restaurant; Venmo the host.
> - **We do not sell anything to your members.** They are not our customers and never become them.
> - **We do not add features to your install that you did not ask for.** You run the same released
>   jar as everybody else.

### 5.6 The honest paragraph

Keep this. A hosting pitch that claims more than this is lying, and this audience can tell.

> **What hosting cannot give you is secrecy from your host.** Whoever holds the machine holds the
> database — that is true of us, and it is true of you if you run it yourself, and it is true of
> every other platform whether or not they say so. What we can promise is that we do not look, that
> nobody's data is sold or used for anything, and that leaving is one directory. If that is not
> enough for your group — and for some support groups it should not be — run it yourself. The page
> for that is one click away and we would rather you were there.

### 5.7 Pricing frame

Do not publish a number until one is decided. Publish the *shape*, which is the differentiated part:

> **Per server, per month. Not per member, not per event, not a percentage of anything.**
>
> A community of 400 costs what a community of 40 costs, because it is the same machine doing the
> same work. Nothing about growing gets more expensive, and nothing about your group's size is our
> business.

The comparison table is the strongest asset on this page. Every figure is somebody else's pricing
page and every one must be re-checked on the day of publication — see the claims register.

| | What it costs | What it is priced on |
| --- | --- | --- |
| **Hearth, self-hosted** | Free, MIT | your own machine |
| **Hearth, hosted** | `DECIDE` | one server, flat |
| Mighty Networks | from $95/month plus 2% of revenue `VERIFY` | your revenue |
| Meetup | $29.99/month to an organizer `VERIFY` | your group, plus members who pay to skip your queue |
| Eventbrite | 3.7% + $1.79 a ticket, plus 2.9% processing `VERIFY` | every ticket |
| Self-hosted Discourse | a 4GB two-core box and an SMTP provider `VERIFY` | what it takes to run it |

Framing line under the table, and it is the argument in one sentence:

> Every other row is priced on your community getting bigger. That is a business model with an
> opinion about what your group should become.

### 5.8 Hosting FAQ

**"Why would I pay you for free software?"**
You are not paying for the software. You are paying for a machine you do not maintain, a domain you
do not renew, certificates you do not think about, backups that have been restored at least once,
and email that arrives. If those are an enjoyable afternoon for you, run it yourself — genuinely,
that page is one click away.

**"What if you go out of business?"**
You get the directory and the jar, and both work without us. `DECIDE: whether to commit to a
notice period and an automatic final export, and then say the number here.`

**"Can we move to you from Mighty / Meetup / a Google Group?"**
`DECIDE.` Content import via the JSON bundle exists today; a member list import does not. Do not
imply either until it is built.

**"Who owns the data?"**
The community. `DECIDE: the terms, and have somebody qualified read them before this sentence is
published as a commitment.`

---

## 6. Honesty blocks

These belong on the page. On a page whose entire argument is that the software tells the truth
about itself, they are the proof rather than the caveat.

### 6.1 What is not built yet

Take the current list from [README.md](README.md)'s road — it moves, so link or regenerate rather
than freezing a copy here. As of this writing: the social leader made concrete, a phone-first pass,
the friction list, refresh tokens for MCP, two-factor beyond email, and telling somebody when they
have been approved.

### 6.2 The one to say out loud

> **The dashboard measures the wrong thing.** The stated measure is hours spent in each other's
> company, and the admin overview currently shows cache hit rates and who is online. Closing that
> means recording attendance *after* an event rather than only promising it before, and it is real
> work that has not been done. It is written down in the project's own notes as an open problem,
> which is where you would want to find it.

### 6.3 What has never been verified

> The project keeps a list of things nobody has proved wrong and nobody has proved right either,
> separately from its bugs — the release path, the mail validators against real mail, anything under
> concurrency. It is in the repository, in public, and it is a different kind of thing from a defect.

---

## 7. Reusable copy

**Meta description (155 chars):**
> A single jar that runs a small community — website, calendar, board, accounts, email — on your own
> machine. Built to get people into the same room.

**Social card:**
> Your community should not need a company to exist.
> One jar. One directory. 100 to 1,000 people. MIT.

**One-liners, for wherever one is needed:**

- The community software that is trying to get you out of the house.
- One fire, not a facility.
- Backup is copying a directory.
- It counts heads, not dollars.
- Frequency beats production value: six small gatherings beat one good one.
- A ten-year-old machine should serve this community faster than a SaaS platform serves it today.
- Not a platform. A community kit.

**For a technical audience specifically** (posting somewhere developers read):

> Hearth is a single-jar community server: Netty, embedded H2, Mustache, no build step, no CDN, no
> third-party request of any kind. Virtual hosts resolved from a flat directory of config files at
> boot into an immutable tree. Every write emits an event; caches invalidate off the bus and the TTL
> is only a backstop. Certificates over HTTP-01 answered by the same process. 1751 tests, most of
> which boot the real server and talk to it over a socket. MIT.

---

## 8. Claims register

Every number that appears on the page belongs here with its source. `VERIFY` means it was true when
written and is somebody else's page — re-check on the day of publication. The mission document
carries the full source list and says the same thing about them.

| Claim | Source | Status |
| --- | --- | --- |
| Half of American adults report considerable loneliness | U.S. Surgeon General's Advisory, *Our Epidemic of Loneliness and Isolation* (2023) | cite the advisory directly |
| Mortality effect comparable to smoking fifteen cigarettes a day | same | cite directly; do not round or embellish |
| ~50 hours to casual friendship, 90 to friendship, 200+ to close friendship | Hall, *Journal of Social and Personal Relationships* (2019) | say "roughly"; the paper does |
| Volunteer moderator PTSD, anxiety, depression, burnout | Schöpke-Gonzalez et al., *New Media & Society* (2024); Reddit moderator litigation reporting | attribute to the research, never to "studies show" |
| Dunbar's 150; ~50 active members typical; forums strain past ~80 contributors | Life With Alacrity on Dunbar | present as a design input, not a law |
| Free events lose 40–60% to no-shows; paid events see 70–90% attendance | Pheedloop Event Data Lab #06; whos-in.app benchmarks | `VERIFY` — weakest sourcing on the page; consider dropping the precise range and keeping the direction |
| Mighty Networks from $95/month plus 2% of revenue | vendor pricing page | `VERIFY` before publication |
| Meetup $29.99/month to an organizer | vendor pricing page | `VERIFY` before publication |
| Eventbrite 3.7% + $1.79 per ticket plus 2.9% processing | vendor pricing page | `VERIFY` before publication |
| Meetup+ subscribers jump the waitlist | Meetup help centre | `VERIFY`; it is the sharpest claim about a named competitor on the page |
| Self-hosted Discourse wants 4GB, two cores, separate SMTP | vendor install docs | `VERIFY` |
| 1751 tests | the suite; `just validate` checks this number against the surefire reports | kept true by the build |
| 100 to 1,000 people | MISSION.md | a design commitment, not a measurement |
| MIT licensed | [LICENSE](LICENSE) | true |

**Two rules for this table.** A competitor's price is a fact about a page that changes without
telling you, so anything comparative gets a "checked on <date>" line in the footer. And no claim
about Hearth goes on the site that is not already true in the software — the road exists for the
rest, and putting a roadmap item in the present tense is the one mistake that would cost this
project the audience it is written for.

---

## 9. Decisions needed from a human

Nothing on the hosting page can ship until these are answered, and none of them is a writing
problem.

1. **Price, and what a unit is.** Per server per month is the stated shape; the number is not set.
2. **The legal entity, the terms, and a DPA** — a support group is going to ask, and for that
   community it is a reasonable question rather than a procurement ritual.
3. **What "we do not look" means operationally.** Access control on the boxes, what is logged, who
   can read a database and under what circumstances. Do not publish the sentence in 5.6 until there
   is a real answer behind it.
4. **Support hours and a response target**, honestly stated.
5. **Backup frequency, retention, and whether a restore is self-service.**
6. **The exit commitment.** Notice period, and whether a final export is automatic. This is the
   promise the whole page rests on, so it should be the most specific thing on it.
7. **Whether the waitlist collects an email address**, and if so what the privacy notice on that
   form says — on this project's site, of all sites, that form is being read closely.
