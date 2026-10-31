-- DEC-01. Rules: PM-FEE-012, PM-GA-022, PM-DOC-007, PM-SEC-004 — a decision the general assembly took on paper, as the
-- administrator transcribed it from its protocol (the owner's decision on #396, 2026-10-31). A table of its own, not
-- `decision`: there was no sitting here, and no tally or majority is claimed. The paper stays the original: where it
-- is kept and who keeps it are required. The login is who recorded it, null only with sign-in switched off
-- (DOMUVAI_AUTH=off), when nobody is asking. Never changed, never removed: a wrong record stays.
CREATE TABLE assembly.paper_decision (
  id               uuid PRIMARY KEY,
  entrance_id      uuid NOT NULL REFERENCES registry.entrance(id),
  decision_text    text NOT NULL CHECK (btrim(decision_text) <> ''),
  protocol_date    date NOT NULL,
  protocol_ref     text NOT NULL CHECK (btrim(protocol_ref) <> ''),
  archive_location text NOT NULL CHECK (btrim(archive_location) <> ''),
  custodian        text NOT NULL CHECK (btrim(custodian) <> ''),
  basis            text NOT NULL,
  basis_hash       text NOT NULL CHECK (basis_hash ~ '^[0-9a-f]{64}$'),
  law_version      text NOT NULL,
  engine_version   text NOT NULL,
  login_issuer     text,
  login_subject    text,
  recorded_at      timestamptz NOT NULL,
  CONSTRAINT paper_decision_login_whole CHECK ((login_issuer IS NULL) = (login_subject IS NULL))
);
CREATE INDEX paper_decision_of_entrance ON assembly.paper_decision (entrance_id, protocol_date DESC);

CREATE FUNCTION assembly.paper_decision_is_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.paper_decision: a record is added once; it is not changed, removed or emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER paper_decision_kept BEFORE UPDATE OR DELETE ON assembly.paper_decision
  FOR EACH ROW EXECUTE FUNCTION assembly.paper_decision_is_kept();
CREATE TRIGGER paper_decision_not_emptied BEFORE TRUNCATE ON assembly.paper_decision
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.paper_decision_is_kept();
