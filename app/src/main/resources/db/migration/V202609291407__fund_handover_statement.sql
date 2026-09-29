-- Rule: PM-FUND-010 — the fund's reconciled balance statement at a change of manager or management company.
-- Stored as issued and never changed; a correction is a new statement. From the ledger: the balance before the
-- period, what was received and paid out in it, and the closing balance, which reconciles by construction; beside
-- it the balance on the bank's own statement for the handover date. The unpaid disbursements the incoming side
-- inherits are in `basis`, pinned by `basis_hash`, `law_version` and `engine_version` (ADR-001 amendment): the
-- hash both parties will sign.
CREATE TABLE money.fund_handover_statement (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  fund_account_id  uuid NOT NULL REFERENCES money.fund_account(id),
  period_from      date,
  handover_on      date NOT NULL,
  outgoing_party   uuid NOT NULL REFERENCES registry.party(id),
  incoming_party   uuid NOT NULL REFERENCES registry.party(id),
  opening_minor    money_minor NOT NULL,
  received_minor   money_minor NOT NULL CONSTRAINT fund_handover_statement_received_not_negative CHECK (received_minor >= 0),
  paid_out_minor   money_minor NOT NULL CONSTRAINT fund_handover_statement_paid_out_not_negative CHECK (paid_out_minor >= 0),
  closing_minor    money_minor NOT NULL,
  bank_minor       money_minor NOT NULL,
  committed_minor  money_minor NOT NULL CONSTRAINT fund_handover_statement_committed_not_negative CHECK (committed_minor >= 0),
  currency         currency_eur NOT NULL DEFAULT 'EUR',
  basis            jsonb NOT NULL,
  basis_hash       text NOT NULL,
  law_version      text NOT NULL,
  engine_version   text NOT NULL,
  issued_on        date NOT NULL,
  CONSTRAINT fund_handover_statement_parties_differ CHECK (outgoing_party <> incoming_party),
  CONSTRAINT fund_handover_statement_reconciles CHECK (opening_minor + received_minor - paid_out_minor = closing_minor),
  CONSTRAINT fund_handover_statement_period CHECK (period_from <= handover_on),
  CONSTRAINT fund_handover_statement_not_ahead CHECK (handover_on <= issued_on)
);
CREATE INDEX fund_handover_statement_entrance ON money.fund_handover_statement (entrance_id);
CREATE RULE fund_handover_statement_no_update AS ON UPDATE TO money.fund_handover_statement DO INSTEAD NOTHING;
CREATE RULE fund_handover_statement_no_delete AS ON DELETE TO money.fund_handover_statement DO INSTEAD NOTHING;
