-- S-G2-02j. Rule: PM-SEC-004 — every read of how each owner voted on an item, one row per attempt: who asked (the
-- party the sign-in is tied to, and the login), which item of which entrance, when, and how it was decided — SERVED,
-- REFUSED citing its rule, or OPEN (sign-in switched off: given to nobody, and entered so). The entrance and item are
-- what was asked: a refused reader may ask under any id, so neither is a reference — the refusal is still entered.
-- Insert-only for the application: an UPDATE or a DELETE changes nothing, and a TRUNCATE is refused.
CREATE TABLE assembly.vote_read (
  id              uuid PRIMARY KEY,
  entrance_id     uuid NOT NULL,
  agenda_item_id  uuid NOT NULL,
  actor           uuid REFERENCES registry.party(id),
  login_issuer    text,
  login_subject   text,
  at              timestamptz NOT NULL,
  outcome         text NOT NULL CHECK (outcome IN ('SERVED','REFUSED','OPEN')),
  rule_id         text CONSTRAINT vote_read_rule_id CHECK (rule_id ~ '^PM-[A-Z]+-[0-9]{3}$'),
  CONSTRAINT vote_read_served_has_a_reader CHECK (outcome <> 'SERVED' OR actor IS NOT NULL OR login_subject IS NOT NULL),
  CONSTRAINT vote_read_refusal_cites_its_rule CHECK (outcome <> 'REFUSED' OR rule_id IS NOT NULL),
  CONSTRAINT vote_read_open_is_nobody CHECK (outcome <> 'OPEN' OR (actor IS NULL AND login_subject IS NULL))
);
CREATE INDEX vote_read_entrance ON assembly.vote_read (entrance_id, at);
CREATE RULE vote_read_no_update AS ON UPDATE TO assembly.vote_read DO INSTEAD NOTHING;
CREATE RULE vote_read_no_delete AS ON DELETE TO assembly.vote_read DO INSTEAD NOTHING;
CREATE FUNCTION assembly.vote_read_keeps_its_entries() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.vote_read is the record of who read how owners voted, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER vote_read_no_truncate BEFORE TRUNCATE ON assembly.vote_read
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.vote_read_keeps_its_entries();
