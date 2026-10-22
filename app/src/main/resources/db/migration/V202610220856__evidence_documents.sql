-- S-G2-04a — documents: stored immutably, versioned, typed.
-- The baseline's evidence.document (V1) is the row; this adds the bytes it points at, its versions, and the kinds.

-- Rule: PM-DOC-001 — the bytes, content-addressed: the database itself checks the hash is of the bytes. A file stored
-- twice is one row. Never changed, removed or emptied — a trigger, not a rule, so a refused write fails loudly and
-- `INSERT … ON CONFLICT DO NOTHING` stays possible.
CREATE TABLE evidence.blob (
  content_hash  text PRIMARY KEY CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  bytes         bytea NOT NULL CHECK (octet_length(bytes) > 0),
  CONSTRAINT blob_is_its_hash CHECK (content_hash = encode(sha256(bytes), 'hex'))
);
CREATE FUNCTION evidence.documents_are_kept() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'a stored document is evidence, and is neither changed nor removed' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER blob_no_change BEFORE UPDATE OR DELETE ON evidence.blob
  FOR EACH ROW EXECUTE FUNCTION evidence.documents_are_kept();
CREATE TRIGGER blob_no_truncate BEFORE TRUNCATE ON evidence.blob
  FOR EACH STATEMENT EXECUTE FUNCTION evidence.documents_are_kept();
CREATE TRIGGER document_no_truncate BEFORE TRUNCATE ON evidence.document
  FOR EACH STATEMENT EXECUTE FUNCTION evidence.documents_are_kept();

-- Rule: PM-DOC-001 — a document names its bytes, its author and the entity it belongs to ("<type>:<uuid>").
ALTER TABLE evidence.document ADD CONSTRAINT document_names_its_bytes FOREIGN KEY (content_hash) REFERENCES evidence.blob (content_hash);
ALTER TABLE evidence.document ALTER COLUMN author_party_id SET NOT NULL;
ALTER TABLE evidence.document ADD CONSTRAINT document_names_its_entity CHECK (entity_ref ~ '^[a-z_]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
ALTER TABLE evidence.document ADD COLUMN media_type text NOT NULL CHECK (media_type ~ '^[a-z0-9.+-]+/[a-z0-9.+-]+$');

-- Rule: PM-DOC-001 — a re-upload is a new version: a row of its own naming the one it supersedes, of the same
-- entrance, entity and kind. One successor per version, so versions form a line, never a fork. [document_key] is the
-- first version's id, shared by every version of that document.
ALTER TABLE evidence.document ADD COLUMN document_key uuid NOT NULL;
ALTER TABLE evidence.document ADD COLUMN version_no integer NOT NULL CHECK (version_no >= 1);
ALTER TABLE evidence.document ADD COLUMN supersedes uuid;
ALTER TABLE evidence.document ADD CONSTRAINT document_version_is_its_line UNIQUE (id, entrance_id, entity_ref, kind, document_key);
ALTER TABLE evidence.document ADD CONSTRAINT document_supersedes_its_own FOREIGN KEY (supersedes, entrance_id, entity_ref, kind, document_key)
  REFERENCES evidence.document (id, entrance_id, entity_ref, kind, document_key);
ALTER TABLE evidence.document ADD CONSTRAINT document_one_successor UNIQUE (supersedes);
ALTER TABLE evidence.document ADD CONSTRAINT document_one_version_no UNIQUE (document_key, version_no);
ALTER TABLE evidence.document ADD CONSTRAINT document_first_version CHECK (
  (supersedes IS NULL) = (document_key = id) AND (supersedes IS NULL) = (version_no = 1)
);

-- Rule: PM-DOC-002 — a statutory document is of one of the kinds the rule names; nothing untyped.
ALTER TABLE evidence.document ADD CONSTRAINT document_kind_is_statutory
  CHECK (kind IN ('PROTOCOL','NOTICE','POSTING_ACT','CONSTATIVE_PROTOCOL','FILING','CERTIFICATE'));

-- Rule: PM-DOC-007 — where the paper is the original, the record says where the paper is and who keeps it; the stored
-- file is a copy of it. A protocol always says so.
ALTER TABLE evidence.document ADD COLUMN paper_custodian text;
ALTER TABLE evidence.document ADD CONSTRAINT document_paper_whole CHECK ((paper_location IS NULL) = (paper_custodian IS NULL));
ALTER TABLE evidence.document ADD CONSTRAINT document_paper_said CHECK (paper_location ~ '\S' AND paper_custodian ~ '\S');
ALTER TABLE evidence.document ADD CONSTRAINT document_protocol_paper CHECK (kind <> 'PROTOCOL' OR paper_location IS NOT NULL);
