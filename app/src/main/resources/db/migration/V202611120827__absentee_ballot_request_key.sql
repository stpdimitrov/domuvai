-- VOTE-KEY-02 · Rule: PM-SYS-014 — an absentee ballot entered from a device's queue carries an Idempotency-Key (DEVBRIEF §8),
-- so a queue replayed twice enters one ballot. It is kept in the one per-entrance key space of assembly.vote_request_key
-- (UNIQUE (entrance_id, idempotency_key)), so the same key for a vote and a ballot is refused, not applied twice. The
-- ballot's rows name the request that made them, so a replay answers with exactly those rows. Insert-only (PM-VOTE-014).
ALTER TABLE assembly.vote_request_key DROP CONSTRAINT vote_request_key_operation_check;
ALTER TABLE assembly.vote_request_key ADD CONSTRAINT vote_request_key_operation_check CHECK (operation IN ('RECORD_VOTE', 'ENTER_ABSENTEE_BALLOT'));

-- Ballots entered before this migration name none. absentee_ballot is never updated (absentee_ballot_no_update); the column is set at insert.
ALTER TABLE assembly.absentee_ballot ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE INDEX absentee_ballot_by_request_key ON assembly.absentee_ballot (request_key_id) WHERE request_key_id IS NOT NULL;
