-- STAGE1-ADDENDUM §1 — an import the registry has reverted, remembered so that it is never adopted again: a commit
-- is delivered at least once, and one delivered again after the revert would otherwise find no row of the import
-- and adopt them all anew. Written in the revert's own savepoint, so a revert that was refused leaves no mark.
-- The application can only add to it: an UPDATE or a DELETE changes nothing, and a TRUNCATE is refused.
CREATE TABLE registry.reverted_import (
  import_id    uuid PRIMARY KEY,
  reverted_at  timestamptz NOT NULL DEFAULT now()
);
CREATE RULE reverted_import_no_update AS ON UPDATE TO registry.reverted_import DO INSTEAD NOTHING;
CREATE RULE reverted_import_no_delete AS ON DELETE TO registry.reverted_import DO INSTEAD NOTHING;
CREATE FUNCTION registry.reverted_import_keeps_its_marks() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'registry.reverted_import is what keeps a reverted import from being adopted again, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER reverted_import_no_truncate BEFORE TRUNCATE ON registry.reverted_import
  FOR EACH STATEMENT EXECUTE FUNCTION registry.reverted_import_keeps_its_marks();
