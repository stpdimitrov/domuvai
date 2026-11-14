-- VOTE-KEY-03 · Rule: PM-SYS-014 — a proxy registered from a device's queue carries an Idempotency-Key (DEVBRIEF §8), so a
-- queue replayed twice registers one proxy. It is kept in the one per-entrance key space of assembly.vote_request_key
-- (UNIQUE (entrance_id, idempotency_key)), so the same key for a vote, a ballot and a proxy is refused, not applied twice.
-- The proxy names the request that made it, so a replay answers with exactly that proxy. The key row is insert-only (PM-VOTE-014).
ALTER TABLE assembly.vote_request_key DROP CONSTRAINT vote_request_key_operation_check;
ALTER TABLE assembly.vote_request_key ADD CONSTRAINT vote_request_key_operation_check CHECK (operation IN ('RECORD_VOTE', 'ENTER_ABSENTEE_BALLOT', 'REGISTER_PROXY'));

-- Proxies registered before this migration name none. The column is set at insert and never updated; the key row it names cannot be removed.
ALTER TABLE assembly.proxy ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE UNIQUE INDEX proxy_by_request_key ON assembly.proxy (request_key_id) WHERE request_key_id IS NOT NULL;
