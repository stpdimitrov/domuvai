#!/usr/bin/env bash
# A13 — the gate pack. The same checks locally and in CI.
# Drift is not something anyone has to remember. It is a red build.
set -uo pipefail
cd "$(dirname "$0")/.."
# JDK 21 for the Gradle build; the system default may be newer than Gradle runs on.
[ -z "${JAVA_HOME:-}" ] && [ -d /usr/local/opt/openjdk@21 ] && export JAVA_HOME=/usr/local/opt/openjdk@21
fail=0
run() { printf '\n\033[1m▸ %s\033[0m\n' "$1"; shift; "$@" || { fail=1; printf '\033[31m  ✗ failed\033[0m\n'; }; }

run "1/11  tests"               ./gradlew test --console=plain
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
GENERATED=(docs/FUNCTIONAL.md docs/TRACEABILITY.md docs/TESTPLAN.md docs/events docs/api)
printf '\n\033[1m▸ generated files must be committed\033[0m\n'
if ! git diff --quiet -- "${GENERATED[@]}"; then
  echo "  ✗ a generated document is out of sync with its generator — commit the regenerated result:"
  git diff --name-only -- "${GENERATED[@]}" | sed 's/^/      /'
  fail=1
else
  echo "  ✓ generated documents match their generators"
fi

printf '\n'
[ $fail -eq 0 ] && { printf '\033[32mALL GATES GREEN\033[0m\n'; exit 0; }
printf '\033[31mGATES FAILED\033[0m\n'; exit 1
