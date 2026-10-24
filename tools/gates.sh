#!/usr/bin/env bash
# A13 — the gate pack. The same checks locally and in CI.
# Drift is not something anyone has to remember. It is a red build.
set -uo pipefail
cd "$(dirname "$0")/.."
# JDK 21 for the Gradle build; the system default may be newer than Gradle runs on.
[ -z "${JAVA_HOME:-}" ] && [ -d /usr/local/opt/openjdk@21 ] && export JAVA_HOME=/usr/local/opt/openjdk@21
fail=0
# GATES_QUIET=1 (tools/check.sh): one line per passing step, only the error lines of a failing one — never raw logs.
# GATES_TESTS overrides the test command (check.sh runs one lane's tests).
run() {
  local name=$1; shift
  local out; out=$(mktemp)
  if [ -z "${GATES_QUIET:-}" ]; then
    printf '\n\033[1m▸ %s\033[0m\n' "$name"
    "$@" 2>&1 | tee "$out"; [ "${PIPESTATUS[0]}" -eq 0 ] && { rm -f "$out"; return; }
    fail=1; printf '\033[31m  ✗ failed\033[0m\n'
    # In CI the failing gate's error lines become a check-run annotation: tools/ci_failure.py reads them when
    # the job log itself is unreachable (it is, from a Claude Code session).
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error title=gate $name::$(errors "$out" | head -10 | sed ':a;N;$!ba;s/%/%25/g;s/\n/%0A/g')"
  elif "$@" >"$out" 2>&1; then echo "✓ $name"
  else fail=1; echo "✗ $name"; errors "$out"
  fi
  rm -f "$out"
}
errors() {
  local hit; hit=$(grep -E 'FAILED|✗|[Ee]rror|What went wrong|> |Traceback|^E |NO RAW' "$1" | grep -vE '^\s+at ' | head -25)
  if [ -n "$hit" ]; then echo "$hit"; else tail -15 "$1"; fi
}

run "1/11  tests"               ${GATES_TESTS:-./gradlew test --console=plain}
run "2/11  event contracts"     bash -c 'cd tools && python3 build_events.py && python3 validate_events.py'
run "3/11  functional spec"     bash -c 'cd tools && python3 build_functional.py'
run "4/11  traceability"        python3 tools/traceability.py
run "5/11  test plan"           python3 tools/testplan.py
run "6/11  openapi"             python3 tools/build_openapi.py
run "7/11  banned words"        python3 tools/check_banned_words.py
run "8/11  legal thresholds"    python3 tools/check_legal_literals.py
run "9/11  schema columns"      python3 tools/check_schema_columns.py
run "10/11 legal today"         python3 tools/check_legal_today.py
run "11/11 catalogue version"   python3 tools/check_catalogue_version.py

# Only GENERATED paths belong here. A hand-written document changing is normal;
# flagging it would make this gate noise, and noise gets switched off.
# docs/TRACEABILITY.md is generated and NOT committed (.gitignore): every slice that adds a test changes it, so
# committed it conflicted between any two open PRs. Gate 4 still runs its generator, which fails on an unknown rule ID.
GENERATED=(docs/FUNCTIONAL.md docs/TESTPLAN.md docs/events docs/api)
[ -z "${GATES_QUIET:-}" ] && printf '\n\033[1m▸ generated files must be committed\033[0m\n'
if ! git diff --quiet -- "${GENERATED[@]}"; then
  echo "  ✗ a generated document is out of sync with its generator — commit the regenerated result:"
  git diff --name-only -- "${GENERATED[@]}" | sed 's/^/      /'
  fail=1
else
  echo "✓ generated documents match their generators"
fi

[ -z "${GATES_QUIET:-}" ] && printf '\n'
[ $fail -eq 0 ] && { printf '\033[32mALL GATES GREEN\033[0m\n'; exit 0; }
printf '\033[31mGATES FAILED\033[0m\n'; exit 1
