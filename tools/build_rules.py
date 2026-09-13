import json, re, pathlib

md = pathlib.Path("RULES.md").read_text(encoding="utf-8")

DOMAIN_TITLES = {
 "ORG":"Structure & ideal parts","BOOK":"Owners' book & residents","GOV":"Governance & mandates",
 "GA":"General assembly lifecycle","VOTE":"Majorities & decision routing","FEE":"Charges & allocation",
 "FUND":"Repair & renewal fund","DEBT":"Arrears & enforcement","MNT":"Maintenance & compliance",
 "REG":"Registers & filings","PMC":"Professional management (commercial)","DOC":"Documents & evidence",
 "SEC":"Access control & privacy","SYS":"Cross-cutting system rules","AI":"The live manager agent","LAW":"Watching the statute",
}

rules=[]
for line in md.splitlines():
    if not line.startswith("| PM-"): continue
    cells=[c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells)!=6: 
        print("SKIP(cols=%d): %s"%(len(cells), cells[0])); continue
    rid, mod, statement, source, scope, acceptance = cells
    domain = rid.split("-")[1]
    rules.append({
        "id": rid,
        "domain": domain,
        "domain_title": DOMAIN_TITLES.get(domain, domain),
        "modality": mod,
        "rule": statement,
        "source": source.replace("⚠","").strip(),
        "verified": "⚠" not in source,
        "scope": scope,
        "acceptance": acceptance,
    })

out={
 "meta":{
   "title":"Bulgarian condominium property management — business rules",
   "version":"1.0",
   "legal_baseline":"2026-09-03",
   "jurisdiction":"BG",
   "primary_act":"Закон за управление на етажната собственост (ЗУЕС), ДВ 6/2009, am. ДВ 82/2023",
   "currency":"EUR since 2026-01-01 (1.95583 BGN/EUR)",
   "disclaimer":"Not legal advice. Rules with verified=false carry unconfirmed numerics; treat as configuration and verify against the consolidated statute.",
   "count":len(rules),
 },
 "domains":[{"code":k,"title":v,"count":sum(1 for r in rules if r["domain"]==k)} for k,v in DOMAIN_TITLES.items()],
 "rules":rules,
}
pathlib.Path("rules.json").write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
print("rules:",len(rules))
print("unverified:",sum(1 for r in rules if not r["verified"]))
for d in out["domains"]: print(" ",d["code"],d["count"])
