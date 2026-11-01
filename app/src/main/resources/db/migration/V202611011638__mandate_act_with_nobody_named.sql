-- Rule: PM-SEC-001, PM-SEC-004 — with sign-in switched off in so many words nobody is asking, so a mandate is recorded
-- with nobody to name (#429), as a paper decision is. The entry is still written, in the act's own transaction: it says
-- what was done and when, and that nobody was signed in. A login is named whole or not at all, and a refusal always
-- names one: a refused attempt with nobody signed in writes nothing.
ALTER TABLE identity_org.mandate_act ALTER COLUMN by_issuer DROP NOT NULL;
ALTER TABLE identity_org.mandate_act ALTER COLUMN by_subject DROP NOT NULL;
ALTER TABLE identity_org.mandate_act ADD CONSTRAINT mandate_act_by_is_whole CHECK ((by_issuer IS NULL) = (by_subject IS NULL));
ALTER TABLE identity_org.mandate_act ADD CONSTRAINT mandate_act_refusal_names_its_login CHECK (act NOT IN ('RECORD_REFUSED','END_REFUSED') OR by_subject IS NOT NULL);
