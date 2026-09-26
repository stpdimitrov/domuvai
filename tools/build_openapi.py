#!/usr/bin/env python3
"""A9, rebuilt by ADR-013 — the HTTP contract for the `api` deployable.

The spec is generated from the running code: `OpenApiContractTest` (gate 1/9) asks springdoc
for it and writes app/build/openapi/api-docs.json. This script (gate 6/9) publishes it as
docs/api/openapi.json and adds the one thing the code cannot say — which rules each operation
serves. The build fails when:

- a running operation is not catalogued below (an endpoint that serves no rule);
- a catalogued operation no longer runs (a stale entry fails like a missing one);
- a cited rule ID is absent from rules.json.

Operations designed in A9 but not built yet stay in PLANNED — the design intent, never
published as if it ran. The spec states what runs, nothing more.
"""
import json, sys, pathlib
from openapi_spec_validator import validate

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = ROOT / 'app/build/openapi/api-docs.json'
OUT = ROOT / 'docs/api/openapi.json'
RULES = json.loads((ROOT/'docs/rules.json').read_text())
KNOWN = {r['id'] for r in RULES['rules']}

# (method, path exactly as the code serves it) → (summary, the rules the operation serves)
RUNNING = {
 # ---- registry
 ('post', '/api/registry/entrances'):
   ('Register an entrance — the unit of ownership and isolation', ['PM-ORG-001']),
 ('get', '/api/registry/entrances'):
   ('List the registered entrances', ['PM-ORG-001']),
 ('post', '/api/registry/entrances/{entranceId}/units'):
   ('Add units with their ideal parts', ['PM-ORG-002', 'PM-ORG-009']),
 ('get', '/api/registry/entrances/{entranceId}/units'):
   ('List the units of an entrance', ['PM-ORG-001', 'PM-ORG-002']),
 ('post', '/api/registry/entrances/{entranceId}/units/{unitId}/household'):
   ("Register a unit's household — who counts for per-person charges", ['PM-FEE-005', 'PM-FEE-008']),
 ('post', '/api/registry/entrances/{entranceId}/units/{unitId}/animals'):
   ('Record the animals kept in a unit', ['PM-BOOK-005', 'PM-FEE-009']),
 ('post', '/api/registry/entrances/{entranceId}/units/{unitId}/absences'):
   ('File an absence declaration — the long-absence exemption', ['PM-FEE-006', 'PM-FEE-007']),
 ('get', '/api/registry/entrances/{entranceId}/book'):
   ('Read the Book of the Condominium as of a date', ['PM-BOOK-001', 'PM-BOOK-002']),
 ('post', '/api/registry/parties'):
   ('Register a party — a person or company that can hold a title', ['PM-BOOK-002']),
 ('post', '/api/registry/entrances/{entranceId}/units/{unitId}/titles'):
   ('Record a title to a unit — effective-dated, with its share', ['PM-ORG-005', 'PM-ORG-011']),
 ('get', '/api/registry/entrances/{entranceId}/owners'):
   ("The owners and users of an entrance's units as of a date — names only", ['PM-ORG-011', 'PM-BOOK-011']),
 # ---- money
 ('post', '/api/money/charge-runs/preview'):
   ('Compute a charge run from a stated basis — nothing is stored',
    ['PM-FEE-001', 'PM-FEE-010', 'PM-FEE-012', 'PM-ORG-002']),
 ('post', '/api/money/entrances/{entranceId}/charge-runs/preview'):
   ("Compute a charge run from the entrance's registered units — nothing is stored", ['PM-FEE-001', 'PM-FEE-014']),
 ('post', '/api/money/entrances/{entranceId}/charge-runs'):
   ('Issue a charge run — stored with its basis, never altered afterwards',
    ['PM-FEE-012', 'PM-FEE-014', 'PM-FEE-015', 'PM-FEE-016']),
 ('get', '/api/money/units/{unitId}/statement'):
   ("The unit's itemised statement, showing how each number was derived", ['PM-FEE-018']),
 ('get', '/api/money/units/{unitId}/arrears'):
   ("The unit's arrears, aged as of a date", ['PM-DEBT-001', 'PM-DEBT-002']),
 ('post', '/api/money/entrances/{entranceId}/fund-accounts'):
   ("Register the entrance's repair-fund account", ['PM-FUND-001', 'PM-FUND-004', 'PM-FUND-005']),
 ('get', '/api/money/entrances/{entranceId}/fund-accounts'):
   ("List the entrance's fund accounts", ['PM-FUND-001', 'PM-FUND-004']),
 # ---- intake
 ('post', '/api/intake/entrances/{entranceId}/fee-sheet/profile'):
   ("Profile a sheet's columns — propose a mapping onto the domain fields, to confirm",
    ['PM-ORG-002', 'PM-BOOK-002']),
 ('post', '/api/intake/entrances/{entranceId}/fee-sheet/dry-run'):
   ("Dry-run a fee sheet — recompute every bill and compare it with the sheet's own figures",
    ['PM-ORG-002', 'PM-FEE-014']),
 ('post', '/api/intake/entrances/{entranceId}/imports'):
   ('Record a source-file import: run the dry-run and store its verdict and content hash', ['PM-DOC-001']),
 ('get', '/api/intake/imports/{id}'):
   ('Read an import record — its verdict and content hash', ['PM-DOC-001']),
 ('post', '/api/intake/imports/{id}/commit'):
   ('Commit a reviewed import into the registry — only the exact file that was reviewed',
    ['PM-ORG-001', 'PM-ORG-002', 'PM-DOC-001']),
 ('post', '/api/intake/imports/{id}/revert'):
   ('Revert a committed import wholesale, with its reason', ['PM-DOC-001']),
}

