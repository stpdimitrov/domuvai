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
  co_signatory_party_id     uuid NOT NULL REFERENCES registry.party(id),
  photo_hash           text NOT NULL CHECK (photo_hash ~ '^[0-9a-f]{64}$'),
  stated_scheduled_at  timestamptz NOT NULL,
  stated_place         text NOT NULL,
  stated_agenda        text NOT NULL CHECK (stated_agenda ~ '\S'),
  recorded_at          timestamptz NOT NULL,
  CONSTRAINT notice_posting_two_signatories CHECK (co_signatory_party_id <> convenor_party_id),
  CONSTRAINT notice_posting_not_in_advance CHECK (posted_at <= recorded_at),
  UNIQUE (id, assembly_id)
);
-- An act is evidence: a voided notice keeps its act, and a new notice gets a new one.
CREATE RULE notice_posting_no_update AS ON UPDATE TO assembly.notice_posting DO INSTEAD NOTHING;
CREATE RULE notice_posting_no_delete AS ON DELETE TO assembly.notice_posting DO INSTEAD NOTHING;
CREATE FUNCTION assembly.notice_posting_keeps_its_acts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.notice_posting is the record of the posting acts, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER notice_posting_no_truncate BEFORE TRUNCATE ON assembly.notice_posting
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.notice_posting_keeps_its_acts();

-- Rule: PM-GA-007 — an assembly past DRAFT points at its own posting act, and a draft at none: a posting
-- time alone is not an act.
ALTER TABLE assembly.assembly ADD COLUMN notice_posting_id uuid;
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_notice_posting_is_its_own
  FOREIGN KEY (notice_posting_id, id) REFERENCES assembly.notice_posting (id, assembly_id);
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_noticed_by_posting
  CHECK ((status = 'DRAFT') = (notice_posting_id IS NULL) AND (notice_posting_id IS NULL) = (notice_posted_at IS NULL)) NOT VALID;

-- Rule: PM-GA-006 — when what a notice must state last changed: the date, the hour, the place or the agenda.
-- A notice posted before that moment stated something else.
ALTER TABLE assembly.assembly ADD COLUMN notice_content_changed_at timestamptz;
