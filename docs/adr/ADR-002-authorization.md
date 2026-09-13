# ADR-002 — One hand-written policy module, with row-level security as the backstop

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | — |
| **Blocks** | A12 scaffold, A18 identity-org, A19 registry, every read of owners'-book data |

---

## 1 Context

чл. 7 ал. 4 ЗУЕС does not describe a role list. It describes a **relationship**: the management board and the manager may read the book, the controller may read all condominium documentation (PM-GOV-008), an owner may read *their own* data (PM-SEC-002), and the Ministry of Interior may read it only through a logged lawful request (PM-SEC-003). Firm staff get access **per building**, not per firm (PM-PMC-007), and every act is attributable to a named individual, never to the company (PM-PMC-013).

Hand-rolled relationship-based authorization is where security defects concentrate, which argues for an engine. Three facts argue against.

**The relation set is fixed by statute and small.** Zanzibar-style engines earn their complexity when users create arbitrary sharing graphs at runtime — share this document with that person. Nothing here is shared at runtime. The relations are the ones чл. 7 ал. 4 enumerates, and adding one requires an amendment to the law.

**The hard requirement is temporal, not relational.** PM-SEC-011: the system must prove, for any past date, who held which role in which building. Mandates expire at two years (PM-GOV-004); grants change. Policy engines model *now*. Asking one to answer "who could read this in March 2025" is the same fight ADR-001 had with rules engines, for the same reason.

**Authorization sits in the hot path of every read.** A network hop to an external decision point on every book access is latency the p95 budget in Stage 1 §7 cannot absorb.

---

## 2 Decision

**One hand-written policy module — `packages/policy` — holding the чл. 7 ал. 4 matrix exactly once, evaluated against effective-dated role grants. Postgres row-level security is the independent backstop.**

Two layers, and they fail independently:

| Layer | Scope | Catches |
|---|---|---|
| **RLS on `entrance_id`** | Coarse, at the database | Any cross-entrance leak, including one caused by a bug in the policy module |
| **`packages/policy`** | Fine, in the application | Who inside an entrance may see what, as at which date, for which purpose |

That independence is the point. A defect in the policy module cannot leak data across entrances, because the database refuses the rows regardless. No single mistake is sufficient.

The policy module takes `(subject, action, resource, as_at_date)` and returns a decision plus the rule ID that produced it. The date is a required argument, not a default — resolving a past authorization question is an ordinary call, not a special path.

---

## 3 Options considered

| Option | Temporal queries | Hot-path cost | Verdict |
|---|---|---|---|
| **OpenFGA / Zanzibar-style** | Models *now*. Answering PM-SEC-011 means replaying tuple history, which is not what it is for | Network hop per check | Rejected. Pays for runtime sharing flexibility that a statute forbids us from using |
| **Cedar / Oso — embedded policy DSL** | Better, still awkward as-at | In-process, acceptable | Rejected, narrowly. The policy is a dozen statutory rules; a DSL puts a translation layer between чл. 7 ал. 4 and the code, and rule IDs end up outside the type system |
| **Framework RBAC (guards + roles)** | None | Cheap | Rejected. чл. 7 ал. 4 is relationship plus time. A role name cannot express "their own unit" |
| **Hand-written policy module + RLS backstop** ✅ | A required argument | In-process | **Chosen.** One implementation, rule IDs in the types, and a second independent layer that does not share its bugs |

---

## 4 Consequences

**Good**

- One implementation of the matrix. `packages/policy` is named in the repo layout for exactly this reason — the same check cannot drift between modules.
- A past authorization question is an ordinary call with a date argument.
- Every decision returns the rule ID that produced it, so a disclosure log records *why*, not only *what*.
- No vendor in the hot path, no external decision point to operate.

**Bad, and accepted**

- We own the correctness. An engine would have brought a tested evaluator; we bring tests instead.
- A future requirement for genuine runtime sharing — an owner delegating read access to their accountant, say — would strain the model and should reopen this.

**How it is enforced**

1. No module implements its own permission check. Importing anything other than `packages/policy` for an authorization decision fails the dependency rule in CI.
2. Every read of owners'-book data writes a disclosure record with actor, purpose, timestamp and rule ID (PM-BOOK-007).
3. RLS is on for every tenant-scoped table, with a test that attempts a cross-entrance read as each role and expects zero rows.
4. A test replays a past date and asserts the role set matches the grants in force then (PM-SEC-011).
5. ЕГН and full addresses are absent from every list-shaped response by type, not by filter (PM-BOOK-011).

---

## 5 What would reverse this

- A requirement for owner-initiated runtime sharing outside the statutory matrix.
- More than about three consumers of the policy module outside this codebase — at which point an external decision point starts to pay for itself.

---

## 6 Deferred

- Whether the Ministry of Interior lawful-request workflow (PM-SEC-003) is a policy path or a separate audited export. Decide when the first request arrives; until then it is not implemented at all.
