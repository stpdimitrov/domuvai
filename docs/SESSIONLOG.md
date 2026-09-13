# Session log

Append only. Never edit an earlier entry. Commit the entry with the work it describes.

---

## S-00 · 2026-09-13 · Stage 1 closed, repo seeded

**Did** — moved Stage 1 out of a personal vault and into this repo, so any session (or any person) boots from the same place. Nine ADRs written; ADR-001 Accepted, eight Proposed.

**Decisions** — ADR-001 … ADR-009. Three changed the design materially:
- **ADR-003** replaced thirteen independently deployed services with three deployables and fourteen CI-enforced module boundaries. The trigger was a correctness bug, not taste: `money` consumed ideal parts and occupancy as *events*, so a charge run computed from projections that could lag. A wrong bill with a valid `basis_hash` and a clean audit trail is worse than an obvious error.
- **ADR-001 amendment** added `engine_version` to every receipt. `law_version` pinned the data but nothing pinned the code, so a bug fix in `computeCharge` would silently rewrite history.
- **ADR-004** settled shared facilities as a cost-sharing agreement with a custodian entrance, keeping `entrance_id` as the only tenant key.

**Added** — `intake` as the fourteenth module. Gate 1 says "reproduces the firm's spreadsheet to the cent" and nothing could read a spreadsheet.

**Open** — A7 traceability · A8 event schemas · A9 OpenAPI · A10 DDL · A11 test plan. Eight ADRs await Accept. Counsel and the pilot firm are unblocked by nothing in here.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/adr/ADR-003`, `docs/SEQUENCE.md`.

---

## S-01 · 2026-09-13 · A8 — event contracts

**Did** — generated the envelope plus one JSON Schema and one worked example per event, from a single catalogue in `tools/build_events.py`. Schemas are generated, never hand-written, so the contract cannot drift from the catalogue.

**Found** — the documents said **17 events**. There are **38**. The count had been stale since the deployables table grew; corrected in `docs/INDEX.md` and ADR-003. Third stale count found in this project by generating instead of reading.

**Two guards, both proved against a real failure:**
- `build_events.py` fails if a module publishes an event with no schema, or if a schema exists that nothing publishes. The first version of the check scanned whole documents and flagged `IdealParts` and `ManagementCharge` as missing events — entity names, not events. Rewritten to read only the *Publishes* column.
- `validate_events.py` validates every example against the envelope and its payload schema. Broke it deliberately twice: a float in `Money.amount_minor` → rejected; a missing `entrance_id` → rejected. Restored, green.

**Types that now fail validation rather than review:** money as a float, ideal parts as a float, an implicit majority denominator, an event without `entrance_id` or `law_version`.

**Open** — A7 traceability · A9 OpenAPI · A10 DDL · A11 test plan. Eight ADRs still Proposed.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/events/README.md`.

---

## S-02 · 2026-09-13 · zues-calc — Gate 1 in a command line

**Did** — the first production code. `@zues/kernel` (Money, IdealParts), `@zues/law` (dated constants, allocation keys), `@zues/charges` (pure `computeChargeRun`), and the `zues-calc` CLI. No database, no HTTP, no services.

**20 tests, every one named after the rule it proves.** Not only happy paths — each test also proves the guard fires: a float rejected as money, ideal parts summing to 99.999999% rejected, a repair fund allocated per person rejected, a tariff line with no GA decision rejected, a business multiplier of 9 rejected as outside the statutory range.

**Types do the work the code would otherwise have to remember.** `eur(42.5)` throws — money is integer minor units by construction (PM-FEE-016). Ideal parts are integer millionths of a percent, so `0.1 + 0.2 === 0.3` holds exactly where floats would not (PM-ORG-002). Largest-remainder allocation is property-tested across totals and weightings: a split pot never invents or loses a cent.

**Gate 1 works in both directions.** With correct figures: `✓ GATE 1 — reproduces their spreadsheet to the cent`, exit 0. Change one unit by a single cent: the differing line is named, exit 1. A gate never run against a failure is not a gate.

**Found and fixed while reading the output.** The derivation line for a business unit read `5.00 € × 6 person(s) · business ×3` — the multiplier was in the weight *and* stated again, so a resident would see six people in a two-person office. Now `5.00 € × 2 person(s) · business ×3`. The arithmetic was always right; the explanation was wrong, and PM-FEE-018 is about the explanation.

