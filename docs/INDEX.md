# Index

**domuvai · Stage 1 · rule catalogue v1.3 · legal baseline 2026-09-03**

Boot from this file. Everything a session needs is in this repo; nothing needed to start work lives anywhere else.

## Documents

| # | File | Answers |
|---|---|---|
| 00 | [DEVBRIEF.md](DEVBRIEF.md) | **A developer starts here.** Words, modules, diagrams, the nine non-negotiables |
| 01 | [FUNCTIONAL.md](FUNCTIONAL.md) | **A non-developer starts here.** 16 modules, who uses them, what the product refuses to do |
| 02 | [STAGE1.md](STAGE1.md) | Module contracts, events, standards, NFRs |
| 02a | [STAGE1-ADDENDUM.md](STAGE1-ADDENDUM.md) | `intake`, resumable charge runs, staleness alarms |
| 03 | [ARCHDETAIL.md](ARCHDETAIL.md) | Five detailed drawings |
| 04 | [SEQUENCE.md](SEQUENCE.md) | **The plan.** 36 actions, 6 phases, four gates |
| 05 | [WORKING.md](WORKING.md) | How two people work without sharing AI context |
| 06 | [SAASPLAN.md](SAASPLAN.md) | Who pays, how much, the 90-day pilot |
| 07 | [UIPROMPTS.md](UIPROMPTS.md) | Design prompts, mobile and desktop |
| 08 | [MODULE-TEMPLATE.md](MODULE-TEMPLATE.md) | The extraction-ready module shape every slice follows (Kotlin · Spring Modulith) |
| — | [RULES.md](RULES.md) · [rules.json](rules.json) | The requirements source. 233 rules |
| — | [TRACEABILITY.md](TRACEABILITY.md) | Rule → test → implementation. Generated |
| — | [TESTPLAN.md](TESTPLAN.md) | The remaining rules as ranked slices. Generated |
| — | [api/openapi.json](api/openapi.json) | The HTTP contract. Generated, validated as OpenAPI 3.1 |
| — | [SESSIONLOG.md](SESSIONLOG.md) | Boot here every session — top entry only |

## Decisions

| ADR | Decision | Status |
|---|---|---|
| [001](adr/ADR-001-rules-mechanism.md) | Dated data + pure functions, no rules engine | **Accepted** |
| [002](adr/ADR-002-authorization.md) | One policy module, RLS as independent backstop | **Accepted** |
| [003](adr/ADR-003-deployment-topology.md) | Three deployables, fourteen modules, CI-enforced boundaries · amended 2026-09-14 (JVM enforcement) | **Accepted** |
| [004](adr/ADR-004-shared-facilities.md) | A shared facility is a cost-sharing agreement, not a tenant | Proposed — counsel |
| [005](adr/ADR-005-entrance-as-isolation-unit.md) | The entrance is the isolation unit; the account belongs to it | **Accepted** |
| [006](adr/ADR-006-money-and-numbers.md) | Integer minor units, exact decimals, double-entry per entrance | **Accepted** |
| [007](adr/ADR-007-no-custody.md) | The platform never holds money | Proposed — counsel |
| [008](adr/ADR-008-explicit-denominator.md) | Every majority carries its denominator explicitly | **Accepted** |
| [009](adr/ADR-009-agent-holds-no-credential.md) | The agent holds no write credential | **Accepted** |
| [010](adr/ADR-010-backend-language.md) | Backend is Kotlin · Spring Boot · Spring Modulith; frontend stays Next.js/TS | **Accepted** |
| [011](adr/ADR-011-frontend-topology.md) | Frontend is a separate Next.js app; contract-first (OpenAPI-generated client); build gate-by-gate; repo layout (mono vs poly) open | Proposed — owner |
| [012](adr/ADR-012-intake-format-agnostic.md) | Intake is format-agnostic — a per-import column mapping onto known domain fields, not a canonical spreadsheet; a pilot sheet is validation, not schema | Proposed — owner |

**Eight of twelve Accepted.** ADR-004 and ADR-007 name counsel as a co-decider and stay Proposed until counsel answers (ADR-004 gates A19/A32, ADR-007 gates `rail` in Phase 5). **ADR-011** awaits the owner's repo-layout pick before the frontend starts; the backend is unaffected — it stays frontend-agnostic (REST + OpenAPI). **ADR-012** awaits the owner's accept — it makes intake format-agnostic, so the remaining intake work proceeds **without** a pilot spreadsheet.

**Frontend status:** not started (backend only, S-09…S-36). Green light = *Gate 1 backend contract-complete* — see ADR-011 §3. `registry`, `money` and `intake` for Gate 1 are largely built — units · households · animals · absence · owners/parties · charge compute/persist/post · fund account · unit statement · arrears ageing · intake dry-run + import record · **intake commit** (adopt a reviewed sheet into `registry`, stamped with `import_id` and revertible — S-36). The cross-module **write seam is settled** — outbox events `ImportCommitted`/`ImportReverted`, per MODULE-TEMPLATE laws 1/3 — so the commit *mechanism* is in place. The remaining intake work — the *rich mapping* — is now built **format-agnostic** (ADR-012): it maps any firm's columns onto our known domain fields per import, so it **no longer waits on the pilot spreadsheet**. A real sheet becomes **validation** (reproduce-to-the-cent against real numbers), not a schema. The assistant announces the green light once the mapping lands and the OpenAPI is frozen.

## Stage 1 — done and not done

| Artefact | |
|---|---|
| Rule catalogue, 233 rules | done |
| Functional specification | done |
| Module catalogue, 14 modules / 3 deployables | done |
| Event catalogue, 38 events | done |
| Cross-cutting standards, NFRs, build order | done |
| Ten ADRs | done — 8 Accepted, 2 awaiting counsel |
| A7 traceability generator | done |
| A8 event JSON schemas | done |
| A9 OpenAPI | done |
| A10 SQL DDL | done |
| A11 test plan across 233 rules | done |

A8 is the serialisation point: it must be merged before two people build modules in parallel.

## Blocked on answers from outside this repo

- [ ] Payments — partner licence, or own PISP authorisation from БНБ (A4)
- [ ] Counsel on the four money-touching unverified rules (A3)
- [ ] Counsel on the custodian's exposure in ADR-004 §4
- [ ] QES provider for absentee ballots — Evrotrust or B-Trust
- [ ] **A signed pilot firm, and one real fee spreadsheet** (A5) — still the highest-value *validation* (reproduce-to-the-cent against real numbers), but **no longer a blocker**: ADR-012 makes intake format-agnostic, so the work proceeds without it
