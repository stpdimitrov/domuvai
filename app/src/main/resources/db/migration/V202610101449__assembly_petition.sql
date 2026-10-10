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
-- What was signed is not rewritten under its signatures.
CREATE RULE petition_no_update AS ON UPDATE TO assembly.petition DO INSTEAD NOTHING;
CREATE RULE petition_no_delete AS ON DELETE TO assembly.petition DO INSTEAD NOTHING;

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

-- Rule: PM-GA-003 — what unlocked a petition, kept as it was that day: the share its signatories owned, the
-- threshold with the dated constant it was and whether counsel had confirmed it, the convenor's statement that
-- the demand was not met, and the catalogue and engine versions that computed it (PM-LAW-002). One per petition.
CREATE TABLE assembly.petition_unlock (
  petition_id         uuid PRIMARY KEY,
  entrance_id         uuid NOT NULL,
  convened_by         uuid NOT NULL REFERENCES registry.party(id),
  demand_unmet_note   text NOT NULL CHECK (demand_unmet_note ~ '\S'),
  weighed_on          date NOT NULL,
  held_pct            numeric(14,10) NOT NULL,
  threshold_pct       numeric(14,10) NOT NULL,
  threshold_constant  text NOT NULL,
  threshold_verified  boolean NOT NULL,
  law_version         text NOT NULL,
  engine_version      text NOT NULL,
  recorded_at         timestamptz NOT NULL,
  FOREIGN KEY (petition_id, entrance_id) REFERENCES assembly.petition (id, entrance_id),
  UNIQUE (petition_id, entrance_id),
  CONSTRAINT petition_unlock_at_threshold CHECK (held_pct >= threshold_pct)
);
CREATE RULE petition_unlock_no_update AS ON UPDATE TO assembly.petition_unlock DO INSTEAD NOTHING;
CREATE RULE petition_unlock_no_delete AS ON DELETE TO assembly.petition_unlock DO INSTEAD NOTHING;

-- The owners' assembly points at the unlock record of a petition of its own entrance, once.
ALTER TABLE assembly.assembly ADD COLUMN petition_id uuid;
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_on_an_unlocked_petition
  FOREIGN KEY (petition_id, entrance_id) REFERENCES assembly.petition_unlock (petition_id, entrance_id);
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_one_per_petition UNIQUE (petition_id);
-- Rule: PM-GA-002 — the offices that convene; and, on a petition only, the owners.
ALTER TABLE assembly.assembly DROP CONSTRAINT assembly_convened_as_check;
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_convened_as_check CHECK (convened_as IN ('MB','BM','CTL','OWNERS'));
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_owners_convene_on_petition
  CHECK ((convened_as = 'OWNERS') = (petition_id IS NOT NULL));
