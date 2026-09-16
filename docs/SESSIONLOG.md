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

---

## S-07 · 2026-09-13 · A9 — the HTTP contract

**Did** — `tools/build_openapi.py` generates `docs/api/openapi.json`: **26 operations across 7 modules, citing 56 rules, validated as OpenAPI 3.1.** One spec, tagged by module, because ADR-003 says one deployable — a spec per module would describe a deployment that no longer exists. Added to the gate pack, now eight checks.

**Conventions are declared once, not repeated per path.** A convention restated sixty times is a convention that drifts. Six hold everywhere: `entrance_id` is the only tenant key so nothing is addressable across entrances; every write carries `Idempotency-Key` so a queued offline action replays without duplicating; money is integer minor units; a statutory precondition cannot be skipped, and 409 is the refusal; statutory documents are Bulgarian whatever the `Accept-Language`.

**And one that is worth more than it looks: a refusal names the rule that refused.** The problem body carries `rule_id`, so a client can tell a домоуправител *which article stopped them* instead of "invalid request". For a product whose whole claim is that the law is the specification, an error that cannot cite the law is a broken promise.

**Two guards, both proved against a real failure.** An operation citing a rule ID absent from `rules.json` fails the build — planted `PM-ZZZ-999`, exit 1. A spec that is not valid 3.1 fails the build — broke the `info` object, exit 1. Then clean, exit 0.

**I got the negative tests wrong first, again.** `python3 … | head; echo $?` reports the exit status of `head`, so both guards appeared to pass while proving nothing. Third time this session a test harness has lied by reading the wrong exit code. Worth remembering as its own rule: **when a check reports success, confirm it was the check that reported it.**

