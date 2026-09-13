# ADR-004 — A shared facility is a cost-sharing agreement, not a tenant

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov · counsel on the exposure in §4 |
| **Supersedes** | — |
| **Blocks** | A19 registry, A32 maintenance, the RLS policy shape in A12 |

---

## 1 Context

`entrance_id` is the tenant key and the row-level-security predicate on every tenant-scoped table. That choice is load-bearing: it is why a building can fire its firm and keep its data, and why no firm can read across entrances.

PM-ORG-010 permits grouping entrances into a "site" for shared assets — a boiler room, parking, a playground — with its own allocation key. A shared boiler has no single `entrance_id`. It belongs to three. Nothing in the design models it, so today the first engineer to write the lift code decides it by accident.

**The legal facts that settle it.** The general assembly is constituted per entrance (чл. 9, чл. 10). Money is allocated per entrance (чл. 51, чл. 50). **No entrance's assembly can bind another.** A shared boiler therefore cannot be owned by a fourth legal person that does not exist — its costs must reach each entrance as that entrance's own obligation, approved by that entrance's own assembly.

PM-ORG-010 is a MAY, sourced to practice rather than statute. Shared parking and shared heating are nonetheless common in Sofia, and closed complexes under чл. 2 are Door B of the commercial plan.

---

## 2 Decision

**Model a shared facility as an asset with a custodian entrance and an explicit share agreement. Do not add a second tenant axis.**

- A `SharedFacility` has exactly one **custodian entrance** — the entrance that holds the service contract and raises work orders.
- A **share agreement** lists the participating entrances and each one's key or percentage. It is evidenced by the decision of *each* participating assembly, stored with its rule ID and `law_version`.
- Every cost posts as **N separate charges, one per participating entrance**, each traceable to that entrance's own decision and computed by the same pure functions as any other charge.
- `entrance_id` remains the only tenant key. RLS is unchanged.
- A participant sees the facility, its work orders and its costs through an **explicit, audited grant** derived from the share agreement — never through a widened RLS predicate.

The facility is a contract between entrances, and the system stores it as one.

---

## 3 Options considered

| Option | Tenant model | Cost | Verdict |
|---|---|---|---|
| **A · `site_id` as a second tenant axis** | Two keys | Every RLS policy becomes a disjunction. One wrong predicate leaks across entrances — the exact failure the single key exists to prevent. Also implies a "site" can own money, which no law supports | Rejected |
| **B · Custodian entrance + share agreement** ✅ | One key | Costs a join and an explicit grant for participants. The custodian's role must be chosen and recorded | **Chosen.** Matches the legal reality: no entrance binds another |
| **C · Site as a first-class condominium** | One key, new kind | It is not one in law. No assembly, no chair, no fund account under чл. 50 | Rejected |
| **D · Do not support shared facilities in v1** | One key | Cheapest. But it excludes closed complexes — Door B of the commercial plan — and most Sofia blocks with shared parking | Rejected, though it is the honest fallback if §4 exposure cannot be resolved |

---

## 4 Consequences

**Good**

- `entrance_id` stays the single tenant key. The RLS predicate, the isolation argument and the offboarding story are all untouched.
- Each entrance's bill remains explainable from that entrance's own decisions — which is what PM-FEE-018 and a чл. 410 packet actually require.
- Closed complexes become expressible without a new tenancy model.

**Bad, and accepted**

- The custodian entrance carries the contract and therefore the exposure. If a participant's assembly refuses to approve its share, the custodian is out of pocket with only a civil claim against the others.
- Participants read facility data through a grant rather than through tenancy — one more code path, and one more thing to audit.
- Three assemblies must agree before a shared boiler can be repaired. That is slow, and it is also the law.

**How it is enforced**

1. No table carries both `entrance_id` and `site_id`. A migration adding a second tenant column fails CI.
2. A facility cost cannot post without a share agreement whose participants each have a stored decision reference.
3. Shares in an agreement must sum to 100%, as a database constraint.
4. A participant's read of facility data is logged as a disclosure, like any other cross-boundary access.

---

## 5 What would reverse this

- Counsel finds the custodian's exposure unacceptable and recommends that shared facilities be held by an owners' association under чл. 25. That association **is** a legal person, and it would become the custodian — a change of who, not of shape.
- A закон amendment creates a statutory "site" with its own assembly. Then option A becomes correct and this ADR is superseded.

---

## 6 Deferred

- Which allocation key a shared facility uses by default — ideal parts across all participating units is the obvious candidate, but PM-ORG-010 says the site may set its own.
- Whether a closed complex under чл. 2 is modelled as a site of entrances or as a single large entrance. Decide with the first developer customer, not before.

> **For counsel, with the four money rules:** where a boiler serves three entrances and one entrance's assembly refuses to approve its share, what is the custodian entrance's position — and does an owners' association under чл. 25 change it?
