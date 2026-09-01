# Hearth -- run `just` for the list, `just validate` before you push.
#
# This file is the primary way to build, run, and validate the server. If a check isn't reachable
# from here, it isn't part of the definition of "working".

jar        := "hearth.jar"
root       := "site"
smoke_port := "18099"

# list the recipes
default:
    @just --list --unsorted

# --- validate ---------------------------------------------------------------------------------

# THE gate: clean build, full unit + HTTP test suite, packaged jar, live smoke test, docs check
validate: clean package smoke suite docs
    @echo ""
    @echo "  validate: OK"

# do the documents still describe this program? checks links, paths, flags, recipes, templates
docs:
    @./tools/check-docs.sh

# did every test actually run? a class with no @Test is skipped in silence
suite:
    @./tools/check-suite.sh

# unit + HTTP tests
test:
    mvn -q test

# one test class, e.g. `just test-one ServerHttpTests`
test-one CLASS:
    mvn -q test -Dtest={{CLASS}}

# tests with coverage; fails below the floor in pom.xml (80% line, 70% branch)
coverage:
    #!/usr/bin/env bash
    set -euo pipefail
    mvn -q test -Pcoverage
    python3 - <<'PY'
    import xml.etree.ElementTree as ET
    root = ET.parse('target/site/jacoco/jacoco.xml').getroot()
    for kind in ('LINE', 'BRANCH'):
        c = [x for x in root.findall('counter') if x.get('type') == kind][0]
        missed, covered = int(c.get('missed')), int(c.get('covered'))
        print(f"  {kind.lower():7} {covered}/{covered+missed}  {100*covered//(covered+missed)}%")
    PY
    echo "  report: target/site/jacoco/index.html"

# load the configs and exit; never opens a socket and never touches a database
check DIR=root:
    java -jar {{jar}} --root {{DIR}} --check

# same, with the full scan narrated
check-verbose DIR=root:
    java -jar {{jar}} --root {{DIR}} --check --verbose

# --- build ------------------------------------------------------------------------------------

# compile only, no tests
build:
    mvn -q -DskipTests compile

# build, test, and drop the single jar at ./hearth.jar
package:
    mvn -q clean package
    cp target/hearth.jar {{jar}}
    @ls -lh {{jar}} | awk '{print "  packaged: " $9 " (" $5 ")"}'

# package without running tests -- for iterating, not for validating
package-fast:
    mvn -q -DskipTests clean package
    cp target/hearth.jar {{jar}}

clean:
    mvn -q clean
    rm -f {{jar}}

# --- run --------------------------------------------------------------------------------------

# run the server against the example configs, narrating everything
run:
    java -jar {{jar}} --root {{root}} --verbose

# run quietly, bound to localhost only
run-quiet:
    java -jar {{jar}} --root {{root}}

# run against an operator's configs and databases
serve DIR:
    java -jar {{jar}} --root {{DIR}}

# throw away the local databases and start over
reset-data:
    rm -rf {{root}}/dbs {{root}}/certs
    @echo "  {{root}}/dbs and {{root}}/certs removed; the next run recreates them"

help:
    java -jar {{jar}} --help

version:
    java -jar {{jar}} --version

# --- smoke ------------------------------------------------------------------------------------

