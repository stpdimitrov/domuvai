#!/usr/bin/env bash
# T-05 — the session boots its own environment, so no session spends calls debugging it.
# Idempotent, quiet on success (one line per failure), fast when already done. Never fails the session.
set -uo pipefail
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
log="${TMPDIR:-/tmp}/zues-session-start.log"
warn() { echo "session-start: $* (see $log)"; }

# Session env. Kotlin test class names are non-ASCII: without a UTF-8 locale Gradle throws InvalidPathException.
# A Gradle daemon started without it keeps failing — stop it: ./gradlew --stop.
ENV_LINES=(
  'export LC_ALL=C.UTF-8'
  'export LANG=C.UTF-8'
  'export TESTCONTAINERS_RYUK_DISABLED=true'
)
export LC_ALL=C.UTF-8 LANG=C.UTF-8 TESTCONTAINERS_RYUK_DISABLED=true
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  for l in "${ENV_LINES[@]}"; do grep -qxF "$l" "$CLAUDE_ENV_FILE" 2>/dev/null || echo "$l" >> "$CLAUDE_ENV_FILE"; done
fi

# Everything below provisions a cloud container; a local machine is the developer's own.
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

retry() { local n=0 d=2; until "$@" >>"$log" 2>&1; do n=$((n+1)); [ $n -ge 4 ] && return 1; sleep $d; d=$((d*2)); done; }

# Python deps for the gate pack (build_openapi needs openapi_spec_validator).
python3 -c 'import openapi_spec_validator, jsonschema, markdown, yaml, rfc3339_validator' 2>/dev/null \
  || retry pip install -q -r tools/requirements.txt || warn "pip install failed"

# Web client deps.
[ -d web/node_modules ] || (cd web && retry npm ci) || warn "npm ci in web/ failed"

# Docker. Docker 29 rejects the API version Testcontainers speaks; without the floor, every *IT silently SKIPS.
if ! docker info >/dev/null 2>&1; then
  DOCKER_MIN_API_VERSION=1.24 nohup dockerd </dev/null >>"$log" 2>&1 & disown
  for _ in $(seq 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
  docker info >/dev/null 2>&1 || warn "dockerd did not start"
fi
# Docker Hub answers 429: pull through Google's mirror and tag it as the name the tests ask for.
if docker info >/dev/null 2>&1 && ! docker image inspect postgres:16 >/dev/null 2>&1; then
  { retry docker pull mirror.gcr.io/library/postgres:16 && docker tag mirror.gcr.io/library/postgres:16 postgres:16; } \
    || warn "postgres:16 pull failed"
fi

# Maven Central answers 429 from here: resolve through Google's mirror of it first (this container's ~/.gradle only).
init=~/.gradle/init.d/zues-central-mirror.gradle
if [ ! -f "$init" ]; then
  mkdir -p "$(dirname "$init")"
  cat > "$init" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
beforeSettings { s ->
  s.pluginManagement.repositories { maven { url mirror } }
  s.dependencyResolutionManagement.repositories { maven { url mirror } }
}
GRADLE
fi
# Gradle dependencies, once per build-file change; still retried with backoff.
stamp=.gradle/zues-deps.sha
sum=$(cat ./*.gradle.kts */build.gradle.kts gradle/*.toml gradle/wrapper/*.properties 2>/dev/null | sha256sum | cut -c1-16)
if [ "$(cat "$stamp" 2>/dev/null)" != "$sum" ]; then
  if retry ./gradlew -q testClasses; then mkdir -p .gradle && echo "$sum" > "$stamp"; else warn "gradle testClasses failed"; fi
fi
exit 0
