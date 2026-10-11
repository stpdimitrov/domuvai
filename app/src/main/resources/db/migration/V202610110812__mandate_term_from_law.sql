-- Rule: PM-SYS-001, PM-SYS-002, PM-GOV-004 — how long a mandate may run is the law module's to say, as a dated value read
-- on the mandate's first day (AUD-01). The baseline wrote the figure into this table as a check; a number frozen in the
-- schema cannot be dated and would overrule the law's own on any later day, so it goes. The term is enforced where a
-- mandate is recorded. The table still refuses a mandate whose last day is not after its first (management_mandate_check).
ALTER TABLE identity_org.management_mandate DROP CONSTRAINT management_mandate_check1;
