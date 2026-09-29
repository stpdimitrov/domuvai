-- Rule: PM-FEE-017 — a consumption line bills each unit for what its own meter read, beside the statutory keys.
-- It is a MAINTENANCE line (PM-FEE-001) typed by its metered item (WATER, HEATING) and marked METERED — not a
-- statutory key — with the reading as its quantity. METERED goes with a metered item, and only with one.
ALTER TABLE money.charge_line DROP CONSTRAINT charge_line_allocation_key_check;
ALTER TABLE money.charge_line ADD CONSTRAINT charge_line_allocation_key_check
  CHECK (allocation_key IN ('PER_PERSON','BY_IDEAL_PARTS','PER_UNIT','METERED'));
ALTER TABLE money.charge_line DROP CONSTRAINT charge_line_item_is_maintenance;
ALTER TABLE money.charge_line ADD CONSTRAINT charge_line_item_is_maintenance
  CHECK (item IS NULL OR (item IN ('CONCIERGE','WATER','HEATING') AND component = 'MAINTENANCE'));
ALTER TABLE money.charge_line ADD CONSTRAINT charge_line_metered_is_consumption
  CHECK ((allocation_key = 'METERED') = (item IS NOT NULL AND item IN ('WATER','HEATING')));
