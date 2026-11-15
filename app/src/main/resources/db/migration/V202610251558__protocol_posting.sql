-- S-G2-02k. Rule: PM-GA-023 — the day an assembly's protocol was posted under чл. 16 ал. 7: the owners received its
-- decisions that day, and the 30 days to seek annulment run from it. Entered once per assembly, by whoever runs it
-- (the sign-in's party and login), never edited: an UPDATE or a DELETE changes nothing, and a TRUNCATE is refused.
CREATE TABLE assembly.protocol_posting (
  id             uuid PRIMARY KEY,
  entrance_id    uuid NOT NULL REFERENCES registry.entrance(id),
  assembly_id    uuid NOT NULL REFERENCES assembly.assembly(id),
  posted_on      date NOT NULL,
  posted_by      uuid NOT NULL REFERENCES registry.party(id),
  login_issuer   text NOT NULL,
  login_subject  text NOT NULL,
  recorded_at    timestamptz NOT NULL,
  CONSTRAINT protocol_posting_once_per_assembly UNIQUE (assembly_id)
);
CREATE RULE protocol_posting_no_update AS ON UPDATE TO assembly.protocol_posting DO INSTEAD NOTHING;
CREATE RULE protocol_posting_no_delete AS ON DELETE TO assembly.protocol_posting DO INSTEAD NOTHING;
CREATE FUNCTION assembly.protocol_posting_keeps_its_acts() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.protocol_posting starts the time to seek annulment, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER protocol_posting_no_truncate BEFORE TRUNCATE ON assembly.protocol_posting
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.protocol_posting_keeps_its_acts();
