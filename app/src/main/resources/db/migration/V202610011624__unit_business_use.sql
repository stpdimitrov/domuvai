-- Rule: PM-FEE-010 — a unit used for business or professional activity. Reached through the common parts it pays
-- the assembly's multiple; with a separate street entrance (Rule: PM-ORG-009, separate_entrance) it pays the
-- standard rate. They are two facts about a unit, and neither implies the other.
ALTER TABLE registry.unit ADD COLUMN business_use boolean NOT NULL DEFAULT false;

-- separate_entrance has meant "business use through a separate street entrance" since V1, so a unit already
-- flagged with it is a business unit (the owner's yes, #91, 2026-10-01).
UPDATE registry.unit SET business_use = true WHERE separate_entrance;
