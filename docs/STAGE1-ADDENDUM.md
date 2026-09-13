# Stage 1 addendum — intake, resumable charge runs, staleness

**Amends Stage 1 §4 (deployables), §5 (events) and §7 (NFRs) · 2026-09-13**

Three gaps found by the system-design review. None is a decision — the decisions are ADR-003 and ADR-004. These are the designs that close them.

---

## 1 · `intake` — the fourteenth module

> [!danger] This was the blocking gap
> Gate 1 is defined as "the fee engine reproduces the firm's spreadsheet to the cent." No module could read the spreadsheet. Every migration from an incumbent starts here, and the pilot begins with an import, not with a login.

A module inside `api` per ADR-003, not a deployable.

**Owns** — import jobs, column mappings, validation results, dry-run diffs, commit records.
**Publishes** — `ImportCommitted` · `ImportReverted`.
**Consumes** — nothing.
**Rules** — owns none. It *enforces* ORG and BOOK invariants on the way in, which is a different thing and must stay that way: intake has no authority to define what is legal.

### The six steps

| # | Step | Notes |
|---|---|---|
| 1 | **Store the source file** | Into `evidence`, content-addressed and unmodified. In a later dispute, what the firm actually gave you is the fact that matters |
| 2 | **Profile** | Detect columns, propose a mapping. Never guess silently — an unmapped column is surfaced, not dropped |
| 3 | **Map** | A human confirms. An import writes the legal record, so this is `HUMAN_RELEASE`, never `AUTONOMOUS` |
| 4 | **Validate** | Every ORG and BOOK invariant: ideal parts sum to 100% per entrance, no duplicate unit designation, occupancy ranges sane, business-use flags coherent |
| 5 | **Dry run** | A diff of what would be created and changed — **and a recomputed sample bill compared line by line against the firm's own figures** |
| 6 | **Commit** | One transaction. Every created row carries the `import_id` |

### Two properties that are not optional

**Step 5 is Gate 1, automated.** Every import re-runs the gate. The first import is the gate; the fiftieth is a regression test that the rules still match reality.

**An import is revertible by `import_id`.** The first imports will be wrong. A migration you cannot undo is one the customer will not let you attempt twice.

### `zues-calc` gains the import path

Per ADR-003, Gate 1 ships as a CLI. It now reads the firm's actual file, not a hand-made CSV: **their file in, their bill out, diffed against their numbers.** No authentication, no HTTP, no services.

---

## 2 · The charge run is a resumable job

The 1st of the month is the load peak (Stage 1 §7). A charge run that dies at unit 40 of 60 must not leave a half-billed entrance, and must not recompute what it already wrote.

```
charge_run
  id · entrance_id · period
  basis · basis_hash · law_version · engine_version
  status: PENDING → RUNNING → COMPLETE | FAILED
  cursor
```

| Property | Why |
|---|---|
| **The basis is frozen at run start** | This is what makes resume safe. A resumed run computes against the same snapshot, so unit 41 is billed on the same facts as unit 1 |
| **Lines are idempotent on `(charge_run_id, unit_id)`** | A retry writes nothing twice |
| **Resume skips written lines, never recomputes them** | Recomputation mid-run is how two units in one period end up on different bases |
| **`ChargeIssued` publishes only on COMPLETE** | No partial run is ever visible to a resident or to `rail`. A FAILED run is resumable and invisible |
| **A FAILED run older than the configured window is an incident** | Per §7, a missed statutory deadline caused by the system is an incident, not a bug |

---

## 3 · Staleness — absence of a signal is a signal

PM-LAW-008 says an unreachable source must raise an alert and that silence must never be read as an all-clear. Nothing implemented it, and the same hole exists for every other scheduled input.

Every scheduled input registers `last_success_at` and a `max_silence`:

| Input | Silence means |
|---|---|
| Law watcher (PM-LAW-008) | The statute may have changed and nobody knows |
| PSD2 partner / bank feed | Payments are not being matched; arrears ageing is wrong |
| Outbox drain | Modules are diverging |
| Compliance sweep | Statutory tasks are not being raised — the SLO in §7 is silently breached |

A watchdog compares each against its threshold and raises an **operational task with a named owner**, not a log line.

> [!warning] Who watches the watchdog
> The watchdog must itself check in to an **external dead-man's switch**. A watchdog that can die quietly is worse than none, because it manufactures confidence. This is the only piece of the system that may depend on a third party outside the EU region, and it carries no personal data.

---

## 4 · What this changes upstream

| Document | Change |
|---|---|
| Stage 1 §4 | Fourteen modules. `intake` added |
| Stage 1 §5 | Two events added: `ImportCommitted`, `ImportReverted` |
| Stage 1 §7 | Staleness thresholds are dated config, like the device floor |
| Implementation sequence | `intake` lands **before** A20 charges — Gate 1 cannot be attempted without it |
| ADR-003 | `zues-calc` reads the customer's file directly |
| Functional spec | A fourteenth module section, and the traceability table regenerates |

**Ask the pilot firm for one real spreadsheet this week.** It defines the intake schema, and it will probably invalidate an assumption while that is still cheap.
