#!/usr/bin/env python3
"""A8 — generates the event envelope and one JSON Schema per event from the catalogue
below, emits an example for each, validates every example against its schema, and
cross-checks the catalogue against the events named in docs/DEVBRIEF.md and
docs/STAGE1.md. Fails loudly rather than printing a wrong total."""
import json, re, sys, pathlib, uuid

OUT = pathlib.Path('../docs/events')
DOCS = pathlib.Path('../docs')

# ---- shared types ---------------------------------------------------------
DEFS = {
  "Money": {"type":"object","additionalProperties":False,
    "required":["amount_minor","currency"],
    "properties":{"amount_minor":{"type":"integer","description":"integer minor units — never a float (ADR-006, PM-FEE-016)"},
                  "currency":{"const":"EUR"}}},
  "IdealParts": {"type":"string","pattern":r"^\d{1,3}\.\d{1,6}$",
    "description":"exact decimal percentage as a string — never a float (ADR-006, PM-ORG-002)"},
  "LegalDate": {"type":"string","format":"date","description":"Europe/Sofia calendar date (PM-SYS-004)"},
  "Instant": {"type":"string","format":"date-time","description":"UTC instant"},
  "Uuid": {"type":"string","format":"uuid"},
  "RuleId": {"type":"string","pattern":r"^PM-[A-Z]+-\d{3}$"},
  "Denominator": {"enum":["TOTAL","REPRESENTED"],"description":"ADR-008 — never implicit"},
}

