#!/usr/bin/env bash
# A13 — the gate pack. Same six checks locally and in CI.
# Drift is not something anyone has to remember. It is a red build.
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
run() { printf '\n\033[1m▸ %s\033[0m\n' "$1"; shift; "$@" || { fail=1; printf '\033[31m  ✗ failed\033[0m\n'; }; }

run "1/6  tests"                npx vitest run --reporter=dot
run "2/6  event contracts"      bash -c 'cd tools && python3 build_events.py && python3 validate_events.py'
run "3/6  functional spec"      bash -c 'cd tools && python3 build_functional.py'
run "4/6  traceability"         python3 tools/traceability.py
run "5/6  banned words"         python3 tools/check_banned_words.py
run "6/6  legal thresholds"     python3 tools/check_legal_literals.py

printf '\n\033[1m▸ generated files must be committed\033[0m\n'
if ! git diff --quiet -- docs/; then
  echo "  ✗ a generated document changed — run tools/gates.sh and commit the result:"
  git diff --name-only -- docs/ | sed 's/^/      /'
  fail=1
else
  echo "  ✓ docs/ is in sync with its generators"
fi

printf '\n'
[ $fail -eq 0 ] && { printf '\033[32mALL GATES GREEN\033[0m\n'; exit 0; }
printf '\033[31mGATES FAILED\033[0m\n'; exit 1
