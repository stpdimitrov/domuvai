-- STAGE1-ADDENDUM §1 — an import is revertible, and a revert is carried out by the registry after the request has
-- answered. So a revert has two steps: REVERTING when it is asked for, then REVERTED when the registry has removed
-- the import's rows — or REVERT_BLOCKED, with what still points at them, when it could not. A blocked revert can be
-- asked for again.
ALTER TABLE intake.fee_import DROP CONSTRAINT fee_import_status_check;
ALTER TABLE intake.fee_import ADD COLUMN revert_blocked_by text
  CONSTRAINT fee_import_revert_blocked_by_not_blank CHECK (revert_blocked_by ~ '\S');
ALTER TABLE intake.fee_import
  ADD CONSTRAINT fee_import_status_check
    CHECK (status IN ('REPRODUCED','NEEDS_REVIEW','COMMITTED','REVERTING','REVERTED','REVERT_BLOCKED')),
  ADD CONSTRAINT fee_import_blocked_says_by_what CHECK ((status = 'REVERT_BLOCKED') = (revert_blocked_by IS NOT NULL));
