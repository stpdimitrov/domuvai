-- Rule: PM-FUND-006 / PM-FUND-007 / PM-FUND-008 — a disbursement from the repair and renewal fund: what it
-- is for, what authorises it — a GA decision, or an emergency with its justification, never neither and
-- never both — and who signed it off (the party holding the fund's account, PM-FUND-004). It stays
-- COMMITTED until it is paid; committed money is not available (PM-FUND-009). The platform records the
-- disbursement; the money moves in the fund's own bank account (ADR-007).
CREATE TABLE money.fund_disbursement (
  id                       uuid PRIMARY KEY,
  entrance_id              uuid NOT NULL REFERENCES registry.entrance(id),
  fund_account_id          uuid NOT NULL REFERENCES money.fund_account(id),
  amount_minor             money_minor NOT NULL CHECK (amount_minor > 0),
  currency                 currency_eur NOT NULL DEFAULT 'EUR',
  purpose                  text NOT NULL CHECK (purpose IN ('WORKS','PASSPORT_MEASURE','GA_PURPOSE')),
  decision_id              text,
  passport_measure         text,
  emergency_justification  text,
  authorised_by            uuid NOT NULL REFERENCES registry.party(id),
  status                   text NOT NULL CHECK (status IN ('COMMITTED','PAID')),
  committed_on             date NOT NULL,
  CHECK ((decision_id IS NULL) <> (emergency_justification IS NULL)),
  CHECK (purpose <> 'PASSPORT_MEASURE' OR passport_measure IS NOT NULL)
);
CREATE INDEX fund_disbursement_entrance ON money.fund_disbursement (entrance_id);
