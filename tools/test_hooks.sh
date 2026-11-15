#!/usr/bin/env bash
# T-07, T-08, T-09 — the session hooks, against fake hook stdin. Each case breaks the rule on purpose and asserts the refusal.
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
  printf '{"session_id":"%s","transcript_path":"%s","cwd":"%s","hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"%s"}}' "$1" "$2" "$tmp" "$3"
}
post() {  # session_id command stdout
  printf '{"session_id":"%s","hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"%s"},"tool_response":{"stdout":"%s","stderr":""}}' "$1" "$2" "$3" | $gate post
}
refused() { [ "$1" -eq 2 ] && grep -q "$2" <<<"$3"; }
lp=tools/lanes.py   # spelled apart, so this file's own text is not a claim to the live gate
claim="python3 $lp claim money --title \\\"S-99 x\\\" --body-file c.md"
printf 'Slice:        S-99 x\nModel:        Sonnet\nConfirmed:    the owner, 2026-10-28, in this session\n' > "$tmp/c.md"
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
refused $rc 'already claimed' "$out" && grep -q 'Boot per CLAUDE.md, lean; lane web; model Sonnet.*lanes.py check web; if the lane is held' <<<"$out" \
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

# T-08: the contract states its model.
printf 'Slice:        S-99 x\nModel:        <Opus|Sonnet> — Opus for design, Sonnet for mechanical\n' > "$tmp/nomodel.md"
out=$(event s5 "$tmp/fresh.jsonl" "${claim/c.md/nomodel.md}" | $gate pre 2>&1); rc=$?
refused $rc 'no `Model:` line' "$out" && [ ! -e "$m/s5" ] && ok "a contract with no Model: line (or the unedited template) refused" || bad "no model: rc=$rc $out"
out=$(event s5 "$tmp/fresh.jsonl" "${claim/c.md/missing.md}" | $gate pre 2>&1); rc=$?
refused $rc 'no `Model:` line' "$out" && ok "a claim whose contract is unreadable refused" || bad "no contract: rc=$rc $out"
out=$(event s5 "$tmp/fresh.jsonl" "${claim/ --body-file c.md/}" | $gate pre 2>&1); rc=$?
refused $rc 'no `Model:` line' "$out" && ok "a claim with no --body-file refused" || bad "no body-file: rc=$rc $out"

# T-09: the contract records the owner's confirmation — who, a date, where.
for line in '' 'Confirmed:    <who, yyyy-mm-dd, where>' 'Confirmed:    <who>, 2026-10-28, <where>' 'Confirmed:    the owner, today' 'Confirmed:' 'Confirmed:\nDone when:    2026-10-28'; do
  printf 'Slice:        S-99 x\nModel:        Sonnet\n%b\n' "$line" > "$tmp/unconfirmed.md"
  out=$(event s6 "$tmp/fresh.jsonl" "${claim/c.md/unconfirmed.md}" | $gate pre 2>&1); rc=$?
  refused $rc 'no `Confirmed:` line' "$out" && [ ! -e "$m/s6" ] && ok "unconfirmed contract refused: '${line:-no line}'" || bad "unconfirmed '$line': rc=$rc $out"
done

