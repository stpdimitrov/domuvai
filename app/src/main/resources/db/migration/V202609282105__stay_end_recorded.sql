-- Rule: PM-BOOK-010 — the day the system recorded that a stay ended. The book's retention window runs
-- from the later of this day and the declared day, so a backdated move-out cannot bring anonymisation
-- forward. Null until a stay is ended.
ALTER TABLE registry.household_member ADD COLUMN end_recorded_on date;
ALTER TABLE registry.animal ADD COLUMN end_recorded_on date;

-- Rule: PM-BOOK-008 — an animal's stay is a range like a resident's: it ends after it begins.
ALTER TABLE registry.animal ADD CONSTRAINT animal_valid_range CHECK (valid_to IS NULL OR valid_to > valid_from);
