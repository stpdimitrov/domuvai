#!/usr/bin/env bash
# T-07 — the session hooks, against fake hook stdin. Each case breaks the rule on purpose and asserts the refusal.
set -uo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
export ZUES_STATE_DIR=$tmp/state ZUES_CLAIM_MAX_BYTES=10000
gate=.claude/hooks/claim-gate.sh
m=$ZUES_STATE_DIR/claims
fail=0
ok()  { echo "✓ $1"; }
bad() { echo "✗ $1"; fail=1; }
event() {  # session_id transcript command
  printf '{"session_id":"%s","transcript_path":"%s","hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"%s"}}' "$1" "$2" "$3"
}
post() {  # session_id command stdout
  printf '{"session_id":"%s","hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"%s"},"tool_response":{"stdout":"%s","stderr":""}}' "$1" "$2" "$3" | $gate post
}
refused() { [ "$1" -eq 2 ] && grep -q "$2" <<<"$3"; }
lp=tools/lanes.py   # spelled apart, so this file's own text is not a claim to the live gate
claim="python3 $lp claim money --title \\\"S-99 x\\\" --body-file c.md"
head -c 2000 /dev/zero | tr '\0' x > "$tmp/fresh.jsonl"
head -c 20000 /dev/zero | tr '\0' x > "$tmp/long.jsonl"

out=$(event s1 "$tmp/fresh.jsonl" 'git status' | $gate pre 2>&1); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && [ ! -e "$m/s1" ] && ok "other commands pass untouched" || bad "other command: rc=$rc $out"

out=$(event s0 "$tmp/fresh.jsonl" "echo $claim; grep -n '$lp claim' x; git commit -m '$lp claim web'" | $gate pre 2>&1); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && [ ! -e "$m/s0" ] && ok "the words in an echo, a grep or a message are not a claim" || bad "echo: rc=$rc $out"

out=$(event s1 "$tmp/fresh.jsonl" "$claim" | $gate pre 2>&1); rc=$?
[ $rc -eq 0 ] && grep -q '"lane": "money"' "$m/s1" && ok "first claim in a fresh session passes, marked" || bad "first claim: rc=$rc $out"

out=$(event s1 "$tmp/fresh.jsonl" "${claim/money/web}" | $gate pre 2>&1); rc=$?
refused $rc 'already claimed' "$out" && ok "a second claim while the first is in flight is refused" || bad "in flight: rc=$rc $out"

post s1 "$claim" 'money: held by #7 (alice) — pick another lane'
[ ! -e "$m/s1" ] && ok "a claim that did not take clears its marker" || bad "failed claim kept: $(cat "$m/s1")"
out=$(event s1 "$tmp/fresh.jsonl" "$claim" | $gate pre 2>&1); rc=$?
[ $rc -eq 0 ] && ok "... so the session may claim another lane" || bad "retry: rc=$rc $out"

post s1 "$claim" 'https://github.com/o/r/issues/42\nmoney: yours, as #42 — branch: …'
grep -q '"issue": 42' "$m/s1" && ok "PostToolUse records the claim issue from lanes.py's output" || bad "post: $(cat "$m/s1")"

out=$(event s1 "$tmp/fresh.jsonl" "${claim/money/web}" | $gate pre 2>&1); rc=$?
refused $rc 'already claimed' "$out" && grep -q 'Boot per CLAUDE.md; lane web; next slice per the newest H- handover' <<<"$out" \
  && ok "second claim in the same session refused, with the boot prompt" || bad "second claim: rc=$rc $out"

for shape in "bash -c \\\"$claim\\\"" "FOO=1 $claim" "time python3 -u $lp claim money --title x" \
             "if true; then ./$lp claim money; fi" "python3 $lp claim --also web money"; do
  out=$(event s1 "$tmp/fresh.jsonl" "$shape" | $gate pre 2>&1); rc=$?
  refused $rc 'already claimed' "$out" && ok "gated: ${shape:0:48}" || bad "slipped past: $shape (rc=$rc)"
done

out=$(event s3 "$tmp/fresh.jsonl" "$claim; ${claim/money/web}" | $gate pre 2>&1); rc=$?
refused $rc 'claims 2 times' "$out" && [ ! -e "$m/s3" ] && ok "two claims in one command refused" || bad "double: rc=$rc $out"

echo 'not json' > "$m/s4"
out=$(event s4 "$tmp/fresh.jsonl" "$claim" | $gate pre 2>&1); rc=$?
refused $rc 'already claimed' "$out" && ok "a corrupt marker refuses, never crashes" || bad "corrupt: rc=$rc $out"
rm -f "$m/s4"

out=$(event s2 "$tmp/long.jsonl" "$claim" | $gate pre 2>&1); rc=$?
refused $rc 'too long' "$out" && [ ! -e "$m/s2" ] \
  && ok "claim in an oversized transcript refused, nothing marked" || bad "oversized: rc=$rc $out"

stop() { printf '{"session_id":"%s","transcript_path":"%s","hook_event_name":"Stop"}' "$1" "$2" | PATH="$tmp/bin:$PATH" $gate stop 2>&1; }
mkdir -p "$tmp/bin"
printf '#!/bin/sh\necho "$GH_STATE"\n' > "$tmp/bin/gh"; chmod +x "$tmp/bin/gh"
out=$(GH_STATE=OPEN stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: claim still open — silent" || bad "stop open: rc=$rc $out"
out=$(GH_STATE=CLOSED stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: asks GitHub at most once a minute" || bad "stop throttle: rc=$rc $out"
python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); d['checked']=0; json.dump(d,open(p,'w'))" "$m/s1"
out=$(GH_STATE=CLOSED stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && grep -q '"systemMessage": "Slice claim #42 is closed' <<<"$out" && ok "Stop: claim closed — one-line nudge, never blocks" || bad "stop closed: rc=$rc $out"
out=$(GH_STATE=CLOSED stop s1 "$tmp/fresh.jsonl")
[ -z "$out" ] && ok "Stop: says it once" || bad "stop repeats: $out"
out=$(GH_STATE=CLOSED stop nobody "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: a session with no marker is untouched" || bad "stop no marker: rc=$rc $out"
rm -rf "$ZUES_STATE_DIR"
out=$(stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: no markers at all — silent" || bad "stop empty: rc=$rc $out"

wired=$(python3 -c "import json; h=json.load(open('.claude/settings.json'))['hooks']
print(' '.join(e + ':' + h[e][0].get('matcher', '') + ':' + h[e][0]['hooks'][0]['command'].split()[-1]
               for e in ('PreToolUse', 'PostToolUse', 'Stop')))")
[ "$wired" = "PreToolUse:Bash:pre PostToolUse:Bash:post Stop::stop" ] && ok "settings.json wires the three hooks" || bad "wiring: $wired"

env_file=$tmp/env; : > "$env_file"
out=$(echo '{}' | CLAUDE_PROJECT_DIR=$PWD CLAUDE_ENV_FILE=$env_file CLAUDE_CODE_REMOTE= .claude/hooks/session-start.sh 2>&1); rc=$?
[ $rc -eq 0 ] && grep -qx 'export LC_ALL=C.UTF-8' "$env_file" && ok "SessionStart hook still exports the session env" || bad "session-start: rc=$rc $out"

exit $fail
