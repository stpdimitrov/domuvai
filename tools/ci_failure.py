#!/usr/bin/env python3
"""T-05 — why is this PR red? Only the failing check, its failing step and the error lines; never a raw log.

    python3 tools/ci_failure.py <pr> [--sha <commit>]

REST only (GraphQL is blocked from Claude Code sessions). The job log redirects to Azure blob storage, which the
session proxy refuses; then the check run's annotations and output are read instead — tools/gates.sh writes each
failing gate's error lines there as an annotation — and the script says the log was unreachable.
"""
import json, re, subprocess, sys

REPO = 'repos/{owner}/{repo}'   # gh fills these from the checkout's remote
ERR = re.compile(r'FAILED|✗|[Ee]rror|What went wrong|Traceback|AssertionError|expected:|NO RAW')
NOISE = re.compile(r'Node\.js 20|deprecated|ubuntu-latest label|^\s+at ')


def api(path: str, raw: bool = False):
    p = subprocess.run(['gh', 'api', path], capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(p.stderr.strip().splitlines()[-1] if p.stderr.strip() else 'gh api failed')
    return p.stdout if raw else json.loads(p.stdout)


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__); return 2
    sha = args[args.index('--sha') + 1] if '--sha' in args else api(f'{REPO}/pulls/{args[0]}')['head']['sha']
    runs = api(f'{REPO}/commits/{sha}/check-runs?per_page=100')['check_runs']
    failed = [r for r in runs if r['conclusion'] in ('failure', 'timed_out', 'cancelled', 'action_required')]
    pending = [r['name'] for r in runs if r['status'] != 'completed']
    if not failed:
        print(f'no failing check on {sha[:7]}' + (f' — still running: {", ".join(pending)}' if pending else ''))
        return 0
    for r in failed:
        print(f"✗ {r['name']} ({r['conclusion']}) on {sha[:7]} — {r['html_url']}")
        try:
            steps = [s['name'] for s in api(f"{REPO}/actions/jobs/{r['id']}")['steps'] if s['conclusion'] == 'failure']
            print('  step: ' + (', '.join(steps) or '?'))
        except RuntimeError:
            pass
        try:
            lines = [l for l in api(f"{REPO}/actions/jobs/{r['id']}/logs", raw=True).splitlines()
                     if ERR.search(l) and not NOISE.search(l)]
            if lines:
                print('\n'.join('  ' + re.sub(r'^\S+Z ', '', l) for l in lines[:30]))
                continue
            print('  (no error line matched in the job log; reading annotations)')
        except RuntimeError as e:
            print(f'  (job log unreachable from here — {str(e)[:60]}…; reading annotations instead)')
        notes = [a for a in api(f"{REPO}/check-runs/{r['id']}/annotations")
                 if a['annotation_level'] == 'failure' and not NOISE.search(a['message'])]
        for a in notes:
            print(f"  {a.get('title') or a['path']}: " + a['message'].replace('\n', '\n    '))
        out = r.get('output') or {}
        for k in ('summary', 'text'):
            if out.get(k):
                print('  ' + '\n  '.join(l for l in out[k].splitlines() if ERR.search(l))[:2000])
        if all(a['message'].startswith('Process completed with exit code') for a in notes):
            print('  no error lines reachable: this run predates the gate annotations (T-05); see the URL above')
    return 1


if __name__ == '__main__':
    sys.exit(main())
