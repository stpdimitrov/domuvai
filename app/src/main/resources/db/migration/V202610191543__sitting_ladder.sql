-- S-G2-02c — the sittings of an assembly: how many there may be is the law's (`GA_SITTINGS`, PM-GA-014), dated, not the
-- schema's (#189). The baseline capped `session_no` at 3; the cap goes, a sitting is numbered from one.
ALTER TABLE assembly.session DROP CONSTRAINT session_session_no_check;
ALTER TABLE assembly.session ADD CONSTRAINT session_numbered_from_one CHECK (session_no >= 1);
