-- Rule: PM-BOOK-003 — a declaration says who filed it: the declarant filing their own, or the manager or a board
-- member recording one handed in on paper (Rule: PM-BOOK-005) — so a filing on someone's behalf is never invisible.
-- Null for a declaration filed before this column, or with sign-in switched off, when nobody is asking.
ALTER TABLE registry.book_declaration ADD COLUMN filed_by uuid REFERENCES registry.party(id);
