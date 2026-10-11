-- S-G2-01g — a proxy says which sign-in registered it, beside the principal it was tied to that day (PM-SEC-001).
-- A row from before this has none: its principal was whatever a request said, and it is not to be counted by
-- anything that reads proxies. No deployment holds real data, so none is expected; the check binds every new row.
ALTER TABLE assembly.proxy ADD COLUMN login_issuer text;
ALTER TABLE assembly.proxy ADD COLUMN login_subject text;
ALTER TABLE assembly.proxy ADD CONSTRAINT proxy_names_its_login
  CHECK (login_issuer IS NOT NULL AND login_subject IS NOT NULL AND login_issuer ~ '\S' AND login_subject ~ '\S') NOT VALID;
