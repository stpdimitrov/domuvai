-- Rule: PM-FUND-007 / PM-FUND-009 — a signed-off disbursement is paid out once, or cancelled once. A payout is
-- dated on the bank's value date, and its journal (money.posting, journal_id = the disbursement's id) credits
-- the fund's bank account. A cancellation records when, by whom and why. Neither can predate the sign-off.
ALTER TABLE money.fund_disbursement ADD COLUMN paid_on date;
ALTER TABLE money.fund_disbursement ADD COLUMN cancelled_on date;
ALTER TABLE money.fund_disbursement ADD COLUMN cancelled_by uuid REFERENCES registry.party(id);
ALTER TABLE money.fund_disbursement ADD COLUMN cancel_reason text
  CONSTRAINT fund_disbursement_cancel_reason_not_blank CHECK (cancel_reason ~ '\S');

ALTER TABLE money.fund_disbursement DROP CONSTRAINT fund_disbursement_status_check;
ALTER TABLE money.fund_disbursement
  ADD CONSTRAINT fund_disbursement_status_check CHECK (status IN ('COMMITTED','PAID','CANCELLED')),
  ADD CONSTRAINT fund_disbursement_paid_dated CHECK ((status = 'PAID') = (paid_on IS NOT NULL)),
  ADD CONSTRAINT fund_disbursement_cancel_recorded CHECK (
    (status = 'CANCELLED') = (cancelled_on IS NOT NULL)
    AND (cancelled_on IS NULL) = (cancelled_by IS NULL)
    AND (cancelled_on IS NULL) = (cancel_reason IS NULL)),
  ADD CONSTRAINT fund_disbursement_paid_after_signed CHECK (paid_on >= committed_on),
  ADD CONSTRAINT fund_disbursement_cancelled_after_signed CHECK (cancelled_on >= committed_on);