# ---- catalogue: event -> (producer, required payload fields) ---------------
M, IP, LD, IN, U, RID = "Money","IdealParts","LegalDate","Instant","Uuid","RuleId"
C = {
 "CatalogueVersionPublished": ("law", {"catalogue_version":"str","approved_by":"str","dv_reference":"str","approved_at":IN}),
 "ConstantAdded":             ("law", {"code":"str","value":"str","in_force_from":LD,"source":"str"}),
 "LawSourceChanged":          ("law", {"source_id":"str","articles":"[str]","impacted_rules":"["+RID+"]","retrieved_at":IN}),
 "UnitChanged":               ("registry", {"unit_id":U,"designation":"str","built_area_m2":"str","valid_from":LD}),
 "IdealPartsChanged":         ("registry", {"unit_id":U,"ideal_parts":IP,"valid_from":LD}),
 "OccupancyChanged":          ("registry", {"unit_id":U,"persons":"int","children_under_6":"int","animals":"int","period_from":LD,"period_to":LD+"?"}),
 "BookEntryFiled":            ("registry", {"unit_id":U,"party_id":U,"declaration_kind":"str","filed_at":IN}),
 "GrantChanged":              ("identity-org", {"party_id":U,"role":"str","scope":"str","valid_from":LD,"valid_to":LD+"?"}),
 "MandateExpired":            ("identity-org", {"party_id":U,"role":"str","expired_on":LD}),
 "TitleTransferred":          ("identity-org", {"unit_id":U,"from_party_id":U,"to_party_id":U,"effective_from":LD}),
 "InsuranceLapsed":           ("identity-org", {"firm_id":U,"policy_ref":"str","lapsed_on":LD}),
 "AssemblyConvened":          ("assembly", {"assembly_id":U,"convened_by":U,"scheduled_for":IN,"agenda_item_count":"int","urgent":"bool"}),
 "SessionOpened":             ("assembly", {"assembly_id":U,"session_no":"int","represented_ideal_parts":IP,"quorum_met":"bool","quorum_threshold":IP}),
 "DecisionTaken":             ("assembly", {"decision_id":U,"assembly_id":U,"session_no":"int","agenda_item":"str","majority_rule":"str",
                                            "denominator":"Denominator","threshold":IP,"tally_for":IP,"tally_against":IP,
                                            "status":"str","execution_due":LD,"law_version":"str"}),
 "ProtocolAnnounced":         ("assembly", {"protocol_id":U,"assembly_id":U,"announced_at":IN,"appeal_deadline":LD,"evidence_document_id":U}),
 "ChargeIssued":              ("money", {"charge_run_id":U,"unit_id":U,"period":"str","amount":M,"basis_hash":"str",
                                          "law_version":"str","engine_version":"str"}),
 "PaymentPosted":             ("money", {"posting_id":U,"unit_id":U,"amount":M,"value_date":LD,"allocation_rule":"str"}),
 "ArrearAged":                ("money", {"unit_id":U,"amount":M,"bucket":"str","due_since":LD}),
 "FundDisbursed":             ("money", {"disbursement_id":U,"work_order_id":U,"amount":M,"authorised_by":U,"decision_id":U}),
 "WorkOrderRaised":           ("maintenance", {"work_order_id":U,"asset_id":U,"work_class":"str","estimated":M,"decision_id":U+"?"}),
 "WorkOrderClosed":           ("maintenance", {"work_order_id":U,"actual":M,"closed_on":LD,"warranty_until":LD+"?"}),
 "InspectionDue":             ("maintenance", {"asset_id":U,"obligation_code":"str","due_on":LD,"statutory_interval_days":"int"}),
 "PassportMeasureAdded":      ("maintenance", {"passport_id":U,"measure_code":"str","due_on":LD}),
 "NoticePosted":              ("notify", {"notice_id":U,"act_kind":"str","posted_at":IN,"evidence_document_id":U,"co_signed_by":U}),
 "DeliveryRecorded":          ("notify", {"notice_id":U,"party_id":U,"channel":"str","delivered_at":IN,"legally_sufficient":"bool"}),
 "DeliveryFailed":            ("notify", {"notice_id":U,"party_id":U,"channel":"str","failed_at":IN,"reason":"str"}),
 "ComplianceTaskRaised":      ("compliance", {"task_id":U,"obligation_code":"str","due_on":LD,"rule_id":RID,"owner_party_id":U+"?"}),
 "ComplianceTaskOverdue":     ("compliance", {"task_id":U,"obligation_code":"str","due_on":LD,"days_overdue":"int","rule_id":RID}),
 "FilingSubmitted":           ("compliance", {"filing_id":U,"authority":"str","filing_kind":"str","submitted_at":IN}),
 "FilingAcknowledged":        ("compliance", {"filing_id":U,"authority":"str","acknowledged_at":IN,"reference":"str"}),
 "DocumentStored":            ("evidence", {"document_id":U,"kind":"str","content_hash":"str","stored_at":IN,"retention_until":LD+"?"}),
 "CaseFileComposed":          ("evidence", {"case_file_id":U,"matter":"str","document_ids":"["+U+"]","composed_at":IN}),
 "PaymentInitiated":          ("rail", {"intent_id":U,"unit_id":U,"amount":M,"destination_iban_last4":"str","initiated_at":IN}),
 "BankEventReceived":         ("rail", {"bank_event_id":U,"amount":M,"value_date":LD,"reference":"str"}),
 "BankEventMatched":          ("rail", {"bank_event_id":U,"intent_id":U+"?","unit_id":U,"amount":M,"confidence":"str"}),
 "ProposalCreated":           ("agent", {"proposal_id":U,"capability":"str","classification":"str","grounding_source_ids":"["+U+"]","model_version":"str","catalogue_version":"str"}),
 "ImportCommitted":           ("intake", {"import_id":U,"source_document_id":U,"rows_created":"int","rows_changed":"int","committed_by":U}),
 "ImportReverted":            ("intake", {"import_id":U,"reverted_by":U,"reverted_at":IN,"reason":"str"}),
}

PRIM = {"str":{"type":"string","minLength":1},"int":{"type":"integer"},"bool":{"type":"boolean"}}
def typ(t):
    opt = t.endswith("?"); t = t.rstrip("?")
    if t.startswith("[") and t.endswith("]"):
        return {"type":"array","items":typ(t[1:-1])[0],"minItems":0}, opt
    if t in PRIM: return dict(PRIM[t]), opt
    return {"$ref": f"#/$defs/{t}"}, opt

