-- Rule: PM-BOOK-007 — testing a proxy called a household member asks the book who lives where, an access to book data:
-- entered as HOUSEHOLD_CHECK, dated the day of the appointment. It is always somebody's, so never OPEN.
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_kind_check;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_kind_check
  CHECK (kind IN ('BOOK_READ','LOG_EXPORT','OWNERS_LIST','OVERDUE_LIST','RETENTION','PARTY_NAMES','HEADCOUNT_READ','HOUSEHOLD_CHECK'));
