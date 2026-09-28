-- S-41b: a commit now adopts each unit's household and owner as well as the unit, so every row it
-- creates carries its import_id (STAGE1-ADDENDUM §1) and revert removes exactly what the import
-- added — these rows first, then the units they point at. A provenance stamp like unit.import_id:
-- matched on revert, never cascaded, so a record added later is never dropped with the import.
ALTER TABLE registry.household_member ADD COLUMN import_id uuid;
ALTER TABLE registry.party ADD COLUMN import_id uuid;
ALTER TABLE registry.title ADD COLUMN import_id uuid;
