-- Rule: PM-FEE-007 — the grace window turns on the day the paper declaration was handed in, stored
-- beside `filed_on`, the day it was entered, and never after it (the owner's answer on #215). A row
-- already stored was never given another day, so its entry day stands as the day it was handed in.
ALTER TABLE registry.absence_declaration ADD COLUMN handed_in_on date;
UPDATE registry.absence_declaration SET handed_in_on = filed_on;
ALTER TABLE registry.absence_declaration ALTER COLUMN handed_in_on SET NOT NULL;
ALTER TABLE registry.absence_declaration
  ADD CONSTRAINT absence_handed_in_not_after_entry CHECK (handed_in_on <= filed_on);
