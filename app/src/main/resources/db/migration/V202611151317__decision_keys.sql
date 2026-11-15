-- VOTE-KEY-06 · Rule: PM-SYS-014 — the close of an item, the merge of its absentee ballots and the posting of an assembly's
-- protocol carry an Idempotency-Key (DEVBRIEF §8), so one sent again after a lost answer is answered with what the first made,
-- never the 409 "already closed / merged / entered". The key is kept in the one per-entrance key space of
-- assembly.vote_request_key (UNIQUE (entrance_id, idempotency_key)), so the same key for another operation is refused. The key
-- row is insert-only (PM-VOTE-014).
ALTER TABLE assembly.vote_request_key DROP CONSTRAINT vote_request_key_operation_check;
ALTER TABLE assembly.vote_request_key ADD CONSTRAINT vote_request_key_operation_check
  CHECK (operation IN ('RECORD_VOTE', 'ENTER_ABSENTEE_BALLOT', 'REGISTER_PROXY', 'RECORD_ATTENDANCE', 'RECORD_PAPER_DECISION',
                       'CLOSE_ITEM', 'MERGE_ABSENTEE', 'POST_PROTOCOL'));

-- Rows made before this migration name none. The column is set at insert and never updated (a decision, a merge and a posting
-- are never edited); the key row each names cannot be removed.
ALTER TABLE assembly.decision ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE UNIQUE INDEX decision_by_request_key ON assembly.decision (request_key_id) WHERE request_key_id IS NOT NULL;
ALTER TABLE assembly.merged_decision ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE UNIQUE INDEX merged_decision_by_request_key ON assembly.merged_decision (request_key_id) WHERE request_key_id IS NOT NULL;
ALTER TABLE assembly.protocol_posting ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE UNIQUE INDEX protocol_posting_by_request_key ON assembly.protocol_posting (request_key_id) WHERE request_key_id IS NOT NULL;
