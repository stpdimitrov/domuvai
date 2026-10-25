-- S-G2-02l. Rule: PM-GA-017 — an item of the agenda accepts absentee ballots only when tagged so as it is drafted.
ALTER TABLE assembly.agenda_item ADD COLUMN absentee_eligible boolean NOT NULL DEFAULT false;

-- Rule: PM-GA-017, PM-GA-018 — an absent owner's ballot on an eligible item, after its sitting and by day 7: a
-- handwritten-signed declaration, entered by whoever runs the assembly with the reference and the SHA-256 of its scan
-- (a QES waits for its provider). One per owner and unit per item; never edited: an UPDATE or a DELETE changes
-- nothing, and a TRUNCATE is refused.
CREATE TABLE assembly.absentee_ballot (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  assembly_id      uuid NOT NULL REFERENCES assembly.assembly(id),
  agenda_item_id   uuid NOT NULL REFERENCES assembly.agenda_item(id),
  party_id         uuid NOT NULL REFERENCES registry.party(id),
  unit_id          uuid NOT NULL,
  weight           numeric NOT NULL CHECK (weight > 0),
  choice           text NOT NULL CHECK (choice IN ('FOR','AGAINST','ABSTAIN')),
  signature        text NOT NULL CHECK (signature IN ('HANDWRITTEN')),
  document_ref     text NOT NULL CHECK (document_ref ~ '\S'),
  document_sha256  text NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
  received_on      date NOT NULL,
  recorded_by      uuid NOT NULL REFERENCES registry.party(id),
  login_issuer     text NOT NULL CHECK (login_issuer ~ '\S'),
  login_subject    text NOT NULL CHECK (login_subject ~ '\S'),
  recorded_at      timestamptz NOT NULL,
  CONSTRAINT absentee_ballot_once UNIQUE (agenda_item_id, party_id, unit_id)
);
CREATE RULE absentee_ballot_no_update AS ON UPDATE TO assembly.absentee_ballot DO INSTEAD NOTHING;
CREATE RULE absentee_ballot_no_delete AS ON DELETE TO assembly.absentee_ballot DO INSTEAD NOTHING;
CREATE FUNCTION assembly.absentee_ballot_keeps_its_ballots() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.absentee_ballot holds signed ballots, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER absentee_ballot_no_truncate BEFORE TRUNCATE ON assembly.absentee_ballot
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.absentee_ballot_keeps_its_ballots();
