# ADR-006 — Integer minor units for money, exact decimals for ideal parts, double-entry per entrance

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | — |
| **Blocks** | A12 ledger schema, A20 charges, `zues-calc` and Gate 1 |

---

## 1 Context

Three of the nine non-negotiables are one schema decision: how money, ideal parts and the ledger are typed. A wrong type here is not a performance defect — it is a wrong number on a bill a court reads.

**Ideal parts are a constraint, not a measurement.** PM-ORG-002 (чл. 7 ал. 2 т. 2): ideal parts are a percentage, the sum per entrance must equal 100%, and a set summing to 99.98% is rejected *with the delta shown*. PM-ORG-005 (чл. 17) subdivides a co-owned unit by title share. A binary float represents neither exactly, so `= 100` degrades into a comparison against an epsilon — a tolerance on a statutory constraint, with a rounding error for an alibi.

**Money is a count, in a stated currency.** PM-FEE-016: amounts are stored in euro as integer minor units; pre-2026 records keep the original BGN amount, the rate 1.95583 and the converted value.

**An issued bill is a document, not a current value.** PM-FEE-014: a charge is computed against a snapshot of headcount, ideal parts, multipliers and tariff, so May 2026 re-run next year reproduces identical figures. PM-FEE-015: an issued bill is never altered — corrections are credit or debit notes.

**Why not a balance column.** A stored position answers *how much* and destroys *why*. PM-DEBT-008 (ЗЗД чл. 76) clears the oldest debt first unless the payer designates otherwise, **and the applied rule must be visible**. PM-FUND-009 wants the fund net of committed but unpaid work orders — two numbers from one fund. PM-FEE-018 and a чл. 410 packet demand the derivation. One mutable cell answers none of the three, and every correction against it is the `UPDATE` PM-FEE-015 forbids.

---

## 2 Decision

**Money is `BIGINT` minor units with an explicit currency. Ideal parts are `NUMERIC`. Every position is derived from append-only double-entry postings, one fund per entrance.**

| Quantity | Type | Because |
|---|---|---|
| Money | `BIGINT` minor units + currency | PM-FEE-016; exact, and rounds only where a rule says |
| Ideal parts | `NUMERIC` percent | PM-ORG-002 needs an exact `= 100`, PM-ORG-005 subdivides it |
| Pre-2026 record | amount + rate `1.95583` + converted value | PM-FEE-016; display is read, not re-derived |

- Every posting has at least two legs summing to zero, within **one entrance** and one currency (PM-FUND-005, PM-PMC-008).
- Postings are append-only; a correction is a new posting — a credit or debit note referencing the original (PM-FEE-015).
- What a unit owes, what the fund holds and what is available are **queries over postings**; `balance` is not a column name anywhere.
- An allocation stores the rule it applied — oldest first, or the payer's designation — and its remainder rule in `basis`, so allocated minor units sum to the total exactly (PM-DEBT-008).
- A charge run stores `basis`, `basis_hash`, `law_version` and `engine_version` (ADR-001): re-running a past period **verifies** rather than re-derives (PM-FEE-014).

---

## 3 Options considered

**Typing money and ideal parts**

| Option | Exact · constrainable in Postgres | Verdict |
|---|---|---|
| **Binary float** | No · no | Rejected. Cannot represent 0.10 or 4.20; PM-ORG-002's 100% becomes an epsilon comparison, and PM-FEE-016 bans it |
| **Decimal as a string, `TEXT`** | Yes · no | Rejected. The arithmetic is fine and the database goes blind — no sum, no constraint, no index. PM-ORG-002's check leaves Postgres, against ADR-001 |
| **`NUMERIC` for money too** | Yes · yes | Rejected for money, kept for ideal parts. A scale finer than the currency is meaningless, a division silently produces one, and rounding lands in SQL rather than a tested pure function |
| **`BIGINT` minor units + `NUMERIC` parts** ✅ | Yes · yes | **Chosen.** Money is counted; ideal parts are a proportion that must sum exactly |

**Deriving a position**

| Option | Answers "why" | Verdict |
|---|---|---|
| **Balance column, ± audit log** | No | Rejected. No чл. 410 derivation, no way to show PM-DEBT-008's applied rule, and a log beside it is a second source of truth that drifts |
| **Single-entry transaction list** | Yes | Rejected. It sums, but nothing forces money to come from somewhere, and PM-FUND-009 needs a second convention bolted on |
| **Double-entry, one fund per entrance** ✅ | Yes | **Chosen.** PM-FUND-005's "cross-entrance postings are impossible" becomes a property of the posting, and both PM-FUND-009 figures fall out of one query |

---

## 4 Consequences

**Good**

- A past bill is verified against its stored result, not recomputed and hoped over (PM-FEE-014).
- Every figure has a derivation — which charges, which payments, which rule allocated them — and a чл. 410 packet is a read of it (PM-DEBT-008, PM-FEE-018).
- Posted and available come from one query, so PM-FUND-009's two figures cannot disagree.

**Bad, and accepted**

- Every position is a query. Statements need indexes and eventually period snapshots — a cache, rebuildable from postings.
- Staff will try to edit an issued bill. The interface must make the credit note the obvious action, not the punishment.
- Every division states its rounding and remainder rule in `basis`. More code per allocation.
- One implicit `parseFloat` between the driver and a `NUMERIC` column restores the defect this decision removes.

**How it is enforced**

1. A CI schema check: no `real`, `double precision` or `float` column anywhere; money is `BIGINT` with a currency, ideal parts `NUMERIC`. No column named `fee` or `balance`.
2. Per entrance, active units' ideal parts sum to exactly 100, the rejection reporting the delta (PM-ORG-002); co-owner shares sum to their unit's parts and count once in a tally (PM-ORG-005).
3. No `UPDATE` and no `DELETE` on postings — revoked at the database role, not agreed in review (PM-FEE-015).
4. A posting whose legs do not sum to zero within one entrance and one currency is rejected at the ledger layer (PM-FUND-005, PM-PMC-008).
5. An allocation without its applied rule, or a pre-2026 row missing amount, rate or converted value, fails the build (PM-DEBT-008, PM-FEE-016).
6. A test re-exports a past period and asserts identical figures, over ADR-001 check 8's fixture set (PM-FEE-014).

Checks 3 and 6 matter most, each proved against a failure: change a rounding step and confirm 6 goes red; `UPDATE` a posting as the application role and confirm it is refused.

---

## 5 What would reverse this

- A second ledger currency — a foreign reserve, or a jurisdiction outside the euro. Postings gain a currency dimension and a revaluation convention; integer minor units survive unchanged.
- A statutory allocation key that is neither a proportion of ideal parts nor a count. None exists; if one arrived the pure function changes, not the storage.

Performance is not on this list. A slow statement is answered with an index or a rebuildable snapshot, never a mutable balance column.

---

## 6 Deferred

- The split convention for a mid-period ownership change. PM-ORG-011 defers it to "the configured convention" — a dated value in `@zues/law`.
- Whether arrears interest posts daily or at statement. Posting volume, not model shape.
- Whether period snapshots are materialised, and at what cadence. Only once a real portfolio exists.
