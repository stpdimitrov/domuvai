-- S-G2-02a — who is present at a sitting of an assembly: the register quorum is weighed from.
-- Rule: PM-GA-012 — "present in person or by proxy": a person present, in person or by video; whom they represent
-- by proxy is read from the proxies, not copied here.
CREATE TABLE assembly.attendance (
  id                 uuid PRIMARY KEY,
  entrance_id        uuid NOT NULL REFERENCES registry.entrance(id),
  assembly_id        uuid NOT NULL REFERENCES assembly.assembly(id),
  session_no         smallint NOT NULL CHECK (session_no >= 1),
  attendee_party_id  uuid NOT NULL REFERENCES registry.party(id),
  channel            text NOT NULL CHECK (channel IN ('IN_PERSON','VIDEO')),
  recorded_by        uuid NOT NULL REFERENCES registry.party(id),
  login_issuer       text NOT NULL CHECK (login_issuer ~ '\S'),
  login_subject      text NOT NULL CHECK (login_subject ~ '\S'),
  recorded_at        timestamptz NOT NULL,
  CONSTRAINT attendance_once_per_sitting UNIQUE (assembly_id, session_no, attendee_party_id),
  UNIQUE (id, assembly_id)
);
CREATE RULE attendance_no_update AS ON UPDATE TO assembly.attendance DO INSTEAD NOTHING;
CREATE RULE attendance_no_delete AS ON DELETE TO assembly.attendance DO INSTEAD NOTHING;

-- Rule: PM-GA-016 — a remote participant is reliably identified before they count: the verification is its own
-- record, of who verified, how and when. One per attendance; never rewritten.
CREATE TABLE assembly.identity_verification (
  attendance_id  uuid PRIMARY KEY,
  assembly_id    uuid NOT NULL,
  entrance_id    uuid NOT NULL REFERENCES registry.entrance(id),
  how            text NOT NULL CHECK (how ~ '\S'),
  verified_by    uuid NOT NULL REFERENCES registry.party(id),
  login_issuer   text NOT NULL CHECK (login_issuer ~ '\S'),
  login_subject  text NOT NULL CHECK (login_subject ~ '\S'),
  verified_at    timestamptz NOT NULL,
  FOREIGN KEY (attendance_id, assembly_id) REFERENCES assembly.attendance (id, assembly_id)
);
CREATE RULE identity_verification_no_update AS ON UPDATE TO assembly.identity_verification DO INSTEAD NOTHING;
CREATE RULE identity_verification_no_delete AS ON DELETE TO assembly.identity_verification DO INSTEAD NOTHING;
