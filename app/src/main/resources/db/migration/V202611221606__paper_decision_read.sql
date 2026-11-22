-- DEC-02. Rule: PM-SEC-004 — a paper decision's custodian is a person's name, so every read that serves it is entered:
-- who asked (the party the sign-in is tied to, and the login), which entrance, when, and how it was decided — SERVED
-- citing the rule the policy gave, or OPEN (sign-in switched off: given to nobody, and entered so). A read with the name
-- withheld served no personal data and is not entered (the owner's D2a, 2026-11-22), nor is a refused one.
-- Insert-only for the application: an UPDATE or a DELETE changes nothing, and a TRUNCATE is refused.
CREATE TABLE assembly.paper_decision_read (
  id              uuid PRIMARY KEY,
  entrance_id     uuid NOT NULL,
  actor           uuid REFERENCES registry.party(id),
  login_issuer    text,
  login_subject   text,
  at              timestamptz NOT NULL,
  outcome         text NOT NULL CHECK (outcome IN ('SERVED','OPEN')),
  rule_id         text CONSTRAINT paper_decision_read_rule_id CHECK (rule_id ~ '^PM-[A-Z]+-[0-9]{3}$'),
  CONSTRAINT paper_decision_read_served_has_a_reader CHECK (outcome <> 'SERVED' OR ((actor IS NOT NULL OR login_subject IS NOT NULL) AND rule_id IS NOT NULL)),
  CONSTRAINT paper_decision_read_open_is_nobody CHECK (outcome <> 'OPEN' OR (actor IS NULL AND login_subject IS NULL))
);
CREATE INDEX paper_decision_read_entrance ON assembly.paper_decision_read (entrance_id, at);
CREATE RULE paper_decision_read_no_update AS ON UPDATE TO assembly.paper_decision_read DO INSTEAD NOTHING;
CREATE RULE paper_decision_read_no_delete AS ON DELETE TO assembly.paper_decision_read DO INSTEAD NOTHING;
CREATE FUNCTION assembly.paper_decision_read_keeps_its_entries() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'assembly.paper_decision_read is the record of who read a paper decision''s custodian, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER paper_decision_read_no_truncate BEFORE TRUNCATE ON assembly.paper_decision_read
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.paper_decision_read_keeps_its_entries();