**Open** — A12 scaffold, which is the line where the work moves to Claude Code. The ideal-parts precision conflict from S-06 is still open. Two ADRs still with counsel. Eight commits, five pushed.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/api/openapi.json`.

---

## S-G1-01a · 2026-09-14 · kernel — time, deadlines & a Bulgarian-first default

**Did** — first of the three sub-slices S-G1-01 was split into. Kernel primitives only. `@zues/kernel` gains a pure civil-date layer — `addCalendarDays`, `weekday`/`isWeekend`, `rollToWorkingDay`, the one `deadline`, and `toSofiaDate` — plus a language primitive (`STATUTORY_LANGUAGE`, `assertStatutoryLanguage`). The statutory non-working-day calendar is dated config in `@zues/law` (`non_working_days`); `statutoryDeadline`/`isStatutoryNonWorkingDay` wire the kernel utility to it and are the single deadline entry point every module uses.

**Deadlines are civil days, never instants.** `deadline` reasons on Europe/Sofia `YYYY-MM-DD` and never touches wall-clock time, so seven days across the 2026-03-29 spring-forward is still seven days — the failure PM-SYS-004 exists to prevent. Storage stays UTC; `toSofiaDate` is the one crossing back to a civil day.

**The predicate is a parameter, so kernel stays law-free.** `deadline(from, days, isNonWorking)` takes the non-working-day test as an argument; the statutory holiday set is injected by `@zues/law`. Kernel gains no dependency on law, and the "single utility used everywhere" (PM-SYS-005) lives once, composed with the calendar in `statutoryDeadline`.

**Rules covered** — PM-SYS-003, PM-SYS-004, PM-SYS-005 (traceability 14→17 / 233).

**Tests added** — `packages/kernel/test/sys-time.test.ts` (PM-SYS-003 ×1, PM-SYS-004 ×3, PM-SYS-005 ×2), `packages/law/test/sys-deadlines.test.ts` (PM-SYS-005 ×4). Each proves its guard fires, not just the happy path: an English statutory document is rejected; an impossible date (`2026-02-30`) and a non-ISO date are rejected; a fractional day throws; a predicate that never yields a working day throws rather than looping; a weekend and a weekday holiday both roll; the non-working-day set reports `verified:false` with its `PM-SYS-005` TODO.

**Decisions** — none new. Applies ADR-001 (dated data, pure functions, no clock) and PM-SYS-001/002 (constants temporal, resolved at the legal date, not `Date.now()`).

**Open** — PM-SYS-005 is ⚠. `non_working_days` carries `TODO(legal): PM-SYS-005`: the movable Orthodox Easter days, the КТ чл. 154 ал. 2 weekend-substitution rule, and the ГПК чл. 60 roll as it applies to ЗУЕС deadlines are unconfirmed and wait on counsel — the mechanism is built, the calendar is config. Next slice: **S-G1-01b** (evidence & delivery — PM-SYS-006/007/013/014). Gates 2 (event contracts) and 6 (openapi) stay red locally until `pip install -r tools/requirements.txt` (jsonschema, openapi_spec_validator) — pre-existing, nothing to do with this slice. A vault reconciliation this session found the repo self-sufficient; the one item worth porting later is the PM-SYS-009 sanctions design (`SanctionRule` shape + чл. 55–57 bands), for S-G1-01c.

**Read first next time** — `docs/INDEX.md`, this entry, `packages/kernel/src/time.ts`, `docs/RULES.md` SYS.

---

## S-08 · 2026-09-14 · Backend language decided — Kotlin on the JVM (ADR-010)

**Did** — recorded **ADR-010**: the backend moves to **Kotlin · Spring Boot · Spring Modulith**; the frontend stays **Next.js/TypeScript**. Amended **ADR-003** to port its boundary enforcement from `dependency-cruiser` to Spring Modulith `ApplicationModules.verify()` + ArchUnit, and codified the extraction-ready module shape as **`docs/MODULE-TEMPLATE.md`**. Reconciled the docs that named the old stack — `INDEX.md` (ADR table, counts), `DEVBRIEF.md` (the Stack line), `CLAUDE.md` (ADR table + a note that the TypeScript examples predate ADR-010).

**Why** — the team is three developers with a Java background; the agent writes the bulk, but the humans review, own and hire around legally-critical code, so the language is optimised for them, not the (language-agnostic) writer. The three arguments that had favoured TypeScript are neutral here: the browser forces TS on the frontend regardless, the agent is fast in Kotlin, and the team already runs the JVM. Decided **now** because only ~600 lines exist and A12 is unscaffolded — the port is ~a day today, a rewrite after A12.

**Integrity checked** — `./tools/gates.sh` green before and after (docs-only changes; no generated document touched, no `*.ts` changed). All nine non-negotiables and all seven previously-Accepted ADRs are language-neutral and survive — verified item by item in ADR-010 §4. Topology is unchanged (ADR-003 already chose the CI-enforced modular monolith, not the by-convention one).

**Open / migration (tracked in ADR-010 §5)** — scaffold Gradle/Spring Boot in Kotlin (A12); port `@zues/kernel|law|charges` + `zues-calc` (~a day); **re-do S-G1-01a in Kotlin** (the TS slice stays as the record on its branch); retarget the gate globs `*.ts`→`*.kt`; port the TS examples in `CLAUDE.md`/slice protocol. One product question drives the kernel design: **must an in-person assembly compute its tally offline?** And confirm **Kotlin vs Java** (recommend Kotlin).

**Read first next time** — `docs/INDEX.md`, this entry, `docs/adr/ADR-010-backend-language.md`, `docs/MODULE-TEMPLATE.md`, the ADR-003 amendment.

---

## S-09 · 2026-09-14 · Kotlin toolchain up — `:kernel` ported, first green build

**Did** — began the ADR-010 migration. Gradle multi-project scaffold (`settings.gradle.kts`, `gradle/libs.versions.toml`, JDK 21 toolchain, committed wrapper), and the first module `:kernel` in Kotlin — `Money` and `IdealParts` as `@JvmInline value class`, `allocateByWeight` (integer largest-remainder), `assertPartsSumTo100`. **4 JUnit 5 tests named by rule ID** (PM-FEE-016, PM-ORG-002 ×2, PM-FEE-004), green.

**Kotlin sharpens two guards into the type system.** `eur(42.5)` is now a *compile* error, not a runtime throw — money is `Long` by construction (PM-FEE-016). `allocateByWeight` uses integer remainders `(total*w) % sum`, so the split is float-free where the TypeScript version still computed fractions as doubles.

**Toolchain notes for the next session (this Intel Mac):**
- Homebrew has **dropped Intel x86_64 support** — `brew install gradle` fails (Tier 3, no bottles). Gradle is bootstrapped from the distribution zip and pinned via the **committed wrapper** (8.14.3); nothing Gradle is installed system-wide.
- System `java` is **JDK 25**, too new for Gradle 8.14 to run on. `openjdk@21` is installed (keg-only). **Run every Gradle command with `JAVA_HOME=/usr/local/opt/openjdk@21`.** The build's toolchain is pinned to JDK 21.
- The loop is `JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew :kernel:test`.

**Open / next** — port `:law` (dated constants + resolver + the non-working-day calendar) and re-do **S-G1-01a** (PM-SYS-003/004/005) in Kotlin; then `:charges`; then retarget the Python gate globs `*.ts`→`*.kt` and swap `vitest`→`gradle test`. The TypeScript `packages/*` still sit alongside for reference and are removed at the end of the port.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/MODULE-TEMPLATE.md`, `kernel/build.gradle.kts`.

---

## S-10 · 2026-09-14 · `:law` ported + S-G1-01a redone in Kotlin

**Did** — `:kernel` gained the civil-date/deadline utility (`addCalendarDays`, `weekday`/`isWeekend`, `rollToWorkingDay`, `deadline`, `toSofiaDate`) built on `java.time.LocalDate`, plus the Bulgarian-first `Language` primitive. `:law` ported — dated `Constant`s with `constantOn`/`numberOn`/`unverified` (PM-SYS-001/002), the allocation keys, and the non-working-day calendar with `statutoryDeadline` (S-G1-01a's PM-SYS-005, holidays as config + `TODO(legal)`). **18 JUnit 5 tests, all green** (kernel 10, law 8), each named by its rule ID.

**java.time did the S-G1-01a work better than the hand-rolled TypeScript.** `LocalDate.parse` rejects `2026-02-30` and `01-01-2026` for free; `plusDays` is civil-date arithmetic with no DST exposure; `atZone("Europe/Sofia")` gives the UTC→Sofia crossing. And `days: Int` makes a fractional day a compile error rather than a runtime guard.

**Open / next** — port `:charges` (`computeChargeRun`); then **retarget the gate pack** (`tools/*.py` globs `*.ts`→`*.kt`, `vitest`→`gradle test`) so traceability counts the Kotlin tests; then scaffold the Spring Boot `:app` (the Spring Modulith modular monolith). The TypeScript `packages/*` still sit alongside for reference until the port completes.

**Read first next time** — `docs/INDEX.md`, this entry, `kernel/src/main/kotlin/zues/kernel/Time.kt`, `law/src/main/kotlin/zues/law/Deadlines.kt`.

---

## S-11 · 2026-09-15 · `:charges` ported + the gate pack retargeted to Kotlin

**Did** — ported `:charges` (`computeChargeRun`, the pure charge engine — allocation, exemptions, the business multiplier, the `basis`/`law_version`/`engine_version` receipt) with **12 JUnit 5 tests** named by rule ID. Then **retargeted the gate pack** from TypeScript to Kotlin: the four Python scanners now read `*.kt` (test detection is `` fun `PM-XXX …` ``, the identifier scan uses Kotlin keywords, the legal-literal scan excludes `law/` and `*/test/*`), and `gates.sh` runs `./gradlew test` instead of `vitest` (with a JDK 21 fallback for `JAVA_HOME`).

**`./tools/gates.sh` is green on the Kotlin tree** — 30 tests; traceability **17/233 covered, 0 orphan IDs**, 14 source files; banned-words and legal-thresholds clean; `TRACEABILITY.md` regenerated to point at the `.kt` files. Same 17 rules as the TypeScript tree — the port kept coverage identical, module for module.

**One naming call to review.** The domain term `Unit` (самостоятелен обект) collides with `kotlin.Unit`. Kept the domain name (per the DEVBRIEF's no-synonyms rule) in `zues.charges` with a comment; it is safe because `kotlin.Unit` is never referenced by name there. Flag if you'd rather rename (e.g. `PropertyUnit`).

**Open / next** — remove the now-redundant TypeScript `packages/*` and `apps/zues-calc` (kernel/law/charges are fully in Kotlin); then scaffold the Spring Boot `:app` (the Spring Modulith modular monolith) with the first HTTP/DB module. The `zues-calc` CLI is not yet re-ported.

**Read first next time** — `docs/INDEX.md`, this entry, `charges/src/main/kotlin/zues/charges/Charges.kt`, `tools/gates.sh`.

---

## S-12 · 2026-09-15 · retired the redundant TypeScript

**Did** — deleted the TypeScript that Kotlin already replaced: `packages/{kernel,law,charges}`, `apps/zues-calc`, and the root `package.json` / `package-lock.json` / `tsconfig.json` / `vitest.config.ts` (22 tracked files + the untracked `node_modules/`). The domain now has exactly one home — the `:kernel`, `:law`, `:charges` Gradle modules — instead of two that could drift.

**Moved three referencing things with it, so nothing dangles:**
- **`.github/workflows/ci.yml`** — swapped `setup-node` + `npm ci` (dead once `package.json` is gone) for `setup-java` (temurin 21) + `gradle/actions/setup-gradle`. CI's JDK is now the one Gradle actually runs on; `gates.sh`'s macOS `JAVA_HOME` fallback stays inert on Ubuntu.
- **`tools/law-watch/impact.py`** — its file scan still read `*.ts/.tsx/.js`; retargeted to `*.kt` (missed in the S-11 retarget because it is not one of the eight gate checks).
- **`CLAUDE.md`** — corrected the statements the removal made false: the "TypeScript examples are being ported" note (the domain is already Kotlin), the JUnit backtick test-name example, the `index.ts` module-boundary rule (now the Spring Modulith package boundary), and stale `packages/…` / `src/modules/…` paths.

**`./tools/gates.sh` green after the removal** — 30 Kotlin tests pass, traceability unchanged at **17/233 (0 orphan IDs)**, banned-words and legal-thresholds clean on 14 `.kt` files, generated docs match. The scanners glob `*.kt`, so deleting the `*.ts` tree could not touch coverage — and didn't.

**Not re-ported** — the `zues-calc` CLI and its golden fixture (`sample/units.csv` + `tariff.json` → `expected.csv`, the Gate-1 "reproduces the spreadsheet to the cent" harness) live in git history at `c638c32`; re-port as a thin Kotlin CLI over `:charges` when Gate 1 needs the end-to-end fixture.

**Open / next** — scaffold the Spring Boot `:app` (Spring Modulith modular monolith) per `docs/MODULE-TEMPLATE.md`: HTTP + Postgres + the fourteen domain-module packages + the outbox. Two calls still yours: rename `Unit` → `PropertyUnit` (S-11)?; merge the `kotlin/scaffold` + `arch/adr-010-kotlin` branches to `main`?

**Read first next time** — `docs/INDEX.md`, this entry, `docs/MODULE-TEMPLATE.md`, `tools/gates.sh`.

---

## S-13 · 2026-09-15 · `Unit` → `PropertyUnit`

**Did** — resolved the S-11 naming call: renamed the `zues.charges` domain type `Unit` (самостоятелен обект) to **`PropertyUnit`**, removing the `kotlin.Unit` collision before `:app` is built on top of `:charges`. Ten code references across `Charges.kt` + `ChargesTest.kt`; the doc comment keeps a one-line note of the old name so the rename is self-explaining. `TRACEABILITY.md` regenerated (rule-tag line numbers shifted +1 as the comment grew) — no coverage change.

**Why** — a domain type that shadows a stdlib type is a footgun that only gets more expensive as callers accumulate. `PropertyUnit` is still the single canonical name for the concept (no synonym proliferation), just disambiguated. Cheap now (one module, no dependents), a refactor later.

**`./tools/gates.sh` green** — 30 tests, traceability 17/233, banned-words clean (`PropertyUnit` trips nothing), generated docs committed.

**Open / next** — scaffold the Spring Boot `:app` walking skeleton (Spring Modulith + Flyway + one module wired HTTP→domain→Postgres→outbox, per `docs/MODULE-TEMPLATE.md`). Consolidating `arch/adr-010-kotlin` + `kotlin/scaffold` to `main` via PRs.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/MODULE-TEMPLATE.md`, `charges/src/main/kotlin/zues/charges/Charges.kt`.

---

## S-14 · 2026-09-15 · `:app` walking skeleton — `registry` end to end

**Did** — stood up the Spring Boot deployable `:app` (Spring Boot 3.4.1 · Spring Modulith 1.3.1) and wired the first Spring module, **`registry`**, through every seam: `POST /api/registry/entrances` → `RegistryService` → **Spring Data JDBC** → PostgreSQL (schema applied by **Flyway V1**) → an **outbox** event (`EntranceRegistered`, persisted in Modulith's event publication registry, consumed by `@ApplicationModuleListener`). `GET` lists them back. The point of the slice is the wiring, not domain rules — it proves the template on real infrastructure before it is replicated.

**Decisions realized** (from this session's questions):
- **Persistence = Spring Data JDBC.** Ids are app-assigned UUIDs, so writes go through `JdbcAggregateTemplate.insert` (states insert directly) and reads through a `ListCrudRepository`.
- **DB tests = Testcontainers, `@Testcontainers(disabledWithoutDocker = true)`.** `RegistryPersistenceIT` runs real Postgres 16 + real Flyway in CI (Docker present) and **skips on a machine without Docker** — so `./tools/gates.sh` stays green locally. `@DynamicPropertySource` builds the JDBC URL (Testcontainers has no fixed port) and adds the `currentSchema` search_path.
- **Schema has one home again.** `git mv db/migrations/0001_init.sql → app/src/main/resources/db/migration/V1__init.sql`; the app owns and applies it. `db/test/constraints.sh` still runs against the migrated DB unchanged.

**Schema mapping** — one database, schema-per-module (ADR-003). The connection `search_path` spans all module schemas, so an unqualified `@Table("entrance")` resolves to `registry.entrance` while names stay unique across schemas. Flyway keeps its history in `public`, where V1's unqualified DOMAINs (`money_minor`, …) live.

**What is proved where** — `ModularityTests` (`ApplicationModules.verify()`, ADR-003 boundaries) and `RegistryWebTest` (`@WebMvcTest`, HTTP contract with the service mocked) need no database and run in the gate pack **everywhere**. The full HTTP→Postgres→outbox path is exercised only by the Docker-gated IT: **written to standard patterns but not executed in this environment — it is CI-verified.** Treat the persistence path as green in CI, unproven locally, until someone runs it with Docker.

**Also** — added a root `build.gradle.kts` (`plugins { … apply false }`) so the Kotlin plugin classpath loads once instead of per-subproject (Gradle warned the duplicate "may break the build"). `PM-ORG-001` is now *referenced* in `registry` (not yet *covered* by a test) — traceability regenerated.

**`./tools/gates.sh` green** — tests across `:kernel :law :charges :app`; banned-words clean on **24** source files; legal-thresholds clean; generated docs committed.

**Open / next** — (1) **RLS** is not wired yet: V1 defines `app.current_entrance()` but no `ENABLE ROW LEVEL SECURITY` / policies (ADR-002 backstop) — a registry follow-up that sets `app.entrance_id` per transaction. (2) Map `EntranceRegistered` to the formal event catalogue in `docs/events`. (3) Next module: **`money`** (depends on `:charges`) toward Gate 1 — "reproduces the firm's spreadsheet to the cent". (4) A CI run is the first real execution of `RegistryPersistenceIT`.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/MODULE-TEMPLATE.md`, `app/src/main/kotlin/zues/app/registry/`, `app/src/main/resources/application.yml`.

---

## S-15 · 2026-09-15 · money charge-run calculator (stateless)

**Did** — added the second Spring module, **`money`**, wired to the pure engine `:charges`: `POST /api/money/charge-runs/preview` takes a self-contained snapshot (tariff + units), runs `computeChargeRun`, and returns the computed charges as JSON. Minor units throughout (ADR-006); no floats cross the wire. Two Gate-1 rules proved by tests named after them — **PM-FEE-001** (every line typed to one of the three cost streams) and **PM-FEE-014** (re-running the same period reproduces identical figures). An unlawful run (no GA decision, ideal parts ≠ a full share, multiplier out of range) is a **400**, not a 500.

**Why a *calculator*, not persistence** — reading `V1__init.sql`: `money.charge_run.entrance_id` is a FK to `registry.entrance` and `money.charge_line.unit_id` is a NOT-NULL FK to `registry.unit`. A stored run therefore requires registry-owned entrances and units, and ADR-003 forbids `money` writing `registry`'s tables. So a genuinely *self-contained* run (units in the payload) cannot be persisted without registry units existing first. The honest self-contained slice is a stateless calculator; persistence + double-entry wait on a registry-units slice.

**Fully verifiable locally** — unlike the registry DB seam, this slice needs no database: the calculator is a pure Kotlin object (`ChargeCalculator`) and the controller calls it directly, so `ChargeCalculatorTest` (pure) and `ChargeRunWebTest` (`@WebMvcTest`, no mock, no DataSource) both run in the gate pack here. No Docker involved.

**One gate caught a real thing** — the legal-threshold scanner flagged `* 100%` on a wrapped KDoc line as "× 100". It's a false positive (prose about ideal parts), but the fix is the right one anyway: reword the comment to name the rule (PM-ORG-002) instead of the bare number. The scanner stays strict; the comment stays legal-literal-free.

**`./tools/gates.sh` green** — traceability **18/233 covered** (FEE-001, FEE-014 added), banned-words clean on 29 files, legal-thresholds clean, TESTPLAN + TRACEABILITY regenerated.

**Open / next** — (1) persistence for money needs a **registry units** slice first (create units with ideal parts summing to 100%, PM-ORG-002/BOOK-002), then `money` can store an immutable `charge_run` + `charge_line` (PM-FEE-015) referencing real units. (2) Double-entry postings + the fund (ADR-006, PM-FEE-020). (3) The registry `RegistryPersistenceIT` still awaits its first CI run (Docker).

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/`, `charges/src/main/kotlin/zues/charges/Charges.kt`.

---

## S-16 · 2026-09-15 · registry units

**Did** — the `registry` module can now register a unit set under an entrance: `POST /api/registry/entrances/{id}/units`. The whole set is validated to sum to exactly 100% (**PM-ORG-002**) and inserted in one transaction, so a mid-set state that does not yet sum to 100% never has to be valid — the DB's deferred trigger checks the same invariant again at commit. Units carry the `separate_entrance` business flag (**PM-ORG-009**). `GET .../units` reads them back. Unblocks money persistence: charge lines can now reference real `registry.unit` rows.

**A precision decision, made to match the schema** — the kernel's `IdealParts` holds six decimals; `V1`'s `ideal_parts` column is `numeric(7,4)` — four. If the app kept six and the DB rounded to four, their two sum-to-100 checks could disagree. So the registry **rejects ideal parts finer than four decimals** (`ppmPct % 100 == 0`) and maps to the column at scale 4. The schema's precision is the contract; the app conforms to it rather than inventing one.

**What is proved where** — `UnitValidationTest` proves PM-ORG-002 (99.98% rejected with the delta shown; a 100% set accepted; >4-decimal rejected) as a **pure** test, and `RegistryUnitsWebTest` proves the HTTP failure modes (invalid → 400, unknown entrance → 404) — both run in the gate pack **locally**. `RegistryUnitsPersistenceIT` (Testcontainers, `disabledWithoutDocker`) round-trips a set through real Postgres + the FK + the `numeric(7,4)` column — **CI-only** here.

**`./tools/gates.sh` green** — traceability 18/233 (PM-ORG-002 now also proved at the app layer; PM-ORG-009 referenced), banned-words clean on 33 files, legal-thresholds clean, TRACEABILITY regenerated.

**Workflow** — built on `slice/S-16-registry-units`, integrated to `main` by fast-forward, branch deleted (Claude now owns the repo workflow: branch-per-slice → gates green → integrate to `main` → delete branch).

**Open / next** — (1) **money persistence**: an immutable `charge_run` + `charge_line` referencing real units (PM-FEE-015), now that units exist. (2) owner/party + full book-completeness (PM-BOOK-002). (3) the two Docker-gated ITs (`RegistryPersistenceIT`, `RegistryUnitsPersistenceIT`) await their first CI run.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/PropertyUnit.kt`, `app/src/main/kotlin/zues/app/registry/RegistryService.kt`.

---

## S-17 · 2026-09-15 · money reads units from registry (cross-module port)

**Did** — `money` can now compute a charge run from an entrance's **stored** units, not units in the payload: `POST /api/money/entrances/{id}/charge-runs/preview` posts the tariff and reads the units through `registry`'s published **`Units`** port (`UnitForCharging`), never its tables (ADR-003). This is the read path money persistence needs. Spring Modulith `verify()` is green with the new `money → registry` API dependency — the boundary holds, no cycle. Shared the `ChargeRun → response` mapping (`ChargeMapping.kt`) between the payload calculator (S-15) and this service.

**The occupancy gap, made explicit** — registry units carry ideal parts but not occupancy (household members/animals/absence are unmodelled). So a `PER_PERSON` line is **refused with a clear 400** rather than silently billed as zero persons; `BY_IDEAL_PARTS` and `PER_UNIT` run. Full per-person Gate-1 runs wait on an occupancy slice.

**What is proved where** — `ChargeRunServiceTest` mocks the `registry` port and proves the read path + the occupancy guard + the empty-entrance rejection; `StoredChargeRunWebTest` proves the HTTP contract (200 / 404). Both **local**, no database. (This is an integration slice — no new rule coverage; the FEE-001/FEE-014 line references shifted by one when an unused import was removed.)

**`./tools/gates.sh` green** — 39 source files, banned-words + legal-thresholds clean, `ModularityTests.verify()` passing, TRACEABILITY regenerated.

**Open / next** — (1) **money persistence**: with the read path in place, persist an immutable `charge_run` (basis `jsonb` + hash) + `charge_line` referencing the stored `registry.unit` rows (PM-FEE-015) — the first `money`-owned DB writes, Testcontainers-tested in CI. (2) occupancy modelling to unlock `PER_PERSON`.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/Units.kt`, `app/src/main/kotlin/zues/app/money/ChargeRunService.kt`.

---

## S-18 · 2026-09-16 · money persistence — the immutable charge run

**Did** — `money` now **issues** a charge run: `POST /api/money/entrances/{id}/charge-runs` computes from registry units and stores, in one transaction, an immutable `charge_run` (the **basis as `jsonb`** + its SHA-256 hash + `law_version`/`engine_version`/`status`) and one `charge_line` per unit and cost stream referencing the real `registry.unit` rows. The first `money`-owned database writes. A period is billed once — a second attempt is a **409**, never a silent second bill.

**Rules** — **PM-FEE-014** (a past bill is exactly reproducible): the basis serialises to canonical, sorted-key JSON, so the same run yields byte-identical JSON and the same hash — proved **purely** in `BasisJsonTest`, locally. **PM-FEE-015** (issued charges are immutable): the `charge_line` table carries an `ON UPDATE DO INSTEAD NOTHING` rule; `ChargeRunPersistenceIT` issues a run, fires a raw `UPDATE` at a line, and asserts the amount is unchanged.

**The jsonb converter — the one piece I could not run locally** — Spring Data JDBC does not map `jsonb`, so `JsonbValue` + a writing/reading `Converter` pair are registered through `AbstractJdbcConfiguration.userConverters()` (Spring Boot backs off its own when a subclass is present). `postgresql` moved from `runtimeOnly` to `implementation` so `PGobject` is on the compile classpath. It **compiles here but is exercised only in CI** — the Testcontainers IT is the first real test of the round-trip.

**What is proved where** — `BasisJsonTest` (FEE-014 determinism) and `ChargeRunStoreWebTest` (201 / 409 / 404) run **locally**. `ChargeRunPersistenceIT` (Testcontainers, `disabledWithoutDocker`) proves the jsonb round-trip, one typed line per unit summing to the pot, and FEE-015 immutability — **CI-only** on this Docker-less machine.

**`./tools/gates.sh` green** — traceability **19/233** (PM-FEE-015 added), banned-words clean on 47 files, legal-thresholds clean, `ModularityTests.verify()` passing (still `money → registry` only), TESTPLAN + TRACEABILITY regenerated.

**Open / next** — (1) **occupancy** modelling (household members / animals / absence) to unlock `PER_PERSON` and full Gate-1 realism. (2) A `GET` for a stored run. (3) double-entry **postings** + the fund (PM-FEE-020, ADR-006). (4) confirm the S-18 CI run is green — it's the first execution of the jsonb/immutability layer.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/ChargeRunStore.kt`, `app/src/main/kotlin/zues/app/money/JsonbConfig.kt`, `app/src/main/kotlin/zues/app/money/BasisJson.kt`.

---

## S-19 · 2026-09-16 · registry household — occupancy behind the port

**Did** — `registry` now records who lives in a unit: `POST /api/registry/entrances/{id}/units/{unitId}/household` stores effective-dated `household_member` rows (a child-under-six flag, `party_id` left null since parties are unmodelled — the count needs no name). The **`Units` port** gained `occupants` and `childrenUnder6`; `UnitsAdapter` derives them from the current members (those with no `validTo`). This is the headcount a per-person charge stands on — exposed, but **not yet consumed**: `money` still refuses `PER_PERSON`. S-20 flips that.

**Rules** — **PM-FEE-008** (persons residing are counted as occupants) is now proved by `UnitsAdapterTest`: registering members raises the count, a moved-out member drops out of it. **PM-FEE-005** (children under six treated separately) — the adapter reports them in their own field, ready for the engine to exclude.

**Deliberately deferred, and why** — the **30-day** residence threshold (PM-FEE-008's number, which belongs in `:law` as `OCCUPANT_THRESHOLD_DAYS`, not this code) and the mid-period **6th-birthday** transition (PM-FEE-005's acceptance) are refinements on top of the count; **animals** (PM-FEE-009 / PM-BOOK-005) and **absence** (PM-FEE-006/007) are their own slices; and occupancy is **current, not period-aware** (the dateless port returns today's members). Each is noted so none is silently assumed.

**What is proved where** — `UnitsAdapterTest` (counts) and `HouseholdWebTest` (201 / 404) run **locally**; `HouseholdPersistenceIT` (Testcontainers) persists members and reads the counts back **through the same port money uses** — CI-only.

**`./tools/gates.sh` green** — traceability **20/233** (PM-FEE-008 added), banned-words clean on 51 files, legal-thresholds clean, `verify()` passing, TESTPLAN + TRACEABILITY regenerated.

**Open / next** — **S-20 money `PER_PERSON`**: consume `occupants`/`childrenUnder6` from the port, drop the rejection, and guard a per-person line whose entrance has zero registered occupants. Then animals, then absence.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/HouseholdMember.kt`, `app/src/main/kotlin/zues/app/registry/Units.kt`.