# Designed in A9, not built yet — method, path as designed, module, summary, rules. Never published.
PLANNED = [
 ('post', '/entrances/{entrance_id}/book/declarations', 'registry',
  'File an owner declaration on the ministry template', ['PM-BOOK-003', 'PM-BOOK-004', 'PM-BOOK-009']),
 ('get', '/entrances/{entrance_id}/mandates', 'identity-org',
  'List office holders as at a date', ['PM-GOV-004', 'PM-SEC-011']),
 ('post', '/entrances/{entrance_id}/mandates', 'identity-org',
  'Record an election', ['PM-GOV-003', 'PM-GOV-004', 'PM-GOV-005']),
 ('post', '/entrances/{entrance_id}/assemblies', 'assembly',
  'Convene an assembly', ['PM-GA-001', 'PM-GA-002', 'PM-GA-003', 'PM-GA-006']),
 ('post', '/assemblies/{assembly_id}/notice', 'assembly',
  'Generate the notice and record the posting act with its evidence',
  ['PM-GA-004', 'PM-GA-007', 'PM-GA-008', 'PM-SYS-013']),
 ('post', '/assemblies/{assembly_id}/sessions', 'assembly',
  'Open a session and record the quorum reached', ['PM-GA-012', 'PM-GA-013', 'PM-GA-014', 'PM-GA-015']),
 ('post', '/agenda-items/{agenda_item_id}/votes', 'assembly',
  'Cast or record a vote', ['PM-GA-009', 'PM-GA-018', 'PM-VOTE-014', 'PM-ORG-005']),
 ('post', '/agenda-items/{agenda_item_id}/close', 'assembly',
  'Close the item and compute the tally on its stated denominator',
  ['PM-VOTE-011', 'PM-VOTE-012', 'PM-VOTE-013', 'PM-VOTE-016']),
 ('post', '/assemblies/{assembly_id}/protocol', 'assembly',
  'Draw up the protocol and announce it, starting the appeal clock',
  ['PM-GA-019', 'PM-GA-020', 'PM-GA-021', 'PM-GA-023']),
 ('get', '/charge-runs/{charge_run_id}', 'money',
  'Read a charge run and its basis', ['PM-FEE-014', 'PM-FEE-018']),
 ('get', '/entrances/{entrance_id}/fund', 'money',
  'Repair and renewal fund, net of committed but unpaid work', ['PM-FUND-004', 'PM-FUND-009']),
 ('post', '/units/{unit_id}/obligations-certificate', 'money',
  'Issue a dated certificate of outstanding obligations, for a sale', ['PM-DEBT-007']),
 ('post', '/entrances/{entrance_id}/arrears/{unit_id}/enforcement-packet', 'money',
  'Compose the чл. 410 packet: decision, proof of announcement, itemised claim',
  ['PM-DEBT-003', 'PM-DEBT-004', 'PM-DEBT-009']),
 ('get', '/entrances/{entrance_id}/compliance-tasks', 'compliance',
  'Statutory obligations due and overdue', ['PM-MNT-006', 'PM-SYS-011']),
 ('post', '/entrances/{entrance_id}/filings', 'compliance',
  'File with the municipality or ЕИСЕС; open until acknowledged', ['PM-REG-001', 'PM-REG-004', 'PM-REG-008']),
 ('get', '/documents/{document_id}', 'evidence',
  'Read a stored document and its hash', ['PM-DOC-001', 'PM-DOC-005']),
 ('post', '/matters/{matter_id}/case-file', 'evidence',
  'Compose a court-ready bundle in order, with an index', ['PM-DOC-006']),
]

