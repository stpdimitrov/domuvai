-- S-G2-01b — the posting act: the only thing that makes an assembly NOTICED.

-- Rule: PM-GA-007 — a certifying act signed by the convenor and one other person.
-- Rule: PM-GA-004 — with the photograph that shows the notice posted.
-- Rule: PM-GA-006 — and what the notice stated: date and hour, place, the full agenda.
CREATE TABLE assembly.notice_posting (
  id                   uuid PRIMARY KEY,
  entrance_id          uuid NOT NULL REFERENCES registry.entrance(id),
  assembly_id          uuid NOT NULL REFERENCES assembly.assembly(id),
  posted_at            timestamptz NOT NULL,
  convenor_party_id    uuid NOT NULL REFERENCES registry.party(id),
  witness_party_id     uuid NOT NULL REFERENCES registry.party(id),
  photo_hash           text NOT NULL CHECK (photo_hash ~ '^[0-9a-f]{64}$'),
  stated_scheduled_at  timestamptz NOT NULL,
  stated_place         text NOT NULL,
  stated_agenda        text NOT NULL CHECK (stated_agenda ~ '\S'),
  recorded_at          timestamptz NOT NULL,
  CONSTRAINT notice_posting_two_signatories CHECK (witness_party_id <> convenor_party_id),
  CONSTRAINT notice_posting_not_in_advance CHECK (posted_at <= recorded_at)
);
-- An act is evidence: a voided notice keeps its act, and a new notice gets a new one.
CREATE RULE notice_posting_no_update AS ON UPDATE TO assembly.notice_posting DO INSTEAD NOTHING;
CREATE RULE notice_posting_no_delete AS ON DELETE TO assembly.notice_posting DO INSTEAD NOTHING;

-- Rule: PM-GA-007 — NOTICED means a posting time; a draft has none.
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_noticed_by_posting
  CHECK ((status <> 'NOTICED' OR notice_posted_at IS NOT NULL) AND (status <> 'DRAFT' OR notice_posted_at IS NULL)) NOT VALID;
