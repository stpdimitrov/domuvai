#!/usr/bin/env python3
"""Who is working where — read live from GitHub, never typed into a document.

A lane is one module (ADR-003) or the `web` deployable: the unit two developers must not work in
at once. Lanes are claimed, not assigned. A lane is TAKEN while an open issue labelled
`lane:<lane>` has an assignee — the slice issue a developer opens before branching, its body the
slice contract — and FREE otherwise; the slice's PR closes the issue on merge (`Closes #n`) and
frees the lane. An open `lane:<lane>` issue with no assignee is a finding waiting in that lane,
not a claim. So any number of sessions boot, read this map and pick free work; nobody keeps a
list of names up to date.

    python3 tools/lanes.py                    the map: taken lanes, free lanes and their next slice
    python3 tools/lanes.py check <lane>       exit 1 if someone else holds <lane>
    python3 tools/lanes.py claim <lane> --title "S-nn …" --body-file contract.md

`git config zues.lane <lane>` marks a preferred lane: it is listed first, never reserved.
Needs `gh`, authenticated for this repository. Not a gate — it reads the network.
"""
from __future__ import annotations

import argparse, json, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
from testplan import MODULE   # domain → owning module: the one home of the module map

LABEL = 'lane:'
APP = ROOT / 'app/src/main/kotlin/zues/app'
# Every module that owns rules, every module with code (intake owns none), and the web deployable.
LANES = sorted(set(MODULE.values()) | {p.name.replace('_', '-') for p in APP.iterdir() if p.is_dir()} | {'web'})
# Top-level directories that are a lane of their own. `charges` is the fee engine the money
# module runs (FEE rules), so it is money's lane.
DIR_LANE = {'charges': 'money', 'kernel': 'kernel', 'law': 'law', 'web': 'web'}
APP_PATH = re.compile(r'^app/src/(?:main|test)/kotlin/zues/app/([a-z_]+)/')
# Shared by every lane: the web client is generated from the contract, so any lane that changes an
# endpoint regenerates it (ADR-011 §2.2); and every legal number lives in one coordinated file
# (ADR-001, WORKING.md), so a slice adding a dated constant is not working in the `law` lane.
SHARED = {'web/lib/api/schema.d.ts', 'law/src/main/kotlin/zues/law/Constants.kt'}
SLICE = re.compile(r'^### (S-\S+) · `([\w-]+)` · (.+?) · (\d+) rule')


