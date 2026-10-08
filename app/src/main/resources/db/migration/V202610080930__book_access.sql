-- Rule: PM-BOOK-007 — every access to the Book of the Condominium, with who read it, why, and when. One row per
-- read of the book (BOOK_READ, with the date the book was read as of) and per export of this log (LOG_EXPORT).
-- Insert-only: an entry is never changed and never removed.
CREATE TABLE registry.book_access (
  id           uuid PRIMARY KEY,
  entrance_id  uuid NOT NULL REFERENCES registry.entrance(id),
  actor        uuid NOT NULL REFERENCES registry.party(id),
  purpose      text NOT NULL CONSTRAINT book_access_purpose_not_blank CHECK (purpose ~ '\S'),
  kind         text NOT NULL CHECK (kind IN ('BOOK_READ','LOG_EXPORT')),
  book_date    date,
  at           timestamptz NOT NULL,
  CONSTRAINT book_access_read_is_dated CHECK ((kind = 'BOOK_READ') = (book_date IS NOT NULL))
);
CREATE INDEX book_access_entrance ON registry.book_access (entrance_id, at);
CREATE RULE book_access_no_update AS ON UPDATE TO registry.book_access DO INSTEAD NOTHING;
CREATE RULE book_access_no_delete AS ON DELETE TO registry.book_access DO INSTEAD NOTHING;
