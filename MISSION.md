# Hearth — Mission

## What it is

A community kit. One jar, one machine, one group of people who mostly live near each other.

It exists to get those people into the same room more often than they otherwise would.

## What broke

Half of American adults report considerable loneliness. The Surgeon General's 2023 advisory put the
mortality effect of social disconnection on a par with smoking fifteen cigarettes a day — worse than
obesity — alongside raised risk of heart disease, stroke, dementia, depression and early death.

Big tech did not set out to do that. It optimized for attention, and attention is extracted from
the same hours that used to be spent with people. What scaled was what could scale, and connection
does not scale: it is made of specific people in specific rooms at specific times. So the software
got better at holding you still and worse at getting you out of the house, and the third places —
the pub quiz, the church hall, the club with a mailing list — thinned out underneath it.

Two jobs died with them, and they are the two this project is about.

**The organizer.** Someone has to pick a date, book the table, send the message, chase the
stragglers, greet the newcomer, and do it again next month when the thanks have worn off. That
person is usually one tired volunteer, and most groups do not die of conflict. They die because
nobody sent the message.

**The moderator.** This is the uglier half, and it is rarely stated plainly. The research on
volunteer moderators finds PTSD, anxiety and depression; secondary trauma from repeated exposure to
the worst things people write; and burnout as the main reason they quit. Their psychological
distress is comparable to crisis-line volunteers and caregivers. A former Reddit moderator's
lawsuit describes panic attacks and a PTSD diagnosis after three years of reading what came in.

We asked unpaid people to absorb the ugliest thing on the internet as a hobby, and were surprised
when small communities kept dying. Both jobs were unpaid and one of them was unbearable.

## What fixes it

Not a bigger platform. Two things that are only now possible together.

**Small tech.** Software cheap enough to run for fifty people does not need fifty thousand to pay
for itself. No growth imperative means no engagement optimization, no ranked feed, no advertiser,
no reason to keep anyone on the site. A single jar on a ten-year-old machine changes the economics
of a community enough to change its character.

**AI that takes the two jobs.** The organizing work nobody volunteers for can be done at 2am for
nothing. The moderation work that traumatizes people can be read first by something that cannot be
traumatized — so a human decides, but never has to meet the worst of it cold.

To be exact about the claim, because the honest version is strong enough:

> **AI does not fix loneliness. People do.** What AI can do is take the two jobs that made small
> communities unsustainable, so the people who would have run one are free to be in it instead.

## Who it is for

Adults with complicated lives — thirties and beyond, with jobs, kids, illnesses, and a shrinking
number of hours in which to be a person. They are not short of things to scroll. They are short of
reasons to leave the house, and somebody to organize it.

## Whose side it is on

Mighty Networks, Meetup and Eventbrite are not evil. They are optimizing for a different customer.
Mighty's is a creator monetizing an audience. Eventbrite's is a promoter selling tickets. Meetup's,
increasingly, is the member who pays for priority — Meetup+ subscribers jump the waitlist, so when a
place opens at *your* event the platform moves whoever paid *the platform* first. The queue in your
living room is being sold to somebody else.

**Hearth's customer is the group.** Everything else in this document follows from that sentence.

## How we know it is working

Not signups. Not daily actives. Not posts, replies or time on site — every one of those goes up when
a community gets worse.

**The measure is hours spent in each other's company.** Gatherings held, and how many came, and for
how long.

That is not a slogan; it comes from a number. Jeffrey Hall's research puts casual friendship at
roughly 50 hours of shared time, friendship at 90, and close friendship past 200 — and finds that
hours spent *working* together barely count. It has to be the good hours.

Run the arithmetic and it reorganizes the product. A monthly dinner of three hours is 36 hours a
year: five years to turn strangers into close friends. The same group meeting weekly gets there
inside a year.

> **Frequency beats production value.** Six small gatherings beat one good one. A standing Tuesday
> beats a quarterly event with a speaker.

So Hearth should push a group toward more, smaller, cheaper, easier occasions — and should make the
low-effort recurring thing the path of least resistance, rather than the impressive thing that
exhausts its organizer by March.

## Four commitments

### Small on purpose

100 to 1,000 people. An architecture, not a limit waiting to be lifted.

Dunbar's 150 is the ceiling on stable relationships; the observed mean for a functioning group is
around 50 active members, and forums start breaking down around 80 active contributors. A community
of 20,000 is not a community — it is an audience with a comment section.

Everything follows from that number. One database file. In-memory caches. A ring buffer for the
event log. No sharding, no queue, no second process. **A ten-year-old machine should serve this
community faster than a SaaS platform serves it today** — not because we are clever, but because
they are doing a hundred things for a hundred thousand groups and we are doing one thing for one.

When a group outgrows it, **the group splits**. That is what healthy communities do at this size
anyway, and it is a better answer than a bigger machine. Helping a group split well is a feature.

### Cheap and boring to run

Self-hosted Discourse wants 4GB, two cores and a separate SMTP provider. Mighty Networks starts at
$95/month plus 2% of revenue. Meetup charges an organizer $29.99/month. Eventbrite takes 3.7% +
$1.79 a ticket plus 2.9% processing.

