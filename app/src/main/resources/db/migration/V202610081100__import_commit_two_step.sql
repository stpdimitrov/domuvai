-- STAGE1-ADDENDUM §1, step 6 — a commit, like a revert, is carried out by the registry after the request has
-- answered. So it has the same two steps: COMMITTING when it is asked for, then COMMITTED when the registry has
-- adopted the import's rows — or COMMIT_BLOCKED, with why, when it could not. A blocked commit adopted nothing and
-- can be asked for again.
ALTER TABLE intake.fee_import DROP CONSTRAINT fee_import_status_check;
ALTER TABLE intake.fee_import ADD COLUMN commit_blocked_by text
  CONSTRAINT fee_import_commit_blocked_by_not_blank CHECK (commit_blocked_by ~ '\S');
ALTER TABLE intake.fee_import
  ADD CONSTRAINT fee_import_status_check
    CHECK (status IN ('REPRODUCED','NEEDS_REVIEW','COMMITTING','COMMITTED','COMMIT_BLOCKED','REVERTING','REVERTED','REVERT_BLOCKED')),
  ADD CONSTRAINT fee_import_commit_blocked_says_why CHECK ((status = 'COMMIT_BLOCKED') = (commit_blocked_by IS NOT NULL));
