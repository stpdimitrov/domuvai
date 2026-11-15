#!/usr/bin/env bash
# T-05 — one quiet call before any push: a lane's tests + every python gate, generator and the
# "generated files committed" check of tools/gates.sh. Prints one line per step, error lines only on failure.
#   tools/check.sh            all tests (as CI)
#   tools/check.sh money      only zues.app.money.* (ITs run when Docker is up — the session hook starts it)
set -uo pipefail
cd "$(dirname "$0")/.."
export LC_ALL=C.UTF-8 LANG=C.UTF-8 TESTCONTAINERS_RYUK_DISABLED=true   # non-ASCII test class names
lane=${1:-}
if [ -n "$lane" ]; then
  pkg=${lane//-/_}
  if [ -d "app/src/test/kotlin/zues/app/$pkg" ]; then export GATES_TESTS="./gradlew -q :app:test --tests zues.app.$pkg.* --tests zues.app.OpenApiContractTest"   # the openapi gate reads its spec
  elif [ -f "$lane/build.gradle.kts" ]; then export GATES_TESTS="./gradlew -q :$lane:test :app:test --tests zues.app.OpenApiContractTest"
  else echo "check.sh: no tests for lane '$lane'"; exit 2; fi
fi
docker info >/dev/null 2>&1 || echo "! Docker is down — every *IT will SKIP"
GATES_QUIET=1 exec tools/gates.sh
