-- Rule: PM-FUND-010 — from S-G1-02d's review. A statement is ordered by the moment it was issued, so a correction
-- never hides the one it corrects (a statement issued before this migration takes the migration's moment). What was
-- received is net — a reversed receipt counts against it — so it carries no sign check.
ALTER TABLE money.fund_handover_statement ADD COLUMN issued_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE money.fund_handover_statement ALTER COLUMN issued_at DROP DEFAULT;
ALTER TABLE money.fund_handover_statement DROP CONSTRAINT fund_handover_statement_received_not_negative;
DROP INDEX money.fund_handover_statement_entrance;
CREATE INDEX fund_handover_statement_entrance ON money.fund_handover_statement (entrance_id, issued_at DESC);
