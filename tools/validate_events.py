#!/usr/bin/env python3
"""CI gate — validates every generated example against the envelope and its event schema.
A schema nobody validates is a comment."""
import json, pathlib, sys
from jsonschema import Draft202012Validator

EV = pathlib.Path('../docs/events')
env = json.loads((EV/'envelope.schema.json').read_text())
env_v = Draft202012Validator(env)

fails, n = [], 0
for ex_path in sorted(EV.glob('*.example.json')):
    name = ex_path.name.replace('.example.json','')
    ex = json.loads(ex_path.read_text())
    for e in env_v.iter_errors(ex):
        fails.append(f"{name} envelope: {e.json_path} {e.message}")
    sch = json.loads((EV/f'{name}.schema.json').read_text())
    for e in Draft202012Validator(sch).iter_errors(ex['payload']):
        fails.append(f"{name} payload: {e.json_path} {e.message}")
    if ex['type'] != name:
        fails.append(f"{name}: envelope type is '{ex['type']}'")
    n += 1

if fails:
    print(f"VALIDATION FAILED — {len(fails)} problem(s):")
    for f in fails[:20]: print("  -", f)
    sys.exit(1)
print(f"OK  {n} events validated against envelope + payload schema")
