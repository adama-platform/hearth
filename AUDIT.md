# Security audit

A running list of what a review of every feature turned up, what was done about each, and what was
looked at and found sound. The question being asked throughout is narrow and it is the one that
matters: **can somebody reach something they are not entitled to** — from outside with no account,
or as a valid member who simply presses a button nobody meant them to press.

Every finding here was reproduced from the outside first, with a test that fails before the fix and
passes after, and the reasoning was written into the comment above the clause that does the work.
This file is the index; the code is the record.

**Status:** first pass complete. Five findings, all fixed, all with tests. What was walked and found
sound is listed below too, because "we tried this door" is worth writing down — the next person
should not have to re-derive it. What has *not* been examined is at the bottom, and that list is the
honest part of this document.

---

## Findings

### 1. `/admin/content` — delete had no permission check at all

**Severity: high.** Privilege escalation for a valid member.

The `content` section opens for `content_read` — *"See pages and their history"* — which is the
mildest thing anybody on that screen can hold. Every button on the screen posts to the section's own
path, so `action=delete` inherited that permission: a member granted read-only access to the content
section could `POST /admin/content` with `action=delete&id=N` and remove any page on the site,
including the front page.

`save` and `suggest` were checked. `delete` was not, and nothing in between said so.

*Fixed:* `neededForContent` now names the permission for every action on that screen, following the
same shape `neededForPerson` and `neededForEvent` already use, and an action nobody listed requires
`everything` so a new button fails closed.

*Test:* `AdminActionPermissionTests.readingPagesIsNotPermissionToDeleteOne`

### 2. `/admin/content` — restore had no permission check either

**Severity: high.** Privilege escalation for a valid member.

Same door, worse button. `action=restore` reached `restoreVersion`, which performs an ordinary save
of an old version's whole document — and it checked nothing. A member with only `content_read` could
overwrite any page on the site with any earlier version of it.

*Fixed:* covered by the same `neededForContent` gate.

*Test:* `AdminActionPermissionTests.readingPagesIsNotPermissionToRestoreAnOldVersion`

### 3. Restoring a version was a way past `content_publish`

**Severity: medium.** Bypass of a permission the save path deliberately enforces.

A version is the whole page including its published flag (invariant 43), and `restoreVersion` wrote
that flag back without asking. The save path is careful about exactly this transition — it checks
`content_publish` *on the change*, so that a writer editing an already-live page is not "publishing"
anything — and restore went around it.

So somebody holding `content_write` but deliberately not `content_publish` could take a page down,
restore the version before it, and have it live again. The reverse also worked: restoring an
unpublished version was a way to take a live page down.

*Fixed:* restore now asks `content_publish` whenever the restored version's published state differs
from the page's current one, in both directions, and refuses the whole restore rather than silently
restoring the words without the flag — a partial restore that looked like a whole one is the kind of
thing nobody checks afterwards.

*Test:* `AdminActionPermissionTests.restoringMayNotPublishForSomebodyWhoMayNotPublish`

### 4. An operator-configured OAuth redirect prefix could name a hostname it did not mean

**Severity: high** where it applies. Authorization-code interception, from outside, with no account.

Where a model connector's authorization code may be sent is decided by an explicit prefix list
matched with `startsWith` — which is the right design, and has no idea where a hostname ends. The
prefixes this server ships all end in a slash and were never exposed to it. `mcp.extra-redirect-
prefixes`, which an operator adds when a connector moves, was stored exactly as typed.

So an operator writing the obvious thing — `https://connector.example.com`, a bare origin with no
trailing slash — also accepted:

```
https://connector.example.com.evil.net/callback     a different registrable domain
https://connector.example.com@evil.net/callback     the trusted name as userinfo, host is evil.net
https://connector.example.com-evil.net/callback
```

An authorization code delivered to any of those is an agent token handed to whoever owns that host,
and PKCE does not help — the attacker's server is the one redeeming it. Nothing about this needs an
account here: the flow is reachable by anybody once the connector is registered.

*Fixed:* a prefix with no path is normalized to end at the authority boundary (`https://host` →
`https://host/`), which is not a guess — a prefix naming only a host has always meant that origin.
A prefix that already names a path is left exactly as written, because `https://host/cb` also
covering `https://host/cbx` is a different page on a host that was already trusted. A prefix naming
no host at all is now fatal at boot.

*Test:* `RedirectPrefixTests` — including one that walks every shipped vendor prefix and asserts it
stops at a boundary, so the next vendor added cannot reintroduce this.

