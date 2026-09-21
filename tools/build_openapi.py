#!/usr/bin/env python3
"""A9 — the HTTP contract for the `api` deployable.

One spec, tagged by module (ADR-003: one deployable, fourteen modules). Generated
from the catalogue below so the surface cannot drift from the decisions, and
validated against the OpenAPI 3.1 schema so it cannot be merely plausible.

Conventions that apply to every operation are declared once here rather than
repeated per path — a convention restated 60 times is a convention that drifts.
"""
import json, sys, pathlib
from openapi_spec_validator import validate

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/api/openapi.json'
RULES = json.loads((ROOT/'docs/rules.json').read_text())
KNOWN = {r['id'] for r in RULES['rules']}

# ---------------------------------------------------------------- components
SCHEMAS = {
 'Money': {'type':'object','additionalProperties':False,
   'required':['amount_minor','currency'],
   'properties':{'amount_minor':{'type':'integer',
     'description':'integer minor units — never a float (PM-FEE-016, ADR-006)'},
     'currency':{'const':'EUR'}}},
 'IdealParts': {'type':'string','pattern':r'^\d{1,3}\.\d{1,4}$',
   'description':'exact decimal percentage, four places per docs/RULES.md §4'},
 'LegalDate': {'type':'string','format':'date',
   'description':'Europe/Sofia calendar day (PM-SYS-004)'},
 'Uuid': {'type':'string','format':'uuid'},
 'Problem': {'type':'object','additionalProperties':False,
   'required':['type','title','status'],
   'description':'RFC 9457. A refusal names the rule that refused.',
   'properties':{'type':{'type':'string'},'title':{'type':'string'},
     'status':{'type':'integer'},'detail':{'type':'string'},
     'rule_id':{'type':'string','pattern':r'^PM-[A-Z]+-\d{3}$',
       'description':'the rule that refused, when a rule refused'}}},
 'Page': {'type':'object','required':['items'],
   'properties':{'items':{'type':'array','items':{}},
     'next_cursor':{'type':['string','null']}}},
 'Unit': {'type':'object','additionalProperties':False,
   'required':['id','designation','ideal_parts_pct'],
   'properties':{'id':{'$ref':'#/components/schemas/Uuid'},
     'designation':{'type':'string'},
     'ideal_parts_pct':{'$ref':'#/components/schemas/IdealParts'},
     'unit_type':{'type':'string'},'area_m2':{'type':'string'},
     'separate_entrance':{'type':'boolean'}}},
 'ChargeLine': {'type':'object','additionalProperties':False,
   'required':['unit_id','component','allocation_key','amount','derivation'],
   'properties':{'unit_id':{'$ref':'#/components/schemas/Uuid'},
     'component':{'enum':['MANAGEMENT','MAINTENANCE','REPAIR_FUND']},
     'allocation_key':{'enum':['PER_PERSON','BY_IDEAL_PARTS','PER_UNIT']},
     'amount':{'$ref':'#/components/schemas/Money'},
     'derivation':{'type':'string','description':'how the number was derived (PM-FEE-018)'}}},
 'ChargeRun': {'type':'object','additionalProperties':False,
   'required':['id','period','status','law_version','engine_version'],
   'properties':{'id':{'$ref':'#/components/schemas/Uuid'},
     'period':{'type':'string','pattern':r'^\d{4}-\d{2}$'},
     'status':{'enum':['PENDING','RUNNING','COMPLETE','FAILED']},
     'basis_hash':{'type':'string'},'law_version':{'type':'string'},
     'engine_version':{'type':'string'},
     'lines':{'type':'array','items':{'$ref':'#/components/schemas/ChargeLine'}}}},
 'Decision': {'type':'object','additionalProperties':False,
   'required':['id','majority_rule_id','denominator','status'],
   'properties':{'id':{'$ref':'#/components/schemas/Uuid'},
     'majority_rule_id':{'type':'string'},
     'denominator':{'enum':['TOTAL','REPRESENTED'],
       'description':'never implicit (PM-VOTE-012, ADR-008)'},
     'threshold_pct':{'$ref':'#/components/schemas/IdealParts'},
     'tally_for':{'$ref':'#/components/schemas/IdealParts'},
     'tally_against':{'$ref':'#/components/schemas/IdealParts'},
     'status':{'enum':['PROVISIONAL','FINAL','ANNULLED'],
       'description':'PROVISIONAL until the absentee window closes (PM-VOTE-013)'},
     'execution_due':{'$ref':'#/components/schemas/LegalDate'}}},
 'ImportDryRun': {'type':'object','additionalProperties':False,
   'required':['import_id','rows_created','rows_changed','violations','bill_comparison'],
   'properties':{'import_id':{'$ref':'#/components/schemas/Uuid'},
     'rows_created':{'type':'integer'},'rows_changed':{'type':'integer'},
     'violations':{'type':'array','items':{'$ref':'#/components/schemas/Problem'}},
     'bill_comparison':{'type':'object',
       'description':'their figures against ours, per unit — Gate 1, on every import',
       'properties':{'matched':{'type':'integer'},'differing':{'type':'integer'}}}}},
 'IntakeField': {'type':'string',
   'description':'a domain field a fee sheet can carry — the target of any column mapping (ADR-012), '
     'fixed by our rules (PM-BOOK-002, PM-ORG-002), never by one spreadsheet',
   'enum':['DESIGNATION','IDEAL_PARTS','OCCUPANTS','FEE_MINOR',
           'BUILT_AREA','OWNER_NAME','CHILDREN_UNDER_6','ANIMALS','ABSENT_DAYS','BUSINESS_USE']},
 'FeeSheetProfile': {'type':'object','additionalProperties':False,
   'required':['csv'],
   'properties':{'csv':{'type':'string',
     'description':'the sheet — only its header row is read, to propose a mapping'}}},
 'MappingProposal': {'type':'object','additionalProperties':False,
   'required':['mapping','unmapped_columns','missing_required'],
   'description':'a proposed column → field mapping for one sheet, plus what could not be placed '
     '(ADR-012): a human confirms or corrects it and returns it on the dry-run/commit. Nothing is '
     'dropped silently, and a required field no column carries is named so the import cannot proceed',
   'properties':{
     'mapping':{'type':'object','description':'source column, as written → domain field',
       'additionalProperties':{'$ref':'#/components/schemas/IntakeField'}},
     'unmapped_columns':{'type':'array','items':{'type':'string'},
       'description':'columns the profiler could not place — surfaced for a human, never dropped'},
     'missing_required':{'type':'array','items':{'$ref':'#/components/schemas/IntakeField'},
       'description':'required fields no column carries — mapped by hand before the import proceeds'}}},
}

