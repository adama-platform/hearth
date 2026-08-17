#!/usr/bin/env bash
#
# Do the documents still describe this program?
#
# Prose cannot be checked by a machine, but the things prose is built out of can be: the files it
# names, the flags it tells somebody to type, the links it offers. Every documentation bug this
# project has actually shipped was one of those -- a manual describing a moderation screen that did
# not exist, a README claiming "no TLS" three features after TLS landed, a link to a configs/
# directory deleted in the --root refactor. None of them were subtle. All of them survived because
# nothing looked.
#
# So this checks the mechanical parts and says nothing about the rest. It is deliberately not a
# style checker: a false alarm here would be trained away within a week, and then the real ones
# would go with it.
#
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
say() { printf '  %-6s %s\n' "$1" "$2"; }
bad() { say "BAD" "$1"; fail=1; }
ok()  { say "ok" "$1"; }

# LANDING.md is in here for a reason worth stating: it is the document read by people who have
# never seen this software, so a claim that has gone stale there is more expensive than the same
# claim in the manual, not less. It quotes a test count, names flags somebody is meant to type, and
# links to the other five -- all of which this script already knows how to check.
DOCS=(README.md MANUAL.md CLAUDE.md MISSION.md API.md LANDING.md)

echo
echo "  documents"

# ---- 1. every local link resolves ------------------------------------------------------------
# A dead link is the cheapest possible signal that a document is describing a program that has
# moved, and it is the one that pointed at configs/README.md for two months.
broken=0
for doc in "${DOCS[@]}"; do
  while IFS= read -r target; do
    [ -z "$target" ] && continue
    case "$target" in http*|\#*|mailto:*) continue ;; esac
    path="${target%%#*}"
    [ -z "$path" ] && continue
    if [ ! -e "$path" ]; then
      bad "$doc links to $path, which does not exist"
      broken=1
    fi
  done < <(grep -oE '\]\([^)]+\)' "$doc" | sed -E 's/^\]\(//; s/\)$//')
done
[ $broken -eq 0 ] && ok "every local link resolves"

# ---- 2. every source file the layout names exists ---------------------------------------------
# CLAUDE.md carries a map of the source tree. A map naming a file that is gone is worse than no
# map, because somebody will go looking.
# The layout block lists a root (src/main/java/io/hearth/) and then entries indented under it, so
# a bare entry has to be resolved against whichever root was last named.
missing=0
while IFS= read -r path; do
  [ -e "$path" ] || { bad "CLAUDE.md's layout names $path, which does not exist"; missing=1; }
