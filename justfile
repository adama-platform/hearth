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
    #!/usr/bin/env bash
    set -euo pipefail
    # The vendored browser libraries are not in git, and a jar built without them ships an editor
    # that silently falls back to a plain textarea. Warned rather than fetched: `just package` has
    # no business reaching the network, and a build that quietly downloads things is a build that
    # fails differently on a machine with no route out.
    if [ ! -d src/main/resources/3rd ]; then
      echo "  note: src/main/resources/3rd is missing -- run 'just third-party' first, or the"
      echo "        rich editor in this jar falls back to a plain textarea"
    fi
    mvn -q clean package
    cp target/hearth.jar {{jar}}
    ls -lh {{jar}} | awk '{print "  packaged: " $9 " (" $5 ")"}'

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
    # This looked in /tmp/vc-smoke-stores, which has not existed since the project was renamed --
    # so the glob never matched, the loop printed the literal `*.mv.db` as though it were a file,
    # and the assertion under it never ran at all. A check that passes by printing an asterisk is
    # worse than no check, because the line in the output says everything is fine.
    dbs=/tmp/hearth-smoke/dbs
    found=0
    for db in "$dbs"/*.mv.db; do
      [ -e "$db" ] || continue
      printf '  ok    %s\n' "$(basename "$db")"
      found=$((found + 1))
    done
    [ "$found" -gt 0 ] || { echo "  FAIL  no databases were created under $dbs"; fail=1; }
    if [ -e "$dbs/junior.example.org.mv.db" ]; then
      echo "  FAIL  junior.example.org has its own database; it shares example.org's"
      fail=1
    else
      echo "  ok    junior.example.org shares example.org's database"
    fi
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

# --- shipping ------------------------------------------------------------------------------------
#
# There is no release recipe, and that is a decision rather than a gap.
#
# There used to be one: `just release 1.2.3` validated, stamped the version into the manifest, cut a
# tag and created a GitHub release. Every part of it served a distribution model this project does
# not have -- nobody resolves this jar from a repository, and a version number on it promises a
# thing nobody is tracking. `just package` produces hearth.jar; copy it to the box. The commit is
# the identity, and `git log` answers that better than a number somebody remembered to bump.
#
# What that recipe did do usefully was run `just third-party` first, because the vendored browser
# libraries are not in git and a jar built without them ships an editor that silently falls back to
# a textarea. `just package` now says so when they are missing, rather than fetching them itself --
# a build that quietly reaches the network fails differently on a machine that cannot.
