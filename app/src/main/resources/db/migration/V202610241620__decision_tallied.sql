-- S-G2-02g: a decision as an item is closed and its votes tallied. No code wrote assembly.decision before this, so it
-- is empty wherever this runs: the NOT NULL columns below need no default.

-- Rule: PM-VOTE-011 — the tally is on ideal parts, exactly: a co-owner's share of 0.333333 of a unit outruns the
-- domain's four decimals, so the sums do too.
ALTER TABLE assembly.decision ALTER COLUMN tally_for TYPE numeric;
ALTER TABLE assembly.decision ALTER COLUMN tally_against TYPE numeric;
ALTER TABLE assembly.decision ADD COLUMN tally_abstain numeric NOT NULL;
ALTER TABLE assembly.decision ADD CONSTRAINT decision_tally_within_the_whole
  CHECK (tally_for >= 0 AND tally_against >= 0 AND tally_abstain >= 0 AND tally_for <= 100 AND tally_against <= 100 AND tally_abstain <= 100);

-- Rule: PM-VOTE-016 — the majority applied (rule id, denominator, threshold, and how it is compared), the represented
-- share it was counted against, the sitting, the outcome and the status, and what it was computed from.
ALTER TABLE assembly.decision ADD COLUMN assembly_id uuid NOT NULL REFERENCES assembly.assembly(id);
ALTER TABLE assembly.decision ADD COLUMN comparison text NOT NULL CHECK (comparison IN ('MORE_THAN','AT_LEAST'));
ALTER TABLE assembly.decision ADD COLUMN represented numeric NOT NULL CHECK (represented > 0 AND represented <= 100);
ALTER TABLE assembly.decision ADD COLUMN outcome text NOT NULL CHECK (outcome IN ('PASSED','NOT_PASSED'));
ALTER TABLE assembly.decision ADD COLUMN basis text NOT NULL;
ALTER TABLE assembly.decision ADD COLUMN basis_hash text NOT NULL CHECK (basis_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE assembly.decision ADD COLUMN closed_by uuid NOT NULL REFERENCES registry.party(id);
ALTER TABLE assembly.decision ADD COLUMN login_issuer text NOT NULL;
ALTER TABLE assembly.decision ADD COLUMN login_subject text NOT NULL;

-- Rule: PM-VOTE-014 — an item is closed once: one decision per item, and a decision is never edited.
ALTER TABLE assembly.decision ADD CONSTRAINT decision_once_per_item UNIQUE (agenda_item_id);
CREATE RULE decision_no_update AS ON UPDATE TO assembly.decision DO INSTEAD NOTHING;
CREATE RULE decision_no_delete AS ON DELETE TO assembly.decision DO INSTEAD NOTHING;
