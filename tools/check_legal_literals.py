#!/usr/bin/env python3
"""No legal threshold outside @zues/law (ADR-001, PM-SYS-001).

Precision matters more than reach here: a check with false positives is a check
somebody switches off. So this does not flag every number that happens to match
a statutory value — `/100` for cents and `repeat(50)` for a rule are not legal
decisions. It flags a watchlist number used as a **threshold**: on either side
of a comparison, or as a multiplier applied to a variable.

The watchlist is generated from the rule texts, never typed.
Escape a deliberate use with `// not-legal: <why>`, on the line or just above it.
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

if bad:
    print(f"LEGAL THRESHOLD OUTSIDE @zues/law — {len(bad)}:")
    for f, i, v, how, src in bad:
        print(f"  {f}:{i}  {v} {how}  ·  {src}")
    print("\nEvery statutory number lives in @zues/law, resolved at the legal date.")
    print("If the number is not legal, mark the line `// not-legal: <why>`.")
    sys.exit(1)
print(f"OK  no statutory threshold outside @zues/law  ·  {len(watch)} watched value(s), generated from the rules")
