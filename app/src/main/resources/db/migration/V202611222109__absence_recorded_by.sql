-- Rule: PM-SEC-004, PM-FEE-007 — an absence entry says who entered it. Since the stated day it was handed in decides
-- the window (PM-FEE-007), the entry must show who stated that day. Set from the sign-in's party, never from the request.
-- Null for an entry stored before this column, or with sign-in switched off, when nobody is asking
-- (precedents: book_declaration.filed_by, fund_handover_statement.recorded_by). The code only inserts; the table has no
-- guard against UPDATE or DELETE yet, which is not this slice's.
ALTER TABLE registry.absence_declaration ADD COLUMN recorded_by uuid REFERENCES registry.party(id);