**Every run ends by naming its unconfirmed constants** — currently three: `ABSENCE_EXEMPTION_DAYS`, `BUSINESS_USE_MULTIPLIER_MIN/MAX`. Those are the questions for counsel, printed at the point of use rather than buried in a document.

**Open** — this is the tool, not the answer. It proves nothing until a real firm's spreadsheet goes through it. A7 traceability · A9 OpenAPI · A10 DDL · A11 test plan. Eight ADRs still Proposed, and this code depends on ADR-006 and ADR-008 among them.

**Read first next time** — `docs/INDEX.md`, this entry, `apps/zues-calc/README.md`.

---

## S-03 · 2026-09-13 · A7 + A13 — the gate pack

**Did** — traceability generator and six CI gates. Drift is no longer something anyone has to remember; it is a red build. `./tools/gates.sh` runs the same six checks locally that CI runs on every push and pull request.

| Gate | Fails when |
|---|---|
| tests | any test fails |
| event contracts | a schema drifts from the catalogue, or an example violates it |
| functional spec | a rule has no module |
| traceability | a rule ID in source is absent from `rules.json` |
| banned words | `tenant`, `building`, or `fee`/`balance` as an identifier |
| legal thresholds | a statutory number is used as a threshold outside `@zues/law` |
| generated docs | a generated document is out of sync with its generator |

**The honest number: 14 of 233 rules — 6% — are proved by a test named after them.** That is now generated into `docs/TRACEABILITY.md` per domain, so it cannot be forgotten or rounded up. A `// Rule:` comment with no test counts as a claim, not as evidence, and the report says so.

**Two gates failed their own negative test, and both failures were instructive.**

*The legal-threshold check started with 67 hits* — column widths, `/100` for cents, regex quantifiers `{1,3}`. A gate with that noise is a gate switched off within a day, which is worse than no gate. Rewritten to flag only a watchlist number used as a **threshold**: either side of a comparison, or a multiplier on a variable. Strings, regexes and comments stripped; test files excluded, since naming an expected value is what a test is for. Result: 67 → 1, and the survivor was a genuine cent conversion, annotated `// not-legal:`. The watchlist itself is generated from the rule texts, never typed.

*Orphan rule IDs were not detected at all.* The scanner only read lines matching `// Rule:` or `* Rule:`, so `PM-ZZZ-999` written mid-sentence inside a block comment passed straight through. Split the two concerns: attribution stays strict (only a `Rule:` tag claims an implementation), orphan detection is now broad (any `PM-XXX-000` anywhere in source must exist in the catalogue). Both retested against deliberate failures.

The `docs/` sync test also gave a false pass first time — appending to a generated file proved nothing, because the generator simply overwrote it. The real failure mode is editing `rules.json` without regenerating, which now fails correctly.

**Every one of the six gates has been run against a deliberate failure and seen to fire.** That is the standing rule, applied to the tool that enforces the standing rules.

**Open** — A9 OpenAPI · A10 DDL · A11 test plan. Eight ADRs still Proposed. Coverage floor not yet set: pass `--min-coverage` once there is a number worth defending.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/TRACEABILITY.md`.

---

## S-04 · 2026-09-13 · Seven of nine ADRs Accepted

**Did** — ADR-002, 003, 005, 006, 008 and 009 move to Accepted, joining ADR-001. These have a single named decider and they unblock A9, A10, A12, A14, A15 and the Phase 3 modules.

**ADR-004 and ADR-007 stay Proposed on purpose.** Both name counsel as a co-decider: the custodian entrance's exposure where one assembly refuses its share of a shared facility, and the PSD2 licensing route. Neither blocks near-term work — ADR-004 gates `registry` and `maintenance`, ADR-007 gates `rail` in Phase 5. Accepting them without the legal answer would be recording a decision nobody made.

**Fixed a gate that would have been ignored within a week.** The generated-docs check compared all of `docs/`, so editing an ADR by hand failed the build. Hand-written documents changing is normal; a gate that punishes it is noise, and noise gets switched off — the same failure mode as the 67 false positives in the legal-threshold check. Narrowed to the three generated paths: `docs/FUNCTIONAL.md`, `docs/TRACEABILITY.md`, `docs/events`.

**And I got the negative test wrong twice before getting it right.** Appending a line to a generated file proves nothing: the generator simply overwrites it. The real failure mode is changing the *source* — `rules.json` — without regenerating, which is what CI does on a clean checkout. Tested that way: two generated documents reported out of sync, exit 1. Control case also verified: a hand-edited ADR leaves the build green.

**Open** — counsel on ADR-004 §4 and ADR-007 §2, alongside the four money rules. A9 · A10 · A11 now unblocked. The repo still needs pushing, and the spreadsheet is still the only thing that can tell us whether the rules are right.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/adr/`.