# start the packaged jar and assert the virtual hosting matrix over real HTTP
smoke PORT=smoke_port:
    #!/usr/bin/env bash
    set -euo pipefail
    test -f {{jar}} || { echo "  no {{jar}}; run 'just package' first"; exit 1; }
    rm -rf /tmp/hearth-smoke
    mkdir -p /tmp/hearth-smoke/domains
    cp {{root}}/domains/*.cfg /tmp/hearth-smoke/domains/
    printf '{"http-port": %s, "bind": "127.0.0.1"}\n' {{PORT}} > /tmp/hearth-smoke/config.cfg
    java -jar {{jar}} --root /tmp/hearth-smoke > /tmp/hearth-smoke.log 2>&1 &
    pid=$!
    trap 'kill $pid 2>/dev/null || true' EXIT
    for _ in $(seq 1 100); do
      curl -s -o /dev/null "localhost:{{PORT}}" && break || sleep 0.1
    done
    fail=0
    expect() {
      local want="$1" host="$2" path="$3" method="${4:-GET}"
      local got
      got=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" -H "Host: $host" "localhost:{{PORT}}$path")
      if [ "$got" = "$want" ]; then
        printf '  ok    %-3s %-4s %-22s %s\n' "$got" "$method" "$host" "$path"
      else
        printf '  FAIL  want %s got %s  %-4s %-18s %s\n' "$want" "$got" "$method" "$host" "$path"
        fail=1
      fi
    }
    echo ""
    echo "  smoke on port {{PORT}}"
    expect 200 localhost             /
    expect 200 example.org           /
    expect 200 junior.example.org    /
    expect 308 www.example.org       /
    expect 200 example.org           /legal/terms-of-service
    expect 200 example.org           /legal/privacy-policy
    expect 404 api.localhost         /
    expect 404 org                   /
    expect 404 nope.org              /
    expect 400 127.0.0.1             /
    expect 410 example.org           /wp-login.php
    # nothing serves security.txt, so it is missing rather than refused -- 410 is what the
    # scanner shield says, and it must never be what a well-known path gets
    expect 404 example.org           /.well-known/security.txt
    expect 405 example.org           /                            DELETE
    expect 200 example.org           /register
    expect 200 example.org           /login
    expect 405 example.org           /logout
    expect 404 example.org           /admin
    expect 404 example.org           /admin/system/events
    expect 404 example.org           /admin/people/list
    expect 400 example.org           /register                    POST
    echo ""
    echo "  the register page is built in the browser"
    body=$(curl -s -H "Host: example.org" "localhost:{{PORT}}/register")
    case "$body" in
      *"<form"*) echo "  FAIL  the HTML contains a form; it should be assembled by script"; fail=1 ;;
      *) echo "  ok    no form in the HTML" ;;
    esac
    case "$body" in
      *'id="mint"'*) echo "  ok    the minted-form blob is present" ;;
      *) echo "  FAIL  no mint blob"; fail=1 ;;
    esac
    csp=$(curl -s -D - -o /dev/null -H "Host: example.org" "localhost:{{PORT}}/register" | grep -i '^content-security-policy')
    case "$csp" in
      *"script-src 'self' 'nonce-"*) echo "  ok    the script is allowed by nonce" ;;
      *) echo "  FAIL  no script nonce in the CSP"; fail=1 ;;
    esac
    echo ""
    echo "  databases"
    for db in /tmp/vc-smoke-stores/*.mv.db; do printf '  ok    %s\n' "$(basename $db)"; done
    test -f /tmp/vc-smoke-stores/junior.example.org.mv.db && { echo "  FAIL  junior should share example.org"; fail=1; } || true
    exit $fail

# what a browser actually gets back, for eyeballing
peek HOST="localhost" PORT="8080":
    curl -s -D - -H "Host: {{HOST}}" "localhost:{{PORT}}/"

# --- housekeeping -----------------------------------------------------------------------------

deps:
    mvn -q dependency:tree

# refuse to leave stray dev servers behind. The bracket keeps pkill from matching its own
# command line; the pattern names the jar and the flag, so a production server started some
# other way is not in scope of a development convenience.
kill:
    -pkill -f "[h]earth.jar --root"

loc:
    @find src -name '*.java' | xargs wc -l | tail -1

# --- third party ------------------------------------------------------------------------------

# Pull third-party browser libraries into src/main/resources/3rd so they are baked into the jar
# (the recipe cannot be called "3rd" -- just names cannot start with a digit).
#
# Served at /3rd/<package>/<version>/<file>. Nothing is fetched at runtime: a community's editor
# must not stop working because a CDN went down or changed a file under the same URL, and a page
# that loads from somebody else's server has told them a member was reading it.
#
# Everything here is pinned and fully bundled -- one file with no imports left in it -- so that
# vendoring is a copy rather than a build step, and so this repository never needs node.
third-party:
    #!/usr/bin/env bash
    set -euo pipefail
    dest="src/main/resources/3rd"
    get() {
      out="$dest/$1"; url="$2"
      if [ -s "$out" ]; then echo "  have  $1 ($(wc -c <"$out") bytes)"; return; fi
      mkdir -p "$(dirname "$out")"
      curl -fsSL --retry 2 -o "$out" "$url" || { echo "  FAILED $url" >&2; rm -f "$out"; exit 1; }
      echo "  get   $1 ($(wc -c <"$out") bytes)"
      # a bundle that still imports from somewhere else is not vendored, it is a redirect
      if grep -qE 'from"?/(npm|node)/' "$out" 2>/dev/null; then
        echo "  FAILED $1 still imports from a CDN -- not self-contained" >&2; exit 1
      fi
    }
    echo ""
    echo "  third-party libraries -> $dest"
    MILKDOWN=7.21.3
    get "milkdown/$MILKDOWN/milkdown.js" \
        "https://esm.sh/@milkdown/crepe@$MILKDOWN/es2022/crepe.bundle.mjs"
    get "milkdown/$MILKDOWN/common.css" \
        "https://cdn.jsdelivr.net/npm/@milkdown/crepe@$MILKDOWN/lib/theme/common/style.css"
    get "milkdown/$MILKDOWN/theme.css" \
        "https://cdn.jsdelivr.net/npm/@milkdown/crepe@$MILKDOWN/lib/theme/nord/style.css"
    echo ""
    echo "  third-party: OK -- 'just package' bakes them into the jar"

# --- release ----------------------------------------------------------------------------------

# Cut a release: validate, build a stamped jar, tag it, and publish it to GitHub.
#
#   just release 0.2.0
#
# NOTE ON CREDENTIALS. An SSH key signs git operations -- push, pull, tag. It cannot create a
# GitHub release, because releases are a REST API resource and that API takes a token, never a key.
# So the tag goes up over SSH and the release needs one of:
#
#   gh auth login                          the gh CLI, which keeps its own token
#   export GITHUB_TOKEN=ghp_...            a personal access token with `contents: write`
#
# This checks for one *before* changing anything, so a missing token is a refusal rather than a
# repository left with a tag pointing at a release that does not exist.
release VERSION:
    #!/usr/bin/env bash
    set -euo pipefail
    version="{{VERSION}}"
    tag="v${version}"

    step() { printf '\n  \033[36m%s\033[0m\n' "$1"; }
    fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1" >&2; exit 1; }
    okay() { printf '  \033[32mok\033[0m    %s\n' "$1"; }

    step "checking"
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
      || fail "'$version' is not a version -- use 1.2.3, or 1.2.3-rc1"
    [ -z "$(git status --porcelain)" ] \
      || fail "the working tree is dirty; a release nobody can rebuild from a commit is not one"
    branch=$(git rev-parse --abbrev-ref HEAD)
    [ "$branch" = "main" ] || fail "on '$branch'; releases are cut from main"
    git fetch --quiet origin main
    [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
      || fail "local main and origin/main differ; push or pull first"
    git rev-parse -q --verify "refs/tags/$tag" >/dev/null \
      && fail "$tag already exists -- a released version is never rebuilt, pick the next one"
    okay "clean tree on main, up to date with origin, $tag is free"

    # How this will be published, decided before anything is built or tagged. Finding out after the
    # tag is pushed leaves the repository claiming a release that is not there.
    publisher=""
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
      publisher="gh"
      okay "publishing with the gh CLI"
    elif [ -n "${GITHUB_TOKEN:-${GH_TOKEN:-}}" ]; then
      publisher="api"
      okay "publishing with GITHUB_TOKEN"
    else
      printf '\n'
      fail "no way to publish.

    Your SSH key can push the tag but cannot create the release: GitHub releases are a REST
    resource and that API takes a token, not a key. Pick one --

      gh auth login                     install the gh CLI and log in, or
      export GITHUB_TOKEN=ghp_xxx       a token with 'contents: write' on this repository

    Nothing has been changed. Run this again once one of those is in place."
    fi

    step "vendoring third-party libraries"
    # Not in git -- a 2.8MB bundle in history is a repository nobody wants to clone -- so a release
    # built without this would ship a jar whose editor silently falls back to a plain textarea.
    just third-party

    step "the gate"
    just validate

    step "building $tag"
    mvn -q clean package -Drevision="$version"
    cp target/hearth.jar hearth.jar
    reported=$(java -jar hearth.jar --version)
    [ "$reported" = "Hearth $version" ] \
      || fail "the jar says '$reported' -- it must be able to tell somebody what it is"
    okay "$reported ($(du -h hearth.jar | cut -f1))"

    mkdir -p target/release
    artifact="target/release/hearth-${version}.jar"
    cp hearth.jar "$artifact"
    (cd target/release && sha256sum "hearth-${version}.jar" > "hearth-${version}.jar.sha256")
    okay "sha256 $(cut -d' ' -f1 < "${artifact}.sha256")"

    step "release notes"
    previous=$(git describe --tags --abbrev=0 2>/dev/null || true)
    notes="target/release/notes-${version}.md"
    {
      echo "## Hearth $version"
      echo
      echo '```'
      echo "java -jar hearth-${version}.jar --root /var/hearth"
      echo '```'
      echo
      if [ -n "$previous" ]; then
        echo "### Since $previous"
        echo
        # subjects only: the bodies in this repository are long, and a release page that reprints
        # them is one nobody scrolls to the bottom of
        git log --no-merges --pretty='- %s' "${previous}..HEAD"
      else
        echo "The first tagged release."
      fi
      echo
      echo "### Verifying"
      echo
      echo '```'
      echo "sha256sum -c hearth-${version}.jar.sha256"
      echo '```'
    } > "$notes"
    okay "$(grep -c '^- ' "$notes" || true) change(s) since ${previous:-the beginning}"

    step "tagging"
    git tag -a "$tag" -m "Hearth $version"
    git push --quiet origin "$tag"
    okay "$tag pushed"

    step "publishing"
    if [ "$publisher" = "gh" ]; then
      gh release create "$tag" \
        --title "Hearth $version" \
        --notes-file "$notes" \
        "$artifact" "${artifact}.sha256"
    else
      token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
      repo=$(git remote get-url origin | sed -E 's#.*[:/]([^/]+/[^/]+?)(\.git)?$#\1#')
      body=$(python3 -c "import json,sys; print(json.dumps({'tag_name': sys.argv[1], 'name': 'Hearth ' + sys.argv[2], 'body': open(sys.argv[3]).read()}))" "$tag" "$version" "$notes")
      created=$(curl -sS -X POST \
        -H "Authorization: Bearer $token" \
        -H "Accept: application/vnd.github+json" \
        -d "$body" "https://api.github.com/repos/${repo}/releases")
      upload=$(printf '%s' "$created" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('upload_url','').split('{')[0])")
      [ -n "$upload" ] || fail "GitHub refused the release: $(printf '%s' "$created" | head -c 300)"
      for file in "$artifact" "${artifact}.sha256"; do
        curl -sS -X POST \
          -H "Authorization: Bearer $token" \
          -H "Content-Type: application/octet-stream" \
          --data-binary "@${file}" \
          "${upload}?name=$(basename "$file")" > /dev/null
        okay "uploaded $(basename "$file")"
      done
      printf '  \033[32mok\033[0m    https://github.com/%s/releases/tag/%s\n' "$repo" "$tag"
    fi

    printf '\n  \033[32mreleased:\033[0m Hearth %s\n\n' "$version"

# What a release would do, without doing any of it.
release-check:
    #!/usr/bin/env bash
    set -uo pipefail
    printf '\n  release readiness\n'
    say() { printf '  %-5s %s\n' "$1" "$2"; }
    [ -z "$(git status --porcelain)" ] && say "ok" "working tree is clean" || say "no" "working tree is dirty"
    branch=$(git rev-parse --abbrev-ref HEAD)
    [ "$branch" = "main" ] && say "ok" "on main" || say "no" "on $branch, not main"
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
      say "ok" "gh CLI is logged in"
    elif [ -n "${GITHUB_TOKEN:-${GH_TOKEN:-}}" ]; then
      say "ok" "GITHUB_TOKEN is set"
    else
      say "no" "no gh login and no GITHUB_TOKEN -- an SSH key cannot create a release"
    fi
    [ -d src/main/resources/3rd ] && say "ok" "third-party libraries are vendored" || say "--" "third-party libraries not fetched yet (the release fetches them)"
    last=$(git describe --tags --abbrev=0 2>/dev/null || echo "none")
    say "--" "last tag: $last"
    printf '\n'