# path, method, tag, summary, rules, [request schema], response schema
OPS = [
 # ---- registry
 ('/entrances/{entrance_id}/units','get','registry','List the units of an entrance',
  ['PM-ORG-001','PM-ORG-002'],None,'Page'),
 ('/entrances/{entrance_id}/units','post','registry','Add a unit',
  ['PM-ORG-002','PM-ORG-009'],'Unit','Unit'),
 ('/entrances/{entrance_id}/book','get','registry',
  'Read the owners\' book — filtered to what the caller may see',
  ['PM-BOOK-006','PM-BOOK-011','PM-SEC-002'],None,'Page'),
 ('/entrances/{entrance_id}/book/declarations','post','registry',
  'File an owner declaration on the ministry template',
  ['PM-BOOK-003','PM-BOOK-004','PM-BOOK-009'],None,'Problem'),
 # ---- identity-org
 ('/entrances/{entrance_id}/mandates','get','identity-org','List office holders as at a date',
  ['PM-GOV-004','PM-SEC-011'],None,'Page'),
 ('/entrances/{entrance_id}/mandates','post','identity-org','Record an election',
  ['PM-GOV-003','PM-GOV-004','PM-GOV-005'],None,'Problem'),
 # ---- assembly
 ('/entrances/{entrance_id}/assemblies','post','assembly','Convene an assembly',
  ['PM-GA-001','PM-GA-002','PM-GA-003','PM-GA-006'],None,'Problem'),
 ('/assemblies/{assembly_id}/notice','post','assembly',
  'Generate the notice and record the posting act with its evidence',
  ['PM-GA-004','PM-GA-007','PM-GA-008','PM-SYS-013'],None,'Problem'),
 ('/assemblies/{assembly_id}/sessions','post','assembly',
  'Open a session and record the quorum reached',
  ['PM-GA-012','PM-GA-013','PM-GA-014','PM-GA-015'],None,'Problem'),
 ('/agenda-items/{agenda_item_id}/votes','post','assembly','Cast or record a vote',
  ['PM-GA-009','PM-GA-018','PM-VOTE-014','PM-ORG-005'],None,'Problem'),
 ('/agenda-items/{agenda_item_id}/close','post','assembly',
  'Close the item and compute the tally on its stated denominator',
  ['PM-VOTE-011','PM-VOTE-012','PM-VOTE-013','PM-VOTE-016'],None,'Decision'),
 ('/assemblies/{assembly_id}/protocol','post','assembly',
  'Draw up the protocol and announce it, starting the appeal clock',
  ['PM-GA-019','PM-GA-020','PM-GA-021','PM-GA-023'],None,'Problem'),
 # ---- money
 ('/entrances/{entrance_id}/charge-runs','post','money',
  'Start a charge run for a period. Resumable; publishes nothing until COMPLETE',
  ['PM-FEE-012','PM-FEE-014','PM-FEE-016'],None,'ChargeRun'),
 ('/charge-runs/{charge_run_id}','get','money','Read a charge run and its basis',
  ['PM-FEE-014','PM-FEE-018'],None,'ChargeRun'),
 ('/units/{unit_id}/statement','get','money',
  'The resident\'s itemised statement, showing how each number was derived',
  ['PM-FEE-018','PM-SEC-002'],None,'ChargeRun'),
 ('/entrances/{entrance_id}/fund','get','money',
  'Repair and renewal fund, net of committed but unpaid work',
  ['PM-FUND-004','PM-FUND-009'],None,'Money'),
 ('/units/{unit_id}/obligations-certificate','post','money',
  'Issue a dated certificate of outstanding obligations, for a sale',
  ['PM-DEBT-007'],None,'Problem'),
 ('/entrances/{entrance_id}/arrears/{unit_id}/enforcement-packet','post','money',
  'Compose the чл. 410 packet: decision, proof of announcement, itemised claim',
  ['PM-DEBT-003','PM-DEBT-004','PM-DEBT-009'],None,'Problem'),
 # ---- intake
 ('/entrances/{entrance_id}/fee-sheet/profile','post','intake',
  'Profile a sheet\'s columns — propose a mapping onto the domain fields, to confirm',
  ['PM-ORG-002','PM-BOOK-002'],'FeeSheetProfile','MappingProposal'),
 ('/entrances/{entrance_id}/imports','post','intake',
  'Record a source-file import: run the dry-run and store its verdict',
  ['PM-DOC-001'],None,'ImportDryRun'),
 ('/imports/{import_id}/dry-run','post','intake',
  'Validate, diff, and recompute a sample bill against their own figures',
  ['PM-ORG-002','PM-FEE-014'],None,'ImportDryRun'),
 ('/imports/{import_id}/commit','post','intake','Commit the import in one transaction',
  ['PM-BOOK-007'],None,'Problem'),
 ('/imports/{import_id}/revert','post','intake','Revert an import wholesale by its id',
  ['PM-BOOK-007'],None,'Problem'),
 # ---- compliance
 ('/entrances/{entrance_id}/compliance-tasks','get','compliance',
  'Statutory obligations due and overdue',
  ['PM-MNT-006','PM-SYS-011'],None,'Page'),
 ('/entrances/{entrance_id}/filings','post','compliance',
  'File with the municipality or ЕИСЕС; open until acknowledged',
  ['PM-REG-001','PM-REG-004','PM-REG-008'],None,'Problem'),
 # ---- evidence
 ('/documents/{document_id}','get','evidence','Read a stored document and its hash',
  ['PM-DOC-001','PM-DOC-005'],None,'Problem'),
 ('/matters/{matter_id}/case-file','post','evidence',
  'Compose a court-ready bundle in order, with an index',
  ['PM-DOC-006'],None,'Problem'),
]

