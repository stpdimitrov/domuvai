#!/usr/bin/env bash
# Every invariant the schema claims to enforce, tried against a deliberate
# violation. A constraint never run against a failure is not a constraint.
set -uo pipefail
PSQL="psql -h ${PGHOST:-/tmp} -p ${PGPORT:-55432} -U postgres -d ${PGDATABASE:-domuvai} -v ON_ERROR_STOP=1 -q"
pass=0; fail=0

# expect_reject <name> <sql>   — the SQL must fail
expect_reject() {
  local name="$1"; shift
  if echo "$1" | $PSQL >/dev/null 2>&1; then
    printf '  \033[31m✗ %s — ACCEPTED, should have been rejected\033[0m\n' "$name"; fail=$((fail+1))
  else
    printf '  ✓ %s\n' "$name"; pass=$((pass+1))
  fi
}
# expect_accept <name> <sql>   — the SQL must succeed (guards against over-tight rules)
expect_accept() {
  local name="$1"; shift
  if echo "$1" | $PSQL >/dev/null 2>&1; then
    printf '  ✓ %s\n' "$name"; pass=$((pass+1))
  else
    printf '  \033[31m✗ %s — REJECTED, should have been accepted\033[0m\n' "$name"; fail=$((fail+1))
  fi
}

SEED="
INSERT INTO registry.condominium(id,address) VALUES ('11111111-0000-4000-8000-000000000001','ул. Тест 1');
INSERT INTO registry.entrance(id,condominium_id,label,management_form)
  VALUES ('22222222-0000-4000-8000-000000000001','11111111-0000-4000-8000-000000000001','А','GA');
INSERT INTO registry.party(id,full_name) VALUES ('33333333-0000-4000-8000-000000000001','Иван Иванов');
"
echo "$SEED" | $PSQL >/dev/null 2>&1

echo "PM-ORG-002 — ideal parts sum to exactly 100%"
expect_reject "99.9999% is rejected" "BEGIN;
  INSERT INTO registry.unit(id,entrance_id,designation,unit_type,ideal_parts_pct)
  VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','x1','FLAT',99.9999);
COMMIT;"
expect_accept "100.0000% across two units is accepted" "BEGIN;
  INSERT INTO registry.unit(id,entrance_id,designation,unit_type,ideal_parts_pct) VALUES
    ('44444444-0000-4000-8000-000000000001','22222222-0000-4000-8000-000000000001','a1','FLAT',60.0000),
    ('44444444-0000-4000-8000-000000000002','22222222-0000-4000-8000-000000000001','a2','FLAT',40.0000);
COMMIT;"

echo "PM-FEE-016 — money is integer minor units"
expect_reject "a fractional amount is rejected" "
  INSERT INTO money.charge_run(id,entrance_id,period,legal_date,basis,basis_hash,law_version,engine_version,status)
   VALUES ('55555555-0000-4000-8000-000000000001','22222222-0000-4000-8000-000000000001','2026-09','2026-09-13','{}','h','1.3','0.1.0','COMPLETE');
  INSERT INTO money.charge_line(id,entrance_id,charge_run_id,unit_id,component,allocation_key,quantity,amount_minor,derivation)
   VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','55555555-0000-4000-8000-000000000001','44444444-0000-4000-8000-000000000001','MANAGEMENT','PER_PERSON',1,42.5,'x');"

echo "PM-FEE-015 — an issued charge line is never rewritten"
expect_accept "the update is silently ignored, not applied" "
  INSERT INTO money.charge_line(id,entrance_id,charge_run_id,unit_id,component,allocation_key,quantity,amount_minor,derivation)
   VALUES ('66666666-0000-4000-8000-000000000001','22222222-0000-4000-8000-000000000001','55555555-0000-4000-8000-000000000001','44444444-0000-4000-8000-000000000001','MANAGEMENT','PER_PERSON',1,1000,'x');
  UPDATE money.charge_line SET amount_minor = 9999 WHERE id='66666666-0000-4000-8000-000000000001';
  SELECT 1/(CASE WHEN (SELECT amount_minor FROM money.charge_line
    WHERE id='66666666-0000-4000-8000-000000000001') = 1000 THEN 1 ELSE 0 END);"

echo "ADR-006 — a journal must balance to zero"
expect_reject "a one-sided journal is rejected" "BEGIN;
  INSERT INTO money.posting(id,entrance_id,journal_id,account,amount_minor,value_date)
   VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','77777777-0000-4000-8000-000000000001','RECEIVABLE',5000,'2026-09-13');
