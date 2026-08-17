# Hearth's API

Everything a program needs to talk to a Hearth community: how to get a token, how to use it, what it
can ask for, and what it gets back when it asks wrongly.

This document is the contract. If you are building a tool, you should not need to read any Java to
finish it.

- [The shape of it](#the-shape-of-it)
- [Getting a token](#getting-a-token)
- [Using a token](#using-a-token)
- [Errors](#errors)
- [`GET /api/v1/whoami`](#get-apiv1whoami)
- [`GET /api/v1/content`](#get-apiv1content)
- [`POST /api/v1/content`](#post-apiv1content)
- [The bundle format](#the-bundle-format)
- [Building a CLI: the whole loop](#building-a-cli-the-whole-loop)
- [What is deliberately not here](#what-is-deliberately-not-here)

---

## The shape of it

There are two halves under `/api`, and they do not share a credential.

| | who it is for | how it authenticates |
|---|---|---|
| `/api` | a person, in a browser | the session cookie, like every other page |
| `/api/v1/…` | a program | `Authorization: Bearer <token>` — **never** a cookie |

That split is a security property rather than a style. A JSON endpoint that accepted the browser's
session cookie would be a cross-site request forgery hole with no form and no token in it: any page
on the internet a member happened to have open could push to it. So `/api/v1` reads exactly one
credential, out of one header, and ignores cookies entirely.

Everything under `/api/v1` speaks JSON in both directions and answers with a sentence, not only a
status code.

The version is in the path so that a tool written today keeps working when a second version turns
up. There is no content negotiation and no `Accept` header to send.

**Is it even on?** `api.enabled` defaults to true, and a community can switch it off entirely with
`"disabled": ["api"]` — in which case every path under `/api` is a 404, the same answer any other
address nothing answers gets.

---

## Getting a token

A token is copied by hand, on purpose. Your tool prints an address; a person opens it, reads what is
being asked for, presses a button, and copies a string back into your tool.

```
$ my-tool login example.org
Open this and authorize, then paste the token here:
  https://example.org/api?name=my-tool
Token: _
```

**Why not an OAuth redirect?** Because this has to work from a machine with no browser on it, over
SSH, and from a phone — and because there is then nothing to get wrong about which program received
what. A person moved the string themselves. (Hearth *does* have a full OAuth 2.1 flow with PKCE, at
`/mcp`; that one exists because a model connector needs a redirect, and it is a different thing with
a different consent screen.)

### The address to print

```
https://<domain>/api?name=<what your tool is called>
```

`name` is a label, not an identifier: it is shown on the authorize screen and in the person's list
of tokens so they can tell two apart six weeks later. It is trimmed to 40 characters of letters,
digits, spaces, `-`, `_` and `.`; anything else is dropped.

If the person is not signed in they are sent to the sign-in form and returned to the authorize
screen afterwards, so the address is safe to print to somebody whose session has lapsed.

### What they see

A page that says what is being authorized, that a token can do whatever *they* can do here and
nothing more, and when it expires. Pressing the button shows the token **once** — what the server
stores is a hash, so nobody, including the server, can print it again.

### The rules your tool should know about

| rule | default | what happens when it bites |
|---|---|---|
| tokens per person | 2 | the third is **refused**, not rotated; they revoke one first |
| lifetime | 30 days | after that every call is a `401` |

Both are per community: `api.max-tokens` and `api.token-days` (`0` means no expiry). A tool that
stores a token should store the domain with it, and should treat a `401` as "ask the person to
authorize again" rather than as a bug.

A person can see and revoke their tokens at any time at `https://<domain>/api`, and revoking is
immediate: the token is deleted rather than marked, so the next request fails.

### What a token is, underneath

A session with a bit set — the same row in the same table as a browser login, with `robot = true`
and an `api:` label. That is why revocation, expiry and the session reaper all work on it without a
second implementation, and why **everything a token does is recorded as the person who made it**. A
token can never do anything that person could not; if their account is turned off or their
permissions change, the token changes with it on the next request.

---

## Using a token

```
Authorization: Bearer <token>
```

```bash
curl -H "Authorization: Bearer $TOKEN" https://example.org/api/v1/whoami
```

There is no refresh grant and nothing to renew. When a token expires, a person authorizes another
one.

---

## Errors

Every refusal is a JSON object with the same three fields, and the HTTP status you would expect.

```json
{ "ok": false, "error": "unauthorized", "detail": "this needs an API token: open https://example.org/api to make one" }
```

| status | `error` | what it means |
|---|---|---|
| `401` | `unauthorized` | no token, an unknown one, an expired one, or an account that was turned off or is not approved. The response carries `WWW-Authenticate: Bearer`. |
| `403` | `not_allowed` | the token is fine and the person may not do this. `detail` names the permission in the words the admin screen uses. |
| `404` | `no_such_endpoint` | nothing is at that path. (A `404` on `/api` itself means the community switched the API off.) |
| `405` | `wrong_method` | the endpoint exists and answers something else; the `Allow` header says what. |
| `400` | `empty`, `bad_bundle` | the body was missing or could not be understood. |
| `500` | `server_error` | our fault. Nothing was half-applied that we know of; check the response of a `?dry=1` run before retrying a push. |

`detail` is written for a person to read in a terminal. Print it.

---

## `GET /api/v1/whoami`

Who this token is, until when, and what it may do. Call it before anything else: it is how a tool
says "you are signed in as Ana and may not publish" *before* it starts, rather than after a `403`
halfway through a push.

```json
{
  "community": "Example Community",
  "domain": "example.org",
  "email": "ana@example.org",
  "token": "my-tool",
  "expires_at": "2026-09-03T18:22:41.501Z",
  "can": ["admin_enter", "content_read", "content_write", "content_publish", "..."],
  "endpoints": [
    "GET /api/v1/whoami",
    "GET /api/v1/content",
    "POST /api/v1/content"
  ]
}
```

- `expires_at` is ISO-8601 UTC, or `null` when the community set no expiry.
- `can` is the closed list of permission names this account holds. The ones that matter here are
  `content_read`, `content_write` and `content_publish`.
- `endpoints` is what this version of the server offers. **Read it rather than hard-coding a list**:
  it is how a tool notices it is talking to a newer server without guessing.

---

## `GET /api/v1/content`

The whole site — every page and every template — as one [bundle](#the-bundle-format).

Needs **`content_write`**, not merely `content_read`: a bundle is every page, including drafts and
the ones marked human-only, which is a different thing from being able to open the listing.

```bash
curl -H "Authorization: Bearer $TOKEN" https://example.org/api/v1/content > site.json
```

---

## `POST /api/v1/content`

Push a bundle. The body is the bundle; no wrapper, no form encoding.

Needs **`content_write` and `content_publish`**, because a bundle whose rows say `published` would
otherwise be a way to publish without the permission to.

| query | meaning |
|---|---|
| `?dry=1` | work out the answer and write nothing |

```bash
# what would change
curl -X POST -H "Authorization: Bearer $TOKEN" \
     --data-binary @site.json "https://example.org/api/v1/content?dry=1"

# and for real
curl -X POST -H "Authorization: Bearer $TOKEN" \
     --data-binary @site.json "https://example.org/api/v1/content"
```

### What it does

**A merge, matched on the `uuid`.** For each page:

1. a `uuid` this site already has → that page is updated, *whatever its address has become since*;
2. a `uuid` it has not seen, at a `uri` that is free → a new page;
3. a `uuid` it has not seen, at a `uri` already taken → the existing page is **adopted**: it takes
   the incoming key and contents, rather than becoming a second page at one address;
4. no `uuid` at all → a new page, and the server gives it one. (Your tool does not have to invent
   keys, but keeping the ones it gets back is what makes case 1 work next time.)

**Only what differs is written.** A page whose every field matches what is stored is skipped
entirely: no save, no mutation event, no version in the page's history. This is what makes it safe
to push the whole site on every commit — otherwise a tool would fill the history with edits nobody
made.

**Everything it does write is versioned like any other edit**, so a bad push is undone page by page
from the page's history rather than from a backup.

Templates are applied first, so a page arriving with a template that is new here finds it.

### The answer

```json
{
  "ok": true,
  "dry_run": false,
  "content": [
    { "uuid": "0f1c…", "name": "/about",   "status": "updated",   "changed": ["body", "title"] },
    { "uuid": "7a22…", "name": "/contact", "status": "unchanged", "changed": [] },
    { "uuid": "b904…", "name": "/new",     "status": "created",   "changed": [] },
    { "uuid": "",      "name": "nowhere",  "status": "skipped",   "changed": ["a uri has to start with '/'"] }
  ],
  "templates": [
    { "uuid": "c31d…", "name": "wrapper", "status": "unchanged", "changed": [] }
  ],
  "summary": {
    "created": 1, "updated": 1, "unchanged": 1,
    "templates_created": 0, "templates_updated": 0, "templates_unchanged": 1
  },
  "notes": ["/about already existed here and was adopted rather than duplicated"]
}
```

| field | meaning |
|---|---|
| `status` | `created`, `updated`, `unchanged`, or `skipped` |
| `changed` | for `updated`, the fields that differ, by name: `uri`, `title`, `kind`, `template`, `folder`, `fields`, `body`, `published`, `human_only`. For `skipped`, why. |
| `name` | the `uri` for a page, the name for a template |
| `notes` | things worth telling a person that are not failures — an adoption, a row that was skipped |

`?dry=1` returns exactly this with `dry_run: true` and writes nothing, which is what a tool should
show before asking "apply?".

---

## The bundle format

The same document `GET /api/v1/content` returns and `POST /api/v1/content` accepts. It is also what
the admin screen downloads, so a file from one is valid for the other.

```json
{
  "hearth": 1,
  "community": "Example Community",
  "domain": "example.org",
  "exported_at": "2026-08-04T18:22:41.501Z",
  "templates": [
    {
      "uuid": "c31d…",
      "name": "wrapper",
      "parameters": "[]",
      "body": "<main>{{{body}}}</main>",
      "directory": false,
      "directory_path": "",
      "directory_pattern": "",
      "directory_page_size": 10,
      "directory_order": "newest"
    }
  ],
  "content": [
    {
      "uuid": "0f1c…",
      "uri": "/about",
      "title": "About us",
      "kind": "markdown",
      "template": "wrapper",
      "folder": "about",
      "fields": "{}",
      "body": "We meet on Tuesdays.",
      "published": true,
      "human_only": false
    }
  ]
}
```

Only `content` and, within it, `uri` are really required — everything else has a sensible default,
so the smallest useful push is:

```json
{ "content": [ { "uri": "/notes", "title": "Notes", "kind": "markdown", "body": "# Hello", "published": true } ] }
```

| field | notes |
|---|---|
| `hearth` | format version; `1` today. Send it, and refuse anything you do not understand. |
| `uuid` | the merge key. Stamped once and never rewritten. Keep it. |
| `uri` | must start with `/`. Rows that do not are skipped rather than failing the push. |
| `kind` | `markdown`, `html`, `page`, or one of the feed kinds: `event_listing`, `event`, `place_listing`, `place`, `member_listing`, `member` |
| `template` | a template name, or `""` for none |
| `folder` | where it sits in the navigation; `""` means it is reachable only by its address |
| `fields` | a JSON **string** holding an object: the values for whatever the template declares, plus `page_size` for a listing |
| `published` | absent means `false`, which means the page is not there |
| `human_only` | invisible to every AI read and refused on every AI write |

`parameters` on a template is likewise a JSON string holding an array. Both are strings rather than
nested objects because that is exactly how they are stored, and a round trip that re-formats
somebody's blob is a diff nobody asked for.

A page whose `kind` is a feed kind carries a **pattern** in its `uri`, with one token in it:
`/whats-on/{{page}}`, `/people/{{member_id}}`. See
[MANUAL.md](MANUAL.md#pages-built-from-what-the-community-already-holds) for what those pages get.

---

## Building a CLI: the whole loop

```bash
#!/bin/sh
# hearth-push <domain> <bundle.json>
set -eu
DOMAIN=$1; FILE=$2
TOKEN=$(cat ~/.config/hearth/$DOMAIN.token 2>/dev/null || true)

if [ -z "$TOKEN" ]; then
  echo "Open this, authorize, and paste the token:"
  echo "  https://$DOMAIN/api?name=hearth-push"
  printf 'Token: '; read -r TOKEN
  mkdir -p ~/.config/hearth && printf '%s' "$TOKEN" > ~/.config/hearth/$DOMAIN.token
  chmod 600 ~/.config/hearth/$DOMAIN.token
fi

# say who we are before doing anything, so a permission problem is one sentence rather than a 403
curl -fsS -H "Authorization: Bearer $TOKEN" "https://$DOMAIN/api/v1/whoami"

# what would change
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
     --data-binary @"$FILE" "https://$DOMAIN/api/v1/content?dry=1"

printf 'Apply? [y/N] '; read -r yes
[ "$yes" = "y" ] || exit 0

curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
     --data-binary @"$FILE" "https://$DOMAIN/api/v1/content"
```

Turning a folder of markdown into a bundle is your tool's job and deliberately not the server's:
where the front matter lives, what maps to `folder`, whether a filename becomes a `uri` — those are
decisions about *your* repository. Keep the `uuid` you get back (in a lock file, in front matter,
wherever) and the next push is a merge rather than a pile of new pages.

A few things worth doing:

- **Store the token per domain**, mode `600`, and never in the repository.
- **Treat `401` as "authorize again"**, not as an error to retry.
- **Show the `?dry=1` answer before applying.** It is the whole reason it exists.
- **Read `endpoints` from `whoami`** rather than assuming what a server offers.

---

## What is deliberately not here

- **No API-only accounts.** A token belongs to a person, because a community has to be able to ask
  who put something here and get somebody they can talk to.
- **No refresh tokens, no client secrets, no registration.** There is one credential and a person
  made it.
- **No write access to people, approvals, bans, or roles.** Those are decisions a community makes
  about a person, and they are made by a person on a screen.
- **No bundle for anything but content and templates yet.** Events, places and members are readable
  through the [model endpoint](MANUAL.md#connecting-a-model) and on the site itself; whether they
  should be pushable is a policy question, not a plumbing one.

If you want one of those, the answer is a conversation about what it would mean rather than an
endpoint. See [MISSION.md](MISSION.md).
