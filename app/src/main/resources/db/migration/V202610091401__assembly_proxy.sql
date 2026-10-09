-- S-G2-01c — an authorisation to represent someone at one assembly.
-- Rule: PM-GA-009 — it records the principal, the agent and what kind of agent, the scope and the form.
CREATE TABLE assembly.proxy (
  id                  uuid PRIMARY KEY,
  entrance_id         uuid NOT NULL REFERENCES registry.entrance(id),
  assembly_id         uuid NOT NULL REFERENCES assembly.assembly(id),
  principal_party_id  uuid NOT NULL REFERENCES registry.party(id),
  agent_party_id      uuid NOT NULL REFERENCES registry.party(id),
  agent_kind          text NOT NULL CHECK (agent_kind IN ('HOUSEHOLD_MEMBER','OWNER','THIRD_PARTY')),
  scope               text NOT NULL CHECK (scope IN ('WHOLE_AGENDA','LISTED_ITEMS')),
  scope_items         text CHECK (scope_items ~ '^\d+(,\d+)*$'),
  form                text NOT NULL CHECK (form IN ('WRITTEN','NOTARISED','LAWYER')),
  registered_at       timestamptz NOT NULL,
  CONSTRAINT proxy_not_for_oneself CHECK (principal_party_id <> agent_party_id),
  CONSTRAINT proxy_scope_lists_its_items CHECK ((scope = 'LISTED_ITEMS') = (scope_items IS NOT NULL)),
  -- a principal is represented by one person at an assembly, so no share is ever represented twice
  CONSTRAINT proxy_one_per_principal UNIQUE (assembly_id, principal_party_id)
);
