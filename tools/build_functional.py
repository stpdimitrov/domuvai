#!/usr/bin/env python3
"""Fills FUNCTIONAL.template.md from rules.json. Counts are generated, never typed.
Fails loudly if any rule is unmapped or any module claims a domain that does not exist."""
import json, re, sys, pathlib

d = json.load(open('../docs/rules.json'))
rules = d['rules']
dom_title = {x['code']: x['title'] for x in d['domains']}
dom_count = {x['code']: x['count'] for x in d['domains']}

# module code -> (number, name, domain)
MODULES = [
    ("M01", "Building register",            "ORG"),
    ("M02", "Owners' book",                 "BOOK"),
    ("M03", "Governance and mandates",      "GOV"),
    ("M04", "General assembly",             "GA"),
    ("M05", "Voting and decisions",         "VOTE"),
    ("M06", "Charges and billing",          "FEE"),
    ("M07", "Repair and renewal fund",      "FUND"),
    ("M08", "Arrears and enforcement",      "DEBT"),
    ("M09", "Maintenance and compliance",   "MNT"),
    ("M10", "Registers and filings",        "REG"),
    ("M11", "Documents and evidence",       "DOC"),
    ("M12", "Professional management",      "PMC"),
    ("M13", "AI live manager",              "AI"),
]
PLATFORM = [
    ("P1", "Access, privacy and audit", "SEC"),
    ("P2", "Platform behaviour",        "SYS"),
    ("P3", "Law watch",                 "LAW"),
]

ALL = MODULES + PLATFORM
mapped = [m[2] for m in ALL]

# --- integrity checks -------------------------------------------------
errs = []
if len(mapped) != len(set(mapped)):
    errs.append("a domain is mapped to two modules")
for code in dom_count:
    if code not in mapped:
        errs.append(f"domain {code} has no module — {dom_count[code]} rules orphaned")
for code in mapped:
    if code not in dom_count:
        errs.append(f"module claims domain {code} which does not exist in rules.json")
covered = sum(dom_count[c] for c in mapped if c in dom_count)
if covered != d['meta']['count'] or covered != len(rules):
    errs.append(f"coverage {covered} != catalogue {d['meta']['count']} / {len(rules)}")
if errs:
    print("INTEGRITY FAILURE:"); [print("  -", e) for e in errs]; sys.exit(1)

def ids(code):
    r = [x['id'] for x in rules if x['domain'] == code]
    return f"{r[0]} – {r[-1]}"
def unverified(code):
    return sum(1 for x in rules if x['domain'] == code and not x.get('verified'))
def scope(code):
    s = {x['scope'] for x in rules if x['domain'] == code}
    return "both modes" if s == {"BOTH"} else ("firms only" if s == {"PMC"} else "both modes")

# --- generated fragments ---------------------------------------------
rows = ["| # | Module | What it is for | Rules | Unconfirmed |",
        "|---|---|---|---|---|"]
PURPOSE = {
 "M01":"Who owns what, and in which entrance",
 "M02":"Who lives there, and who may see that",
 "M03":"Who holds office, and until when",
 "M04":"Calling, holding and minuting the meeting",
 "M05":"Turning attendance into a lawful decision",
 "M06":"Turning decisions into money owed",
 "M07":"The repair fund the building owns",
 "M08":"Turning money owed into money collected",
 "M09":"Keeping the building legal and safe",
 "M10":"Telling the authorities, on time",
 "M11":"Proving it later",
 "M12":"Running many buildings as a business",
 "M13":"Doing the work, under a human's name",
 "P1":"Who may see and do what",
 "P2":"Money, time and truth",
 "P3":"Noticing when the law moves",
}
for num, name, code in ALL:
    u = unverified(code)
    rows.append(f"| {num} | {name} | {PURPOSE[num]} | {dom_count[code]} | {u if u else '—'} |")
tot_u = sum(1 for x in rules if not x.get('verified'))
rows.append(f"| | **{len(ALL)} modules** | | **{covered}** | **{tot_u}** |")
MODULE_TABLE = "\n".join(rows)

trows = ["| Module | Rule domain | Rule IDs | Count | Applies to |", "|---|---|---|---|---|"]
for num, name, code in ALL:
    trows.append(f"| {num} {name} | {code} — {dom_title[code]} | `{ids(code)}` | {dom_count[code]} | {scope(code)} |")
trows.append(f"| | | | **{covered} of {d['meta']['count']}** | |")
TRACE_TABLE = "\n".join(trows)

# per-module footer line
FOOT = {}
for num, name, code in ALL:
    u = unverified(code)
    extra = (f" · {u} carries an unconfirmed number, held in configuration" if u==1 else f" · {u} carry unconfirmed numbers, held in configuration") if u else ""
    FOOT[num] = f"**Rules:** {dom_count[code]} (`{ids(code)}`){extra}"

tpl = pathlib.Path('FUNCTIONAL.template.md').read_text(encoding='utf-8')
out = (tpl.replace("{{MODULE_TABLE}}", MODULE_TABLE)
          .replace("{{TRACE_TABLE}}", TRACE_TABLE)
          .replace("{{TOTAL}}", str(covered))
          .replace("{{UNVERIFIED}}", str(tot_u))
          .replace("{{MODCOUNT}}", str(len(MODULES)))
          .replace("{{PLATCOUNT}}", str(len(PLATFORM)))
          .replace("{{VERSION}}", d['meta']['version'])
          .replace("{{BASELINE}}", d['meta']['legal_baseline']))
for k, v in FOOT.items():
    out = out.replace("{{RULES:%s}}" % k, v)

left = re.findall(r"\{\{[^}]+\}\}", out)
if left:
    print("UNFILLED PLACEHOLDERS:", sorted(set(left))); sys.exit(1)

pathlib.Path('../docs/FUNCTIONAL.md').write_text(out, encoding='utf-8')
print(f"OK  {len(ALL)} modules  {covered}/{d['meta']['count']} rules covered  {tot_u} unconfirmed  {len(out)} bytes")
