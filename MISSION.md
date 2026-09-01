# Hearth — Mission

## What it is

A community kit, reduced to its spine: **accounts, a website, and the mail that ties them together.**
One jar, one directory, one machine, one group of people who mostly live near each other.

It gives a small community a place of its own on the internet that it actually owns — a site its
members can write, behind a door somebody decides who comes through — without a company, a
subscription, or an afternoon of assembly.

## What it was, and why it is smaller

This project spent a while being much larger. It had a discussion board with polls and threaded
comments, a calendar with RSVPs and real emailed invitations, an address book, an availability grid
folded out of members' own calendars, a members directory, an invitation funnel with conversion
tracking, projects and a training log, a live channel, and a JSON API.

All of that worked. It was removed anyway, and the reason is the only one that matters for software
one person operates:

> **Surface you cannot validate is surface you cannot safely run.** Sixty thousand lines of features
> across twenty-eight subsystems is not a community kit; it is a platform, and a platform needs a
> team to keep honest. What is left is the part whose behaviour one person can hold in their head
> and check.

That is a retreat from ambition, and it is worth naming as one. The arguments below about loneliness
and about what small communities need are still true. This software no longer claims to answer all
of them.

## What broke, and what this can honestly do about it

Half of American adults report considerable loneliness. The Surgeon General's 2023 advisory put the
mortality effect of social disconnection on a par with smoking fifteen cigarettes a day.

Big tech did not set out to do that. It optimized for attention, and attention is extracted from the
same hours that used to be spent with people. The third places thinned out underneath it — the pub
quiz, the church hall, the club with a mailing list.

The club with a mailing list is the one this software is for.

**What Hearth does about it is narrow and it is real:** a group gets a website it owns, a way to
decide who is a member, and email that works. What they put on that website — when they meet, what
they are reading, who to call about the hall — is theirs to write. The organising happens wherever
that group already organises.

**What Hearth deliberately no longer does** is run the gathering. There is no calendar here, no
guest list, no board. Those are real needs and this is not the tool for them any more. A community
that wants them will use something else alongside this, and that is a better outcome than a tool
nobody can verify.

## Four commitments

### Small on purpose

100 to 1,000 people. An architecture, not a limit waiting to be lifted.

Dunbar's 150 is the ceiling on stable relationships; a functioning group averages around 50 active
members. A community of 20,000 is not a community — it is an audience with a comment section.

Everything follows from that number. One database file. In-memory caches. A ring buffer for the
event log. No sharding, no queue, no second process. **A ten-year-old machine should serve this
community faster than a SaaS platform serves it today** — not because we are clever, but because
they are doing a hundred things for a hundred thousand groups and we are doing one thing for one.

### Cheap and boring to run

A single jar with an embedded database. Backup is copying a directory. Upgrade is replacing a file.
**If an operator cannot explain their entire installation in two sentences, we have failed.** You
can read all of it, host all of it, and carry all of it away in a tarball.

**No money ever moves through Hearth.** Not tickets, not dues, not tips. That refusal deletes PCI
scope, chargebacks, refunds and tax reporting — and the moment a platform holds a community's money,
it holds the community.

### The old web, on purpose

Pages that arrive instantly. No framework, no build step, no bundle. A URL is a place you can send
somebody. View-source is readable by a curious teenager. No infinite scroll, no ranked feed, no
notification engineered to pull you back.

A page that costs one request works on bad rural wifi, on an old phone, in a waiting room. The
people we build for are often in all three.

### Small enough to check

The newest commitment, and the reason for everything above.

Every feature here is one whose failure modes one person can enumerate. When a feature cannot be
held in one head — when reviewing it honestly would take longer than an evening — it does not belong
in a jar somebody runs alone for their friends. **The right response to "this would be useful" is
sometimes "and it would be one more thing nobody is checking".**

## What is here

- **Accounts.** Passwordless sign-in by emailed code, or a password, or both. Every account waits
  for a human to approve it: proving you can read an address is not the same as belonging here.
  Roles and permissions, bans, sessions with a cap.
- **A website.** Pages and templates the community writes, every save versioned as a whole document,
  directory indexes so a template can behave like a blog, and the whole thing as one JSON file that
  merges back — which is what makes a git repository of markdown a way to keep this site.
- **Files.** Photographs, video, the PDF of the menu. The extension decides what a thing is and the
  browser's claim is thrown away.
- **Email, both ways.** Amazon SES on the way out in the community's own colours; SMTP in, with SPF,
  DKIM and DMARC checked and stamped. Nothing acts on an inbound message today — it is received,
  authenticated and printed.
- **Push notifications** and an installable app, with nothing yet that produces one but the
  self-test that proves delivery works.
- **TLS**, with certificates it obtains and renews itself over HTTP-01.
- **A model endpoint.** MCP, off by default, offering the content and template tools and nothing
  else — an assistant can write the site as the person who authorized it, and can do nothing that
  person could not.
- **Terms and a privacy policy**, published from the first day, with data export and erasure that
  the policy can honestly describe.

## The AI thesis, reduced to what is built

The larger version of this document argued that AI could take the two jobs that killed small
communities — the organizing nobody volunteers for, and the moderation that traumatizes whoever does
it. That argument is still one I believe.

**What is built is the smaller half of it:** a model can be handed the keys to a community's
website, under a leash that is short and readable. It acts as the person who connected it and can do
nothing they could not. Any page marked *human only* is invisible to it. Everything it does is
logged under two names — the person and the connector — and refusals are logged as loudly as
successes.

The moderation half needs a board, and there is no board. It is not deferred so much as out of
scope: it is a promise this software does not currently keep.

## Never

Never a feed ranked by engagement. Never a platform that sells position in a community's own queue.
Never a place where the members are the product. Never a system that must keep existing for the
community to keep existing. Never so large that a person becomes a number. Never so large that one
person cannot check it.

## The business, noted and deferred

The natural business is hosting: sell a machine, install the software, provide domain and email as a
monthly service — priced against a VPS, not against a headcount.

Explicitly **out of scope now**. Recorded only so the software stays shaped in a way that would make
it possible: one directory to back up, one command to install, one file to move.

---

*Load-bearing figures, with sources, because they should be re-checked before anyone repeats them:
loneliness prevalence and mortality — U.S. Surgeon General's Advisory, "Our Epidemic of Loneliness
and Isolation" (2023). Group size — Life With Alacrity on Dunbar.*
