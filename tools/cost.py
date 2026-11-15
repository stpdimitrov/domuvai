#!/usr/bin/env python3
"""T-08 — a session's measured cost, for the session log's Cost line.

    python3 tools/cost.py [session_id]     default: the session the Stop hook recorded last

Reads .claude/state/<session_id>.json (the size gate's Stop hook writes it at the end of every turn) and measures the
transcript again, so the number is current. Prints the Cost line's figures: transcript bytes · turns.
"""
import json
import os
import sys
from pathlib import Path

STATE = Path(os.environ.get('ZUES_STATE_DIR') or Path(__file__).resolve().parent.parent / '.claude/state')


def main(argv: list[str]) -> int:
    files = [STATE / f'{argv[0]}.json'] if argv else sorted(STATE.glob('*.json'), key=lambda p: p.stat().st_mtime)
    if not files or not files[-1].is_file():
        print(f'no cost record in {STATE} — the Stop hook writes one at the end of every turn', file=sys.stderr)
        return 1
    path = files[-1]
    state = json.loads(path.read_text())
    try:
        size = os.path.getsize(state.get('transcript', ''))
    except OSError:
        size = state.get('bytes', 0)
    print(f'transcript {size // 1000}k bytes · {state.get("turns", 0)} turns   (session {path.stem})')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
