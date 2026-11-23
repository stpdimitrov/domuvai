-- Rule: PM-FEE-012 — a tariff version: the lines and metered prices an entrance's general assembly adopted, the
-- decision they were adopted under (`decision_id`, the rule's `protocol_id`) and the months they are in force.
-- Whole months, because runs bill months: `valid_from` is a month's first day, `valid_to` (open when null) a
-- month's last. Dated data (ADR-001): the version in force for a month is the latest begun by then, unless its own
-- `valid_to` has passed, so a later version ends an earlier one and two cannot begin in the same month.
CREATE TABLE money.tariff_version (
  id           uuid PRIMARY KEY,
  entrance_id  uuid NOT NULL REFERENCES registry.entrance(id),
  decision_id  text NOT NULL CHECK (decision_id <> ''),
  valid_from   date NOT NULL CHECK (extract(day FROM valid_from) = 1),
  valid_to     date CHECK (extract(day FROM valid_to + 1) = 1),
  lines        jsonb NOT NULL,
  recorded_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (entrance_id, valid_from),
  CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
-- A version is never altered: a changed tariff is a new version.
CREATE RULE tariff_version_no_update AS ON UPDATE TO money.tariff_version DO INSTEAD NOTHING;
CREATE RULE tariff_version_no_delete AS ON DELETE TO money.tariff_version DO INSTEAD NOTHING;
