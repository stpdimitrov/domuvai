-- Rule: PM-FUND-004 — "the chair of the management board" is a mark on one board member's (MB) mandate, given when the
-- mandate is recorded, from that mandate's own protocol (GOV-04, #434). The role stays MB. Existing mandates are unmarked.
-- At most one chair in force per entrance on any day is the application's check (MandateAdministration.record).
ALTER TABLE identity_org.management_mandate ADD COLUMN chair boolean NOT NULL DEFAULT false;
ALTER TABLE identity_org.management_mandate ADD CONSTRAINT management_mandate_chair_is_a_board_member CHECK (NOT chair OR body = 'MB');

-- Rule: PM-SEC-011 — the mark is kept as the rest of the mandate is: added once, never changed. The guard of
-- V202610101450 now names it too.
CREATE OR REPLACE FUNCTION identity_org.mandate_is_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.succeeded_at IS NULL AND NEW.succeeded_at IS NOT NULL
     AND (NEW.id, NEW.entrance_id, NEW.body, NEW.party_id, NEW.elected_by_protocol, NEW.valid_from, NEW.valid_to, NEW.protocol_ref, NEW.chair)
         IS NOT DISTINCT FROM (OLD.id, OLD.entrance_id, OLD.body, OLD.party_id, OLD.elected_by_protocol, OLD.valid_from, OLD.valid_to, OLD.protocol_ref, OLD.chair) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'identity_org.management_mandate: a mandate is added and its end recorded once; it is not otherwise changed, and not removed' USING ERRCODE = 'check_violation';
END $$;

-- Rule: PM-SEC-004 — a RECORDED entry says whether the mandate is the chair's; a refused attempt keeps the mark it was
-- asked with. Earlier entries, and every end, say nothing.
ALTER TABLE identity_org.mandate_act ADD COLUMN chair boolean;