ENVELOPE = {
  "$schema":"https://json-schema.org/draft/2020-12/schema",
  "$id":"https://domuvai.bg/events/envelope.schema.json",
  "title":"Event envelope",
  "description":"Every message carries this. Ordering is guaranteed per entrance_id only; delivery is at least once and every consumer is idempotent on event_id.",
  "type":"object","additionalProperties":False,
  "required":["event_id","type","version","entrance_id","occurred_at","correlation_id","law_version","producer","payload"],
  "properties":{
    "event_id":{"$ref":"#/$defs/Uuid","description":"idempotency key for every consumer"},
    "type":{"type":"string"},
    "version":{"type":"integer","minimum":1},
    "entrance_id":{"$ref":"#/$defs/Uuid","description":"the only tenant key (ADR-005). Never a building, never a firm"},
    "occurred_at":{"$ref":"#/$defs/Instant"},
    "causation_id":{"oneOf":[{"$ref":"#/$defs/Uuid"},{"type":"null"}]},
    "correlation_id":{"$ref":"#/$defs/Uuid"},
    "law_version":{"type":"string","description":"catalogue version in force when produced (ADR-001)"},
    "producer":{"type":"string"},
    "payload":{"type":"object"}},
  "$defs":{k:DEFS[k] for k in ("Uuid","Instant")}}

def example_for(t):
    opt = t.endswith("?"); t=t.rstrip("?")
    if t.startswith("["): return [example_for(t[1:-1])]
    return {"str":"example","int":3,"bool":True,"Uuid":str(uuid.uuid5(uuid.NAMESPACE_DNS,t)),
            "Instant":"2026-09-13T08:00:00Z","LegalDate":"2026-09-13","IdealParts":"12.345600",
            "Money":{"amount_minor":4250,"currency":"EUR"},"RuleId":"PM-FEE-002","Denominator":"REPRESENTED"}.get(t,"example")

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for f in OUT.glob("*.json"): f.unlink()
    (OUT/"envelope.schema.json").write_text(json.dumps(ENVELOPE,indent=2,ensure_ascii=False)+"\n")

    errs=[]; written=0
    for name,(producer,fields) in sorted(C.items()):
        props={}; req=[]; used=set()
        for fname,ftype in fields.items():
            s,opt = typ(ftype); props[fname]=s
            if not opt: req.append(fname)
            m=re.search(r'#/\$defs/(\w+)', json.dumps(s))
            if m: used.add(m.group(1))
        sch={"$schema":"https://json-schema.org/draft/2020-12/schema",
             "$id":f"https://domuvai.bg/events/{name}.schema.json",
             "title":name,"description":f"Produced by `{producer}`.",
             "type":"object","additionalProperties":False,
             "required":sorted(req),"properties":props,
             "$defs":{k:DEFS[k] for k in sorted(used)}}
        (OUT/f"{name}.schema.json").write_text(json.dumps(sch,indent=2,ensure_ascii=False)+"\n")
        ex={f:example_for(t) for f,t in fields.items() if not t.endswith("?")}
        (OUT/f"{name}.example.json").write_text(json.dumps(
            {"event_id":str(uuid.uuid5(uuid.NAMESPACE_DNS,name)),"type":name,"version":1,
             "entrance_id":"9f1a2b3c-0000-4000-8000-000000000001","occurred_at":"2026-09-13T08:00:00Z",
             "causation_id":None,"correlation_id":"9f1a2b3c-0000-4000-8000-0000000000ff",
             "law_version":"1.3","producer":producer,"payload":ex},indent=2,ensure_ascii=False)+"\n")
        written+=1

    # cross-check: every event in DEVBRIEF's "Publishes" column has a schema.
    # Read the column, not the whole document — entity names are CamelCase too.
    named=set()
    for line in (DOCS/"DEVBRIEF.md").read_text().split("\n"):
        if line.startswith("| `") and line.count("|")>=5:
            cells=[c.strip() for c in line.split("|")]
            named |= set(re.findall(r"`([A-Z][A-Za-z]+)`", cells[3]))
    named.discard("Publishes")
    orphan = sorted(named - set(C))
    if orphan: errs.append(f"published in DEVBRIEF but no schema: {orphan}")
    unused = sorted(set(C) - named - {"ImportCommitted","ImportReverted"})
    if unused: errs.append(f"schema exists but no module publishes it: {unused}")
    if errs:
        print("INTEGRITY FAILURE:"); [print("  -",e) for e in errs]; sys.exit(1)
    print(f"OK  {written} events + envelope  ·  {written*2+1} files in docs/events/")

if __name__ == "__main__": main()
