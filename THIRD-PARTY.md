# Third-party code in this jar

Hearth vendors a small number of browser libraries into `hearth.jar` rather than fetching them from
somebody else's server at runtime — a page that loads from a CDN has told that CDN a member was
reading it, and stops working when they have a bad day.

**Vendoring is redistribution.** Every licence here requires its copyright notice and permission
notice to travel with the code, so they are checked into this repository, baked into the jar, and
served at `/3rd/licenses` on every running community. The bundles themselves are not in git (they
are megabytes of minified JavaScript); the licences are, because the obligation exists whether or
not somebody has run `just third-party` yet.

| Package | Version | Licence | Copyright | Where it is used |
|---|---|---|---|---|
| [@milkdown/crepe](https://milkdown.dev) | 7.21.3 | MIT | 2020–present Mirone | the rich markdown editor in the admin content and template editors |

Full texts are in [`src/main/resources/3rd-licenses/`](src/main/resources/3rd-licenses/) and are
served, verbatim, at `/3rd/licenses`.

Hearth itself is a separate work; see the repository's own licence.

## Adding one

1. Add the fetch to `just third-party`, pinned to an exact version.
2. Put its licence text in `src/main/resources/3rd-licenses/<package>.txt`, fetched from the package
   rather than typed from memory.
3. Add a row above.

Nothing enforces this automatically, and it is worth being honest about: there was a claim here that
a release-readiness recipe refused to ship when a licence was missing, and that recipe never checked
licences at all. It has since been removed along with the rest of the release machinery. The
obligation is real and the check is a person reading this list.

## What else is in the jar

The table at the top is about the **browser** libraries, which are vendored by hand because they
are served to a member's browser from this machine. The jar is also an uber-jar and therefore
redistributes its ordinary Maven dependencies — Netty, H2, Jackson, jsoup, commonmark, scrypt,
acme4j, logback and the rest — each under its own licence, unmodified, as published.

One of those is worth naming because it is most of the jar's size and it is not Java:

| Package | Licence | What it is |
|---|---|---|
| [Javet](https://github.com/caoccao/Javet) | Apache-2.0 | the Java binding for V8 |
| [V8](https://v8.dev) | BSD-3-Clause | Google's JavaScript engine, as a native library per platform |

Javet ships V8 as a compiled shared library in a per-platform jar, and Hearth bundles two of them —
`linux-x86_64` and `linux-arm64` — which is what takes the deliverable from about 20MB to about
50MB. They are there so that a dynamic JavaScript page works on a server the moment the jar lands,
with nothing installed beside it. A platform whose native library is not bundled runs everything
else normally and reports the JavaScript engine as unavailable.
