-- Rule: PM-FUND-007 / PM-FUND-009 — a signed-off disbursement closes once: paid out, or cancelled. A payout is
-- dated on the bank's value date and names who recorded it; its journal (money.posting, journal_id = the
-- disbursement's id) credits the fund's bank account. A cancellation records when, by whom and why. Neither
-- can predate the sign-off. A closed disbursement is never reopened, and what was signed off never rewritten.
ALTER TABLE money.fund_disbursement ADD COLUMN paid_on date;
ALTER TABLE money.fund_disbursement ADD COLUMN paid_by uuid REFERENCES registry.party(id);
ALTER TABLE money.fund_disbursement ADD COLUMN cancelled_on date;
ALTER TABLE money.fund_disbursement ADD COLUMN cancelled_by uuid REFERENCES registry.party(id);
ALTER TABLE money.fund_disbursement ADD COLUMN cancel_reason text
  CONSTRAINT fund_disbursement_cancel_reason_not_blank CHECK (cancel_reason ~ '\S');

ALTER TABLE money.fund_disbursement DROP CONSTRAINT fund_disbursement_status_check;
ALTER TABLE money.fund_disbursement
  ADD CONSTRAINT fund_disbursement_status_check CHECK (status IN ('COMMITTED','PAID','CANCELLED')),
  ADD CONSTRAINT fund_disbursement_paid_dated CHECK ((status = 'PAID') = (paid_on IS NOT NULL)),
  ADD CONSTRAINT fund_disbursement_paid_by_recorded CHECK ((paid_on IS NULL) = (paid_by IS NULL)),
  ADD CONSTRAINT fund_disbursement_paid_after_signed CHECK (paid_on >= committed_on),
  ADD CONSTRAINT fund_disbursement_cancelled_dated CHECK ((status = 'CANCELLED') = (cancelled_on IS NOT NULL)),
  ADD CONSTRAINT fund_disbursement_cancelled_by_recorded CHECK ((cancelled_on IS NULL) = (cancelled_by IS NULL)),
  ADD CONSTRAINT fund_disbursement_cancel_reason_recorded CHECK ((cancelled_on IS NULL) = (cancel_reason IS NULL)),
  ADD CONSTRAINT fund_disbursement_cancelled_after_signed CHECK (cancelled_on >= committed_on);

-- Only a COMMITTED disbursement changes, and only in its closing columns (status, paid_*, cancel*): a closed one
-- is never reopened or closed again, and what was signed off is never rewritten.
CREATE FUNCTION money.fund_disbursement_closes_once() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'COMMITTED' THEN
    RAISE EXCEPTION 'fund disbursement % is already %, and closes once', OLD.id, OLD.status
      USING ERRCODE = 'check_violation';
  END IF;
  IF (NEW.id, NEW.entrance_id, NEW.fund_account_id, NEW.amount_minor, NEW.currency, NEW.purpose, NEW.decision_id,
      NEW.passport_measure, NEW.emergency_justification, NEW.authorised_by, NEW.committed_on)
     IS DISTINCT FROM
     (OLD.id, OLD.entrance_id, OLD.fund_account_id, OLD.amount_minor, OLD.currency, OLD.purpose, OLD.decision_id,
      OLD.passport_measure, OLD.emergency_justification, OLD.authorised_by, OLD.committed_on) THEN
    RAISE EXCEPTION 'fund disbursement % stands as signed off, and is not rewritten', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER fund_disbursement_closes_once BEFORE UPDATE ON money.fund_disbursement
  FOR EACH ROW EXECUTE FUNCTION money.fund_disbursement_closes_once();
