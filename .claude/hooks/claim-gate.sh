#!/usr/bin/env bash
# T-07 — the claim gate (PreToolUse Bash: `claim-gate.sh pre`; PostToolUse Bash: `post` records the issue) and the close nudge (Stop: `claim-gate.sh stop`).
# Fast path in bash, so the hook costs nothing on every other command; claim_gate.py does the work.
dir=$(dirname "$0")
export ZUES_STATE_DIR=${ZUES_STATE_DIR:-${CLAUDE_PROJECT_DIR:-$dir/../..}/.claude/state}   # one state dir for both halves
case "${1:-}" in
  pre|post) in=$(cat); case "$in" in *lanes.py*claim*) ;; *) exit 0 ;; esac ;;
  stop) [ -n "$(ls -A "$ZUES_STATE_DIR/claims" 2>/dev/null)" ] || exit 0; in=$(cat) ;;
  *)    exit 0 ;;
esac
printf '%s' "$in" | exec python3 "$dir/claim_gate.py" "$1"
