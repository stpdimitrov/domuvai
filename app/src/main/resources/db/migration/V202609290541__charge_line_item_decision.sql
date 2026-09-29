-- Rule: PM-FEE-011 — a concierge (портиер) line is a maintenance line with a name, not a fourth stream
-- (PM-FEE-001). A stream may now store more than one line per unit, so a stored line is unique per run,
-- unit, stream and item — a resumed run still writes nothing twice.
ALTER TABLE money.charge_line ADD COLUMN item text;
ALTER TABLE money.charge_line ADD CONSTRAINT charge_line_item_is_maintenance
  CHECK (item IS NULL OR (item = 'CONCIERGE' AND component = 'MAINTENANCE'));
ALTER TABLE money.charge_line DROP CONSTRAINT charge_line_charge_run_id_unit_id_component_key;
CREATE UNIQUE INDEX charge_line_run_unit_component_item
  ON money.charge_line (charge_run_id, unit_id, component, COALESCE(item, ''));

-- Rule: PM-FEE-003 — each stored line names the GA decision behind it. Lines stored before this keep
-- their trace in the run's basis, so the check is NOT VALID: it binds every line written from now on.
ALTER TABLE money.charge_line ADD COLUMN decision_id text;
ALTER TABLE money.charge_line ADD CONSTRAINT charge_line_decision_named
  CHECK (decision_id IS NOT NULL AND decision_id <> '') NOT VALID;
