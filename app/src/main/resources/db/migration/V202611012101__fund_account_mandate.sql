-- Rule: PM-FUND-004 — the account record stores IBAN, holder and mandate. The mandate is the one the holder held when
-- the fund's account was opened, a reference and never a copy of its dates. Null for the rows already here, which stay
-- as stored, for an operating account, and for an association, whose mandate nothing records yet.
ALTER TABLE money.fund_account ADD COLUMN mandate_id uuid REFERENCES identity_org.management_mandate(id);
ALTER TABLE money.fund_account ADD CONSTRAINT fund_account_mandate_is_the_managers_of_the_fund
  CHECK (mandate_id IS NULL OR (purpose = 'REPAIR_RENEWAL' AND holder_kind = 'MANAGER'));
