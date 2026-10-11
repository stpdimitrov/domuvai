-- S-G2-01f — convening an assembly and recording its posting act say which sign-in did it, beside the party it was
-- tied to that day (PM-SEC-001): a login tied to one party and then to another leaves its own name on what it did.
ALTER TABLE assembly.assembly ADD COLUMN convened_login_issuer text;
ALTER TABLE assembly.assembly ADD COLUMN convened_login_subject text;
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_names_its_convening_login
  CHECK (convened_login_issuer IS NOT NULL AND convened_login_subject IS NOT NULL AND convened_login_issuer ~ '\S' AND convened_login_subject ~ '\S') NOT VALID;
ALTER TABLE assembly.notice_posting ADD COLUMN login_issuer text;
ALTER TABLE assembly.notice_posting ADD COLUMN login_subject text;
ALTER TABLE assembly.notice_posting ADD CONSTRAINT notice_posting_names_its_login
  CHECK (login_issuer IS NOT NULL AND login_subject IS NOT NULL AND login_issuer ~ '\S' AND login_subject ~ '\S') NOT VALID;
