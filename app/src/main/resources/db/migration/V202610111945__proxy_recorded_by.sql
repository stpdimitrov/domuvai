-- S-G2-01h — a proxy says who registered it: the principal themself, or whoever runs the assembly recording a
-- written authorisation they were handed — then with the document's SHA-256, what was handed over (PM-GA-009).
ALTER TABLE assembly.proxy ADD COLUMN recorded_by uuid REFERENCES registry.party(id);
ALTER TABLE assembly.proxy ADD COLUMN authorisation_hash text CHECK (authorisation_hash ~ '^[0-9a-f]{64}$');
-- A proxy from before this, registered by its principal signed in, was recorded by that principal: say so, so the
-- row can be told from a recorded one and can still be changed later. One with no sign-in is left as it is.
UPDATE assembly.proxy SET recorded_by = principal_party_id WHERE recorded_by IS NULL AND login_subject IS NOT NULL;
-- A proxy appointed in person carries no document; one recorded for the principal always does.
ALTER TABLE assembly.proxy ADD CONSTRAINT proxy_recorded_with_its_authorisation
  CHECK (recorded_by IS NOT NULL AND (recorded_by = principal_party_id) = (authorisation_hash IS NULL)) NOT VALID;