done < <(awk '
  /^src\/[a-z\/]+\/$/            { root = $1; next }
  /^  [a-z]+\/[A-Za-z0-9_.]+\.java/ { gsub(/^ +/, "", $1); print root $1; next }
  /^  src\/[A-Za-z0-9_.\/-]+/      { gsub(/^ +/, "", $1); print $1 }
' CLAUDE.md | sort -u)
[ $missing -eq 0 ] && ok "every source file the layout names exists"

# ---- 3. every package is described ------------------------------------------------------------
# The other direction, which is the one that catches a whole feature landing undocumented: the
# calendar shipped as six files in a package CLAUDE.md had never heard of.
undocumented=0
for pkg in src/main/java/io/hearth/*/; do
  name=$(basename "$pkg")
  grep -q "  $name/" CLAUDE.md || {
    bad "package io.hearth.$name is not in CLAUDE.md's layout"
    undocumented=1
  }
done
[ $undocumented -eq 0 ] && ok "every package appears in the layout"

# ---- 4. every command the docs tell somebody to type exists ------------------------------------
# The --configs/--stores/--certs flags were replaced by one --root and the docs kept recommending
# them. A flag that no longer parses is a document that will waste somebody's afternoon.
# Only flags Args.java has an opinion about. A doc quoting `git reset --hard` is not our business;
# a doc telling somebody to pass --configs, which Args refuses by name, is exactly our business. A
# case arm that throws is a refusal; anything else is a flag that still works.
eval "$(awk '
  /case "--/ {
    arm = $0
    while (arm !~ /}|;/ && (getline nxt) > 0) arm = arm " " nxt
    n = split(arm, parts, /"/)
    for (i = 2; i <= n; i += 2)
      if (parts[i] ~ /^--/)
        print (arm ~ /throw new ArgsException/ ? "REFUSED" : "ACCEPTED") "+=\"" parts[i] " \""
  }
' src/main/java/io/hearth/cli/Args.java)"
stale=0
for doc in "${DOCS[@]}"; do
  while IFS= read -r flag; do
    [ -z "$flag" ] && continue
    [[ " ${ACCEPTED:-} " == *" $flag "* ]] && continue   # a current flag; fine
    [[ " ${REFUSED:-} "  == *" $flag "* ]] || continue   # not ours at all; not our business
    # it is a removed flag. Naming one to say it is gone is correct; recommending it is not.
    # a line either side, because prose wraps and "replaced them" often lands on the next line
    if ! grep -qiE "removed|replac|no longer|is now|gone|upgrad|old flag" \
         <<<"$(grep -F -B1 -A1 -- "$flag" "$doc")"; then
      bad "$doc uses $flag, which Args.java refuses -- say it is gone or stop naming it"
      stale=1
    fi
  done < <(grep -ohE '\-\-[a-z][a-z-]+' "$doc" | sort -u)
done
[ $stale -eq 0 ] && ok "every flag the docs mention is one Args.java accepts"

# ---- 5. every just recipe the docs mention exists ----------------------------------------------
RECIPES=$(grep -E '^[a-z][a-z0-9_-]*([ :])' justfile | grep -v ':=' \
          | sed -E 's/[ :].*//' | sort -u)
badrecipe=0
for doc in "${DOCS[@]}"; do
  while IFS= read -r recipe; do
    [ -z "$recipe" ] && continue
    grep -qx -- "$recipe" <<<"$RECIPES" || {
      bad "$doc mentions 'just $recipe', which is not in the justfile"
      badrecipe=1
    }
    # backticked only: "just the friction list" is prose, `just validate` is an instruction
  done < <(grep -ohE '`just [a-z][a-z0-9-]*' "$doc" | awk '{print $2}' | sort -u)
done
[ $badrecipe -eq 0 ] && ok "every 'just <recipe>' the docs mention exists"

# ---- 6. every template Templates.PAGES lists is on disk, and the reverse ------------------------
# A page registered but absent fails at boot; a page present but unregistered is dead weight that
# looks like a feature to whoever finds it.
pagemiss=0
while IFS= read -r page; do
  [ -f "src/main/resources/templates/$page.mustache" ] \
    || { bad "Templates.PAGES lists $page, which has no .mustache file"; pagemiss=1; }
done < <(sed -n '/PAGES = List.of(/,/);/p' src/main/java/io/hearth/template/Templates.java \
         | grep -oE '"[a-z0-9_/]+"' | tr -d '"')
while IFS= read -r file; do
  name="${file#src/main/resources/templates/}"
  name="${name%.mustache}"
  # a template that others inherit from is never rendered by name, so it is never in PAGES
  grep -rqF "{{<$(basename "$name")}}" src/main/resources/templates && continue
  grep -q "\"$name\"" src/main/java/io/hearth/template/Templates.java \
    || { bad "$file exists but Templates.PAGES does not list it"; pagemiss=1; }
done < <(find src/main/resources/templates -name '*.mustache' | sort)
[ $pagemiss -eq 0 ] && ok "templates on disk and in Templates.PAGES agree"

# ---- 7. the admin sections the docs describe are the ones that exist ---------------------------
sectionmiss=0
while IFS= read -r section; do
  grep -qE "(^|[^a-z])$section([^a-z]|$)" MANUAL.md README.md CLAUDE.md \
    || { bad "admin section '$section' is in AdminView and in no document"; sectionmiss=1; }
done < <(sed -n '/public enum Section {/,/;$/p' src/main/java/io/hearth/web/AdminView.java \
         | grep -oE '^    [a-z]+\(' | tr -d ' (')
[ $sectionmiss -eq 0 ] && ok "every admin section is described somewhere"

# ---- 8. the schema version the docs quote is the one in the code -------------------------------
VERSION=$(grep -oE 'VERSION = [0-9]+' src/main/java/io/hearth/store/Schema.java | grep -oE '[0-9]+')
verbad=0
for doc in "${DOCS[@]}"; do
  while IFS= read -r quoted; do
    [ "$quoted" = "$VERSION" ] || {
      bad "$doc says schema v$quoted; Schema.VERSION is $VERSION"; verbad=1
    }
    # fenced blocks hold sample output, where "schema v3 -> v4" is a transcript, not a claim
  done < <(awk '/^```/{f=!f; next} !f' "$doc" | grep -ohE 'schema v[0-9]+' | grep -oE '[0-9]+')
done
[ $verbad -eq 0 ] && ok "schema version in the docs matches Schema.java"

# ---- 9. the test count the README quotes is the one the suite reports -------------------------
# A precise number in a document is a promise to keep it precise. This one is free to check because
# `just validate` has already run the suite by the time it gets here; standalone, it stays quiet
# rather than guessing.
CLAIMED=$(grep -ohE '\b[0-9]{3,5} tests\b' "${DOCS[@]}" | grep -oE '^[0-9]+' | sort -u)
if [ -n "$CLAIMED" ]; then
  if [ -d target/surefire-reports ]; then
    ACTUAL=$(grep -oh 'tests="[0-9]*"' target/surefire-reports/*.xml 2>/dev/null \
             | grep -oE '[0-9]+' | paste -sd+ | bc)
    countbad=0
    for n in $CLAIMED; do
      [ "$n" = "$ACTUAL" ] || { bad "the docs say $n tests; the suite ran $ACTUAL"; countbad=1; }
    done
    [ $countbad -eq 0 ] && ok "the test count in the docs matches the suite ($ACTUAL)"
  else
    say "skip" "test count not checked -- no surefire reports; run 'just validate'"
  fi
fi

# ---- 10. every endpoint API.md promises is one the server answers ----------------------------
# API.md is a contract with somebody else's code: a tool written against it is already relying on
# every path in it. A renamed endpoint is not a documentation bug, it is a broken client -- so the
# paths it names have to appear in the routes that serve them.
if [ -f API.md ] && [ -f src/main/java/io/hearth/api/ApiRoutes.java ]; then
  apibad=0
  for path in $(grep -ohE '/api/v1/[a-z]+' API.md | sort -u); do
    leaf=${path#/api/v1/}
    grep -q "\"$leaf\"" src/main/java/io/hearth/api/ApiRoutes.java \
      || { bad "API.md promises $path and ApiRoutes answers no such thing"; apibad=1; }
  done
  # and the error codes it tabulates
  for code in unauthorized not_allowed no_such_endpoint wrong_method server_error; do
    if grep -q "\`$code\`" API.md; then
      grep -q "\"$code\"" src/main/java/io/hearth/api/ApiRoutes.java \
        || { bad "API.md documents the error '$code' and nothing emits it"; apibad=1; }
    fi
  done
  [ $apibad -eq 0 ] && ok "every endpoint and error API.md promises is one the server has"
fi

echo
if [ $fail -eq 0 ]; then
  echo "  docs: OK"
else
  echo "  docs: FAILED -- the documents describe a program that has moved"
fi
exit $fail
