-- S-G2-02m. Rule: PM-VOTE-013, PM-GA-017 — an eligible item's tally, reopened after day 7 with the absent owners'
-- ballots merged in: a decision of its own that supersedes the provisional one (once), with its own basis. The absent
-- owners' parts add to the represented share (the owner's, 2026-10-25). Never edited: an UPDATE or a DELETE changes
-- nothing, and a TRUNCATE is refused.
CREATE TABLE assembly.merged_decision (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  assembly_id      uuid NOT NULL REFERENCES assembly.assembly(id),
  agenda_item_id   uuid NOT NULL REFERENCES assembly.agenda_item(id),
  supersedes       uuid NOT NULL UNIQUE REFERENCES assembly.decision(id),
  represented      numeric NOT NULL CHECK (represented > 0 AND represented <= 100),
  tally_for        numeric NOT NULL CHECK (tally_for >= 0 AND tally_for <= 100),
  tally_against    numeric NOT NULL CHECK (tally_against >= 0 AND tally_against <= 100),
  tally_abstain    numeric NOT NULL CHECK (tally_abstain >= 0 AND tally_abstain <= 100),
  outcome          text NOT NULL CHECK (outcome IN ('PASSED','NOT_PASSED')),
  basis            text NOT NULL,
  basis_hash       text NOT NULL CHECK (basis_hash ~ '^[0-9a-f]{64}$'),
  law_version      text NOT NULL,
  engine_version   text NOT NULL,
  merged_at        timestamptz NOT NULL,
  merged_by        uuid NOT NULL REFERENCES registry.party(id),
  login_issuer     text NOT NULL CHECK (login_issuer ~ '\S'),
  login_subject    text NOT NULL CHECK (login_subject ~ '\S')
);
CREATE RULE merged_decision_no_update AS ON UPDATE TO assembly.merged_decision DO INSTEAD NOTHING;
CREATE RULE merged_decision_no_delete AS ON DELETE TO assembly.merged_decision DO INSTEAD NOTHING;
CREATE FUNCTION assembly.merged_decision_keeps_its_rows() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.merged_decision holds decisions, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER merged_decision_no_truncate BEFORE TRUNCATE ON assembly.merged_decision
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.merged_decision_keeps_its_rows();
