-- BOOK-02e (#441, #449) — Rule: PM-BOOK-011, PM-BOOK-002, ADR-005. A party is registered in an entrance, and only that
-- entrance's book may name it. Until now parties were one pool: a manager holding another entrance's party id could
-- enter that person in their own book and read the name back. The same person in two entrances is two parties.
-- Existing parties are tied to the one entrance whose book names them. One named in two entrances' books is never
-- guessed: the migration fails naming it, and is fixed by hand (register the person again in the second entrance and
-- point that entrance's rows at the new party). A party no book names keeps a NULL entrance, so no book can name it.
ALTER TABLE registry.party ADD COLUMN entrance_id uuid REFERENCES registry.entrance(id);

CREATE TEMP TABLE named_in ON COMMIT DROP AS
  SELECT party_id, entrance_id FROM registry.title
  UNION SELECT party_id, entrance_id FROM registry.household_member WHERE party_id IS NOT NULL;

DO $$
DECLARE spread text;
BEGIN
  SELECT string_agg(party_id::text, ', ' ORDER BY party_id::text) INTO spread
    FROM (SELECT party_id FROM named_in GROUP BY party_id HAVING count(*) > 1) s;
  IF spread IS NOT NULL THEN
    RAISE EXCEPTION 'BOOK-02e: party % is named in the books of more than one entrance; register it once per entrance, then migrate', spread;
  END IF;
END $$;

UPDATE registry.party p SET entrance_id = n.entrance_id FROM named_in n WHERE n.party_id = p.id;

-- Every party registered from now on names its entrance; rows from before keep theirs.
ALTER TABLE registry.party ADD CONSTRAINT party_registered_in_an_entrance CHECK (entrance_id IS NOT NULL) NOT VALID;

-- The backstop to the service's check: a title or member naming a party of another entrance, or of none, is refused.
ALTER TABLE registry.party ADD CONSTRAINT party_id_entrance UNIQUE (id, entrance_id);
ALTER TABLE registry.title
  ADD CONSTRAINT title_party_of_entrance FOREIGN KEY (party_id, entrance_id) REFERENCES registry.party (id, entrance_id);
ALTER TABLE registry.household_member
  ADD CONSTRAINT household_member_party_of_entrance FOREIGN KEY (party_id, entrance_id) REFERENCES registry.party (id, entrance_id);