stop() { printf '{"session_id":"%s","transcript_path":"%s","hook_event_name":"Stop"}' "$1" "$2" | PATH="$tmp/bin:$PATH" $gate stop 2>&1; }
mkdir -p "$tmp/bin"
printf '#!/bin/sh\necho "$GH_STATE"\n' > "$tmp/bin/gh"; chmod +x "$tmp/bin/gh"
out=$(GH_STATE=OPEN stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: claim still open — silent" || bad "stop open: rc=$rc $out"
out=$(GH_STATE=CLOSED stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: asks GitHub at most once a minute" || bad "stop throttle: rc=$rc $out"
python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); d['checked']=0; json.dump(d,open(p,'w'))" "$m/s1"
out=$(GH_STATE=CLOSED stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && grep -q '"systemMessage": "Slice claim #42 is closed (its PR merged).*if you confirmed the next contract here.*starts the successor.*model Sonnet' <<<"$out" && ok "Stop: claim closed — one-line nudge, never blocks" || bad "stop closed: rc=$rc $out"
out=$(GH_STATE=CLOSED stop s1 "$tmp/fresh.jsonl")
[ -z "$out" ] && ok "Stop: says it once" || bad "stop repeats: $out"
out=$(GH_STATE=CLOSED stop nobody "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: a session with no marker is untouched" || bad "stop no marker: rc=$rc $out"
rm -rf "$ZUES_STATE_DIR"
out=$(stop s1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "Stop: no markers at all — silent" || bad "stop empty: rc=$rc $out"

# T-08 — the size gate on every turn and every outward act, and the cost record.
sg=.claude/hooks/size-gate.sh
export ZUES_SOFT_BYTES=5000 ZUES_HARD_BYTES=10000
head -c 7000 /dev/zero | tr '\0' x > "$tmp/mid.jsonl"
say() {  # session_id transcript prompt
  printf '{"session_id":"%s","transcript_path":"%s","hook_event_name":"UserPromptSubmit","prompt":"%s"}' "$1" "$2" "$3" | $sg prompt 2>&1
}
tool() {  # session_id transcript tool_name command
  printf '{"session_id":"%s","transcript_path":"%s","hook_event_name":"PreToolUse","tool_name":"%s","tool_input":{"command":"%s"}}' "$1" "$2" "$3" "$4" | $sg act 2>&1
}
end() { printf '{"session_id":"%s","transcript_path":"%s","hook_event_name":"Stop"}' "$1" "$2" | $sg stop 2>&1; }
st=$ZUES_STATE_DIR

out=$(say f1 "$tmp/fresh.jsonl" 'next slice'); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "size gate: a small fresh session's prompt untouched" || bad "fresh prompt: rc=$rc $out"
for c in 'git push -u origin x' 'gh pr create --fill' 'gh pr merge 3 --squash'; do
  out=$(tool f1 "$tmp/fresh.jsonl" Bash "$c"); rc=$?
  [ $rc -eq 0 ] && [ -z "$out" ] || bad "fresh $c: rc=$rc $out"
done; ok "size gate: a small fresh session may push, open and merge"

out=$(say m1 "$tmp/mid.jsonl" 'carry on'); rc=$?
[ $rc -eq 0 ] && grep -q '^Session size: transcript 7k bytes (soft limit 5k, hard 10k) — a fresh session is cheaper' <<<"$out" \
  && ok "size gate: soft limit — one line of warning, the prompt passes" || bad "soft: rc=$rc $out"

out=$(say l1 "$tmp/long.jsonl" 'carry on'); rc=$?
refused $rc 'prompt refused: this session is too long to carry on — transcript 20k bytes, limit 10k' "$out" \
  && grep -q 'Boot per CLAUDE.md, lean; lane <lane>; model <model>' <<<"$out" && grep -q 'status only' <<<"$out" \
  && ok "size gate: hard limit — the prompt refused, with both boot prompts" || bad "hard: rc=$rc $out"
for c in 'git push origin HEAD' 'cd x && git -C . push' 'gh pr create --fill' 'gh pr merge 3 --squash'; do
  out=$(tool l1 "$tmp/long.jsonl" Bash "$c"); rc=$?
  refused $rc 'too long for delivering' "$out" && ok "size gate: refused over the hard limit: $c" || bad "slipped: $c (rc=$rc)"
done
out=$(tool l1 "$tmp/long.jsonl" mcp__ccd_session__start_session ''); rc=$?
refused $rc 'too long for starting a session' "$out" && ok "size gate: start_session refused over the hard limit" || bad "spawn: rc=$rc $out"
out=$(tool l1 "$tmp/long.jsonl" mcp__claude-code-remote__create_session ''); rc=$?
refused $rc 'too long for starting a session' "$out" && ok "size gate: §7.5's create_session refused too" || bad "create: rc=$rc $out"
out=$(tool l1 "$tmp/long.jsonl" Bash 'git status && gh pr view 3'); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && ok "size gate: other commands pass even over the hard limit" || bad "other: rc=$rc $out"

out=$(say l1 "$tmp/long.jsonl" 'override-size: merge it'); rc=$?
[ $rc -eq 0 ] && ok "size gate: the override word lets the turn pass" || bad "override: rc=$rc $out"
out=$(tool l1 "$tmp/long.jsonl" Bash 'gh pr merge 3 --squash'); rc=$?
[ $rc -eq 0 ] && ok "... and that turn's merge" || bad "override act: rc=$rc $out"
end l1 "$tmp/long.jsonl" >/dev/null
out=$(tool l1 "$tmp/long.jsonl" Bash 'gh pr merge 3 --squash'); rc=$?
refused $rc 'too long' "$out" && ok "... for that one turn only: after Stop it is refused again" || bad "override kept: rc=$rc $out"

out=$(end f1 "$tmp/fresh.jsonl"); rc=$?
[ $rc -eq 0 ] && [ -z "$out" ] && python3 -c "import json,sys; d=json.load(open(sys.argv[1])); assert d['bytes']==2000 and d['turns']==1" "$st/f1.json" \
  && ok "Stop: the cost record written (bytes, turns), silently" || bad "cost record: rc=$rc $out $(cat "$st/f1.json")"
out=$(python3 tools/cost.py f1); rc=$?
[ $rc -eq 0 ] && grep -q '^transcript 2k bytes · 1 turns' <<<"$out" && ok "tools/cost.py reads it" || bad "cost.py: rc=$rc $out"
out=$(python3 tools/cost.py nobody 2>&1); rc=$?
[ $rc -eq 1 ] && grep -q 'no cost record' <<<"$out" && ok "tools/cost.py: no record — says so, exits 1" || bad "cost.py none: rc=$rc $out"
unset ZUES_SOFT_BYTES ZUES_HARD_BYTES

wired=$(python3 -c "import json; h=json.load(open('.claude/settings.json'))['hooks']
print(' '.join(e + ':' + g.get('matcher', '') + ':' + c['command'].split()[-1]
               for e in ('UserPromptSubmit', 'PreToolUse', 'PostToolUse', 'Stop') for g in h[e] for c in g['hooks']))")
[ "$wired" = "UserPromptSubmit::prompt PreToolUse:Bash:pre PreToolUse:Bash|mcp__.*__(start|create)_session:act PostToolUse:Bash:post Stop::stop Stop::stop" ] \
  && grep -q 'size-gate.sh stop' .claude/settings.json && ok "settings.json wires the hooks" || bad "wiring: $wired"

env_file=$tmp/env; : > "$env_file"
out=$(echo '{}' | CLAUDE_PROJECT_DIR=$PWD CLAUDE_ENV_FILE=$env_file CLAUDE_CODE_REMOTE= .claude/hooks/session-start.sh 2>&1); rc=$?
[ $rc -eq 0 ] && grep -qx 'export LC_ALL=C.UTF-8' "$env_file" && ok "SessionStart hook still exports the session env" || bad "session-start: rc=$rc $out"

exit $fail
