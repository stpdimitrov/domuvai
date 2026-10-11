#!/usr/bin/env python3
"""No legal threshold outside @zues/law (ADR-001, PM-SYS-001).

Precision matters more than reach here: a check with false positives is a check
somebody switches off. So this does not flag every number that happens to match
a statutory value — `/100` for cents and `repeat(50)` for a rule are not legal
decisions. It flags a watchlist number used as a **threshold**: on either side
of a comparison, or as a multiplier applied to a variable.

The watchlist is generated from the rule texts, never typed.
Escape a deliberate use with `// not-legal: <why>`, on the line or just above it.

Migrations are read too (AUD-01): a duration written into the schema — `INTERVAL '2 years'`,
`make_interval(...)` — is a legal number the law module cannot date, whatever its size. Each one
fails this gate unless the line, or the one above it, says `-- not-legal: <why>`, or a LATER
migration drops the constraint that carries it. An applied migration is never edited, so the one
such interval the baseline has is answered the second way, and the gate checks that the drop is
really there — remove that migration and this fails.
"""
import json, re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
rules = json.loads((ROOT/'docs/rules.json').read_text())['rules']

watch = set()
for r in rules:
    t = r['rule']
    watch |= {int(x) for x in re.findall(r'(\d{1,3})\s*%', t)}
    watch |= {int(x) for x in re.findall(r'(\d{1,4})\s*(?:days?|hours?|years?|months?)', t)}
    watch |= {int(x) for x in re.findall(r'(\d{1,2})\s*to\s*\d{1,2}\s*times', t)}
watch -= {0, 1, 2}

LAW = ROOT/'law'
SRC = [p for p in ROOT.rglob('*.kt')
       if 'build' not in p.parts and 'node_modules' not in p.parts
       and '.claude' not in p.relative_to(ROOT).parts   # other sessions' worktrees are not this checkout
       and LAW not in p.parents and 'test' not in p.parts]   # a test may name the value it asserts

N = r'(\d{1,4})'
PATTERNS = [
    (re.compile(rf'(?:[<>]=?|===|!==|==)\s*{N}(?![\w.])'), 'compared against'),
    (re.compile(rf'(?<![\w.]){N}\s*(?:[<>]=?|===|!==|==)'), 'compared against'),
    (re.compile(rf'\*\s*{N}(?![\w.])'), 'used as a multiplier'),
]

def strip(line: str) -> str:
    line = line.split('//')[0]
    line = re.sub(r'/\*.*?\*/', '', line)
    line = re.sub(r'`[^`]*`|"[^"]*"|\'[^\']*\'', '""', line)   # strings
    line = re.sub(r'/\^?[^/\n]+/[gimsuy]*', '', line)          # regex literals
    return line

bad = []
for p in SRC:
    lines = p.read_text().splitlines()
    for i, line in enumerate(lines, 1):
        prev = lines[i-2] if i >= 2 else ''
        if 'not-legal:' in line or 'not-legal:' in prev: continue   # marker on the line or just above it
        code = strip(line)
        for rx, how in PATTERNS:
            for m in rx.finditer(code):
                v = int(m.group(1))
                if v in watch:
                    bad.append((p.relative_to(ROOT), i, v, how, line.strip()[:76]))

# ---- migrations: a duration in the schema -----------------------------------------------------------------------
MIGRATION_DIR = ROOT/'app/src/main/resources/db/migration'
def _version(p):
    m = re.match(r'[VR](\d*)__', p.name)
    return (0, int(m.group(1))) if m and m.group(1) else (1, 0)      # a repeatable or oddly named file sorts last, and is read
MIGRATIONS = sorted([*MIGRATION_DIR.glob('V*.sql'), *MIGRATION_DIR.glob('R*.sql')], key=lambda p: (_version(p), p.name))

# A duration, however SQL spells it: INTERVAL '2 years' · interval '2' year · '2 years'::interval · CAST('…' AS interval) ·
# make_interval(…) · justify_interval and friends are not ways to write one. Case and line breaks do not hide it.
DURATION = re.compile(r"\binterval\s*'[^']*'|'[^']*'\s*::\s*interval\b|\bAS\s+interval\b|\bmake_interval\s*\(", re.I)

# A duration an applied migration carries, and the constraint a later migration must drop for it to stop counting.
# Keyed by file and the text of the line, so an edit to either is seen. Checked below, never trusted.
SUPERSEDED = {
    ('V1__init.sql', "CHECK (valid_to <= valid_from + INTERVAL '2 years')"): 'management_mandate_check1',
}

def uncommented(sql: str) -> str:
    """The text with comments blanked, line breaks and offsets kept — a `--` inside a quoted string is not a comment."""
    out, i, n = list(sql), 0, len(sql)
    while i < n:
        if sql[i] == "'":
            i = sql.find("'", i + 1); i = n if i < 0 else i + 1
        elif sql.startswith('--', i):
            j = sql.find('\n', i); j = n if j < 0 else j
            out[i:j] = ' ' * (j - i); i = j
        elif sql.startswith('/*', i):
            j = sql.find('*/', i); j = n if j < 0 else j + 2
            out[i:j] = [c if c == '\n' else ' ' for c in sql[i:j]]; i = j
        else:
            i += 1
    return ''.join(out)

def dropped_later(constraint: str, after: pathlib.Path) -> bool:
    later = MIGRATIONS[MIGRATIONS.index(after) + 1:]
    return any(re.search(rf'DROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?{re.escape(constraint)}\b', uncommented(p.read_text()), re.I) for p in later)

for p in MIGRATIONS:
    raw = p.read_text()
    lines, code = raw.splitlines(), uncommented(raw)
    for m in DURATION.finditer(code):
        i = code.count('\n', 0, m.start()) + 1
        line, prev = lines[i-1], lines[i-2] if i >= 2 else ''
        if 'not-legal:' in line or 'not-legal:' in prev: continue
        constraint = SUPERSEDED.get((p.name, code.splitlines()[i-1].strip()))
        if constraint and dropped_later(constraint, p): continue
        how = (f'a duration in the schema — its constraint {constraint} is not dropped by any later migration' if constraint
               else 'a duration in the schema')
        number = re.search(r'\d+', m.group(0))
        bad.append((p.relative_to(ROOT), i, number.group(0) if number else '?', how, line.strip()[:76]))

if bad:
    print(f"LEGAL THRESHOLD OUTSIDE @zues/law — {len(bad)}:")
    for f, i, v, how, src in bad:
        print(f"  {f}:{i}  {v} {how}  ·  {src}")
    print("\nEvery statutory number lives in @zues/law, resolved at the legal date.")
    print("If the number is not legal, mark the line `// not-legal: <why>`.")
    sys.exit(1)
print(f"OK  no statutory threshold outside @zues/law  ·  {len(watch)} watched value(s), generated from the rules  ·  "
      f"{len(MIGRATIONS)} migration(s) read for durations")
