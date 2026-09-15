#!/usr/bin/env python3
"""Banned words in identifiers. `tenant` means two things — the isolation unit
and the person renting — and that collision found in month four is a schema
migration. Escape a deliberate use with a trailing `// allow-word` comment."""
import re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
BANNED = {
  'tenant':   'use Entrance for the isolation unit, Occupant for the person',
  'building': 'the tenant key is the entrance, not the building (ADR-005)',
}
# `fee` and `balance` are banned only as stored column names
COLUMNISH = {'fee': 'use charge', 'balance': 'derive it from postings (ADR-006)'}
IDENT = re.compile(r'\b(?:val|var|fun|class|object|interface|enum|typealias)\s+(\w+)|(\w+)\s*:')
SRC = [p for p in ROOT.rglob('*.kt') if 'build' not in p.parts and 'node_modules' not in p.parts]

bad = []
for p in SRC:
    for i, line in enumerate(p.read_text().splitlines(), 1):
        if 'allow-word' in line: continue
        code = line.split('//')[0]
        for m in IDENT.finditer(code):
            name = (m.group(1) or m.group(2) or '')
            low = re.sub(r'[^a-z]', '', name.lower())
            for w, why in BANNED.items():
                if w in low: bad.append((p.relative_to(ROOT), i, name, w, why))
            for w, why in COLUMNISH.items():
                if low == w: bad.append((p.relative_to(ROOT), i, name, w, why))
if bad:
    print(f"BANNED WORDS — {len(bad)} identifier(s):")
    for f, i, n, w, why in bad: print(f"  {f}:{i}  `{n}` contains `{w}` — {why}")
    sys.exit(1)
print(f"OK  no banned word in {len(SRC)} source file(s)")
