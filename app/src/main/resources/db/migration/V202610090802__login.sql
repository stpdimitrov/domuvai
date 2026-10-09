-- A login is a party (ADR-011, AUTH-02): the issuer and the subject of a sign-in token, tied to one registered party.
-- The api keeps the tie; the provider's token only says which login is asking. A login is tied to one party, and
-- under one issuer a party has one login — a second login claiming to be the same person is refused, not merged.
CREATE TABLE identity_org.login (
  issuer    text NOT NULL CHECK (issuer <> ''),
  subject   text NOT NULL CHECK (subject <> ''),
  party_id  uuid NOT NULL REFERENCES registry.party(id),
  tied_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (issuer, subject),
  CONSTRAINT login_one_per_party UNIQUE (issuer, party_id)
);
