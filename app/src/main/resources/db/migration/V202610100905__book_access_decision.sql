-- Rule: PM-BOOK-006, PM-BOOK-007 — who may read the book is decided before it is read (AUTH-04b), and the entry says
-- how it was decided: served or refused, and by which rule. A refusal is an entry too. The reader is the party the
-- sign-in's login is tied to; the login itself is kept beside it, and is all there is when it is tied to no party —
-- so an entry of a refusal may have no party, and one of a served read never. Entries written before this — every
-- one of them served, to an actor the caller named — keep no rule and no login.
ALTER TABLE registry.book_access ADD COLUMN outcome text NOT NULL DEFAULT 'SERVED' CONSTRAINT book_access_outcome CHECK (outcome IN ('SERVED','REFUSED'));
ALTER TABLE registry.book_access ADD COLUMN rule_id text CONSTRAINT book_access_rule_id CHECK (rule_id ~ '^PM-[A-Z]+-[0-9]{3}$');
ALTER TABLE registry.book_access ADD COLUMN login_issuer text;
ALTER TABLE registry.book_access ADD COLUMN login_subject text;
ALTER TABLE registry.book_access ALTER COLUMN actor DROP NOT NULL;
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_served_to_a_party CHECK (outcome = 'REFUSED' OR actor IS NOT NULL);
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_refusal_cites_its_rule CHECK (outcome = 'SERVED' OR rule_id IS NOT NULL);
ALTER TABLE registry.book_access ADD CONSTRAINT book_access_login_is_whole CHECK ((login_issuer IS NULL) = (login_subject IS NULL));
