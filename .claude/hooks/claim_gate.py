#!/usr/bin/env python3
"""T-07 — one slice per fresh session, enforced. Called by claim-gate.sh with the hook's stdin JSON.

  pre   PreToolUse(Bash): `tools/lanes.py claim` is refused (exit 2) when this session already claimed — or has a
        claim in flight — when its transcript is past CLAIM_MAX_BYTES (a long or forked session), or when the contract
        (`--body-file`) states no `Model:` (T-08). Otherwise the claim is allowed and marked.
  post  PostToolUse(Bash): records the claim issue from lanes.py's own output ("yours, as #n"); a claim that did not
        take (lane held, lost race) leaves no issue, so its marker is removed and the session may pick another lane.
  stop  Stop: once the claim issue is closed (the slice PR merged), one line: end this session, start the next.
        Never blocks, never fails; asks GitHub at most once a minute, says it once.

Marker: <state>/claims/<session_id>, JSON {lane, issue, state, checked, nudged}; <state> is .claude/state
(gitignored), passed by claim-gate.sh as $ZUES_STATE_DIR.
"""
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

# The measure, the claim limit (T-07: 1.2 MB, ZUES_CLAIM_MAX_BYTES) and the boot prompt are shared with the size gate.
from size_gate import CLAIM_MAX_BYTES as MAX_BYTES, boot_prompt, transcript_bytes

IN_FLIGHT = 120   # seconds: a marker with no issue older than this is a claim whose PostToolUse never came
CLAIM = re.compile(r'lanes\.py["\']?\s+claim\s+(?:-\S+\s+)*([a-z][a-z0-9-]*)')
CLAIMED = re.compile(r'yours, as #(\d+)')   # what lanes.py claim prints on success
# A command segment that only shows the words — not a claim.
BODY_FILE = re.compile(r'--body-file[ =]["\']?([^\s"\';&|]+)')
MODEL = re.compile(r'^\s*Model:\s*(Opus|Sonnet|Haiku|Fable)\b', re.M | re.I)   # the contract's Model: line (T-08)
QUOTING = re.compile(r'\s*(?:\w+=\S*\s+)*(?:echo|printf|grep|rg|sed|awk|cat|less|head|tail|git|gh)\b')


def state_dir() -> Path:
    root = os.environ.get('ZUES_STATE_DIR') or Path(os.environ.get('CLAUDE_PROJECT_DIR', '.')) / '.claude/state'
    return Path(root) / 'claims'


def claims(command: str) -> list[str]:
    """The lanes claimed by a command, one per invocation."""
    return [m.group(1) for seg in re.split(r'[;&|\n]', command) if not QUOTING.match(seg)
            for m in CLAIM.finditer(seg)]


def marker_of(sid: str) -> Path | None:
    return state_dir() / sid if re.fullmatch(r'[\w-]+', sid) else None


def contract(command: str, cwd: str) -> str:
    """The text of the claim's --body-file, '' when absent or unreadable."""
    hit = BODY_FILE.search(command)
    try:
        return (Path(cwd or '.') / hit.group(1)).read_text() if hit else ''
    except OSError:
        return ''


def pre(event: dict) -> int:
    lanes = claims(event.get('tool_input', {}).get('command', ''))
    if not lanes:
        return 0
    path, transcript = marker_of(event.get('session_id', '')), event.get('transcript_path', '')
    why = None
    if len(lanes) > 1:
        why = f'one command claims {len(lanes)} times — one slice per session'
    elif path and path.exists():
        try:
            marker = json.loads(path.read_text())
        except ValueError:
            marker = {'lane': '?', 'issue': '?'}
        if marker.get('issue') or time.time() - path.stat().st_mtime < IN_FLIGHT:
            why = f'this session already claimed a slice (lane {marker.get("lane")}) — one slice per session'
    if why is None:
        size = transcript_bytes(transcript)
        if size > MAX_BYTES:
            why = (f'this session is too long to start a slice in — transcript {size // 1000}k bytes, '
                   f'limit {MAX_BYTES // 1000}k (a long or forked session pays for its whole context on every call)')
    model = MODEL.search(contract(event.get('tool_input', {}).get('command', ''), event.get('cwd', '')))
    if why is None and not model:
        print('claim refused: the contract (--body-file) has no `Model:` line — state the model this slice runs on '
              '(Opus for design or rule slices, Sonnet for mechanical ones; CLAUDE.md, zues-slice §3).', file=sys.stderr)
        return 2
    if why:
        print(f'claim refused: {why}.\nClaim only in a session whose first act was the boot. '
              f'Start a fresh session with:\n\n  {boot_prompt(lanes[0], model.group(1) if model else "<model>")}',
              file=sys.stderr)
        return 2
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({'lane': lanes[0], 'model': model.group(1), 'issue': None}))
    return 0


def post(event: dict) -> int:
    path = marker_of(event.get('session_id', ''))
    if not (path and path.is_file() and claims(event.get('tool_input', {}).get('command', ''))):
        return 0
    marker = json.loads(path.read_text())
    if marker.get('issue'):
        return 0
    hit = CLAIMED.search(json.dumps(event.get('tool_response', '')))
    if hit:
        marker['issue'] = int(hit.group(1))
        path.write_text(json.dumps(marker))
    else:
        path.unlink()
    return 0


def stop(event: dict) -> int:
    path = marker_of(event.get('session_id', ''))
    if not (path and path.is_file()):
        return 0
    marker = json.loads(path.read_text())
    issue = marker.get('issue')
    if not issue or marker.get('nudged'):
        return 0
    if marker.get('state') != 'CLOSED' and time.time() - marker.get('checked', 0) >= 60:
        try:
            marker['state'] = subprocess.run(['gh', 'issue', 'view', str(issue), '--json', 'state', '-q', '.state'],
                                             capture_output=True, text=True, timeout=8).stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            pass
        marker['checked'] = time.time()
    if marker.get('state') == 'CLOSED':
        marker['nudged'] = True
        print(json.dumps({'systemMessage': f'Slice claim #{issue} is closed — sync main, end this session and start '
                                           f'the successor fresh (zues-slice §7.5): {boot_prompt(marker["lane"], marker.get("model", "<model>"))}'}))
    path.write_text(json.dumps(marker))
    return 0


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else ''
    try:
        event = json.load(sys.stdin)
    except ValueError:
        sys.exit(0)
    if mode in ('post', 'stop'):
        try:
            sys.exit((post if mode == 'post' else stop)(event))
        except Exception:   # these never fail the session
            sys.exit(0)
    sys.exit(pre(event) if mode == 'pre' else 0)
