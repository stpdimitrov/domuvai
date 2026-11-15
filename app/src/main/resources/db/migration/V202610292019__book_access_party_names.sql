-- Rule: PM-BOOK-007 — the names beside parties' ids in another module's read (the entrance's mandates, NAMES-01) are an
-- access to book data too: entered as PARTY_NAMES, of the day they were given. With sign-in switched off they are given
-- to nobody, as the names lists are — OPEN. Withheld names are not entered: nothing of the book was given.
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_kind_check;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_kind_check
  CHECK (kind IN ('BOOK_READ','LOG_EXPORT','OWNERS_LIST','OVERDUE_LIST','RETENTION','PARTY_NAMES'));
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_open_is_a_list_to_nobody;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_open_is_a_list_to_nobody
  CHECK (outcome <> 'OPEN' OR (kind IN ('OWNERS_LIST','OVERDUE_LIST','PARTY_NAMES') AND actor IS NULL AND login_subject IS NULL));