### 5. A forged calendar reply was authenticated whenever the sender's domain had no DMARC record

**Severity: high** where inbound mail is on. Acting as another member, from outside, with no account.

A calendar reply is a claim about identity arriving over SMTP, and invariant 157 is what stands
between that and fiction: the `ATTENDEE` in the file must be the sender, and the message must have
passed sender authentication. The second half was not doing its job.

The fallback for a domain publishing no DMARC policy read:

```java
return lower.contains("spf=pass") || lower.contains("dkim=pass") || lower.contains("=none");
```

The stamp this server writes always carries `dmarc=none` for a domain with no policy — which is
*exactly* the case the fallback exists to decide. So the third clause was true whenever the line was
reached, and the first two never got to matter. A message with `spf=fail; dkim=none; dmarc=none` was
authenticated.

That is: forge `From: member@their-personal-domain.example` on any domain without a DMARC record —
which the comment above the line correctly calls the common case for a personal domain — and the
RSVP lands. The attendee check passes too, because the attacker writes the `ATTENDEE` to match the
`From:` they chose. The UID needed is in any invitation that went out, and for an event opened to
the public it is in the `.ics` anybody can fetch.

*Fixed:* the fallback is now what its own comment says — either SPF or DKIM actually passing.
Nothing vouching for a message is not the same as nothing objecting to it, and a reply that cannot
be authenticated simply does not register, which leaves the nudge loop to ask that person again.
That is invariant 158 working as intended rather than a failure.

*Test:* `ReplyAuthenticationTests`. The suite was already 1730 green before the change and 1737
after, so nothing was relying on the loose reading.

*Checked while here and sound:* a sender cannot bring their own verdict. The server stamps its
finding onto the front of the message and `Envelope.headers()` keeps the first occurrence of a name,
so a forged `Authentication-Results` further down is never the one read. That is now a test rather
than a property of a `putIfAbsent` nobody was watching.

---

## Reviewed and sound

Recorded because "we looked at this" is worth as much as a finding, and because the next person to
read this file should not have to re-derive which doors were tried.

### Admin actions, section by section

Every `actOn*` handler was read against the permission its section opens for, looking for the shape
in findings 1–3: an action milder than the button it performs.

- **People** (`people_read`) — `neededForPerson` already maps every action; approve, reject, erase
  and the two admin-role buttons each ask for their own permission.
- **Events** (`calendar_write`) and **Suggested** (`calendar_review`) — `neededForEvent` maps every
  action; a reviewer cannot delete an event.
- **Suggestions** (`content_propose`) — approve and decline both ask `content_review` inside the
  handler; withdraw checks that it is the proposer's own.
- **Invites** (`invites_send`) — the bulk button asks `invites_bulk`.
- **Import & export** (`content_write`) — an import asks for `content_write` *and*
  `content_publish`, because a bundle whose rows say published would otherwise publish them.
- **Roles** (`people_roles`) — the escalation guard is present: nobody may grant a permission they
  do not themselves hold (invariant 124), which is what stops `people_roles` being the whole server
  by a longer route.
- **Bans**, **Templates**, **Directories**, **Files**, **Unused files**, **Survey**, **Retired**,
  **Board**, **Flagged**, **Places**, **Kinds**, **Appearance**, **Legal**, **Messages**, **AI** —
  in each of these the section's permission is the same one every action on it needs, so there is no
  gap to fall through.
- **Settings** and **Setup** (`config_write`) — every action on both is a settings write, and the
  catalogue contains nothing security-bearing, so the section permission and the action permission
  are the same thing.

### The member-facing surface

Looked at for direct object references: an id in a form or a path that is acted on without asking
whose it is.

- **`/self`** — every action is scoped to `me.id()`. Disconnecting an agent looks the id up among
  *that person's* agents rather than trusting it, which is the right shape and is commented as such.
  Erasure and export from here act on `me.id()` and take no id at all.
- **`/tasks`** — ownership is in the `WHERE` clause and asked once at the top of the route. An
  administrator gets the same 404 a stranger does on the member-facing path, which is invariant 254
  and is the strictest thing in the codebase.
- **Comments** — `CommentBox.act` checks that the comment's subject is the page's subject before
  editing or removing, so an id from one section cannot be used to moderate another (invariant 131).
  Editing is author-only in the DAO's `WHERE`, and removing needs either authorship or that
  section's own moderate permission.
