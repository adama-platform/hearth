# Hearth — Mission

## What it is

**A content management system for a small community, in one jar.**

A website the community owns, a door somebody decides who comes through, and the mail that ties the
two together. One process, one directory, one machine, 100 to 1,000 people.

That is the whole claim. It is smaller than it sounds and much smaller than this project used to
claim, and both of those are on purpose.

## Why it is this small

This project spent a while being a platform. It had a discussion board, a calendar with RSVPs and
emailed invitations, an address book, an availability grid, a members directory, an invitation
funnel, projects, a training log, a live channel and a JSON API. About 26,000 lines of it, and all
of it worked.

It was removed anyway:

> **Surface you cannot validate is surface you cannot safely run.** Twenty-eight subsystems is not
> something one person operates for their friends. It is something a team keeps honest, and there is
> no team.

What is left is the part whose failure modes one person can enumerate. That is the constraint every
other decision here answers to, and it is worth more than any feature that has ever been proposed
for this codebase.

## Four commitments

**Small on purpose.** 100 to 1,000 people — an architecture, not a limit waiting to be lifted. One
database file, caches in memory, a ring buffer for the log, no sharding, no queue, no second
process. A ten-year-old machine should serve this community faster than a SaaS platform serves it
today, because they are doing a hundred things for a hundred thousand groups and we are doing one
thing for one.

**Cheap and boring to run.** One jar with an embedded database. Backup is copying a directory,
upgrade is replacing a file. If an operator cannot explain their whole installation in two
sentences, we have failed. **No money ever moves through Hearth** — that deletes PCI scope,
chargebacks, refunds and tax reporting, and the moment a platform holds a community's money it holds
the community.

**The old web, on purpose.** Pages that arrive whole. No framework, no build step, no bundle, no
third-party request of any kind. A URL is a place you can send somebody, and view-source is readable
by a curious teenager. That is also what makes it work on bad rural wifi, on an old phone, in a
waiting room — where the people we build for often are.

**Small enough to check.** The newest one, and the reason for the other three. Every feature here is
one whose failure modes fit in a single head. **The right answer to "this would be useful" is
sometimes "and it would be one more thing nobody is checking."**

## What is here

- **Accounts.** Sign in by emailed code, by password, or both. Every account waits for a human to
  approve it. Roles, permissions, bans, sessions with a cap.
- **A website.** Pages and templates the community writes, every save versioned as a whole document,
  directory indexes so a template behaves like a blog, and the whole site as one JSON file that
  merges back.
- **Files.** Photographs, video, the PDF of the menu. The extension decides what a thing is.
- **Email, both ways.** SES out in the community's colours; SMTP in with SPF, DKIM and DMARC checked
  and stamped. Nothing acts on an inbound message yet — it is received, authenticated and printed.
- **Push notifications** and an installable app.
- **TLS**, with certificates it obtains and renews itself.
- **A model endpoint.** MCP, off by default, offering content and template tools and nothing else.
- **Terms and a privacy policy**, published from day one, with export and erasure the policy can
  honestly describe.

## Where it is going

**Dynamic apps.** A page whose body is a program rather than a document — the first of it is here: a
JavaScript content kind with two functions, `render` and `meta`, a fresh V8 isolate per request, and
a second to finish in. It is small and it is fenced: no network, no storage, no way back into this
server, and no agent may write one.

That fence is the whole design so far, and it is the easy part. What comes next — state, request
data, anything an app would actually need — is where this stops being a CMS and starts being a place
to run code, and **that needs thinking about before it needs building.** The bar has not moved: can
one person enumerate how it fails.

## Never

Never a feed ranked by engagement. Never a place where the members are the product. Never a system
that must keep existing for the community to keep existing. Never so large that one person cannot
check it.

## The business, noted and deferred

The natural business is hosting: a machine, the software on it, domain and email as a monthly
service, priced against a VPS rather than a headcount. Explicitly out of scope, recorded only so the
software stays shaped in a way that would make it possible — one directory to back up, one command
to install, one file to move.
