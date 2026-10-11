-- S-G2-01e — a signature and an unlock say which sign-in made them, beside the party it was tied to that day:
-- a login tied to one party and then to another leaves its own name on what it signed (PM-SEC-001).
ALTER TABLE assembly.petition_signature ADD COLUMN login_issuer text;
ALTER TABLE assembly.petition_signature ADD COLUMN login_subject text;
ALTER TABLE assembly.petition_signature ADD CONSTRAINT petition_signature_names_its_login
  CHECK (login_issuer ~ '\S' AND login_subject ~ '\S' AND login_issuer IS NOT NULL AND login_subject IS NOT NULL) NOT VALID;
ALTER TABLE assembly.petition_unlock ADD COLUMN login_issuer text;
ALTER TABLE assembly.petition_unlock ADD COLUMN login_subject text;
ALTER TABLE assembly.petition_unlock ADD CONSTRAINT petition_unlock_names_its_login
  CHECK (login_issuer ~ '\S' AND login_subject ~ '\S' AND login_issuer IS NOT NULL AND login_subject IS NOT NULL) NOT VALID;
