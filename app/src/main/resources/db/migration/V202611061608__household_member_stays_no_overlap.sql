-- BOOK-02d (#446) — Rule: PM-BOOK-002, PM-FEE-008. One party is one member of a unit at a time: two stays of the same
-- party in the same unit never overlap, so the headcount never counts one person twice. Stays are half-open
-- [valid_from, valid_to), as the service reads them. A NULL party_id never compares equal, so an unnamed member is
-- never constrained. RegistryService checks the same before it inserts, and names the party; this closes the race
-- two concurrent entries slip through, and catches a corrected move-out that runs into a later stay.
-- No row is edited: a database that already holds overlapping stays of one party fails here, naming the constraint,
-- and is fixed by hand. The rows in the way:
--   SELECT a.id, b.id FROM registry.household_member a JOIN registry.household_member b
--     ON a.unit_id = b.unit_id AND a.party_id = b.party_id AND a.id < b.id
--    AND daterange(a.valid_from, a.valid_to, '[)') && daterange(b.valid_from, b.valid_to, '[)');
ALTER TABLE registry.household_member
  ADD CONSTRAINT household_member_party_stays_no_overlap EXCLUDE USING gist (
    unit_id WITH =, party_id WITH =,
    daterange(valid_from, valid_to, '[)') WITH &&
  );
