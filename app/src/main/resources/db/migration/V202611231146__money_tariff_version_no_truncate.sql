-- Rule: PM-SEC-004 — a tariff version is the decision behind every bill a run raises (PM-FEE-012); UPDATE and DELETE are
-- already refused, and a TRUNCATE would empty the table past them, so it is refused too.
CREATE FUNCTION money.tariff_versions_are_kept() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'money.tariff_version holds the decisions behind bills, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER tariff_version_no_truncate BEFORE TRUNCATE ON money.tariff_version
  FOR EACH STATEMENT EXECUTE FUNCTION money.tariff_versions_are_kept();
