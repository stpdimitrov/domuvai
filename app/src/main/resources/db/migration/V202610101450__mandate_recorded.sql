-- Rule: PM-GOV-004, PM-SEC-011 — a mandate is recorded through the api by the deployment's administrator (AUTH-04d),
-- from the protocol that elected it: the reference to that protocol is kept on the mandate, free text until the
-- assembly module serves protocols. Mandates written before this have none.
ALTER TABLE identity_org.management_mandate ADD COLUMN protocol_ref text CONSTRAINT management_mandate_protocol_ref CHECK (protocol_ref ~ '\S' AND char_length(protocol_ref) <= 500);
-- An end is never before the start: a successor cannot take office before the mandate it succeeds began.
ALTER TABLE identity_org.management_mandate ADD CONSTRAINT management_mandate_ended_after_start CHECK (succeeded_at IS NULL OR succeeded_at >= valid_from);
COMMENT ON COLUMN identity_org.management_mandate.valid_to IS 'The day the mandate runs up to and does not include: [valid_from, valid_to).';
COMMENT ON COLUMN identity_org.management_mandate.succeeded_at IS 'The day a successor took office, or the mandate was otherwise ended: it no longer counts from this day.';

-- Rule: PM-SEC-011 — who held an office on a past day must stay answerable. A mandate is added, and its end is
-- recorded once; nothing else about it is changed afterwards and it is never removed — the application cannot, even by
-- mistake. The database's owner can still drop this guard.
CREATE FUNCTION identity_org.mandate_is_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.succeeded_at IS NULL AND NEW.succeeded_at IS NOT NULL
     AND (NEW.id, NEW.entrance_id, NEW.body, NEW.party_id, NEW.elected_by_protocol, NEW.valid_from, NEW.valid_to, NEW.protocol_ref)
         IS NOT DISTINCT FROM (OLD.id, OLD.entrance_id, OLD.body, OLD.party_id, OLD.elected_by_protocol, OLD.valid_from, OLD.valid_to, OLD.protocol_ref) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'identity_org.management_mandate: a mandate is added and its end recorded once; it is not otherwise changed, and not removed' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER management_mandate_kept BEFORE UPDATE OR DELETE ON identity_org.management_mandate
  FOR EACH ROW EXECUTE FUNCTION identity_org.mandate_is_kept();
CREATE TRIGGER management_mandate_not_emptied BEFORE TRUNCATE ON identity_org.management_mandate
  FOR EACH STATEMENT EXECUTE FUNCTION identity_org.mandate_is_kept();

-- Rule: PM-SEC-004 — every mandate recorded and every end recorded, and every refused attempt of a signed-in login,
-- with who did it and when. `on_day` is the day an end takes effect; `succeeded_by` the new mandate, where recording
-- one ended this one. Values are kept, not references: the entry outlives what it describes. Insert-only for the
-- application, as identity_org.login_act is; the database's owner can still drop these guards.
CREATE TABLE identity_org.mandate_act (
  id            uuid PRIMARY KEY,
  act           text NOT NULL CHECK (act IN ('RECORDED','ENDED','RECORD_REFUSED','END_REFUSED')),
  entrance_id   uuid NOT NULL,
  mandate_id    uuid,
  body          text,
  party_id      uuid,
  valid_from    date,
  valid_to      date,
  on_day        date,
  succeeded_by  uuid,
  protocol_ref  text,
  by_issuer     text NOT NULL,
  by_subject    text NOT NULL,
  rule_id       text NOT NULL CONSTRAINT mandate_act_rule_id CHECK (rule_id ~ '^PM-[A-Z]+-[0-9]{3}$'),
  at            timestamptz NOT NULL,
  CONSTRAINT mandate_act_names_its_mandate CHECK (act NOT IN ('RECORDED','ENDED') OR mandate_id IS NOT NULL),
  CONSTRAINT mandate_act_end_has_its_day CHECK (act <> 'ENDED' OR on_day IS NOT NULL),
  CONSTRAINT mandate_act_end_has_its_basis CHECK (act <> 'ENDED' OR succeeded_by IS NOT NULL OR protocol_ref IS NOT NULL),
  CONSTRAINT mandate_act_record_is_whole CHECK (act <> 'RECORDED' OR (body IS NOT NULL AND party_id IS NOT NULL AND valid_from IS NOT NULL AND valid_to IS NOT NULL AND protocol_ref IS NOT NULL))
);
CREATE INDEX mandate_act_entrance ON identity_org.mandate_act (entrance_id, at);
CREATE RULE mandate_act_no_update AS ON UPDATE TO identity_org.mandate_act DO INSTEAD NOTHING;
CREATE RULE mandate_act_no_delete AS ON DELETE TO identity_org.mandate_act DO INSTEAD NOTHING;
CREATE FUNCTION identity_org.mandate_act_keeps_its_entries() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'identity_org.mandate_act is the record of who was given which office, and is not emptied' USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER mandate_act_no_truncate BEFORE TRUNCATE ON identity_org.mandate_act
  FOR EACH STATEMENT EXECUTE FUNCTION identity_org.mandate_act_keeps_its_entries();
