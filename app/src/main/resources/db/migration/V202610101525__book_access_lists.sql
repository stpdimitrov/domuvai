-- Rule: PM-BOOK-007 — the names lists drawn from the book and the retention pass are accesses to book data too (AUTH-04e):
-- the owners of an entrance's units (OWNERS_LIST), who is past the deadline to declare for the book (OVERDUE_LIST),
-- and the anonymisation of what is past its retention window (RETENTION). Each is entered, served or refused, as a
-- read of the book is; each is of a day, as a read is — only the export of this log is of none.
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_kind_check;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_kind_check CHECK (kind IN ('BOOK_READ','LOG_EXPORT','OWNERS_LIST','OVERDUE_LIST','RETENTION'));
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_read_is_dated;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_read_is_dated CHECK ((kind <> 'LOG_EXPORT') = (book_date IS NOT NULL));
-- With sign-in switched off (a developer's machine) nobody is asking: a names list is still given, and entered as given
-- to nobody — OPEN. Only the two lists are ever given so: the book, its log and the retention pass are not.
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_outcome;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_outcome CHECK (outcome IN ('SERVED','REFUSED','OPEN'));
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_served_to_a_party;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_served_to_a_party CHECK (outcome <> 'SERVED' OR actor IS NOT NULL);
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_open_is_a_list_to_nobody
  CHECK (outcome <> 'OPEN' OR (kind IN ('OWNERS_LIST','OVERDUE_LIST') AND actor IS NULL AND login_subject IS NULL));
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_refusal_cites_its_rule;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_refusal_cites_its_rule CHECK (outcome <> 'REFUSED' OR rule_id IS NOT NULL);
