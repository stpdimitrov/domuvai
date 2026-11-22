-- DEC-03. Rule: PM-SEC-004 — the log of who read a paper decision's custodian names persons, so a read of the log is
-- itself entered, in the same table: read_of says what was read — CUSTODIAN (a paper decision's custodian, every row
-- before this one) or LOG (this log). A read of the log is served only to a signed-in reader the policy allows (the
-- owner's Q1a and Q2a on #497, 2026-11-22), so a LOG row is always SERVED. The table stays insert-only.
ALTER TABLE assembly.paper_decision_read
  ADD COLUMN read_of text NOT NULL DEFAULT 'CUSTODIAN' CHECK (read_of IN ('CUSTODIAN','LOG'));
ALTER TABLE assembly.paper_decision_read
  ADD CONSTRAINT paper_decision_read_log_is_served CHECK (read_of <> 'LOG' OR outcome = 'SERVED');
