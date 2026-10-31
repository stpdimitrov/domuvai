-- S-G2-02n. Rule: PM-SEC-004 — an absentee ballot is how an owner voted, so its read is entered in the same log as the
-- votes' (the owner's answer on #298, 2026-10-27); `read_of` says which of the two was read. Every entry so far was of
-- the votes, and so is any an api still on the old code enters during a deploy: hence the default.
ALTER TABLE assembly.vote_read ADD COLUMN read_of text NOT NULL DEFAULT 'VOTES' CHECK (read_of IN ('VOTES','ABSENTEE_BALLOTS'));
