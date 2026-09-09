# Security

This is one person's infrastructure on one machine. That shapes every decision below: the threat
model is not a hosting company's, the scale target is a handful of people, and simplicity is chosen
over generality wherever the simple thing holds at that size.

**There is no list of known-broken things here, and that is deliberate.** This repository has had a
`PROBLEMS.md` and an `AUDIT.md` and both were deleted, because a standing list of defects is a place
findings go to sit. A finding gets reproduced, fixed, and written into the comment above the clause
that fixes it and the javadoc of the test that proves it. What is in this file is the *model* — what
is being defended, against whom, and what an operator has to do that the software cannot do for them.
What is not defended is under [What this does not defend against](#what-this-does-not-defend-against),
and what has never been proven either way is in [CLAUDE.md](CLAUDE.md#not-verified).

## Reporting something

Open an issue, or email the address in the repository's git history. There is no bounty, no SLA and
no security team — there is one person. If you have found something that lets a stranger read
somebody's mail, act as somebody else, or take the machine, say so plainly and I will treat it as the
only thing that matters that week.

If it is exploitable and you would rather not open it in public, say only "I have something" in the
issue and we will find a private channel. Nothing here handles money, so there is no incentive to
sit on a finding.

## What is being defended, and from whom

| Who | What they can reach | What stops them |
| --- | --- | --- |
| **Anybody on the internet** | The public pages, the sign-in form, port 25, the ACME challenge, a calendar feed URL if they guess a 32-character random token | Approval is the boundary for the site; SPF/DKIM/DMARC and a closed rule set for mail; a hashed token for the feed |
| **Somebody with an account, not yet approved** | The account pages and `/self`, nothing else | `WebHandler`'s approval gate, against a closed list of reachable routes rather than "is this in the routing table" |
| **An approved member** | Their own gym log, tasks, votes, mail and calendar — never anybody else's | Every call uses the actor's id; there is no argument anywhere for *whose* |
| **An agent a member connected** | Exactly what its person can do, minus everything human-only, minus writes it is refused by name | `AiSurface`, once, for every tool |
| **An administrator** | The screens their permissions open, and no others | Section permissions, per-action permissions, 404 rather than 403 |
| **Whoever runs the machine** | Everything | Nothing. This is the trust boundary, and it is one person's own box |

The last row is the whole design. This is not multi-tenant hosting; the operator is the owner, and
the software does not pretend to defend against them.

## The rules that carry the most weight

Each of these is an invariant with a number in [CLAUDE.md](CLAUDE.md), a comment where it is
enforced, and a test that fails if it comes back. The numbers move when invariants are inserted; the
names do not.

**All bytes leave through one place.** `Responses` sets every security header, so there is one place
to be wrong rather than forty. `nosniff`, a referrer policy of "no-referrer", `SAMEORIGIN`, and a content security policy of
`default-src 'self'` with `form-action 'self'`, `base-uri 'self'` and `frame-ancestors 'self'`.
Inline scripts run by nonce, never `'unsafe-inline'`.

**Secrets are never stored in the form they are presented in.** Session tokens and calendar feed
tokens as SHA-256; passwords as scrypt. A stolen database file is not a list of logins or a list of
working calendar subscriptions. The two things that *cannot* be hashed — a Hevy API key and the DKIM
private key — are named as such, kept in one place each, and never printed back.

**A GET never writes** anything a program could reach, and every mutation is a POST with a
double-submit CSRF token. The one deliberate exception is that opening a message marks it read,
which is what a mail reader is for.

**Nothing is confirmed to somebody who should not know it exists.** A section an administrator may
not open answers 404, not 403. Somebody else's message answers 404. A revoked calendar feed and a
made-up one answer identically. Asking for a sign-in code looks the same whether or not the address
has an account.

**Nothing remote is ever fetched on anybody's behalf.** No CDN, no web fonts, no analytics, and — the
one that took work — no remote image in an email. Every external reference in a message is removed
and counted, so the screen can say how many. A member-supplied URL (a calendar link) is https-only,
resolved and refused if it points inside, no redirects, with a timeout and a ceiling.

**Untrusted input never reaches an identifier, a path or a command line.** SQL is prepared statements
everywhere; the one place a name is spliced is a user table's, and those names are validated and then
prefixed. An attachment's path is computed from a long. An SMTP recipient is checked with the same
address rule the inbound side uses at RCPT before it goes anywhere near `RCPT TO:`.

## Mail, which is the largest attack surface here

Receiving mail means accepting arbitrary bytes from strangers, all day, with no account and no rate
limit that matters. It is worth setting out separately.

**Inbound.** Refused at RCPT unless the domain has a config file — matched exactly, never by
wildcard, because a wildcard is an open relay built by accident. Bounded by arithmetic: a message
size ceiling, a recipient count, a connection cap, an idle timeout. SPF, DKIM and DMARC run on every
message and stamp `Authentication-Results` whatever the outcome; with `enforce-dmarc` on, a message
failing a published `p=reject` is refused rather than forwarded in this machine's name.

**Parsing.** `MimeTree` is lenient — a malformed message is still somebody's mail — and bounded:
depth, part count, and a hard stop on the number of parts listed. A parser that throws does not eat
the message; it is stored unparsed with its original on disk.

**Attachments.** A closed allow list by extension, the *last* extension rather than the first, and
the bytes have to agree with the name. Images are opened to their header only, so a nine-kilobyte PNG
that expands to fourteen gigabytes is refused before anything decodes it. Anything refused is still
*listed* with the reason. Served with the type this server chose, `nosniff`, as a download unless it
is an image that passed.

**The HTML half** goes through jsoup against a closed list with no `style`, no `script`, no `iframe`
and no form controls — a message that can position itself over the page can draw a sign-in form.

**Outbound.** A forwarded message is not modified, so the sender's signature survives. The return
path is rewritten (SRS) with a MAC, so an address this server did not write does not reverse — that
MAC is the only thing between a rewritten return path and an open relay, and it is compared in
constant time. Delivery refuses an exchanger that resolves inside the network, because a reply-all is
addressed from headers a stranger wrote and the MX for a domain they chose is a record they also
control.

**No bounces are ever generated.** The message goes out before the 250 comes back, and the far end's
verdict is what this server returns. That removes backscatter entirely: the sending server writes the
failure report, to the address it really sent from.

**A full mailbox is refused, never emptied.** Anybody on the internet can write to an address a rule
keeps, so both a count and a byte ceiling apply — and when they are reached the delivery gets a
temporary failure, which is what every mail server does and is much better than deleting unread mail
to make room.

## What an operator has to do

The software cannot check any of these from inside itself. `/admin/mail/setup` generates the DNS and
the Google Workspace half with your actual values in it; this is the rest.

### Before it faces the internet

- [ ] **Run it as a user that owns nothing else.** `--install` writes a systemd unit that asks for
      `CAP_NET_BIND_SERVICE` and bounds the capability set to it. It needs no root and starts
      nothing; read `install.sh` before running it.
- [ ] **Put the root somewhere only that user can read.** It holds the database, the certificates,
      what people uploaded, stored mail, and `config.cfg` — which contains the SES credentials, the
      SRS secret and the path to the DKIM key. `--setup-mail` writes `config.cfg` and the key `0600`;
      check the directory as well.
- [ ] **Set `admin_emails` and understand what it is.** Those addresses are administrators by fiat:
      approved, holding the admin role, and not revocable from inside the running system. That is
      deliberate — an escape hatch that reads the thing it rescues you from is not one — and it means
      the list is as sensitive as a password.
- [ ] **Turn on TLS,** and decide about `hsts-seconds`. It is `0` by default because HSTS is a
      one-way door: a browser that has seen it refuses plaintext for the whole window and there is no
      way to reach the people whose browsers already have it. `31536000` is right for a domain that
      is https and intends to stay that way. It is never sent over plaintext whatever it is set to.
- [ ] **Back up the root, and test restoring it.** A backup nobody has restored is a hypothesis.
- [ ] **Decide about `mcp.enabled`.** It is off by default and it is the only default here not tuned
      for a high-trust setting, because what it hands out is the ability to act as somebody.

### If you receive mail

- [ ] **Turn on `enforce-dmarc`.** It is off by default everywhere because the validators had never
      seen real mail; for a machine that *forwards*, passing on a message that failed its own
      domain's published policy means delivering it in your name, from your address. Watch the mail
      log for a fortnight first, then turn it on.
- [ ] **Publish SPF, DKIM and DMARC, and get reverse DNS** pointing at the name in `smtp.hostname`.
      `/admin/mail/setup` prints the exact records and the `dig` commands that check them from
      outside, which is the only vantage point where the answer means anything.
- [ ] **Keep `require-tls` on.** Off means a receiver that offers no STARTTLS gets your mail in the
      clear.
- [ ] **Know that `mail_route` is the sharpest permission on the list.** It can silently redirect
      somebody's post and it shows who has been writing to whom. Give it to nobody you would not give
      the mailbox to.
- [ ] **Watch the disk.** Stored mail is bounded per person, and the bound is generous.

### Once a quarter

- [ ] Read `/admin/system/ai` if agents are connected. It keeps the last 1,000 actions with arguments
      and results, which is a number a person can actually get to the bottom of.
- [ ] Read `/admin/people` and remove people who have left. Disabling stops their agent at its next
      request.
- [ ] Check `/admin/system/cleanup` after an upgrade. The schema upgrader never drops anything; a
      table the code stopped declaring is listed there for a person to drop deliberately.

## What this does not defend against

Said plainly, because a security document that implies completeness is worse than none.

- **The operator.** Whoever runs the machine can read everything on it. There is no encryption at
  rest, no key escrow and no separation between the operator and the data. For one person's own box
  that is the correct design; for anything else it is disqualifying.
- **A stolen root directory.** Passwords and session tokens survive it — they are hashed — but stored
  mail, uploads, the DKIM key and the SES credentials do not. Disk encryption is the operating
  system's job and is not attempted here.
- **A determined denial of service.** There are ceilings everywhere and no rate limiting worth the
  name. One machine, one person, one uplink: somebody who wants it down can have it down.
- **Traffic analysis.** The access log holds addresses and user agents in memory; a push notification
  crosses Google's or Mozilla's infrastructure and lands on a lock screen, which is why it says who
  and where and never what.
- **A malicious agent doing something a person could do.** An agent is held to exactly what its
  person can do. If that person can send mail, so can the agent. The defence is that the blast radius
  is a list you can read, and that every action is in the AI log.
- **Somebody who is already inside the network,** if `require-tls` is off or the destination does not
  offer STARTTLS.
- **A compromised dependency.** Everything vendored travels with its licence and is served from the
  jar rather than a CDN, which removes the CDN as a live attack surface but does nothing about a bad
  release of Netty, jsoup, H2 or Javet.

## Cryptography, and where it came from

Nothing here invents a primitive. What is implemented rather than imported is protocol framing
around library cryptography, and each has a reason:

- **scrypt** for passwords, from the library.
- **SHA-256** for session and feed tokens; `SecureRandom` for every token, nonce and secret.
- **RFC 8291 push encryption** and **VAPID**, checked against the published test vector.
- **AWS SigV4**, checked against Amazon's own worked example, because SigV4 fails closed and
  silently.
- **DKIM signing and verification** (RFC 6376) sharing one canonicalization, because two
  implementations agree until the day one of them handles a trailing space differently. The signer is
  checked against this repository's own verifier — which is the strongest cheap test available and is
  still two halves of one reading of the RFC. See [CLAUDE.md](CLAUDE.md#not-verified).
- **ARC** (RFC 8617), produced and never verified: a chain is started, never extended, because
  asserting `cv=pass` on arithmetic nobody did is a lie about a message that is probably fine.
- **SPF, DKIM and DMARC** verification, tested hard against the RFCs with a fake resolver and never
  against real mail.

## The documents

- **[MISSION.md](MISSION.md)** — why this exists and what it refuses to become.
- **[CLAUDE.md](CLAUDE.md)** — every invariant, why it exists, and what broke when it did not hold.
  The [Not verified](CLAUDE.md#not-verified) section is the honest list of what nobody has proven
  either way.
- **[README.md](README.md)** — the vision and the current state.
- **SECURITY.md** — this.
