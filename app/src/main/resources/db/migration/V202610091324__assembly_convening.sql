-- S-G2-01a — what convening records beyond the baseline: in what capacity the convenor acts, where the
-- assembly meets, whether it is urgent and why, and the type of each agenda item.

-- Rule: PM-GA-002 — convened by the management board, the manager or the control board/controller.
ALTER TABLE assembly.assembly ADD COLUMN convened_as text CHECK (convened_as IN ('MB','BM','CTL'));
ALTER TABLE assembly.assembly ADD COLUMN place text;
ALTER TABLE assembly.assembly ADD COLUMN urgent boolean NOT NULL DEFAULT false;
ALTER TABLE assembly.assembly ADD COLUMN urgency_reason text;
-- Rule: PM-GA-005 — the urgency is recorded: urgent means a justification, and only urgent has one.
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_urgency_recorded
  CHECK (urgent = (urgency_reason IS NOT NULL) AND (urgency_reason IS NULL OR urgency_reason ~ '\S'));
-- Every assembly written from now on says who convened it, in what capacity and where it meets. NOT VALID:
-- the rule binds new rows without failing the migration on a row someone wrote by hand before it.
ALTER TABLE assembly.assembly ADD CONSTRAINT assembly_convening_stated
  CHECK (convened_by IS NOT NULL AND convened_as IS NOT NULL AND place ~ '\S') NOT VALID;

-- Rule: PM-VOTE-004 — the item's type is what selects its majority rule.
ALTER TABLE assembly.agenda_item ADD COLUMN item_type text;
ALTER TABLE assembly.agenda_item ADD CONSTRAINT agenda_item_typed CHECK (item_type IS NOT NULL) NOT VALID;