COMMIT;"
expect_accept "both legs together are accepted" "BEGIN;
  INSERT INTO money.posting(id,entrance_id,journal_id,account,amount_minor,value_date) VALUES
   (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','88888888-0000-4000-8000-000000000001','RECEIVABLE',5000,'2026-09-13'),
   (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','88888888-0000-4000-8000-000000000001','INCOME',-5000,'2026-09-13');
COMMIT;"

echo "PM-GOV-004 — a mandate may not exceed two years"
expect_reject "a three-year mandate is rejected" "
  INSERT INTO identity_org.management_mandate(id,entrance_id,body,valid_from,valid_to)
   VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','BM','2026-01-01','2029-01-01');"
expect_accept "a two-year mandate is accepted" "
  INSERT INTO identity_org.management_mandate(id,entrance_id,body,valid_from,valid_to)
   VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','BM','2026-01-01','2028-01-01');"

echo "PM-ORG-005 — no double counting of a co-owner's vote"
expect_reject "overlapping title periods for one party and unit are rejected" "
  INSERT INTO registry.title(id,entrance_id,unit_id,party_id,title_role,share,valid_from,valid_to) VALUES
   (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','44444444-0000-4000-8000-000000000001','33333333-0000-4000-8000-000000000001','OWN',0.5,'2026-01-01','2027-01-01'),
   (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','44444444-0000-4000-8000-000000000001','33333333-0000-4000-8000-000000000001','OWN',0.5,'2026-06-01',NULL);"

echo "PM-VOTE-012 — a decision carries its denominator"
expect_reject "an invented denominator is rejected" "
  INSERT INTO assembly.assembly(id,entrance_id,scheduled_at,mode,status)
   VALUES ('99999999-0000-4000-8000-000000000001','22222222-0000-4000-8000-000000000001',now(),'IN_PERSON','OPEN');
  INSERT INTO assembly.agenda_item(id,entrance_id,assembly_id,ordinal,text,majority_rule_id)
   VALUES ('99999999-0000-4000-8000-000000000002','22222222-0000-4000-8000-000000000001','99999999-0000-4000-8000-000000000001',1,'t','MAJ-50');
  INSERT INTO assembly.decision(id,entrance_id,agenda_item_id,session_no,majority_rule_id,denominator,threshold_pct,tally_for,tally_against,status,law_version,engine_version)
   VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','99999999-0000-4000-8000-000000000002',1,'MAJ-50','WHATEVER',50,60,40,'FINAL','1.3','0.1.0');"

echo "PM-FUND-005 / PM-PMC-008 — one IBAN, one entrance, one purpose"
expect_reject "a second entrance reusing the same IBAN is rejected" "
  INSERT INTO registry.entrance(id,condominium_id,label,management_form)
   VALUES ('22222222-0000-4000-8000-000000000002','11111111-0000-4000-8000-000000000001','Б','GA');
  INSERT INTO money.fund_account(id,entrance_id,iban,purpose,holder_party) VALUES
   (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','BG00TEST1','REPAIR_RENEWAL','33333333-0000-4000-8000-000000000001'),
   (gen_random_uuid(),'22222222-0000-4000-8000-000000000002','BG00TEST1','REPAIR_RENEWAL','33333333-0000-4000-8000-000000000001');"

echo "PM-REG-008 — a filing is not done until acknowledged"
expect_reject "an ack reference without an ack timestamp is rejected" "
  INSERT INTO compliance.registry_filing(id,entrance_id,registry,filed_at,ack_ref)
   VALUES (gen_random_uuid(),'22222222-0000-4000-8000-000000000001','MUNICIPAL',now(),'REF-1');"

echo "PM-DOC-001 — documents are immutable"
expect_accept "a delete is silently ignored, not applied" "
  INSERT INTO evidence.document(id,entrance_id,entity_ref,kind,content_hash)
   VALUES ('aaaaaaaa-0000-4000-8000-000000000001','22222222-0000-4000-8000-000000000001','x','PROTOCOL','h1');
  DELETE FROM evidence.document WHERE id='aaaaaaaa-0000-4000-8000-000000000001';
  SELECT 1/(CASE WHEN EXISTS (SELECT 1 FROM evidence.document
    WHERE id='aaaaaaaa-0000-4000-8000-000000000001') THEN 1 ELSE 0 END);"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
