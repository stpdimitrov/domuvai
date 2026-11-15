-- VOTE-KEY-05 · Rule: PM-SYS-014 — a decision recorded from its paper protocol carries an Idempotency-Key (DEVBRIEF §8), so a
-- form sent again after a lost answer records it once. It is kept in the one per-entrance key space of assembly.vote_request_key
-- (UNIQUE (entrance_id, idempotency_key)), so the same key for a vote, a ballot, a proxy, an attendance and a paper decision is
-- refused, not applied twice. The key row is insert-only (PM-SEC-004).
ALTER TABLE assembly.vote_request_key DROP CONSTRAINT vote_request_key_operation_check;
ALTER TABLE assembly.vote_request_key ADD CONSTRAINT vote_request_key_operation_check
  CHECK (operation IN ('RECORD_VOTE', 'ENTER_ABSENTEE_BALLOT', 'REGISTER_PROXY', 'RECORD_ATTENDANCE', 'RECORD_PAPER_DECISION'));

-- Records made before this migration name none. The column is set at insert; the table's own trigger refuses any change to a
-- record, so it is never rewritten, and the key row it names cannot be removed.
ALTER TABLE assembly.paper_decision ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE UNIQUE INDEX paper_decision_by_request_key ON assembly.paper_decision (request_key_id) WHERE request_key_id IS NOT NULL;
