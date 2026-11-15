-- S-G2-04 — evidence: retention, case export, QES, photographic evidence.

-- Rule: PM-DOC-005 — documents are retained for a statutory or contractual period, then deleted with a logged reason.
-- [retention_until] is when deletion may occur; null = retention policy not yet applied. Once set, deletion is by a
-- background job (not in this slice) that writes the log and removes the row. The column exists since V1__init.sql:430.

-- Rule: PM-DOC-005 — the deletion log, immutable: who deleted what when and why. Written by the deletion job; the
-- document row and its bytes are both gone by then, so the log keeps what is needed to answer "where did it go".
CREATE TABLE evidence.deletion_log (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id      uuid NOT NULL,
  entrance_id      uuid NOT NULL REFERENCES entrance (id),
  entity_ref       text NOT NULL,
  kind             text NOT NULL,
  content_hash     text NOT NULL,
  deleted_at       timestamptz NOT NULL DEFAULT now(),
  deleted_by       uuid NOT NULL REFERENCES party (id),
  reason           text NOT NULL CHECK (reason ~ '\S')
);
CREATE TRIGGER deletion_log_no_change BEFORE UPDATE OR DELETE ON evidence.deletion_log
  FOR EACH ROW EXECUTE FUNCTION evidence.documents_are_kept();
CREATE TRIGGER deletion_log_no_truncate BEFORE TRUNCATE ON evidence.deletion_log
  FOR EACH STATEMENT EXECUTE FUNCTION evidence.documents_are_kept();
CREATE INDEX deletion_log_by_entrance ON evidence.deletion_log (entrance_id);
CREATE INDEX deletion_log_by_entity ON evidence.deletion_log (entity_ref);

-- Rule: PM-DOC-008 — a qualified electronic signature's validation evidence is stored at signing time, not recomputed.
-- JSONB carries the provider's validation report as-is: who signed, when, validity status, certificate chain, timestamps.
ALTER TABLE evidence.document ADD COLUMN signature_validation_report jsonb;

-- Rule: PM-DOC-009 — photographic evidence keeps its capture timestamp and is stored unmodified; a display copy is
-- separate. [captured_at] is when the photo was taken; null for non-photos. [display_copy_hash] names the resized
-- version in evidence.blob; the original bytes are what [content_hash] names.
ALTER TABLE evidence.document ADD COLUMN captured_at timestamptz;
ALTER TABLE evidence.document ADD COLUMN display_copy_hash text REFERENCES evidence.blob (content_hash);

-- Rule: PM-DOC-003 / PM-DOC-004 — a constative protocol records a breach: who, what, when, where. These fields are
-- required when kind = CONSTATIVE_PROTOCOL; optional otherwise. The generation mechanism (who signs) is deferred to a
-- later slice as it needs the governance module's answer.
ALTER TABLE evidence.document ADD COLUMN breach_party_id uuid REFERENCES party (id);
ALTER TABLE evidence.document ADD COLUMN breach_description text;
ALTER TABLE evidence.document ADD COLUMN breach_occurred_at timestamptz;
ALTER TABLE evidence.document ADD COLUMN breach_location text;
ALTER TABLE evidence.document ADD CONSTRAINT document_constative_complete CHECK (
  kind <> 'CONSTATIVE_PROTOCOL' OR (
    breach_party_id IS NOT NULL AND
    breach_description IS NOT NULL AND breach_description ~ '\S' AND
    breach_occurred_at IS NOT NULL AND
    breach_location IS NOT NULL AND breach_location ~ '\S'
  )
);
