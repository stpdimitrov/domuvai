-- S-G2-02a — who is present at a sitting of an assembly: the register quorum is weighed from.
-- Rule: PM-GA-012 — "present in person or by proxy": a person present, in person or by video; whom they represent
-- by proxy is read from the proxies, not copied here.
-- A register line is of its assembly's own entrance: the pair is a key the line can point at.
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_of_its_entrance UNIQUE (id, entrance_id);

CREATE TABLE assembly.attendance (
  id                 uuid PRIMARY KEY,
  entrance_id        uuid NOT NULL,
  assembly_id        uuid NOT NULL,
  session_no         smallint NOT NULL CHECK (session_no >= 1),
  attendee_party_id  uuid NOT NULL REFERENCES registry.party(id),
  channel            text NOT NULL CHECK (channel IN ('IN_PERSON','VIDEO')),
  recorded_by        uuid NOT NULL REFERENCES registry.party(id),
  login_issuer       text NOT NULL CHECK (login_issuer ~ '\S'),
  login_subject      text NOT NULL CHECK (login_subject ~ '\S'),
  recorded_at        timestamptz NOT NULL,
  CONSTRAINT attendance_once_per_sitting UNIQUE (assembly_id, session_no, attendee_party_id),
  FOREIGN KEY (assembly_id, entrance_id) REFERENCES assembly.assembly (id, entrance_id),
  UNIQUE (id, assembly_id, entrance_id)
);
CREATE RULE attendance_no_update AS ON UPDATE TO assembly.attendance DO INSTEAD NOTHING;
CREATE RULE attendance_no_delete AS ON DELETE TO assembly.attendance DO INSTEAD NOTHING;
CREATE FUNCTION assembly.register_keeps_its_lines() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'the register of an assembly is evidence of who was present, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER attendance_no_truncate BEFORE TRUNCATE ON assembly.attendance
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.register_keeps_its_lines();

-- Rule: PM-GA-016 — a remote participant is reliably identified before they count: the verification is its own
-- record, of who verified, how and when. One per attendance; never rewritten.
CREATE TABLE assembly.identity_verification (
  attendance_id  uuid PRIMARY KEY,
  assembly_id    uuid NOT NULL,
  entrance_id    uuid NOT NULL,
  how            text NOT NULL CHECK (how ~ '\S'),
  verified_by    uuid NOT NULL REFERENCES registry.party(id),
  login_issuer   text NOT NULL CHECK (login_issuer ~ '\S'),
  login_subject  text NOT NULL CHECK (login_subject ~ '\S'),
  verified_at    timestamptz NOT NULL,
  FOREIGN KEY (attendance_id, assembly_id, entrance_id) REFERENCES assembly.attendance (id, assembly_id, entrance_id)
);
CREATE RULE identity_verification_no_update AS ON UPDATE TO assembly.identity_verification DO INSTEAD NOTHING;
CREATE RULE identity_verification_no_delete AS ON DELETE TO assembly.identity_verification DO INSTEAD NOTHING;
CREATE TRIGGER identity_verification_no_truncate BEFORE TRUNCATE ON assembly.identity_verification
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.register_keeps_its_lines();