- **Availability** — every window and link operation is keyed on `me.id()`; the grid that shows
  everybody needs `calendar_write`, and carries counts rather than names.
- **Notifications** — every read and every mark-as-read is scoped by `user_id` in the `WHERE`
  clause, so an id from somebody else's inbox matches nothing.
- **Polls** — a vote is cast as `me.id()` and asks `board_vote`; closing one asks separately.
- **Attachments** — private means signed in, approved and not disabled, checked in the route rather
  than inherited from wherever the link was; a private file answers 404, not 403, so its existence
  is not confirmed. The extension in the URL must match the row, so `/attachment/12.png` cannot
  serve the video that is actually row 12.

### The two doors for programs

- **`/api/v1`** — bearer only; the cookie is genuinely not consulted. The token must additionally
  carry the `api:` agent prefix, so a model's MCP token cannot be replayed here, and the person
  behind it must still be approved and not disabled. Reading content asks `content_write` and
  pushing asks `content_write` *and* `content_publish`, so a token cannot exceed its person.
- **MCP** — codes are memory-only, single use, bound to the client and redirect they were issued
  for, and PKCE S256 is required. `agent_connect` is re-checked on every call rather than at issue.
  Finding 4 is the one thing that was wrong, and it was in the list the code is checked against
  rather than in the checking.

### Redirects and hosts

- **`Landing.safe`** refuses a scheme, a host, `//host`, `/\host`, control characters, and the
  quoting characters that would matter when the value is echoed into a page as well as a header.
  Refusing rather than repairing is the right call and is what it does.
- **Canonicalization** — a name that is not the config's own domain answers 308 to the same path on
  the domain itself. The ACME challenge, the tracking pixel and `/3rd` are answered before it, which
  is necessary rather than an exception: an authority validating `www` fetches its token from `www`.

### Signing in

- Codes are compared in constant time, bound to the purpose they were minted for, expire, burn after
  the configured number of wrong guesses, and are single use. Asking for one is rate limited per
  address per hour.
- A code lives in memory only and never becomes a row, so a stolen database file is not a list of
  credentials in flight.
- `Landing.safe` governs where a sign-in returns to; approval still outranks it, so an unapproved
  person goes to their own page whatever they asked for.

### Multi-tenancy

- Every request resolves a config from the Host header and gets its account space from that. There
  is no path from one domain to another's data: the session token is looked up in that domain's own
  table, so a token from one host matches nothing at another that does not share its database.
- **Sharing a database is sharing everything**, deliberately: accounts, sessions, the login policy,
  the admin list, and — since settings moved — the community's own configuration. An administrator
  of a domain that shares another's database can rename it. That is not an escalation, because the
  owning domain's admin list already governs both and signing in at one already signs you in at the
  other; it is the same "one account space cannot have two answers" rule reaching one more table.
  Worth knowing before pointing `use_database_domain` at somebody you do not trust.

### The live channel

- A hub exists per domain, ignores events from any other, and only publishes for the allow-listed
  tables. A signal carries a table name and a row id and nothing else, and the client then fetches
  that row through the ordinary authorised path — so the live path never becomes a second and weaker
  way into the same data.

### Inbound mail

- **The relay refusal** happens at RCPT, before a body arrives, and only for a domain with a config
  file matched exactly — never by wildcard, because a wildcard would accept for every domain under
  a suffix and that is an open relay built by accident.
- **A calendar reply** is checked hard: the sender must be a member, the `ATTENDEE` in the file must
  equal the sender, the UID must name an event here, and the sequence must not be older than ours. A
  `COUNTER` is recorded as a suggestion and never applied, so one attendee cannot move everybody
  else's evening. Finding 5 was in the authentication predicate, not in any of these.

### Observations, not findings

- **`/admin/system/async`** opens for `system_read` and its three buttons re-queue geocoding work.
  That is a read permission driving outbound requests, which is worth noticing — but it moves no
  member data, cannot be aimed at a chosen address, and is bounded by the queue's capacity and its
  one-a-second rate limit. Left alone deliberately rather than overlooked.

---

## Still to review

- Sign-in end to end: enumeration, code brute force under the configured ceilings, session
  fixation, and what a lapsed session can still reach.
- Multi-tenancy: whether one domain can reach another's data, and what a shared database does to
  that question.
- The live channel: it carries row ids, and the claim is that every one names a row the recipient
  could already fetch.
- Push subscriptions: bound to a session, destroyed with it, and whether an orphan can outlive one.