def gh(*args: str) -> str:
    """Run gh. A missing or unauthenticated gh is a clear stop, not a stack trace."""
    try:
        return subprocess.run(['gh', *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout
    except FileNotFoundError:
        sys.exit('gh is not installed — https://cli.github.com, then `gh auth login`')
    except subprocess.CalledProcessError as e:
        sys.exit(f'gh {" ".join(args[:2])} failed: {e.stderr.strip()}')


def lane_of(path: str) -> str | None:
    """The lane a changed file belongs to; None for shared files (docs, tools, CI, migrations)."""
    if path in SHARED:
        return None
    m = APP_PATH.match(path)
    return m.group(1).replace('_', '-') if m else DIR_LANE.get(path.split('/')[0])


def next_slices() -> dict[str, str]:
    """Each lane's next slice — the first one docs/TESTPLAN.md lists for it, in gate order."""
    out: dict[str, str] = {}
    for line in (ROOT / 'docs/TESTPLAN.md').read_text().splitlines():
        if m := SLICE.match(line):
            out.setdefault(m.group(2), f'{m.group(1)} · {m.group(3)} · {m.group(4)} rules')
    return out


def lane_issues() -> dict[str, list[dict]]:
    """lane → its open labelled issues. One with an assignee is a claim; one without, a finding."""
    out: dict[str, list[dict]] = {}
    for issue in json.loads(gh('issue', 'list', '--state', 'open', '--limit', '500',
                               '--json', 'number,title,assignees,labels')):
        for label in issue['labels']:
            if label['name'].startswith(LABEL):
                out.setdefault(label['name'][len(LABEL):], []).append(issue)
    return out


def numbers(items) -> str:
    return ', '.join('#%d' % i['number'] for i in items)


def claims_of(issues: list[dict]) -> list[tuple[dict, list[str]]]:
    """The claims among a lane's issues, oldest first, with who holds each."""
    held = [(i, [a['login'] for a in i['assignees']]) for i in issues if i['assignees']]
    return sorted(held, key=lambda c: c[0]['number'])


def me() -> str:
    return gh('api', 'user', '-q', '.login').strip()


def show() -> int:
    issues = lane_issues()
    touched: dict[str, list[dict]] = {}
    for pr in json.loads(gh('pr', 'list', '--state', 'open', '--limit', '100', '--json', 'number,author,files')):
        for lane in {lane_of(f['path']) for f in pr['files']} - {None}:
            touched.setdefault(lane, []).append(pr)
    nexts = next_slices()
    preferred = subprocess.run(['git', 'config', '--get', 'zues.lane'], cwd=ROOT,
                               capture_output=True, text=True).stdout.strip()

    rows, warnings = [], []
    for lane in LANES:
        claimed = claims_of(issues.get(lane, []))
        findings = len(issues.get(lane, [])) - len(claimed)
        holders = {who for _, whos in claimed for who in whos}
        prs = touched.get(lane, [])
        extra = (' · PR ' + numbers(prs) if prs else '') + (f' · {findings} finding(s)' if findings else '')
        if claimed:
            issue, whos = claimed[0]
            rows.append((0, 0, lane, 'TAKEN', ', '.join(whos), f'#{issue["number"]} {issue["title"]}{extra}'))
            if len(claimed) > 1:
                warnings.append(f'{lane} is claimed more than once ({numbers(c[0] for c in claimed)})'
                                ' — the lowest number holds it; close the others')
        else:
            rank = list(nexts).index(lane) if lane in nexts else len(nexts)
            detail = f'next: {nexts[lane]}' if lane in nexts else 'next: — (no TESTPLAN slice)'
            rows.append((1 if lane != preferred else 0.5, rank, lane, 'free', '', detail + extra))
        for pr in prs:
            author = pr['author']['login']
            if not claimed:
                warnings.append(f'PR #{pr["number"]} ({author}) touches {lane} with no {LABEL}{lane} claim')
            elif author not in holders:
                warnings.append(f'PR #{pr["number"]} ({author}) touches {lane}, which {", ".join(sorted(holders))} holds')

    width = max(len(lane) for lane in LANES) + 2
    print(f'  {"lane".ljust(width)}{"status":8}{"holder":24}claim / next')
    for _, _, lane, status, holder, detail in sorted(rows):
        mark = '★' if lane == preferred else ' '
        print(f'{mark} {lane.ljust(width)}{status:8}{holder:24}{detail}')
    for w in warnings:
        print(f'⚠ {w}')
    return 0


def check(lane: str, login: str) -> int:
    """0 if the lane is free or already yours; 1, naming the holder, if someone else holds it."""
    held = claims_of(lane_issues().get(lane, []))
    others = [(i, whos) for i, whos in held if login not in whos]
    if others:
        issue, whos = others[0]
        print(f'{lane} is held by {", ".join(whos)} — #{issue["number"]} {issue["title"]}. Pick a free lane.')
        return 1
    print(f'{lane} is {"yours" if held else "free"}')
    return 0


def claim(lane: str, title: str, body_file: str) -> int:
    """Open the slice issue that claims the lane, assigned to you, then settle any race."""
    login = me()
    if check(lane, login):
        return 1
    gh('label', 'create', LABEL + lane, '--force', '--color', '1d76db',
       '--description', f'the {lane} lane — held by an assigned open issue (tools/lanes.py)')
    url = gh('issue', 'create', '--title', title, '--label', LABEL + lane, '--assignee', '@me',
             '--body-file', body_file).strip()
    number = int(url.rstrip('/').rsplit('/', 1)[1])
    # Two sessions can claim one free lane in the same moment: the lower issue number wins.
    rivals = [i for i, whos in claims_of(lane_issues().get(lane, [])) if login not in whos and i['number'] < number]
    if rivals:
        print(f'{url}\nlost the race: #{rivals[0]["number"]} claimed {lane} first — close yours: gh issue close {number}')
        return 1
    print(f'{url}\n{lane} is yours — branch: git fetch && git switch -c slice/S-nn-<name> origin/main')
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description='Who is working where — lanes claimed live on GitHub.')
    sub = parser.add_subparsers(dest='command')
    sub.add_parser('check').add_argument('lane', choices=LANES)
    claiming = sub.add_parser('claim')
    claiming.add_argument('lane', choices=LANES)
    claiming.add_argument('--title', required=True, help='S-nn <slice name> (<lane> · <rule ids>)')
    claiming.add_argument('--body-file', required=True, help='the slice contract, in the CLAUDE.md shape')
    args = parser.parse_args()
    if args.command == 'check':
        return check(args.lane, me())
    if args.command == 'claim':
        return claim(args.lane, args.title, args.body_file)
    return show()


if __name__ == '__main__':
    sys.exit(main())