Hearth is a single jar with an embedded database. Backup is copying a directory. Upgrade is
replacing a file. **If an operator cannot explain their entire installation in two sentences, we
have failed.** This is the hacker's way to run a community: you can read all of it, host all of it,
and carry all of it away in a tarball.

**No money ever moves through Hearth.** Not tickets, not dues, not tips. That refusal deletes PCI
scope, chargebacks, refunds, tax reporting and the whole class of feature that turns an organizer
into a merchant — and the moment a platform holds a community's money, it holds the community. Pay
the restaurant. Venmo the host. Hearth counts heads, not dollars.

### The old web, on purpose

Pages that arrive instantly. No framework, no build step, no bundle. A URL is a place you can send
somebody. View-source is readable by a curious teenager. No infinite scroll, no ranked feed, no
notification engineered to pull you back — the point is to get you *out* of the house, not back onto
the site.

This is not nostalgia; it is the commitment above seen from the member's side. A page that costs one
request works on bad rural wifi, on an old phone, in a waiting room. The people we build for are
often in all three.

### Local, and in person

Everything online here is scaffolding for something offline. The forum exists so the dinner happens.
The calendar exists so people show up. **If a feature makes the website better and the gatherings no
more frequent, it is the wrong feature.**

That is the editorial rule that keeps a kitchen sink from becoming slop. Hearth will have many
features — forum, calendar, profiles, invitations, surveys, content — because a real community
needs all of them. Each is admitted on the same test, and the test is never "the other platforms
have it".

## The friction thesis

The finding that shapes the product, and it is counterintuitive:

> Free events lose 40–60% of registrants to no-shows; paid events see 70–90% attendance. One-click
> registration increases signups and *decreases* commitment. A passive channel — the social-media
> "Interested" tap — converts worst of all.

**Making it easier to say yes produces more people who say yes and don't come.** So "reduce
friction" is the wrong goal. The right one:

> **An RSVP is a promise, not a bookmark. Attending should be effortless. Promising should cost
> something social.**

There is a second reason to sweat this, from the loneliness literature. The interventions with the
strongest evidence are not the ones that put people in a room — those help, but they come second.
The largest effects come from addressing the *thinking* that keeps people from connecting: the
expectation of being unwelcome, the certainty of standing alone by the door. Hearth cannot do
therapy. It can dismantle the specific thoughts that stop somebody coming, and every item below is
aimed at one of them.

**Make attending effortless.** The last hundred feet — which door, where to park, what the place
looks like, who to look for and what they will be wearing; more people bail outside an unfamiliar
bar than at the RSVP. A first-timer flag, so somebody is told a new person is coming and that it is
their job to say hello — *nobody will talk to me* is the thought, and being greeted by name at the
door is its refutation. Bringing somebody, as a first-class field, because arriving with a friend
turns a daunting night into an easy one. Rides: who is driving from where, which for an MS group is
not a nicety. Recurring anchors — "first Tuesday, always" removes the coordination cost entirely and
buys the repetition that friendship is made of. And asking when people are free before picking a
date, rather than announcing one half the group cannot make.

**Make the promise mean something.** Say yes in public: the guest list is names, not a number, and a
promise made in front of people is a different promise. Ask each person for one small thing — bring
the bread, book the table — because nothing raises attendance like being responsible for one item,
and a job to do is the best-known cure for not knowing what to do with your hands. Make "I can't"
one click and blameless, because an organizer needs the count more than the yes, and a group that
punishes changing your mind gets silence instead. Keep an honest attendance record — not to shame
anyone, but so an organizer knows the reliable core and a waitlist can be trusted. Send two
reminders and only two: the day before (*can I still make it?*) and the morning of (*where am I
going?*).

**Then close the loop.** The recap, the photos, who to talk to next time. An event is not over when
it ends; the thread is where the next one starts.

## The AI thesis

The industry has settled on AI-as-moderator in the narrow sense — flag the bad post, save a
community manager twenty hours a week. **Hearth has no community manager. That is the whole
opportunity.**

### The job nobody volunteers for

A social leader notices who has gone quiet and says something. Remembers who knows what, and
introduces them. Picks a date, books the table, sends the reminder, chases the stragglers. Welcomes
the newcomer and tells three people to say hello. Asks the question nobody wants to ask first.
Writes the recap. Notices a dying thread and revives it, or lets it go. Keeps the group's memory:
what we tried, who came, what worked.

Every one of those is work an LLM can do at 2am for nothing, and work the tired human at the centre
of a small community is currently doing badly or not at all.

### The job that hurts

The other half matters more and is talked about less. Someone in a support group posts something
frightening at 3am. Someone arrives to abuse a member. Someone describes a crisis.

A volunteer should not have to meet any of that cold, and the evidence says the cost of making them
is measured in panic attacks and PTSD. AI can take the first read. It can absorb the ugly thing,
summarize it without reproducing it, tell a human *what kind* of thing arrived and how urgent it is,
and hand over a decision rather than an image.

**The human still decides.** What changes is that they decide having been warned, not having been
ambushed — and that the fiftieth piece of abuse costs them what the first one did instead of
compounding. This is the single most humane thing AI can do in a community, and almost nobody is
framing it this way.

