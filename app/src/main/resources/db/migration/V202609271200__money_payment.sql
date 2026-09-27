-- Rule: PM-DEBT-008 — a recorded payment and the rule that allocated it. The allocation itself is
-- the payment's journal (journal_id = payment.id), every leg dated on the payment's value date: one
-- RECEIVABLE credit per settled debt, naming that debt, and any overpayment credited to the unit's
-- ADVANCE. The rule applied, its remainder rule and the debts it saw are stored in `basis`
-- (ADR-006), pinned by `basis_hash`, `law_version` and `engine_version` (ADR-001 amendment), so the
-- allocation stays visible and reproducible after the fact.
CREATE TABLE money.payment (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  unit_id          uuid NOT NULL REFERENCES registry.unit(id),
  amount_minor     money_minor NOT NULL CHECK (amount_minor > 0),
  currency         currency_eur NOT NULL DEFAULT 'EUR',
  value_date       date NOT NULL,
  -- where the money landed: one of the entrance's own accounts (PM-FUND-004), or the manager's cash
  received_into    text NOT NULL CHECK (received_into IN ('OPERATING','REPAIR_RENEWAL','CASH')),
  allocation_rule  text NOT NULL CHECK (allocation_rule IN ('OLDEST_FIRST','DESIGNATED')),
  designated_date  date,
  basis            jsonb NOT NULL,
  basis_hash       text NOT NULL,
  law_version      text NOT NULL,
  engine_version   text NOT NULL,
  idempotency_key  text NOT NULL,
  recorded_at      timestamptz NOT NULL DEFAULT now(),
  -- a retried request records one payment, never two
  UNIQUE (entrance_id, idempotency_key),
  CHECK ((allocation_rule = 'DESIGNATED') = (designated_date IS NOT NULL))
);
-- A recorded payment is never rewritten; a mistaken one is corrected by a reversing journal.
CREATE RULE payment_no_update AS ON UPDATE TO money.payment DO INSTEAD NOTHING;
CREATE RULE payment_no_delete AS ON DELETE TO money.payment DO INSTEAD NOTHING;

-- A receivable credit names the debt it settled — the charge's value date — while the credit itself
-- is dated when the money arrived. An as-of read then sees only payments made by that date, and
-- ageing bands the credit with its debt (PM-DEBT-001). A payment cannot settle a later debt.
ALTER TABLE money.posting ADD COLUMN settles_value_date date;
ALTER TABLE money.posting ADD CONSTRAINT posting_settles_a_receivable
  CHECK (settles_value_date IS NULL OR (account = 'RECEIVABLE' AND amount_minor < 0 AND settles_value_date <= value_date));
