# ADR-005 — The entrance is the isolation unit, and the account belongs to it

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | — |
| **Blocks** | A12 scaffold, A17 identity-org, A19 registry, every RLS policy, the offboarding export. ADR-004 assumes this decision |

---

## 1 Context

Two of the nine non-negotiables, together because one SaaS instinct overturns both: the entrance is the isolation unit, and the account is the entrance's.

**The law constitutes the condominium per entrance.** PM-ORG-001 (чл. 9, чл. 10): each entrance may run its own general assembly, manager and accounts. Three entrances, three assemblies, three funds. No entrance's assembly binds another.

**The money is separated by statute.** PM-FUND-005 (чл. 50): fund monies must not be commingled with a management company's own funds or with another building's. PM-PMC-008 repeats it — no inter-building transfers.

**The firm is the most transient party in the model.** PM-GOV-004 (чл. 19, чл. 21) caps a mandate at two years, the incumbent continuing until a successor is elected. Firms are fired and rehired constantly, by design. PM-GOV-018 and PM-PMC-010 (чл. 23) make each departure an obligation: book, protocols in original, cashbox records and fund balance transfer under a signed handover act, and the pack **must not be blocked by commercial disputes**. The fund follows the building (PM-FUND-010).

**The commercial consequence.** Were the firm the isolation unit, the platform would churn at the rate чл. 19 guarantees. With the entrance: the firm leaves, the building stays, the incoming firm finds its client already here. Each handover adds a firm and keeps a building — a flywheel that follows from a legal fact, not a growth tactic.

---

## 2 Decision

**`entrance_id` is the only tenant key. The entrance owns its record; a firm holds a time-boxed mandate over it.**

- Every tenant-scoped table carries `entrance_id NOT NULL` with an RLS policy on it, and no second scope column.
- A firm is a **party holding an effective-dated mandate** — start, end, PM-GOV-004's two-year cap. Access derives from a live mandate, never from owning rows.
- The fund account is the entrance's. PM-FUND-004 puts it in the name of the chair of the management board or of the association, never the firm's.
- Ending a mandate changes **who may read**, never **who owns**. Offboarding is an export, not a migration.

| The entrance keeps, permanently | The firm holds, for the mandate |
|---|---|
| Units, occupants, ideal parts, the register | The mandate: scope, term, price |
| The book, protocols, decisions | Staff assignments, internal notes |
| The ledger, the fund, its account | Its invoice to the entrance |
| Documents, compliance history, work orders | Nothing else |

`tenant` appears in no identifier. The isolation unit is an **Entrance**; a person is an **Occupant**.

---

## 3 Options considered

| Option | Key | Survives a handover | Verdict |
|---|---|---|---|
| **A · Building** | `building_id` | Yes | Rejected. Under PM-ORG-001 a building-wide predicate lets entrance 2's manager read entrance 1's ledger, and PM-FUND-005 demands one fund per entrance |
| **B · Firm / organisation** | `organisation_id` | **No** | The SaaS default, and wrong here. чл. 19 makes the partition key the model's most volatile entity, and PM-PMC-010's export cannot be guaranteed while the departing firm owns the partition |
| **C · Entrance** ✅ | `entrance_id` | Yes | **Chosen.** The unit the law constitutes, the unit that holds the fund, the unit that outlives every firm |
| **D · Entrance + site axis** | two keys | Yes | Rejected by ADR-004. Every policy becomes a disjunction, and one wrong predicate leaks across entrances |

---

## 4 Consequences

**Good**

- A lost mandate is a permissions change, not a migration; PM-PMC-010's one-click export has nothing to disentangle.
- PM-FUND-005's "cross-entrance postings are impossible" becomes a database predicate, not a review habit.
- One isolation mechanism in both modes — one entrance or two hundred, the same policy.

**Bad, and accepted**

- A firm's portfolio view is an aggregation over its live mandates — dearer than an `organisation_id` filter, and the only correct answer.
- Anything building-wide — roof, lift shaft, facade — needs a share agreement and an explicit grant (ADR-004), not a widened predicate.
- An entrance exists before any firm touches it. Founding one is a first-class operation, not a side effect of onboarding.

**How it is enforced**

1. Every tenant-scoped table has `entrance_id NOT NULL` and an RLS policy on it. A migration adding a table without both fails CI, as does a second scope column (ADR-004).
2. A test authenticates into one entrance, attempts a cross-entrance read and expects zero rows — the check ADR-003 §4 mandates.
3. A posting whose legs resolve to different entrances is rejected at the ledger layer (PM-FUND-005, PM-PMC-008).
4. Firm access resolves through a mandate capped at two years; at expiry its status becomes `EXPIRED_ACTING`, with an election task 60 days before (PM-GOV-004).
5. Deactivating a mandate without a handover act raises a blocking task (PM-GOV-018).
6. The offboarding export is a job owned by the entrance. A test runs it with every firm account disabled and asserts a complete pack and an audit trail of what was handed over (PM-PMC-010, PM-FUND-010).

Check 6 is the one that matters, and it is proved against a failure: give the export job firm ownership, confirm it goes red.

---

## 5 What would reverse this

- An amendment to чл. 9 / чл. 10 making the building the unit of assembly and account. The predicate keeps its shape and entrances merge into one row — a migration, not a redesign.
- A jurisdiction where the manager lawfully holds the fund in its own name. PM-FUND-005 forbids it here, and that rule is load-bearing.

Commercial pressure is not on this list. A firm wanting a portfolio-wide partition because reporting is slow is asking for an index.

---

## 6 Deferred

- How long a departed firm may read the periods it managed. A data-protection answer, not a tenancy one — ADR-002 territory.
- Merging or splitting an entrance when a building reorganises into a single association. Needs counsel first.