### The leash

A **standing brief**: the community writes down in its own words what the AI is for and what it is
not, as a document members can read and argue with — never a system prompt they never see.

**Memory of what happened, not of what was said in confidence.** Human-only content is invisible and
unwritable to AI, which in an MS group is not a preference but the condition on which people speak
at all.

**Ratification.** The AI drafts; a person sends.

### The hard refusals

Never impersonate a member — an agent acts as the person who authorized it, with a robot bit set,
and is never mistaken for that person afterwards. Never be the one who cares: an AI that sends the
sympathy note is worse than nobody sending one, and it can tell a human a note is needed without
being the note. Never be the voice of the group. Never be the reason somebody stayed home because
the website was enough.

### The agent bridge

MCP is how an agent reaches a tool; A2A is how agents talk to each other. Hearth speaks MCP today.

The interesting future is that members have *their own* agents, and a community is where those
agents negotiate on their owners' behalf — find the night four people are free, hold a place, accept
the invitation, arrange the lift. Hearth is neutral ground for that: not a platform that owns the
calendar, but a place where a group's agents meet under the group's rules, with every human able to
see what was agreed in their name.

## Three communities that have to work

Not examples — acceptance tests.

**KC Meat Up**, a carnivore supper club. Monthly dinners, local ranchers, 30–150 people most of whom
will never post. Wants recurring events, capacity that matches a restaurant table, an RSVP count a
restaurant can be told, and a forum alive between dinners.
*Tests the calendar, capacity, and whether a group survives the gap between events.*

**An MS support group.** Some members cannot drive; some cannot type on a bad day; all are sharing
medical details with strangers. The peer-support literature says screening matters, privacy matters
more, and *similarity* matters most — peers matched on age, time since diagnosis, life stage. Wants
approval that means something, human-only spaces, real accessibility, rides, and a way to miss three
months and still be a member.
*Tests approval, human-only, privacy, whether a low-energy member stays a member — and whether AI
can carry the frightening 3am post to a human without a volunteer reading it cold.*

**Meat and board games.** New friends around food and a table, 20–60 people. No cause, no shared
illness, no diet — just the wish to know people better.
*Tests whether Hearth can run a group with no cause, and whether it can get strangers past fifty
hours together.* The hardest of the three, and the one most worth passing.

## The road

**Built.** Accounts and approval, content with version history, a threaded forum, a calendar with
RSVP and waitlists, invitations with conversion tracking, notifications and digests, an MCP
endpoint, automatic certificates, real email.

**Next, in order.**

1. **ICS / iMIP** — events send real calendar invitations and *receive replies*. RFC 6047 defines
   REQUEST and REPLY over email: an invite that lands in Google Calendar, and an accept that comes
   back and updates the guest list without anybody visiting the site. The largest single friction
   removal available to us. Replies must be authenticated; anybody can forge an email.
2. **A board worth living in daily.** Chat was built and taken back out. Two rooms is one too many
   for a group of two hundred, and the one that dies is always the board — which is precisely the
   one that lets somebody who could not be online at nine take part as a full peer. For a support
   group whose members have variable energy that is not a preference, it is who gets a say. So the
   board keeps the ground, and what it has to earn is the frequency chat had.
3. **The social leader, made concrete** — the standing brief, the memory, the nudges, and the first
   read of the hard thing.
4. **A phone-first pass.** Most community activity happens on a phone. Hearth is a website; it had
   better be an excellent one in a car park.
5. **The friction list** — rides, date polls, recurring events, first-timer flags, the last hundred
   feet, and hours-together on the dashboard.

## Never

Never a feed ranked by engagement. Never a platform that sells position in a community's own queue.
Never a place where the members are the product. Never a system that must keep existing for the
community to keep existing. Never so large that a person becomes a number. Never so good at being a
website that it becomes a substitute for the room.

## The business, noted and deferred

The natural business is hosting: sell a machine, install the software, provide domain and email as a
monthly service — priced against a VPS, not against a headcount.

Explicitly **out of scope now**. Recorded only so the software stays shaped in a way that would make
it possible: one directory to back up, one command to install, one file to move.

---

*Load-bearing figures, with sources, because they should be re-checked before anyone repeats them:
loneliness prevalence and mortality — U.S. Surgeon General's Advisory, "Our Epidemic of Loneliness
and Isolation" (2023). Hours to friendship — Jeffrey A. Hall, Journal of Social and Personal
Relationships (2019). Volunteer moderator harm — Schöpke-Gonzalez et al., New Media & Society
(2024); Reddit moderator litigation reporting, Coda Story. Loneliness interventions — rapid
systematic review of 101 interventions (2025); meta-analysis of interventions to reduce loneliness.
No-show and registration friction — Pheedloop Event Data Lab #06; whos-in.app 2026 benchmarks.
Group size — Life With Alacrity on Dunbar. Platform pricing and features — Mighty Networks, Meetup
help centre, Eventbrite/SimpleTix (2026). MS peer support — IJMSC narrative synthesis systematic
review. iMIP — RFC 6047.*
