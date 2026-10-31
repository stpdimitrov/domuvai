-- PM-FUND-010: who recorded the statement, the sign-in's party. Null for a statement issued before this, or with sign-in off.
-- Outside the hashed basis: that is the text the two sides sign, and issued statements must keep verifying.
ALTER TABLE money.fund_handover_statement ADD COLUMN recorded_by uuid REFERENCES registry.party(id);
