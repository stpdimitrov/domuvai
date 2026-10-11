-- S-G2-01h — a proxy says who registered it: the principal themself, or whoever runs the assembly recording a
-- written authorisation they were handed — then with the document's SHA-256, what was handed over (PM-GA-009).
ALTER TABLE assembly.proxy ADD COLUMN recorded_by uuid REFERENCES registry.party(id);
ALTER TABLE assembly.proxy ADD COLUMN authorisation_hash text CHECK (authorisation_hash ~ '^[0-9a-f]{64}$');
-- A proxy appointed in person carries no document; one recorded for the principal always does.
ALTER TABLE assembly.proxy ADD CONSTRAINT proxy_recorded_with_its_authorisation
  CHECK (recorded_by IS NOT NULL AND (recorded_by = principal_party_id) = (authorisation_hash IS NULL)) NOT VALID;
