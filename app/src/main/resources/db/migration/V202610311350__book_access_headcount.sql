-- Rule: PM-BOOK-007 — the headcount check shows what the book declares of each unit's persons, an access to book data:
-- entered as HEADCOUNT_READ, dated the run's legal date. With sign-in switched off the counts are given to nobody, as
-- the names lists are — OPEN.
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_kind_check;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_kind_check
  CHECK (kind IN ('BOOK_READ','LOG_EXPORT','OWNERS_LIST','OVERDUE_LIST','RETENTION','PARTY_NAMES','HEADCOUNT_READ'));
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_open_is_a_list_to_nobody;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_open_is_a_list_to_nobody
  CHECK (outcome <> 'OPEN' OR (kind IN ('OWNERS_LIST','OVERDUE_LIST','PARTY_NAMES','HEADCOUNT_READ') AND actor IS NULL AND login_subject IS NULL));
