-- Rule: PM-ORG-003 — where the title deed does not state a unit's ideal parts, the registry may
-- derive them from the built-up area ratio; such a value is marked DERIVED so every screen that
-- weighs it (voting above all) can warn. Every unit registered so far was declared.
ALTER TABLE registry.unit ADD COLUMN ideal_parts_source text NOT NULL DEFAULT 'DECLARED';
ALTER TABLE registry.unit ADD CONSTRAINT unit_ideal_parts_source
  CHECK (ideal_parts_source IN ('DECLARED','DERIVED'));
