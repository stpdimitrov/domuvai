-- S-G2-02b — a sitting of an assembly is the record of one weighing of quorum: what was represented, against what
-- threshold, on what day, from which lines of the register — and whether the sitting opened on it.
-- `assembly.session` was made by the baseline and has never been written: its new columns are required from the start.

-- Rule: PM-GA-012 — the share represented is an exact sum of ideal parts by title share, not a rounded figure.
ALTER TABLE assembly.session ALTER COLUMN represented TYPE numeric(14,10);
ALTER TABLE assembly.session ALTER COLUMN represented SET NOT NULL;
ALTER TABLE assembly.session ALTER COLUMN quorum_met SET NOT NULL;
ALTER TABLE assembly.session ADD COLUMN weighed_at timestamptz NOT NULL;
ALTER TABLE assembly.session ADD COLUMN weighed_on date NOT NULL;
ALTER TABLE assembly.session ADD COLUMN threshold_pct numeric(14,10) NOT NULL;
-- which dated constant the threshold was, and whether counsel had confirmed it that day
ALTER TABLE assembly.session ADD COLUMN threshold_constant text NOT NULL;
ALTER TABLE assembly.session ADD COLUMN threshold_verified boolean NOT NULL;
-- what was weighed — each owner represented, each unit, the ideal parts counted — and its hash (ADR-001)
ALTER TABLE assembly.session ADD COLUMN basis text NOT NULL;
ALTER TABLE assembly.session ADD COLUMN basis_hash text NOT NULL CHECK (basis_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE assembly.session ADD COLUMN law_version text NOT NULL;
ALTER TABLE assembly.session ADD COLUMN engine_version text NOT NULL;
ALTER TABLE assembly.session ADD COLUMN weighed_by uuid NOT NULL REFERENCES registry.party(id);
ALTER TABLE assembly.session ADD COLUMN login_issuer text NOT NULL CHECK (login_issuer ~ '\S');
ALTER TABLE assembly.session ADD COLUMN login_subject text NOT NULL CHECK (login_subject ~ '\S');
-- A sitting opens exactly when its quorum was met, and its weighing says so of itself.
ALTER TABLE assembly.session ADD CONSTRAINT session_opens_on_its_quorum
  CHECK (quorum_met = (represented >= threshold_pct) AND quorum_met = (opened_at IS NOT NULL));
ALTER TABLE assembly.session ADD CONSTRAINT session_of_its_entrance
  FOREIGN KEY (assembly_id, entrance_id) REFERENCES assembly.assembly (id, entrance_id);
-- A weighing is evidence: it is not rewritten, removed or emptied.
CREATE RULE session_no_update AS ON UPDATE TO assembly.session DO INSTEAD NOTHING;
CREATE RULE session_no_delete AS ON DELETE TO assembly.session DO INSTEAD NOTHING;
CREATE TRIGGER session_no_truncate BEFORE TRUNCATE ON assembly.session
  FOR EACH STATEMENT EXECUTE FUNCTION assembly.register_keeps_its_lines();
