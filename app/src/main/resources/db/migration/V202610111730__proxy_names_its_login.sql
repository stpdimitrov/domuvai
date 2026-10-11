-- S-G2-01g — a proxy says which sign-in registered it, beside the principal it was tied to that day (PM-SEC-001).
ALTER TABLE assembly.proxy ADD COLUMN login_issuer text;
ALTER TABLE assembly.proxy ADD COLUMN login_subject text;
ALTER TABLE assembly.proxy ADD CONSTRAINT proxy_names_its_login
  CHECK (login_issuer IS NOT NULL AND login_subject IS NOT NULL AND login_issuer ~ '\S' AND login_subject ~ '\S') NOT VALID;
