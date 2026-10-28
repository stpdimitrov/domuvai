#!/usr/bin/env python3
"""T-08 — the cost policy for every session, not only at a claim. Called by size-gate.sh with the hook's stdin JSON.

  prompt  UserPromptSubmit: above SOFT_BYTES one line of context (the size, and that a fresh session is cheaper);
          above HARD_BYTES the prompt is refused (exit 2) with the fresh-session boot prompt. A prompt starting with
          OVERRIDE lets that one turn pass — its outward acts too. Counts the turn.
  act     PreToolUse: `git push`, `gh pr create`, `gh pr merge` and session starts are refused above HARD_BYTES, so an
          oversized session can neither deliver nor spawn — unless this turn was overridden.
  stop    Stop: records the transcript size in <state>/<session_id>.json and ends the override. Never fails.

State: <state>/<session_id>.json, JSON {transcript, bytes, turns, override, updated}; <state> is .claude/state
(gitignored). tools/cost.py prints it. The measure and the limits live here only; claim_gate.py imports them.
"""
import json
import os
import re
import sys
import time
from pathlib import Path

# Measured over this machine's transcripts (the T-08 log entry): a fresh boot is 0.6–0.8 MB before it reads anything,
# a single-slice session claims at 0.9–1.1 MB and pushes or merges at 1.5–1.7 MB, and ends by 2.2 MB; the coordinating
# WEB-28 session ran to 4 MB. HARD is past every normal slice's delivery (2.2 MB + ~15%); SOFT is past a normal
# delivery, where carrying on stops being the cheaper choice. Half of HARD would warn every slice after its claim.
CLAIM_MAX_BYTES = int(os.environ.get('ZUES_CLAIM_MAX_BYTES', 1_200_000))   # T-07: where a claim may still be made
SOFT_BYTES = int(os.environ.get('ZUES_SOFT_BYTES', 2_000_000))
HARD_BYTES = int(os.environ.get('ZUES_HARD_BYTES', 2_500_000))
OVERRIDE = 'override-size:'
OUTWARD = re.compile(r'\bgit\s+(?:(?:-[Cc]\s+\S+|-\S+)\s+)*push\b|\bgh\s+pr\s+(?:create|merge)\b')
# A session start: the app's start_session, or the claude-code-remote create_session of zues-slice §7.5.
START_SESSION = re.compile(r'mcp__.+__(?:start|create)_session')


def transcript_bytes(path: str) -> int:
    """The session's size: its transcript in bytes (0 when unknown). Every call re-sends roughly this much."""
    try:
        return os.path.getsize(path) if path else 0
    except OSError:
        return 0


def boot_prompt(lane: str = '<lane>', model: str = '<model>') -> str:
    # The successor's prompt, zues-slice §7.5 (T-09): the contract arrives confirmed, so the claim follows the boot.
    return (f'Boot per CLAUDE.md, lean; lane {lane}; model {model}. Contract: <the confirmed contract verbatim, its '
            'Confirmed: line quoting who, the date and where — claim with it as written; or "none": draft one from the '
            'newest H- handover, docs/TESTPLAN.md and the open owner-question issues and get it confirmed before the '
            f'claim>. Before the claim run python3 tools/lanes.py check {lane}; if the lane is held, say so in one '
            'line and stop. Work cheaply; record a Cost line.')


def status_prompt() -> str:
    # The coordinating variant: a fresh session that answers one question and ends.
    return ('Boot per CLAUDE.md, status only: run python3 tools/lanes.py and read the newest H- handover; answer '
            '<the question>; claim nothing, write no code; end the session once it is answered.')


def fresh_session(size: int, what: str) -> str:
    return (f'{what} — transcript {size // 1000}k bytes, limit {HARD_BYTES // 1000}k: this session re-sends its whole '
            f'context on every call. Start a fresh session with\n\n  {boot_prompt()}\n\nor, to coordinate:\n\n'
            f'  {status_prompt()}\n\nTo let one turn pass anyway, start the prompt with `{OVERRIDE}`.')


def state_of(sid: str) -> Path | None:
    root = os.environ.get('ZUES_STATE_DIR') or Path(os.environ.get('CLAUDE_PROJECT_DIR', '.')) / '.claude/state'
    return Path(root) / f'{sid}.json' if re.fullmatch(r'[\w-]+', sid) else None


def load(path: Path | None) -> dict:
    try:
        return json.loads(path.read_text()) if path else {}
    except (OSError, ValueError):
        return {}


def save(path: Path | None, state: dict) -> None:
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(state))


def prompt(event: dict) -> int:
    path = state_of(event.get('session_id', ''))
    state = load(path)
    state['turns'] = state.get('turns', 0) + 1
    size = transcript_bytes(event.get('transcript_path', ''))
    overridden = event.get('prompt', '').lstrip().startswith(OVERRIDE)
    state['override'] = overridden
    save(path, state)
    if size > HARD_BYTES and not overridden:
        print(f'prompt refused: {fresh_session(size, "this session is too long to carry on")}', file=sys.stderr)
        return 2
    if size > SOFT_BYTES:
        print(f'Session size: transcript {size // 1000}k bytes (soft limit {SOFT_BYTES // 1000}k, hard '
              f'{HARD_BYTES // 1000}k) — a fresh session is cheaper than carrying on here.')
    return 0


def act(event: dict) -> int:
    tool = event.get('tool_name', '')
    if not (START_SESSION.fullmatch(tool) or OUTWARD.search(event.get('tool_input', {}).get('command', '') or '')):
        return 0
    size = transcript_bytes(event.get('transcript_path', ''))
    if size <= HARD_BYTES or load(state_of(event.get('session_id', ''))).get('override'):
        return 0
    what = 'starting a session' if START_SESSION.fullmatch(tool) else 'delivering (push, PR, merge)'
    print(f'refused: {fresh_session(size, f"this session is too long for {what}")}', file=sys.stderr)
    return 2


def stop(event: dict) -> int:
    path = state_of(event.get('session_id', ''))
    state = load(path)
    transcript = event.get('transcript_path', '')
    state.update(transcript=transcript, bytes=transcript_bytes(transcript), override=False, updated=int(time.time()))
    save(path, state)
    return 0


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else ''
    try:
        event = json.load(sys.stdin)
    except ValueError:
        sys.exit(0)
    if mode == 'stop':
        try:
            sys.exit(stop(event))
        except Exception:   # never fails the session
            sys.exit(0)
    sys.exit({'prompt': prompt, 'act': act}.get(mode, lambda _: 0)(event))
