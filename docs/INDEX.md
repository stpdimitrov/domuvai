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
| [011](adr/ADR-011-frontend-topology.md) | Frontend is a separate Next.js app; contract-first (OpenAPI-generated client); build gate-by-gate; **monorepo** (`web/` here); **OIDC · stateless api · Next.js BFF session** (provider deferred, Keycloak marked) | **Accepted** |
| [012](adr/ADR-012-intake-format-agnostic.md) | Intake is format-agnostic — a per-import column mapping onto known domain fields, not a canonical spreadsheet; a pilot sheet is validation, not schema; a go-live gate caps the risk (§7) | **Accepted** |

**Ten of twelve Accepted.** ADR-004 and ADR-007 name counsel as a co-decider and stay Proposed until counsel answers (ADR-004 gates A19/A32, ADR-007 gates `rail` in Phase 5). **ADR-011** (Accepted 2026-09-21) settles the frontend as a **monorepo** (`web/` in this repo) with **OIDC · stateless api · Next.js BFF** auth — the provider deferred to the first FE auth slice (Keycloak marked as the default); the backend stays frontend-agnostic (REST + OpenAPI). **ADR-012** (Accepted) makes intake format-agnostic — the remaining intake work proceeds **without** a pilot spreadsheet, held to a **go-live gate** (§7): build freely, do not bill real money until a real sheet reproduces to the cent.

**Frontend status:** **scaffolded, UI-first** (PRs #14–#22, 2026-09-22/23) — a marketing landing + the 7-screen manager console (`/portfolio · /entrance · /entrance/charges · /entrance/fund · /debts · /compliance · /assembly`), built from the owner's Claude Design canvases as real responsive React with **typed mock data** (no live `fetch` yet). Green light = *Gate 1 backend contract-complete* — see ADR-011 §3. `registry`, `money` and `intake` for Gate 1 are largely built — units · households · animals · absence · owners/parties · charge compute/persist/post · fund account · unit statement · arrears ageing · intake dry-run + import record · **intake commit** (adopt a reviewed sheet into `registry`, stamped with `import_id` and revertible — S-36). The cross-module **write seam is settled** — outbox events `ImportCommitted`/`ImportReverted`, per MODULE-TEMPLATE laws 1/3 — so the commit *mechanism* is in place. The intake *rich mapping* is built **format-agnostic** (ADR-012): it maps any firm's columns onto our known domain fields per import, so it **no longer waits on the pilot spreadsheet**. A real sheet becomes **validation** (reproduce-to-the-cent against real numbers), not a schema. **Green light reached (S-41, 2026-09-21):** the mapping is exposed through the API (`POST …/fee-sheet/profile` + a confirmed mapping on dry-run/record/commit) and the intake OpenAPI is frozen — **Gate 1 backend is contract-complete.** The Gate-1 frontend can start as a `web/` app in this repo (ADR-011 Accepted: monorepo · OIDC/BFF), on the owner's go-ahead. S-41b (adopt the optional mapped fields into `registry`) enriches what a commit *does*; it is not a contract blocker. **Deviation to converge** (ADR-011 amendment 2026-09-23): the screens are **UI-first, ahead of the generated OpenAPI client** — the next FE step is **generating the TS client to replace the mock data** (which restores ADR-011 §2.2's enforced boundary). The CI gap is closed: `web/` is **gated in CI** since WEB-10 (`.github/workflows/web.yml` — `npm ci` + `next build` incl. the strict type-check, folder-scoped to `web/**`). The `assembly` / `compliance` screens run **ahead of their backend** — they are design-validated shells, not feature-complete.

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
