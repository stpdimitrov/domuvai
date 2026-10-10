-- S-G2-01d — the owners' petition to convene, and the assembly owners convene on it.

-- Rule: PM-GA-003 — owners demand an assembly; their signatures accumulate.
CREATE TABLE assembly.petition (
  id           uuid PRIMARY KEY,
  entrance_id  uuid NOT NULL REFERENCES registry.entrance(id),
  opened_by    uuid NOT NULL REFERENCES registry.party(id),
  subject      text NOT NULL CHECK (subject ~ '\S'),
  opened_at    timestamptz NOT NULL,
  UNIQUE (id, entrance_id)
);

CREATE TABLE assembly.petition_signature (
  id           uuid PRIMARY KEY,
  entrance_id  uuid NOT NULL,
  petition_id  uuid NOT NULL,
  party_id     uuid NOT NULL REFERENCES registry.party(id),
  signed_at    timestamptz NOT NULL,
  FOREIGN KEY (petition_id, entrance_id) REFERENCES assembly.petition (id, entrance_id),
  CONSTRAINT petition_signed_once UNIQUE (petition_id, party_id)
);
-- A signature is evidence of the demand: it is not rewritten, removed or emptied.
CREATE RULE petition_signature_no_update AS ON UPDATE TO assembly.petition_signature DO INSTEAD NOTHING;
CREATE RULE petition_signature_no_delete AS ON DELETE TO assembly.petition_signature DO INSTEAD NOTHING;
CREATE FUNCTION assembly.petition_signature_keeps_its_rows() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.petition_signature is the record of who demanded an assembly, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER petition_signature_no_truncate BEFORE TRUNCATE ON assembly.petition_signature
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.petition_signature_keeps_its_rows();

-- Rule: PM-GA-003 — owners convene on their petition: the assembly points at it, once, and keeps what
-- unlocked it — the share held, the threshold, the day, and the versions that computed it — with the
-- convenor's statement that the demand was not met.
ALTER TABLE assembly.assembly ADD COLUMN petition_id uuid REFERENCES assembly.petition(id);
ALTER TABLE assembly.assembly ADD COLUMN demand_unmet_note text;
ALTER TABLE assembly.assembly ADD COLUMN petition_held_pct numeric(14,10);
ALTER TABLE assembly.assembly ADD COLUMN petition_threshold_pct numeric(14,10);
ALTER TABLE assembly.assembly ADD COLUMN petition_weighed_on date;
ALTER TABLE assembly.assembly ADD COLUMN law_version text;
ALTER TABLE assembly.assembly ADD COLUMN engine_version text;
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_one_per_petition UNIQUE (petition_id);
-- Rule: PM-GA-002 — the offices that convene; and, on a petition only, the owners.
ALTER TABLE assembly.assembly DROP CONSTRAINT assembly_convened_as_check;
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_convened_as_check CHECK (convened_as IN ('MB','BM','CTL','OWNERS'));
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_owners_convene_on_petition CHECK (
  (convened_as = 'OWNERS') = (petition_id IS NOT NULL)
  AND (petition_id IS NULL) = (demand_unmet_note IS NULL)
  AND (demand_unmet_note IS NULL OR demand_unmet_note ~ '\S')
  AND (petition_id IS NULL OR (petition_held_pct IS NOT NULL AND petition_threshold_pct IS NOT NULL AND petition_weighed_on IS NOT NULL
       AND law_version IS NOT NULL AND engine_version IS NOT NULL AND petition_held_pct >= petition_threshold_pct))
) NOT VALID;
