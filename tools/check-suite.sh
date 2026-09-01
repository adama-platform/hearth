#!/usr/bin/env bash
#
# Did every test actually run?
#
# A green suite is only evidence if the tests in it executed. There are two ways a class can stop
# running without anything going red, and this project has now shipped one of them.
#
# The one it shipped: reducing the feature set removed eight of the thirteen message flows, and the
# script that removed test methods naming a dead symbol emptied two whole classes -- leaving a
# `@Before`, an `@After` and nothing to run. Surefire skips a class with no tests silently. So
# `SystemTemplateTests` sat there, the right size and the right shape, covering a feature that still
# shipped, asserting nothing. Nobody would notice by reading a build log, because there is nothing
# in the log to read.
#
# Both checks are mechanical and neither has an opinion about what a test should contain.
#
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
say() { printf '  %-6s %s\n' "$1" "$2"; }
bad() { say "BAD" "$1"; fail=1; }
ok()  { say "ok" "$1"; }

echo
echo "  suite"

# ---- 1. no test class is empty -----------------------------------------------------------------
# The direct check for what happened. A file named *Tests with no @Test in it is either a mistake
# or a placeholder, and both are better deleted than left looking like coverage.
empty=0
while IFS= read -r file; do
  grep -qE '^\s*@Test' "$file" || {
    bad "$(basename "$file" .java) has no @Test -- surefire will skip it silently"
    empty=1
  }
done < <(find src/test -name '*Tests.java' | sort)
[ $empty -eq 0 ] && ok "every *Tests class has at least one @Test"

# ---- 2. every test class produced a report -----------------------------------------------------
# The general check, which catches the same thing plus whatever else stops a class running. Only
# meaningful once the suite has been run, so standalone it stays quiet rather than guessing.
# An abstract class is a base for other tests and is never run on its own.
if [ -d target/surefire-reports ] && ls target/surefire-reports/*.xml >/dev/null 2>&1; then
  ran=0
  while IFS= read -r file; do
    grep -qE '^\s*(public\s+)?abstract\s+class' "$file" && continue
    class=$(sed 's|^src/test/java/||; s|\.java$||; s|/|.|g' <<<"$file")
    [ -f "target/surefire-reports/TEST-$class.xml" ] || {
      bad "$class has no surefire report -- it did not run"
      ran=1
    }
  done < <(find src/test -name '*Tests.java' | sort)
  [ $ran -eq 0 ] && ok "every *Tests class produced a report"
else
  say "skip" "reports not checked -- no surefire output; run 'just validate'"
fi

echo
if [ $fail -eq 0 ]; then
  echo "  suite: OK"
else
  echo "  suite: FAILED -- a test that does not run is not a test"
fi
exit $fail
