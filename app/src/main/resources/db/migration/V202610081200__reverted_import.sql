-- STAGE1-ADDENDUM §1 — an import the registry has reverted, remembered so that it is never adopted again: a commit
-- is delivered at least once, and one delivered again after the revert would otherwise find no row of the import
-- and adopt them all anew. Written in the revert's own savepoint, so a revert that was refused leaves no mark.
-- Insert-only.
CREATE TABLE registry.reverted_import (
  import_id    uuid PRIMARY KEY,
  reverted_at  timestamptz NOT NULL DEFAULT now()
);
CREATE RULE reverted_import_no_update AS ON UPDATE TO registry.reverted_import DO INSTEAD NOTHING;
CREATE RULE reverted_import_no_delete AS ON DELETE TO registry.reverted_import DO INSTEAD NOTHING;
