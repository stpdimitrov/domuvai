-- Rule: PM-BOOK-007 — the names lists drawn from the book and the retention pass are accesses to book data too (AUTH-04e):
-- the owners of an entrance's units (OWNERS_LIST), who is past the deadline to declare for the book (OVERDUE_LIST),
-- and the anonymisation of what is past its retention window (RETENTION). Each is entered, served or refused, as a
-- read of the book is; each is of a day, as a read is — only the export of this log is of none.
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_kind_check;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_kind_check CHECK (kind IN ('BOOK_READ','LOG_EXPORT','OWNERS_LIST','OVERDUE_LIST','RETENTION'));
ALTER TABLE registry.book_access DROP CONSTRAINT book_access_read_is_dated;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_read_is_dated CHECK ((kind <> 'LOG_EXPORT') = (book_date IS NOT NULL));
