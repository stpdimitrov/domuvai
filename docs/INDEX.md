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
| — | [RULES.md](RULES.md) · [rules.json](rules.json) | The requirements source. 233 rules |
| — | [SESSIONLOG.md](SESSIONLOG.md) | Boot here every session — top entry only |

## Decisions

| ADR | Decision | Status |
|---|---|---|
| [001](adr/ADR-001-rules-mechanism.md) | Dated data + pure functions, no rules engine | **Accepted** |
| [002](adr/ADR-002-authorization.md) | One policy module, RLS as independent backstop | Proposed |
| [003](adr/ADR-003-deployment-topology.md) | Three deployables, fourteen modules, CI-enforced boundaries | Proposed |
| [004](adr/ADR-004-shared-facilities.md) | A shared facility is a cost-sharing agreement, not a tenant | Proposed |
| [005](adr/ADR-005-entrance-as-isolation-unit.md) | The entrance is the isolation unit; the account belongs to it | Proposed |
| [006](adr/ADR-006-money-and-numbers.md) | Integer minor units, exact decimals, double-entry per entrance | Proposed |
| [007](adr/ADR-007-no-custody.md) | The platform never holds money | Proposed |
| [008](adr/ADR-008-explicit-denominator.md) | Every majority carries its denominator explicitly | Proposed |
| [009](adr/ADR-009-agent-holds-no-credential.md) | The agent holds no write credential | Proposed |

**No production code until the eight Proposed ADRs are Accepted.**

## Stage 1 — done and not done

| Artefact | |
|---|---|
| Rule catalogue, 233 rules | done |
| Functional specification | done |
| Module catalogue, 14 modules / 3 deployables | done |
| Event catalogue, 19 events | done |
| Cross-cutting standards, NFRs, build order | done |
| Nine ADRs | done — 8 awaiting Accept |
| **A7 traceability generator** | not started |
| **A8 event JSON schemas** | not started |
| **A9 OpenAPI per module** | not started |
| **A10 SQL DDL** | not started |
| **A11 test plan across 233 rules** | not started |

A8 is the serialisation point: it must be merged before two people build modules in parallel.

## Blocked on answers from outside this repo

- [ ] Payments — partner licence, or own PISP authorisation from БНБ (A4)
- [ ] Counsel on the four money-touching unverified rules (A3)
- [ ] Counsel on the custodian's exposure in ADR-004 §4
- [ ] QES provider for absentee ballots — Evrotrust or B-Trust
- [ ] **A signed pilot firm, and one real fee spreadsheet** (A5) — the cheapest, highest-value item in the plan
