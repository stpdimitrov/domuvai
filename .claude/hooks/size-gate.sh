#!/usr/bin/env bash
# T-08 — the size gate (UserPromptSubmit: `size-gate.sh prompt`; PreToolUse Bash|start_session: `act`) and the cost
# record (Stop: `stop`). Fast path in bash, so every other command costs nothing; size_gate.py does the work.
dir=$(dirname "$0")
export ZUES_STATE_DIR=${ZUES_STATE_DIR:-${CLAUDE_PROJECT_DIR:-$dir/../..}/.claude/state}
in=$(cat)
case "${1:-}" in
  act) case "$in" in *'git '*push*|*'gh pr create'*|*'gh pr merge'*|*'_session","tool_input'*|*'_session", "tool_input'*) ;; *) exit 0 ;; esac ;;
  prompt|stop) ;;
  *) exit 0 ;;
esac
printf '%s' "$in" | exec python3 "$dir/size_gate.py" "$1"