TAGS = {
 'registry':'Units, the owners\' book, occupancy. Owns ORG and BOOK.',
 'identity-org':'Parties, titles, mandates, the firm. Owns GOV and PMC.',
 'assembly':'Convening, quorum, voting, protocols. Owns GA and VOTE.',
 'money':'Charges, the fund, arrears. Owns FEE, FUND and DEBT.',
 'intake':'Importing a firm\'s existing records. Owns no rules — it enforces ORG and BOOK on the way in.',
 'compliance':'Statutory obligations and filings. Owns REG.',
 'evidence':'Immutable documents and case files. Owns DOC.',
}

def build():
    paths = {}
    bad = []
    for path, method, tag, summary, rules, req, resp in OPS:
        for r in rules:
            if r not in KNOWN: bad.append((path, r))
        params = [{'name': p, 'in': 'path', 'required': True,
                   'schema': {'$ref': '#/components/schemas/Uuid'}}
                  for p in [seg[1:-1] for seg in path.split('/') if seg.startswith('{')]]
        op = {
          'tags': [tag],
          'summary': summary,
          'description': 'Rules: ' + ', '.join(f'`{r}`' for r in rules),
          'operationId': (method + path.replace('/', '_').replace('{','').replace('}','')
                          .replace('-','_')),
          'parameters': params,
          'responses': {
            '200': {'description': 'OK', 'content': {'application/json':
                    {'schema': {'$ref': f'#/components/schemas/{resp}'}}}},
            '403': {'description': 'Refused. The body names the rule that refused.',
                    'content': {'application/problem+json':
                    {'schema': {'$ref': '#/components/schemas/Problem'}}}},
            '409': {'description': 'A statutory precondition is not met.',
                    'content': {'application/problem+json':
                    {'schema': {'$ref': '#/components/schemas/Problem'}}}},
          },
        }
        if method in ('post','put','patch'):
            op['parameters'] = params + [{'name':'Idempotency-Key','in':'header',
              'required': True, 'schema': {'type':'string'},
              'description':'Every write is idempotent — offline queues replay (PM-SYS-014)'}]
            if req:
                op['requestBody'] = {'required': True, 'content': {'application/json':
                  {'schema': {'$ref': f'#/components/schemas/{req}'}}}}
        paths.setdefault(path, {})[method] = op

    if bad:
        print('INTEGRITY FAILURE — operations citing rule IDs absent from rules.json:')
        for p, r in bad: print(f'  {p} → {r}')
        return None

    return {
      'openapi': '3.1.0',
      'info': {
        'title': 'domuvai api',
        'version': '0.1.0',
        'summary': 'Управление на етажна собственост по ЗУЕС',
        'description':
          'One deployable, fourteen modules (ADR-003). Conventions that hold everywhere:\n\n'
          '- **`entrance_id` is the only tenant key** (ADR-005). Every resource resolves to '
          'exactly one entrance; nothing is addressable across entrances.\n'
          '- **Every write carries `Idempotency-Key`** (PM-SYS-014). A queued offline action '
          'replays without duplicating.\n'
          '- **Money is integer minor units in EUR** (PM-FEE-016). No floats anywhere.\n'
          '- **A refusal names the rule that refused** — `rule_id` in the problem body, so a '
          'client can explain the law rather than say "invalid".\n'
          '- **A statutory precondition cannot be skipped** (PM-SYS-006); 409 is the refusal, '
          'and the only alternative is a recorded, audited deviation.\n'
          '- **Statutory documents are Bulgarian** (PM-SYS-003) whatever the Accept-Language.',
      },
      'servers': [{'url': 'https://api.domuvai.bg/v1'}],
      'tags': [{'name': k, 'description': v} for k, v in TAGS.items()],
      'paths': paths,
      'components': {'schemas': SCHEMAS,
        'securitySchemes': {'bearer': {'type':'http','scheme':'bearer'}}},
      'security': [{'bearer': []}],
    }

def main():
    spec = build()
    if spec is None: return 1
    validate(spec)                       # fails loudly if the spec is not valid 3.1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + '\n')
    ops = sum(len(v) for v in spec['paths'].values())
    cited = {r for _,_,_,_,rs,_,_ in OPS for r in rs}
    print(f'OK  {ops} operations · {len(spec["paths"])} paths · {len(TAGS)} modules · '
          f'{len(cited)} rules cited · valid OpenAPI 3.1')
    return 0

if __name__ == '__main__':
    sys.exit(main())