TAGS = {
 'registry': 'Entrances, units, households, ownership, the Book of the Condominium. Owns ORG and BOOK.',
 'money': 'Charges, the fund, arrears. Owns FEE, FUND and DEBT.',
 'intake': "Importing a firm's existing records. Owns no rules — it enforces ORG and BOOK on the way in.",
}


def publish(raw):
    problems = []
    running = {(m, p): op for p, ops in raw['paths'].items() for m, op in ops.items()}
    for m, p in sorted(running):
        if len(p.split('/')) < 4 or p.split('/')[1] != 'api':
            problems.append(f'off-contract path: {m.upper()} {p} — every endpoint lives under /api/<module>/ (ADR-013 §2.4)')
    for key in sorted(running.keys() - RUNNING.keys()):
        problems.append(f'uncatalogued: {key[0].upper()} {key[1]} — add it to RUNNING with the rules it serves')
    for key in sorted(RUNNING.keys() - running.keys()):
        problems.append(f'stale: {key[0].upper()} {key[1]} is catalogued but does not run — remove it or move it to PLANNED')
    cited = [(f'{m.upper()} {p}', rules) for (m, p), (_, rules) in RUNNING.items()]
    cited += [(f'{m.upper()} {p}', rules) for m, p, _, _, rules in PLANNED]
    for op, rules in cited:
        if not rules: problems.append(f'no rules: {op} — every operation serves at least one rule')
        problems += [f'unknown rule: {op} cites {r}, absent from rules.json' for r in rules if r not in KNOWN]
    if problems: return None, problems

    for (method, path), op in running.items():
        summary, rules = RUNNING[(method, path)]
        module = path.split('/')[2]
        op['tags'] = [module]
        op['summary'] = summary
        op['x-rules'] = rules
        op['operationId'] = (method + path.removeprefix('/api').replace('/', '_')
                             .replace('{', '').replace('}', '').replace('-', '_'))
    raw.pop('servers', None)
    raw['info'] = {
      'title': 'domuvai api',
      'version': '0.1.0',
      'summary': 'Управление на етажна собственост по ЗУЕС',
      'description':
        'Generated from the running code (ADR-013): it states what runs, nothing more. Each '
        'operation names the rules it serves in `x-rules`. Operations designed but not built yet '
        'are listed in `tools/build_openapi.py` (PLANNED), not here.',
    }
    used = sorted({op['tags'][0] for op in running.values()})
    raw['tags'] = [{'name': t, 'description': TAGS.get(t, '')} for t in used]
    return raw, []


def main():
    if not RAW.exists():
        print(f'NO RAW SPEC — {RAW} is written by OpenApiContractTest; run ./gradlew test (gate 1/9)')
        return 1
    spec, problems = publish(json.loads(RAW.read_text()))
    if problems:
        print('CONTRACT FAILURE — the catalogue and the running code disagree:')
        for p in problems: print(f'  {p}')
        return 1
    validate(spec)                       # fails loudly if the spec is not valid OpenAPI 3.1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(spec, indent=2, ensure_ascii=False, sort_keys=True) + '\n')
    rules = {r for _, rs in RUNNING.values() for r in rs}
    print(f'OK  {len(RUNNING)} operations running · {len(spec["tags"])} modules · {len(rules)} rules cited · '
          f'{len(PLANNED)} planned · generated from the code (ADR-013) · valid OpenAPI 3.1')
    return 0

if __name__ == '__main__':
    sys.exit(main())
