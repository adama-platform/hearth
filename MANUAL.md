# Operating Hearth

Everything you need to run this thing. [README.md](README.md) is why it exists; this is how it works.

- [Running it](#running-it)
- [Installing it as a service](#installing-it-as-a-service)
- [The configs directory](#the-configs-directory)
- [Every config key](#every-config-key)
- [Databases and the stores path](#databases-and-the-stores-path)
- [Sharing accounts between domains](#sharing-accounts-between-domains)
- [Getting the first admin in](#getting-the-first-admin-in)
- [Approving people](#approving-people)
- [Writing content](#writing-content)
- [Templates](#templates)
- [Files people upload](#files-people-upload)
- [Asking the community questions](#asking-the-community-questions)
- [The dashboard](#the-dashboard)
- [The admin section](#the-admin-section)
- [Switching parts off](#switching-parts-off)
- [Events](#events)
- [Members](#members)
- [When people can come](#when-people-can-come)
- [Projects, routines and what you did](#projects-routines-and-what-you-did)
- [Geocoding](#geocoding)
- [Where members are, and how far they would travel](#where-members-are-and-how-far-they-would-travel)
- [Somebody's data, and getting rid of it](#somebodys-data-and-getting-rid-of-it)
- [What members see of each other](#what-members-see-of-each-other)
- [Triage: votes and flags](#triage-votes-and-flags)
- [Who can do what](#who-can-do-what)
- [Colours](#colours)
- [Terms, privacy and cookies](#terms-privacy-and-cookies)
- [Email](#email)
- [Caching and the event bus](#caching-and-the-event-bus)
- [Signing in from somewhere else](#signing-in-from-somewhere-else)
- [Security decisions you should know about](#security-decisions-you-should-know-about)
- [Backups](#backups)
- [Upgrading](#upgrading)
- [Troubleshooting](#troubleshooting)

---

## Running it

One jar, one directory:

```bash
java -jar hearth.jar --root /var/hearth
```

Everything lives under that root:

```
/var/hearth/
  config.cfg      the server: ports, TLS, limits. every setting has a working default
  domains/        one .cfg per virtual host, named for its domain
  dbs/            one database per domain
  certs/          the certificate authority account, and a key and chain per domain
```

The subdirectories are created for you. `config.cfg` is not — an absent one means every default,
which is a normal state.

| flag | meaning |
|---|---|
| `--root <dir>` | the only required flag |
| `--verbose`, `-v` | narrate the config scan and every request decision |
| `--check` | load everything, report, and exit; never opens a socket |
| `--help`, `-h` | usage |
| `--version` | print the version |

And the setup steps, each of which writes one file and then exits:

| flag | writes |
|---|---|
| `--setup` | `config.cfg` — ports, TLS, the clock, and whether to receive mail |
| `--domain-setup <domain>` | `domains/<domain>.cfg` — name, clock, admins, subdomains, mail, sign-in mode |
| `--setup-certs` | `certs/account.key` and `certs/account.json` |
| `--setup-email <domain>` | the `ses` block in that domain's config |
| `--test-email <domain> <to>` | nothing; sends one message and reports what happened |
| `--install <dir>` | a systemd service in that directory; needs no root and starts nothing |

> **Upgrading from the old flags?** `--configs`, `--stores`, `--certs`, `--port`, `http-port`,
> `https-port` and `bind` are gone, and each one refuses with a message saying where it went.
> They are not silently ignored, because a server quietly listening on a different port than your
> service file says is the worst possible outcome.

### Installing it as a service

On a Linux box with systemd — Ubuntu and Debian, most of the rest:

```bash
sudo mkdir -p /hearth
sudo chown $USER /hearth
java -jar hearth.jar --install /hearth
```

That writes five things and stops:

| | |
|---|---|
| `hearth.jar` | the server itself, copied in |
| `data/` | the `--root` directory: `config.cfg`, `domains/`, `dbs/`, `certs/` |
| `run.sh` | what systemd runs; it swaps in a staged jar on the way up |
| `<name>.service` | the unit, for you to read and disagree with |
| `install.sh` | the part that needs root: the user, the unit, and enabling it |

**`--install` never needs root**, and starts nothing. It writes into a directory you already own and
prints what it wrote. The half that does need root is `install.sh`, written out for you to read
*before* you run it — a program that wanted root to tell you what it was about to do would be one
you had to trust before you could check it.

The directory has to exist already. It is not created for you, because an install path is a decision
about where a community's database lives and a typo that silently makes `/hearthh` is a server
nobody can find later.

Then:

```bash
less /hearth/hearth.service /hearth/install.sh    # read them
sudo /hearth/install.sh
sudo -u hearth java -jar /hearth/hearth.jar --root /hearth/data --setup
sudo systemctl start hearth
journalctl -u hearth -f
```

**The service is named after the directory**, so `/srv/supper` is `supper.service` and two
communities on one box are two services somebody can tell apart.

**`install.sh` is safe to run again**, every time. It checks before each step: the group and the user
are created only if they are missing, `mkdir -p` and `chown` are safe twice, and `systemctl enable`
is idempotent. It never touches `data/` beyond creating it and setting its owner, so your config and
your databases are not in its way. It deliberately does **not** start or restart anything: starting a
server is something to do while watching, and restarting one that is already up is not what
"run the installer again" should mean.

**Ports 80 and 443 without running as root.** The unit asks for `CAP_NET_BIND_SERVICE` and nothing
else, and bounds the capability set to that one — so the server can bind low ports and can do
nothing else privileged, which is a smaller thing to trust than "starts as root and drops it".

#### Deploying a new version

```bash
scp hearth.jar you@box:/tmp/
sudo install -o hearth -g hearth -m 0644 /tmp/hearth.jar /hearth/hearth.new.jar
sudo systemctl restart hearth
```

`run.sh` finds `hearth.new.jar` on the way up, checks it really is a jar, keeps the one it replaces
as `hearth.prev.jar`, and swaps it in. So **rolling back is one move and a restart**:

```bash
sudo -u hearth mv /hearth/hearth.prev.jar /hearth/hearth.new.jar
sudo systemctl restart hearth
```

Running `--install` again does the same thing: if the jar it is installing differs from the one
already there, it **stages** it rather than overwriting, and says so. Overwriting the jar a running
service came from works on Linux, and it means the file on disk and the process in memory are
different software — which is the state every confusing incident report starts in.

A half-finished upload cannot replace a working server: `run.sh` checks the staged file starts with
the two bytes every jar starts with, and leaves it alone if not.

#### Settings for the service

`/hearth/env` is optional and read by systemd if it is there:

```
JAVA_OPTS=-Xmx512m
JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
```

The java that ran `--install` is baked into `run.sh` as the default, so a box with three JDKs starts
the right one.

### First run

```bash
java -jar hearth.jar --root /var/hearth --setup
java -jar hearth.jar --root /var/hearth --domain-setup example.org
java -jar hearth.jar --root /var/hearth
```

`--check` is what you run before restarting a live server, or in CI:

```bash
java -jar hearth.jar --root /var/hearth --check
```

It exits non-zero on any problem, so it works as a gate.

**Running a walkthrough again is safe.** Both of them read the file they are about to rewrite and
offer what is already there as the default for every question, so pressing return through a second
run changes nothing. That matters because the alternative is a trap: it looks like a review and
behaves like a reset.

### Binding 80 and 443

Linux reserves ports below 1024 for root, and Hearth should not run as root — it reads a config
directory and writes databases, and neither wants that authority. Grant the ports instead of the
privilege:

```bash
# /etc/sysctl.d/10-unprivileged-ports.conf
net.ipv4.ip_unprivileged_port_start = 80
```

```bash
sudo sysctl -p /etc/sysctl.d/10-unprivileged-ports.conf
```

Start the range at 80 rather than 0. Everything from 80 up becomes bindable by any user, so the
lower number you pick, the more services you hand over — 22 and 53 stay protected at 80, and there
is no reason to give them away to serve a website.

The alternatives are worse for this shape of program. `setcap` on the `java` binary grants the
capability to *every* JVM on the box, not just this one. A systemd unit with
`AmbientCapabilities=CAP_NET_BIND_SERVICE` is the right answer once Hearth is a service, and is the
one to revisit then; the sysctl is what works when you are running the jar by hand.

Opening the port on the host is separate from opening it on the firewall:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

Both are wanted: 80 is the site and the certificate challenge, 443 is TLS once `enable-https` is
on. See [Certificates](#certificates).

### During development

The `justfile` is the interface. `just validate` is the gate — clean build, full test suite,
packaged jar, then a live smoke test against the running jar.

```bash
just              # list every recipe
just validate     # the gate; run it before you push
just run          # serve ./configs into ./stores on 8080, verbose
just reset-stores # delete the local databases and start over
just check DIR    # validate somebody's config tree
just coverage     # jacoco; fails below 80% line / 70% branch
just peek HOST    # dump headers and body for one host
```

### Boot order

Arguments, then configs, then templates, then databases, then the socket. Nothing serves traffic
until all of it succeeded, and any problem stops the server rather than being logged and shrugged
at. A half-applied configuration is worse than a server that refused to start.

---

## The domains directory

Flat. One JSON file per virtual host, named for the domain it configures:

```
/etc/hearth/
  localhost.cfg
  example.org.cfg
  junior.example.org.cfg
```

`ls /etc/hearth` is the list of every domain this server will serve. The filename **is** the domain —
there is no `domain` key inside the file, because two sources of truth for one fact is how they
drift apart.

Directories inside the configs directory are ignored. There is deliberately nowhere to put content
on disk: pages, templates and icons all live in the database.

### How a request finds its config

At boot the filenames become a tree of DNS labels rooted at the top level domain:

```
(root)
  +-- org
  |     +-- example          <- example.org.cfg
  |           +-- junior     <- junior.example.org.cfg
  +-- localhost              <- localhost.cfg
```

A request descends from the top level domain as far as its labels allow and takes the **deepest
config that applies**:

| request | resolves to | why |
|---|---|---|
| `junior.example.org` | itself | exact match |
| `example.org` | itself | exact match |
| `www.example.org` | `example.org` | that config sets `wildcard: true` |
| `api.localhost` | nothing → 404 | `localhost.cfg` sets `wildcard: false` |
| `org` | nothing → 404 | a junction; no config lives there |
| `anything.else` | nothing → 404 | no config at all |

An exact name always matches. A less specific config only covers subdomains if it sets
`wildcard: true`. Nodes with no config of their own — `org` above — are junctions: they exist
because something lives under them and they serve nothing.

### Naming subdomains instead of wildcarding

`subdomains` is the middle setting, and usually the one you want:

```json
{ "wildcard": false, "subdomains": ["www", "blog"] }
```

Those are **labels**, not hostnames — `"www"`, never `"www.example.org"`, which would be read as
`www.example.org.example.org` and is refused at boot. Each one resolves to this same config: the
same community, the same database, the same accounts. Nothing else under the domain resolves at all.

The reason to prefer it over `wildcard: true` is the certificate. Hearth validates over HTTP-01,
which **cannot get a wildcard certificate** — so a wildcard domain serves its subdomains over plain
HTTP forever, and you find out from a browser warning. A named subdomain is ordered along with the
domain itself, so `https://www.example.org` works the day it is listed.

Two configs cannot claim the same name. If `example.org` lists `junior` and `junior.example.org.cfg`
also exists, the server refuses to start with `subdomain conflict` rather than picking a winner.

### Every other name redirects to the primary one

A community has one address. Anything that resolves to a config under a name that is *not* that
config's own domain — a listed subdomain, or anything a `wildcard: true` swept up — is answered with
a `308` to the same path on the domain itself:

```
GET /events?month=3   Host: www.example.org
  -> 308  Location: https://example.org/events?month=3
```

It keeps the **scheme** (an https request is never sent to http, and an http one is never sent to a
domain whose certificate has not been issued yet), the **port** (a developer on 8080 is not sent to
443), and the **path and query** (the point of a redirect is that the link still works).

Two live spellings of one community means two sets of links people paste to each other, two entries
in a search engine, and — because a session cookie is scoped to a host — signing in at one leaving
you signed out at the other.

`308` rather than `301` because it preserves the method: a browser following a `301` after a POST
turns it into a GET and silently drops the form.

**The certificate challenge is never redirected.** An authority validating `www.example.org`
fetches its token from `www`, so `/.well-known/acme-challenge/…` is answered before any of this.
Same for the invitation pixel and `/3rd`.

`--verbose` narrates the whole descent for every request:

```
... GET / host=a.b.example.org from /127.0.0.1:41288
      descend org -> junction, no config here
      descend example.org -> config (wildcard) example.org.cfg
      descend b.example.org -> no such branch; stopping
      most specific match: example.org
```

### What stops the server

- a filename that isn't a valid lowercase domain (`Example.ORG.cfg` is rejected, not folded)
- an unknown key in a config — a typo is a policy that didn't get applied
- malformed JSON, or JSON that isn't an object
- a symlink anywhere in the configs directory
- two `urls` pointing at the same path

---

## config.cfg

The server itself. Every key has a working default, so the file is optional and usually short.

| key | default | meaning |
|---|---|---|
| `http-port` | `80` | plain HTTP; always listening |
| `enable-https` | `false` | terminate TLS with the certificates in `certs/` |
| `https-port` | `443` | only used when `enable-https` is true |
| `http2` | `true` | offer HTTP/2 over TLS; anything that cannot gets HTTP/1.1 |
| `compact-html` | `true` | take the template's own whitespace out of every page |
| `enable-http-bounce` | `false` | run a listener that only redirects to https |
| `http-bounce-port` | `9999` | where it listens |
| `bind` | `0.0.0.0` | `127.0.0.1` to stay on this machine |
| `timezone` | the machine's | the clock every community here keeps unless it says otherwise — see [below](#the-clock) |
| `max-request-bytes` | `1048576` | a request larger than this is a 413 |
| `idle-seconds` | `60` | drop a connection that goes quiet |
| `verbose` | `false` | narrate everything; `--verbose` also turns it on for one run |

Unknown keys are refused at boot. A server that ignored `htp-port` and listened on 80 anyway would
look like it worked.

### The clock

```json
{ "timezone": "Europe/London" }
```

**An IANA zone id** — `Region/City`, with an underscore for a space: `Europe/London`,
`America/New_York`, `America/Argentina/Buenos_Aires`, `Australia/Sydney`. `UTC` works. An
abbreviation (`EST`, `BST`) or an offset (`+01:00`) does not, because neither of them knows when the
clocks change.

This is a program for people who meet in a **room**, so "Tuesday evening" is a fact about a place
rather than about UTC. It decides:

- what **today** is — which events are upcoming, which have happened, what the dashboard's next
  seven days are;
- every hour on the [availability grid](#when-people-can-come), and which occurrences of it fall
  inside the horizon;
- how an **all-day entry** and a floating time in somebody's calendar are read.

It defaults to whatever the machine is set to, which is right on a box somebody set up for their own
community and wrong on a rented one in another continent — where every evening would be a few hours
out and it would look like a bug in the calendar.

**A community can override it**, and that is the point of having it in two places: one box happily
hosts a supper club in Bristol and a support group in Toronto. A domain that says nothing inherits,
and `--domain-setup` writes nothing when you accept the default, so the file stays about what
differs.

A zone that is not one **stops the server**, naming the file and the value. `--setup` and
`--domain-setup` ask again rather than writing it, because the failure this prevents is a server
that will not start after somebody has walked away from the terminal.

`/admin/system/settings` shows both — the server's and this community's — with **what time it
actually is** beside the id, which is the one thing that makes `America/Indiana/Indianapolis` versus
`America/Indianapolis` obvious at a glance.

---

## Every domain config key

A minimal config is `{}`. Everything below has a default.

```json
{
  "name": "Example Community",
  "enabled": true,
  "wildcard": false,
  "subdomains": ["www"],
  "accepts-mail": true,
  "use_database_domain": null,
  "admin_emails": ["you@example.com"],
  "login_security": { },
  "cache": { },
  "urls": { }
}
```

### Top level

| key | default | meaning |
|---|---|---|
| `name` | the domain | display name for the community, shown in the header and in email |
| `enabled` | `true` | kill switch; a disabled domain looks unconfigured from outside (404) |
| `wildcard` | `true` | does this config also cover subdomains with no config of their own |
| `subdomains` | none | labels this config also answers for — see [above](#naming-subdomains-instead-of-wildcarding) |
| `accepts-mail` | `true` | may inbound mail be accepted for this domain — see [below](#receiving-email) |
| `use_database_domain` | none | share another domain's database — see [below](#sharing-accounts-between-domains) |
| `admin_emails` | none | addresses that are admins by fiat — see [below](#getting-the-first-admin-in) |
| `timezone` | whatever `config.cfg` says | this community's own clock — see [below](#the-clock) |
| `units` | `metric` | `metric` for kilometres, `imperial` for miles — see [below](#miles-or-kilometres) |

### `login_security`

Defaults are tuned for a **high trust community**: a few hundred people who mostly know each other,
where the realistic threat is a stranger finding the site rather than a targeted attack. Every one
of them tightens with a line of JSON.

| key | default | meaning |
|---|---|---|
| `mode` | `passwordless` | `passwordless`, `password`, or `password_and_code` |
| `session-lifetime-seconds` | `0` | `0` means a session does not expire on its own |
| `session-idle-seconds` | `0` | dead after this long untouched; `0` means never |
| `max-active-sessions` | `0` | ceiling per person; `0` means no ceiling |
| `max-active-sessions-grace-seconds` | `1800` | sessions younger than this are never reaped for being over the ceiling |
| `session-cache-max` | `1000` | sessions held in memory before the coldest are evicted |
| `session-cache-ttl-seconds` | `3600` | how long an untouched session stays cached |
| `signup-ip-days` | `90` | how long the IP address a sign-up came from is kept; `0` keeps it forever |
| `reaper-interval-seconds` | `300` | how often dead and surplus sessions are swept |
| `code-length` | `6` | digits in an emailed code |
| `code-lifetime-seconds` | `600` | how long a code is good for |
| `code-max-attempts` | `5` | wrong guesses before a code burns |
| `code-requests-per-hour` | `10` | codes one address may ask for |
| `lockout-threshold` | `10` | failed sign-ins before an account locks |
| `lockout-seconds` | `900` | how long it stays locked |
| `password-min-length` | `12` | only when passwords are in play |
| `cookie-name` | `hearth_session` | |
| `cookie-secure` | `false` | **turn this on when you have TLS** |
| `cookie-same-site` | `Lax` | `Lax`, `Strict`, or `None` (which requires `cookie-secure`) |

**Passwordless is the default and it is the recommendation.** A password nobody has is a password
nobody can leak, reuse, or need reset.

The three modes, in full:

| mode | signing in |
|---|---|
| `passwordless` | give an address, receive a code, enter it. The mailbox is the credential. |
| `password` | give an address and a password. |
| `password_and_code` | give an address and a password, **then** a code mailed to that address. |

`password_and_code` is real two-factor: the right password on its own hands out nothing — no cookie,
no redirect, nothing that survives the request — and the code is a separate proof. A *wrong*
password never reaches the second step and never sends mail, so the mailbox cannot be used as a
doorbell by somebody guessing. An address that does not exist is answered exactly like a wrong
password, so the pause before the code is not a way to find out who has an account.

Both password modes ask for a password when the account is created, alongside the code that proves
the address. Set `password-min-length` to what you actually want; the default of 12 is a floor, not
a recommendation.

**Sessions never expire by default**, because logging your neighbours out every week to defend
against nothing is how you get them to stop coming. Bound it with a cap rather than a clock:

```json
"login_security": {
  "max-active-sessions": 4,
  "max-active-sessions-grace-seconds": 1800
}
```

Four sessions that stick, infinite lifetime, and a fifth sign-in never knocks anybody out mid-task —
the surplus is collected once the newcomer has settled, oldest first.

For a business that needs real isolation:

```json
"login_security": {
  "mode": "password",
  "session-lifetime-seconds": 28800,
  "session-idle-seconds": 3600,
  "max-active-sessions": 2,
  "lockout-threshold": 5,
  "password-min-length": 16,
  "cookie-secure": true,
  "cookie-same-site": "Strict"
}
```

### `cache`

One catch-all, and per-cache overrides that inherit from it:

```json
"cache": {
  "ttl-seconds": 3600,
  "max-entries": 1000,
  "rendered": { "ttl-seconds": 600 }
}
```

| cache | holds |
|---|---|
| `content` | content rows, keyed by uri |
| `rendered` | finished page bytes, keyed by uri |
| `templates` | compiled templates, keyed by name |

Each takes `enabled`, `ttl-seconds` (`0` means no expiry) and `max-entries`. See
[Caching and the event bus](#caching-and-the-event-bus) for why the TTL is a backstop rather than
the mechanism.

### `urls`

| key | default | what lives there |
|---|---|---|
| `register` | `/register` | create an account |
| `login` | `/login` | sign in |
| `logout` | `/logout` | sign out (POST only) |
| `forgot-password` | `/forgot-password` | ask for a reset code |
| `reset-password` | `/reset-password` | choose a new password |
| `admin` | `/admin` | the admin section, and everything under it |
| `home` | `/home` | the dashboard a signed-in member lands on |
| `self` | `/self` | somebody's own profile, inbox and settings |
| `survey` | `/survey` | this community's questions, and what somebody has answered |
| `orientation` | `/welcome` | the first few minutes: a name, then the questions |
| `members` | `/members` | the directory of who else is here |
| `board` | `/board` | the discussion board, and every thread under it |
| `calendar` | `/events` | the calendar, and every event under it |
| `places` | `/places` | the address book |
| `after-login` | `/home` | where a successful sign-in lands |

`/legal` lives outside this table, fixed, because every email links to it. So does `/`, which is
whatever the community put there and is never one of these.

**`/` and `/home` are different pages on purpose.** `/` is the community's own front page — a
content page if they wrote one, the built-in greeting if they have not — and most communities will
aim it at somebody who is not a member yet. `/home` is the dashboard: what is waiting for that
person, the next seven days on the calendar, and the conversations they are in. The bar's "Home"
points at the first for a stranger and the second for a member. Signing out always goes to `/`.

These are the only paths that accept a POST. Two pointing at the same path stops the server.
`admin` owns everything beneath it, so `/admin/people`, `/admin/system/events` and the rest are its
sub-pages.

---

### `mcp`

Whether this domain lets a model act on it. **Off by default** &mdash; every other default here is
tuned for a high trust community, and this one is not, because what it hands out is the ability to
rewrite the site.

| key | default | meaning |
|---|---|---|
| `enabled` | `false` | serve the model endpoint on this domain at all |
| `path` | `/mcp` | where the endpoint lives; must not collide with an account page |
| `vendors` | `["grok"]` | which connectors may register: `grok`, `claude`, `chatgpt`, `custom` |
| `extra-redirect-prefixes` | `[]` | additional `https://` prefixes codes may be sent to |
| `dynamic-registration` | `true` | may a connector register itself, or must an admin add it |
| `read-only` | `false` | when true, agents can look but not change anything |
| `token-lifetime-seconds` | `0` | `0` follows the domain's `session-lifetime-seconds` |
| `code-lifetime-seconds` | `120` | how long an authorization code is good for |

A domain with `mcp.enabled` and no `admin_emails` refuses to start: connecting requires an admin to
click approve, and there would be nobody who could.

---

## Sending real email

Without this, codes and links print to the terminal the server runs in — fine for a development box,
useless for anybody else. Hearth speaks to **Amazon SES** directly, with no AWS SDK: it is one signed
POST, and forty megabytes of dependency to make it would be a poor trade for a single-jar server.

```bash
java -jar hearth.jar --root /var/hearth --setup-email example.org
java -jar hearth.jar --root /var/hearth --test-email example.org you@example.com
```

### Before it can work

Two things have to be true at Amazon, and the second one catches people out:

1. **The sending address, or its whole domain, is verified in SES.**
2. **Your account is out of the SES sandbox** — or every *recipient* is verified too. In the sandbox
   SES cheerfully accepts the send and delivers nothing.

An IAM user for this should be able to do exactly one thing, `ses:SendEmail`. That turns a leaked
key from a disaster into a nuisance.

### The `ses` block

Per domain, because the sending address has to belong to the community whose name is on the message.
A code from "Example Community" arriving from `no-reply@somewhere-else` is the shape a phishing mail
has.

| key | default | meaning |
|---|---|---|
| `enabled` | `false` | when false, codes print to the terminal |
| `access-key-id` | | required when enabled |
| `secret-access-key` | | required when enabled |
| `region` | `us-east-1` | must be the region the key belongs to |
| `from` | | required; the address SES has verified |
| `from-name` | the community's name | what the recipient sees as the sender |
| `reply-to` | the `from` address | where replies go |

> **The credentials are in that file in the clear.** There is nowhere else for them in a single jar
> with no secret store, so `--setup-email` writes the file `0600` and the boot report names which
> domains have keys. Back it up accordingly, and keep the IAM user narrow.

### When it does not arrive

`--test-email` sends one real message and reports exactly what SES said. "Accepted" is not
"delivered" — if nothing arrives, the causes in order are the sandbox, the spam folder, an
unverified sender, and a key belonging to a different region than the config names.

Domains with no `ses` block keep printing to the terminal, which is the normal state of a box where
one community is live and another is being set up.

---

## Connecting a model

The short version: turn it on, open your assistant's connector settings, paste
`https://yourdomain/mcp`, and approve the screen it sends you to.

```json
{
  "name": "Example Community",
  "admin_emails": ["you@example.com"],
  "mcp": { "enabled": true, "vendors": ["grok"] }
}
```

What happens, in order:

1. The connector reads `/.well-known/oauth-protected-resource`, which points at this domain as its
   authorization server, and then `/.well-known/oauth-authorization-server`, which says what the
   flow supports. Nothing has to be configured by hand on their side.
2. It registers itself at `/mcp/register`, naming the address it wants codes sent back to. **That
   address must already be one this domain trusts** or the registration is refused.
3. It sends you to `/mcp/authorize`. You have to be signed in and to hold **Connect an assistant
   that acts as you** (`agent_connect`); the screen names the connector, what *you specifically*
   will be able to do through it, and what nobody can. If you are not signed in, you get the login
   page and come **back to the consent screen** afterwards — the connector's popup is waiting on
   it, so landing anywhere else means the connection never completes.
4. You approve, and a code goes back to the connector, which trades it for a token at `/mcp/token`
   using PKCE.
5. The token is a session belonging to **you**, with a robot bit set and the connector's name on it.

### Anybody can have one, if you say so

This used to be admins only. It is now the `agent_connect` permission, which an administrator puts
in a role and grants like any other — so a community can let its members bring their own assistants.
The mission for that is a group of friends where some of the organising is done by agents: one puts
a question to the board, people and other agents answer it, and the answer becomes an evening.

**It is deliberately not automatic.** Being approved is enough to read and post and vote; it is not
enough to have a connection, because a connection is a standing credential held by *somebody else's
software* that can act as that person for a month. That is a decision worth making per person.

It is also not an admin power. `agent_connect` opens no screen — it does not imply `admin_enter` —
because what an assistant can do is exactly what its person can do. `ai_manage` is the separate
thing: the screen that lists every connector in the community and revokes one.

**Everybody who has one can see and remove it themselves**, on their own page under **Assistants**.
Disconnecting deletes the session, so it stops working at its next request.

Taking the permission away stops an existing agent at its next request too, rather than at the end
of the month when its token would have expired anyway — it is re-checked on every call.

### If the connector's callback is refused

Vendors move their callback URLs, and the ones shipped here are a starting point rather than
gospel. The refusal names the address that was rejected; add its prefix and restart:

```json
"mcp": {
  "enabled": true,
  "vendors": ["grok"],
  "extra-redirect-prefixes": ["https://some.new.host.example/oauth/"]
}
```

Prefixes are matched exactly, must be `https://`, and there are no wildcards. That is deliberate:
an authorization code sent to the wrong address is an agent token handed to whoever owns it, and
every clever matching rule in this space has been somebody's advisory.

### What a connected model can do

| tool | what it does |
|---|---|
| `content_list`, `content_search`, `content_get` | read the site |
| `content_save`, `content_delete` | write pages; a save changes only the fields it mentions |
| `navigation_get` | the folder tree, and which pages sit outside it |
| `template_list`, `template_get`, `template_save`, `template_delete` | manage templates |
| `survey_list`, `survey_ask`, `survey_update`, `survey_delete` | shape the survey |
| `survey_summarize` | every answer, aggregated &mdash; the tool for "what is the community saying" |
| `task_projects`, `task_project`, `task_project_save` | the person's own projects |
| `task_definitions`, `task_definition`, `task_definition_save` | the library: what things are, and how they are done |
| `task_add`, `task_remove`, `task_record`, `task_complete` | put things on a routine, and write down what happened |
| `task_group` | supersets and circuits |
| `task_review` | how the routine is actually going, sorted by impact for time |
| `board_list`, `board_read` | read the discussion board |
| `board_post`, `board_reply` | take part in it, under the name of whoever authorized the connection |
| `board_flagged` | the moderation queue &mdash; needs permission to moderate |
| `poll_create`, `poll_get`, `poll_list` | put a question to the group, and read how it is going |
| `poll_option_add`, `poll_option_remove` | put a day, a place or a choice on the table, or take one off |
| `poll_vote`, `poll_close` | vote, and count it |

It cannot touch member accounts, approvals, emails, bans, or anything marked human only. Set
`"read-only": true` to take away the writes as well.

### It can only do what its person can

**Every tool asks whether the person who authorized the connection is allowed to do that**, using
the same `Access.can` a page uses. An agent can never do anything the person could not.

There are two shapes of answer, and the difference matters:

- **A write is refused, by name.** "It needs 'Write and edit pages'." The model's useful next move
  is to tell them which permission is missing, rather than to try a different phrasing.
- **A read is narrowed, not refused.** A member's assistant listing pages gets the published ones;
  listing events gets the announced ones; listing places gets the ones in the book. Refusing
  outright would make the tool useless to the person it belongs to, and answering in full would hand
  them a draft they cannot open in a browser. It is the same asymmetry human-only already has:
  what somebody may not see is *absent*, not forbidden.

**A tool that could only ever refuse is not offered at all.** `tools/list` is narrowed to what this
connection can actually call, so a member's assistant is never shown `content_save` — a control that
would refuse teaches whoever meets it that the software is broken, and a model meeting one spends
its turns hunting for a phrasing that works. The listing is the courtesy; the surface is still the
boundary, and calling a tool that was not listed is refused by name.

**The briefing says who they are.** A connection tells the model, at `initialize`, whether it is
acting for an admin or a member, and that it can never do anything that person could not. A model
told it can shape a site when it is holding an ordinary member's connection spends its first three
turns being refused and its fourth apologising.

Two things are worth calling out. **Moderating** — the flag queue, taking somebody else's option off
the table — needs `board_moderate`, which an ordinary member does not have. **Putting up a vote that
turns itself into an event** needs `calendar_write`, checked when the vote is *asked*, not when it
closes: discovering at midnight that the answer cannot become anything wastes everybody's attention.

`survey_summarize` numbers respondents instead of naming them. A model summarizing what a community
said does not need to know who said it, and once the summary exists somewhere else, neither does
whoever gets hold of it.

### Human only

Tick **human only** on a page in the content editor and it disappears from every model: not in a
listing, not in a search, not fetchable by exact uri, not in the navigation. Writing to it is
refused &mdash; out loud, unlike the reads.

That asymmetry is on purpose. If a locked uri merely looked empty to a write, a model asked to "add
an about page" would cheerfully overwrite the one page somebody locked. Leaking the existence of a
uri to something already being told "no" is a much smaller problem.

Human only says nothing about who among the **people** can see the page &mdash; that is what
published is for &mdash; and no model can set or clear the bit.

### Watching what it did

`/admin/system/ai` is the connector list — **every member's, not just yours** — and the last 1,000
actions. Each one opens to show the
arguments it sent and what came back, as indented JSON. Filter by text, by outcome, or to changes
only; there is a live toggle.

Every action is recorded under two names: the person who authorized the connection and the connector
that acted. Refusals are logged as loudly as successes &mdash; a model repeatedly bouncing off a
human-only page is worth seeing.

Disconnecting a connector revokes every token it holds. Revoking somebody's sessions
(**Turn off** on their review page) takes its tokens with them, because an agent token is one of
their sessions.

---

## Certificates

Hearth can get and renew its own certificates from a certificate authority. Point `<root>/certs` at a
directory and it manages one per domain, verifying over plain HTTP.

```bash
java -jar hearth.jar --root /var/hearth --setup-certs   # once
java -jar hearth.jar --root /var/hearth
```

### What has to be true

Two things, and neither is something this server can arrange:

1. **Each domain's DNS points at this machine.**
2. **Port 80 on this machine is reachable from the internet.**

The authority proves you control a domain by asking this server for a file over plain HTTP on port
80. There is no DNS record to add and nothing to upload anywhere — the server answers the request
itself, at `/.well-known/acme-challenge/…`, and that path is handled before anything else in the
request path can refuse it.

If the server is not on port 80, forward it. The authority connects to port 80 regardless of what
`http-port` in config.cfg says.

### Setting up

`--setup-certs` walks through it and then exits:

```
  [certificate setup]
  ...
  Certificates will be managed for:
      example.org   -> 203.0.113.10

  Are those domains pointed here, with port 80 open? [y/N]
  Use the staging authority? [Y/n]
  Contact email:
  Do you agree to them? [y/N]
```

It is a conversation rather than a flag on purpose. Authorities rate limit failures hard — a handful
per hour — so restarting a server whose DNS is not ready yet can lock you out for an afternoon. The
walkthrough asks first, resolves each domain so you can see what this machine thinks, and needs a
terminal: piping answers into it is refused, because the point is to make somebody think.

**Say yes to staging the first time.** Staging certificates are not trusted by browsers, but its
rate limits are generous, so a wrong DNS record or a closed port costs you nothing. Once a full
round works, run it again and answer `n`.

Afterwards the directory holds:

```
  account.key     the ACME account key — back this up
  account.json    the account URL and what it was registered against
```

Losing `account.key` is not fatal but is annoying: you register again, and the rate limit clock
starts over.

### What happens on a normal start

With `<root>/certs` pointed at a set-up directory, a few seconds after the server starts listening it
checks every domain and orders what is missing. Then it re-checks every twelve hours and renews
anything inside **20 days of expiry**.

The delay is not arbitrary: validation is the authority calling back into this server, so ordering
cannot begin until the socket is accepting.

Per domain, the directory gains:

```
  example.org.key    the domain's private key
  example.org.crt    the certificate chain as the authority returned it
  example.org.json   key (PKCS#8) + chain, bundled for a TLS listener to load
```

The domain key is kept across renewals rather than churned.

### Turning on HTTPS

Once certificates exist, `"enable-https": true` in `config.cfg` serves them:

```bash
java -jar hearth.jar --root /var/hearth
```

with `"enable-https": true` in `config.cfg`.

That binds **80 and 443**. Each domain gets its own certificate, chosen during the TLS handshake by
the hostname the client asked for (SNI) — which is how one process serves several communities on
one address without a certificate that has to cover all of them.

**Port 80 stays a real web server.** It is not turned into a redirect, and it cannot be: it is what
answers the certificate authority's challenge, so a domain that has not got a certificate yet needs
it working in plain HTTP or it never will. Turning it into a redirect would quietly break renewal
for everything three months later.

**HTTP/2** is negotiated during the handshake (`"http2": true`, the default). Anything that cannot
do it gets HTTP/1.1 without noticing, so there is no reason to turn it off. The plain HTTP listener
stays HTTP/1.1 — it is answering a certificate challenge, and simple is the point there.

A host with no certificate still completes a handshake and gets a self-signed one, so the browser
says "not secure". That is deliberate: a refused connection looks exactly like a firewall problem
and people lose hours to that, while a browser warning names the real problem in its first sentence.

Renewals reach the listener without a restart. When a certificate is replaced, the TLS layer is
handed the new one and the next connection gets it.

### Bouncing plain traffic

Some load balancers terminate TLS themselves and want somewhere to send plain HTTP so that a person
who typed `http://` gets redirected rather than refused. That cannot be port 80 here, so it is its
own listener:

```json
{
  "enable-http-bounce": true,
  "http-bounce-port": 9999
}
```

It does exactly one thing: reads the Host and path, answers **308** pointing at `https://` on the
same host and path, and nothing else. No sessions, no virtual host lookup, no content — its value is
that it always works, which means having nothing behind it to go wrong. It is off unless you set
the flag.

308 rather than 301 because a browser following a 301 after a POST turns it into a GET and silently
drops the form.

### Knowing whether it is working

The boot report does not promise anything about certificates, because at boot nothing has happened
yet. Instead each domain reports as it resolves, a few seconds in:

```
  [OK] certificate for example.org: good until 2026-10-29 (89 days); https is now serving it
  [!!] no certificate for other.example.org yet: the CA could not reach other.example.org to verify it
```

A failure is one domain without a certificate, retried on the next sweep. The server keeps serving.

### What is not covered

| | |
|---|---|
| **Wildcards** | A wildcard certificate requires a DNS challenge, which requires credentials for whoever runs your DNS — exactly the dependency this avoids. A domain served by `wildcard: true` gets a certificate for its own name only; give subdomains their own config and they get their own. |
| **localhost** | No authority issues for it, and asking is how you meet a rate limit for nothing. Skipped. |
| **Disabled domains** | Skipped while `enabled: false`. |

### When it does not work

Nothing here stops the server. A domain that will not validate has no certificate, the reason is
written down, and the next sweep tries again — the site serves plain HTTP throughout.

The boot report shows what is cached and how long each has left. With `--verbose`, every check and
order is narrated.

| what you see | what it usually means |
|---|---|
| `the CA could not reach <domain> to verify it` | DNS does not point here yet, or port 80 is firewalled |
| `no account yet — run --setup-certs` | `<root>/certs` points at a directory that was never set up |
| `a wildcard certificate needs DNS-01` | a `*.` domain got into the managed list; give it a real name |
| `gave up waiting for the CA` | the authority is slow or the order is stuck; it retries next sweep |

---

## Databases

Every domain gets one embedded H2 database under `<root>/dbs`, named for the domain:

```
/var/hearth/
  example.org.mv.db
  localhost.mv.db
```

Nothing to install, no daemon to supervise, no migration tool. The schema lives in the code and
brings itself up to date on every boot — see [Upgrading](#upgrading).

The database is behind an interface (`Database` + `Dialect`) and H2 runs in `MODE=STRICT`, which
refuses H2's own SQL extensions. That is deliberate: the SQL is much likelier to work unchanged if
this ever needs MySQL or PostgreSQL.

---

## Sharing accounts between domains

`use_database_domain` points a domain at another one's database:

```json
// junior.example.org.cfg
{
  "name": "Example Community Junior",
  "use_database_domain": "example.org"
}
```

That means **one account space** — the same people, the same sessions, the same content and
questions, because there is literally one set of tables behind both.

Rules, all enforced at boot:

- the target must be a domain this server actually serves
- a domain cannot point at itself
- delegation is **one level deep**: pointing at a domain that itself delegates is an error

Because a shared database is one account space, it needs one policy: the **owning** domain's
`login_security`, `admin_emails` and `cache` govern it. The boot report says which that is.

> **Known limitation.** Cookies are set per host, so a browser signed in at `example.org` is not
> automatically signed in at `junior.example.org` — they share accounts, not cookies. A
> parent-domain cookie would fix it and is a real security decision, not a flag flip.

---

## Getting the first admin in

**Every account starts unapproved.** That is the right default for a community that wants to know
who is in the room, and it creates an obvious hole: the first person to sign up has nobody to
approve them.

So `admin_emails` names addresses that are admins by fiat:

```json
"admin_emails": ["owner@example.com", "someone-else@example.com"]
```

Being on that list:

- approves the account on sight
- grants the `admin` role (and writes it into the roles table, so listings show it)
- **cannot be revoked from inside the running system**

That last point matters. The admin page refuses to demote a config admin and tells you to edit the
file. Changing who is on the list means editing a file on the box and restarting — which is exactly
the level of access somebody should need to appoint an administrator.

Matching is case-insensitive.

---

## Approving people

A session means **authenticated** — they proved they can read that address. It does not mean
**authorized**. An unapproved person can reach their own page at `urls.self`, the welcome at
`urls.orientation` and the questions at `urls.survey`, and nothing else; the community itself is
what approval gates.

That separation is what makes approval workable. Approving somebody is a judgement call, and a
judgement call needs something to judge — so an unapproved person can write a profile and answer
the community's questions first. That is what you read before deciding, and it is why those three
pages are on the near side of the gate.

The flow:

1. somebody registers, proves their address with an emailed code, and lands on `/welcome`, where
   they are asked what to call them and then walked into the community's questions
2. they fill in a profile and answer whatever the community has asked
3. you open `/admin/people`, click their address, and read their review page:

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

4. **Approve**, and they can see the community

### The three different ways to say no

They are deliberately different buttons, because they mean different things and only one of them
can be undone.

| action | effect | reversible |
|---|---|---|
| leave unapproved | *not yet* — they keep everything and see only their own page | yes, by approving |
| **Turn off** | *not right now* — everything is kept, every session ends immediately | yes, **Turn back on** |
| **Reject and delete** | *no* — the account, the profile and the answers are deleted | **no** |
| **Reject, delete and ban** | the same, plus the address is refused in future | yes, by lifting the ban |

**An admin can be none of these.** Remove their admin role first; the buttons say so rather than
hiding, so nobody wonders where they went.

What else the People page does:

| action | effect |
|---|---|
| Approve | they can see the community |
| Make admin / Remove admin | granting also approves; config admins cannot be removed here |
| filter by state | everybody, waiting, approved, turned off, admins |
| search by email | contains-of, updating as you type |

The two kinds of admin are drawn differently: **red** for an address in `admin_emails`, which no
button here can revoke, and **purple** for somebody promoted from inside. One is a fact about a file
on the box; the other is a decision somebody made in this UI.

The list also shows each person's signup **signal count** — how much mouse, keyboard and touch
activity their browser reported while registering. A wave of accounts that all scored identically is
a shape you can only see afterwards, and only if somebody wrote it down.

---

## The discussion board

`/board` (configurable via `urls.board`). Members post, reply in threads, and hear about what they
are part of.

**Posts expire by default** — 60 days, set with `board.expiry-days`. A board that keeps everything
becomes an archive nobody reads; set it to `0` for a permanent record.

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | serve the board on this domain |
| `expiry-days` | `60` | how long a post lives; `0` means forever |
| | | polls are part of a conversation and go when it does |
| `notification-days` | `30` | how long an inbox notification outlives being written |

### Deciding something: votes in a conversation

Any member can put a vote inside a conversation &mdash; *put something to a vote* on any thread.
It lives in the discussion because that is where the reasons are; deleting the conversation takes
the vote with it.

**Two kinds.**

**A straight choice** is either-or: one vote each, most votes wins. Voting for a different option
moves your vote rather than adding one, and voting for the same thing twice takes it back.

**A day and a place** asks both at once, because they are one decision &mdash; a hall free on
Thursday and a friend's kitchen free on Saturday cannot be chosen separately. When it closes, the
winners become an event in the calendar, as a draft. It needs permission to create events, checked
when the vote is asked rather than when it closes.

The two halves count differently, on purpose:

| | how it is voted | why |
|---|---|---|
| **days** | yes, no, or nothing &mdash; on each day separately | a week has several evenings and somebody can be free on three of them; forcing one pick throws away most of what they know. What comes out is a histogram, which shows whether one evening is genuinely better or the group is split |
| **places** | one vote, either-or | somebody can be free on three evenings; nobody thinks the event should happen in three places |

A **no** on a day counts against it, so an evening half the group has ruled out does not beat one
nobody objects to. A place is always a place from the address book, never typed text, so the winner
becomes the event's location without anybody retyping an address.

Anybody who can vote can also **put another day or place on the table** &mdash; somebody else knows
a hall you do not. Tick *only I can add options* when setting it up if that is not wanted. Removing
an option you added is yours to do; removing somebody else's needs `board_moderate`. **The votes for
a removed option are kept**, so taking one off cannot silently change what the others are a share
of.

**Ties are reported, never broken.** A tied day makes no event and says so, in the conversation
where the question was asked. That is not a failure &mdash; it is the group not having decided, and
the fix is another day on the table or two more votes. The same is true when everything on the table
was voted down more than up: a winner on a negative score would be the software insisting on an
evening nobody wants.

A poll closes at the end of the day you name, in the community's own timezone, on the background
pass &mdash; so it closes on Sunday night rather than the next time somebody happens to look. The
answer is posted back into the conversation, which tells everybody already watching it.

### Watching, and the inbox

**Replying is what makes you a watcher.** There is no subscribe button, because a board where you
have to remember to press one is a board where people miss the reply to their own comment. Posting
makes you a watcher too. There is an explicit *stop watching* on any thread.

Everybody watching gets a notification when somebody replies — except the person who replied. It
lands in **Your page → Inbox**, with a count in the navigation, and opening the tab marks it read.

Notifications age out with the conversation they are about, so an inbox does not accumulate
forever.

### Getting told outside the site

**Your page → Notifications.** Two settings, because there are two different things that happen:

| setting | default | what it covers |
|---|---|---|
| When somebody replies to you | **Straight away** | a reply to your post, or to your comment |
| When there is activity in a conversation you are watching | **Once a day** | everything else in a thread you joined |

Each is one of *Never*, *Straight away*, *Once a day*, *Once a week*. A reply aimed at you is a
conversation waiting on an answer; activity in a thread you watch is news, and news can wait for
the morning — which is why one setting could not be right for both.

Whatever somebody picks, everything still lands in their inbox on the site. These settings only
decide what is also worth an email.

**Text messages are not available.** The preference exists in the schema and the seam is in place
(`io.hearth.sms.Sms`), but no provider is implemented, so the settings page says so instead of
offering a checkbox that stores a promise the server cannot keep.

### How delivery actually works

One background thread for the whole server — not the click that caused it. A reply in a thread with
forty watchers is forty signed requests to Amazon, and doing that inside the POST would make the
reply box get slower as a thread got popular. So:

- **"Straight away" means the next pass**, which runs every 60 seconds.
- **A digest is one message**, gathering everything that was waiting. The first one goes out on the
  next pass rather than waiting a day, because the notification that triggered it is what starts
  the clock; after that it is a real day or a real week.
- **Nothing is sent twice.** Each notification row carries a `notified_at` stamp, so a restart
  mid-pass cannot duplicate anything, and a digest is stamped *before* it is sent — two copies of
  Tuesday's summary is worse than missing Tuesday, and the inbox on the site is the record either
  way.
- **Nothing is sent about a conversation that has aged out.**
- A database shared by two domains is delivered for once, under the domain that owns it.

If a community has no SES credentials, every one of these prints to the terminal like the login
codes do — see [Email](#email).

**Troubleshooting.** Nobody is getting email: check the domain has `ses` configured
(`--test-email <domain> <address>`), then check the member's Notifications tab — the default for
thread activity is a daily summary, not an immediate mail, so "I replied and they got nothing
within a minute" is usually correct behaviour rather than a fault.

### Threads

Replies nest up to six deep. Past that a reply attaches at the sixth level rather than nesting
further, which keeps a long back-and-forth from becoming a staircase without losing anything.

A removed comment keeps its place and says "removed" — the replies underneath it are other
people's words.

### Moderating

`/admin/board` lists every conversation, including the ones the feed hides &mdash; removed, and aged
out. Three things happen there:

| action | what it does |
|---|---|
| **pin** | holds the thread at the top of the feed |
| **lock** | it stays readable and takes no more replies |
| **remove** | it leaves the feed; the thread and its replies are kept |

There is deliberately **no fourth**. An admin cannot edit somebody else's words: changing what a
person said while leaving their name on it is the one moderation power they cannot undo. Removing
says *removed*.

Setting a thread's expiry to `0` days keeps it indefinitely, the same meaning `board.expiry-days`
has.

### Fixing your own words

Authors can edit their own post or comment, and remove them. An edited post says **edited** next to
the byline &mdash; people replied to what it said before, and a post that changes silently is a
small lie to all of them. A removed comment keeps its place so the replies underneath it are not
orphaned.

---

## The calendar

`/events` (configurable via `urls.calendar`), managed at `/admin/calendar`. **Only admins create
events; everybody else answers.**

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | serve the calendar on this domain |
| `past-days` | `90` | how many days of finished events the list still shows |

### Making one

An event has a name, a first day, and optionally a last day &mdash; leave the last day empty for a
one-day event. Everything else is optional:

- **Time** is free text, shown exactly as written. "Doors at 7, music at 8" is a real answer that no
  time field holds. Days rather than timestamps is deliberate: a community meeting in one town does
  not need a timezone question on every page.
- **Capacity** empty means no limit.
- **Announced** is the publish switch. A draft is invisible to members and visible to admins, so an
  event can be written before it is decided.

### Answering

Going, maybe, or not this time &mdash; plus how many they are bringing, and a note. Answers can be
changed or withdrawn at any time until the event is over or cancelled.

The guest list shows **names, not a number**, because a community event is people deciding whether
to go based on who else is.

### Capacity and the waitlist

Places are counted **by heads, not by answers**: somebody bringing three takes four places.

When the room is full, saying "going" puts somebody on the **waitlist** and the page tells them so.
It never shows a tick to somebody who does not have a place.

- Somebody dropping out promotes the **longest wait that fits**. It skips a party too large rather
  than stopping, so one person waiting for six places does not block five people waiting for one
  each.
- **Raising** the capacity seats whoever now fits; removing the limit seats everybody.
- **Lowering** it never takes a place back from somebody who already has one. Withdrawing is
  something a person does, not something a sweep does to them.
- A **maybe** is never a place.

### Cancelling versus deleting

**Cancel** keeps the page and the guest list, and says it is off. That is almost always what you
want: the people who said they were coming are exactly the people who need to see it.

**Delete** removes the event and everybody's answers, with no record. Use it for something created
by mistake.

---

## The app

`/~app` is a progressive web app: a shell page that holds the site in an iframe, with a manifest and
a service worker. Members can install it from any browser that offers to, and it behaves like an app
without being one.

**There is nothing to install from a store, and there never will be.** Everything here is a web
feature. A community of a hundred people should not need somebody's developer account, a review
process, or a build for two platforms in order to have an icon on a phone.

### What the shell is for

The site works exactly as it always did &mdash; every page on its own URL, no JavaScript required.
The shell is an *additional* way in, and it exists so the service worker, the notification
permission and the push subscription live in a document that is never torn down as somebody
navigates. A plain multi-page app re-registers on every page load, which makes "has this browser
subscribed?" a question with a different answer each time.

The frame is same-origin only. `/~app?to=/board` opens the shell on the board; anything pointing off
this site is refused by the same check that stops `?next=` being an open redirect.

### The manifest, and why nothing used to offer to install this

`/manifest.webmanifest`, generated per domain: the community's own name and short name, `/~app` as
the start URL, standalone display, an `id`, and shortcuts to the board and the calendar.

Two things about it were wrong for a long time and both had the same symptom &mdash; **no browser
ever showed an install button**. If you are upgrading from a version before this, that is what
changed:

- **The manifest was declared on `/~app` alone.** A browser offers to install what the page in front
  of it declares, and `/~app` is an address nobody reaches without already knowing about it. It is
  now on every page.
- **The icons were `data:` URIs.** Correct by the specification, refused in practice: Chrome
  downloads manifest icons and will not install an app whose icons it cannot fetch from a URL, and
  iOS wants a PNG `apple-touch-icon` before it puts anything on a home screen.

Icons are now real bytes at real addresses &mdash; `/~app/icon-192.png`, `/~app/icon-512.png` and
`/~app/icon-maskable-512.png` &mdash; **drawn at request time** in the community's own accent colour
and cached in memory. There is still no image file anywhere in this project, and a community that
changes its colours gets an icon that changes with them. On a runtime built without image encoding
the icon endpoints answer 404 and the site keeps its inline SVG favicon; nothing else breaks.

The service worker also has a `fetch` handler now, because a browser will not offer to install an app
whose worker cannot answer a navigation with the network down. It still caches nothing: requests pass
straight through, and the only thing it can produce offline is a "no connection" page built inside
the worker itself, for which stale is not a possible state.

---

## Notifications on a phone

`/sw.js` is the service worker, served from the root so its scope is the whole site. It caches
nothing &mdash; an offline cache serving a stale members list is worse than a page saying it cannot
reach the server &mdash; and exists to receive pushes and to be somewhere a notification can be
clicked.

### Send people to `/~app/help`

That is the screen to point anybody at, and it is in the menu as **Get the app**. It has:

- **install steps per platform** &mdash; Safari's Share menu on iOS (nothing else on iOS can do it),
  Chrome's *Install app* on Android, the address-bar icon or *File → Add to Dock* on a computer;
- a **Turn on notifications** button, which asks permission and subscribes this browser;
- what to do when a browser has already been told no and will not ask again;
- and a **self-test**: it sends a real notification to this browser, right now.

The self-test is the reason the page exists. Turning notifications on means clearing three separate
permissions plus whatever the operating system thinks, and nobody discovers that a focus mode has
been eating them until the evening they needed one. The page reports both halves separately: whether
the push service accepted it, and whether this device actually showed it &mdash; the service worker
tells the page when one lands, so "sent" and "arrived" cannot be confused. If it says sent and
nothing lands within ten seconds, the problem is below the browser: a focus mode, a system-wide do
not disturb, or notifications switched off for the app in the phone's settings.

Over plain http the page says so at the top and stops promising anything: browsers refuse both
installing and notifications without https, and nothing on the page can get around it.

### Turning them on

Open `/~app` while signed in and there is a **Turn on notifications** button, and the same button is
on `/~app/help`. The browser asks; if the person agrees, that browser is registered and the server
can reach it.

Clicking a notification **comes back into the app rather than opening another copy**. If a window is
already open it is focused and sent to the right page; a new one is started only when nothing is
open at all.

### A subscription belongs to a session, not to an account

This is the part worth understanding.

- **Each signed-in browser is one session and one subscription.** A phone and a laptop are two, and
  turning notifications off on the laptop leaves the phone alone.
- **Signing out deletes the session**, and the subscription goes with it. Not revoked &mdash;
  deleted. A revoked session used to linger for a day, and for that day the server still held a key
  that could make a notification appear on a device somebody had just signed out of.
- **The VAPID keypair is per session too.** Most implementations use one pair for the whole server;
  here each subscribing session gets its own, so revoking a login destroys the only key the push
  service will accept for that browser. It costs a keypair per sign-in, which at this scale is
  nothing.
- **An admin removing somebody silences every device they had**, immediately.
- The session reaper sweeps any subscription whose session has gone by some other route.

**Everything is re-entrant.** The shell re-registers on every load and every step is a no-op if it
has already happened: the worker registration is returned rather than duplicated, an existing
subscription is reused, and posting it again updates one row instead of adding one. A browser whose
subscription was rotated repairs itself on the next visit rather than going quietly silent.

### What a push contains

A title, one line, and where to go. **Never the contents.** A push travels through Google's or
Apple's infrastructure, and although it is encrypted end to end, whatever is on a lock screen is
visible to whoever is holding the phone. Its job is to bring somebody back, not to tell them the
thing &mdash; which matters most in exactly the community where it matters most.

Pushes go out alongside email rather than instead of it, on the same background pass. A push that
fails never stops the mail.

### When it does not work

| what you see | what it usually is |
|---|---|
| No install button anywhere | Not on https; or the manifest, an icon or `/sw.js` is not being served &mdash; fetch all four by hand and check they answer 200. |
| No button on `/~app` | Not signed in, or the browser has no `PushManager` &mdash; some do not. |
| The test says sent and nothing appears | A focus mode, a system-wide do not disturb, or notifications turned off for the app in the operating system's own settings. Nothing on this server can see any of those. |
| The test says the push service refused it | The message says what it said. Usually the subscription has been rotated: turn notifications off and on again in that browser. |
| "This browser is blocking notifications" | Permission was denied once; browsers remember, and only the person can undo it in site settings. |
| Subscribed, but nothing arrives | Notification preferences: the default for thread activity is a daily summary, not an immediate ping. Check **Your page → Notifications**. |
| It worked and then stopped | The session ended &mdash; signed out, expired, or reaped. Open `/~app` again and turn it back on. |
| iOS shows no button until installed | Safari only allows push for apps added to the home screen. Install it first, then open it from the icon. |

---

## Cutting a release

```bash
just release-check     # what a release would refuse, without doing anything
just release 0.2.0     # validate, build, tag, publish
```

### An SSH key is not enough, and this is why

Your SSH key signs **git** operations &mdash; push, pull, tag. It cannot create a GitHub release,
because releases are a **REST API** resource and that API takes a token. There is no way around
that; it is how GitHub is built.

So the tag goes up over SSH, and publishing the release needs one of:

```bash
gh auth login                    # the gh CLI keeps its own token
export GITHUB_TOKEN=ghp_xxxx     # a PAT with `contents: write` on this repository
```

`just release` checks for one **before it changes anything**. Finding out after the tag is pushed
would leave the repository claiming a release that does not exist, which is worse than not starting.

### What it does, in order

1. **Refuses** a version that is not `1.2.3`, a dirty tree, a branch other than `main`, a local
   `main` that differs from `origin/main`, and a tag that already exists. A released version is
   never rebuilt.
2. **Vendors the third-party libraries.** They are not in git, so a release built without this step
   would ship a jar whose editor silently falls back to a plain textarea.
3. **Runs `just validate`** &mdash; the whole gate, including the live smoke test and the docs check.
4. **Builds a stamped jar.** The version goes into the manifest, so the binary somebody downloads
   answers `--version` with the tag it was cut from. The recipe then runs `--version` and refuses if
   the jar disagrees, because a binary that cannot say what it is turns every bug report into
   archaeology. A jar built by hand says `0.0.1-SNAPSHOT`, never a release number.
5. **Writes a checksum** and release notes &mdash; commit subjects since the last tag, not bodies,
   because the bodies in this repository are long and a release page nobody scrolls is not notes.
6. **Tags and pushes** the tag.
7. **Publishes** with `gh` or the API, attaching the jar and its `.sha256`.

### Installing what comes out

```bash
sha256sum -c hearth-0.2.0.jar.sha256
java -jar hearth-0.2.0.jar --root /var/hearth
```

That is the whole install. One file, one directory.

---

## Receiving email

Hearth can also *receive* mail. Off by default, turned on in `config.cfg`:

```json
{
  "smtp": {
    "enabled": true,
    "port": 25
  }
}
```

| key | default | meaning |
|---|---|---|
| `enabled` | `false` | listen for inbound mail at all |
| `port` | `25` | where; 25 needs root |
| `hostname` | — | the name in the banner; defaults to a domain this server serves |
| `max-message-bytes` | `10485760` | the biggest message accepted |
| `max-recipients` | `25` | recipients in one message |
| `idle-seconds` | `60` | how long a connection may sit silent |
| `max-connections` | `64` | at once |

**Off by default is deliberate.** Port 25 needs root, and an unconfigured SMTP listener is found by
scanners within the hour. Turning it on is a decision somebody should make on purpose.

### Routing

A message is accepted only if the recipient's domain **has a config file here**, matched exactly.
Everything else is refused with a permanent `550` at RCPT, before any message body is sent.

**This server never relays, and that is not a setting.** An open relay is found within days, used to
send spam in somebody else's name, and ends with the machine's address on every blocklist there is
&mdash; with the community's own outbound mail undeliverable behind it.

A `wildcard: true` domain is matched exactly too. A wildcard is a reasonable thing to want for a
website; treating it as permission to receive mail would mean accepting for every domain under that
suffix.

A **named** `subdomains` entry *is* accepted, because somebody wrote it down. `mail.example.org` in
that list means mail for it lands in the same community as `example.org`.

A domain can opt out entirely with `"accepts-mail": false`, which refuses at RCPT the same way an
unknown domain does. That is the setting for a domain that has a website here and its mail somewhere
else.

One message goes to one community. Recipients spread across two domains get a `451` asking the
sender to split them, because everything downstream needs to know which community it is acting for.

### What happens to it

Today: **it prints to the terminal** &mdash; who it came from, who it was for, which community it
routed to, the subject, and the start of the body. That is the whole handler, and it is enough to
watch routing work.

Anything else is a `MailReceiver`, which is one method. That is where a feature like "replying to a
notification posts to the thread" would live.

### Is this message really from who it says?

Every message is checked with **SPF, DKIM and DMARC** before it is delivered, and the findings are
written onto the front of it as an `Authentication-Results` header. That happens whatever the
outcome: whatever handles the mail later should be able to see what was known when it arrived.

| | what it actually proves |
|---|---|
| **SPF** | the machine that connected is one the *envelope sender's* domain listed. Says nothing about the `From:` a person reads, and **breaks on forwarding** &mdash; a mailing list re-sends from its own machine. |
| **DKIM** | the message is signed by some domain, and neither the signed headers nor the body have changed since. Survives forwarding. |
| **DMARC** | the domain in the `From:` header &mdash; the one a person reads &mdash; vouches for the message, because either SPF or DKIM authenticated a domain that *aligns* with it. |

Alignment is the part that makes the other two mean anything. A spammer can publish a perfectly good
SPF record for their own domain, send from it, pass SPF, and put your bank in the `From:` header.
DMARC closes that, and lets the domain owner say what to do about it.

### What gets refused

**Only what the domain owner asked to be refused.** A message that fails DMARC where the owner
published `p=reject` is refused with a `550`. Everything else is delivered and marked.

That line is deliberate. Refusing on an SPF failure alone would reject every message that came
through a mailing list, and a community whose mail silently stops working is worse off than one that
receives the occasional forgery it can see is a forgery.

| key | default | meaning |
|---|---|---|
| `check-senders` | `true` | run SPF, DKIM and DMARC at all |
| `enforce-dmarc` | `false` | refuse when the From domain publishes `p=reject` and the message failed |
| `dns-timeout-millis` | `3000` | how long to wait on one DNS answer |

**`enforce-dmarc` is off by default, and that is a statement about this code rather than about
DMARC.** The three validators are tested hard against the RFCs with a fake resolver, and have never
seen a real Gmail signature or a message that came through a mailing list — which is exactly where
canonicalization bugs live. Enforcing on the first day means a bug here refuses real mail from the
providers most likely to publish `p=reject`, and you find out when somebody says "I emailed you last
week".

Everything is still checked, and every message still carries an `Authentication-Results` header
saying what was found. Read those on mail you know is genuine for a few weeks, then turn enforcement
on.

The checks run on a worker thread rather than the connection's, because DNS blocks and one slow
nameserver must not stall every other conversation. The sending server simply waits a moment longer
for its `250`, which is exactly what it expects to be doing.

While it waits, **anything else that arrives on that connection is held and answered afterwards, in
order**. SMTP replies are matched to commands by position, so answering a pipelined command before
the message's own `250` would let a sender record the wrong message as accepted. A client that keeps
talking into the silence past a small bound is closed with a `421` rather than buffered.

There is one more thing worth knowing about DMARC here: relaxed alignment needs the registrable
domain, which strictly requires the Public Suffix List — a weekly download this project will not
take on for one check. The rule used instead handles the common shapes and errs toward *not*
aligning, so a wrong guess marks a message unaligned rather than passing a forgery. With enforcement
on, a legitimate sender under an unusual suffix can be refused; that is the second reason to leave
enforcement off until you have watched what arrives.

### What is still not here

No TLS on this port and no `AUTH`. A DNS lookup failure is treated as *temporary* rather than as a
forgery throughout &mdash; an unreachable nameserver should never bounce real mail.

The organizational-domain rule (`mail.example.org` aligning with `example.org`) does not use the
Public Suffix List, which is a downloaded file that changes weekly and a dependency this project
will not take for one check. It handles the common shapes and errs toward *not* aligning, so a wrong
guess marks a message unaligned rather than passing a forgery.

`VRFY` and `EXPN` answer `252` rather than the truth: a server that confirms whether an address
exists is a membership oracle, one guess at a time.

### When it does not work

| what you see | what it usually is |
|---|---|
| `could not bind smtp on 25` at boot | not running as root. The site is unaffected; use a high port and redirect, or grant the capability. |
| Senders get `550 We do not relay` | the recipient domain has no config file here, or its filename does not match exactly. |
| Senders get `451 One community per message` | one message addressed to two of your domains. Send it twice. |
| Real mail refused with `550 ... p=reject` | the sender's own DMARC says to. Usually their misconfiguration, sometimes a forwarder rewriting headers. `enforce-dmarc: false` while you work out which. |
| Everything says `spf=temperror` | DNS is not answering from this machine. |
| Nothing arrives at all | check DNS: an MX record has to point at this machine, and port 25 has to be reachable, which many hosts block by default. |

---

## The address book

`/places` for members, `/admin/places` to keep it. An address book where **you decide what a kind of
place records.**

That is the whole idea. A carnivore supper club wants ranches, and a ranch has grass-finished,
cuts-sold and whether they deliver. An MS group wants vendors, and a vendor has what the discount is
and who to ask for. A games night wants venues, and a venue has how many fit and whether there is
parking. None of that belongs in a program that does not know your community, so you declare it.

### Kinds first

`/admin/places/kinds` (the `placetypes` section). A kind has a short name (used in the URL), a singular and a plural, and a
list of fields &mdash; each with a name, a type, a label and whether it is required. Those become
the boxes on the address editor and the details on the public page, and changing them changes every
editor for that kind at once.

A kind can also name a **template**, edited under Content like any other, which renders every place
of that kind. The template gets the place's fields by name, so `{{extra.grass_finished}}` works.
Without a template there is a plain built-in page, which is enough to start with.

**Removing a kind does not remove its addresses.** They move to **Unsorted** &mdash; a kind that
always exists, declares nothing, and refuses to be removed itself &mdash; and come off the listing,
because what was recorded about them belonged to a kind that is gone and re-listing them under a
heading nobody chose would be worse than putting them aside. Their field values are kept even though
nothing displays them: give one a kind that asks those questions again and the answers are still
there.

### Then addresses

`/admin/places`. Name, address, contact details, and whatever the kind asks for. The address is free
text on purpose &mdash; addresses are not a schema, half the useful ones are "the barn behind the
white house", and a community that has to fight a form will not write it down. Latitude and
longitude are optional and produce a map link a phone opens in whatever map app it has.

**Changing the kind swaps the questions immediately, and loses nothing.** Pick a different kind and
the boxes below change on the spot &mdash; no save, no reload. Anything already typed is held, so
switching back brings it straight back, and saving keeps answers given under a kind you switched away
from. The editor ships every kind's declarations to the browser and carries the values in a hidden
field to make that true.

Without JavaScript the page still works: the server renders the current kind's boxes, and the save
merges against what is stored. The only thing lost is the live swap itself.

**Changing an address's kind takes it off the listing when you save.** The questions a kind asks only
make sense for that kind, so changing what something *is* means somebody should look before it goes
back on. The editor says so as soon as you pick a different kind.

An address is a draft until it is listed, and can be marked **human only** &mdash; invisible to any
connected model, and unwritable by one, exactly as with content.

### What members see

| | |
|---|---|
| `/places` | the kinds this community keeps |
| `/places/<kind>` | everything of that kind, with a column per declared field |
| `/places/<kind>/<name>` | one place |

Searching covers names, addresses and **the values of the fields you invented** &mdash; searching a
supper club's ranches for "grass" finds the ones that recorded it. It matches the values rather than
the field names, so searching "grass" does not return a ranch that recorded *grain* just because the
field is called `grass_finished`.

The address book is behind sign-in and approval like the rest of the community. A list of the
vendors who give a discount to people with MS is not something to leave on the open web with the
community's name on it.

### What a connected model can do

`place_types` lists the kinds and their fields, `place_list` and `place_get` read, `place_save` and
`place_delete` write. Requires **Keep the address book** on a role, and the connection not to be
read-only.

A model can only fill in fields the kind declared &mdash; inventing one is **refused**, not quietly
dropped, so it cannot believe it saved something it did not. Human-only places are absent from its
listings rather than forbidden, and it can never set or clear that bit.

---

## Roles and permissions

`/admin/roles`. A role is a name and a list of things it lets somebody do; give somebody a role in
`/admin/people`, decide what it means here.

**Administrator is built in.** It holds every permission, is rewritten at every boot, and cannot be
edited or deleted &mdash; a community that can edit its way out of having an administrator has
locked itself out of its own server. `admin_emails` in the domain config is the second lock on that
door: those addresses hold everything no matter what the database says.

**Editor** is created on first boot as a starting point: write, publish, templates, navigation,
review suggestions &mdash; and no system access. It is ordinary data, so change it freely; boot will
not overwrite your version.

Everything else is yours to invent. `greeter` who can only invite, `librarian` who can only read
content, whatever the community needs.

### How permissions behave

- **Ticking one implies the ones it needs.** Anything at all implies reaching the admin section;
  writing implies reading; bulk inviting implies inviting. A role granting a power behind a door it
  cannot open would look exactly like a bug.
- **Two roles add up.** Holding more can only ever give you more.
- **A section you cannot open is absent, not greyed out** &mdash; and answers 404, not 403. A door
  that says "forbidden" has confirmed what is behind it.
- **A grant of a role that no longer exists grants nothing** rather than failing.
- Deleting a role takes its grants with it.

Members see what they hold on **Your page**, under "What you are trusted with", in the same words
the role editor uses.

---

## Suggested edits

`/admin/content/proposals`. The version history looking forwards.

Somebody with **Suggest an edit** but not **Write** opens a page in the editor and gets a *Suggest
this change* button instead of *Save*. Nothing goes live. A reviewer &mdash; anyone with **Approve
or decline suggested edits** &mdash; sees the queue, can look at exactly what would change, and says
yes or no.

- **Approving is a save.** The page updates and the history records it like any other edit;
  afterwards an approved suggestion is indistinguishable from one made directly, because it is one.
- **Declining keeps the row and the reason**, so whoever wrote it can read why.
- **A page that moved is flagged, not blocked.** If somebody edits the page after a suggestion was
  written, the queue marks it *page moved since*. It stays approvable, because only the reviewer can
  tell whether the two edits conflict &mdash; but applying it blind would revert somebody's work
  while looking like it worked.
- The queue is oldest first. A queue people can jump is a queue nobody trusts.

---

## Third-party libraries

Browser libraries are **vendored into the jar**, not loaded from a CDN, and served at
`/3rd/<package>/<version>/<file>`.

```bash
just third-party      # download them into src/main/resources/3rd
just package          # bake them into hearth.jar
```

Nothing is fetched at runtime. A community's editor must not stop working because a CDN had a bad
day or changed a file under the same URL &mdash; and a page that loads from somebody else's server
has told them a member was reading it. The version is in the path, which makes the URL immutable, so
these are cached for a year.

**Milkdown** is wired into the content editor: a rich markdown editor over the same `body` field.
The textarea remains the form field and the source of truth, so if the script fails to load the page
is still a working plain-text editor. There is a toggle back to plain markdown.

This is the one thing in the jar that is not the program, and it is not small &mdash; Milkdown is
about 2.8 MB. It is still one artifact to deploy and nothing to keep in sync, which is the property
that mattered.

---

## Inviting people

`/admin/invites`. Write an invitation, send it, and watch what becomes of it.

The screen leads with the funnel: **sent → opened → clicked → joined**, and the conversion rate,
which is the number worth having. Not how many went out, but how many became somebody who signed
in.

Writing and sending are separate steps, because they fail for different reasons: a mistyped address
is a bad invitation, and a wrong SES key is a bad afternoon.

### What "opened" means, precisely

Each message carries a one-pixel image whose path holds the invitation's token. When a mail client
fetches it, an open is recorded &mdash; first seen, last seen, and how many times.

**An open is not a read, and it fails in both directions.**

| | why |
|---|---|
| **It under-reports** | Most clients block remote images by default. Somebody can read the whole thing twice and never register an open. *No evidence of an open* is exactly that &mdash; it is not "unread". |
| **It over-reports** | Apple Mail Privacy Protection fetches remote images **by itself**, before anybody opens anything, and Apple Mail is around half of all opens. Gmail proxies and pre-fetches too. A recorded open frequently means a machine looked. |

Read together, an open is **weak evidence that the message landed somewhere real and was not
obviously refused**. It is not evidence a human saw it.

- A high open rate with no conversions usually means prefetching, not interest.
- A low open rate with good conversions is normal and completely fine.
- **If you want to know whether an invitation worked, look at *joined*.** It is the only number on
  that screen a machine cannot produce on somebody's behalf.

The pixel is never cached and always returns an image even for a token that means nothing: a mail
client must not get an error page, and a cached pixel would record one open forever.

### What "clicked" means

The link in the message carries the invitation's token, and following it lands on the sign-up form
with the address already filled in. That arrival is recorded &mdash; first, last, and how many times.

**This is the honest one of the three rates.** No mail client follows a link on somebody's behalf,
so a click is a person deciding to find out more. Read the four numbers together and they say which
problem you have:

| what you see | what it usually means |
|---|---|
| nothing opened | the message did not arrive, or is in a folder nobody looks at |
| opened, never clicked | it arrived and did not persuade &mdash; look at what it says |
| clicked, never joined | somebody was convinced and the sign-up lost them. This is the one worth chasing: it is the closest anybody got |
| joined | it worked |

There is no redirect in the middle. The click is recorded by the sign-up page itself, because a
tracking hop between a person and the form is the wrong trade for a number.

### The three messages

An invitation is one row, one token, one link &mdash; and up to three messages.

| | when | what it says |
|---|---|---|
| **welcome** | on sending | what this is, who invited them, and the way in |
| **reminder** | 3 days later | short and friendly; assumes it got buried |
| **apology** | 7 days after that | says it is the last one, and means it |

The defaults come from published sequence research rather than taste: the usual spacing is 2-4-7
days with a floor of a day, one message plus a follow-up around day three and another six or seven
days after. A first follow-up lifts response substantially; a three-step sequence lifts completion
by roughly 14-25%.

There is deliberately **no fourth**. The third says it is the last one, and going back on that is
how a community earns a spam complaint &mdash; which costs the whole sending domain, not the one
message.

The sequence stops immediately when somebody **joins** or the invitation is **revoked**. An
invitation that was written but never sent is never reminded, because a reminder about a message
nobody received is a first contact wearing the wrong clothes.

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | invitations on this domain at all |
| `members-may-invite` | `true` | can an ordinary approved member invite, or only a role that says so |
| `member-daily-limit` | `5` | how many one member may write in a day; `0` for no limit |
| `reminders` | `true` | send the second and third messages |
| `reminder-after-days` | `3` | days from the welcome to the friendly reminder |
| `apology-after-days` | `7` | days from the reminder to the last note |
| `tagline` | — | the line under the community name in every message |
| `about` | — | a sentence or two about the community; the welcome only |
| `call-to-action` | `Accept the invitation` | what the button says |
| `sign-off` | — | who it is from |

Set those once and inviting somebody is **just an email address**. An invite flow that asks for a
subject line every time is one nobody uses twice.

### Members inviting people

**Your page → Invite.** An approved member writes an address and a line of their own; the message
goes out with their name on it and the reminders follow automatically. They can see what became of
everyone they invited.

Members are held to `member-daily-limit` per day. Anybody with the **Invite many people at once**
permission is not &mdash; the limit exists so one enthusiastic person with a contacts export cannot
burn the sending domain for everybody, and somebody trusted with bulk has already been trusted with
that.

### Inviting a lot of people at once

`/admin/invites` → *Invite a lot of people at once*, for anybody with the bulk permission. Paste
addresses separated by commas, semicolons, newlines or tabs, including
`Name <addr@example.org>` straight out of a mail client. Duplicates are dropped &mdash; a pasted
list has the same person on it twice more often than not. At most 200 at a time; past that it is a
mailing list rather than an invitation, and every spam filter will treat it as one.

**Every address gets its own result.** A bulk send that reports only "12 sent" hides the three that
did not, and those are the ones somebody has to do something about.

### Conversion

An invitation converts when somebody **signs up**, not when they click. A click proves a link was
opened; what an invitation is for is a member.

Matching is by address, so an invited person who ignores the link and signs up from the front page
still counts. Inviting the same address three times and having them join once counts as **one**
conversion, not three — resending does not inflate the rate.

A converted invitation links straight to the member, and any member can be traced back to the
invitation that brought them. In `/admin/people` a member who came in that way carries an
**invited** tag, and their review page names who invited them, when, and what the invitation said —
which is usually the context an admin wants before deciding about somebody.

---

## Banning an address

`/admin/bans`. This is not about insult, it is about cost. A banned address is checked **before** a
code is minted, before anything is mailed, and before a row is written — so somebody hammering the
register form costs a hash lookup instead of a scrypt hash, an email, and a row for you to review
later.

Ban an address directly, or reach it through **Reject, delete and ban** on somebody's review page —
which also removes the account they already have.

Two things are deliberate:

- **A banned address sees exactly what a fresh one sees.** They get the "check your email" page and
  no email. A ban that answered differently would be a way for anybody to ask whether an address is
  banned, or whether it ever had an account here.
- **Admins cannot be banned**, by config or by role. Remove the role first.

Lifting a ban is one click and takes effect immediately — the list is cached in memory and the cache
is invalidated by the event bus, because it is consulted on the cheapest path in the system.

---

## Writing content

Pages live in the `content` table and are written at `/admin/content`.

**An address nothing answers is a 404.** `/` is the exception: before a community has written a
front page, it gets the placeholder one. Everything else — a typo, a page that was unpublished, a
link from three years ago — gets a *not found* page in the community's own colours, with the way
back on it and, for somebody with no session, the offer to sign in and be returned to what they were
asking for. (It used to be the front page with a `200`, which lies to a person, to a search engine
and to anything automated.)

| field | meaning |
|---|---|
| uri | the path this page answers on, e.g. `/about`; must start with `/` |
| title | shown in the browser tab and available to the template |
| kind | `markdown`, `html`, `page`, or one of the six that are filled in from what the community holds |
| published | the day it counts as published, which orders every listing. Defaults to the first save; move it when bringing old writing in, so 2011 says 2011. |
| template | which template wraps it; `(none)` serves the body alone |
| published | an unpublished page is not there: its address answers 404 like any other address nothing answers |
| body | the source, as you typed it |

The three kinds:

- **`markdown`** — rendered to HTML, then wrapped in the template
- **`html`** — an HTML fragment, wrapped in the template as-is
- **`page`** — a whole document served exactly as stored; the template is ignored

Markdown has everything switched on: tables, strikethrough, task lists, footnotes, autolinks,
heading anchors (so deep links work with no effort), image attributes, and YAML front matter. Raw
HTML in markdown is allowed — anybody who can edit content can already publish a whole `page`
document, so blocking a `<script>` in markdown would be a lock on a door with no wall around it.

**All styling goes in the template or the page.** There is no stylesheet to serve and nowhere to put
one. Images should be inline SVG. That is a resource budget, not a preference: a page costs one
request.

### Version history

Every save is kept. The editor has a **history** link, and it lists every version with who changed
it, when, and what moved — the title, the template, the folder, the field values, the published or
human-only flags, and how many lines the body gained or lost. Clicking one previews the page as it
was, in a modal, rendered through the current template because that is what restoring it would
produce.

This is meant to replace keeping a site in git, so a version is the **whole page** rather than just
the body. Storage is a snapshot every ten versions and a patch in between, so fixing a typo on a
long page costs a few dozen bytes and rewriting the page costs the page.

A version that cannot be rebuilt says so rather than showing you an older one. Deleting a page
deletes its history with it — there is no undelete.

**Changes** next to any version after the first shows what that save actually did, line by line:
what went, what arrived, and nothing else. Unchanged lines away from a change are left out, so a
one-word fix reads as a one-word fix. An edit that only touched the settings says so rather than
showing an empty diff.

**Restore** puts an old version back — and it is a *save*, not a rewind. The old text becomes the
newest version and everything before it stays, including the edit you are undoing. Nothing is
deleted and nothing is rewritten; this is `git revert`, not `git reset --hard`. It brings back the
words, the title, the template, the folder and the field values, but **never the old address** —
that uri may now belong to something else.

**Renaming** a page in the editor renames it. Changing the uri moves the page, keeps its id and
keeps its history; it does not leave a copy behind at the old address. Renaming onto an address
another page already answers on is refused by name.

### Taking it away, and bringing it back

**Download everything** on `/admin/content` gives you every page and every template as one JSON
file. There is a link beside each page's editor for that page on its own.

Each row carries a **merge key** — a uuid, stamped once when the page is written and never changed
afterwards. That is what makes the file useful rather than merely a copy: a uri is an address and an
id is a row number in one database, and neither survives a page being exported, edited somewhere
else and brought back. The key does, so **bringing a bundle back is a merge**:

| what arrives | what happens |
|---|---|
| a key this site already has | that page is updated, whatever its address has become since |
| a key it has never seen, at an address that is free | a new page |
| a key it has never seen, at an address already taken | **adopted** — the page here takes the key and the contents, rather than becoming a second page at one address |
| no key at all | a new page, and it is given one |

That last row is the one that matters for tooling: something turning a directory of markdown files
into JSON will not have invented keys, and it should not have to.

**Every page an import writes is versioned like any other edit**, so an import that went wrong is
undone from a page's history rather than from a backup, and the history says the site was imported on
Tuesday rather than quietly skipping a day.

The templates travel with the pages either way, including when you download a single page — a page
that arrives without the template it names renders as a bare body, which looks exactly like the
import having failed.

**Who can do it.** Downloading needs *write pages*, not merely *read* — a bundle is every page,
including the drafts and the ones locked away from AI. Importing needs *write pages* **and** *publish
pages*, because a bundle whose rows say published would otherwise be a way to publish without the
permission to. There is no AI tool for either: a bundle is the one view of the content table that
ignores [human only](#human-only), and it stays that way by not existing for a model.

The import box takes the JSON directly, and the whole POST is capped at a megabyte — for a community
of the size this server is for, that is a few hundred pages.

### Navigation folders

Each page carries a **navigation folder** — just a name. Pages sharing one appear together, and a
template can walk the whole tree.

A page with no folder is reachable by its uri and by nothing else. That is a legitimate thing to
want, and it is also the easiest mistake to make, so the content listing flags it and
`/admin/navigation` collects every such page under "Outside the navigation". Nothing about it
changes how the page is served.

---

## Templates

Templates live in the `templates` table and are written at `/admin/templates`. A template is
[mustache](https://mustache.github.io/):

| variable | is |
|---|---|
| `{{{body}}}` | the rendered page — **three braces**, or it will be escaped and you will see markup |
| `{{title}}` | the page's title |
| `{{uri}}` | the page's uri |

A new template starts from a working skeleton. A minimal real one:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{title}}</title>
  <style>
    body{max-width:38rem;margin:2rem auto;padding:0 1rem;
         font:16px/1.6 ui-sans-serif,system-ui,sans-serif}
  </style>
</head>
<body>
  <header><a href="/">Home</a></header>
  <main>{{{body}}}</main>
</body>
</html>
```

**Saving a template re-renders every page that uses it.** That cascade is immediate — the caches are
invalidated by the event bus the moment you save, not an hour later. The template list shows how
many pages depend on each one.

### Fields a template asks for

A template can declare the values it needs, and the page editor will then ask for them. Add them in
the template editor: a **name** (lowercase letters, digits and underscore, starting with a letter),
a **type**, a **label**, optional **help**, and whether it is **required**.

| type | the editor shows |
|---|---|
| `text` | a single line — the common case |
| `multiline` | a box |
| `markdown` | a box, rendered as markdown by your template if you want it that way |
| `number`, `bool`, `url`, `date` | a single line, labelled for what it holds |

A field called `headline` is available to the template as `{{headline}}` and appears in the page
editor as its own box. A required field with nothing in it refuses the save and says which template
asked for it. Changing a template's fields does not touch pages already saved — the values live on
the page row, so removing a field hides it rather than destroying it.

This is the whole reason an operator can ask for a headline on every landing page without anybody
touching the schema.

A page naming a template that doesn't exist serves the body alone rather than failing. A template
that doesn't parse does the same. An operator typo should not take a site down.

### A template can publish a directory index

**A directory is two templates**, and they live on their own screen at
`/admin/templates/directories`. The page template renders one thing; the index renders a list with
pagination. Asking one file to be both meant opening it with a branch on `{{#directory}}`, which is
a shape somebody writes once and nobody can edit six months later — so the index has a body of its
own, seeded with a working listing the moment the box is ticked. A template written before this
existed, whose body branches on `{{#directory}}`, is left exactly as it was and keeps working.


Tick **this template publishes a directory index** in the template editor and it also publishes a
listing of every page that uses it, a page at a time. That is what lets the content system behave
like a blog: writing a post is writing a page, and the index is a property of the shape rather than
a second thing to keep in step.

| setting | what it does |
|---|---|
| where the index lives | the address of page one, e.g. `/blog` |
| entries per page | how many before it paginates |
| how page two is addressed | a pattern with `{page}` in it: `/blog/page/{page}` or `/blog?page={page}`. Empty means `/blog/page/2`. |
| order | newest or oldest first, **by when a page was created** |

Page one is always the bare path whatever the pattern says — two addresses for one page is two
entries in a search engine. Ordering is by creation and never by last edit, because a blog that
reshuffles when somebody fixes a typo is one nobody can find anything in.

The template is rendered for the listing too, so the same file does both. What it gets:

```
{{#directory}}            true only when rendering the listing
{{#entries}}              uri, title, at, excerpt, folder, plus any field this template declares
{{page}} {{pages}}        which page, and how many
{{#hasPrev}} {{prevUrl}}  and hasNext / nextUrl / firstUrl / lastUrl
{{#numbers}}              n, url, here — for a row of page numbers
{{{body}}}                a plain list, for before you have written the listing half
```

Drafts and human-only pages are never in a listing. A real page at the index's own address wins over
the listing — the page is what somebody wrote, the listing is a property of a setting.

### Pages built from what the community already holds

A directory index lists *pages*. These list the things this server already knows about — the
calendar, the address book, the members — so a community can have its own front page for what is on
without maintaining a website beside a database that already knows all of it.

Pick one of these as the **kind** of a page:

| kind | what it shows | the hole in its address |
|---|---|---|
| HTML Event Listing | what is on, a page at a time | `{{page}}` |
| HTML Event | one event | `{{event_id}}` |
| HTML Place Listing | the address book | `{{page}}` |
| HTML Place | one place | `{{place_id}}` |
| HTML Member Listing | who is here | `{{page}}` |
| HTML Member | one person | `{{member_id}}` |

**The uri is a pattern.** `/whats-on/{{page}}` and `/people/{{member_id}}` are addresses with a hole
in them; the request fills the hole and the hole becomes an argument to the query behind the page.
One token each — a URL language with two variables in it is a router, and a router is the shortest
path to a page nobody can debug. **Page one of a listing is always the bare path** (`/whats-on`),
whatever the pattern says. A page for one row is refused on save if its address has no hole in it,
because without one it could never match anything.

**The body is a mustache**, not markdown — a listing is a loop and markdown has no loops. It is
wrapped in whatever template the page names, so a feed page wears the community's layout without the
operator repeating it.

```
{{#events}} id title summary starts_on ends_on time where going limited capacity
            seats_left full open_to_public over today ics_url {{/events}}
{{#places}} id name kind slug address summary latitude longitude located {{/places}}
{{#members}} id name where summary joined {{/members}}
{{#rows}}   the same list under a name that does not care what it is a list of
```

A page for **one** of a thing gets those same keys at the top level, plus `{{{body_html}}}` — the
event's details, the place's notes, the member's own words — already rendered and already filtered.
A place also gets every field its kind declared, by name.

Every listing gets a **pagination** object:

```
{{pagination.page}}  {{pagination.pages}}  {{pagination.count}}  {{pagination.size}}
{{#pagination.has_next}} {{pagination.next_url}}   and has_prev / prev_url
{{pagination.first_url}} {{pagination.last_url}}
{{pagination.next_id}}   the id of the first row on the next page
{{#pagination.numbers}}  n, url, here — for a row of page numbers
```

**Rows per page**, **order** and — for a place listing — **which kind of place** are boxes on the
page editor, and the editor shows the address rule for whichever kind is chosen rather than a
paragraph covering all six.

| listing | orders | default |
|---|---|---|
| events | by the day they happen | the only order an event listing can have |
| places | `name`, `kind`, `newest`, `oldest` | **alphabetical** — a directory somebody looks things up in has one predictable order, and newest-first means the page moves under whoever is reading it every time anybody adds a venue |
| members | `name`, `joined`, `newest` | alphabetical |

A place listing can be narrowed to one kind — `ranch`, `vendor` — or `*` for every one, which is the
default so a listing that says nothing lists everything rather than nothing.

**Who may read one is inherited, never invented.** A place listing and a member listing need
somebody who has signed in and been approved, exactly as `/places` and `/members` do — an anonymous
request is sent to the sign-in form carrying where it was going, rather than a 404 that lies about a
page on the community's own navigation. An events feed is the exception: a stranger sees the events
marked [open to anybody](#the-file-and-events-anybody-can-come-to) and nothing else, and a member
sees all of them. **A member listing carries names and never addresses**, for the same reason the
directory does not.

**Caching is broad.** A rendered feed page is cached per address *and per audience* — what a stranger
saw must never be handed to a member — and dropped whenever anything it lists changes. One answer to
an event moves a row on every listing that shows it, on a page number that depends on how many
events there are, so working out which cached page went stale is more code and more ways to be wrong
than building them again.

---

## Files people upload

`/admin/attachments` — photographs, video, a recording, the PDF of the menu. Anything here can be
put in a page: each file shows the line to paste, and the page editor has a **picker** that inserts
it at the caret.

### Where they go

```
<root>/attachments/jpg/42/1342.blob
                   <ext>/<id % 100>/<id>.blob
```

Three levels, and each earns its place. **The extension** first, because that is how somebody with a
shell looks for something and how an operator moves all the video to another disk one day. **A
hundred buckets**, because a directory with a million entries is one that every tool on the machine
is slow in. **The id as the name**, because a file named after what somebody called it is a file
named after something they chose — and what people choose includes `../`, a null byte, and a
right-to-left override that makes `gpj.exe` read as `exe.jpg`.

Nothing on the request path parses a path: the id is a number and the extension is looked up in a
table, and the file is found by computing from both.

Writes are atomic — a temporary name in the same directory, then a move — so a server killed
mid-upload leaves the old file or the new one, never half a photograph.

**Not in the database.** A photograph in a column is read into memory to be served, copied by every
backup of the schema, and impossible to hand to a web server or an object store later. The row is
the record; the bytes are files. `AttachmentStore` is an interface with one implementation, which is
what makes the second one possible without an audit.

### What may be uploaded

An **allow list**, by extension, checked against a table of things this server knows how to serve
safely. `jpg jpeg png gif webp avif heic`, `mp4 m4v mov webm`, `mp3 m4a aac ogg oga wav flac`, `pdf
txt csv md ics vcf`.

**What the browser says the file is counts for nothing.** A declared content type is a claim by
whoever is uploading; believing it means somebody names a file `photo.png`, calls it `text/html`,
and this community's own domain now serves attacker-written HTML to members who are signed in to it.
The extension decides, and the table decides what the extension means.

Not on the list, deliberately: `.html`, `.svg` (a document that can carry script, arriving dressed as
a picture), `.js`, archives, anything with macros. A community that genuinely needs one can add it in
a line and will have thought about it once, which is one more time than a default would have.

### Who may see one

| | |
|---|---|
| **private** | the default. A signed-in, approved member. Anybody else gets a `404` — whether a private file exists is itself private, and a sign-in form is no use to the `<img>` tag that asked. |
| **public** | anybody, including somebody with no account |

### Hotlinking

`Referer` is checked, and a request from somebody else's page is refused with `403`. Without it, a
community's server is a free image host for whoever finds a url, paid for in its bandwidth and
appearing in its logs.

**A request with no referrer is honoured.** Browsers omit it on a direct navigation, from a bookmark,
under `Referrer-Policy: no-referrer` and behind several privacy settings — refusing those would mean
refusing members to inconvenience a hotlinker who can forge the header anyway. This is a bandwidth
measure, not a security boundary, which is [invariant 13](CLAUDE.md) exactly.

`attachments.allowed-referrers` names other hosts that may embed; this community's own domain and its
subdomains never need listing.

### Caching

`Cache-Control: private, max-age=86400, immutable`. **Browsers yes, shared caches never** — these are
frequently a photograph of somebody's children, and a copy in a proxy is a copy in a place nobody
here chose. The url carries an id and the bytes at an id never change, so a long max-age costs
nothing.

Recently-served bytes are also kept **in memory**, bounded by `attachments.cache-bytes` and evicting
the coldest. What it is for is one photograph and two hundred people: a picture goes up, a
notification goes out, and forty browsers ask for the same file inside a minute. One blob larger than
a quarter of the budget is never cached at all — admitting it would evict everything to hold one file
that is probably being streamed once.

### Folders and tags

A folder is a **path with no table behind it**: `suppers/2026-05`. Moving one is an update on a
prefix, an empty one stops existing, and a folder covers what is under it when you search. Tags are
words in a column, searched with a prefilter and then checked properly — because `LIKE '%cake%'`
matches "cheesecake", and somebody looking for cake pictures does not want that explained to them.

**What it is** doubles as the alt text when a picture is put in a page, which is why the field is
there rather than being a nicety: an image embedded with nothing to say about it is an image that is
not there at all for whoever is not looking at it.

### Uploading

`attachments_write` — implied by *write pages*, so anybody who writes a page can put a photograph in
one, and grantable on its own so a community can hand somebody the camera without handing them the
website.

The upload posts to `/attachment/upload`, which is **the only path on this server allowed a body
bigger than a form**. Everything else is refused from the request line, before a byte of the body is
buffered, by a gate in front of the aggregator — so the large ceiling only applies where an upload is
expected.

| key | default | meaning |
|---|---|---|
| `attachments.enabled` | `true` | `"disabled": ["attachments"]` also switches it off |
| `attachments.extensions` | the list above | an allow list; an extension this server cannot serve safely is fatal at boot |
| `attachments.max-bytes` | `26214400` (25MB) | the largest single upload |
| `attachments.cache-bytes` | `67108864` (64MB) | recently-served bytes kept in memory |
| `attachments.browser-cache-seconds` | `86400` | how long a browser may keep one |
| `attachments.check-referrer` | `true` | refuse a request from somebody else's page |
| `attachments.allowed-referrers` | none | other hosts that may embed |

### What nothing points at any more

`/admin/attachments/unused` is a **mark and sweep**. Every column in this database that holds text
somebody wrote is read, every `/attachment/…` address in it is marked, and what is left over is
listed with a button to delete the lot.

The marking is the part that matters. A sweep that keeps too much wastes disk and somebody notices;
a sweep that misses one place a url can hide deletes a photograph that is on a page, and nobody
finds out until they open that page in six months and see a broken image with no way to learn what
was there. So it reads all of these:

| | |
|---|---|
| pages | the body, the title, and the template fields |
| **page history** | every version, because restoring one has to still work |
| **suggested edits** | what is waiting in the review queue |
| templates | what an operator wrote around the pages |
| board, comments | posts and the conversations under them |
| places, events | including what each kind of place records |
| profiles | what members wrote about themselves |
| legal, messages | an overridden policy, or an email a community reworded |
| file descriptions | one attachment pointing at another |

The two in bold are the ones nobody thinks of. A page's history can point at something the current
version does not — delete it and restoring that version produces a broken page, which makes a
history that cannot be restored, which is not a history. A suggested edit is the same problem before
the fact.

**Nothing uploaded in the last 24 hours is ever offered**, whatever the scan says. A file uploaded
twenty minutes ago is very likely on somebody's clipboard or in a draft in another tab, on its way
into a page that does not exist yet. That grace period is what makes "delete everything unreferenced"
a safe button rather than a race against whoever is writing.

**The scan runs again when you press the button.** The screen you are looking at was drawn a minute
ago, and somebody may have put one of these into a page since; deleting it then would be exactly the
failure the whole screen exists to avoid. Confirming is typing *delete*, because nothing here comes
back.

**If any source could not be read, nothing is offered.** A partial scan's answer is "I do not know",
and a delete button on top of that is offering to remove files it never looked for.

**It finds addresses, not intentions.** The scan matches the url these are served at — what the
editor inserts and what anybody writing markdown by hand would type. A url built by joining strings
together inside a template is a reference it cannot see, and the screen says so rather than
pretending otherwise.

### When somebody leaves

Their uploads **stay and their name comes off**, the rule the board already follows: a photograph of
last summer is part of what everybody remembers, and cutting one person out of it leaves holes in
everybody else's Tuesday. Taking the files down as well is a separate decision an administrator makes
deliberately.

---

## Asking the community questions

`/admin/survey`. This is the engagement mechanism: ask something, and everybody in the community
has a small number in their navigation until they answer it.

| field | meaning |
|---|---|
| question | the prompt |
| kind | `free` (text box), `choice` (dropdown), `rating` (a scale) |
| order | lower numbers first |
| rating from / to | the scale bounds, for `rating` |
| help text | a line under the prompt |
| dropdown options | one per line, for `choice` |
| required | marks it, but does not block anything |
| published | unpublished questions are drafts and count against nobody |

People answer at `/survey` (configurable via `urls.survey`; the old `/self?tab=questions` redirects
there), which is a page in the navigation
rather than a tab on a profile. It opens on what is outstanding; `/survey?all=1` is everything they
have said, editable. Their answers show up in the approval review, which is what makes the survey
double as a social check on newcomers.

**Saving is a merge.** The page shows a handful of the questions that exist, so a submission
mentions a handful — anything not on the form is left alone. Clearing a box on `/survey?all=1`
takes that answer back and puts the question on their list again. The welcome flow deliberately
cannot erase: every box there is a question nobody has answered, so an empty one is somebody
skipping rather than withdrawing something they said.

### The welcome

`/welcome` (configurable via `urls.orientation`) is where somebody lands the first time they sign in
with nothing on their account. Three steps: **their name**, then **the community's questions**, then
what happens next. The questions step is the survey itself rather than a copy of it, so a question
added next month is asked of newcomers without anybody editing a welcome flow.

**The name is the one required thing in this server.** Everything it prints on somebody's behalf
says who it is from, and "ana@example.com invited you to join" is an address rather than a person —
so a profile with no name refuses to save, and **an invitation cannot be sent by somebody who has
not set one**. The admin sending invitations is not exempt: the message says who is asking.

**Three questions at a time, never more.** A wall of boxes is a thing people close; three is a
screen somebody finishes, and finishing one is what makes the next get answered. Answering three
brings the next three rather than a finish line, and the screen says how many are behind them.

**The welcome comes back.** A survey only asked on the first day measures what your community wanted
to know the day somebody joined, and then goes stale. So a returning member who has unanswered
questions lands on the same three-at-a-time screen when they sign in — headed *Welcome back*, and
skippable in one press. Once per sign-in, never twice.

**Skipping is always fine and gets them into the site straight away.** Whatever is left waits on
their questions page, and one line above every page says how many — which disappears the moment
there are none.

Everything after the name is skippable. Which step somebody reached is written on their profile as
they finish it, so closing the tab half way through and coming back later picks up where they left
off rather than at a name box they already filled in. The number only ever moves forwards: typing a
step into the address bar shows that screen but does not claim they did it, because the one thing
this number is for is knowing who is half way in.

Somebody who jumps out of the welcome entirely is not chased. Their **dashboard** asks them to
finish it, once, in the place they will actually see — which is the whole reason there is a
dashboard.

**Editing a question keeps existing answers** — answers are keyed by question id, not by the text.
**Deleting a question keeps them too**, uncounted; deleting a question should not rewrite everybody's
history.

The "how many are left" counts are maintained by a background indexer rather than computed per
request. Adding a question makes everybody behind by one, and doing that inside the request that
saved it would get slower as the community grows. Bursts coalesce, so building a survey costs one
re-index rather than one per question. `/admin/system/caching` shows how many sheets exist and how
many re-indexes have run.

The survey listing also shows **how many people answered each question**, which is the number worth
watching when you are deciding what to ask next. It comes from the same indexer rather than a query
per row — a single answer moves it by one, and a full sweep rebuilds it from the blobs — so it
trails a write by a moment rather than costing anything on the page load.

Filters on the listing: contains-of against the question and its help text, and a filter by kind.

### Retiring a question

**Delete** on a question stops it being asked and stops it counting, immediately. It does not touch
anybody's answers.

That is a soft delete, and the reason is the cascade underneath: answers live inside each person's
answer sheet, so really removing a question means rewriting every sheet in the community. Doing
that inside the click that meant "stop asking this" is too much, and it is irreversible on a button
people press by accident.

So retired questions wait at `/admin/survey/retired` with two choices:

- **Ask it again** &mdash; free, and the old answers are still attached, so they simply start
  counting again.
- **Delete for good** &mdash; the cascade. It strips the question from every answer sheet and tells
  you how many it rewrote. This cannot be undone.

---

## The dashboard

`/home` (configurable via `urls.home`), and where a sign-in lands. Three things, in the order
somebody can act on them:

1. **Waiting for you** — an unfinished welcome, unread replies, an event this week nobody has
   answered, questions with no answer on them. Each is one link to the screen that clears it, and
   the whole panel disappears when there is nothing in it. A permanent "nothing to do" box is a box
   people stop reading, and it takes the useful ones with it.
2. **The next week** — seven days of the calendar, with what this person said about each. Not the
   whole calendar: an event two months out on a dashboard is decoration.
3. **Conversations you are in** — threads they started or said something in, most recently active
   first, with the rest of the board underneath so a quiet member still sees what is being talked
   about.

It stores nothing. Everything on it is a read of what another page owns, and nothing is reachable
only from here.

---

## The admin section

`/admin` by default, configurable via `urls.admin`. A top bar, a nested sidebar, and a main area.
Every page is a real server load with its own URL, so bookmarks and the back button work.

| path | section | what it's for |
|---|---|---|
| `/admin` | **Overview** | what there is, and the last ten row changes |
| `/admin/people` | **People** | approve, promote, turn off, reject; read profiles and answers |
| `/admin/bans` | **Bans** | addresses refused before anything expensive happens |
| `/admin/content` | **Content** | write pages |
| `/admin/templates` | **Templates** | write templates and the fields they need |
| `/admin/templates/directories` | **Directories** | which templates publish an index, and the second template each one uses |
| `/admin/content/bundles` | **Import & export** | the whole site as one JSON file, and the way back |
| `/admin/navigation` | **Navigation** | which folder each page sits in, and which have none |
| `/admin/attachments` | **Files** | uploads: photographs, video, the PDF of the menu |
| `/admin/attachments/unused` | **Unused files** | what nothing points at any more, and a way to delete it |
| `/admin/survey` | **Survey** | ask the community something |
| `/admin/calendar` | **Events** | what is on, and what members have suggested |
| `/admin/engagement` | **Engagement** | every rule for getting somebody here and getting them back |
| `/admin/appearance` | **Appearance** | the colours, for the community and for the administration |
| `/admin/legal` | **Legal** | the terms of service and the privacy policy |
| `/admin/messages` | **Messages** | every message this server sends, in your words |
| `/admin/system/machine` | **Machine** | processor, memory, and a day of both, read from `/proc` |
| `/admin/system/settings` | **Settings** | every setting this server is running with, and the key for each |
| `/admin/system/events` | **Events** | the last 1,000 row changes in this process, with a live toggle |
| `/admin/system/analytics` | **Analytics** | top pages, common IPs, common members, browsers, status codes |
| `/admin/system/caching` | **Caching** | every cache's size, hit rate and invalidation count |
| `/admin/system/logs` | **Log** | the last 5,000 requests, searchable as you type |

`/admin/system` has no page of its own; it lands on Events.

**The sidebar folds.** A section's children appear when you are standing on it, on one of them, or
anywhere deeper; everything else is one press away through its parent. Thirty entries is a list
nobody reads, and most of them are nobody's business most days — reaching Bans means pressing People
first, which is what a person does anyway.

**The overview is about the community, not the machine.** Requests, error rate, mutations and live
pings moved to *System → Machine*, which is where somebody goes when they are asking a question
about a box. A debugging view on the page everybody lands on is a page people learn to skim.

**The overview leads with who is here right now**, with a link to each person's page. It used to
lead with the last ten row changes; that is a debugging view, it was interesting exactly once, and
it now lives where somebody goes looking for it at `/admin/system/events`.

**The live channel never appears in the analytics or the log.** An open tab asks it every few
seconds forever, so within an hour it would be nine tenths of every request and every page anybody
actually read would fall off the bottom of the top-pages list. It is counted instead — one number,
"live pings", on the overview.

### How the URLs work

Worth knowing, because it is what you will see in the log and what you can link to.

- **A listing is a page; the rows inside it are a panel with their own path.** `/admin/people` is the
  page; `/admin/people/list` is the table. The page embeds exactly what that path returns, and the
  filter boxes re-fetch it. Both show up in the access log as themselves.
- **Filters are query parameters on the panel.** `/admin/content/list?q=about&published=no` is a
  view of a list, and it is a link you can send somebody.
- **Identity is in the path.** `/admin/content/edit/41`, `/admin/people/review/7`,
  `/admin/survey/new`. Creating and editing are separate pages, never a form above a listing.
- **Nothing that changes anything is a GET.** Every change is a POST to the section path, answered
  with a redirect. The confirmation — or the refusal — appears on the next page and is shown once.
  Nothing meant for one person ends up in a URL or in the log.

The panels, for when you want to watch one directly: `/admin/people/list`, `/admin/bans/list`,
`/admin/content/list`, `/admin/templates/list`, `/admin/survey/list`, `/admin/system/events/stream`,
`/admin/system/caching/stats`, `/admin/system/logs/results`.

The whole section is a **404** to anybody who is not an admin, signed out or not. It does not
confirm its own existence to people who cannot use it.

Admin status is read from the database on every request, not from the session, so revoking somebody
takes effect on their next click.

### Analytics

The access log is the last 5,000 requests, in memory. It knows which **member** made each request,
because session resolution happens on the request path and writes the user id into the log.

User agents are classified into common browsers and bots — carefully, since Chrome claims to be
Safari which claims to be KHTML, and most bots claim to be a browser somewhere in the string.
Anything unrecognised is counted **and recorded verbatim**, so a spike is something you can look at
and write a rule for, rather than a bucket labelled "other".

The log search is one box matched against path, IP, agent, method, referer and status — type
`404`, or `/about`, or `curl`, or an IP.

> Both the access log and the event bus are **memory only**. A restart loses them. They are for
> looking at, not for keeping; the file log in `logs/` is the durable one.

---

## Settings, and why they are a report

`/admin/system/settings` lists every setting this process is actually running with — the ports and
limits from `config.cfg`, and everything this community's own `.cfg` says — with the **key** beside
each value, so "how do I change this" is on the same line as the thing being changed.

**Nothing on it can be edited, and that is the design.** Configuration is read once, before the
socket opens, and never again: a half-applied policy is worse than no server, and nothing on a
request path should be able to change what a domain is. The cost of that is being unable to *see*
it, because the values then exist only as fields on objects inside a running process — which is what
this screen fixes. To change something: edit the file it names, and restart.

A key this server does not recognise **stops it from starting**. That is the intended failure mode:
a typo is a setting somebody believes is applied and is not.

Credentials are never printed. `ses.access-key-id` and `gps.key` show *set* or *not set*, which is
the half worth knowing; a credential on a screen is a credential in a screenshot.

---

## Switching parts off

Everything is on. A community that wants less says so in one word:

```json
{ "disabled": ["places", "members"] }
```

| word | what goes |
|---|---|
| `board` | the discussion board |
| `calendar` | events and RSVPs |
| `places` | the address book |
| `members` | the directory of who else is here |
| `survey` | the community's questions, and the welcome that starts them |
| `invites` | inviting people by email |
| `app` | the installable shell and push notifications |
| `ai` | the endpoint a model connects to (off by default anyway) |

Off means off everywhere at once: the path stops answering, the entry disappears from the
navigation, and anything that hangs off it goes with it. It beats the block's own `enabled`, so
`"places": {"enabled": true}` next to `"disabled": ["places"]` is still no address book — the broad switch is
the decision about what this community is, and the block is a setting for a thing that exists.

**A word this server does not recognise stops it from starting.** A typo here is a surface somebody
believes is off and is not, which is the worst possible outcome for a list whose only purpose is
turning things off.

---

## Events

`/events` by default (`urls.calendar`). Events happen on a day or across a span of them, and members
answer.

### Anybody can suggest one

`calendar.suggestions` is **on by default**. Any approved member can put something forward from the
events page; it goes into a queue at `/admin/calendar/suggestions` rather than onto the calendar.

That is a decision about what a community is rather than a convenience. A calendar only an
administrator can write to is a programme published at a group; a calendar anybody can suggest to is
a group deciding what it does. The queue is what makes opening that door cost a screen to look at
rather than control of the front page.

| | |
|---|---|
| **Accepting** | publishes it. Accepted-but-invisible is the worst of both — the person who suggested it sees "accepted" and nobody else sees anything. |
| **Declining** | keeps the row and the reason. A queue where things quietly disappear is one nobody uses twice. |
| **Editing first** | opens the ordinary event editor; saving there does not accept it. |

Two permissions: `calendar_write` creates and edits directly, `calendar_review` decides on
suggestions. Writing implies reviewing.

Turn it off with `"calendar": {"suggestions": false}` and only people with the permission create
events.

### Where an event is

An event's location is **a place from the address book, free text, or both**:

- pick a place and it gets its name, its address, and a link to its page;
- type something as well and it appears beside it — "The Oak, back room";
- pick nothing and the free text stands alone, which is right for somebody's garden.

A place is worth more than a string, because the string ends up typed four slightly different ways
across four events.

### Calendar invitations

**On by default.** *Invite everybody* on an event sends every approved member a real calendar
invitation — the kind Gmail, Outlook and Apple Mail draw with accept, maybe and decline buttons above
the message. Whichever they press comes back here and lands on the guest list, without them opening
the site at all. That is the whole point: an invitation in a calendar carries its own reminder and
sits in the week somebody is already looking at, where a link to a page is a thing to remember to
click.

**It needs inbound mail.** Every invitation says "answer from your calendar", and a calendar answers
*by email, to this server* — so invitations refuse to go out unless the `smtp` block is on. Without
it, pressing Accept sends a message nothing is listening for: the person believes they answered, the
guest list never hears, and the reminder chases somebody who did reply. Answers arrive at
`events@<your domain>`, which your MX records have to point here.

| key | default | meaning |
|---|---|---|
| `calendar.invites` | `true` | send calendar invitations at all |
| `calendar.remind-days-before` | `[7, 1]` | days before an event to nudge people who have not answered |
| `calendar.attendance-days` | `30` | how long after an event the attendance question stays useful |

**What an invitation says is not a config key.** It used to be `calendar.invite-template`, which
meant one message in the whole server could be rewritten and the other twelve could not. All of them
now live at [`/admin/messages`](#messages) — the invitation, the change, the cancellation and the
nudge among them — and `/admin/calendar` previews what will be sent.

**Creating an announced event invites everybody.** The box is on the form, ticked by default for a
new event and unticked for an edit — so an event nobody has agreed on can be saved as a draft
without landing in anybody's calendar, and fixing a typo in a title does not make everybody's
calendar buzz. Whenever you are ready, *invite everybody* on the listing does it.

Changing an event afterwards marks its invitations **out of date** in the listing; sending again is
what moves it in everybody's calendar (a client ignores an update that does not carry a higher
sequence number, which is handled for you). Cancelling sends a real cancellation, so it disappears
from their calendar rather than sitting there being wrong.

**There are no repeating events, deliberately.** Every event is written down on purpose, once. A
series expressed as a rule keeps happening whether or not anybody decided it should, and this is
built for a community where somebody says "same again next month" and means it — so the second one
is a second event, with its own guest list and its own answers.

### The calendar's own address

Invitations come from, and answers go back to, an address of the calendar's own — **not** the
address everything else is sent from. Everything else this server sends is one-way and can be a
`no-reply@`; an invitation is a conversation, and the address on it has to be one this machine
*receives* at.

| key | default | meaning |
|---|---|---|
| `calendar.events-address` | `events@` on your sending domain | where invitations come from and answers arrive |
| `calendar.events-name` | *"<community> Calendar"* | what a mail client shows in the From line |

The default is **derived rather than stored**, so a community that predates this setting needs no
change: it takes the domain from whatever `ses.from` already is — which is the domain your SPF, DKIM
and MX records are already about. `--setup-email` asks for both at the end, since that is where you
are already thinking about which addresses exist.

For it to work, an MX record for that domain has to point at this machine and the `smtp` block has
to be on. Until both are true, invitations refuse to go out rather than handing people an address
that swallows their answer.

### Emailing an event in

**Add the calendar's address as a guest to an event in your own calendar, and it appears here.** For
whoever does most of the organising — the person most likely to stop — that is one keystroke instead
of a visit to a form, and it is the difference between the calendar being current and being three
weeks out of date.

Who may do it is the same question as on the screen: `calendar_write` puts it straight on the
calendar, an approved member gets a **suggestion** in the queue exactly as they would from the site,
and anybody else is ignored. The message has to pass SPF/DKIM/DMARC, because *From* is a claim
rather than a fact.

It keeps the UID it arrived with, so when the sender changes the event in their own calendar and it
comes again, it updates rather than making a second one.

**The location is matched before anything is created:** the name and address are compared with what
is already in the address book (case, spacing, punctuation and a leading "The" ignored), then — if
[geocoding](#geocoding) is on — anything within three hundred metres. Only when neither finds
anything does a new place appear, and it appears **unpublished**, because a place a machine made out
of one line of an email is a draft rather than a decision.

**What comes back is checked hard**, because a reply is a claim about who somebody is arriving over
SMTP:

- the sender must be an approved member here;
- **the attendee named in the file must be the sender** — without this, anybody who can send an
  email could accept on somebody else's behalf;
- the event must exist and not be cancelled;
- the reply must not answer an older version of the event;
- the message must have passed SPF/DKIM/DMARC.

Anything that fails is **accepted and ignored, never bounced**: a rejection teaches somebody's mail
client that this address is broken, and the bounce lands on a person who did nothing but press a
button. Their answer does not register, and the nudge asks again.

**"I would rather it were Tuesday."** Some clients can propose another time. That is recorded on
their answer as a suggestion and shown on the event page to whoever keeps the calendar. An attendee
can never move an event themselves.

Taking one asks the question that actually matters: **what happens to the answers?** Forty people
said yes to a Tuesday and it is now a Thursday — keeping their answers claims forty people are
coming to an evening none of them agreed to, and clearing them starts from nothing. Which is right
depends on how far it moved, so the box is there and unticked by default, and whoever moves the
event decides.

Clearing keeps the **no**s. Somebody who said they could not come has told you something that is
probably still true, and asking them again because the day shifted by two is how a community teaches
people that answering does not stick. Either way the sequence goes up and everybody gets a fresh
invitation, because their calendar is holding a day that no longer exists.

### The file, and events anybody can come to

Every event has a **calendar file** at `/events/<id>.ics`, linked from its page as *Add to your
calendar*. An invitation reaches whoever was a member on the day it went out; this reaches whoever is
looking at the page now — somebody who joined last week, somebody whose client threw the invitation
away, somebody reading it on a phone that is not their mail client. It keeps the event's UID, so
taking a copy and being invited properly later is one entry rather than two.

**Open to anybody** is a button on the event's admin screen, off until somebody presses it. It is a
decision about one evening rather than a setting to leave on, which is why it is not a checkbox in
the middle of the form. With it on:

- the file can be fetched by anybody, with no account here;
- an answer arriving by email from an address nobody recognises is **written down** instead of
  ignored.

Those answers go on a list of their own, not on the guest list. **They take no seat** — capacity is a
promise to the people you can actually reach, and a stranger's answer is a fact about interest rather
than a claim on a chair. Members see them on the event page by name (or as *a guest* when their
calendar sent no name), never by address.

The admin screen shows the addresses, because the decision being made there is about an address:
**invite them.** Somebody who found out about a thing, said they were coming, and has never been
asked to join is the strongest lead a small community gets. When they join and are approved,
everything they said from outside becomes an ordinary answer — with a seat, in the guest list — for
every event that has not happened yet. An event that is already over is skipped: turning up in a
guest list for last March would be inventing a history rather than keeping one.

Turning it back off keeps what already arrived. Those people still said it.

**Nudges and no-shows.** Anybody who has not answered is nudged the configured number of days
before; somebody who said *no* is never chased, because chasing a no teaches people that saying no
does not work. The event page lists **who has not been heard from**, by name, so somebody can ask
one of them in person. After an event, anybody with `calendar_write` can mark an attendee as *not
there* — a note for the people organising rather than a mark against anybody, and never inferred
from inactivity.

---

## When people can come

`/when` by default (`urls.availability`). Two things, and the split is the whole idea.

**A normal week** is the shape of somebody's life: Tuesday evenings, most of Saturday. It is true
for years, it takes a minute to fill in, and it never needs touching again.

**A calendar** is where the exceptions come from. Somebody pastes the address of the calendar they
already keep — Google's "secret address in iCal format", a published Outlook calendar, a `webcal://`
address from Apple — and the fortnight they are away stops being a fortnight the community plans
around them. **Nothing but the times is kept.** This server reads a feed, reduces it to pairs of
numbers, and stores those; no title, no location, no guests. What somebody is doing on Thursday is
not the community's business, and a table that held it would be a table somebody eventually puts on
a screen.

### The grid

Anybody with `calendar_write` — the people who put events up — sees the aggregate at the bottom of
the same page, and the three best hours are printed on the event form itself, which is the moment
the question actually gets asked.

Each cell is an hour of a weekday and carries two numbers:

| | |
|---|---|
| **ideal** | how many people would like to be free then |
| **clear** | how many of those are *also* free at every occurrence of that hour between now and the horizon |

The gap between them is the useful part. "Sixteen people love Tuesday evenings and four are actually
clear for the next month" is a different fact from "four people like Tuesdays", and an average would
hide it. **It is a fold, not a forecast**: every Tuesday 7pm in the horizon is collapsed into one
cell, so the question it answers is *could this be our Tuesday* rather than *is next Tuesday free*.
For one particular date, put the event up and watch the answers come in.

**Counts, never names.** Who is free on Thursday is a question about individuals that nobody agreed
to answer, and a screen that answered it is a screen people stop putting their calendars into.

### The assumption

**Somebody who has said nothing is still counted**, from a guess: weekday evenings from 16:00 to
22:00, and from 09:00 at a weekend. That is wrong about individuals and roughly right about groups,
and it is the entire reason the grid says something useful on the first day rather than after a
campaign to get everybody to fill a form in. The page says out loud what is being assumed about you,
and anything you enter **replaces it completely** — so the people who care most are the ones who move
the picture, which is the right way round.

### When the calendars are read

Once a day, at `availability.refresh-hour` (midnight by default), for everybody at once. Every read
after that is a read of a cache table that expires at the next refresh. Fetching on render would be
a page whose speed depends on Google having a good day, and two hundred members opening it would be
two hundred requests to the same handful of hosts.

The grid itself is rebuilt whenever anything changes — a window, a link, an approval — because that
is local arithmetic and somebody who has just typed their evenings in should see them.

A calendar that **stops working** is written down with the reason and shown to the person whose
calendar it is; the last good answer is kept, so one bad night does not make somebody look free for a
fortnight they are away. A link that silently stopped working is a member the grid quietly starts
lying about.

| key | default | meaning |
|---|---|---|
| `availability.enabled` | `true` | `"disabled": ["availability"]` also switches it off |
| `availability.refresh-hour` | `0` | the hour of the daily pull, 0–23 |
| `availability.horizon-days` | `28` | how far ahead a busy block counts against an hour |
| `availability.max-links` | `5` | calendars one person may attach |
| `availability.fetch-timeout-seconds` | `10` | how long to wait on somebody else's server |

### What this server will and will not fetch

A url somebody pasted is an instruction to make a request, which makes this the one place in Hearth
where server-side request forgery could happen. So:

- **https only** (`webcal://` is rewritten, because that is the address people actually have);
- **public addresses only**, checked *after* the name resolves — a name that resolves to `127.0.0.1`
  is the whole trick, and checking the text would never notice;
- **no redirects**, because a redirect is a second address nobody checked;
- **a timeout and a 4MB ceiling**, so a slow or enormous feed costs one member's slot rather than
  the nightly pass.

Refusals happen when the link is added, not at 3am, so the person finds out while they still have
the right address on their clipboard.

### What it does not do

Recurring events in somebody's calendar are expanded for **daily** and **weekly** rules, including
intervals, named days, `COUNT`, `UNTIL` and cancelled instances. A **monthly or yearly** rule
contributes only its first occurrence: getting "the third Thursday" right is a fortnight of work for
one hour of one week, and guessing at it would be worse than saying so here.

An **all-day** entry is read as 09:00 to 22:00 rather than as the whole 24 hours. A calendar that
knows when the bins go out would otherwise black out every evening in the week.

---

## Members

`/members` by default (`urls.members`). Everybody who has been approved, with what lets you
recognise them: what they are called, roughly where they are, and the first line or two of what they
wrote about themselves. Clicking through shows the whole profile at `/members/<id>`.

**Both pages need a session and approval.** A directory of a community's members is the single most
valuable page on this server to somebody who should not have it, so the check is the first thing the
handler does rather than a condition on a template. A stranger is sent to sign in; somebody waiting
to be approved gets the waiting page they get everywhere else.

**Neither page carries an email address.** The admin section shows addresses because approving
somebody is a decision about an address. A member looking at the directory is looking at people, and
a member list is the easiest thing in the world to screenshot. Somebody who has not chosen a display
name is shown the part of their address before the `@`.

Somebody waiting to be let in, or whose account has been turned off, is not on it. Turn the whole
thing off with `"disabled": ["members"]`.

---

## Comments

The discussion board, every event and every place in the address book all take comments, and they
are the same thing in all three: what people said, in reading order, with the author able to edit
their own and somebody with the permission able to take one down.

**They do not expire.** A thread is what the community decided, and a memory with a fortnight's
horizon is not one. Posts on the board still age out — that is the `board.expiry-days` setting —
but nothing removes a comment except a person.

### Long threads clump

Once a thread passes twenty comments it arrives folded. Older conversations become one line with a
count on them ("March — 34 comments"), and the recent ones are open underneath. Opening a clump
shows it.

Grouping is by the age of the **top-level** comment, and a whole reply tree goes wherever its root
went. Bucketing each reply by its own timestamp would put somebody answering a two-year-old question
under "this week" with no question above it.

### Who can take one down

| where | permission |
|---|---|
| the board | `board_moderate` |
| an event | `calendar_moderate` |
| a place | `places_moderate` |

**Per section on purpose.** Keeping the address book tidy is a job somebody was given for the
address book — it is not a power over everything anybody has ever written. `calendar_write` implies
`calendar_moderate` and `places_write` implies `places_moderate`, so whoever runs a section can keep
it tidy; the reverse is not true, so a community can hand somebody the moderating without handing
them the editing.

Anybody can always take back their own words. A removed comment keeps its row, blanked, so the
replies underneath it do not become orphans.

### They update themselves

A new comment appears in an open page without a reload, on the same live channel the board uses —
and nothing that is being typed is touched. See [the board](#the-discussion-board) for what that
costs and how it works.

---

## Projects, routines and what you did

`/tasks` (configurable via `urls.tasks`, off with `"disabled": ["tasks"]`). A project is a list, a
routine or a board, and it decides what it calls its own items &mdash; "exercise", "chore", "step".

**Two kinds of ownership, and one nullable column between them.** A project with an owner is that
person's: nobody else opens it, administrators included. A project with none belongs to the
community, and every approved member can use it &mdash; which is how a committee's list of things to
do before the summer party works.

### Definitions and occasions

The thing most worth understanding. A **definition** is what something *is*: its name, how it is
performed, what it is measured in, where the form came from. A **task** is one occasion of it on one
project. An **entry** is one recorded set, with the time it happened.

That split is why history survives everything. Delete the task and the entries stay; move the
exercise to next year's routine and it still knows what you lifted last March; reword the
instructions and nothing that happened changes.

### What a set can be

| measured in | records | example |
|---|---|---|
| `none` | nothing | it is done or it is not |
| `weight_reps` | weight, reps | 60kg x 8 |
| `bodyweight_reps` | reps | 12 reps |
| `weighted_bodyweight` | weight (signed), reps | +10kg x 5, or -20kg assisted x 5 |
| `duration` | seconds | 1m 30s |
| `duration_weight` | seconds, weight | 1m at 24kg |
| `distance_duration` | distance, seconds | 5km in 26m |
| `weight_distance` | weight, distance | 40kg for 40m |

**The weighted-bodyweight case is signed on purpose.** Positive is weight added, negative is
assistance taken off, and they are one number because they are one axis &mdash; the first unassisted
rep is where it crosses zero. Two measures would put that week in the gap between two charts.

Charts ask the measure what "more" means rather than assuming: tonnage for a barbell, distance for a
run, seconds for a plank. A chart that silently rewarded slower running would be worse than none.

### The three scores

Every occasion &mdash; a set, or a chore ticked off &mdash; can carry three numbers, one to five:

- **how hard** it was,
- **how long** it took,
- **whether it was worth doing.**

They come apart on purpose. The exercise that is exhausting and achieves nothing is exactly what
somebody is trying to find, and neither the weight on the bar nor the clock can tell you. What comes
out is **impact for time**, which is the number to tune towards, and the definition screen says
plainly whether something is earning its place.

**Nothing is stored for "did not say", and no box is pre-selected.** A missing score is a different
fact from a middling one, and a form that started in the middle would quietly turn every unanswered
set into an average one.

### Rest between sets

`rest_seconds` on the **definition**, not on the task. A heavy squat wants three minutes in every
routine it ever appears in; somebody who wants it different for one block takes a copy of the
definition, which is what taking a copy is for. A copy that has not set its own inherits the
original's.

The timer is **rendered by the server** — "1m 20s since your last set, rest 3m" is on the page before
any script runs — and `/~rest.js` only makes the number move and say when the rest is up. A gym is
the worst network anybody uses regularly, and a timer that exists only once JavaScript has loaded is
one missing at the moment it is wanted.

### Supersets and circuits

Items on one project sharing a **group name** are done together. Two modes:

| mode | what it means | where the rest goes |
|---|---|---|
| `related` | a **superset**: alternate between them | **after the round**, not between the parts |
| `sequenced` | a **circuit**, or a warm-up building into a working set | after each, as usual |

That difference is the whole point. Resting between the two halves of a superset turns a time-saving
device into one that takes longer, so the item screen does not offer the timer there and says why.
A sequenced group is numbered on the project page, and each item says which one comes next.

A group is a name in a column rather than a table, so there is nothing to create and nothing left
behind when the last member leaves it.

### One-rep max

Shown for `weight_reps` and `weighted_bodyweight` and nowhere else, from the **best single set**
ever recorded.

- **Epley's formula**, `weight × (1 + reps ÷ 30)`.
- **Only up to 12 reps.** Past that the answer is dominated by how long somebody can suffer rather
  than by what they can lift. It returns nothing rather than a confident figure, because a number on
  a screen is a number somebody tries to beat.
- **Nothing for an assisted rep.** It is easier than one unassisted, so there is nothing to
  extrapolate towards.
- Rounded to the half kilo, and labelled *a direction, not a target*. The plates come in halves and
  the input does not carry the precision that printing 102.83kg would claim.

For bodyweight work with load added, it is an estimate of the **added weight**, and says so — the
body's own mass is not something this server knows.

### Routines, boards and getting out of the way

- **`repeat_days` makes something come back.** Ticking it moves the date forward rather than closing
  it, because a list somebody rewrites every Sunday is one they stop rewriting in March.
- **Phases turn a list into a board.** Give the columns in order; reaching the last one counts as
  finished. No phases is a plain list.
- **`hide_done_hours` decides what is in front of you today**, 24 by default. Nothing is deleted by
  it, ever &mdash; the entries are the whole point, and a chart of six months needs six months of
  them.

### Sharing

`tasks_share` is the permission for the community's half: its own projects, and the shared library
of definitions. Keeping your own needs nothing &mdash; asking permission to write a todo list would
be absurd.

**Adoption is a pointer, not a copy.** Take a copy of a shared definition and it stays pointed at
the original, so improving the community's form notes improves everybody's. What you get of your own
is somewhere to put your target and your own notes, which is where "the same movement, but I do six"
goes without forking the whole thing.

### What an administrator can see

**/admin/tasks** shows the community's own projects, the shared library, and a list of *which*
members keep something of their own &mdash; a name, a count, and when it was last touched.

**It deliberately cannot open one.** Knowing that half the members have a routine and three have not
touched theirs since February is an administrative fact worth having. What somebody lifted on Tuesday
is not, and a screen that showed it would be a screen people stop putting anything into. If you
genuinely need one, ask &mdash; its owner can download the whole thing from their own page, and it
is in their data export because it is theirs.

### Doing it from a phone

The item screen is built around one gesture: the boxes come pre-filled from your last set, and the
button under them is the biggest thing on the page. No JavaScript is required for any of it &mdash;
each set is a form that posts and comes back, which is a page load per set and completely fine, and
the alternative is an app that stops working in a basement gym with one bar of signal.

---

## Geocoding

Turning an address into coordinates. Two things need it: a place in the address book, so a map link
works and an event arriving by email is matched to somewhere you already have rather than becoming a
duplicate; and a member who has said where they are, so that whoever is booking a hall can see how
far people would have to come.

**It is on by default, on Nominatim, which needs no account and no key.** That is a reversal — it
used to be the one thing here that was off until asked for, on two arguments. The second argument
(it can cost money) does not apply to the default: OpenStreetMap's service is free, has no signup
and takes no card. The first (it sends an address somebody typed to another company) is real, and
the answer to it is that the addresses are now the point of the feature. A community that had to
find a config key first would never have the travel chart, and would go on planning around whoever
complains loudest about the venue.

Switching it off is one line:

```json
{ "gps": { "enabled": false } }
```

`--setup-gps` is the walkthrough, and it is now about the *paid* alternatives rather than about
turning anything on.

**Tell it how to reach you.** Set `gps.contact` in `config.cfg` to an email address. Nominatim's
policy asks for a User-Agent that identifies the application *and* gives a way to get in touch;
this server always sends the first half, and without the second a client can be blocked without
warning — which from inside looks exactly like geocoding quietly stopping. The Async screen and the
Settings screen both nag for it until it is set. If you *write a `gps` block naming nominatim* and
leave the contact out, the server refuses to start: that is a decision somebody made on purpose,
and it gets the strict answer.

### Why Google, Mapbox and HERE are not offered

Because this server **keeps** the answer — a coordinate written onto a place row, indefinitely — and
their terms do not allow that. Google requires results to be used with a Google map. Mapbox charges
a different, higher rate for storing them. HERE has the largest free tier of any of them and caps
caching at thirty days. Picking one of those off a comparison table puts you in breach on the day
you save your first address, and nothing anywhere would tell you.

### The three that do allow it

| service | key | what it asks of you |
|---|---|---|
| **Nominatim** (OpenStreetMap) | none | at most one request a second, a way to reach you in the User-Agent, no bulk work, and results cached rather than re-asked. All four are things this server does anyway. |
| **OpenCage** | yes | a free tier for testing, paid from there. The clearest terms of the three: results may be kept permanently, even after you stop being a customer. |
| **Geoapify** | yes | a larger free allowance; attribution to Geoapify required on the free plan. |

Nominatim is the default and is genuinely right at this scale — a community geocodes a few dozen
places and a few hundred members, once each.

### Everything goes through a queue

**Nothing is ever geocoded while somebody is waiting for a page.** Saving a place, or saving your
address, writes the row and puts an ask on a queue; the coordinates arrive a minute or two later.
That used to happen inside the save, which meant an admin adding forty places on a Sunday afternoon
made forty requests as fast as they could type — and waited on each one.

| | |
|---|---|
| **Pace** | one request every **1.5 seconds**, across the whole machine and every community on it |
| **Waiting** | up to **1000**; past that an ask is refused and the refusal is recorded |
| **Backoff** | a failure costs **10 seconds**, doubling each time, up to ten minutes; reset by any answer |
| **Giving up** | after **5** failures the job is dropped and said so |

The pace is deliberately slower than any of the three services allow. There is no configuration key
for it: it is a promise this server makes as somebody else's client, and with a queue to wait in the
difference between one a second and one every 1.5 seconds is a few minutes on a batch nobody is
watching — against a real risk of being blocked, which is found out days later when nothing has
resolved.

**"No answer" and "failed" are different, and only one of them causes a backoff.** A service saying
it has never heard of an address is a complete, correct answer; treating it as an error would put
the whole queue into backoff because somebody typed a street name wrong.

### When an address will not resolve

Every address — a member's and a place's — records how its last lookup ended, which service gave
that answer, and when it may be asked about again. There are two ways to fail and they get different
treatment, because collapsing them is wrong in both directions at once.

| state | what it means | what happens next |
|---|---|---|
| **waiting** | never asked about | goes on the queue as soon as anything is looking |
| **placed** | it has a point | nothing, until the address is edited |
| **no such address** | the service answered and has never heard of it | **nothing, on its own.** The same question to the same service tomorrow gets the same answer |
| **cannot reach the service** | the service did not answer | retried on its own: 15 minutes, then an hour, four hours, then daily, forever |

An unreachable service **keeps whatever point is already stored** — it is not a statement about the
address. A "no such address" clears the point, because there is none.

Three things re-open a **no such address**:

1. **Editing the address.** It is a different address and deserves its own answer.
2. **Switching geocoding service.** The service that answered is stored with the answer, so
   changing `gps.service` makes every address the old one could not find due again, with nobody
   having to remember. That is usually the whole reason somebody switches.
3. **System → Async → *try the given-up-on ones again***. For after somebody fixed whatever was
   wrong at the other end. It never re-asks about an address that already has a point.

The Async screen counts all four states, for members and for addresses separately. The address book
listing shows the reason beside any address with no coordinates — which is a thing an admin can
usually fix in ten seconds by adding a town, and previously could not find out about at all.

A member sees the same distinction on their own page, in their own terms: *"That address could not
be found — a town and a postcode is usually enough"* versus *"The lookup service could not be
reached. Nothing is wrong with the address; this keeps trying on its own."* The second one names
nothing technical, because there is nothing for them to do about it.

**A coordinate somebody typed by hand is never overwritten** — they were standing in the field, and
a geocoder is reading a string.

### The Async screen

**System → Async** is where all of that is visible: how many are waiting, what is being asked right
now, whether it is backing off and why, and the last two hundred finished asks for this community
with what each one did. Two buttons: *look up anybody still waiting*, for after a service was down
all day, and *drop what is waiting*, for after somebody queued a mistake.

The counts are the machine's, because one queue serves every community on it. The list is this
community's only.

---

## Where members are, and how far they would travel

A member can give an address on their own page, under **Your address**. It is the most sensitive
thing this server holds and it is treated accordingly.

**Nobody can read it.** Not other members, not you, not an administrator, not anything automated,
not a model. It is not on their profile, not in the members directory, not on the review screen you
read before approving somebody, and not in the copy of their data you can download for them. It
lives in columns that the query building a profile does not name — so there is no version of
"somebody forgot to hide it" available, which is a stronger guarantee than a rule.

**What leaves it is a distance.** When an event has a place, and that place has coordinates, the
event's edit screen shows a chart: how many members are within so many miles, in buckets. No names,
no order, no map, no "who lives nearest". That is the whole feature, and it is worth having because
a planner who can see that a hall puts half the community on an hour's journey books a different
hall.

- **Somebody who gives no address is still counted, roughly**, from the town on their profile —
  placed at its centre. The chart says how many of its numbers came from a real address and how
  many from a town, because those are different claims.
- **Somebody who has said nothing is not invented.** The chart says "31 of 48 members counted", so
  you know what you are looking at. There is no sane default for where a person lives.
- **Clearing the box removes the coordinates too.** A point beside a deleted address is exactly the
  thing somebody was asking to be rid of.
- **Erasing an account erases it**, with everything else.

The privacy policy this software ships describes all of the above, including that the address is
sent once to a geocoding service. If you turn geocoding off, the section does not appear on
anybody's page at all — an empty box that does nothing is worse than no box.

### Miles or kilometres

Per domain, beside the timezone, and for the same reason: this is a program for people who meet in a
room.

```json
{ "units": "imperial" }
```

`metric` (kilometres) is the default; `imperial` gives miles and slightly different buckets, because
a mile is a walk and three kilometres is not. It is the only place this server prints a distance.

---

## Somebody's data, and getting rid of it

The [privacy policy](#terms-privacy-and-cookies) this software ships promises three things about
personal data, on your behalf, with your data protection authority named two paragraphs below. All
three have a button.

| What they ask for | Where |
|---|---|
| "Show me what you hold about me" | their own page → **Your data** → *Download my data* |
| "Give me a copy I can take elsewhere" | the same button; it is one JSON file |
| "Delete my account" | the same tab, with a typed confirmation |

You can do all three for somebody who wrote to you instead: `/admin/people/review/<id>` has
*Download everything about them* and *Delete this person*.

**What deleting removes**: the account, the profile, the answers, every session, the notifications,
the push subscriptions, the invitations sent to them, their answers to events, and their name from
every invitation they sent, every page they edited and every event they created.

**What it keeps, and why**: what they wrote on the board, with their name taken off it. A
conversation other people replied to is theirs as well, and cutting one person's words out of it
leaves holes in everybody else's. If they asked for those gone too, tick *also take down what they
wrote* — that is a decision to make when it is asked for, not by default. A **ban** on the address
also survives; that is the one piece of data the policy claims a legitimate interest in keeping.

The export is built the moment somebody asks and is never written to disk. A file holding a complete
record of a member is a worse thing to have lying around than the database it came from.

**The clock is a month.** Under the UK and EU GDPR you have one month to answer a request. These
buttons are the difference between that being a minute and being an afternoon with a SQL client.

---

## What members see of each other

**Names, never email addresses.** The board, the guest list on an event, the dashboard, the
notifications and the emails built from them all print what somebody chose to be called. So does
what a connected model is shown. Somebody who has not set a name yet is "a member" — not the part of
their address before the `@`, which is still most of an address and usually most of a real name.

The **admin section is the exception**, and deliberately: approving somebody is a decision about an
address, and an administrator who cannot see one cannot do the job.

If you want people to be recognisable to each other, that is what the welcome is for — it asks for a
name first, before anything else, and a profile will not save without one.

---

## Triage: votes and flags

Every conversation and every comment carries two small buttons and a flag.

**Votes are a signal and nothing else.** Nothing is hidden, reordered, scored down or removed
because of them. A community where votes bury things has handed its judgement to whoever votes most.
What the numbers are for is telling somebody where to look. One vote each, changeable — pressing the
same one twice takes it back.

**A flag is a request for a person**, with a reason, and it does exactly nothing on its own: whoever
wrote the thing is not told, and the thing stays where it is. It appears at `/admin/board/flagged`
with the words on the page, so triage means reading rather than opening twelve tabs.

Two outcomes, both real:

- **looked at it, it is fine** — clears the queue and *keeps the record*, so the next flag on the
  same thing arrives with its history behind it;
- **take it down** — which needs that section's own moderation permission, so a board moderator
  cannot remove a comment on a place by way of this queue.

**A model can read the queue and cannot act on it.** `board_flagged` returns everything waiting with
the reasons and the words; there is no tool that removes, clears or hides anything. That is a
stronger rule than a permission check, because there is nothing to route around — the software
gathers the signal and a person keeps the judgement.

---

## Who can do what

Roles are at `/admin/people/roles`, and [the permission list](#roles-and-permissions) is what a role
is made of. Three rules are worth knowing because they decide what a screen looks like:

- **A permission is asked for where the thing happens**, not only where the screen is. Somebody with
  `board_moderate` can take a comment down on the board itself, not just from the admin section.
- **A control that would refuse is not drawn.** Somebody who can write pages but not publish them
  does not get a publish checkbox — they get a line saying who can. A link into a section somebody
  cannot open is not a link.
- **The overview shows what you can reach.** It sits behind the mildest permission there is, so each
  block on it asks for its own: who is here and the community counts need `people_read`, the content
  counts need `content_read`, the process counts need `system_read`. A narrow role sees a page that
  says very little, which is correct.

A refusal names the permission in the words the role editor uses — "It needs 'Approve somebody
waiting to join'" — so the person reading it can ask for the right thing rather than asking to be
made an administrator.

### Three things being approved is enough for

`board_read`, `board_write` and `board_vote` are **not** in the role editor, because every approved
member already has them. Reading the board, posting and voting are what most people are here for; if
they worked like every other permission, an upgrade would leave you with a board only administrators
could read, and the fix would be a role granted to everybody, which is a permission system with one
row in it.

They are real permissions rather than a hard-coded "is this person approved" for two reasons. The
board asks the same `Access.can` every other screen asks, so there is one place that knows how this
adds up. And an **agent** acting for somebody is held to them: when member-scoped connections exist,
a member's agent will be able to read, post and vote, and nothing else, without a line of that being
written twice.

`board_moderate` is deliberately not among them. It acts on somebody else's words.

---

## Colours

`/admin/appearance`. Six colours, twice — once for a light screen and once for a dark one.

**Every page is light unless the person reading it says otherwise.** There is a switch in the bar,
next to the sign-out; what it chooses is kept in that browser's local storage and applied before the
page is painted, so it survives navigation without a flash and without a cookie. It is per browser
rather than per account, because the same person can reasonably want dark on a phone at night and
light on a laptop at work.

This used to follow the operating system's `prefers-color-scheme`, and that was wrong for a reason
worth writing down: it meant a community that had chosen its colours had them shown to roughly half
its members in a scheme nobody had ever looked at, and no way for a person to disagree with their
laptop.

| slot | what it is |
|---|---|
| Accent | links, buttons, and anything asking to be pressed |
| Text | the words themselves |
| Background | the page behind everything |
| Panel | cards and boxes that sit on the background |
| Quiet text | labels, timestamps, anything secondary |
| Lines | borders, rules, table separators |

There are two independent palettes:

| scope | where it shows |
|---|---|
| **The community** | every page a member sees, and **every email this community sends** |
| **The administration** | the admin section, and the [legal pages](#terms-privacy-and-cookies) |

The legal pages take the administration's colours on purpose. A community's promises are not its
decoration, and a community that themes itself into something unusual has not themed its terms into
something unusual.

**Red, green and amber are not on the list.** They mean refused, worked and careful, and a community
that could recolour them could end up with a red "approved".

Values are hex and nothing else. Anything the server cannot read as a colour leaves that slot as it
was rather than failing the save, and the pickers accept a pasted hex code. A community that has
never opened this screen has no row in the database at all, and the screen says so.

**Email uses the light palette whatever the reader's screen is set to.** Mail clients handle dark
mode badly and inconsistently — a dark background chosen here would reach half the readers as a
black box. Button text is picked as black or white by luminance, so a pale accent gets readable
buttons rather than white on white.

---

## Terms, privacy and cookies

Every community has two documents whether or not anybody has thought about them, so this server
ships a considered version of each and publishes them from the first day:

```
https://example.org/legal/terms-of-service
https://example.org/legal/privacy-policy
https://example.org/legal                    both of them, listed
```

Those paths are fixed rather than configurable, because they are quoted in every email footer and by
anybody linking from outside. **Both are readable without signing in** — most of the people who get
that link have no account yet, and "the terms you are accepting are behind a login" is not a
defensible sentence.

### What the defaults say

They are written for the shape of community this program is for: a few hundred people who meet in
person, no money moving through the site, volunteers running it. The parts worth knowing:

- **Who is not a party.** The people who wrote this software and whoever provides the machine are
  not involved in what a community does with them. In a self-hosted community that is the fact
  somebody actually has to be told.
- **Events are attended at your own risk.** Nobody vets who turns up or inspects the venue, and
  meeting somebody from the internet in person calls for the same judgement it always has.
- **Listings are not endorsements.** An address in the book is a note that something exists.
- **Liability is limited** for the administrators, the host and the software's authors, except where
  it cannot lawfully be.

> **These are a starting point, not legal advice.** Nobody who wrote them is a lawyer and they
> cannot know which country you are in. Read them. If your community carries real risk — money,
> children, medical advice, an organisation with something to lose — have somebody qualified read
> them too.

### Writing your own

`/admin/legal`, one markdown box per document. The editor starts from whatever is published today,
so "edit the terms" starts from the terms.

Saving makes the document yours, and this community stops picking up improvements to the shipped
text. **Emptying the box and saving puts the default back** — as does the button that says so.

`{{community}}` and `{{domain}}` inside the text are filled in when the page is shown, so a document
written once is still right after somebody renames the community.

### Cookies, and why there is no banner

This server sets **two** cookies, and neither of them tracks anybody:

| cookie | for | lasts |
|---|---|---|
| the session cookie | remembering you are signed in | until you sign out or it expires |
| the CSRF token | proving a form came from a page on this site | the same |

Both are strictly necessary for a service the person asked for, which under the ePrivacy rules is
the category that does **not** require consent. There are no analytics cookies, no advertising
cookies, and no third-party requests of any kind on any page.

So every page carries one quiet line in the footer saying exactly that, with a link to the privacy
policy — and no modal, no "manage preferences", nothing to click through before reading. A consent
wall here would be asking permission for something that needs none, and it would need JavaScript and
somewhere to store the dismissal, which for a site with two cookies means adding a third.

If your community adds something that *does* set a non-essential cookie, that calculation changes
and the privacy policy needs changing with it.

---

## Email

There is no email provider. The dev box prints messages to the terminal you started the server
from, spaced out for copy and paste:

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

The boot report warns about this loudly, because a server that thinks it is sending email while
printing secrets to stdout is worse than one that obviously is not.

Adding a real provider means implementing `Mailer`, which is a short closed list of flows —
registration code, login code, password reset, two factor, password changed — rather than a generic
`send()`. A template per method, and nothing in a request handler can invent a new kind of email
without the interface growing a method and somebody noticing.

### What every message carries

However a message is sent, it is built from one layout, so all of them look the same and say the
same things:

- the **community's colours**, from [Appearance](#colours);
- **why it arrived** — "you asked to sign in", "somebody invited this address", "you follow that
  conversation";
- a link to the **terms and the privacy policy**, and the sentence that using the community means
  accepting them. That is in the plain-text half as well as the HTML one: a message whose HTML
  carries the promise and whose text does not says nothing to whoever reads the text, and spam
  filters read the text.

Only the invitation carries a tracking pixel, and only because whether it arrived is a real question
for somebody who has no account yet. Notifications and digests carry nothing.

### Messages

`/admin/messages` is every message this server sends, and the words in it are yours.

| message | when it goes |
|---|---|
| Creating an account | somebody asked to sign up and needs the code |
| Signing in | somebody asked for a sign-in code |
| The second step | a password was accepted and this community asks for a second step |
| Resetting a password | somebody asked to reset one |
| After a password changes | one was changed, and this is the warning if it was not them |
| An invitation | the first message to somebody who was invited |
| The second invitation | the reminder |
| The last invitation | the third and final one, which says it is the last |
| A reply on the board | somebody replied to you, or in a thread you are in |
| The daily or weekly summary | the digest |
| A calendar invitation | an event went out to everybody's calendar |
| An event that moved | it was rescheduled and the invitation was sent again |
| An event called off | it was cancelled |
| A nudge about an event | it is close and this person has not answered |

Each one has three boxes: the **subject line**, the **first line**, and an optional **paragraph
under it**. Everything else — the layout, the button, the code block, the plain-text half, the
footer saying why the message arrived — stays in the software. A community one paste away from a
message that renders as markup in Outlook has not been given control of anything useful.

**A message you have not touched is not in the database at all.** It uses the wording that ships,
and keeps picking up improvements when the software is upgraded. Saving something different makes it
yours; saving it back exactly as it was — or pressing the button that says so — takes the override
away again.

**`{{name}}` puts a value in.** Every message can use `{{community}}`, `{{domain}}` and `{{site}}`;
each one also has its own, listed on its editing screen — `{{code}}` and `{{minutes}}` for the code
messages, `{{title}}`, `{{when}}`, `{{where}}` for an event, `{{inviter}}` for an invitation. It is a
straight replace and not a template language: there are no sections, no loops and no lookups, for
the same reason the [legal documents](#writing-your-own) work that way.

**Anything else in braces becomes nothing at all.** `{{first_name}}` in a message that has no such
value leaves a hole rather than printing the braces at somebody. The preview at the top of every
editing screen fills the message in with believable values, which is where you find out.

---

## Programs: the API and its tokens

A command line tool, a script, a git repository of markdown — anything that is not a browser gets in
the same way: a token somebody makes for it by hand.

### The copy-paste flow

A tool prints an address; somebody opens it, reads what is being asked for, presses a button and
copies a string back into the tool.

```
$ my-tool login example.org
Open this and authorize, then paste the token here:
  https://example.org/api?name=my-tool
Token: _
```

No callback, no local listener, no redirect. That is the point: it works from a machine with no
browser on it, over SSH, from a phone — and there is nothing to get wrong about which program
received what, because a person moved the string themselves.

`/api` on its own is the same page without the prompt: your tokens, what each is called, when it was
last used, when it expires, and a button to revoke each one. It is linked from your own page.

### What a token is

**A session with a bit set.** Same table, same reaper, same revocation, same answer to "is this
still valid" — because a second kind of credential is a second implementation of that question, and
the two would eventually disagree. Everything a token does is recorded as **you**: a community has
to be able to ask who put something here and get somebody they can talk to.

**It can never do more than the person who made it.** Every endpoint asks the same permission the
admin screen asks, of the same account. Turn the account off and the token stops at its next
request.

| key | default | meaning |
|---|---|---|
| `api.enabled` | `true` | answer to programs at all; `"disabled": ["api"]` also switches it off |
| `api.token-days` | `30` | how long a token lives; `0` means it never expires |
| `api.max-tokens` | `2` | how many one person may hold at once |

Two is a laptop and a build machine. A third is refused rather than rotated: rotating the oldest away
would stop whatever has been using it for a month, and the person would find out from something
breaking.

### Using it

Everything under `/api/v1` is bearer-only. **The browser's session cookie is never a credential
there** — a JSON endpoint that accepted it would be a cross-site forgery hole with no form and no
token in it, reachable from any page a member happens to have open.

```
curl -H "Authorization: Bearer $TOKEN" https://example.org/api/v1/whoami
```

| endpoint | what it does |
|---|---|
| `GET /api/v1/whoami` | who this token is, when it expires, and every permission it holds |
| `GET /api/v1/content` | the whole site as one JSON bundle |
| `POST /api/v1/content` | push a bundle; only what differs is written |
| `POST /api/v1/content?dry=1` | the same answer, writing nothing |

`whoami` exists because a tool should be able to say "you are signed in as X and may not publish"
before it starts, rather than making somebody find out from a 403 halfway through a push.

**[API.md](API.md) is the contract**: every endpoint, every error, the bundle format, and a worked
example of the whole loop. It is written so somebody can build a tool against this server without
reading any of its source.

### Pushing content

The body is a [bundle](#taking-it-away-and-bringing-it-back), so a folder of markdown turned into
JSON by your own tooling is a site. Three things make it safe to run from a build:

- **Only what differs is written.** A page whose every field matches is skipped entirely — no save,
  no event, no version. A tool that pushed the whole site on every commit would otherwise fill the
  history with edits nobody made.
- **The answer says what moved**, row by row: `created`, `updated` with the list of fields that
  changed, or `unchanged`.
- **`?dry=1` answers the same thing and writes nothing**, because a diff nobody can see before it lands
  is a diff nobody reviews.

```json
{
  "ok": true,
  "dry_run": false,
  "content": [
    { "uuid": "…", "name": "/about", "status": "updated", "changed": ["body"] },
    { "uuid": "…", "name": "/contact", "status": "unchanged", "changed": [] }
  ],
  "summary": { "created": 0, "updated": 1, "unchanged": 1 }
}
```

Reading the bundle needs *write pages* — it is every page, including drafts and the ones locked away
from AI. Pushing needs *write pages* **and** *publish pages*, since a bundle whose rows say published
would otherwise be a way to publish without the permission to.

---

## Caching and the event bus

Every write to the database announces itself: which domain, which table, which primary key.

```
#3 example.org templates/1 update
#2 example.org content/1   insert
#1 example.org templates/1 insert
```

Caches listen and drop exactly what changed. Editing a page updates it immediately; editing a
template cascades and drops every page that named it. **The TTL is a backstop for an event that
never arrives, not the invalidation mechanism** — which is why an hour is a sensible default even
for content you edit constantly.

`/admin/system/events` shows the last 1,000 events with a live toggle. If a page looks stale, that
page answers whether the invalidation happened at all.

`/admin/system/caching` shows every cache beside it: size, hits, misses, hit rate, and how many
entries the bus has dropped. A cache nobody can see the hit rate of is a cache nobody can tune — and
if the hit rate is low while the event count is high, something is invalidating more than it should.

The bus is an interface with an in-process implementation. That is the scaling escape hatch: put
several processes behind a sticky load balancer and the first thing that breaks is cache coherence —
process A edits a page and process B keeps serving the old one. The fix is one implementation of
`EventBus`, not an audit of every cache.

---

## Signing in from somewhere else

Anything that needs a session and does not have one answers `303` to the sign-in form with
`?next=` holding **the path and the query** it was asked for. Signing in returns them there.

That matters more than it sounds. Most links into a community arrive in an email or a message —
a thread, an event, somebody's profile — and land in a browser whose session lapsed weeks ago. A
sign-in that returns them to the thread is a two second interruption; one that drops them on a home
page is a dead end with no clue what they were looking at.

It survives the whole flow: the address step, the code step, a mistyped code, and the links between
the sign-in, sign-up and forgot-password forms — because somebody bounced to a form they cannot use
presses the other one.

Three rules hold it together:

- **A `next` that is not a plain path on this site is dropped, never repaired.** A scheme, a host,
  `//host`, a backslash, a control character or anything non-ASCII means the destination is thrown
  away and they land on the ordinary page. Repairing it would mean guessing what somebody meant by
  a URL that is already wrong, and this is the classic phishing redirect.
- **Approval outranks it.** Somebody who has not been let in yet goes to the welcome or their own
  page whatever they asked for. There is nothing else they could usefully be returned to, and a
  `next` that bypassed the waiting page would be a way to find out what exists behind it.
- **The admin section still answers `404`.** Anything else confirms that the path is guarded. The
  not-found page offers an anonymous visitor a sign-in link that returns them; somebody signed in
  who may not enter sees exactly what a missing page looks like.

A session whose account has been deleted is treated as signed out — cookie cleared, bounced with the
destination — rather than being shown the waiting page forever for a decision about an account that
is not there.

---

## Security decisions you should know about

**Registration forms are minted.** The register and sign-in pages have no form in their HTML; it is
assembled in JavaScript from a JSON blob, with field names derived per page load. A submission also
carries a value the script computes, a hidden field the script always leaves empty, and counts of
the mouse, keyboard and touch events the page saw. Zero events of every kind is refused.

None of that is a security boundary and it should not be treated as one — anything shipped to a
browser can be replayed by something that reads it. It makes the cheap attack stop working.
**Approval is what actually decides who gets in.**

**Session tokens are stored as SHA-256**, never in the clear. A stolen database file is a list of
hashes rather than a list of logins.

**Passwords are scrypt** with memory-hard parameters, when passwords are in use at all.

**No account enumeration.** Asking for a code, or getting a password wrong, looks identical whether
or not the address has an account.

**HSTS is deliberately not sent** while the server is HTTP-only. Pinning a browser to https for a
port with no TLS behind it is a bad day. It goes on with the TLS listener, along with
`cookie-secure`.

**A scanner shield** answers common probe paths (`/wp-login.php`, `/phpmyadmin`, `.env`, …) with a
flat 410 before anything else runs. It is noise control, not security: those technologies are not in
this server, so a request for them is a scanner.

**Only `GET`, `HEAD` and `POST`** are implemented, and `POST` only on the paths a domain's `urls`
declared. HTTP/1.0 and 1.1 only. Bodies are capped at 1MB.

---

## Backups

Stop the server, copy the stores directory, start it again:

```bash
systemctl stop hearth
cp -a /var/hearth /backups/hearth-$(date +%F)
systemctl start hearth
```

H2 is an embedded database owned by exactly one process. Copying `.mv.db` files out from under a
running server can catch a write in progress. Back up the configs directory too — it is small, and
`admin_emails` is the only way back in.

---

## Upgrading

Replace the jar and restart. The schema brings itself up to date:

- a missing table is created
- a missing column is added **in the position the code declares**, so a database upgraded through
  three releases has the same shape as one created today
- a missing index is created
- a column the code says was **renamed** is renamed, before anything else looks at what is missing.
  No data moves. That check has to come first: a renamed column is indistinguishable from a missing
  one, and adding it would leave every value behind in the column with the old name. If both names
  are present it says so and leaves them alone — that is somebody's half-finished surgery, and
  picking one to keep is not a decision a boot path should make.

It will **not** drop anything — a column the code no longer declares is reported and left alone. It
will **not** change a column's type; that refuses to start, because running against a column that
isn't the type the code thinks it is fails later, further away, and harder to diagnose.

`--verbose` prints every statement it ran. The boot report summarises:

```
  [stores]
       localhost:    opened, schema v3 -> v4, 5 change(s)
       example.org: created, schema v4
```

---

## Troubleshooting

**"That form could not be accepted. Is JavaScript switched off?"**
The page's script did not run, or the value it computed did not match what the server expected. In
a browser with JavaScript enabled, check the console: a Content-Security-Policy error means the
script's nonce did not survive whatever is in front of the server (a proxy that rewrites HTML will
break it). If it happens in every browser at once, the server and the shipped script have gone out
of step -- `ProofContractTests` is the test that catches that, and it runs the real script under
node.

**"That form expired. Please try again." straight away.**
The CSRF cookie is missing or does not match the form. Check that cookies are not being stripped by
a proxy in front of the server, and that `cookie-secure` is not `true` while you are serving plain
HTTP — a `Secure` cookie over http is silently dropped by the browser, and the symptom is exactly
this.

**Nobody can sign in on a fresh install.**
Every account starts unapproved. Put your address in `admin_emails` and restart.

**A page still shows the old content.**
Open `/admin/system/events` and look for a `content` or `templates` event with the right row id. If the
event is there and the page is stale, that is a cache bug worth reporting. If the event is not
there, the save did not happen.

**A page or template saves as empty, or a save says something is "too long".**
Fields have a ceiling. A uri, a title or a template name is capped at 512 characters; a body, a
template, a profile paragraph or a survey question is capped at a megabyte, which is also what the
column holds. Anything past its ceiling is refused by name, and nothing is written — what was there
stays there. A whole submission past a megabyte gets an HTTP 413 from the request reader before
this code sees it.

**The server refuses to start.**
It prints one line saying what and where. Run `--check` to see it without binding a port. The usual
causes are an unknown key (often a typo), a filename that isn't a valid domain, or two `urls`
pointing at the same path.

**Binding 80 or 443 dies with "Permission denied".**
The kernel reserves ports below 1024 for root and Hearth is not running as root, which is correct.
See [Binding 80 and 443](#binding-80-and-443) — the fix is one sysctl, not a `sudo java`. If the
sysctl is already set and it still fails, something else holds the port: `ss -ltnp | grep :80`.

**The port binds but nothing reaches it from outside.**
Two different doors. `ss -ltnp` says the server is listening; `sudo ufw status` says whether the
host lets anyone in. Also check `bind` — `127.0.0.1` listens locally on purpose.

**A domain 404s and you think it shouldn't.**
Run with `--verbose` and make one request; the descent is printed step by step. The usual causes are
a missing `wildcard: true` on the parent, or a config file whose name doesn't match the host.

**`/admin` gives you a 404 and you are sure you are an admin.**
It gives everybody who is not an admin a 404, signed in or not. Check that the address you signed in
with is the one in `admin_emails`, exactly, and that the account is not disabled.

**The event bus or the log is empty after a restart.**
Both are memory only, by design. `logs/` has the durable log.
