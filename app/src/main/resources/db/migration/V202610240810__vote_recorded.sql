-- S-G2-02f: a vote as it is recorded at a sitting. No code wrote assembly.vote before this, so it is empty
-- wherever this runs: the NOT NULL columns below need no default.

-- Rule: PM-ORG-005 — a co-owner's weight is their title share of the unit's ideal parts, kept exactly: the
-- domain's four decimals would round a share of 0.333333 of a unit.
ALTER TABLE assembly.vote ALTER COLUMN weight TYPE numeric;
ALTER TABLE assembly.vote ADD CONSTRAINT vote_weight_a_share_of_the_whole CHECK (weight > 0 AND weight <= 100);

-- The assembly and the sitting it was cast at, and who recorded it, with the sign-in they recorded it under.
ALTER TABLE assembly.vote ADD COLUMN assembly_id uuid NOT NULL REFERENCES assembly.assembly(id);
ALTER TABLE assembly.vote ADD COLUMN session_no smallint NOT NULL CHECK (session_no >= 1);
ALTER TABLE assembly.vote ADD COLUMN recorded_by uuid NOT NULL REFERENCES registry.party(id);
ALTER TABLE assembly.vote ADD COLUMN login_issuer text NOT NULL;
ALTER TABLE assembly.vote ADD COLUMN login_subject text NOT NULL;

-- Rule: PM-VOTE-014 — a correction is a new row that reverses the one it corrects, and says why; nothing is edited.
ALTER TABLE assembly.vote ADD COLUMN correction text;
ALTER TABLE assembly.vote ADD CONSTRAINT vote_correction_says_why
  CHECK ((reverses IS NULL) = (correction IS NULL) AND (correction IS NULL OR length(btrim(correction)) > 0));

-- Rule: PM-ORG-005 — one first vote per owner per unit per item; a correction replaces a vote, once.
ALTER TABLE assembly.vote DROP CONSTRAINT vote_agenda_item_id_party_id_unit_id_key;
CREATE UNIQUE INDEX vote_first_once ON assembly.vote (agenda_item_id, party_id, unit_id) WHERE reverses IS NULL;
CREATE UNIQUE INDEX vote_reversed_once ON assembly.vote (reverses) WHERE reverses IS NOT NULL;
