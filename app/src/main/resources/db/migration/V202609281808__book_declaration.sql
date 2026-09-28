-- Rule: PM-BOOK-003 — owners and users declare for the book within BOOK_DECLARATION_DAYS (:law) of
-- acquiring title or use, and on any change of the declared data. `filed_on` is set by the system,
-- not the caller: the filing date, not a claimed one, decides timeliness.
-- Rule: PM-BOOK-004 — the template version in force on the filing date is recorded with it.
CREATE TABLE registry.book_declaration (
  id                uuid PRIMARY KEY,
  entrance_id       uuid NOT NULL REFERENCES registry.entrance(id),
  unit_id           uuid NOT NULL REFERENCES registry.unit(id),
  party_id          uuid NOT NULL REFERENCES registry.party(id),
  kind              text NOT NULL CHECK (kind IN ('ACQUISITION','CHANGE')),
  filed_on          date NOT NULL,
  template_version  text NOT NULL
);
CREATE INDEX book_declaration_entrance ON registry.book_declaration (entrance_id);
