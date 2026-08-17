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

`just release-check` refuses a release when a vendored package has no licence file, because a
release is the moment redistribution actually happens.
