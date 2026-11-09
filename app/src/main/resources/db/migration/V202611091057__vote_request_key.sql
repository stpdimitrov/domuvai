-- VOTE-KEY-01 · Rule: PM-SYS-014 — a vote recorded from a device's queue carries an Idempotency-Key (DEVBRIEF §8), so a
-- queue replayed twice records one vote. The key is kept per entrance, in the vote's own transaction: the operation, a
-- hash of what was asked, and when. A repeat with the same key and the same request writes nothing and is answered with
-- the votes the first one made; the same key for anything else is refused. Insert-only (PM-VOTE-014).
CREATE TABLE assembly.vote_request_key (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  idempotency_key  text NOT NULL CONSTRAINT vote_request_key_not_blank CHECK (idempotency_key ~ '\S'),
  operation        text NOT NULL CHECK (operation IN ('RECORD_VOTE')),
  request_hash     text NOT NULL,
  kept_at          timestamptz NOT NULL,
  UNIQUE (entrance_id, idempotency_key)
);
CREATE RULE vote_request_key_no_update AS ON UPDATE TO assembly.vote_request_key DO INSTEAD NOTHING;
CREATE RULE vote_request_key_no_delete AS ON DELETE TO assembly.vote_request_key DO INSTEAD NOTHING;

-- Each vote names the request that made it, so a replay answers with exactly those rows. Votes recorded before this
-- migration name none.
ALTER TABLE assembly.vote ADD COLUMN request_key_id uuid REFERENCES assembly.vote_request_key(id);
CREATE INDEX vote_by_request_key ON assembly.vote (request_key_id) WHERE request_key_id IS NOT NULL;
