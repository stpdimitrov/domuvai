-- Rule: PM-DEBT-001 — an arrear accrues per unit, so a receivable always names its unit; so does an
-- advance, the credit a unit holds. A RECEIVABLE or ADVANCE posting without one would vanish from
-- every per-unit read. Nothing writes one today; this makes it impossible (#73 item 3).
ALTER TABLE money.posting ADD CONSTRAINT posting_unit_receivable_advance
  CHECK (account NOT IN ('RECEIVABLE','ADVANCE') OR unit_id IS NOT NULL);
