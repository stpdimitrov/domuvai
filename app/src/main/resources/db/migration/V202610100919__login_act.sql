-- Rule: PM-SEC-004 — every tie and untie of a login, and every refused attempt, with who did it and when (AUTH-04c).
-- A login says who a signed-in person is in the book, so changing one is recorded where it cannot be changed after.
-- The party is kept by value, not by reference: the entry outlives the tie. `by_*` is the login that asked — absent
-- only on a refusal. Insert-only for the application, as registry.book_access is: an UPDATE or a DELETE
-- changes nothing, a TRUNCATE is refused; the database's owner can still drop these guards.
CREATE TABLE identity_org.login_act (
  id             uuid PRIMARY KEY,
  act            text NOT NULL CHECK (act IN ('TIED','UNTIED','TIE_REFUSED','UNTIE_REFUSED')),
  login_issuer   text NOT NULL,
  login_subject  text NOT NULL,
  party_id       uuid,
  by_issuer      text,
  by_subject     text,
  rule_id        text NOT NULL CONSTRAINT login_act_rule_id CHECK (rule_id ~ '^PM-[A-Z]+-[0-9]{3}$'),
  at             timestamptz NOT NULL,
  CONSTRAINT login_act_by_is_whole CHECK ((by_issuer IS NULL) = (by_subject IS NULL)),
  CONSTRAINT login_act_done_by_somebody CHECK (act IN ('TIE_REFUSED','UNTIE_REFUSED') OR by_subject IS NOT NULL),
  CONSTRAINT login_act_done_names_a_party CHECK (act NOT IN ('TIED','UNTIED') OR party_id IS NOT NULL)
);
CREATE INDEX login_act_login ON identity_org.login_act (login_issuer, login_subject, at);
CREATE RULE login_act_no_update AS ON UPDATE TO identity_org.login_act DO INSTEAD NOTHING;
CREATE RULE login_act_no_delete AS ON DELETE TO identity_org.login_act DO INSTEAD NOTHING;
CREATE FUNCTION identity_org.login_act_keeps_its_entries() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'identity_org.login_act is the record of who was said to be whom, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER login_act_no_truncate BEFORE TRUNCATE ON identity_org.login_act
  FOR EACH STATEMENT EXECUTE FUNCTION identity_org.login_act_keeps_its_entries();
