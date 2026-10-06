-- DEVBRIEF §8 — every write endpoint takes an Idempotency-Key. The key a fund write came with, kept per entrance:
-- which operation it was, a hash of what was asked, and the record it made (the disbursement, or the handover
-- statement). A repeat with the same key and the same request is answered from that record and writes nothing; the
-- same key for anything else is refused. Insert-only.
CREATE TABLE money.fund_request_key (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  idempotency_key  text NOT NULL CONSTRAINT fund_request_key_not_blank CHECK (idempotency_key ~ '\S'),
  operation        text NOT NULL CHECK (operation IN ('COMMIT','PAY','CANCEL','HANDOVER')),
  request_hash     text NOT NULL,
  result_id        uuid NOT NULL,
  UNIQUE (entrance_id, idempotency_key)
);
CREATE RULE fund_request_key_no_update AS ON UPDATE TO money.fund_request_key DO INSTEAD NOTHING;
CREATE RULE fund_request_key_no_delete AS ON DELETE TO money.fund_request_key DO INSTEAD NOTHING;