---

## S-05 · 2026-09-13 · A11 — the test plan

**Did** — `tools/testplan.py` turns the 233 rules into a ranked, sliceable worklist at `docs/TESTPLAN.md`. Generated from `rules.json` and the current traceability, so it shrinks as tests land and can never claim coverage the source tree does not have. Added to the gate pack, now seven checks.

**220 rules remaining, in 22 slices.** Every rule in the catalogue carries an acceptance criterion, so every one is testable as written — there is nothing to invent, only to do.

**Ordered by the gate each rule serves**, because a gate is the only thing that can tell us the rules are *wrong*; everything else is scheduling. Within a gate: MUST before SHOULD, and rules whose number is unconfirmed come first — the mechanism is testable today with the number read from configuration while counsel answers. Gate 1 is 57 rules across 5 slices; Gate 2 is the largest at 8.

**Two passes to get the slicing right.** The first produced 48 slices including several of one rule, because it broke a slice whenever the *domain* changed — but FEE, FUND and DEBT all live in `money`, so a single FUND rule between FEE rules split the run. Sorting by module before modality fixed the fragmentation, and merging any tail shorter than four rules back into the slice before it took 48 → 29 → **22**. A one-rule slice is not a slice; it is an errand.

**This is the queue for Claude Code.** Each slice names one module, its rule IDs and what each test must prove, in the shape `zues-slice` expects. `S-G1-01` is the next thing anyone should pick up.

**Open** — A9 OpenAPI · A10 DDL, both now unblocked by the seven Accepted ADRs. Counsel on ADR-004 §4, ADR-007 §2 and the four money rules. The spreadsheet.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/TESTPLAN.md`.

---

## S-06 · 2026-09-13 · A10 — the database schema

**Did** — `db/migrations/0001_init.sql`: 23 tables across 8 schemas, covering the entity contract in `docs/RULES.md` §4. Applied against a real PostgreSQL 16, not written and hoped over. `db/test/constraints.sh` puts every invariant the schema claims against a deliberate violation — **13 checks, all firing.**

**An invariant the schema can enforce is never left to application code.** Ideal parts summing to 100% is a deferred constraint trigger, so a multi-row import can reach a valid state before COMMIT judges it. A journal that does not balance to zero is rejected the same way. A mandate longer than two years is a CHECK, not a reminder. Votes, postings and documents have UPDATE and DELETE rules that make them append-only. One IBAN belongs to one entrance and one purpose, so commingling is unrepresentable.

**Found by running it: a money column that silently rounds.** `amount_minor` was `bigint`, and inserting `42.5` stored **43** — Postgres rounds on an assignment cast, so the domain never sees the fraction. That is precisely how a wrong bill acquires a clean audit trail, and it defeats PM-FEE-016 while appearing to honour it. `numeric(18,0)` does the same. Only unconstrained `numeric` with `CHECK (scale(VALUE) = 0)` refuses the value instead of rounding it. Tested all three side by side; the domain is now numeric. This was invisible on paper and obvious after one INSERT.

> **CHANGE PLAN — needs a decision, not a silent edit.**
> `docs/RULES.md` §4 says `ideal_parts_pct` is `DECIMAL(7,4)` — four decimal places. `packages/kernel` accepts six (`^\d{1,3}\.\d{1,6}$`, millionths of a percent). The schema follows the rule, so the two now disagree by two digits.
>
> Per `CLAUDE.md` the rule wins, which means narrowing the kernel to four decimals — a change to shipped code and its tests, and to what `zues-calc` will accept from a firm's file. The alternative is amending the rule to six decimals, which is an edit to the requirements source and wants a reason.
>
> **What decides it: what a Bulgarian title deed actually states.** If deeds carry four decimals, four is right and six is false precision. Ask counsel alongside the four money rules. Until then the schema is the stricter of the two, which fails safe.

**Open** — the precision decision above · A9 OpenAPI · A12 scaffold. Two ADRs still with counsel.

**Read first next time** — `docs/INDEX.md`, this entry, `db/migrations/0001_init.sql`.
