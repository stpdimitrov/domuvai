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

---

## S-20 · 2026-09-16 · money bills PER_PERSON

**Did** — `money` now bills a `PER_PERSON` line on the **registered household headcount** the port exposes (S-19), completing the occupancy goal. Dropped the S-17 refusal; the engine already excludes children under six (PM-FEE-005). A per-person run whose entrance has **no chargeable occupant** is refused with a 400 — the engine would otherwise divide a pot by a zero weight-sum. The guard computes the real chargeable count with `:charges`' own `chargeablePersons`, so it agrees with the engine to the person.

**End to end** — register a household of three in `registry`, post a `PER_PERSON` management line at 500 minor/person, and the run totals 1500 — read back through the port money shares with `registry`.

**Rules** — **PM-FEE-008** (persons residing are billed as occupants) is proved at the money layer by `ChargeRunServiceTest`: three occupants in one unit, one in another, a per-person rate splits 1500 / 500. **PM-FEE-005** (children excluded) rides on the engine's own coverage.

**What is proved where** — `ChargeRunServiceTest` (per-person allocation + the zero-occupancy guard) runs **locally**; `PerPersonChargeIT` (Testcontainers) drives registry household → money per-person charge against real Postgres — **CI-only**.

**`./tools/gates.sh` green** — traceability 20/233, banned-words clean on 52 files, legal-thresholds clean, `verify()` passing, TRACEABILITY regenerated.

**Open / next** — **animals** (PM-FEE-009 / PM-BOOK-005, one occupant-equivalent each), **absence** exemptions (PM-FEE-006/007), the **30-day** residence threshold and **6th-birthday** transition (both config-driven, from `:law`), and **period-aware** occupancy (a dated port). Separately: double-entry **postings** + the fund (PM-FEE-020), and a `GET` for a stored run.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/ChargeRunService.kt`.

---

## S-21 · 2026-09-16 · fix — household column name (CI red → green)

**Found in CI, not locally** — the S-19/S-20 runs went red: `BadSqlGrammarException` in `HouseholdPersistenceIT` (insert) and `ChargeRunPersistenceIT` (S-19 made `UnitsAdapter` read `household_member` on every charge, so issuing a run hit it too — which is why S-18's IT was green on `6ad8918` but failed after S-19). Root cause: Spring Data JDBC's default naming maps `isChildUnder6` to column `is_child_under6`, but the schema column is **`is_child_under_6`** — no underscore before a digit (the same rule that makes `areaM2 → area_m2` *match* and pass). Column not found.

**Fix** — one line: `@Column("is_child_under_6")` on `HouseholdMember.isChildUnder6`. Audited every persisted entity property against the schema; this was the only digit-column mismatch (`area_m2` already matched, which is why S-16's unit IT passed).

**The lesson, logged** — column-name mapping cannot be verified on this Docker-less machine; the gate pack is green locally while the mapping is wrong. CI (Testcontainers) is the backstop and caught it, exactly as the "CI validates the DB layer" note intended. A cheap future gate would compare entity property names to the schema's columns without a database — worth adding before the persistence surface grows.

**`./tools/gates.sh` green locally** (compile + non-DB tests); the real proof is the next CI run.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/HouseholdMember.kt`.

---

## S-22 · 2026-09-16 · gate — entity ⇄ schema columns (closes the S-21 gap)

**Did** — added `tools/check_schema_columns.py` as gate **9/9**. It maps every `@Table` entity's properties to the column name Spring Data JDBC would use — an underscore before each capital, none before a digit (the convention read straight off the working columns `area_m2`, `ideal_parts_pct`, `charge_run_id`) — honours `@Column("…")` overrides, and diffs the result against the columns parsed from `V1__init.sql`. A property mapped to a column the table lacks fails the build **locally**, so the `is_child_under_6` class of bug can no longer reach CI. `gates.sh` renumbered to `/9`.

**Proved against the real failure** — temporarily dropping the `@Column` reintroduces the S-21 bug and the check flags it precisely: `` `isChildUnder6` -> column `is_child_under6`, absent from household_member (has: … is_child_under_6 …) `` — it even names the column that exists. Restored → green. (A check never run against a failure is not a check.)

**Why this was the right fix** — S-21 was caught by CI at the cost of a red `main`; the gap was that column mapping is invisible to `./gradlew test` without Docker. This check needs no database, so it moves that failure from CI back to the developer's terminal. Escape hatches match the house style: name the column with `@Column`, or mark a non-persisted property `// not-a-column`.

**Assumption logged** — table names are treated as unique across schemas (the same `search_path` assumption the app relies on), and the naming rule is the observed default; a future entity with consecutive capitals (an `ID`/`URL` acronym) or a second table of the same name would need the rule revisited. Both are noted in the check's header.

**`./tools/gates.sh` green** — 9/9, including the new check; generated documents match.

**Read first next time** — `docs/INDEX.md`, this entry, `tools/check_schema_columns.py`, `tools/gates.sh`.

---

## S-23 · 2026-09-16 · registry animals — the occupant-equivalent

**Did** — `registry` now records animals: `POST /api/registry/entrances/{id}/units/{unitId}/animals` stores effective-dated `animal` rows (species + veterinary passport, a separate section of the book — PM-BOOK-005). The `Units` port gained `animals`; `UnitsAdapter` counts the current ones. `money` passes the count to the engine, which already treats each as one occupant-equivalent (PM-FEE-009). The per-person headcount is now complete: residents (children excluded) plus animals.

**Rules** — **PM-BOOK-005** (animals recorded, headcount recalculated) proved by `UnitsAdapterTest`; **PM-FEE-009** (occupant-equivalent) proved at the money layer by `ChargeRunServiceTest` — a unit with one resident and one animal charges for more than one person — on top of the engine's own coverage.

**The S-22 gate earned its keep** — `Animal` is the first entity added since the schema-column check landed, and it validated the mapping **locally** (`vetPassportNo → vet_passport_no`, and the rest) before a single container started. No CI round-trip to find a column typo; the check now reads "7 entities."

**What is proved where** — `UnitsAdapterTest`, `AnimalWebTest` (201 / 404) and the money `ChargeRunServiceTest` run **locally**; `AnimalPersistenceIT` (Testcontainers) persists an animal and reads the count back through the port — CI-only.

**`./tools/gates.sh` green** — 9/9; traceability **21/233** (PM-BOOK-005 added), banned-words clean on 55 files, legal-thresholds clean, TESTPLAN + TRACEABILITY regenerated.

**Open / next** — **absence** exemptions (PM-FEE-006/007, ⚠ the number is unverified — config + `TODO(legal)`), the **30-day** residence and **6th-birthday** thresholds (config-driven), **period-aware** occupancy; then double-entry **postings** + the fund (PM-FEE-020), and a `GET` for stored runs.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/Animal.kt`, `app/src/main/kotlin/zues/app/registry/Units.kt`.

---

## S-24 · 2026-09-16 · money postings — the double-entry ledger

**Did** — issuing a charge run now also writes its **double-entry** postings, in the same transaction as the run and its lines. Each unit's charge is a **debit** to its receivable; the income is a **credit** to the condominium's ledger, split by cost stream (`INCOME:MANAGEMENT`, …) and carrying no unit — the condominium's, never a manager's (Rule: PM-FEE-020). One journal per run (its id is the run's), debits equal credits, so it **balances to zero** (ADR-006). Postings are immutable, like charge lines.

**Pure where it counts** — the derivation is a pure function, `Ledger.forRun`, so both properties are proved without a database: `PostingsTest` shows the journal sums to zero and the income lands on the entrance ledger split by stream. The database enforces the balance again — a deferred `assert_journal_balances` trigger rejects a journal that does not sum to zero at commit — which `ChargeRunPostingIT` exercises by issuing a run and reading a zero-sum journal back.

**Rules** — **PM-FEE-020** (income to the condominium, by stream) proved purely by `PostingsTest`; **ADR-006** double-entry balance proved purely and, in CI, by the DB trigger.

**What is proved where** — `PostingsTest` (balance + income routing) runs **locally**; `ChargeRunPostingIT` (Testcontainers) issues a run and asserts the persisted journal balances — CI-only. The S-22 gate validated `PostingRow`'s columns locally first (8 entities now).

**`./tools/gates.sh` green** — 9/9; traceability **22/233** (PM-FEE-020 added), banned-words clean on 58 files, legal-thresholds clean, TESTPLAN + TRACEABILITY regenerated.

**Open / next** — **absence** exemptions (PM-FEE-006/007) with **period-aware** occupancy (the dated port), the **30-day / 6-yr** config thresholds, a `GET` for a stored run, and the **fund** accounts (PM-FUND-*). A charge is now computed, stored immutably, and posted to a balanced ledger.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/Postings.kt`, `app/src/main/kotlin/zues/app/money/ChargeRunStore.kt`.

---

## S-25 · 2026-09-16 · period-aware occupancy (a correctness fix)

**Fixed a latent correctness bug** found by design review, before it reached the pilot: occupancy was read from a **dateless** port — today's residents and animals — but a charge is for a **period**. Issuing May's bill in June counted June's household. That silently broke **PM-FEE-014** (a past bill must be exactly reproducible — re-run it later, the headcount has moved) and plainly overcharged anyone who arrived after the period. Masked only because the pilot would bill the current month.

**Did** — the `Units` port now takes the period's date: `forEntrance(entranceId, on)`. The adapter counts a member or animal only if the half-open interval `[validFrom, validTo)` contains `on`. `money` parses `request.legalDate` and passes it (a malformed date is now a clean **400**, not a latent 500). The stored basis is now honestly period-scoped.

**The signature change rippled**, as a correctness fix does: `UnitsAdapterTest` now proves the period scoping (a member who left before, or arrived after, the period is not counted), `ChargeRunServiceTest`'s stubs match the two-arg call, and three ITs register with an explicit `validFrom` and query as of the period.

**No new rule coverage** — this is a correctness refactor; the PM-FEE-008 / PM-FEE-005 / PM-BOOK-005 tests now assert occupancy *as of the period* rather than *as of now*.

**`./tools/gates.sh` green** — 9/9; traceability 22/233, banned-words clean on 58 files, legal-thresholds clean, TRACEABILITY regenerated.

**Open / next** — **absence** (S-26) builds directly on this: a non-use declaration during the period feeds `:charges`' `absentDays`; the exemption threshold stays in `:law` config (⚠ PM-FEE-006/007). Also the **30-day** residence and **6th-birthday** thresholds, then the **fund**.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/Units.kt`, `app/src/main/kotlin/zues/app/money/ChargeRunService.kt`.

---

## H-01 · 2026-09-16 · Handover — resume point before /compact

**RESUME HERE.** `main` is at the S-25 tree; this consolidates a long session (S-15 → S-25 plus the S-21 fix, the S-22 gate, and ADR-011) so the next session can continue without the conversation.

**Where the code is** — the fee engine runs end to end: a charge is **computed** (ideal-parts / per-unit / per-person over registered units + households + animals, children excluded, business multiplier), **stored immutably** with a reproducible period-scoped basis, and **posted** to a balanced double-entry ledger (income to the condominium, by stream). `registry` holds entrances · units · household · animals; `money` holds the charge run + postings. Two of fourteen modules have app code.

**Progress** — ~**15%** of the whole plan (233 rules / 4 gates); rules test-covered **22/233 (9%)**. **Gate 1 ≈ 40–50%** (its architecture is done; remaining below). Gates 2–4 at 0%. Foundation (full schema for all 14 modules, pure engine, 9-gate pack, CI, 11 ADRs, event + OpenAPI contracts) is ~85% done and front-loaded — that's why 15% > 9%.

**How this session operates (owner delegated the workflow — reaffirm or change):**
- One slice = one branch `slice/S-nn-*` off `main` → build → `./tools/gates.sh` green **locally** → SESSIONLOG entry → commit (attributed) → **integrate to `main` by fast-forward + push** → delete the branch. Direct-to-`main` because **no GitHub auth in-session** (`gh` logged out; PRs need `gh auth login`); the gate pack is the quality bar.
- **No Docker locally**, so the Testcontainers ITs (`*PersistenceIT`, `*IT`) **skip locally and run in CI** (`ci.yml`, GitHub Actions, full gate pack on Ubuntu). After a DB-touching slice, the owner confirms the CI run is green; S-22's `check_schema_columns.py` gate pre-catches column-mapping bugs (the S-21 class) **locally**.
- Toolchain: JDK 21 (`gates.sh` sets `JAVA_HOME=/usr/local/opt/openjdk@21`). The session cwd sometimes flips to `…/weatherappnew`; use `git -C …/domuvai` and absolute paths when it does.
- Attribution (keep): commits end `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; PRs end `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

**Next slices (toward Gate-1 contract-complete → frontend green light):**
1. **S-26 absence** (PM-FEE-006/007) — builds on S-25's period-aware port; a non-use declaration feeds `:charges`' `absentDays`. ⚠ the exemption number is **unverified** → keep it in `:law` config with `TODO(legal)`, never an invented number.
2. **Fund** accounts (PM-FUND-004+; mind ADR-007 *Proposed* — the account record is safe, holding/moving money is not).
3. **Owners / parties** in `registry` (a receivable needs a liable party) · **intake / spreadsheet import** (Gate 1's literal "reproduce the firm's spreadsheet").
4. Then the `registry` + `money` + `intake` OpenAPI freezes → **announce "Gate 1 backend contract-complete — build the Gate-1 frontend."**

**Frontend** — not started; **backend only**. Decided to be a **separate Next.js app** (ADR-010/003, consolidated in **ADR-011**). Build **gate by gate**, not big-bang; the assistant announces the green light per ADR-011 §3. **Open owner decision:** repo layout — monorepo `web/` (recommended) vs separate repo.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry, `docs/adr/ADR-011-frontend-topology.md`, then `git log --oneline -12` and `./tools/gates.sh`.

---

## S-26 · 2026-09-16 · absence — a filed non-use declaration exempts the unit (PM-FEE-006/007)

**Did** — closed the registry gap under the already-built engine. `:charges` and `:law` already prorated absence (`chargeablePersons` → 0 when `absentDays > ABSENCE_EXEMPTION_DAYS`), and the *payload* path carried `absentDays` — but a run computed **from the registry** always sent 0, so no registered unit could ever be exempt, and nothing recorded a **filed declaration** (PM-FEE-007). Now `registry` holds `absence_declaration` (a closed span `[absentFrom, absentTo)` + a system-stamped `filedOn`), the `Units` port surfaces `absentDays`, and `ChargeRunService` passes it through into the engine.

**The rule split, deliberately** — the *charge* consequence stays in `:charges` (absent-days vs the window → exempt); the *record* judgment stays in `registry`: the adapter sums a unit's filed declarations, **clipped to the run's calendar year** (PM-FEE-006 counts absence "in a calendar year") and **dropping late filings** (PM-FEE-007 — filed more than the grace window after the absence ends → not applied). `filedOn` is stamped from the clock, never the caller, so timeliness can't be back-dated.

**⚠ two unverified numbers, both config, never invented** — `ABSENCE_EXEMPTION_DAYS` (pre-existing) and the new `ABSENCE_DECLARATION_GRACE_DAYS` live in `:law` marked `verified=false` with a `TODO(legal)`; every test reads them via `numberOn`, never as a literal. The exemption *mode* (full vs reduced share) is still the engine's documented placeholder.

**Tests — each guard proves it fires** — `UnitsAdapterTest`: days surfaced, year-clip, **unfiled → 0**, **late-filed → 0** (boundary filing still counted). `ChargeRunServiceTest`: a unit absent beyond the window drops off a PER_PERSON line while its neighbour bills normally. `LawTest`: the grace window is unconfirmed & config-sourced. `AbsenceWebTest`: 201 / 404 / 400. `AbsencePersistenceIT` (Docker, CI): file → read back through the port, clock pinned so the filing is deterministically timely.

**`./tools/gates.sh` green** — 9/9. Traceability **23/233 (10%)** — **PM-FEE-007 newly covered** (PM-FEE-006 was already green in the engine); TESTPLAN 211 remaining; schema-columns 9 entities / 24 tables (new `absence_declaration`); legal-thresholds clean. OpenAPI unchanged — the absence sub-endpoint stays out of the published contract, as household/animals do.

**Open / next** — **fund** accounts (PM-FUND-004+; ADR-007 *Proposed* — the account record is safe, holding/moving money is not), then **owners / parties** (a receivable needs a liable party) and **intake / spreadsheet import**, toward Gate-1 contract-complete → the frontend green light. Both absence day-counts and the exemption mode remain for counsel.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/Units.kt` (the absence computation), `app/src/main/kotlin/zues/app/registry/AbsenceDeclaration.kt`, `law/src/main/kotlin/zues/law/Constants.kt`.

---

## S-27 · 2026-09-16 · fund account — the чл. 50 account record (PM-FUND-001/004/005)

**Did** — `money` now records an entrance's external **"Ремонт и обновяване"** fund account (PM-FUND-001): its IBAN, its holder (the chair — **MANAGER** — or the **ASSOCIATION**, the two чл. 50 permits), and its purpose. `POST /api/money/entrances/{id}/fund-accounts` files it; `GET` lists them.

**The account holds no money — that is the whole point (ADR-007).** The row carries **no balance column**; the platform never holds fund monies, so this is only the external account a payment initiation would later target. A balance, when it exists, is derived from postings, never stored.

**No commingling, structurally (PM-FUND-004/005)** — `UNIQUE(iban)` across all entrances and `UNIQUE(entrance_id, purpose)`: one IBAN belongs to one account, and an entrance keeps at most one account per purpose. So the fund **cannot equal the operating account** and monies **cannot be commingled** — enforced by the table, pre-checked in the service for a clean 409, and proved end to end against real Postgres.

**Schema evolved, honestly** — `fund_account.holder_party → registry.party` became `holder_name` + `holder_kind`, because **parties are not modelled yet**. The party link returns (nullable, back-filled) with the owners/parties slice. IBAN is normalised (spaces stripped, upper-cased, ISO-13616 shape); the **mod-97 checksum is deferred to `rail`**, where the IBAN is actually used for initiation.

**Tests** — `IbanTest` (normalise / reject); `FundAccountServiceTest` (validation + both no-commingling guards, mocked, runs locally); `FundAccountWebTest` (201 / 409 / 400); `FundAccountPersistenceIT` (Docker, CI: register + read back; a second same-purpose account refused; the fund refused an operating IBAN yet coexisting on its own).

**`./tools/gates.sh` green** — 9/9. Traceability **26/233 (11%)** — PM-FUND-001/004/005 newly covered; TESTPLAN 208 remaining; schema-columns 10 entities; banned-words clean on 69 files. OpenAPI unchanged — the registration endpoint stays out of the published contract (the `/fund` balance endpoint, PM-FUND-009, is a later slice).

**Deferred here** — the fund **balance net of committed work** (PM-FUND-009), the **minimum contribution floor** against the national minimum wage (PM-FUND-002, ⚠ + a new `:law` constant), **disbursement** (PM-FUND-006/007/008 — the ADR-007-sensitive path), and the **party / mandate** link.

**Open / next** — **owners / parties** in `registry` (a receivable needs a liable party; also unblocks the fund holder link), then **intake / spreadsheet import**, toward Gate-1 contract-complete → the frontend green light.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/FundAccount.kt`, `app/src/main/kotlin/zues/app/money/FundAccountService.kt`, `docs/adr/ADR-007-no-custody.md`.

---

## S-28 · 2026-09-16 · fix — fund IT reused a global IBAN (CI red → green)

**CI on S-27 (`6082cc0`) went red while local was green** — the gap is by design: the Docker-gated ITs skip locally and only run in CI. `FundAccountPersistenceIT` shared one Postgres container across its three methods (a static `@Container`, inserts not rolled back) but reused **two IBAN literals**, and `UNIQUE(iban)` is **global**. Whichever test ran after the first collided on the same IBAN and got a 409 where it expected 201. The production code was right; the *fixture* was wrong — the failure was the no-commingling guard doing its job.

**Fix** — a static counter mints a distinct, well-formed IBAN per registration (`freshIban()`), so each test inserts its own. The PM-FUND-004 test still reuses one IBAN on purpose, to prove the iban-conflict.

**No gate for this one** — S-21's column bug was statically detectable (hence the S-22 gate); "a test reuses a globally-unique fixture" is semantic, not cheaply gate-able. The standing rule stands: an IT that shares a container must treat a unique column as global and mint fresh values.

**Local `./tools/gates.sh` green** (the IT still skips locally) — CI is the real proof this time. Only traceability line numbers regenerated.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/test/kotlin/zues/app/money/FundAccountPersistenceIT.kt`.

---

## S-29 · 2026-09-16 · owners & parties — the liable party a receivable needs (PM-ORG-011, PM-BOOK-002/011)

**Did** — `registry` now models **who owns a unit**. A **party** (person or legal entity) is registered with an optional identity (ЕГН / БУЛСТАТ / passport), and a **title** links a party to a unit as **OWN** or **USR** for a share, effective-dated. Endpoints: `POST /api/registry/parties`, `POST /entrances/{e}/units/{u}/titles`, and `GET /entrances/{e}/owners?on=YYYY-MM-DD`.

**Ownership resolves *as of a date*, never today (PM-ORG-011)** — a title carries `[validFrom, validTo)`, so a sale ends the seller's title on the day the buyer's begins, and the owners read returns whoever held the unit on the asked date. Same period-aware discipline as S-25's occupancy; this is what a past charge or an arrears claim will resolve the liable party against.

**ЕГН never reaches a resident-visible list (PM-BOOK-011)** — the owners view carries the party's **name only**; the identity number has no field in the response, proved against real data. The restricted single-party read that *may* show it (PM-BOOK-006) waits for the authorization module (ADR-002).

**Co-ownership is a share (PM-ORG-005)** — a title's `share ∈ (0, 1]`, several titles per unit. Summing shares to one, and the voting double-count guard, are the assembly module's, later.

**Tests** — `OwnershipServiceTest` (name recorded; bad id-type / share / party rejected; the as-of-date resolution across a sale, mocked); `OwnershipWebTest` (201 / 400 / 404; the owners response has a name but no id field); `OwnershipPersistenceIT` (Docker, CI: the sale transition; the ЕГН absent from the list). Each IT uses its own entrance/unit/party — no shared-fixture trap (the S-28 lesson).

**`./tools/gates.sh` green** — 9/9. Traceability **30/233 (13%)** — PM-ORG-005, PM-ORG-011, PM-BOOK-002, PM-BOOK-011 newly covered; TESTPLAN 204 remaining; schema-columns 12 entities (party, title). OpenAPI unchanged. `party.contact` is left to its column default (no jsonb mapping this slice).

**Deferred** — the book-complete check (PM-BOOK-002 full), the 15-day declaration deadline + overdue task (PM-BOOK-003, ⚠ + `:law`), book read-authorization (PM-BOOK-006, ADR-002), ЕГН retention/anonymisation (PM-BOOK-010), and shares-sum-to-one.

**Open / next** — **intake / spreadsheet import** (Gate 1's literal "reproduce the firm's spreadsheet") and/or the **receivable read** (a resident's balance) — either moves toward Gate-1 contract-complete → the frontend green light. A small follow-up can now wire `fund_account.holder_party`, since parties exist.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/registry/OwnershipService.kt`, `app/src/main/kotlin/zues/app/registry/Title.kt`.

---

## S-30 · 2026-09-16 · fund holder → party (closes the S-27 deferral)

**Did** — now that parties exist (S-29), a fund account's holder can be **linked to a book party**. `money.fund_account` gains a nullable `holder_party → registry.party`; registration takes an optional `holderPartyId` and the read returns it. `holder_name`/`holder_kind` stay as the stated holder and the fallback when the holder is not a modelled party (the association, or a chair not yet in the book).

**A DB FK, not a code dependency** — `holder_party` references `registry.party` at the schema level, exactly as `entrance_id` already references `registry.entrance`; `money` imports no registry type, so ADR-003's no-cross-module-code rule holds. A party that does not exist is caught by the FK, not by a cross-module read (which money may not do).

**Tests** — `FundAccountServiceTest`: the holder party is stored when given, a malformed id is a 400. `FundAccountPersistenceIT` (Docker, CI): register a party, link it, read it back; the IBAN comes from `freshIban()` (the S-28 lesson).

**No new rule coverage** — this refines PM-FUND-004's holder from free text to a precise reference; traceability stays **30/233 (13%)**. `./tools/gates.sh` green 9/9 (schema-columns 12 entities, `fund_account` with the new column). Only traceability line numbers regenerated.

**Open / next** — **intake / spreadsheet import** (Gate 1's "reproduce the firm's spreadsheet") or the **receivable read** (a resident's balance), toward Gate-1 contract-complete → the frontend green light.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/FundAccount.kt`, `app/src/main/kotlin/zues/app/money/FundAccountService.kt`.

---

## S-31 · 2026-09-17 · intake fee-sheet dry-run — Gate 1's "reproduce to the cent"

**Did** — the **intake** module (the sixth with app code) and Gate 1's centerpiece: `POST /api/intake/entrances/{e}/fee-sheet/dry-run` takes a firm's fee sheet (CSV) plus the tariff it was built on, recomputes every fee through the **same `:charges` engine** that will bill it, and compares **to the cent**. The report says `reproduced` (Gate 1 met), names each `differing` unit with its delta, and lists `violations`.

**Stateless, like money's first slice (S-15)** — no persistence: parse → recompute → diff → report. The import record, commit and revert are the next intake slice, so every test runs locally and there is no Docker IT.

**A dry-run reports, it does not raise** — a sheet whose ideal parts do not sum to 100% (PM-ORG-002), or a row that will not parse, becomes a **violation in the report**, not a 500. Only a malformed *tariff* — the caller's own input, e.g. an unknown cost stream — is a 400.

**Module boundary held** — intake computes via `:charges` (the pure library), never through `money`; it imports no other app module, and `ApplicationModules.verify()` (ModularityTests) passes with the new module. Intake "owns no rules" — it enforces ORG and FEE on the way in (ADR-003).

**Input, first cut** — a clean CSV export: header `designation,ideal_parts,occupants,fee_minor`, dot decimals, integer minor units (ADR-006, no floats). Quoting, embedded delimiters, locale-formatted numbers and XLSX are deliberate later hardening.

**Tests** — `FeeSheetTest` (clean / missing column / malformed row / empty); `IntakeDryRunTest` (**PM-FEE-014** reproduces to the cent; a **one-cent** difference named; **PM-ORG-002** sum ≠ 100% a violation); `IntakeWebTest` (the report; a bad cost stream → 400).

**`./tools/gates.sh` green** — 9/9. No new rule coverage — intake re-applies PM-FEE-014 and PM-ORG-002, already covered — so traceability stays **30/233 (13%)**; banned-words clean on 82 files. Only traceability's displayed references regenerated.

**Open / next** — intake **persistence** (import record → dry-run → commit → revert, PM-BOOK-007) and/or **locale/XLSX** parsing; the **receivable read**. When `registry` + `money` + `intake` for Gate 1 are contract-complete and their OpenAPI is frozen, the frontend green light (ADR-011 §3) — not yet: intake persistence and the receivable read are still owed.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/intake/IntakeDryRun.kt`, `app/src/main/kotlin/zues/app/intake/FeeSheet.kt`.

---

## S-32 · 2026-09-17 · unit statement — what a unit owes, and why (PM-FEE-018)

**Did** — `GET /api/money/units/{unitId}/statement`: a resident's itemised statement. The **balance is derived from the ledger** — the sum of the unit's `RECEIVABLE` postings (ADR-006) — so when payments later post their credits the balance falls without touching the immutable charge lines. The **itemisation** comes from the issued charge lines (each with its cost stream, allocation key, quantity and derivation, PM-FEE-018), dated by their run and sorted by period then component.

**A unit with nothing billed owes nothing** — an empty statement (balance 0, no lines), not a 404. Whether the unit exists is registry's to say; money reports only what it has charged.

**Reads only money's own tables** — postings, charge lines, charge runs — so it stays inside the module (ADR-003); the unit id is the only cross-boundary value, and it is just a UUID.

**Tests** — `StatementServiceTest` (balance = Σ receivable postings; every line itemised with a derivation; an empty unit owes nothing, mocked); `StatementWebTest` (the read's shape); `StatementIT` (Docker, CI: issue a 60/40 run of 10000 management + 20000 maintenance, then read ап. 1's statement — balance 18000, two dated, derived lines).

**`./tools/gates.sh` green** — 9/9. No new rule coverage — PM-FEE-018 was already proved on the engine's derivation, so this is a new read over it — traceability stays **30/233 (13%)**; only its displayed reference regenerated. Two new derived queries (`postings.findByUnitIdAndAccount`, `chargeLines.findByUnitId`); no schema change.

**Open / next** — this closes the charge → post → **owe** loop for Gate 1. Remaining before Gate-1 contract-complete: intake **persistence** (import record → commit → revert, with the cross-module commit decision to settle) and, on top of this balance, the **arrears / ageing** view (PM-DEBT-001, 0–30/31–60/61–90/90+). Then the frontend green light (ADR-011 §3).

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/Statement.kt`, `app/src/main/kotlin/zues/app/money/Postings.kt`.

---

## S-33 · 2026-09-17 · fix — `balance` is a banned identifier (gate red)

**S-32 (`2a4869d`) was pushed with a red gate.** The banned-words gate forbids the exact identifier `balance` — a *stored* balance is the mistake, since a balance is derived from postings (ADR-006). `Statement.kt` had a local `val balance`; renamed to `val balanceMinor` (the field already carried that name and passes — the check flags only the exact word `balance`, not `balanceMinor`). `./tools/gates.sh` now green 9/9, exit 0.

**Two process slips, both mine, recorded so they do not recur:**
- **Masked gate output — the real cause.** I read the gates through `| grep` and `| tail`, which hid gate 7's failure, and the pipe's exit code was `tail`'s `0`, so the `&&` chain pushed a red commit. **Run `./tools/gates.sh` and read its own exit code and the `ALL GATES GREEN` line — never pipe it in a way that drops the status.**
- **Forgot the branch.** S-32 was built directly on `main`. Harmless here — the gate pack is the bar, not the branch — but the ritual is `slice/S-nn-*` off `main`.

**Read first next time** — `docs/INDEX.md`, this entry, `tools/check_banned_words.py`.

---

## S-34 · 2026-09-17 · intake persistence — the import record (verdict + provenance)

**Did** — a fee-sheet import is now **durable**. `POST /api/intake/entrances/{e}/imports` runs the dry-run (S-31) and stores the result: its verdict (`REPRODUCED` when the engine matched the firm to the cent, else `NEEDS_REVIEW`), the counts, and a **SHA-256 of the source** — what the firm actually gave us (STAGE1-ADDENDUM §1, step 1). `GET /api/intake/imports/{id}` reads it back. New `intake` schema + `fee_import` table.

**Deliberately the envelope, not the contents** — the column mapping, the profiled structure, and the rows a commit would create are **not** modelled, because the addendum says a real pilot spreadsheet defines the intake schema (and "will probably invalidate an assumption while that is still cheap"). What is stored — a verdict and a content hash — is orthogonal to that mapping, so it is safe to build now.

**Commit stays deferred, with its seam** — step 6 (one transaction, every created row carrying the `import_id`, revertible by `import_id`) writes across module boundaries into `registry`/`money`. That decision — domain events vs a published write-port — is unsettled and waits for a real spreadsheet and an explicit call. So this slice records imports; it does not yet adopt them. The source file itself belongs in `evidence` eventually; for now only its hash is kept in intake.

**Module boundary held** — intake's new entity carries only scalars (the `entrance_id` FK is a UUID); it imports no other app module, and `ModularityTests` passes. Intake still owns no rules.

**Tests** — `ImportServiceTest` (a reproduced sheet → `REPRODUCED` + a 64-char hash; one cent off → `NEEDS_REVIEW`; a missing import not found, mocked); `ImportWebTest` (201 with id + report; the read; a 404); `ImportPersistenceIT` (Docker, CI: record and read back both a reproduced and a one-cent-off import). `intake` added to the IT search_path.

**`./tools/gates.sh` green — 9/9, exit 0** (read the real exit code, per the S-33 lesson). Schema-columns 13 entities / 25 tables (`fee_import`); banned-words clean on 93 files. No new rule coverage — intake owns none — so traceability holds at 30/233; no generated doc changed.

**Open / next** — the intake **commit** (the cross-module adoption; needs the seam decision and ideally a real spreadsheet) and column **profiling** (step 2); the **arrears / ageing** view on the statement balance (PM-DEBT-001). Gate-1 contract-complete still owes the commit path.

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/intake/ImportService.kt`, `docs/STAGE1-ADDENDUM.md` (§1, the six steps).

---

## S-35 · 2026-09-17 · arrears ageing — what a unit owes, by how overdue (PM-DEBT-001/002)

**Did** — `GET /api/money/units/{unitId}/arrears?asOf=YYYY-MM-DD`: a unit's outstanding, aged into the standard bands (**CURRENT / 0-30 / 31-60 / 61-90 / 90+**, PM-DEBT-001). Each receivable posting falls due `PAYMENT_TERM_DAYS` after its value date (**PM-DEBT-002**, 14 days, чл. 38 ал. 1 — a confirmed `:law` constant), and is aged by days overdue as of the read date. Every band is present, in order, so the shape is stable for a caller.

**Payments aren't modelled yet, so everything owed is still owed** — the total is the sum of the unit's `RECEIVABLE` postings (ADR-006). When a payment posts its credit, the total and the aged bands fall automatically; nothing here changes.

**Two honest approximations, both noted:**
- The **ageing bands are an accounting convention, not statute** — the band edges are marked `// not-legal:` so the thresholds gate stays meaningful; only `PAYMENT_TERM_DAYS` is a legal number, and it lives in `:law`.
- The due date anchors on the charge's **value date** as a stand-in for the decision's announcement (PM-DEBT-002 is "14 days after announcement"); decisions are the assembly module's, not built. The term is right; the anchor is a documented proxy.

**Reads only money's own postings** — self-contained (ADR-003); the unit id is the only cross-boundary value.

**Tests** — `ArrearsServiceTest` (three postings land in 0-30 / 31-60 / 90+; a not-yet-due charge is CURRENT; every band present even at zero — mocked, term read from config); `ArrearsWebTest` (the aged read; a malformed `asOf` → 400); `ArrearsIT` (Docker, CI: issue a run, read arrears 5 days past due → the 18000 sits in 0-30).

**`./tools/gates.sh` green — 9/9, exit 0** (verified). Traceability **32/233 (14%)** — PM-DEBT-001, PM-DEBT-002 newly covered; TESTPLAN 202 remaining; legal-thresholds clean (the bands escaped, the term in `:law`). No schema change — arrears is a read over the existing postings.

**Open / next** — an **entrance-wide arrears roll-up** (all debtors), default **interest** on overdue (PM-DEBT-006, dated config), oldest-first **payment allocation** (PM-DEBT-008); and still, for Gate-1 contract-complete, intake's **commit** (needs a pilot spreadsheet + the seam decision).

**Read first next time** — `docs/INDEX.md`, this entry, `app/src/main/kotlin/zues/app/money/Arrears.kt`, `law/src/main/kotlin/zues/law/Constants.kt`.

---

## H-02 · 2026-09-17 · Handover — resume point before /compact

**RESUME HERE.** `main` is at `e8d1fef`, clean and CI-green. This consolidates the session that built S-26 → S-35 (ten slices + two fixes) on top of H-01, so the next session continues without the conversation.

**Where the code is now** — the fee engine runs **charge → post → owe → age**, end to end:
- **`registry`** — entrances · units · household · animals · **absence declarations** (S-26) · **parties + titles** (owners/users, effective-dated, ЕГН never in a resident list) (S-29).
- **`money`** — charge run compute/persist/post (double-entry) · **fund account** (the чл. 50 external account, no custody) (S-27) · **fund holder → party** link (S-30) · **unit statement** (itemised, ledger-derived balance) (S-32) · **arrears ageing** (CURRENT/0-30/31-60/61-90/90+) (S-35).
- **`intake`** (the sixth module with app code) — **fee-sheet dry-run** (Gate 1 "reproduce to the cent", stateless) (S-31) · **import record** (verdict + source hash, durable) (S-34).
- **`:law`** gained `ABSENCE_DECLARATION_GRACE_DAYS` (unverified) and `PAYMENT_TERM_DAYS` (14, confirmed).

**Three of fourteen modules have app code** (registry, money, intake).

**Progress** — rules test-covered **32/233 (14%)**, up from 22 at H-01. Whole-plan ≈ **~20%** (foundation front-loaded; see H-01). Gates 2–4 still at 0%. **Gate 1 is ~70–80% built** — the one functional piece left is intake **commit**.

**The one thing blocking Gate-1 contract-complete → the frontend green light:**
- **intake commit** — adopt an imported sheet's rows into `registry`/`money`, one transaction, every row carrying `import_id`, revertible by `import_id` (STAGE1-ADDENDUM §1, step 6). It is **blocked on two owner/design items**: (1) a **real pilot spreadsheet** — the addendum says it *defines the intake schema* and "will probably invalidate an assumption while that is still cheap" (INDEX lists it as the cheapest, highest-value unblock); (2) the **cross-module write seam** — domain events vs a published write-port — best settled against real data. Do not invent the intake mapping schema before the spreadsheet.

**How this session operates (owner delegated the workflow — reaffirm or change):**
- One slice = one branch `slice/S-nn-*` off `main` → build → `./tools/gates.sh` green **locally** → SESSIONLOG entry → commit (attributed) → **fast-forward to `main` + push** → delete branch. Direct-to-`main` because there is **no GitHub auth in-session** (`gh` logged out); the gate pack is the quality bar.
- **No Docker locally** → the Testcontainers `*IT`/`*PersistenceIT` **skip locally, run in CI** (`ci.yml`, the full gate pack on Ubuntu). After a DB-touching slice, confirm the CI run is green via the **public GitHub Actions API** (repo is public; poll `actions/runs?per_page=N` and match `head_sha`) — `gh` cannot be used.
- **Two hard-won rules from this session's mistakes (S-33):** (1) **Read `./tools/gates.sh`'s own exit code and its `ALL GATES GREEN` line — never pipe it through `| tail`/`| grep`**, which hid a failing gate and pushed a red commit (`2a4869d`). (2) **Actually create the `slice/…` branch** before building (S-32 was built on `main` by mistake — harmless, but off-ritual).
- Gate quirks that bit real slices: the **banned-words** gate forbids the exact identifiers `balance`/`fee` (derive a balance from postings, ADR-006) — `balanceMinor` is fine, `balance` is not; the **legal-thresholds** gate flags watchlist numbers in comparisons — put statutory numbers in `:law`, and mark a genuinely non-legal number (e.g. accounting ageing bands) `// not-legal: <why>`; the **schema-columns** gate maps camelCase→snake_case with no underscore before a digit; IT fixtures that share one container must respect **global** unique constraints (S-28 — mint fresh values).
- Toolchain: JDK 21 (`gates.sh` sets `JAVA_HOME=/usr/local/opt/openjdk@21`). The session cwd sometimes flips to `…/weatherappnew`; use `git -C …/domuvai` / absolute paths.
- Attribution (keep): commits end `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; PRs end `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

**Next slices — three unblocked, pick per owner:**
1. **Entrance-wide arrears roll-up** — all debtors of an entrance in one aged view (extends S-35, self-contained).
2. **Default interest** on overdue (PM-DEBT-006, dated `:law` rate) · oldest-first **payment allocation** (PM-DEBT-008).
3. **Start the Gate-1 frontend** for the stable contracts already built — only intake commit is missing (ADR-011; owner still owes the **monorepo vs separate-repo** decision).
Blocked until a pilot spreadsheet: **intake commit** (the Gate-1 finisher).

**Frontend** — not started; **backend only**. Separate Next.js app, contract-first (ADR-010/003/011). Open owner decision: **repo layout — monorepo `web/` (recommended) vs separate repo** (ADR-011 §3).

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry, `docs/STAGE1-ADDENDUM.md` (§1 intake), `docs/adr/ADR-011-frontend-topology.md`, then `git log --oneline -14` and `./tools/gates.sh`.

---

## S-36 · 2026-09-17 · intake commit — the Gate-1 finisher (the seam, resolved)

**Did** — built intake **commit** and **revert**: a reviewed import (`REPRODUCED`) is committed, its units are adopted into `registry`, each stamped with `import_id` and revertible by it (STAGE1-ADDENDUM §1, step 6). Owner directed "continue with the finisher" and declined to defer, so this ships **minimal + provisional** rather than waiting for the pilot spreadsheet — the mechanism, with the pilot-sheet-shaped fields defaulted and flagged.

**The seam my H-02 handover left open is resolved — by re-reading, not by an owner decision.** MODULE-TEMPLATE's three laws settle it: law 1 (one transaction writes one module's schema + the outbox, never two) *forbids* the synchronous cross-schema write-port I'd half-considered; law 3 (reactions go through outbox events) *mandates* the event. So commit = intake publishes **`ImportCommitted`**, `registry` reacts and adopts; revert = **`ImportReverted`**, `registry` drops the stamped rows. Both events were **already in the A8 catalogue** (`docs/events/`), so none was invented; the pattern (`ApplicationEventPublisher` + `@ApplicationModuleListener`) was already in the tree (`money/ChargeRunStore`, `registry/RegistryNotifications`). The externalized schema is a **counts summary**; the in-process event additionally carries the rows for the listener (dropped at externalization).

**Dependency direction** — `registry` → `intake` on the event type only (the consumer depends on the producer's event), acyclic because `intake` never imports `registry`. `ApplicationModules.verify()` (ModularityTests, runs locally) is **green**, so the seam is boundary-legal.

**Deliberately provisional (all flagged, none silent), pending the pilot spreadsheet:**
- Only **designation + ideal parts** are adopted — the exact fields the dry-run already maps. `unit_type` defaults to `UNSPECIFIED` (`TODO(pilot-sheet)`); area, **occupancy, owners/household** are not in a fee sheet and wait for the real file.
- **money is untouched** — fees follow from a later charge run; no arrears/opening-balance migration here.
- `committed_by` is a required request field (a commit names its actor) but is **not yet identity-validated** (identity-org unbuilt); `source_document_id` = the import id as a **stand-in** until `evidence` exists.
- Adoption is **insert-only into a fresh entrance** (`rows_changed` = 0); merge/re-import is a later slice.

**Rules covered** — **PM-ORG-002** "the sum of ideal parts per entrance MUST equal 100%": `adoptImport` reuses `UnitValidation.requirePartsSumTo100`, and the deferred DB trigger backstops it. **PM-ORG-001** "model ownership at the level of … a separate entrance": adopted units are entrance-scoped and read back under it. (Coverage count stays 32/233 — PM-ORG-002 was already covered; this slice re-enforces it on the commit path.)

**Schema** — `registry.unit.import_id uuid` (provenance, nullable, no cross-schema FK); `intake.fee_import.status` CHECK extended with `COMMITTED`/`REVERTED`; the `assert_parts_sum_100` trigger now allows an **empty** entrance (total 0 **or** 100) so a first import is revertible to nothing — the populated-entrance invariant (exactly 100%) is unchanged, a partial set (e.g. 90%) still refused.

**Tests added** — local (run in the gate pack): `ImportServiceTest` — commit adopts + publishes `ImportCommitted`; a non-`REPRODUCED` import and a hash-mismatched sheet are refused; revert publishes `ImportReverted`; only a `COMMITTED` import reverts. `ImportCommitWebTest` — commit 200 + counts, wrong-state **409**, revert 200. CI-only IT: `ImportCommitPersistenceIT` — the HTTP commit→revert status lifecycle, and the registry listener adopting (stamped, `UNSPECIFIED`, summing 100 — PM-ORG-002 — under the entrance — PM-ORG-001), **idempotent** on redelivery, dropped on revert; a 90% set refused.

**Decisions** — no new ADR. The seam follows ADR-003 §4 + MODULE-TEMPLATE laws 1/3 (recorded there, not re-decided here). The trigger refinement (empty entrance is valid) is a PM-ORG-002-preserving clarification, documented in `V1__init.sql`.

**`./tools/gates.sh` — 9/9, exit 0** (verified: read the exit code and the `ALL GATES GREEN` line, per the S-33 rule). Diff ≈517 lines (≈220 logic, ≈300 tests) — larger than the ~400 guide because it stands up the first cross-module event flow.

**Honest coverage gap** — the **async delivery hop** (Modulith dispatch from publish to listener) is not directly asserted: I cannot run Docker ITs locally, so I proved the two halves separately (intake publishes — local unit test; registry adopts — the IT calls the listener directly) rather than risk an un-runnable async test pushing a red CI. A `Scenario`-based end-to-end await is a worthwhile follow-up.

**What Gate-1 needs now** — the commit *mechanism* is built. What remains is genuinely pilot-sheet-shaped: the **rich mapping** (the firm's real columns → owners/household/IBANs/opening balances, XLSX, locale numbers) and money migration. That is additive on this seam, not a redesign of it.

**Open / next** — (1) confirm CI green on this push (DB-touching: the IT runs only in CI). (2) Still unblocked and independent: entrance-wide **arrears roll-up**, default **interest** (PM-DEBT-006), oldest-first **allocation** (PM-DEBT-008). (3) The **pilot spreadsheet** now unblocks the *rich* intake mapping and is still the cheapest, highest-value external item. (4) Frontend green light (ADR-011) — the backend commit contract is now in place; owner still owes the **monorepo vs separate-repo** decision.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry, `docs/STAGE1-ADDENDUM.md` (§1 intake), `docs/MODULE-TEMPLATE.md` (laws 1/3 — the seam), `app/src/main/kotlin/zues/app/intake/ImportService.kt` + `app/src/main/kotlin/zues/app/registry/ImportAdoption.kt`, then `git log --oneline -14` and `./tools/gates.sh`.

---

## S-36 fix · 2026-09-17 · the commit IT's adoption assertions were async

**Found** — CI red on `3b5259b` (the Gate-pack step, a Docker IT — invisible locally, all 9 gates were green). `ImportCommitPersistenceIT` called the registry's listener directly (`adoption.on(event)`), but **`@ApplicationModuleListener` is meta-annotated `@Async`**, so the injected bean is an async proxy: the call dispatched to a background thread and returned before the units existed — the size assertion saw 0, and the expected exception was thrown on the background thread rather than caught. The HTTP-lifecycle test (asserts only status codes) was unaffected.

**Fixed** — the two adoption tests now call `RegistryService.adoptImport`/`revertImport` directly (plain `@Transactional`, synchronous), proving the same properties — PM-ORG-001/002, `import_id` provenance, idempotency, revert — deterministically. The listener→adopt hop is one line; the async delivery between publish and adopt stays a documented follow-up (a Spring Modulith `Scenario` await).

**Lesson** — `@ApplicationModuleListener` is `@Async`; calling such a bean's method directly in a test still goes through the async proxy, so it does not run synchronously. Exercise the reaction by calling the underlying non-async service (or unwrap the proxy). And a corollary of the no-Docker-locally rule: an IT that runs **only** in CI has to be reasoned through for async and lifecycle, because it cannot be run locally to catch this before the push.

---

## WF-01 · 2026-09-17 · parallel-development setup (three developers)

**Did** — turned the two-person protocol into a three-developer one, enforced by the repo so every Claude session picks it up automatically (not a code slice — no rule/module change):
- **`CLAUDE.md`** gained a **"Working in parallel"** section — auto-loaded by every session, so the other developers' Claude sessions intercept it without being told: never push to `main` (branch → PR → CI → merge); one module per developer, claim the slice first; rebase before pushing; generated docs are regenerated, never hand-merged; schema changes are new `V<yyyyMMddHHmm>__*.sql` files; `SESSIONLOG.md` union-merges.
- **`.gitattributes`** (new) — `docs/SESSIONLOG.md merge=union` so two appended entries concatenate instead of conflicting; generated docs marked `linguist-generated`.
- **`tools/check_schema_columns.py`** — now reads **every** `V*.sql` and understands `ALTER TABLE … ADD COLUMN` (was hardcoded to `V1__init.sql`), so additive migrations are gate-safe. Verified: same 25 tables today, and an ALTER-added column is now seen.
- **`docs/WORKING.md`** — updated for three: never-push-to-`main` + rebase + claim in the loop, a **Conflict surfaces** table, a module-lane work split (the A-plan split was stale), and a pre-flight (`gh auth login` per dev, branch protection).

**Gates** — 9/9, exit 0. Bootstrap change committed **direct-to-`main`** because there is no `gh` auth in-session to open a PR — the last such push before the PR flow is turned on.

**Owner action to finish the switch** — each developer runs `gh auth login`; enable **branch protection** on `main` (PRs required, the gate-pack CI run a required check). Until both are done, a session with no GitHub auth still falls back to direct-to-`main`.

**Read first next time** — `CLAUDE.md` (Working in parallel), `docs/WORKING.md`, this entry.

---

## S-37 · 2026-09-18 · payment allocation — settle the oldest debt first (the rule, pure)

**Did** — implemented PM-DEBT-008 as pure logic in `money`: `PaymentAllocation.allocate` settles a unit's **oldest debt first**, or a payer-**designated** debt first with the remainder oldest-first, and returns the **rule applied** (`OLDEST_FIRST` | `DESIGNATED`) plus the per-debt breakdown — explainable per payment (ЗЗД чл. 76). `netOutstanding` applies prior payments FIFO oldest-first, so a later payment sees the correct remaining debts (ADR-006 — outstanding **derived, never stored**). No persistence, no clock, no I/O.

**Rules covered** — PM-DEBT-008 (MUST). Traceability 32 → 33/233; TESTPLAN 202 → 201.

**Tests added** — `PaymentAllocationTest` — 9 cases, named after PM-DEBT-008 (+ a positive-amount guard): oldest-first, partial reach, designation-first, rule-visible, overpayment remainder, nothing-owed, prior-payments FIFO, a later payment after prior ones. All pure — run in the **local** gate pack, no Docker.

**Decisions** — none. Split from the full payment feature to stay under the reviewable-diff limit (~480 lines whole): this slice is the rule; S-38 wires it.

**Out of scope / next (S-38)** — persist the payment; post the RECEIVABLE credits **dated to each settled debt** so Statement (S-32) and Arrears (S-35) stay consistent with oldest-first; the HTTP endpoint. Then default interest (PM-DEBT-006, SHOULD — dated `:law` rate) builds on it.

**Read first next time** — this entry, `app/src/main/kotlin/zues/app/money/PaymentAllocation.kt`, `app/src/main/kotlin/zues/app/money/Postings.kt` (the ledger S-38 credits).

---

## S-38 · 2026-09-19 · Book of the Condominium — completeness + read/export (PM-BOOK-001/002)

**Did** — the домова книга (чл. 7 ЗУЕС) as a **read** over registry's own record (units · titles/parties as-of · household · non-use), never a second copy. `BookService.forEntrance(entranceId, on)` assembles per-unit entries (designation, built area, ideal parts, owner/user names, household count, non-use periods) and a **book-complete** flag; `GET /api/registry/entrances/{e}/book?on=` reads it back — the electronic book is the system of record. Names only, never ЕГН (PM-BOOK-011); resolved **as of** a date, never today (PM-ORG-011). **No schema change** — a pure read.

**Rules covered** — PM-BOOK-001 (MUST, чл. 7 ал. 1), PM-BOOK-002 (MUST, чл. 7 ал. 2). Traceability → **34/233 (15%)**; TESTPLAN 200 remaining. This closes the **ADR-011 §3 registry "book completeness + ministry export" gate item**.

**Tests added** — `BookCompletenessTest` (pure, PM-BOOK-002: complete only with ideal parts + an owner named, incl. missing/blank) and `BookWebTest` (PM-BOOK-001 read-back + malformed-date 400) run **locally**; `BookPersistenceIT` (a unit with owner/household/non-use → complete; a unit with no owner → incomplete; book-level complete; HTTP read-back) runs in CI.

**Decisions** — none. `complete` follows the acceptance exactly: ideal parts present AND ≥1 `OWN` title; built area is recorded but does not gate completeness.

**Out of scope** — the exact ministry export **layout/versioning** (PM-BOOK-004); the 15-day-declaration overdue task (PM-BOOK-003 — compliance + clock); the access/export **audit log** (PM-BOOK-007); **temporary occupants** and **agreed owner–user rights** (no registry model yet — the book shows what exists).

**Gate 1 status** — with this, the **only** remaining Gate-1 backend gap is the **intake rich mapping**, which is blocked on the pilot spreadsheet. So the Gate-1 backend contract is as complete as it can be without that file; the frontend green light (ADR-011) now turns on the **pilot spreadsheet** + the owner's **repo-layout & auth** decisions + freezing the OpenAPI — not on more backend. Payments/interest (S-37's "S-38" note) are Gate 2 and resume after.

**Read first next time** — this entry, `docs/adr/ADR-011-frontend-topology.md` (§3 green light), `app/src/main/kotlin/zues/app/registry/BookService.kt`.

---

## S-39 · 2026-09-21 · intake mapping model + profiler (format-agnostic, ADR-012)

**Did** — the first slice of **format-agnostic intake** (ADR-012 Accepted this session). `IntakeField` fixes the **target** fields a fee sheet maps onto — derived from the rules, not from any sheet: `DESIGNATION`, `IDEAL_PARTS`, `OCCUPANTS`, `FEE_MINOR` (required — the minimum to reproduce & compare a fee), plus `BUILT_AREA`, `OWNER_NAME`, `CHILDREN_UNDER_6`, `ANIMALS`, `ABSENT_DAYS`, `BUSINESS_USE` (recorded when present). `MappingProfiler.profile(headers)` proposes a column→field mapping by header aliases (English **and** Bulgarian), surfaces columns it cannot place, and names a required field no column carries. Pure — a **proposal a human confirms**, never a silent adoption (STAGE1-ADDENDUM §1). No persistence.

**Rules covered** — none newly (the mapping is mechanism; intake owns no rules). The field-set derives from PM-BOOK-002, PM-ORG-002, PM-FEE-005/006/008/009/010, PM-ORG-009 — cited, not claimed.

**Tests added** — `MappingProfilerTest` (5, pure, **local**): the fixed 4-column layout; a **different** reordered Bulgarian layout with an extra column (ADR-012 §7 — the two-layout corpus that stands in for a pilot sheet); a missing required field named; unrecognised columns surfaced; optional fields mapped.

**Decisions** — ADR-012 (Accepted this session): intake is format-agnostic; a real sheet is validation, not schema; **go-live gate** (§7) caps the risk (build freely, do not bill real money until a real sheet reproduces to the cent).

**Out of scope / next (S-40)** — wire the confirmed mapping into the dry-run, **replacing** the fixed 4-column parse (S-31) with one mapping-driven path (the 4-column contract becomes a default mapping; the existing dry-run tests pass via it). Then S-41 (mapping-driven commit). Grow the adversarial fixture corpus as the harness.

**Read first next time** — this entry, `app/src/main/kotlin/zues/app/intake/IntakeField.kt`, `app/src/main/kotlin/zues/app/intake/FeeSheet.kt` (the fixed parse S-40 folds into the mapping), `docs/adr/ADR-012-intake-format-agnostic.md`.

---

## S-40 · 2026-09-21 · mapping-driven dry-run — one parse path (ADR-012)

**Did** — refactored `FeeSheet.parse` to be **mapping-driven**, replacing the fixed 4-column parse with one path (ADR-012). `parse(csv, mapping?)` uses a confirmed column→field mapping when given, else `MappingProfiler` auto-profiles the header. **Callers unchanged** (`parse(csv)`), so the dry-run (S-31) and commit (S-36) became **format-agnostic for free** — a Bulgarian- or arbitrarily-headed sheet now reproduces and commits. Standard headers auto-profile to the old fixed mapping, so the existing path is byte-identical.

**Rules covered** — none new (mechanism). The reproduce path (PM-FEE-014, S-31) is preserved, now format-agnostic.

**Tests added** — 2 in `FeeSheetTest`: a reordered **Bulgarian**-headed sheet parses via the profiler; a **confirmed mapping** parses headers the profiler cannot recognise (`col_a…`). The 4 original FeeSheet tests + `IntakeDryRunTest` + `ImportServiceTest` pass unchanged — the safety net for the refactor; the intake ITs run in CI on standard headers.

**Decisions** — ADR-012 (Accepted). One-parse-path was the integrity note from the S-39 evaluation (no two divergent ways to read a sheet).

**Out of scope / next (S-41)** — expose profile + a confirmed mapping through the API (add `mapping` to the request; a `POST …/profile` endpoint) and thread it into commit; adopt the **optional** mapped fields (owner, household, business, children, animals, absence) so reproduce + adopt use the fuller sheet. Grow the adversarial corpus.

**Read first next time** — this entry, `app/src/main/kotlin/zues/app/intake/FeeSheet.kt`, `IntakeField.kt` + `MappingProfiler.kt`, `docs/adr/ADR-012-intake-format-agnostic.md`.

---

## H-03 · 2026-09-21 · Handover — resume point before /compact

**RESUME HERE.** `main` is at `df5d8c9` (S-40 merged), clean, in sync, CI-green. This consolidates the session that ran from H-02 through S-40, so the next session continues without the conversation.

**What this session shipped (since H-02)**
- **S-36 intake commit** (+ a fix): adopt a reviewed sheet into `registry` via the outbox events `ImportCommitted`/`ImportReverted` (the settled seam — MODULE-TEMPLATE laws 1/3); revertible by `import_id`. Fix: the IT called an `@ApplicationModuleListener` (which is `@Async`) directly — corrected to call the synchronous service.
- **WF-01 parallel-development setup** (see below).
- **S-37 payment allocation** (PM-DEBT-008): pure oldest-first / designated allocation, `netOutstanding` FIFO — the rule, no persistence yet.
- **S-38 Book of the Condominium** (PM-BOOK-001/002): a pure read over registry (units · owners-as-of · household · non-use) with a book-complete flag; names only, as-of a date. **Closed the ADR-011 §3 registry gate item.**
- **ADR-012 (Accepted)**: **intake is format-agnostic** — map any firm's columns onto our known domain fields per import; a pilot sheet is *validation, not schema*. §7 holds the risk boundary: the **go-live gate** (build freely, do NOT bill real money until a real sheet reproduces to the cent), preventive measures (dated config, adversarial fixtures, a reproduction harness) and recovery (versioned charges, revertible imports, dated config — mostly already built).
- **S-39 intake mapping model + profiler**: `IntakeField` (target fields from the rules) + `MappingProfiler` (EN + BG header aliases; proposes, human confirms; surfaces unmapped, names missing).
- **S-40 mapping-driven parse**: `FeeSheet.parse` is now mapping-driven (one path); callers unchanged, so the dry-run + commit are format-agnostic; standard headers auto-profile to the old mapping (identical existing path).
- **Skills**: built `zues-audit` (read-only verify) and `zues-adr` (house ADR format) — the repo's skills table promised them; only `zues-slice` existed. All three now in `.claude/skills/`.
- **README**: developer quick-start; status de-staled.

**Two decisions of record this session** — **ADR-012** (intake format-agnostic + go-live gate, **Accepted**); **ADR-011** stays Proposed on the owner's repo-layout + auth calls.

**The big operating shift — the PR flow is LIVE**
- `gh` is now authenticated **as the owner (stpdimitrov, admin)** in-session, so work goes **branch → PR → CI green → merge** (PRs #3–#10 merged). Direct-to-`main` is superseded; it survives only as a fallback for a session with no `gh` auth.
- **Three-developer parallel workflow** is set up and enforced by the repo (so every Claude session inherits it): `CLAUDE.md` **"Working in parallel"** (never push `main`; one module per dev; rebase; regenerate-don't-hand-merge generated docs; additive migrations; `SESSIONLOG` union-merges), `.gitattributes` (`SESSIONLOG.md merge=union`), `check_schema_columns.py` reads every `V*.sql` incl. `ALTER TABLE … ADD COLUMN`, and `docs/WORKING.md` updated for three.

**Progress** — traceability **34/233 (15%)**; TESTPLAN 200 remaining. Modules with app code: registry, money, intake. Gate 1 backend is contract-complete **except** intake's rich mapping — and ADR-012 makes that **buildable without the pilot sheet** (in progress: S-39/S-40 done, S-41 next).

**Resume point — next work**
1. **S-41** (intake): expose profile + a **confirmed mapping** through the API (add `mapping` to the request; a `POST …/profile` endpoint), thread it into commit, and **adopt the optional mapped fields** (owner, household, business, children, animals, absence). Grow the adversarial fixture corpus + the reproduction harness (ADR-012 §7).
2. **Track B money** (unblocked, additive): **S-42 payment persistence** (wire S-37's allocation to the ledger + endpoint) → **S-43 default interest** (PM-DEBT-006).
3. **Track C frontend**: blocked on the owner's ADR-011 calls (repo layout mono/poly + auth).

**Owner actions still open** — branch protection on `main` (I can set it up, admin); `gh auth login` for the **other two developers** (their sessions fall back to direct-to-`main` without it); the **ADR-011** decisions; the **pilot spreadsheet** (now *validation*, not a blocker — get it when convenient).

**Operating lessons this session**
- **PR flow**: I open PRs and merge on green (self-merge is fine while sole active dev; branch protection will formalise it). The **CI poller's `rc=1` can be a transient network error**, not a check failure — re-verify with `gh pr checks <n>` before believing a red.
- **Branch per slice** — I slipped onto `main` for S-40 and caught it **before any commit** (nothing landed on `main`); create `slice/S-nn-*` first.
- **Gradle gotcha**: a stale daemon can point at a cleaned scratchpad distro → `NoSuchFileException` on a distribution JAR. Fix: `./gradlew --stop` and run with `GRADLE_USER_HOME="$HOME/.gradle"` (JDK 21 at `/usr/local/opt/openjdk@21`).
- **The go-live gate** (ADR-012 §7) is now in `CLAUDE.md` — never wire real billing without a real sheet reproducing to the cent.
- No-Docker-locally still holds: `*IT`/`*PersistenceIT` skip locally, run in CI; reason async/lifecycle through before pushing (the S-36 `@Async` bug).

**Read first next time** — `CLAUDE.md` (incl. "Working in parallel" + the go-live gate), `docs/INDEX.md`, this entry, `docs/adr/ADR-012-intake-format-agnostic.md`, then `git log --oneline -16` and `./tools/gates.sh` (with `GRADLE_USER_HOME=$HOME/.gradle`).

---

## S-41 · 2026-09-21 · intake — the mapping through the API (profile + confirmed mapping, ADR-012)

**Did** — exposed the format-agnostic intake mapping (S-39/S-40) through the HTTP contract, the ADR-011 §3 Gate-1 green-light piece:
- **`POST /api/intake/entrances/{id}/fee-sheet/profile`** — a pure propose step: reads a sheet's header, returns the `ProposedMapping` (column → field, the columns it could not place, the required fields no column carries). Stores nothing.
- Threaded a **confirmed `mapping`** through `FeeSheetDryRunRequest`, so dry-run, `record` and `commit` all read a non-standard sheet once a human confirms its mapping; omit it and the profiler proposes one (the standard-layout path, unchanged). At `commit`, the source hash still pins the bytes and the reproduce-to-the-cent check is the guard — the mapping is only how the same bytes are read.
- **Contract**: added the `profile` operation + `FeeSheetProfile`/`MappingProposal`/`IntakeField` schemas to `tools/build_openapi.py` (contract-first — the catalogue is the source, the controller implements it). Regenerated `docs/api/openapi.json` → **27 operations, valid 3.1**. The upload op summary was corrected (profiling is now its own step).
- **Adversarial corpus** (ADR-012 §7): profiler tests for duplicate-column first-wins and a wholly unrecognised header; web tests for the profile endpoint and a confirmed-mapping dry-run; a service test proving the mapping threads through `record`.

**Scope call** — S-41 is the *API exposure* only. **Adopting the optional mapped fields** (owner, household, animals, absence, business use) into the registry is **S-41b**: it changes the externalized `ImportCommitted` event contract and the registry adoption seam, so it earns its own PR with event-contract + IT coverage. Splitting it keeps both PRs reviewable and gate-green.

**Gates** — `./gradlew test` green (all modules, ITs skip locally); openapi/traceability/testplan/banned/schema-columns green. Traceability unchanged at 34/233 — S-41 is contract/mechanism, no new rule closed.

**Next** — **S-41b** (adopt optional fields into registry, event-contract change). Then **Track B**: S-42 payment persistence (wire S-37 to the ledger + endpoint) → S-43 default interest (PM-DEBT-006). **Track C** frontend still blocked on the owner's ADR-011 repo-layout + auth calls — but with S-41 the intake contract is now frozen, so once the owner answers, the Gate-1 frontend can start.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, the H-03 handover, this entry, then `git log --oneline -16` and `./tools/gates.sh` (with `GRADLE_USER_HOME=$HOME/.gradle`).

---

## ADR-011 · 2026-09-21 · Accepted — frontend topology settled (monorepo + OIDC/BFF)

**Did** — the owner (Stoyan Dimitrov) resolved the two open ADR-011 sub-decisions, moving it **Proposed → Accepted**:
- **Repo layout — monorepo.** `web/` (Next.js/TS) beside `app/` (Kotlin/Gradle) in this repo; sibling toolchains, **folder-scoped CI**, independent deploys (a monorepo, not a unified build). Rationale: three full-stack Claude Code devs (WF-01) + contract-first + a gate pack that already fails on drifted generated docs → a contract change is **atomic** (endpoint + regenerated `openapi.json` + regenerated TS client + screen in one PR), and the gate can enforce the client stays in sync. Avoids the SunnyEscape two-repo lockstep the owner has already lived. Reverses if a **FE-only** dev joins or FE deploy cadence must diverge (§4).
- **Auth — OIDC · stateless `api` · Next.js BFF session** (architecture Accepted). The `api` validates JWT (JWKS), **issues nothing** (ADR-009); the session is an **httpOnly cookie in the `web` BFF** (browser never holds a raw JWT); the **token is authN**, the **policy module + RLS** stay authZ (ADR-002/005, entrance is the only isolation key). **Provider deferred** to the first FE auth slice — **Keycloak self-hosted marked as the default** (EU residency for GDPR/PM-BOOK-007, OIDC/SAML, a path to broker Bulgarian e-ID / QES for ballots). Provider must satisfy EU residency + resident-friendly login + an e-ID/QES path.

**Why now** — S-41 froze the intake mapping contract, so every Gate-1 backend item is contract-complete; deciding topology now means the Gate-1 frontend can start the moment the owner gives the go-ahead. The OIDC architecture is provider-agnostic, so the deferred provider blocks neither the FE structure nor S-41b.

**Changed** — `docs/adr/ADR-011-frontend-topology.md` (Status → Accepted; §2.3 repo layout DECIDED; §5 resolved; **Decision · 2026-09-21** section). `docs/INDEX.md` (ADR-011 row Accepted; "Ten of twelve Accepted"; frontend status → **green light reached**). `CLAUDE.md` (decisions row 011 + count).

**Next** — **S-41b** (adopt optional mapped fields into `registry`, event-contract change) → **Track B** S-42 payment persistence → S-43 default interest. **Track C** (`web/` Gate-1 frontend) is now **unblocked** — scaffold on the owner's go-ahead; the auth **provider** is the one open pick, taken at the first FE auth slice.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, the H-03 handover, S-41 + this entry, `docs/adr/ADR-011-frontend-topology.md`.

---

## WEB-01 · 2026-09-22 · web/ scaffold + landing page (Етаж) — first frontend slice

**Did** — stood up the `web` deployable (ADR-011) as a monorepo sibling of `app/`, and implemented the **landing page** the owner designed in Claude Design.
- **Scaffold** — Next.js 15 (App Router) + React 19 + TypeScript, minimal by hand (no `create-next-app` boilerplate): `web/{package.json,next.config.mjs,tsconfig.json,.gitignore,README.md}` + `web/app/{layout.tsx,globals.css,page.tsx,HeroVideo.tsx}`. Independent toolchain, own build; `node_modules`/`.next` gitignored.
- **Import** — pulled `Етаж - лендинг.dc.html` (+ `support.js`) from the Claude Design project `70a25109-…` via the DesignSync MCP. **Discarded the design tool's `<x-dc>` runtime** (`support.js`) and re-implemented the page as idiomatic React: static markup as a server component, hover states as pure CSS, and the boomerang hero video as a `'use client'` component (`HeroVideo.tsx`) — a faithful port of the capture-to-canvas logic, with a graceful fallback to the looping `<video>` on a CORS-tainted canvas.
- **Faithful to the design**: Literata + IBM Plex Sans, the stone/green palette, the fixed nav, the hero "Нито един пропуснат срок.", the frosted "Какво правим?" panel, and the three ЗУЕС cards (Календар по ЗУЕС · Начисления и каса · Общи събрания). It is **static marketing** — no auth, no API.

**Verified** — `npm run build` clean (compiled, strict type-check, `/` prerendered static); rendered in the browser pane top-to-bottom, no console errors.

**Auth path getting here** — the DesignSync MCP needed a design-system authorization the CLI couldn't give: the machine had **two Claude Code installs** (npm-global 2.1.87 shadowing native), both far behind. Fixed by `claude install` (native → 2.1.278, user-space, no sudo); the owner then ran `/design-login` on the current build, which seeded the machine-level auth this desktop session reuses.

**Notes / TODO** — re-host the hero clip (currently the design tool's CDN URL) on a domuvai origin before launch; add the OpenAPI-generated client + a `web` build/lint CI job (the backend gate pack does not build `web`). The authenticated consoles (resident/manager/firm) and the API client come when a Gate-1 screen needs data.

**Next** — more Gate-1 frontend screens (the design project also has `Домоуправител` working/desktop screens + `Етажна собственост`), or resume backend **S-41b** (adopt optional mapped fields) / **S-42** payments. The auth **provider** pick lands at the first screen that needs login.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, `docs/adr/ADR-011-frontend-topology.md`, this entry, `web/README.md`.

---

## WEB-02 · 2026-09-22 · console shell + Портфейл dashboard — first manager screen

**Did** — began integrating the manager console (`Домоуправител - работни екрани`, a 7-screen 1440×1024 canvas). Built the shared **console shell** and the first screen, **01 Портфейл** (the firm-wide portfolio dashboard).
- **Route group `(console)/`** — `layout.tsx` renders the firm **sidebar** (`Sidebar.tsx`, a client component; active item from `usePathname`) + a main column each screen fills. `console.css` holds the shell, table and status-badge styles; tokens still live in `globals.css`.
- **`/portfolio`** — the risk-sorted table of all entrances: overdue ЗУЕС tasks, next deadline (with the legal article in IBM Plex Mono), collection %, arrears, repair fund, mandate expiry, and a Критичен/Внимание/Спокоен risk badge; summary line, filter chips and a totals row. The design's sample data is modeled as **typed rows** (`EntranceRow[]`) so wiring to the api's cross-entrance query later is a data-source swap.
- **Design language** — same stone/green as the landing (the `_ds/organic` bundle in the design project is unused scaffolding — a warm terracotta system for a different product; ignored). Added IBM Plex Mono to the font link for legal refs.
- **Real, not an artboard** — the fixed 1440×1024 frame became a responsive route that fills the viewport (sidebar fixed, table scrolls); the design-doc chrome (labels, palette swatches) was dropped.

**Verified** — `npm run build` clean (strict types, `/portfolio` prerendered static); rendered at 1440×900 in the browser pane, faithful to the design, no console errors.

**Scope** — static, no API/auth yet (same posture as the landing). The other 6 console screens (Вход · Начисления · Общо събрание · Задължения · Каса и фонд · Съответствие) follow one PR each, in that logical order; nav items are placeholders until each lands. There are also two `Домоуправител Про - desktop` files to cross-check for polished art per screen, and a mobile app spec (`Етажна собственост`, 390×844, resident+manager, light+dark).

**Next** — **02 Вход** (entrance detail — registry: units + book), then the money screens (03 Начисления, 05 Задължения, 06 Каса и фонд), then 04 Общо събрание and 07 Съответствие. Wire to the API once the OpenAPI client is generated.

**Read first next time** — `web/README.md`, `docs/adr/ADR-011-frontend-topology.md`, this entry, then the persisted design canvases if re-porting.

---

## WEB-03 · 2026-09-22 · console screen 02 — Вход (entrance detail) + two nav contexts

**Did** — built the second manager-console screen, **02 Вход** (a single entrance's detail), and refactored the shell to carry two navigation contexts.
- **Two sidebars.** The entrance view uses an entrance-scoped sidebar (Статутен календар · Обекти · Начисления · Каса и фонд · Общи събрания · Задължения · …), not the firm one. Refactored `(console)/layout.tsx` down to just the flex shell + `console.css`, and split the nav into **sibling nested layouts**: `(console)/(firm)/` (firm sidebar → `/portfolio`) and `(console)/entrance/` (entrance sidebar → `/entrance`). `Sidebar.tsx` → `(firm)/FirmSidebar.tsx`; portfolio moved under `(firm)/` (URL unchanged). A portfolio row now **links to** `/entrance`.
- **`/entrance`** — the design's Вход screen: breadcrumb + entrance pill, a tab bar, and a two-column body — left, the **statuten-kalendar timeline** (Просрочени / Този месец / Следващите 90 дни, each item citing its ЗУЕС article in IBM Plex Mono, with dot + status badge); right, the **Дело на входа / Сметки на входа / Следващо събрание** cards. Calendar items and card rows modeled as typed data.
- Same stone/green language; reused the console tokens and added timeline/card CSS.

**Verified** — `npm run build` clean (strict types, `/entrance` + `/portfolio` prerendered); both rendered at 1440×900 in the browser pane, faithful, no console errors; `/portfolio` intact after the restructure.

**Scope** — still static (no API/auth). `/entrance` is a single demo entrance; real routing becomes `/entrance/[id]` when wired. Entrance tab bar and non-active nav items are placeholders.

**Next** — **03 Начисления** (charges), then **05 Задължения**, **06 Каса и фонд**, **04 Общо събрание**, **07 Съответствие**, one PR each. Wire to the API once the OpenAPI client is generated.

**Read first next time** — `web/README.md`, this entry, then the persisted design canvas for the next screen.

---

## WEB-04 · 2026-09-22 · console screen 03 — Начисления (monthly charge run)

**Did** — built **03 Начисления** at `/entrance/charges` (entrance context; reuses the entrance sidebar, "Начисления" active).
- **The fee-engine screen**: a **basis card** (the ОС decision the run is built on — per-resident management/common/elevator rates, fund per 0,01% ideal parts, exemptions per чл. 51 ЗУЕС) + preparer/version; a **per-object charge table** (Обект · Собственик · Живущи · Освобождавания · Коеф. · Ид. части · Управл. · Общи ч. · Асанс. · Фонд · Общо) with exemption tags (Дете < 6 г. · Отсъства > 30 д. · Ръчна корекция) and green commercial coefficients; a **totals row**; and a **confirm/return action bar** (validation line + "Общо за начисляване €1.252,50" + Върни за корекция / Потвърди начисленията).
- Rows modeled as typed data — **exactly what the backend's `computeChargeRun` emits per object**, so wiring is a source swap. Added charge-table + action-bar CSS; wired the Начисления nav link.

**Verified** — `npm run build` clean (strict types; `/entrance/charges` prerendered); rendered at 1440×900, faithful, no console errors.

**Progress** — console screens: 01 Портфейл ✅, 02 Вход ✅, **03 Начисления ✅**. Remaining: 05 Задължения, 06 Каса и фонд, 04 Общо събрание, 07 Съответствие. Still static (no API/auth).

**Next** — **05 Задължения** (arrears — arrears ageing is built in the backend), then 06 Каса и фонд, 04 Общо събрание, 07 Съответствие.

**Read first next time** — `web/README.md`, this entry, then the persisted design canvas for the next screen.

---

## WEB-05 · 2026-09-22 · console screen 05 — Задължения (arrears / escalation ladder)

**Did** — built **05 Задължения** at `/debts` (firm context; firm sidebar, Задължения active).
- **The collections screen**: the **чл. 38 ЗУЕС → чл. 410 ГПК escalation ladder** (Покана → Нотариална покана → Решение на ОС → Заповед за изпълнение) with a colour legend; a debtor table **grouped by entrance** (each group its subtotal + a note, e.g. "няма решение на ОС за съдебно събиране"); per-debtor rows with a **4-segment ladder bar** + step label, oldest-debt age (colour-coded), interest, and the next action — including a red **"Блокира ескалацията:"** flag where a step is gated; a totals row with the step distribution (1·14  2·9  3·5  4·3).
- Modeled as typed groups/debtors — the shape the backend's arrears-ageing query emits. Added ladder + group-row CSS; wired the Задължения nav link (`/debts`).

**Verified** — `npm run build` clean (strict types; `/debts` prerendered); rendered at 1440×900, faithful, no console errors.

**Progress** — 01 Портфейл ✅, 02 Вход ✅, 03 Начисления ✅, **05 Задължения ✅**. Remaining: 06 Каса и фонд, 04 Общо събрание, 07 Съответствие.

**Next** — **06 Каса и фонд** (cash & repair fund), then 04 Общо събрание, 07 Съответствие.

**Read first next time** — `web/README.md`, this entry, then the persisted design canvas for the next screen.

---

## WEB-06 · 2026-09-22 · console screen 06 — Каса и фонд (cash & repair fund)

**Did** — built **06 Каса и фонд** at `/entrance/fund` (entrance context; Каса и фонд active).
- **The money-ledger screen**: two **account cards** — 501 operating cash and the ring-fenced 502 **repair fund** (badge "Отделна сметка · чл. 50 ЗУЕС") — each with balance / committed / available (available = balance − committed, shown green); a filter chip row; and a **double-entry journal** (Дата · Документ · Описание · Дебит сметка · Кредит сметка · Дебит · Кредит · Салдо 501) where **Дебит = Кредит per line** (ADR-006), 502 rows tinted green, an **off-balance commitment** row greyed, and a period оборотна ведомост totals row (€4.442,80 = €4.442,80).
- Accounts + journal modeled as typed data — the shape the backend's fund/postings query emits. Added account-card + journal CSS and a green badge variant; wired the Каса и фонд nav link.

**Verified** — `npm run build` clean (strict types; `/entrance/fund` prerendered); rendered at 1440×900, faithful, no console errors.

**Progress** — 01 Портфейл ✅, 02 Вход ✅, 03 Начисления ✅, 05 Задължения ✅, **06 Каса и фонд ✅**. Remaining: 04 Общо събрание, 07 Съответствие.

**Next** — **04 Общо събрание** (general assembly), then **07 Съответствие** (compliance) — the last two console screens.

**Read first next time** — `web/README.md`, this entry, then the persisted design canvas for the next screen.

---

## WEB-07 · 2026-09-22 · console screen 04 — Общо събрание (live general assembly)

**Did** — built **04 Общо събрание** at `/assembly` — the most complex screen, a **full-bleed live-session view with no sidebar** (its own `assembly.css`, standalone route outside the console groups).
- **Session-quorum banner** — сесия 1 пропаднала (51%) → сесия 2 открита (26% праг), citing чл. 15, ал. 2 ЗУЕС, with three session badges.
- **Three columns**: the **agenda** (6 points, т.3 being voted, статуси Приета/Гласува се сега/Предстои); the **item under vote** — a Кворум-сега gauge (58,412% with 26%/51% threshold markers), a **tally card** with a represented/all-ideal-parts toggle and За/Против/Въздържал се/Не гласували bars (the За bar carries the **67% majority marker**, чл. 17 ЗУЕС) resolving to "решението се приема"; the **live attendance** list (16 от 24, Лично/Пълномощно split, proxy holders tagged Пълн. · за ап. X, denominators to six decimals).
- Wired the entrance "Общи събрания" nav link and the "Отвори подготовката" button to `/assembly`. All modeled as typed data — the shape the `assembly` module's quorum/tally query emits (denominators explicit per ADR-008).

**Verified** — `npm run build` clean (strict types; `/assembly` prerendered); rendered at 1440×900, faithful, no console errors.

**Progress** — 01 Портфейл ✅, 02 Вход ✅, 03 Начисления ✅, 04 Общо събрание ✅, 05 Задължения ✅, 06 Каса и фонд ✅. **One left: 07 Съответствие.**

**Next** — **07 Съответствие** (compliance) — the final console screen. Then the console set is complete; remaining design work is the two `Домоуправител Про - desktop` polish files and the mobile app (`Етажна собственост`).

**Read first next time** — `web/README.md`, this entry, then the persisted design canvas for screen 07.

---

## WEB-08 · 2026-09-22 · console screen 07 — Съответствие (firm compliance) · console complete

**Did** — built **07 Съответствие** at `/compliance` (firm context; Съответствие на фирмата active, badge 2). **This completes the 7-screen manager console.**
- **The firm's regulatory standing**: three **status cards** — public-register entry (Рег. № ПД-0142, valid to 14.12.2026), professional-liability insurance (Полица 22-0034512, ЗАД „Армеец", €50.000/event, expiring in 39 days — red), and management contracts (62 действащи, mandates by чл. 19, ал. 5 ЗУЕС) — each with a countdown badge and an action link; plus a **filings & declarations table** (Документ · Период · Институция · Подаден · Вх. № · Основание · Статус) with statuses Приет / Изпратен / Просрочен (overdue row flagged) / До 23 дни, citing ЗСч, ЗУЕС, ОРЗД, ЗМИП. Wired the Съответствие nav link (`/compliance`).

**Verified** — `npm run build` clean (strict types; `/compliance` prerendered); rendered at 1440×900, faithful, no console errors.

**Console complete** — 01 Портфейл ✅, 02 Вход ✅, 03 Начисления ✅, 04 Общо събрание ✅, 05 Задължения ✅, 06 Каса и фонд ✅, **07 Съответствие ✅**. Routes: `/portfolio`, `/entrance`, `/entrance/charges`, `/entrance/fund`, `/debts`, `/compliance`, `/assembly` — plus the `/` landing. All static; every screen's data is typed for a later API-source swap.

**Remaining design work** (not the console): the two `Домоуправител Про - desktop` polish files (cross-check for refined art) and the **mobile app** (`Етажна собственост` — resident + manager, 390×844, light/dark). And the standing follow-ups: the OpenAPI-generated client + a `web` CI job; re-host the landing hero clip.

**Read first next time** — `web/README.md`, this entry.

---

## WEB-09 · 2026-09-23 · landing refresh — full marketing site (Етаж)

**Did** — the owner published a new `Етаж - лендинг.dc.html` (now a 9-section, interactive design, ~83KB). Re-imported via DesignSync and rebuilt `/` as a real Next.js page, discarding the `<x-dc>` runtime.
- **Sections**: scroll-aware sticky nav + mobile menu · boomerang hero (reused `HeroVideo`) + "Какво правим" panel · **Платформа** (intro + f01 Календар / f02 Начисления-каса / f03 Общи събрания feature blocks, each with a mock console card, + f04 Задължения / f05 Съответствие) · **За кого** (3 roles) · **Как започвате** (3 steps) · **Доверие** (derivation card + 6 principles) · **Цени** (3 plans) · **Фирмата** (bio + reg details) · **Въпроси** (FAQ accordion) · **Демо** (validated request form) · footer.
- **Interactivity as client components**: `LandingNav.tsx` (scroll → solid nav, scroll-spy active-section underline, `< 880px` hamburger + full-screen menu), `Faq.tsx` (one-open accordion), `DemoForm.tsx` (name/count/email/consent validation, phone regex, success state). Static content stays server-rendered in `page.tsx`. Added landing hover classes + `scroll-behavior`/`:focus-visible` to `globals.css`. `Вход`/`Започнете безплатно`/`Вход в системата` point at `/portfolio` (no login screen imported yet); the `./Етаж - вход.dc.html` login is a separate design file.

**Verified** — `npm run build` clean (strict types; `/` prerendered, ~6 kB). In-browser at 1280 + 375: nav goes solid on scroll with the correct active underline, the platform mock cards render, the demo form flags all required fields ("4 полета изискват внимание."), and the mobile menu opens. No console errors.

**Notes** — the hero clip is still the design tool's CDN URL (re-host before launch); a login screen (`Етаж - вход`) exists in the design project but isn't imported.

**Read first next time** — `web/README.md`, this entry.

---

## H-04 · 2026-09-23 · Handover — resume point before /compact

**RESUME HERE.** `main` is at the merge of this docs PR (H-04); before it, `main` was `ba09d92`. Everything below is merged (PRs #12–#22). This consolidates the session that ran from H-03 through the frontend build, so the next session continues without the conversation.

**What this session shipped (since H-03)**
- **S-41** (intake mapping API, PR #12): `POST …/fee-sheet/profile` + a confirmed `mapping` threaded through dry-run/record/commit. **Froze the intake contract → Gate 1 backend contract-complete** (the ADR-011 §3 green light).
- **ADR-011 Accepted** (PR #13): frontend = **monorepo** (`web/` here) + **OIDC · stateless api · Next.js BFF**; auth **provider deferred**, **Keycloak** marked as the default.
- **Frontend built** (PRs #14–#22) from the owner's Claude Design project (`70a25109-…`), all re-implemented as idiomatic React (the `<x-dc>` runtime always discarded), all with **typed mock data**:
  - **WEB-01** `web/` scaffold + landing; **WEB-02..08** the 7-screen manager console — Портфейл `/portfolio`, Вход `/entrance`, Начисления `/entrance/charges`, Каса и фонд `/entrance/fund`, Задължения `/debts`, Общо събрание `/assembly`, Съответствие `/compliance` (two nav contexts: firm `(firm)/` + entrance `entrance/`); **WEB-09** a full interactive landing refresh (scroll-aware nav + mobile menu, FAQ accordion, validated demo form).

**Evaluation — did the last steps disrupt the initial plan?** Core disciplines **intact**: contract-first ("no hand-written `fetch`" — the screens are static, no live calls), ADR-011 topology, the go-live gate (no billing wired), the branch→PR→CI-green→merge flow. **Two deviations, now recorded** (ADR-011 amendment 2026-09-23):
1. **Frontend built UI-first, ahead of the generated OpenAPI client** (§2.2's enforced boundary). Acceptable as scaffolding — every screen's data is typed, so wiring is a source-swap — but the mock data can **drift** until the client lands, and the console runs **ahead of the backend** (`assembly`/`compliance`/arrears-escalation screens exist before their modules). They are **design-validated shells**, not feature-complete.
2. **`web/` is ungated in CI** (`ci.yml` runs only the backend gate pack) — a broken web build merges silently.
Neither breaks correctness; both are convergence debt, scheduled below.

**Adapted plan / resume queue**
1. **Frontend convergence (restores ADR-011 §2.2)** — (a) add a **`web` CI job** (install · typecheck · `next build`) — immediate, cheap, closes the ungated gap; (b) **generate the OpenAPI TS client** and **replace the mock data screen by screen**, starting with the Gate-1 screens whose backend exists (`portfolio`/`entrance`/`charges`/`fund`/`debts`); (c) pick the **auth provider** (Keycloak) at the first login screen. Optional: import the **`Етаж - вход`** login design (a separate file in the project; `Вход`/`Започнете безплатно` currently point at `/portfolio`).
2. **Backend critical path for a *billable* pilot** (unchanged, still primary) — **S-41b** (adopt the optional mapped fields into `registry` — an externalized `ImportCommitted` event-contract change, its own PR + IT) → **Track B**: **S-42** payment persistence (wire S-37 to the ledger + endpoint) → **S-43** default interest (PM-DEBT-006). The **go-live gate** (ADR-012 §7) still governs: do not bill real money until a real sheet reproduces to the cent.
3. **Design backlog** — the two `Домоуправител Про - desktop` polish files (cross-check for refined art) and the **mobile app** (`Етажна собственост`, resident + manager, 390×844, light/dark).

**Open owner actions** — branch protection on `main`; `gh auth login` for the other two devs; ADR-004 / ADR-007 counsel; the pilot spreadsheet (now *validation*, not a blocker); the **auth provider** pick (Keycloak) when the first login screen is built.

**Operating lessons this session**
- **DesignSync (Claude Design MCP) auth**: it needs a design-system authorization the old CLI lacked. Root cause was a **dual Claude Code install** — npm-global (`/usr/local/bin`, 2.1.87) shadowing a native install, both far behind. Fix: `claude install` (native → 2.1.278, user-space, no sudo); the owner then ran `/design-login`, which seeds a **machine-level auth this desktop session reuses**. `claude mcp list` does not show `claude_design` — it is a desktop-app built-in, not a configured server.
- **Extract a screen from a design canvas** with the Python helper (load the `get_file` JSON `content`, balance `<div>` tags from `data-screen-label="…"`); the console file is one 214KB multi-artboard canvas. The `.dc.html` files are static inline-styled HTML (no `{{ }}` bindings) except the landing, which carries an `<x-dc>` runtime + logic.
- **Browser-pane scroll checks**: nested `element.offsetTop` is relative to the offsetParent (not the document) — use `getBoundingClientRect().top + scrollY` for absolute; and `html{scroll-behavior:smooth}` makes `scrollTo` animate, so force `scrollBehavior='auto'` before reading positions.
- **Gate/CI**: the backend gate pack still runs locally with `GRADLE_USER_HOME=$HOME/.gradle`; `web/` has **no** CI yet (see queue #1). A dev server may still be **running at `localhost:3000`** (started for the "run on localhost" request) — it survives across turns; stop with `pkill -f "next dev"`.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md` (frontend status), this entry, `docs/adr/ADR-011-frontend-topology.md` (Amendment 2026-09-23), `web/README.md`, then `git log --oneline -20`.

---

## WEB-10 · 2026-09-26 · `web` CI gate — a broken web build can no longer merge

**Did** — closed the gap the H-04 evaluation found (ADR-011 amendment 2026-09-23: "`web/` is ungated"). New workflow `.github/workflows/web.yml`: Node 22 · `npm ci` · `npm run build` (the build runs the strict TypeScript check). **Folder-scoped** per ADR-011 — it triggers only on changes to `web/**` or the workflow itself; the backend `gates` workflow is unchanged. `web/README.md` gained a CI section; `docs/INDEX.md` frontend status updated.

**Verified** — simulated CI from a clean copy of the tracked `web/` files (`git archive HEAD web`): `npm ci` + `npm run build` → exit 0, 11 static routes. Then injected a type error (`const probe: number = 'not a number'`) → `Failed to compile. Type error: Type 'string' is not assignable to type 'number'.` → exit 1. The gate fails a broken build.

**Notes** — path-filtered, so **do not make `web / build` a required status check** when branch protection is set: a skipped workflow never reports, and a required check that never reports blocks every non-web PR. No lint step (ESLint is not configured in `web/`). When the generated TS client lands, add `docs/api/openapi.json` to the workflow's `paths`.

**Found while planning this step** — `docs/api/openapi.json` does **not** describe the running API. Only **9 of 27** catalogued operations match a controller; **15** running endpoints are uncatalogued (incl. `GET /entrances`, `GET …/fund-accounts`, `GET /units/{id}/arrears` — the reads the console screens need); **18** catalogued operations do not run (design-ahead modules, plus renames such as `/fund` vs `/fund-accounts`). The running paths carry `/api/<module>`, and the JSON is `camelCase` where the spec says `snake_case`. Nothing checks spec ⇔ code — gate 6/9 validates OpenAPI syntax and rule IDs only. A TS client generated from this spec would compile and then fail at runtime.

**Next** — before the TS client: **ADR-013 — the OpenAPI spec is generated from the code** (owner decision), then the slice that implements it.

---

## ADR-013 · 2026-09-26 · Accepted — the OpenAPI contract is generated from the running code

**Did** — drafted ADR-013 from the drift WEB-10 measured (9 of 27 catalogued operations match a running endpoint; `camelCase` code vs `snake_case` spec; A9's `Idempotency-Key` / `rule_id` conventions implemented nowhere; no spec ⇔ code check). It proposes: springdoc generates `docs/api/openapi.json` from the controllers; the hand catalogue becomes the rule-traceability map (a running endpoint without rule citations fails the gate; unbuilt operations stay, marked `planned`); the wire format is what runs (`/api/<module>/…`, `camelCase`). §3 weighs A–D; **B (a method + path conformance gate) vs C (generated from the code)** is the decision — B cannot see bodies, which is where a generated client breaks.

**Status** — **Accepted** by the owner the same day (option C).

**Next** — the implementing slice **API-01** (springdoc + a spec test + the catalogue re-keyed to real paths + the `x-rules` merge), then **WEB-11** (generated TS client; first screen `/entrance/fund`, whose backend exists).

---

## API-01 · 2026-09-26 · the published contract is generated from the running code (ADR-013)

**Did** — `docs/api/openapi.json` is now generated from the controllers. `OpenApiContractTest` (a `@WebMvcTest`: every controller, collaborators mocked — no database, no Docker) asks springdoc for the spec and writes `app/build/openapi/api-docs.json`. springdoc is a **test-only** dependency (2.8.8, the last release on Spring Boot 3.4), so the running app serves no `/v3/api-docs`. `tools/build_openapi.py` (gate 6/9) is rebuilt as the **rule map + publisher**: `RUNNING` maps each of the 24 running operations to the rules it serves (merged as `x-rules`), `PLANNED` keeps the 17 A9 operations not built yet, and deterministic `operationId`s and module tags replace springdoc's. The 10 creation endpoints now declare `@ResponseStatus(CREATED)` instead of returning `ResponseEntity.status(CREATED)` — same behaviour, and the spec documents `201` instead of claiming `200`.

**Rules covered** — none implemented; 27 rules cited by the 24 running operations. Citations were re-derived from the implementation (`TRACEABILITY.md`, rule-named tests, controller KDoc): A9's are kept where the code serves them and replaced where it does not — commit/revert `PM-BOOK-007` (access logging, not built) → `PM-DOC-001` / `PM-ORG-001/002`; the book's and statement's caller filtering (`PM-BOOK-006`, `PM-SEC-002`) arrives with the authorization module (ADR-002).

**Tests added** — `OpenApiContractTest`. Guards proved against real failures, each exit 1: uncatalogued endpoint · stale entry · unknown rule (running and planned) · operation citing no rule · missing raw spec; clean → exit 0. Regenerated from scratch twice → byte-identical. 146 app tests pass (24 Docker ITs skip locally; they run in CI).

**Decisions** — ADR-013, implemented. The A9 hand schemas are retired — the code's types are the schemas now. A9's prose conventions (`Idempotency-Key`, the `rule_id` problem body, bearer security, the `/v1` server) left the published spec: none of them runs yet (ADR-013 §2.5, §6).

**What the generated contract now shows** — error bodies are `{"error": "…"}`, not RFC 9457 problems; no auth; `/api/<module>/…` paths; `camelCase` JSON; `required` follows Kotlin nullability.

**Open** — **WEB-11**: generate the TS client from this spec (and add `docs/api/openapi.json` to the `web` workflow's `paths`), then wire **`/entrance/charges`** first — its per-unit lines, persons, stream amounts and totals match `ChargeRunResponse`, and preview + issue back its confirm bar. Not `/entrance/fund` (as the ADR-013 entry said): the API has its account records only — no balances, commitments or journal yet. A new controller collaborator must be mocked in `OpenApiContractTest` — its context fails loudly otherwise.

**Read first next time** — ADR-013, the `tools/build_openapi.py` docstring, this entry.

---

## WEB-11 · 2026-09-27 · the generated client + `/entrance/charges` live

**Did** — restored ADR-011 §2.2's enforced boundary. `openapi-typescript` generates `web/lib/api/schema.d.ts` from `docs/api/openapi.json` (`npm run gen:api`); `web/lib/api/client.ts` is a typed `openapi-fetch` client, `server-only` (the browser never calls `api`; Next.js is the thin BFF), at `API_URL`. The `web` workflow now also triggers on the contract and fails when the generated client differs from it. **`/entrance/charges` is live**: one server-side aggregation — the engine's `POST …/charge-runs/preview` joined to `GET …/units` (ideal parts) and `GET …/owners` (names, co-owners joined) — with `?period=` (default: this month, Europe/Sofia) and `?entrance=` (default: the first registered). Each amount's hover shows the engine's derivation. Clear states for backend down, no entrance, and an API refusal (its own message).

**Verified** — no database on this machine, so against a stub API **typed to the generated contract** (`satisfies`; `tsc` clean): the page posted exactly `StoredChargeRunRequest`; 6 units rendered with the joined owners and ideal parts, per-stream sums and totals (€789,00); derivations on hover; a 400 rendered the API's reason; with the backend down it says so. Clean-copy `npm ci` + `gen:api` (byte-identical) + `next build` pass; `/entrance/charges` is dynamic (no build-time fetch). **The boundary, proved:** renaming `chargeablePersons` in the spec fails the drift check (exit 1), and after regeneration fails `next build` at the exact use (`page.tsx`). Not yet verified against the real backend — that needs a local Postgres or Docker.

**Decisions** — generator: `openapi-typescript` + `openapi-fetch` (types only, a 6 kB typed fetch; ADR-013 §6 deferred it here). The tariff basis is a **labelled demo** (the design's GA decision): the API takes the tariff with the request, and the assembly module does not serve decisions yet — confirming is disabled; never bill from it (ADR-012 §7).

**Design vs domain gaps (not invented, shown as `—`)** — the design's **elevator** column has no stream (PM-FEE-001 defines three: management, maintenance, repair fund); the **exemptions** tags and the per-unit **coefficient** have no response field. And the design's sample shop coefficients (×2,00 / ×1,50) are below PM-FEE-010's 3–5× range — a design-sample issue, not wired.

**Open** — a local runtime (Postgres or Colima) to verify against the real backend and seed a demo entrance; then the next screens (`/debts` via per-unit arrears, `/portfolio` via the entrance list). A follow-up for the API: `TariffLineRequest.stream`/`key` are plain strings — typing them as the `CostStream`/`AllocationKey` enums would put the allowed values into the generated client.

**Read first next time** — `web/README.md` (The API client), this entry, ADR-013.

---

## S-41b · 2026-09-27 · intake commit adopts the mapped optional fields (ADR-012)

**Did** — a committed import now adopts more than designation and ideal parts. `FeeSheet` keeps the optional columns a mapping names (as written; a blank cell is absent) and refuses what the registry could not hold: an area with more than two decimals or not positive, a child count that is not whole, an owner cell carrying nine-plus digits (an ЕГН/ЛНЧ/ЕИК — PM-BOOK-011). `ImportCommitted` carries each unit's area, occupants, children and owner name plus the import's legal date; `registry` adopts, per unit and stamped with `import_id`: the area, the persons charged as anonymous household members plus the children on top, flagged (PM-FEE-005/008), and a name-only party holding an `OWN` title, share 1, from the legal date (PM-ORG-011). **Absences, animals and business use are not adopted** — the commit returns them as `manualEntries` naming the rule (PM-FEE-007 · PM-BOOK-005 · PM-ORG-009). Owner decisions D1–D3 on #31 (2026-09-27).

**Revert fixed before it broke** — no child table cascades from `registry.unit`, so the first import with household or owner rows would have made `revert` fail on the foreign keys. Migration `V202609271200__import_provenance.sql` stamps `household_member`, `party` and `title` with `import_id`; revert drops them first, then the units. A record added later and pointing at an imported unit is not the import's: the delete then fails and rolls back, so nothing is lost — but the refusal is not yet reported back to the caller (the listener is asynchronous).

**Rules covered** — PM-BOOK-002 · PM-FEE-005 · PM-FEE-008 · PM-ORG-011 · PM-BOOK-011 · PM-FEE-007 · PM-BOOK-005.

**Tests added** — `ImportServiceTest`: `PM-BOOK-002 a commit carries…`, `PM-FEE-007 PM-BOOK-005 an absence or animal count is returned…`. `ImportAdoptionTest` (new, registry, mocks): `PM-FEE-008 PM-FEE-005 the persons charged…`, `PM-ORG-011 PM-BOOK-011 an adopted owner is a name-only party…`, a bare unit adopts alone, revert order. `FeeSheetTest`: optional columns read as written; unstorable area/children refused; `PM-BOOK-011 an owner cell carrying an identity number…`. `ImportCommitPersistenceIT`: `PM-BOOK-002 an import's household and owner are adopted stamped, and revert drops them before the units` (Docker — CI).

**Contract** — `CommitResult` gained `manualEntries`; the generated spec and the web client (`web/lib/api/schema.d.ts`) were regenerated in this PR, as ADR-011 §2.2 now requires.

**Decisions** — none new (ADR-012; D1–D3 on #31).

**Finding, for `money` (S-42's owner, #29)** — `ChargeRunService.kt:58` feeds registry's `separateEntrance` (PM-ORG-009) to the engine as `businessUse`, which applies PM-FEE-010's multiplier meant for business use *through the common parts*. One flag stands for two legal cases; a sheet's business-use column stays unadopted until it is split.

**Open** — report a refused revert back to the caller; merge / re-import into a populated entrance; the business-use flag split (above).

**Read first next time** — this entry, #31, `app/src/main/kotlin/zues/app/registry/RegistryService.kt` (`adoptImport` / `revertImport`).

---

## S-42 · 2026-09-27 · payment persistence — record a payment, oldest debt first (PM-DEBT-008)

**Did**
- `money` records payments: `POST /api/money/entrances/{e}/payments` (requires `Idempotency-Key`) and `GET …/payments/{id}`.
- A payment is allocated by S-37's `PaymentAllocation`: oldest debt first, or the payer's designated debt first. It is posted as one balanced journal, with every leg dated on the payment day:
  - `BANK:<purpose>` or `CASH` is debited.
  - One `RECEIVABLE` credit per settled debt, each naming its debt in the new `posting.settles_value_date`.
  - Any overpayment goes to a unit-scoped `ADVANCE`.
- The payment row stores the rule applied, the designation, and a `basis` (rule · remainder rule · amount · date · the open debts it saw) with `basis_hash`, `law_version` and `engine_version` (ADR-006 l.40, ADR-001 amendment).
- `ArrearsService` now reads **as of** its date: it counts only postings dated on or before `asOf` and bands a credit with the debt it settled. The statement is unchanged.
- `PaymentPosted` is published in the write's transaction.
- New migration `V202609281000__money_payment.sql`.
- Claim: stpdimitrov/domuvai#29.

**Rules covered** — PM-DEBT-008 (MUST): now implemented, where it was test-only since S-37. PM-DEBT-001: its as-of reading is corrected. Traceability 34/233 covered · 35 referenced; TESTPLAN 200 remaining.

**Tests added**
- `PaymentLedgerTest`: 5, pure.
- `PaymentWebTest`: 7. These are HTTP mapping only, so they are deliberately not rule-named.
- `PaymentPersistenceIT`: 9, on Postgres 16. Covers oldest-first plus as-of arrears; designated plus explainable read-back plus stored basis/hash/versions; a designation not owed → 400; retry recorded once; overpayment → advance; no reach past the payment date; backdated → 409 and future → 400; the settles CHECK and payment immutability proved against real violations; a foreign unit → 404.
- `ArrearsServiceTest`: +1 (PM-DEBT-001 as-of).
- Mutations proved each guard fires: credit dating (4 tests failed), the as-of filter (2), the backdating guard (1).

**Decisions (owner, 2026-09-27)**
- **D1:** `receivedInto` is OPERATING | REPAIR_RENEWAL (a registered account of the entrance) | CASH.
- **D2:** an overpayment goes to `ADVANCE`.
- **C1:** a journal has one date; a credit names the debt it settled; a payment reaches only debts raised on or before its date. This **reverses S-37's note** ("credits dated to each settled debt"), which a fresh review showed breaks as-of reads and makes intermediate-date ledgers unbalanced.
- **C2:** the allocation's basis is stored.
- **Backdating:** a payment dated before one already recorded for the unit is refused (409). Reversal plus re-record comes later.

**Found**
- **Two fresh-context reviews** (a subagent given only the diff and the rule texts) found the credit-dating flaw above, a false `DESIGNATED` when the designation had no effect, the missing ADR-006 basis and ADR-001 versions, the out-of-order bug, a missing future-date check, and a 500 on a same-key race. All are fixed.
- **Counsel question, before S-43.** ЗЗД чл. 76 ал. 1 puts the *most onerous* debt before the oldest and splits simultaneous debts pro rata. Ал. 2 settles costs → interest → principal. The catalogue reduces PM-DEBT-008 to oldest-first, and the code follows the catalogue. Once S-43 adds interest, ал. 2 decides whether a payment covers interest before principal. That is a catalogue/ADR question, not a code one.
- **PM-PMC-008 has no ledger guard.** No DB check stops one journal mixing entrances, although DEVBRIEF promises one. This slice cannot create a mixed journal. Follow-up **S-42a**.
- **`DEVBRIEF.md` is stale:** 13 deployables, and §11 points at the retired vault.

**Open**
- Show or net `ADVANCE` on the statement and arrears. Apply advances to later charges.
- Reverse a payment.
- Pre-2026 BGN payments (PM-FEE-016 needs a law constant for the euro-adoption date). Charge runs share this gap.
- Reject fractional money in JSON. Jackson's `ACCEPT_FLOAT_AS_INT` truncates `150.75` to `150`.
- A two-thread lock IT.
- A light registry membership API. `Units.forEntrance` is heavy for a yes/no.
- A debt is keyed by value date, so two runs on one date merge.
- `PaymentAllocation` lives in `app`, so `ENGINE_VERSION` (`:law`) does not version it.
- Remove the stale `netOutstanding`.
- List a unit's payments.
- S-42a.
- **S-43** (default interest, PM-DEBT-006) is next on the money track, after the counsel answer.

**Operating lessons**
- **Local JDK:** Gradle 8.14 cannot start on JDK 27. Use `brew install openjdk@21` (no sudo; keg-only) and `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- **Docker ITs:** they skip silently unless Testcontainers can talk to Docker Desktop 29. Its minimum API is 1.40, and the BOM's Testcontainers asks for 1.32. Run with `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock JAVA_TOOL_OPTIONS=-Dapi.version=1.44`, then **check the XML for `skipped="0"`**, since a skip reports green.
- **Gate 6** needs `tools/requirements.txt`. Locally: `python3 -m venv .venv && .venv/bin/pip install -r tools/requirements.txt`, and put `.venv/bin` first on `PATH`.

**Read first next time** — this entry, `app/src/main/kotlin/zues/app/money/Payments.kt`, `Postings.kt` (`Ledger.forPayment`, `openDebts`), `docs/adr/ADR-006-money-and-numbers.md`.

---

## WF-02 · 2026-09-28 · lanes are claimed, not assigned (`tools/lanes.py`)

**Why** — the first real parallel run (S-41b and S-42, 2026-09-27) worked where the protocol was explicit — different modules, no code collision — and failed in two places it was silent. **GitHub ignores the `merge=union` driver**, so the second PR to merge showed a `SESSIONLOG.md` conflict; and **both slices wrote migration `V202609271200`**, which no text merge flags, the local gates cannot see (the ITs skip without Docker) and GitHub runs no CI on while a PR conflicts — Flyway would not have started. Fixed on #33 by merging `main` into it and restamping its migration. The owner also asked that lanes not be hardcoded to people.

**Did**
- **`tools/lanes.py`** — the live lane map, read from GitHub: a lane (a module, or `web`) is held while an open `lane:<lane>` issue has an assignee; free lanes show their next `TESTPLAN.md` slice; a PR working in a lane nobody holds, or someone else holds, is flagged. `check <lane>` and `claim <lane> --title … --body-file <contract>` (claims a free lane; two claims at once — the lower issue number holds). Unassigned `lane:` issues are findings, not claims. `git config zues.lane` is a preference, never a reservation. The module list is imported from `testplan.py`, never retyped; the generated web client counts as shared.
- **Schema gate** — fails on a duplicate or malformed migration version (V1, or a real `yyyyMMddHHmm` UTC minute). Proved against the replayed S-42 collision, month 13, `V2`, and bad name characters — each exit 1; clean, exit 0.
- **Rules** — `CLAUDE.md` (boot step 8, the claim rule, the real-UTC-minute stamp, the GitHub-union rebase, findings as unassigned issues, pushing to another's branch only by agreement); the `zues-slice` skill's claim and close steps; `WORKING.md` (the A/B/C developer table replaced by the claim protocol). #29 labelled `lane:money`.

**Open** — at pilot, with a long-lived database: a migration merged after a later-stamped one is applied out of order — decide Flyway `outOfOrder` versus restamping on rebase. The remaining owner actions still apply (`gh auth login` per developer, now also for `tools/lanes.py`; branch protection).

**Read first next time** — `CLAUDE.md` (*Working in parallel*), then run `python3 tools/lanes.py`.

---

## S-G1-03a · 2026-09-28 · building & entrances, voting weight, occupancy ranges (registry)

**Did** — the first slice claimed through `tools/lanes.py` (#35, `lane:registry`). TESTPLAN's S-G1-03 was split: only three of its ten rules are buildable in `registry` today (the split and the blocked rules are on #35). **PM-ORG-001:** an entrance now joins an existing building (`condominiumId`) instead of always founding one — one or the other, never both; each entrance stays its own isolation unit with its own 100% (ADR-005, PM-ORG-002), and a label repeated in one building is a 409, not a 500. **PM-ORG-004:** `OwnershipService.votingWeights(entrance, on)` — per title holder and role, Σ ideal parts × title share over the titles in force that day, never a count of units (co-owners split by share, PM-ORG-005); who votes stays assembly's (owner decision D2). **PM-BOOK-008:** already built (S-19/S-25) — proved by name: a short stay and a move-out change the headcount only inside their half-open ranges.

**Rules covered** — PM-ORG-001 · PM-ORG-004 · PM-BOOK-008 (named-test coverage 34 → 37 of 233).

**Tests added** — `EntranceRegistrationTest` (new): `PM-ORG-001 a second entrance joins its building…`, a first entrance founds it, both-or-neither refused, unknown building 404. `OwnershipServiceTest`: `PM-ORG-004 a 2-unit owner with 12% outvotes 5 owners holding 10%` (the TESTPLAN acceptance), `PM-ORG-004 PM-ORG-005 a co-owned unit splits its weight by share…`. `UnitsAdapterTest`: `PM-BOOK-008 a short stay and a move-out…`. `RegistryWebTest`: the building id passes through. `RegistryUnitsPersistenceIT`: `PM-ORG-001 a building with three entrances keeps three independent entrances, each with its own 100%` + the 409 on a repeated label (Docker — CI).

**Contract** — `RegisterEntranceRequest.address` is optional and `condominiumId` new; registry endpoints document their 409. Spec and web client regenerated.

**Decisions** — owner D1/D2 on #35 (2026-09-28). The banned-words gate caught `building` as a test identifier (ADR-005) — renamed to `condominiumId`.

**Open** — S-G1-03b book declarations (PM-BOOK-003 · PM-BOOK-004): the 15-day window is a legal number, so `law/…/Constants.kt` changes — it is shared (WORKING.md), but `tools/lanes.py` maps `law/` to the `law` lane and will flag it; make `Constants.kt` shared in `lanes.py` first. S-G1-03c (PM-BOOK-010) needs the owner's retention windows. `votingWeights` has no HTTP read yet — add one when the assembly screen needs it.

**Read first next time** — #35, this entry, `app/src/main/kotlin/zues/app/registry/OwnershipService.kt`.

---

## S-G1-03b · 2026-09-28 · book declarations — the 15-day window and the versioned template (registry)

**Did** — declarations for entry in the book (#37, `lane:registry`). `POST …/book/declarations` files one for a unit by a party; the **system** dates it (the Sofia day, PM-SYS-004 — the filing date decides timeliness) and records the **template version in force** that day (PM-BOOK-004). `GET …/book/declarations/overdue?on=` lists owners and users whose title is in force and whose deadline has passed with none of theirs filed between acquisition and `on` — a later filing never rewrites what was owed on `on`. The deadline is `BOOK_DECLARATION_DAYS` (15, чл. 7 ал. 3 ЗУЕС, confirmed) through `statutoryDeadline`, so it rolls off weekends and holidays (PM-SYS-005). Two dated constants in `:law`: `BOOK_DECLARATION_DAYS` and `BOOK_DECLARATION_TEMPLATE` — the latter a placeholder, **unconfirmed**, until the owner supplies the minister's current order (чл. 7 ал. 7). New table `registry.book_declaration` (`V202609281808`). The A9 planned declarations operation is now running (16 planned left). `tools/lanes.py` treats `law/…/Constants.kt` as shared, as WORKING.md already said.

**Rules covered** — PM-BOOK-003 · PM-BOOK-004 (named-test coverage 37 → 39 of 233).

**Tests added** — `DeclarationServiceTest` (new): `PM-BOOK-003 day 16 without a declaration is overdue when day 15 is a working day`, `PM-BOOK-003 a deadline that lands on a weekend rolls to the next working day`, `PM-BOOK-003 only a declaration filed between acquisition and the date asked clears it`, `PM-BOOK-004 a declaration records the system's filing day and the template in force`, `PM-SYS-004 a filing just after midnight in Sofia is dated the Sofia day`, bad kind / foreign unit. `DeclarationWebTest` (new): 201 with date and version, overdue defaults to today, 400s. `BookPersistenceIT`: `PM-BOOK-003 PM-BOOK-004 a filed declaration persists…` (Docker — CI).

**Decisions** — owner D1–D3 on #37 (2026-09-28): an overdue read now, a task once a task module exists (PM-SYS-011); the acquisition trigger only — change detection later; the template version is a placeholder until the order is known. The TESTPLAN's "day 16 = overdue" holds only when day 15 is a working day — both cases tested.

**Found** — the rest of `registry` dates "today" in UTC, not Sofia (PM-SYS-004): the absence filing date decides PM-FEE-007 timeliness. Filed as an unassigned `lane:registry` finding (the lane map counts it).

**Open** — S-G1-03c (PM-BOOK-010, retention) needs the owner's retention windows; PM-BOOK-009 self-service waits for the auth provider; the template text and the minister's order for PM-BOOK-004.

**Read first next time** — #37, this entry, `app/src/main/kotlin/zues/app/registry/DeclarationService.kt`.

---

## H-05 · 2026-09-28 · Handover — resume point before /compact

**RESUME HERE.** `main` is at the merge of this docs PR; before it, `main` was `ea7f538` (S-G1-03b). Everything below is merged. No PR is open; no lane is claimed.

**What shipped since H-04 (2026-09-23)**
- **Contract & frontend convergence — ADR-011 §2.2 now holds.** WEB-10 (#24) gates `web/` in CI. ADR-013 (#25) + API-01 (#26): `docs/api/openapi.json` is generated from the controllers (springdoc, test-only); `tools/build_openapi.py` is the rule map (`RUNNING` / `PLANNED`); code and contract cannot disagree. WEB-11 (#30): the generated TS client (`web/lib/api/`, server-only) with a CI drift check; `/entrance/charges` live on the engine (a labelled demo basis).
- **Gate-1 backend.** S-41b (#32): an import commit adopts area, household and name-only owners; absences, animals and business use come back as `manualEntries`; revert fixed via `import_id` stamps. S-42 (#33, Steliyan): payment persistence (PM-DEBT-008). S-G1-03a (#36): buildings with several entrances (PM-ORG-001), voting weights (PM-ORG-004), occupancy ranges proved (PM-BOOK-008). S-G1-03b (#39): book declarations (PM-BOOK-003/004).
- **Process — WF-02 (#34).** Lanes are claimed, not assigned: `python3 tools/lanes.py` (map · `check` · `claim`); a `lane:<lane>` issue with an assignee is a claim, without one a finding. The schema gate fails on a duplicate migration version. Steliyan's #27/#28: boot from the repo; the log's *last* entry is the resume point.

**Where the plan stands.** The five steps of H-04's evaluation are done (web CI → ADR-013 → contract from code → generated client → the backend path). Work now runs by lane:
- **registry (us):** **#38 next** — `registry` dates "today" in UTC, not the Sofia day (PM-SYS-004); the absence filing date behind PM-FEE-007 is the one that matters. ~45 min. Then S-G1-03c (PM-BOOK-010) once the owner gives retention windows. Blocked: PM-BOOK-006/007/009 (the auth provider), PM-ORG-006/007 (closed complex: money + evidence + compliance).
- **money (Steliyan; free to claim):** S-42a ledger guard (PM-PMC-008) · a fund balance read (→ `/entrance/fund` live) · arrears per entrance (→ `/debts` live) · payments completion · S-43 after counsel.
- **web:** wire `/debts` and `/entrance/fund` once those reads exist.
- **Numbers:** 39 of 233 rules have a named test; 28 operations run, 16 are planned.

**Owner inputs outstanding**
1. Retention windows per field group — unblocks PM-BOOK-010.
2. The minister's current order for the declaration template (чл. 7 ал. 7) — replaces the placeholder `ministry-template-1`.
3. Counsel: ЗЗД чл. 76 payment order (blocks S-43); ADR-004 / ADR-007 as before.
4. The auth provider (Keycloak marked) — unblocks login and PM-BOOK-006/007/009.
5. The business-multiplier split (`ChargeRunService.kt:58` feeds `separateEntrance` as `businessUse`) — decide, then registry adds the field and money maps it.
Also: branch protection (do not require `web / build` — it is path-filtered); `gh auth login` for every developer (the lane map needs it); optionally a local Docker (Colima) so ITs and a live backend run here.

**Operating lessons**
- GitHub ignores `merge=union`: the PR that merges second rebases locally, and its log entry lands last.
- Migrations are stamped with the real UTC minute (`date -u +%Y%m%d%H%M`); S-41b and S-42 both wrote `…271200`, which Flyway refuses — the gate now catches it.
- No Docker here: ITs skip locally and run in CI. The desktop app's Auto-fix wakes a session on a CI failure, not on success — look at a PR again after ~5 min.
- Auto mode blocks pushing to a teammate's branch; the owner's personal `.claude/settings.local.json` allows `git push origin *:slice/*`. Use it only by agreement: merge `main` in, never force.
- Banned identifiers bite in tests too (`building` → `condominiumId`, ADR-005). A legal "today" is `toSofiaDate(clock.instant())`, never `LocalDate.now(clock)`.
- A `next dev` server may still run at `localhost:3000` — stop it with `pkill -f "next dev"`.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry; then `python3 tools/lanes.py` and `gh pr list`; for the next slice, #38.

---

## F-38 · 2026-09-28 · registry "today" is the Sofia day (PM-SYS-004)

**Did** — fixed finding #38 (`lane:registry`, found in S-G1-03b). Every "today" `registry` stamps or falls back to is now the Europe/Sofia calendar day — `LocalDate.parse(toSofiaDate(clock.instant()))` — not `LocalDate.now(clock)` on the app's UTC clock, which gave yesterday from 00:00 to about 03:00 Sofia time. Five sites: an absence's `filedOn` (PM-FEE-007 judges a filing's timeliness on it); the default `validFrom` of a household member, an animal and a title; the book's default date. No `LocalDate.now` is left anywhere in the code.

**Rules covered** — PM-SYS-004 (already covered; named-test coverage stays 39 of 233).

**Tests added** — `LegalTodayTest` (new; the clock at 00:30 Sofia, 21:30 UTC the evening before): `PM-SYS-004 an absence filed just after midnight in Sofia is dated the Sofia day, not the UTC one`, `PM-SYS-004 a resident or an animal given no start date starts on the Sofia day`, `PM-SYS-004 a title given no start date starts on the Sofia day`. `BookWebTest`: `PM-SYS-004 with no date given the book is read as of today in Sofia`. `DeclarationWebTest`: its clock moved to 00:30 Sofia, so `PM-SYS-004 GET overdue defaults to today in Sofia…` now proves what it names. All four new tests failed on the old code; reverting the declarations default made the fifth fail.

**Decisions** — none; the fix is the one #38 named.

**Open** — nothing stops a new `LocalDate.now(clock)` from landing: a check in `tools/` would keep it out of every lane (cross-lane, so not done here). Registry next: S-G1-03c (PM-BOOK-010) once the owner gives retention windows; otherwise as H-05.

**Read first next time** — H-05, this entry; then `python3 tools/lanes.py`.

---

## WF-03 · 2026-09-28 · a gate keeps a legal "today" on the Sofia day (PM-SYS-004)

**Did** — gate 10/10 `legal today` (`tools/check_legal_today.py`): main code reads a calendar date off a clock only through the kernel's `toSofiaDate`. It fails on `LocalDate` / `LocalDateTime` / `ZonedDateTime` / `OffsetDateTime` / `YearMonth` / `Year` / `MonthDay` `.now(…)` and `LocalDate` / `LocalDateTime` `.ofInstant(…)` in `*/src/main`; comments and KDoc are skipped, and a deliberate use takes a trailing `// allow-clock-date` that says why. Tests are not checked — they pin their own dates. `CLAUDE.md` names the idiom under Hard constraints and the check under Exit checks. The gate pack's header no longer types a stale count ("six checks").

**Rules covered** — PM-SYS-004 (a guard, not a test; named-test coverage stays 39 of 233).

**Tests added** — none in the suite. The guard was run against failures: on `main` before #41 it flags exactly the five #38 sites; on a probe file with three violations, a comment, KDoc, an escape and the right idiom, it flags the three and nothing else. On today's `main` it passes.

**Decisions** — none; the owner asked for the guard after #38 (2026-09-28).

**Open** — registry: S-G1-03c (PM-BOOK-010) waits for the owner's retention windows; otherwise as H-05.

**Read first next time** — H-05, F-38, this entry; then `python3 tools/lanes.py`.

---

## S-G1-03c · 2026-09-28 · book retention — move-out, then anonymised three months on (registry)

**Did** — the book can record that a stay ended, and drops who it was three months later (#43, `lane:registry`). Found while planning: nothing could end a stay — occupants and animals were added but never moved out, so a moved-out occupant was counted in every charge, and no retention window could ever start. `POST …/household/{memberId}/end` and `…/animals/{animalId}/end` close the range on the declared day (PM-BOOK-008); the fee engine stops counting from that day. `POST …/book/retention` anonymises what is past its window, as of today in Sofia, taking no date from the caller: a household row's link to a named person, an animal's veterinary passport number. Unit, dates, child flag and species stay, so a past charge still reproduces (PM-FEE-014). The windows are `:law` constants, one per field group — `BOOK_RETENTION_HOUSEHOLD_MONTHS`, `BOOK_RETENTION_ANIMAL_MONTHS` = 3, the owner's default (2026-09-28), unconfirmed (`TODO(legal): PM-BOOK-010`). A malformed date on a registry write is now a 400, not a 500.

**Rules covered** — PM-BOOK-010 (named-test coverage 39 → 40 of 233); PM-BOOK-008 gains its write side. 31 operations run, 16 planned.

**Tests added** — `BookRetentionTest` (new): `PM-BOOK-010 a former occupant's household record is anonymised after the retention window` (a day short, then on the day), `PM-BOOK-010 a departed animal loses its passport number after the window, and keeps its species and dates`, `PM-BOOK-010 a current stay is never anonymised, however long it has lasted`, a second pass changes nothing, unknown entrance. `MoveOutTest` (new): `PM-BOOK-008 a resident's move-out closes their occupancy range on the declared day`, the animal's, the refusals. Web: end → 200 / 400; `PM-BOOK-010 POST retention anonymises what is due today in Sofia…`. `BookRetentionPersistenceIT` (Docker — CI): the count drops after the move-out; three months on the identifiers go while April's counts hold. Moving the window boundary by a day, and dropping the ended-stay check, each failed their tests.

**Decisions** — owner D1–D5 on #43 (2026-09-28): 3 months where no law says otherwise; former owners and users (name, ЕГН) and their declarations held — a claim for charges they owe outlives the title, and for how long is counsel's; anonymised means the identifier goes and the fee facts stay; applied on request, a daily job later; move-out first.

**Open** — counsel (owner input 3): how long a former owner's identity may be kept for a claim, and confirm the 3-month windows. No household row names a person today (occupants are counts), so the live effect is on animals' passport numbers. The person record's own retention is cross-module — votes, the board and money refer to it. Registry's remaining Gate-1 rules: PM-ORG-009 waits on owner input 5 (the business-use split); PM-ORG-008 (common parts per building type) and PM-BOOK-012 (book vs billed headcount) are free SHOULDs; the rest wait on auth (PM-BOOK-006/007/009), the closed complex (PM-ORG-006/007), assembly (PM-ORG-003/012) or ADR-004 (PM-ORG-010).

**Read first next time** — #43, this entry, `app/src/main/kotlin/zues/app/registry/BookRetention.kt`.

---

## S-G1-03c-r · 2026-09-29 · review fixes — a backdated move-out cannot bring anonymisation forward (registry)

**Did** — a fresh-context review of S-G1-03c (#44, merged before these fixes landed) found that the declared move-out day alone started the retention window, so a backdated or mistyped date anonymised at once — and a wrong date could never be corrected. Now a move-out also stamps the Sofia day it was recorded (`end_recorded_on`, migration `V202609282105`), and the window runs from the later of the two; ending a stay again corrects its day. The animal range gains the CHECK the household range had. Comments and the API summary say what the pass anonymises — a resident's link to a named person, an animal's passport number — not "the book's personal data".

**Rules covered** — PM-BOOK-010, PM-BOOK-008 (no new rule; named-test coverage stays 40 of 233).

**Tests added** — `BookRetentionTest`: `PM-BOOK-010 a backdated move-out does not bring anonymisation forward`, `… each field group runs on its own window`, `… a window from the end of a long month closes on the last day of a short one`; a stay ending in the future counts as current. `MoveOutTest`: the stored row carries the recorded Sofia day; a correction; the refusals for animals too; both ownership axes for both. `BookWebTest`: a caller's `on` is ignored. `BookRetentionPersistenceIT`: nothing goes a day before three months from the record. Six mutations the old tests let through — swapped group windows, an unpersisted animal end, the animal refusal dropped, one ownership axis dropped, the floor dropped, a caller date honoured — each now fails its test.

**Decisions** — none new; D4 ("nothing is anonymised early") now holds for the move-out day too.

**Open** — as S-G1-03c.

**Read first next time** — this entry, S-G1-03c, `app/src/main/kotlin/zues/app/registry/BookRetention.kt`.

---

## WF-04 · 2026-09-29 · the test plan credits every rule a test names

**Did** — `tools/testplan.py` read only the first rule ID of a test name, so `PM-FEE-004 PM-FUND-003 …` left PM-FUND-003 listed as remaining while `traceability.py` counted it covered — two generators, two numbers. It now reads the whole name, as traceability does: both say 40 covered, 193 remaining, and S-G1-02 no longer lists PM-FUND-003.

**Rules covered** — none new (PM-FUND-003 was already proved by `ChargesTest`).

**Tests added** — none; the evidence is the regenerated TESTPLAN (39 → 40 covered, PM-FUND-003 gone) matching TRACEABILITY.

**Decisions** — none.

**Open** — the next slice: S-G1-02 in `money` (concierge PM-FEE-011, key traceable to a protocol PM-FEE-003).

**Read first next time** — this entry; then `python3 tools/lanes.py`.

---

## S-G1-02a · 2026-09-29 · concierge follows maintenance; every charge line names its decision (money)

**Did** — two Gate-1 fee rules in the engine (#47, `lane:money`). A concierge (портиер) line is a **named line inside MAINTENANCE** (`CostItem.CONCIERGE`), not a fourth stream — PM-FEE-001 separates exactly three, and the chart of accounts keeps three roots (concierge income posts to `INCOME:MAINTENANCE`). It must use the key of the tariff's own maintenance lines (maintenance's default when there are none), so it takes maintenance's exemptions and business multiplier (PM-FEE-011); another key, another stream, a second concierge line, or maintenance itself split over two keys is rejected — whatever the line order. Every computed and stored charge line names the GA decision behind it (PM-FEE-003), not only the run's basis. Storage (`V202609290541`): `charge_line.item` (CONCIERGE, only on MAINTENANCE) and `charge_line.decision_id` (required on every line written from now on — a NOT VALID check, so older lines keep their trace in the basis); a line is unique per run, unit, stream and item, so a resumed run still writes nothing twice. The basis JSON writes `item` only when a line has one, so a past run's hash still reproduces (PM-FEE-014) — pinned against `main`'s serializer. A unit statement orders a period's lines deterministically. `ENGINE_VERSION` 0.1.0 → 0.2.0. `law/…/Keys.kt` is a shared file in `tools/lanes.py` and `WORKING.md`.

**Rules covered** — PM-FEE-011, PM-FEE-003 (named-test coverage 40 → 42 of 233).

**Tests added** — `ChargesTest`: `PM-FEE-011 a concierge line uses maintenance's key and inherits its exemptions and multiplier` (with the derivation label and the default-key case), `… on another key than maintenance's, or in another stream, is rejected` (also named twice, and maintenance split over two keys in either order), `PM-FEE-003 the assembly may choose any of the three keys, and every line names the decision that chose it`, `… changing the key without a linked protocol is rejected`. `ChargeCalculatorTest`: `PM-FEE-001 a concierge line is a maintenance line, so there are still three streams`. `PostingsTest`: `PM-FEE-001 concierge income posts to the maintenance root, so the ledger keeps three income roots`. `ChargeRunServiceTest`: `PM-FEE-011 a concierge line reaches the engine named…`. `BasisJsonTest`: an item-less basis serializes without `item`, and hashes to the value `main`'s serializer gives (`4c0a3997…`). `ChargeRunWebTest`: the preview names each line's decision. `ChargeRunPersistenceIT` (Docker — CI): a concierge line stored as a second maintenance line with every decision; the schema refuses a concierge line outside maintenance and a new line without a decision. Four mutations (the key check off, the stream check off, the decision dropped, `item` always serialized) each failed their tests.

**Review** — a fresh-context review, before the PR: one bug outside this lane — the intake dry-run cannot name a line, so a sheet's concierge column is checked as plain maintenance (finding #48, `lane:intake`). In this lane: duplicate concierge lines previewed fine and then broke the unique index at issue (now rejected up front); "maintenance's key" depended on line order (now all of maintenance's own lines); the schema did not require a decision or tie concierge to maintenance (now it does); five tests proved less than their names (tightened, hash pinned).

**Decisions** — owner D1–D3 on #47 (2026-09-29). **D1 corrected while building:** proposed as a fourth cost stream, it would have broken PM-FEE-001; the rule won — concierge is a named maintenance line, with the effect the owner approved.

**Open** — #48 (intake names a concierge column). The web charges screen has no concierge column yet (web lane; the unit total includes it). The rest of S-G1-02: PM-FEE-017 (consumption lines), the fund rules (PM-FUND-006…010); PM-FUND-002 needs the national minimum wage as dated `:law` values, from a source (owner input).

**Read first next time** — #47, this entry, `charges/src/main/kotlin/zues/charges/Charges.kt`.

---

## H-06 · 2026-09-29 · Handover — resume point before /compact

**RESUME HERE.** `main` is at the merge of this docs PR; before it, `main` was `f1288d4` (S-G1-02a). One slice is in flight: **S-G1-02b** (the repair fund, #50, `lane:money` held) — built, committed and pushed on `slice/S-G1-02b-fund-disbursements` (`dd97eca`), all gates green locally, no PR yet. A fresh-context review of it was running when this was written. Next: apply the review's findings, open the PR (`Closes #50`), switch auto-merge on.

**What shipped since H-05**
- **#41 F-38** — registry "today" is the Sofia day (PM-SYS-004). **#42 WF-03** — gate 10/10 `legal today`: no calendar date read off a clock except through `toSofiaDate`.
- **#44 S-G1-03c + #45** — move-out for residents and animals (PM-BOOK-008); book retention 3 months after the *recorded* move-out (PM-BOOK-010; the owner's default, `TODO(legal)`); former owners' data held for counsel.
- **#46 WF-04** — the test plan credits every rule a test names.
- **#49 S-G1-02a** — concierge is a named maintenance line, not a fourth stream (PM-FEE-011 within PM-FEE-001); every charge line names its GA decision (PM-FEE-003); `ENGINE_VERSION` 0.2.0.
- Finding **#48** (`lane:intake`): the intake dry-run cannot name a concierge line.

**Where the plan stands** — on `main`: 42 of 233 rules have a named test; Gate 1 is 39 of 70; 31 operations run, 16 planned. With S-G1-02b: 46 of 233, Gate 1 43 of 70, 33 running, 15 planned. Every lane is free except `money` (#50).
- **money:** after S-G1-02b, S-G1-02c — pay a disbursement out (its ledger posting) and cancel one; the fund handover statement (PM-FUND-010). Then PM-FEE-017 (consumption lines). PM-FUND-002 waits for the national minimum wage as dated `:law` values, with their source.
- **registry:** free SHOULDs PM-ORG-008 (common parts per building type) and PM-BOOK-012 (book vs billed headcount); PM-ORG-009 waits on owner input 4; the rest on auth, the closed complex, assembly or ADR-004.
- **web:** `/entrance/fund` can go live once S-G1-02b merges. **intake:** #48.

**Owner inputs outstanding**
1. The minister's current order for the declaration template (PM-BOOK-004's placeholder).
2. Counsel: ЗЗД чл. 76 (S-43); ADR-004 / ADR-007; how long a former owner's identity may be kept for a claim, and confirm the 3-month retention windows (PM-BOOK-010).
3. The auth provider (Keycloak marked) — unblocks PM-BOOK-006/007/009.
4. The business-multiplier split (PM-ORG-009, `ChargeRunService.kt:58`).
5. The national minimum wage by year, with its source (PM-FUND-002).
The retention windows were answered on 2026-09-28: 3 months where no law says otherwise.

**Operating procedure (new since H-05)**
- **Review before the PR.** A fresh-context subagent reviews the staged diff against the rule texts; fix, then open the PR. #44 merged before its review, and #45 had to fix it.
- **Merging is automatic.** After opening a PR, switch auto-merge on (squash); the ruleset **Claude** on `main` requires `gates` (active since 2026-09-29), so it waits for CI. If GitHub answers "clean status", CI is already green: `gh pr merge N --squash`. Then sync `main`.
- **Conflicts are fixed without asking.** Merge `origin/main` in — no rebase, no force-push; a generated doc takes main's copy and is regenerated; the session log takes main's file with the branch's entry appended last — the union driver can share one `---` between two entries, so rebuild it rather than trust it.
- Banned identifiers bite on locals too: a `val balance` fails gate 7 even when it is derived from postings (ADR-006) — name it for what it holds.
- Repository settings (auto-merge, rulesets) and Claude's own permissions are the owner's to change; the auto-mode classifier refuses Claude doing either.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry; then `python3 tools/lanes.py`, `gh pr list`, and the branch `slice/S-G1-02b-fund-disbursements`.

---

## S-G1-02b · 2026-09-29 · the repair fund: sign off a disbursement; balance and available (money)

**Did** — the repair and renewal fund's disbursements (#50, `lane:money`). `POST …/fund/disbursements` signs one off: a purpose code — `WORKS` (чл. 48–49 works and equipment), `PASSPORT_MEASURE` (its measure reference required), `GA_PURPOSE` (PM-FUND-006); signed by the party holding the fund's account (PM-FUND-004) on a GA decision (PM-FUND-007) — or, without one, as an emergency repair (`WORKS` only) with its written justification, only while the available balance covers it (PM-FUND-008). Signing off locks the entrance (a transaction advisory lock, as payments do), so two emergencies cannot both pass the cap. `GET …/fund` shows the balance, what is committed and what is available (PM-FUND-009): the balance is what the fund's bank account has received, read from the ledger (`BANK:REPAIR_RENEWAL` postings — paying out is S-G1-02c); committed is the signed-off, unpaid disbursements. New table `money.fund_disbursement` (`V202609290715`) with named checks, one per invariant: a decision or an emergency and never both, an emergency is `WORKS`, a passport measure names its reference, and no reference is blank. The A9 planned fund view now runs (15 planned left). The platform records; the money moves in the fund's own account (ADR-007).

**Rules covered** — PM-FUND-006, PM-FUND-007, PM-FUND-008, PM-FUND-009 (named-test coverage 42 → 46 of 233).

**Tests added** — `FundServiceTest` (new): `PM-FUND-006 a disbursement names a lawful purpose, and a passport measure names the measure`, `PM-FUND-007 only the party holding the fund's account signs off, and on a GA decision`, `PM-FUND-008 an emergency repair needs no decision, but its justification and the available balance to cover it`, `PM-FUND-008 the entrance is locked before the available balance is read, so two emergencies cannot both pass`, `PM-FUND-009 the fund shows its balance and, net of committed disbursements, what is available`, a positive amount and blank references, a decided disbursement may take available below zero, no repair fund account (even with an operating one). `FundWebTest` (new): the view, every request field reaches the service, and 409 / 400 / 404 with the database's text kept inside. `FundPersistenceIT` (Docker — CI): a payment into the fund is its balance and cash is not, a signed-off disbursement is committed, an emergency beyond what is available is a 409; and each table check refuses its own violation, by name. Mutations: five at first (signatory, emergency cap, paid counted as committed, measure reference, the 409 mapping), then nine from the fresh-context review's survivors (blank references, the purpose filter, the order, the amount, the lock, the emergency purpose, the controller's field order, the 409's text) — each failed its tests.

**Decisions** — owner D1–D4 on #50 (2026-09-29): a GA decision always, except an emergency; the signatory is the fund account's holder party (until identity-org's mandates); a decided disbursement is not capped by what is available; paying out and cancelling are the next slice. D5 (owner, 2026-09-29, after the review): an emergency is repair works only — `GA_PURPOSE` and `PASSPORT_MEASURE` need a GA decision.

**Open** — S-G1-02c: pay a disbursement out (its ledger posting, balance down, no longer committed) and cancel one; the fund handover statement (PM-FUND-010); reconcile `FundDisbursed.schema.json` (it requires uuid `decision_id` and `work_order_id`) when the payout publishes it. From the review, deferred: the table does not tie `fund_account_id` to the same entrance's repair fund account (the service picks it); `decision_id` is free text until assembly decisions exist; the signatory is named by the caller until sign-in (owner input 3); JSON `200.9` binds to 200 app-wide; `db/test/constraints.sh` is not run in CI. The web `/entrance/fund` screen can now go live (web lane). Maintenance work orders will commit through this record (maintenance lane).

**Read first next time** — #50, this entry, `app/src/main/kotlin/zues/app/money/FundService.kt`.

---

## S-G1-02c · 2026-09-29 · the repair fund: pay a disbursement out, or cancel it (money)

**Did** — closes a disbursement's life (#53, `lane:money`). `POST …/fund/disbursements/{id}/pay` records that the fund's bank paid a signed-off disbursement: exactly its amount, once, on the bank's value date (not after today, not before the sign-off), recorded — and named in `paid_by` — by the party holding the fund's account. Its journal (journal id = the disbursement's) credits `BANK:REPAIR_RENEWAL` and debits `EXPENSE:REPAIR_FUND`, so the balance and what is committed fall alike and what is available does not move (PM-FUND-009). `POST …/{id}/cancel` withdraws a signed-off, unpaid one — by the holder, with a written reason — and frees what was committed (PM-FUND-007). A paid or cancelled disbursement is closed: a second payout or cancellation is a 409, and the table itself refuses reopening it or rewriting what was signed off (trigger `fund_disbursement_closes_once`). The fund view reads the ledger and the disbursements from one snapshot (REPEATABLE READ), so a payout cannot fall between the two reads. Migration `V202609291132`: `paid_on`, `paid_by`, `cancelled_on`, `cancelled_by`, `cancel_reason`, status `CANCELLED`, named checks one per invariant, with neither date before the sign-off. The recorded balance may go below zero (D2): the bank is the truth.

**Rules covered** — PM-FUND-007, PM-FUND-009 deepened (named-test coverage unchanged, 46 of 233).

**Tests added** — `FundServiceTest`: `PM-FUND-009 a payout lowers the balance and what is committed alike, so what is available does not move`, `PM-FUND-007 only a signed-off disbursement is paid out, once, by the fund account's holder, on the bank's date`, `PM-FUND-009 a cancelled disbursement is no longer committed, so what is available rises`, `PM-FUND-007 only the fund account's holder cancels, with a reason, and never a closed disbursement`, `PM-FUND-007 the entrance is locked before a disbursement is read, so two payouts cannot close it twice`, `PM-FUND-009 a payout may take the recorded balance below zero, since the bank is the truth`, `PM-FUND-009 the fund is read from one snapshot, so a payout cannot fall between the balance and what is committed`. `PostingsTest`: `PM-FUND-009 a payout's journal credits the fund's bank account, debits its spending and balances to zero`. `FundWebTest`: pay and cancel hand each field through; 409 / 404 / 400, and a malformed id or date answers in words, not the framework's classes. `FundPersistenceIT` (Docker — CI): a payout and a cancellation end to end, with the journal's two legs; every check on the table (02b's and 02c's) refuses its own violation, by name, and the trigger refuses a reopening and a rewrite. Mutations: 15, then the 8 the fresh-context review found surviving — each failed its tests.

**Decisions** — owner D1–D5 on #53 (2026-09-29): pay exactly the signed-off amount, once; the recorded balance may go below zero; the holder records payouts and cancels, with a reason, never a paid one; the payout date is the bank's value date, not after today nor before the sign-off; the handover statement is split off to S-G1-02d.

**Open** — S-G1-02d: the fund handover statement (PM-FUND-010), contract confirmed by the owner (D1–D5, 2026-09-29) — claim it once this merges. The fund's opening balance, for a building that joins with money already in its fund (until then a negative balance flags it). `FundDisbursed` comes with maintenance's work orders. Unknown JSON fields are ignored app-wide, so a payout's stray `amountMinor` is ignored (D1 holds: the amount is never read from the request). The web `/entrance/fund` screen can go live (web lane).

**Read first next time** — #53, this entry, `app/src/main/kotlin/zues/app/money/FundService.kt`.

---

## S-G1-02d · 2026-09-29 · the repair fund: the handover statement (money)

**Did** — the fund's reconciled balance statement at a change of manager or management company (#55, `lane:money`, PM-FUND-010). The fund already follows the building: its account and ledger belong to the entrance (ADR-005). `POST …/fund/handover-statements` issues the statement for a handover date (not after today, Sofia), optionally from a period start. From the fund's bank-account postings it gives the opening balance before the period, what was received and paid out within it, and the closing balance, which reconciles by construction. Beside that it sets the balance on the bank's own statement, the difference, and `reconciled` only when they match. It also lists the signed-off disbursements neither paid out nor cancelled by the handover day, which the incoming side inherits, and what is available. It is stored as issued in `money.fund_handover_statement` (`V202609291407`, insert-only: the table ignores updates and deletes). The key figures are columns, with named checks (reconciles, two different sides, the period, not dated ahead of issue, no negative receipts, payouts or commitments). The whole statement is `basis` (canonical JSON), pinned by `basis_hash`, `law_version` and `engine_version`: the hash both sides will sign. `GET …/{id}` reads it back from the stored basis, never recomputed. Issuing reads the fund from one snapshot (REPEATABLE READ).

**Rules covered** — PM-FUND-010 (named-test coverage 46 → 47 of 233).

**Tests added** — `FundHandoverTest` (new): `PM-FUND-010 the handover statement reconciles opening, receipts and payouts to the closing balance, beside the bank's`, `PM-FUND-010 the incoming side inherits what was signed off and neither paid out nor cancelled by the handover`, `PM-FUND-010 the basis reads back as the statement it was, so its hash is what both sides sign`, `PM-FUND-010 a statement is stored as issued, with the hash of its basis, and read back from what was stored`, `PM-FUND-010 a handover is dated no later than today, its period starts by then, and two different parties hand over`, `PM-FUND-010 a statement reads the fund from one snapshot, so a payout cannot fall between its reads`. `FundHandoverWebTest` (new): each field reaches the service, GET as issued, 400 / 404 / 409 with the database's text kept inside. `FundPersistenceIT` (Docker — CI): a statement issued end to end after a payout, with an unpaid disbursement inherited; an UPDATE and a DELETE change nothing; each of the table's checks refuses its own violation, by name. Seventeen mutations (period and handover-day boundaries, signs, inheritance by paid, cancelled and signed dates, order, reconciled, difference, the three guards, the entrance filter, the snapshot, the basis, the controller) each failed their tests.

**Decisions** — owner D1–D5 on #55 (2026-09-29): stored and frozen; the period runs from an optional start to the handover date, not after today; reconciled against the bank's own balance, stored even when it differs; two different registered sides, neither needing to hold the account; a correction is a new statement, never an edit.

**Open** — Signing by both sides (the evidence lane; the QES provider is pending) signs `basis_hash`. The handover act and pack (PM-GOV-018, PM-PMC-010) take this statement (identity-org). Re-titling the fund account to the incoming holder. The fund's opening balance for a building that joins with money already in its fund. `S-G1-02` still has PM-FUND-002 (awaiting the minimum-wage values, owner input 5), PM-FEE-013, PM-FEE-017, PM-FEE-019 and PM-FUND-011.

**Read first next time** — #55, this entry, `app/src/main/kotlin/zues/app/money/FundHandover.kt`.

---

## S-G1-02d-r · 2026-09-29 · the handover statement: the fresh-context review's fixes (money)

**Did** — #56 was merged before its fresh-context review was applied; this lands the review (#59, `lane:money`, PM-FUND-010), as S-G1-03c-r did for #44. **Bug:** an omitted `bankBalanceMinor` bound to 0, so a fund whose ledger closes at 0 could be stored `reconciled` from a figure nobody entered — it is now required (400). Reading back re-derives the canonical basis from what was stored and refuses one that no longer matches its hash (`StatementTampered`); the view returns the `basis` text beside `basisHash`, so anyone can check the one against the other. Statements carry `issued_at` and `GET …/handover-statements` lists them newest first, so a correction never hides the one it corrects; the repository only reads. Paid out is the disbursements' payout journals and received is net, so a reversed receipt is not read as paid out. Migration `V202609291704` adds `issued_at`, drops the received sign check and re-keys the index — the merged `V202609291407` is untouched. Wording: the account and ledger stay with the entrance; re-titling to the incoming holder is not done here.

**Rules covered** — PM-FUND-010 (coverage unchanged, 47 of 233; its "signed by both parties" is #57).

**Tests added** — `FundHandoverTest`: `PM-FUND-010 a stored statement that no longer matches its hash is refused, not served`, and a fuller ledger — a payout before the period, a reversed receipt, payouts and cancellations on the handover day and after it, a tie on the sign-off day, the operating account first — behind the existing seven. `FundHandoverWebTest`: a missing bank balance is a 400; the list. `FundPersistenceIT`: an unreconciled statement end to end, an unregistered side (409), the list newest first, the hash checked against the returned basis; `reconciles` tried from both sides, a one-day period and negative net receipts accepted. The review's 26 surviving mutations were answered by 22 targeted ones plus the 13 earlier that still applied — each fails its tests.

**Decisions** — none new; D1–D5 on #55 stand.

**Open** — #57 (signing by both sides, evidence lane); #58 (the fund's write endpoints take no `Idempotency-Key`). Insert-only tables refuse UPDATE and DELETE but not TRUNCATE (a database-role matter). Next in the money lane: S-G1-02e, consumption lines (PM-FEE-017), contract with the owner.

**Read first next time** — #59, this entry, `app/src/main/kotlin/zues/app/money/FundHandover.kt`.

---

## H-07 · 2026-09-29 · Handover — resume point before /compact

**RESUME HERE.** `main` is at `b8d58de` (#61) or later. In flight: **S-G1-02e** (consumption lines, #62, `lane:money` held) — PR #63, fresh-context review applied, auto-merge on, `gates` running when this was written; it merges itself. Look at it once (`gh pr view 63`) and carry on per the slice skill §7: merged → sync `main`; green but open → merge; failing → fix. Then the owner's answers below, then the next slice.

**Shipped since H-06** (squash-merged):
- #52 S-G1-02b — the repair fund: sign off a disbursement; balance and available (PM-FUND-006…009).
- #54 S-G1-02c — pay a disbursement out, or cancel it (PM-FUND-007, PM-FUND-009).
- #56 S-G1-02d — the fund handover statement (PM-FUND-010); merged before its review, so #60 S-G1-02d-r landed the review.
- #61 — delivery is the session's job: the slice skill §7; `main` is merged in, never rebased.
- #63 S-G1-02e — metered water and heating (PM-FEE-017): in flight, above.

**Numbers** (with #63) — named-test coverage 48 of 233; Gate 1 45 of 70 (left: S-G1-01 kernel 10, S-G1-03 registry 11, and in S-G1-02 PM-FUND-002 — awaiting the minimum wage — PM-FEE-013, PM-FEE-019, PM-FUND-011). The API runs 38 operations (registry 16, money 16, intake 6), 15 planned.

**The owner's open questions** (asked 2026-09-29, one word each; both are built as proposed, a "no" is a small follow-up):
- (a) The business-use multiplier (PM-FEE-010, "the standard rate") is not applied to a metered line — a unit pays what its meter read.
- (b) Issuing waits for every reading, rather than locking the period with an unread unit unbilled.

**Next slice, proposed** — make `/entrance/fund` live (web lane): its backend is complete (the fund view, sign off / pay out / cancel, handover statements — 8 operations). Then `/debts` (arrears, statement, payments).

**Frontend ↔ backend, as of today** — the web calls 4 of the 38 running operations: only `/entrance/charges` is live. `/entrance`, `/portfolio`, `/debts` and `/entrance/fund` render sample data though their backend exists; `/assembly` and `/compliance` are ahead of any backend. Drift is gated where the client is used (the generated client and `next build` in CI — it caught #63's required-field regression). No sign-in yet (owner input 3), so signatories are named by the caller; no end-to-end test runs the web against a live API.

**Findings open** — #57 signing the handover statement by both sides (evidence lane) · #58 idempotency keys on the fund's write endpoints (money lane) · #48 the intake dry-run drops `item` (intake lane).

**Owner inputs outstanding** (unchanged from H-06): 1. the ministry template order · 2. counsel items · 3. the auth provider · 4. the business-multiplier split · 5. the national minimum wage by year, with its source (PM-FUND-002).

**Operating procedure** — the slice skill's §6–§7 (`.claude/skills/zues-slice/SKILL.md`) is the delivery procedure: review before merge (a draft opened for early CI is the session's to mark ready); auto-merge on; failures and conflicts fixed without asking (merge `main` in — never rebase or force-push); CI is never polled — the open PR is looked at once at each wake-up; the owner never merges a PR or reports a merge; after the merge, sync `main`, then the next slice. When the API contract changes, build the web too (`cd web && npm run build`). A PR merged before its review gets an `S-nn-r` slice.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry; then `python3 tools/lanes.py` and `gh pr list`.

---

## S-G1-02e · 2026-09-29 · consumption lines: metered water and heating beside the statutory keys (money)

**Did** — per-unit metered components (#62, `lane:money`, PM-FEE-017). A charge run may carry consumption lines — `WATER` (m³) or `HEATING` (kWh), each with one price per unit of measure in minor units and its GA decision (PM-FEE-012) — and each unit's own readings: plain digits, at most six before the point and three after (what a stored quantity, numeric(12,6), holds), kept as thousandths. Each unit is billed its reading × the price, half-up to the cent, overflow refused, as a MAINTENANCE line (never a fourth stream, PM-FEE-001) marked `METERED` — documented as not a statutory key; no tariff line may take it and a metered item cannot be allocated by a key (a runtime guard: a nullable key would have rippled into the API and the web). Its derivation names the reading and the unit of measure ("water · 12.345 m³ × 2.30 €/m³ (metered)"). The keyed lines are untouched. An unread meter bills nothing and is listed in the preview's `missingReadings`; nothing is estimated. **Issuing waits for every reading** (400): an issued period is final (PM-FEE-015), so an unread unit would stay unbilled for good. A reading for an item the tariff does not price is refused, not dropped. Both paths carry it; the request fields are optional in the contract, so existing callers keep working. An issued metered line keeps its reading as `quantity`, and the statement shows its item. The basis carries `readingsThousandths` and the prices only when present, so every earlier basis hashes as before. `ENGINE_VERSION` 0.3.0. Migration `V202609291726` admits `METERED`, `WATER` and `HEATING` on `charge_line` and ties `METERED` to a metered item and back.

**Rules covered** — PM-FEE-017 (named-test coverage 47 → 48 of 233).

**Tests added** — `ChargesTest`: `PM-FEE-017 a consumption line bills each unit for its own reading, half-up to the cent, and changes no other line`, `PM-FEE-017 an unread meter is billed nothing and listed, never estimated`, `PM-FEE-017 a zero reading is a reading — a line of nothing owed, not a missing meter`, `PM-FEE-017 PM-FEE-010 a business unit pays what its meter read, not a multiple of it`, `PM-FEE-017 a consumption line needs its GA decision, a metered item and one price, and no statutory line is metered`. `ChargeRunStoreTest` (new): `PM-FEE-017 a run is not issued while a meter is unread — the period would stay unbilled for good`. `ChargeCalculatorTest`, `ChargeRunWebTest`: readings reach the engine exactly; a malformed reading is a 400 in words — never a 500, never a class name. `ChargeRunServiceTest`, `BasisJsonTest`, `PostingsTest`, `StatementServiceTest`: the stored path, the basis, the ledger, the statement's item and reading. `ChargeRunPersistenceIT` (Docker — CI): a metered line stored as MAINTENANCE / METERED with its reading; an unread meter leaves the period open; the schema keeps METERED for metered items and back. Mutations: 22, then the fresh-context review's survivors and the fixes (13) and the changed originals (7) — each fails its tests.

**Decisions** — owner D1–D5 on #62 (2026-09-29): a MAINTENANCE line typed by its kind; quantity (3 decimals) × price in minor units, half-up per unit; an unread meter billed nothing and listed; a GA decision per consumption line; the building-meter difference out. Put to the owner, pending: (a) the business-use multiplier (PM-FEE-010, "the standard rate") is not applied to a metered line; (b) D3 on issue — issuing waits for every reading rather than locking the period with a unit unbilled.

**Open** — The owner's answers to (a) and (b). The building-meter difference needs its own decision. A price finer than a cent per unit of measure (common for kWh) cannot be stated yet — D2 fixed minor units. Intake of consumption columns (#48, intake lane). The web charges screen shows `METERED` lines as any line; `missingReadings` has no screen yet (web lane).

**Read first next time** — #62, this entry, `charges/src/main/kotlin/zues/charges/Charges.kt`.

---

## S-G1-02e-o · 2026-09-30 · the owner's answers on metered lines (money)

**Did** — recorded the owner's answers (2026-09-30) to the two questions S-G1-02e put (#65, `lane:money`). (a) **Yes**: the business-use multiplier (PM-FEE-010, a multiple of "the standard rate", a share of the common costs) is not applied to a metered line — a business unit pays what its meter read. (b) **Yes**: a run is issued only once every meter has a reading — an issued period is final (PM-FEE-015), so an unread unit would stay unbilled for good. Both confirm what S-G1-02e built; the comment in `Charges.kt` that said "not yet decided" now says the owner confirmed it. No behaviour change.

**Rules covered** — none new (PM-FEE-017 and PM-FEE-010 were covered in S-G1-02e).

**Tests added** — none; `PM-FEE-017 PM-FEE-010 a business unit pays what its meter read, not a multiple of it` and `PM-FEE-017 a run is not issued while a meter is unread — the period would stay unbilled for good` already hold the answers.

**Decisions** — owner (a) and (b), 2026-09-30. PM-FEE-010's range is still unverified (counsel); if counsel reads "the standard rate" to include consumption, (a) is revisited as a new slice.

**Open** — the building-meter difference; a price finer than a cent per unit of measure; intake of consumption columns (#48); the web charges screen's `missingReadings`.

**Read first next time** — this entry, #62, `charges/src/main/kotlin/zues/charges/Charges.kt`.

---

## WEB-12 · 2026-09-30 · `/entrance/fund` live — the repair fund from the API

**Did** — the fund screen reads the API (#67, `lane:web`). One server-side aggregation per request — the accounts and the statements each fail alone, leaving the fund on screen: `GET …/fund` (the account, balance / committed / available, every disbursement), `GET …/fund-accounts` and `GET …/fund/handover-statements`, for `?entrance=` (default: the first entrance by name — the API lists them in no fixed order). The fund card shows the IBAN and its holder (PM-FUND-004), balance, committed and available side by side (PM-FUND-009), and names every committed disbursement. The sample journal became the fund's **disbursement register**: signed on, purpose (PM-FUND-006), basis — the GA decision (PM-FUND-007), the passport measure, or the emergency and its justification (PM-FUND-008) — amount, and committed / paid / cancelled with its date and reason, filtered by `?status=`; the page adds no figure of its own. The **handover statements** (PM-FUND-010) follow as issued: opening + received − paid out = closing beside the bank's balance and the difference, whether it matches the bank, the date issued, the law and engine versions, the full basis hash, and each inherited disbursement — never called signed, since the parties' signatures are not recorded yet (#57). Read-only: the two write buttons stay disabled, with the reason. The operating account shows its IBAN and holder, and `—` for every figure — the API serves no balance for it. States: backend down; the entrance list refused; no entrance; unknown entrance; no fund account (the API's 404 naming PM-FUND-001 — any other 404 is shown as the API's refusal); a refused or dropped call for the accounts or the statements, which leaves the fund on screen. `web/lib/console.ts` holds what the live entrance screens share — the entrance from `?entrance=`, a call that may find the backend down (logged), and `eur()` (integer arithmetic, a true minus sign); `/entrance/charges` uses it, and now also says so when the entrance list is refused rather than reporting no entrance.

**Verified** — no database here, so against a stub API typed to the generated contract (`satisfies`; `tsc` clean — a misspelt field fails it): six disbursements across the three statuses, an emergency and a passport measure among them; two statements, one matching the bank and one €5,00 short, one inheriting a disbursement; committed beyond the balance (available −€300,00, in red); an empty fund; no operating account; the accounts refused; a 404 naming PM-FUND-001 and one that does not; a 409 on the fund; a 409 on the statements; the statements' connection dropped; the entrance list refused (both screens); no entrance; an unknown one; the backend down; entrances listed out of order. Each render made its GETs and nothing else. `/entrance/charges` after the refactor: the same request body, figures and states. Laid out at 1440 and 1280 wide, no sideways scroll. `npm run build` clean (`/entrance/fund` is now rendered per request); `gen:api` — no drift. Gates green. The fresh-context review's nine findings are fixed: the refused entrance list, the operating account, the handover wording, figures summed in the page, a capped list of commitments, the statements' details, calls failing together, the default entrance's order, the column widths; a second review of the fixes found nine smaller ones, fixed too: the basis and the cancel reason are read in full (never cut to a tooltip), the operating card is not dimmed, the committed figure is printed as the API gives it, a failed call is red and gives its reason, a statement shows its available, inherited disbursements say "поето", names sort 9 before 14. Not yet verified against the real backend (no Docker).

**Rules covered** — none implemented; the screen shows PM-FUND-001, PM-FUND-004 and PM-FUND-006 … PM-FUND-010 as `money` computes and tests them.

**Tests added** — none in `web/` (no test runner there yet; verified with the typed stub, as WEB-11).

**Decisions** — owner D1–D5 on #67 (2026-09-30). D2 was confirmed on a wrong premise — "the API keeps no operating account". It does (`OPERATING` accounts; receipts post to `BANK:OPERATING`); only its balance is not served. The card keeps D2's greyed `—` figures and adds the account and holder the API does give (#68 corrected).

**Open** — #68: a journal read and the operating account's balance, in `money`. #57: the statement's signatures. A statement's parties are not shown — the API gives their ids only. Every write (sign off, pay, cancel, issue a handover) waits for sign-in. The entrance sidebar's name and signed-in person are still the design's, and its links drop `?entrance=`. Next screens: `/debts` (per-unit arrears), then `/portfolio` and `/entrance`.

**Read first next time** — this entry, `web/README.md`, #68.

---

## E2E-01 · 2026-09-30 · the whole chain, checked on every PR — web → api → Postgres

**Did** — the frontend and the backend had never run together: the API was tested against Postgres per endpoint in CI, the web only against stubs (#70, `lane:web`). Postgres 16 now runs on the owner's machine (Homebrew), and a new CI job, `e2e`, runs the whole chain on every PR: Postgres → the API from its jar, Flyway migrating an empty database → `tools/seed_demo.py`, one demo entrance through the public API only (6 units, households, owners, two accounts, September's run issued, payments, four fund disbursements, a handover; once per database) → the production web server → `tools/check_e2e.py`. The check validates every response the live screens use against the published contract, formats included; reads the seeded figures off both screens, each with its label or its whole row, so the same amount elsewhere cannot stand in for it; fails on any error state; and fails when a screen calls an operation it does not cover, or makes a call it cannot read (a non-literal path, a destructured client, a raw `fetch`) — the list is compared with the calls found in `web/`. **The first real run found a drift:** the API sent `null` for every empty field — 50 values in 13 fields across 4 of the 7 calls — where the contract, and the client generated from it, say the field is absent; the screens survived only because they test truthiness. The API now leaves an empty property out of a response (`WireFormat`, the HTTP converter's own copy of the mapper). It is scoped to the wire on purpose: the shared mapper also writes the event publication registry, whose stored text must not change (a global `non_null`, tried first, would have changed it — the review caught it); a map keeps its null values. The charges screen sorts co-owners by name (the API lists them in no fixed order). `web/README.md` gives the local run.

**Verified** — on this machine, the chain exactly as CI runs it (the jar, an empty database, the seed twice — the second writes nothing — `next build` + `next start`): all 7 calls conform, 4/4 and 9/9 anchored figures, "the chain holds". The check proven red on real failures: the API before the fix (13 fields, 50 nulls); a malformed date, uuid or date-time; a screen calling something the check does not cover; a non-literal path, a destructured client, a raw fetch; the API unreachable; the API stopped while the web runs (both screens' error state); a changed figure (euros with a decimal point); the fund card zeroed while the handover still shows the amount. `WireFormatTest` fails without `WireFormat` (the body carries `null`) and with a global `non_null` (the shared mapper stops writing null). Gates green.

**Rules covered** — none implemented; the check reads the screens that show PM-FEE-001…005, PM-FEE-018 and PM-FUND-001, PM-FUND-004, PM-FUND-006 … PM-FUND-010.

**Tests added** — `WireFormatTest`: `an empty field is left out of a response, not sent as null`; `only the wire changes — the shared mapper still writes null, and a map keeps its null values`. `tools/check_e2e.py` (CI job `e2e`).

**Decisions** — owner D1–D5 on #70 (2026-09-30). D5 as built: the wire leaves an empty property out, but through the HTTP converter, not `spring.jackson.default-property-inclusion` — the shared mapper writes stored events. The job runs on every PR and `main` push, unfiltered, so it can be made a required check — the owner's (branch protection); until then a red `e2e` does not block a merge.

**Open** — make `e2e` a required check (owner). The check's `DEMO_BASIS` and error words are copies of the screens' — kept short, reviewed with them. The screens left: `/debts` (needs a per-entrance arrears read in `money`), `/portfolio`, `/entrance`; an entrance picker (the sidebar still shows the design's entrance and drops `?entrance=`). #68 (journal read, operating balance). Every write waits for sign-in.

**Read first next time** — this entry, `web/README.md` (Against the real API and a database), `tools/check_e2e.py`.

---

## S-G1-02f · 2026-09-30 · the entrance's arrears in one read (money)

**Did** — `GET /api/money/entrances/{entranceId}/arrears?asOf=` (#72, `lane:money`): every unit of the entrance that owes on the read date, each aged by the same code as the per-unit read (PM-DEBT-001), largest first (equal amounts by unit id), and their total; a unit owing nothing is left out. One read per entrance instead of one per unit, read from money's own postings only — the caller joins unit names and owners; an entrance with no receivable reads as owing nothing (money does not know which entrances exist). Each unit, and the per-unit read too, now carries its `oldestDebt`: the day the oldest debt still open fell due (the charge's date + the payment term in force on the read date, PM-DEBT-002 — the charge's date stands in for the announcement until the assembly module records one) and how many days overdue it is on the read date (0 while not yet due) — one object, absent when nothing is owed, so "due today" and "owes nothing" cannot be confused. A debt is open while its charge and the credits made by the read date net above zero, so a part-paid debt stays the oldest, and a credit dated after the read date has not closed it. A read date before any payment term was in force is a 400, not a 500. The contract is regenerated (39 operations), and the web client with it.

**Rules covered** — PM-DEBT-001, PM-DEBT-002 (already covered; the new tests are named after them).

**Tests added** — `ArrearsServiceTest`: `PM-DEBT-001 an entrance's arrears list each unit that owes, aged as its own read, largest first — one owing nothing is left out`, `PM-DEBT-002 the oldest open debt fell due the payment term after its charge — a settled one is not the oldest`, `PM-DEBT-002 a debt not yet due is 0 days overdue, and nothing owed has no oldest debt`, `PM-DEBT-002 a credit dated after the read date has not closed its debt — it is still the oldest`, `PM-DEBT-002 a read date before any payment term was in force is a bad date`. `ArrearsWebTest`: `GET an entrance's arrears returns each owing unit and the total, with the oldest debt's due day`, a malformed date a 400. `ArrearsIT` (Docker — CI): `PM-DEBT-001 an entrance's arrears list each unit that owes, with the day its oldest debt fell due`. Mutations: 12, each fails its tests.

**Verified** — on local Postgres against the demo entrance, 2026-09-30: €156,50 — ап. 5 €80,00 (fell due 15.09, 15 days overdue), then ап. 6 €76,50 — no contract violation; 1990-01-01 is a 400. Gates green. The fresh-context review found the new IT paying into an account the entrance never registered (a 400 in CI — it skips locally; now cash), untested paths (a credit after the read date, equal amounts, the entrance id, the total), a 500 on an early date, and the two optional fields — all fixed.

**Decisions** — owner D1–D3 on #72 (2026-09-30); the oldest debt is one nested object rather than two optional fields (the review).

**Open** — #73, for the owner: PM-SYS-002 says a legal constant is the one in force on the relevant legal date — the arrears read (before and after this slice, as D2 says) takes the payment term in force on the read date; the correction is the term on each debt's own date, latent while one entry exists, and it restates D2. Also on #73: advances are not netted against later charges; a receivable with no unit would vanish. WEB-13, `/debts` live, builds on this read.

**Read first next time** — this entry, #73, `app/src/main/kotlin/zues/app/money/Arrears.kt`.

---

## WEB-13 · 2026-09-30 · `/debts` live — arrears from the API, every entrance

**Did** — the firm's arrears screen reads the API (#75, `lane:web`). One server-side aggregation: every entrance by name → its arrears as of a date (the new read, S-G1-02f), then — only where something is unpaid — its units and their owners on that date (owners only, not users), six entrances at a time. `?asOf=` (a real calendar day, else today in Sofia). A group per entrance with something unpaid: unit, owners, what is owed and the entrance's total (the API's), and the oldest debt's days overdue, coloured by its band — or "в срок" while nothing is overdue yet, so the fortnight after a run does not read as a building full of debtors. The header counts units with something unpaid, how many are overdue, and the entrances — including the ones that did not load, which are shown with their reason rather than read as clean. Interest, the escalation ladder and the next action have no backend (PM-DEBT-006, PM-DEBT-009): their columns show `—`, the ladder legend is greyed and says so, both buttons stay disabled. The sidebars lose the design's sample counts; "Задължения" links to `/debts`. Every API call now times out after 10 seconds (the client), so a hung call cannot hold a page. `lib/console.ts` gains `entrances()` (shared with `entranceAt`) and `inTurn` (a few at a time). The charges screen, too, lists owners only. **PM-DEBT-011:** this screen names debtors and amounts — `web/README.md` now says the console must not be reachable by the public before sign-in.

**Verified** — on local Postgres (the demo entrance): as of 30.09, 2 units unpaid and 2 overdue — ап. 5 Надя Тодорова €80,00, ап. 6 Петър Георгиев €76,50, 15 days each, €156,50 for the entrance; as of 05.09, all 6 unpaid and none overdue ("в срок"); 31.02 falls back to today; the backend down; nobody owing. Against a stub typed to the contract: one entrance refused and one dropped — both shown in red, counted in the header ("2 входа не се заредиха") and the footer; a user's title is not listed as an owner. `tools/check_e2e.py` covers the new call and `/debts` on both dates (4 screen checks, 8 calls, all conforming) — and a raw `fetch` anywhere but the typed client still fails it. `npm run build` clean; no drift; gates green.

**Rules covered** — none implemented; the screen shows PM-DEBT-001 and PM-DEBT-002 as `money` computes them, under PM-DEBT-011.

**Tests added** — none in `web/` (no runner); `tools/check_e2e.py` (CI job `e2e`) gains the arrears call and two `/debts` checks.

**Decisions** — owner D4–D7 on #75 (2026-09-30). The fresh-context review's nine findings: failed entrances counted and shown; "длъжник" replaced by "с неплатено" and "в просрочие" (a charge not yet due is not an arrear); a real-day check on `?asOf=`; owners only; the sample badges gone; a timeout and a cap on the fan-out; the band read documented; the grammar (1 ден, 1 вход). Put to the owner: a switch that serves the console only where it is enabled (PM-DEBT-011 today is prose in the README).

**Open** — the owner's call on that switch. #73 (the payment term by each debt's date, advances, a receivable with no unit). The firm sidebar still lists the design's sample entrances and user; an entrance picker. Interest and dunning (PM-DEBT-006, PM-DEBT-009). Next screens: `/portfolio`, `/entrance`.

**Read first next time** — this entry, `web/README.md`, `web/app/(console)/(firm)/debts/page.tsx`.

---

## H-08 · 2026-10-01 · Handover — resume point before /compact

**RESUME HERE.** `main` is at `2129983` (#76) or later. Nothing in flight: no open PR, every lane free. Next: the owner's two answers below, then the next slice.

**Shipped since H-07** (squash-merged, each reviewed before it merged):
- #63 S-G1-02e — metered water and heating (PM-FEE-017). #66 S-G1-02e-o — the owner's answers (yes, yes): no business multiplier on a metered line; issuing waits for every reading.
- #69 WEB-12 — `/entrance/fund` live: the fund card, the disbursement register, the handover statements as issued.
- #71 E2E-01 — the whole chain checked on every PR (job `e2e`): Postgres → the API jar → `tools/seed_demo.py` → `next start` → `tools/check_e2e.py`. Its first run found the API sending `null` where the contract says a field is absent; `WireFormat` leaves it out, on the wire only.
- #74 S-G1-02f — `GET /api/money/entrances/{entranceId}/arrears?asOf=`: an entrance's arrears in one read, each unit with its oldest open debt (PM-DEBT-001, PM-DEBT-002).
- #76 WEB-13 — `/debts` live.

**Numbers** — named-test coverage 48 of 233; Gate 1 45 of 70 (left: S-G1-01 kernel 10, S-G1-03 registry 11, S-G1-02 money 4). The API runs 39 operations (registry 16, money 17, intake 6), 15 planned.

**Frontend ↔ backend, as of today** — three screens are live: `/entrance/charges`, `/entrance/fund`, `/debts`. The web calls 8 operations; on every PR `e2e` validates each real response against the contract and reads the seeded figures off the screens. Still sample data: `/portfolio` and `/entrance` (their backend is partly there), `/assembly` and `/compliance` (no backend), the firm sidebar's entrance list and signed-in person. Every API call times out after 10 s. No sign-in.

**The owner's open questions** (asked 2026-09-30, one word each):
- (a) A switch that serves the console only where it is enabled. PM-DEBT-011 forbids debtor names and amounts in any publicly accessible place; today that is prose in `web/README.md`.
- (b) #73: take the payment term in force on each debt's own date (PM-SYS-002), not on the read date — latent while one entry exists; it restates S-G1-02f's D2.
- And an owner action: make `e2e` a required check in branch protection. Until then a red `e2e` does not block a merge.

**Findings open** — #73 (above; also advances not netted, a receivable with no unit) · #68 a journal read and the operating account's balance (money) · #58 idempotency keys on the fund's writes (money) · #57 signing the handover statement (evidence) · #48 the intake dry-run drops `item` (intake).

**Owner inputs outstanding** (unchanged): 1. the ministry template order · 2. counsel items · 3. the auth provider · 4. the business-multiplier split · 5. the national minimum wage by year, with its source (PM-FUND-002).

**Next slices, proposed** — after (a) and (b): `/entrance` live (units, owners, accounts; its statutory calendar waits for `compliance`) or `/portfolio`; an entrance picker (the sidebars still show the design's entrance); or back to Gate 1's backend (S-G1-01 kernel, S-G1-03 registry).

**The chain on one machine** — `web/README.md` ("Against the real API and a database"): Postgres 16, the API (`bootRun` or the jar), `tools/seed_demo.py` (once per database), `npm run dev`; `tools/check_e2e.py` runs the CI check against it.

**Operating procedure** — unchanged (`.claude/skills/zues-slice/SKILL.md` §6–§7): review before merge; auto-merge on; failures and conflicts fixed without asking (merge `main` in, never rebase); CI never polled — the open PR looked at once per wake-up; sync `main` after a merge; build the web when the contract changes. New since H-07: a slice that adds a web call or a screen extends `tools/check_e2e.py` — the check fails until it does.

**Read first next time** — `CLAUDE.md`, `docs/INDEX.md`, this entry; then `python3 tools/lanes.py` and `gh pr list`.

---

## WEB-14 · 2026-10-01 · the console only where it is switched on

**Did** — `web/middleware.ts`: a server serves the console only with `DOMUVAI_CONSOLE=on` in its environment, or under `next dev` (`npm run dev` binds it to `127.0.0.1`); anywhere else every path but the landing answers a 404 before any route is matched — the same response a missing page gets, however it is asked. Closed by default, so a screen added later is closed with the rest; only `/_next/static/` passes. The landing is rendered per request and shows no link into a closed console: its three `Вход` links are hidden (the mobile menu is now in the server's HTML, hidden until opened), and its two `Започнете безплатно` buttons — which also led into the console — go to the demo form (D3 named three links; there were five). `e2e` starts the same build twice, switched on and not.
**Rules covered** — PM-DEBT-011 (the web console; notices posted at the entrance are `notify`'s)
**Tests added** — `tools/check_e2e.py` "PM-DEBT-011 not switched on": every path in the build's route manifest but the landing answers a load, a client navigation, a prefetch and a HEAD with the 404 a missing page gets on the closed server; the open one serves each static page; the closed landing links to none of them, the open one does. Five mutants, each caught: the middleware ignoring the switch, the landing ignoring it, the landing never linking in, the mobile link unguarded, prefetches let past the middleware.
**Decisions** — none (the contract's D1–D4, owner's yes 2026-10-01). The review's findings are fixed in the slice: a direct 404 instead of a rewrite (a root dynamic route would have answered it), routes from the manifest instead of re-deriving Next's routing, a warning against `web/.env*` files.
**Open** — #79 the demo form says a request was accepted but sends nothing (now the closed landing's one call to action) · the API has no sign-in either: it must stay off the internet (the web calls it from the server only) until the policy module · sign-in itself (ADR-011; the provider is an owner input) · next: S-G1-02f-o, each debt's own payment term (money, #73 item 1; contract approved 2026-10-01)
**Read first next time** — `web/README.md` (The console switch), `web/middleware.ts`

---

## S-G1-02f-o · 2026-10-01 · each debt's own payment term (money)

**Did** — the arrears reads (#81, `lane:money`; #73 item 1) take the payment term in force on each debt's own date — its charge's date, standing in for the announcement — not the one on the read date (PM-SYS-002). The ageing bands and the oldest debt both go by each debt's own due day, in the unit's read and the entrance's; the oldest debt is the open one that fell due first. The read date only picks which postings count. A read date before any term is no longer a 400: no debt had been raised by then, so it reads as nothing owed. A debt dated before any term — open or settled — stops the unit's read and its entrance's with a server error naming the constant; none can exist today, since a charge cannot be computed before its constants. `ArrearsService` takes the term lookup as a parameter, so a test supplies two terms; Spring builds it with the law's, which keeps its one entry. No figure changes today — one term, 14 days, since 2009 — and the API contract is unchanged.

**Rules covered** — PM-SYS-002, PM-DEBT-002

**Tests added** — `ArrearsServiceTest`: `PM-SYS-002 a debt falls due by the payment term in force on its own date, not on the read date`, `PM-DEBT-002 the oldest debt is the open one that fell due first, not the first charged`, `PM-SYS-002 a read date before any payment term reads as nothing owed` (replaces "is a bad date"), `PM-SYS-002 a debt dated before any payment term stops the read, naming the missing constant`, `PM-DEBT-002 the service Spring builds takes the payment term from the law, by the debt's date`. `ArrearsWebTest`: `PM-SYS-002 a debt dated before any payment term is a server error naming the constant — not a 404, not a 400`. `ArrearsIT` (Docker — CI): `PM-SYS-002 each debt falls due by the payment term in force on its own date — a part-paid debt, read back from Postgres`, `PM-SYS-002 a read date before any payment term reads as nothing owed, not a bad request`.

**Verified** — the persistence tests skip here (no Docker), so a scratch copy without Testcontainers ran them against local Postgres 16, 2026-10-01: all pass; the copy is not committed. Mutations, each failing its tests: the term on the read date (unit tests and the persistence one), a read date before any term a bad date again, the oldest as the first charged, a credit aged by its own date, a missing term read as none, the running lookup ignoring the debt's date, Spring left to the primary constructor, a missing term answered as a 404, terms looked up for debts raised after the read date. Gates green.

**Decisions** — none (the contract's D1–D4, the owner's yes 2026-10-01). The review found no defect in the read; its findings are fixed in the slice: the server error pinned at the controller (seven sibling controllers answer the same exception with a 404), a test that Spring can build the service without Docker, the lookup's scope (only debts raised by the read date), an older expectation that still took the term on the read date.

**Open** — #73 item 2, advances not netted (the owner's choice) · #73 item 3, a receivable with no unit (a schema check) · from the review, latent while there is one term: a payment settles the oldest debt by its charge's date (PM-DEBT-008), the arrears read now names as oldest the one that fell due first — the same debt until a term changes · `TRACEABILITY.md` now cites the arrears read for PM-SYS-002 where it cited `law` (the generator takes the first by path).

**Read first next time** — this entry, #73, `app/src/main/kotlin/zues/app/money/Arrears.kt`.

---

## WEB-15 · 2026-10-01 · the demo form claims nothing it did not do

**Did** — the landing's demo form (#83, `lane:web`; fixes #79) no longer says "Заявката е приета": the page sends a request nowhere and stores none. Where to write is the server's setting, `DOMUVAI_CONTACT_EMAIL` (`web/lib/contact.ts`), read per request like the console switch — the repository holds no address. With a plain address the form writes the request out as a letter, with the consent the visitor ticked as its last line; they open it in their own mail or copy it, the page says it reaches us only once sent, and the landing's two "[имейл — предстои]" placeholders show the address. With none — unset, or a value that is not a plain address (quoted, `mailto:` in front, carrying `?`, `#`, `%` or a comma) — there is no form: the section is headed "Демо — предстои" and says requests are not taken through the site yet. `e2e` starts the same build three times: with an address, with none, with a bad one.

**Rules covered** — none in the catalogue (a finding of WEB-14's review, #79).

**Tests added** — `tools/check_e2e.py` "#79 the demo request": with an address the landing offers the form and names the address in both places; with none, and with a value that is not an address, it offers neither and says so; no built file says a request was accepted, and one says the page sends nothing by itself. Six mutants, each caught: the claim back, a form with no address, one placeholder left unfilled, any value counted as an address, the "sends nothing by itself" sentence removed, the address ignored. In a browser, on the production build: an empty submit still names the four fields; a valid one shows the letter, its mail link (lines broken with CR LF, half a broken character pair dropped) and no claim.

**Decisions** — none (the contract's D1–D4 on #83, on the owner's "fix #79", 2026-10-01). After the review: the letter is shown and linked rather than opened by itself, so a visitor with no mail program can copy it; the address check is an allow-list; the consent is a line of the letter; the section's heading follows the setting.

**Open** — the owner's choice, still: where a request goes for good — a stored lead, a CRM, a mail provider; set `DOMUVAI_CONTACT_EMAIL` before the landing is public — without it the "Заявете демо" buttons across the landing still lead to a section that takes no requests · the check reads text, not a browser: an acceptance worded some other way would pass it · the privacy policy the consent names is still "предстои" · #48 (intake) next.

**Read first next time** — `web/README.md` (The demo request), `web/app/DemoForm.tsx`.

---

## S-G1-02a-i · 2026-10-01 · the dry-run names a tariff line (intake)

**Did** — a dry-run tariff line can name its cost (#85, `lane:intake`; fixes #48): `TariffInput.item`, optional, passed to the engine as a charge run's line passes it. A JSON `item` was ignored before, so a concierge line was checked as unnamed maintenance on any key. Now a concierge line on another key than maintenance's, in another stream or named twice is a reported violation and the sheet is not reproduced (PM-FEE-011) — recorded NEEDS_REVIEW, and refused at commit with the reason in the message; a metered cost named on a keyed line is reported the same way (the engine's PM-FEE-017 check). A cost the law does not name is a 400, as an unknown stream or key is — and the tariff is now read before the sheet's rows, so that holds for an empty sheet too. The dry-run, the import record and the commit share the one tariff shape. The contract is regenerated (`TariffInput.item`), and the web client with it.

**Rules covered** — PM-FEE-011, PM-FEE-014 (both already covered; the new tests are named after them)

**Tests added** — `IntakeDryRunTest`: `PM-FEE-011 a concierge line on another key than maintenance's is a violation — the sheet is not reproduced` (#48's probe, with the unnamed line as its control), `PM-FEE-011 a concierge line in another stream, or named twice, is a violation`, `PM-FEE-014 a sheet billed with a concierge line on maintenance's key is reproduced to the cent`, `PM-FEE-017 a metered cost named on a keyed line is a violation — it is not billed as maintenance`, `a cost the law does not name is the caller's error, not a finding about the sheet — whatever the sheet holds`. `IntakeWebTest`: `PM-FEE-011 POST dry-run reports a concierge line on another key than maintenance's — the item is read, not ignored` (raw JSON), `a cost the law does not name is a 400`. `ImportServiceTest`: `PM-FEE-011 a sheet whose concierge line is on another key than maintenance's is recorded NEEDS_REVIEW`, `PM-FEE-011 a commit whose concierge line is on another key than maintenance's is refused, and says why`. `ImportWebTest`: `a malformed tariff on an import — a cost the law does not name — is a 400`. Mutations, each failing its tests: the item dropped, a cost the law does not name ignored, every named line taken for a concierge, a metered cost billed as unnamed maintenance, lower case accepted, a blank read as none, the commit dropping the item, a malformed tariff on an import a 500, the commit not saying why.

**Decisions** — none (the contract's D1–D4 on #85, on the owner's "fix #48", 2026-10-01). A broken PM-FEE-011 is a reported violation, not a 400: a dry-run surfaces what is wrong with a firm's tariff. The review's findings are fixed in the slice: the tariff read before an empty sheet returns, the commit's refusal saying why, the metered, commit and import-400 paths tested.

**Open** — #86, from the review: the dry-run feeds the engine no exemption data (children under six, absence, animals, business use), so a per-person line is checked on raw occupants · a sheet column per cost — #48's "let the column mapping mark a column as concierge" — needs a per-line sheet model: the sheet carries one fee per unit · the two positive tests would pass with the name dropped (the report exposes no charge lines); the negative ones carry the proof · next in money: #73 item 2, advances netted in the arrears read and shown (the owner's answer, 2026-10-01).

**Read first next time** — this entry, #86, `app/src/main/kotlin/zues/app/intake/IntakeDryRun.kt`.

---

## S-G1-02a-j · 2026-10-01 · the dry-run counts the persons the firm charged — pinned (intake)

**Did** — no behaviour changes (#89, `lane:intake`; resolves #86). #86 — filed from S-G1-02a-i's review — said the dry-run feeds the engine no exemption data. A first attempt fed it the sheet's children, animal, absence and business cells; its own review showed that contradicts D1 on #31 (the sheet's occupants are the persons the firm charged, children on top): the dry-run would have charged 2 persons where the household a commit adopts is charged 3. That change was discarded before any commit. The owner then answered the two open questions: animals and absences are already in the charged headcount, and the business-use multiple is not applied in a dry-run yet. `IntakeDryRun` now says, where the engine's units are built, why they carry the occupants alone, and a test fails if any of the four cells is fed to the engine.

**Rules covered** — PM-FEE-008, PM-FEE-005 (already covered; the new test is named after them)

**Tests added** — `IntakeDryRunTest`: `PM-FEE-008 PM-FEE-005 the sheet's occupants are the persons charged — its children, animal, absence and business cells change no recomputed fee`. Mutations, each failing it: the children subtracted, the animals added, the absence exempting, the business cell applying the multiple.

**Decisions** — the owner's, 2026-10-01 (on #86): the sheet's `OCCUPANTS` already reflects absences and animals, as it already leaves out children (D1 on #31); the business-use multiple waits. The contract on #89 was rewritten to match before any code merged.

**Open** — the business-use multiple in a dry-run waits on two things: `ChargeRunService` feeds the engine's `businessUse` from the registry's separate-street-entrance flag (the S-41b finding for `money`), and PM-FEE-010's range is unconfirmed · a dry-run with a business unit and no multiplier in the request would default to the law's minimum (`Charges.kt`, `multiplierFor`) — unreachable from intake while the cell is not fed · from the discarded attempt's review, in the engine and so in `money`'s lane, latent: PM-FEE-009 counts an animal on every per-person line, management included, where the rule names electricity, water, heating and cleaning; PM-FEE-006 exempts the whole unit on one absence figure; an animal count has no upper bound · a real pilot sheet is what would show how firms write these columns (ADR-012) · next in money: #73 item 2, advances netted in the arrears read and shown.

**Read first next time** — this entry, #86, #31 (D1–D3), `app/src/main/kotlin/zues/app/intake/IntakeDryRun.kt`.

---

## S-G1-03d · 2026-10-01 · a unit's business use, apart from its separate entrance (registry)

**Did** — slice 1 of 3 of the change plan #91 (#92, `lane:registry`). `registry.unit` held one flag, `separate_entrance` ("business use through a separate street entrance"), and money fed it to the engine as business use — so PM-FEE-010's multiple went to exactly the units the rule returns to the standard rate. A unit now carries two facts, and neither implies the other: `business_use` (new, migration `V202610011624__unit_business_use.sql`) and `separate_entrance` (as before). A unit already flagged `separate_entrance` is marked `business_use` too — what the flag has meant since V1. Registering a unit takes `businessUse`; the unit view and the registry's view for charging (`UnitForCharging`) return both. The registry applies no rate. **Money still feeds `separateEntrance` to the engine — nothing is charged differently until slice 2.** The contract is regenerated (`NewUnitRequest.businessUse`, `UnitView.businessUse`), and the web client with it.

**Rules covered** — PM-ORG-009 (first tests named after it); PM-FEE-010 is money's, slice 2.

**Tests added** — `RegistryUnitsWebTest`: `PM-ORG-009 a unit is registered with its business use and its separate entrance as two facts`, `PM-ORG-009 GET units returns business use and the separate entrance apart`. `UnitsAdapterTest`: `PM-ORG-009 the view for charging reports business use and a separate entrance as two facts`. `ImportAdoptionTest`: `PM-ORG-009 a registered unit keeps its business use and its separate entrance as given — neither sets the other`, `PM-ORG-009 an adopted unit carries the business use it was given, and no separate entrance`. `RegistryUnitsPersistenceIT` (Docker — CI): `PM-ORG-009 business use and a separate entrance persist as two facts of a unit`. Mutations, each failing its tests: registering drops business use, the request field never reaching the command, the unit view repeating the entrance flag, the charging view dropping it or taking it from the entrance flag, business use registered as a separate entrance, a separate entrance read as business use (at registering, in the request, in the charging view), adoption dropping it.

**Verified** — on local Postgres 16, 2026-10-01: the earlier migrations applied by hand, one unit flagged `separate_entrance` and one not, then the new migration — the flagged one came out `business_use`, the other did not. The persistence test (in its first form, three units) passed from a scratch copy without Testcontainers, through Flyway; the copy is not committed. **No automated test covers the migration's backfill line**: every IT starts from an empty database.

**Decisions** — the owner's yes on #91, 2026-10-01 (the three contracts and proposals 1–3).

**Open** — slice 2, `money`, S-G1-02g: the multiple only for business use without a separate entrance; a run that needs the multiple and has none is refused · slice 3, `intake`, S-G1-02a-k: the dry-run and the commit take the sheet's business use · #91's two catalogue defects (PM-ORG-009 cites a PM-FEE-034 that does not exist; the catalogue and `law` disagree on whether PM-FEE-010's range is confirmed) · who may declare business use, and on what document, is not decided.

**Read first next time** — #91, this entry, `app/src/main/kotlin/zues/app/money/ChargeRunService.kt`.

---

## S-G1-02g · 2026-10-01 · the multiple is charged for business through the common parts only (money)

**Did** — slice 2 of 3 of the change plan #91 (#94, `lane:money`). `ChargeRunService` fed the registry's separate-street-entrance flag to the engine as business use, so PM-FEE-010's multiple went to exactly the units the rule returns to the standard rate. It now feeds `businessUse && !separateEntrance` (the registry's two facts, S-G1-03d): a unit pays the multiple when it is used for business and has no separate street entrance. The multiple is the assembly's: a run that would charge it with no `businessMultiplier` is refused with a 400 naming the units — the stored preview, the issued run and the stateless preview alike — where the engine used to take the law's minimum. A run that charges it to no one needs none: no such unit, or only repair-fund and metered lines. The stored basis records what the engine was given, as before. No change to the API's shape.

**Rules covered** — PM-FEE-010 (first tests named after it in `money`; the engine's own are in `charges`).

**Tests added** — `ChargeRunServiceTest`: `PM-FEE-010 business through the common parts pays the assembly's multiple — with its own street entrance, or with no business, a unit pays the standard rate`, `PM-FEE-010 a run that would charge the multiple with none given is refused, naming the units it would reach`, `PM-FEE-010 a run that charges the multiple to no one needs none`. `ChargeCalculatorTest`: `PM-FEE-010 the preview refuses a run with a business unit and no multiple, and charges the multiple once it is given`. `ChargeRunPersistenceIT` (Docker — CI): `PM-FEE-010 the stored run charges the multiple to business through the common parts only, and a run without it is refused` (the basis too). Mutations, each failing its tests: the old mapping, a separate entrance no longer returning the standard rate, either fact paying, the stored run or the preview defaulting to the law's minimum, a repair-fund-only run refused, the refusal naming every unit, a figure given being ignored.

**Verified** — the persistence test passed on local Postgres 16 from a scratch copy without Testcontainers, 2026-10-01; the copy is not committed. Gates green.

**Decisions** — the owner's yes on #91 (proposal 2: refuse, never default). From the review, kept as is and said in the code: a business unit on a management or maintenance line needs the figure even where its share comes to nothing — what a share weighs is the engine's, not worked out twice at the edge.

**Open** — slice 3, `intake`, S-G1-02a-k: the dry-run and the commit take the sheet's business use · #95 is `web`'s: the charges screen gives no multiple, so an entrance with a business unit shows its run as refused · on #91, for the owner and counsel: a business unit with no chargeable person pays nothing on a per-person line, multiple or not; a multiple given when no unit pays it is never range-checked · the stateless preview's `businessUse` means "pays the multiple" — it has no separate-entrance field · PM-FEE-010's acceptance (a multiple per unit, a protocol reference) is not built.

**Read first next time** — #91, this entry, `app/src/main/kotlin/zues/app/intake/IntakeDryRun.kt`.

---

## S-G1-02a-k · 2026-10-01 · the dry-run and the commit take the sheet's business use (intake)

**Did** — slice 3 of 3 of the change plan #91 (#98, `lane:intake`). A unit the sheet marks as business use pays the assembly's multiple in the dry-run. The request carries the multiple: with a business unit and none given, the dry-run reports a violation naming the units and the sheet is not reproduced — never the law's minimum by default; a sheet whose only lines are the repair fund's needs none. A multiple outside the law's range is the caller's error, a 400, whether or not a unit pays it. The business cell is a yes or a no (да/не, yes/no, true/false, 1/0; a blank is a no) and anything else makes its row a violation. A commit adopts business use: `AdoptedUnit.businessUse` travels on `ImportCommitted`, the registry's listener hands it to `adoptImport` (one line in `registry/ImportAdoption.kt`, the consumer half of the seam), and it is no longer a manual entry — this revises D3 on #31 for business use only. A sheet has no separate-entrance column, so an adopted unit has none, and a charge run on the adopted units, given the same figure, charges what the dry-run did. Children, animals and absences are still not fed to the engine (D1 on #31, #86). The HTTP contract is unchanged.

**Rules covered** — PM-FEE-010, PM-ORG-009, PM-FEE-014.

**Tests added** — `IntakeDryRunTest`: `PM-FEE-010 a unit the sheet marks as business use pays the assembly's multiple — a sheet that charged it the standard rate is named`, `PM-FEE-010 a sheet with a business unit and no multiple in the request is a violation — never the law's minimum`, `PM-FEE-010 a sheet whose only lines are the repair fund's needs no multiple — it is never multiplied`, `PM-FEE-010 a multiple outside the law's range is the caller's error, whether or not a unit pays it`; the test pinned in S-G1-02a-j now covers children, animals and absence. `FeeSheetTest`: `PM-FEE-010 a business-use cell is a yes or a no, in either language — a blank is a no, anything else makes its row a violation`. `ImportServiceTest`: `PM-FEE-010 a commit carries the sheet's business use to the registry — it is no longer a manual entry`, `PM-FEE-010 a commit whose sheet has a business unit and no multiple is refused, and says why`. `ImportAdoptionTest`: `PM-FEE-010 the import listener hands the sheet's business use to the registry, with no separate entrance`. `ImportCommitPersistenceIT` (Docker — CI): `PM-FEE-010 the units a commit adopts are charged what the dry-run charged — the business unit at the multiple`. Mutations, each failing its tests: the dry-run dropping business use, the law's minimum by default, a repair-fund-only sheet refused, only an all-multiplied tariff needing the figure, an unreadable cell read as a no, a 1 not a yes, a no read as a yes, the refusal naming every unit, the commit not adopting, business use still a manual entry, the listener dropping it, an out-of-range multiple accepted, only the upper bound checked.

**Verified** — the commit's persistence test passed on local Postgres 16 from a scratch copy without Testcontainers, 2026-10-01; the copy is not committed. Gates green.

**Decisions** — the owner's yes on #91 (proposal 3: a commit adopts business use). After the review: the multiple's range is checked where the tariff is read, so a figure out of range is a 400 before anything is stored, not a recorded import.

**Open** — an import recorded REPRODUCED before this slice whose sheet has a business cell no longer commits (a 409 saying why): it is re-recorded with the multiple · the "needs the figure" guard and the range check now exist in `money` and in `intake`, each reading the numbers from `law`; their one home is the engine, when the tariff's shape changes for PM-FEE-010's acceptance (a multiple per unit, a protocol reference) · the persistence test adopts through `adoptImport` directly; the event's hop through Spring Modulith with `businessUse` set is covered by `ImportAdoptionTest` with mocks only · #95 (the charges screen gives no multiple) · #91's notes for counsel · next in money: #73 item 2, advances netted in the arrears read and shown.

**Read first next time** — #91, this entry, #73.

---
---

## S-G1-03e · 2026-10-01 · derived ideal parts (registry)

**Did**
- Where no title deed states a unit's ideal parts, the registry derives them from the built-up area ratio and marks them `DERIVED` (PM-ORG-003).
- **When:** a unit set registered into an entrance with no units yet, declaring no ideal parts at all, with every unit's area given.
- **How:** each share is area over total area. Areas are counted in hundredths of m² and the 100% in 0.0001% steps, then split by the kernel's largest-remainder allocator (ties go to the earlier unit). So the entrance sums to exactly 100.0000% (PM-ORG-002), with no floats.
- **Refused (400):** a mixed set, a unit without an area, an entrance that already has units, or an area beyond `numeric(10,2)`. No rule says how declared and derived shares combine.
- New `unit.ideal_parts_source` (`DECLARED` | `DERIVED`, migration `V202610011900__unit_ideal_parts_source.sql`; existing rows `DECLARED`), returned on `GET …/units`. `NewUnitRequest.idealParts` is now optional.
- A unit-set write (registration and import adoption) now locks its entrance row (`FOR NO KEY UPDATE`). Two sets racing into one entrance used to be able to commit 200%; this was true for declared sets too, not just derived ones.
- The contract and the web client are regenerated. Claim: #97.

**Rules covered** — PM-ORG-003 (SHOULD). The mechanism is done; its acceptance, the warning badge in voting screens, waits for `assembly`. PM-ORG-002 gains a concurrency test.

**Tests added**
- `IdealPartsDerivationTest` (3, pure): area ratio; exact 100% by largest remainder; bad areas refused.
- `RegistryUnitsWebTest` +2: no parts reach the service as null; the view returns the source.
- `RegistryUnitsPersistenceIT` +4 (Postgres): derived and marked; declared stays DECLARED; mixed / no area / non-empty entrance refused, writing nothing; a unit-set write waits for a lock held on its entrance.
- **Mutations, each caught:** derived marked DECLARED; the non-empty-entrance refusal dropped; the lock dropped.
- **The lock test first passed with the lock removed.** A `FOR UPDATE` holder also blocks the units' foreign-key check (KEY SHARE). So both the holder and the service lock are `FOR NO KEY UPDATE`, which also leaves other modules' inserts for the entrance unblocked.

**Decisions** — the owner chose **A** (2026-10-01): build per the catalogue and log the counsel question.

**Found**
- **Counsel.** PM-ORG-003 cites **ЗС чл. 40**, which, as read here, apportions common parts by the units' **value**, not their built-up area. The catalogue says area, and the code follows the catalogue. The derived value is marked as such, and only offered when the deed states nothing.
- **Fresh review (subagent, diff + rule text only).** No bugs. It raised the race (fixed), the unbounded area (fixed), and the stale contract (regenerated).

**Open**
- **The warning badge** (web, `assembly` voting screens).
- **`DERIVED` doesn't reach the charge basis.** `UnitForCharging` and `BasisJson` carry the value, not its source, so a charge run on derived parts can't say so. Add it before charges or votes rely on derived parts. This is `money`'s lane, currently taken.
- `allocateByWeight` is used with `Money` as a counter of percent steps; a non-money overload in `kernel` would be cleaner.
- Re-deriving an existing entrance is not supported.

**Read first next time** — this entry, #97, `app/src/main/kotlin/zues/app/registry/PropertyUnit.kt` (`IdealPartsDerivation`).

---

## S-G1-02h · 2026-10-01 · advances netted in the arrears read (money)

**Did**
- Delivers #73 items 2 and 3 (claim #102).
- **Unit read:** both arrears reads now net a unit's `ADVANCE` (the overpayment credit, S-42) against what it owes. `advanceMinor` is the credit held from payments made on or before the read date, shown positive. `netMinor = max(0, total − advance)`. `totalMinor` and the ageing buckets stay gross, since an advance settles no particular debt. No stored posting changes.
- **Entrance read:** `advanceMinor` counts only the credit that covers each unit's own debt (`min(advance, debt)`), so `total − advance = net` holds there too. A unit's surplus can't pay another unit (PM-PMC-008) and shows only on its own figure. A unit fully covered by its advance is still listed; a unit holding only an advance is not.
- **New migration** `V202610012100__posting_unit_required.sql`: a `RECEIVABLE` or `ADVANCE` posting must name its unit. Every writer (`forRun`, `forPayment`, `forPayout`) already did.
- The contract and web client are regenerated, adding the new fields, both required.

**Rules covered** — PM-DEBT-001.

**Tests added**
- `ArrearsServiceTest` +4:
  - a unit's advance netted and shown, buckets gross, a later advance not counted;
  - an advance above the debt leaves net 0;
  - an entrance nets per unit, lists a covered unit, and leaves out a credit-only unit;
  - an entrance counts only covering credit (total − advance = net).
- `PaymentPersistenceIT` +2 (Postgres): an overpayment netted against the next charge in both reads, entrance totals included; the ledger refuses a receivable or advance with no unit (by constraint name).
- **Mutations, each caught:** later advances counted; net not clamped; no DB guard; the entrance advance left uncapped.

**Decisions**
- D1–D4: the owner's go, 2026-10-01; item 2's choice dates from #73.
- **The entrance figure:** the owner chose covering credit over full credit (2026-10-01). A fresh review found that the full credit breaks `total − advance = net` when a unit is over-covered.

**Found** — the fresh review (subagent, diff + rule text only) raised the entrance mismatch (decided and fixed), the stale contract (regenerated), stale comments, and a weak integration assertion (fixed). It also suspected the fields would publish optional; the generated contract marks them required.

**Open**
- **The `/debts` screen** shows gross only. A unit fully covered by its advance still counts as "в просрочие". This is the web lane.
- The statement still shows gross receivables.
- Applying advances to new charges was not chosen.

**Read first next time** — #73, this entry, `app/src/main/kotlin/zues/app/money/Arrears.kt`.

## WEB-16 · 2026-10-02 · the charges screen takes the assembly's multiple

**Did** — `/entrance/charges` can be given the business-use multiple (#104, `lane:web`; fixes #95). Since S-G1-02g the API refuses a run that would charge the multiple with none given, and the screen sent none and had no way to. Now a field on the screen takes it and it travels in the address (`?multiple=`), kept by the period links. **No figure lives in `web`**, and the demo basis has none: the screen passes the figure as typed and the API decides against the law whether it is in range. A run the API refuses over the multiple — none for a business unit, or one out of range — shows the API's refusal with the field, for the same entrance and period; a figure that is not a whole number is said to be so, never dropped. The basis card says which figure was passed, by hand, and that it applies only to a business unit reached through the common parts. `tools/seed_demo.py` seeds a second, small entrance with such a unit — no run issued, no figure seeded — so `/debts` now counts two entrances.

**Rules covered** — PM-FEE-010 (the web's part: the figure is the assembly's, never the screen's).

**Tests added** — `tools/check_e2e.py` "PM-FEE-010 the charges screen": it asks the API which of the multiples 1–9 it accepts and types none itself; for the business entrance the screen shows the API's own refusal (naming the unit) with the field, its entrance and its period — with no multiple, with the lowest and the highest refused figure, and with one that is not a whole number — and the API's own totals for the lowest and the highest accepted figure, with the period links keeping the entrance and the multiple. Twelve mutants, each caught: the field ignored, a figure of the screen's own, the demo basis supplying one, no field on a refusal, the links dropping the multiple, the field forgetting the entrance or the period, the card not saying the figure, a non-whole figure dropped silently, a refused run losing its entrance, the API's message hidden, a high figure capped.

**Verified** — the whole chain on this machine, 2026-10-02: the API from its jar on a scratch Postgres 16 database, the seed, the production web build started three times as CI does; `check_e2e.py` holds. In a browser: the business entrance is refused with the field; given ×4 the shop pays 4× on management and maintenance and its plain share of the fund.

**Decisions** — none (the contract's D1–D4 on #104, on the owner's "#95", 2026-10-02). After the review: a typed figure is never silently dropped; the hint and field appear only on a refusal about the multiple (the API names PM-FEE-010 in it).

**Open** — the "Коеф." column still shows `—`: which unit paid the multiple is not a field of the run · for an entrance with no business unit a figure given is passed and shown but applied to nothing, and the API does not range-check it there (#100) · the API's refusals are in English on a Bulgarian screen · `/debts` still shows gross amounts (S-G1-02h's open point) · the multiple wants a protocol reference (#100).

**Read first next time** — `web/README.md` (Status, `/entrance/charges`), `web/app/(console)/entrance/charges/page.tsx`, #100.

---

## S-G1-02i · 2026-10-02 · the entrance's journal, and the operating account's balance (money)

**Did** — the API half of #68 (#106, `lane:money`); the fund screen follows in `web`.
- **`GET /api/money/entrances/{entranceId}/journal?from=&to=[&account=]`** — the entrance's journals dated from one day to another, both included, oldest first. Each is whole: every leg a signed amount against an account (a debit above zero, a credit below), summing to zero (ADR-006). Each says what wrote it — `CHARGE_RUN`, `PAYMENT` or `FUND_PAYOUT` — read from money's own tables, since a journal's id is that record's; nothing is stored for it. With `account`, only the journals that touch it, still whole. Legs and same-day journals come in the same order on every read.
- **A journal the range or the entrance would cut stops the read** rather than be shown as if whole. Every writer dates a journal's legs alike today, so it cannot happen; the reversing journal the payment migration promises is the case it guards.
- **`GET /api/money/entrances/{entranceId}/operating-account`** — the operating account's IBAN and holder, and what the ledger holds as paid into that bank account, apart from the fund's account and the cash box. **Nothing can record money leaving the operating account**, so the figure is what was paid in to date and the response says so: `outflowsRecorded: false` (the owner's choice, 2026-10-02). Committed and available are not served — nothing can be committed against it. No operating account registered is a 404.
- The contract is regenerated (41 operations) and the web client with it. No schema change.

**Rules covered** — PM-FUND-005, PM-PMC-008, PM-FUND-004, PM-FEE-019 (first tests named after PM-PMC-008 and PM-FEE-019).

**Tests added** — `LedgerReadsTest` (8): the journal whole, oldest first, balanced, naming what wrote it; the account filter keeping whole journals; a journal nothing claims, and an empty entrance; a backwards range refused; same-day journals and like legs in a fixed order; a cut journal stopping the read; the operating account's balance apart from the fund's, saying outflows are not recorded; no operating account. `LedgerWebTest` (4): the wire shape, the account passed or blank, the 400s, the 404. `FundPersistenceIT` +1 (Docker — CI): `PM-PMC-008 PM-FUND-005 an entrance's journal is read whole and balanced from Postgres, with nothing of another entrance — and the operating account's balance apart from the fund's` — two payments, a payout and a charge run. Mutations, each failing its tests: the filter cutting a journal to the matching legs, no date order, credits before debits, a payment named a charge run, no source read, a backwards range read as empty, the operating balance taken from the fund's account, any account taken for the operating one, outflows claimed as recorded, the last day left out, a blank account passed as a filter, a bad date a server error, like legs unordered, a cut journal shown as whole, same-day journals unordered, a year no calendar holds reaching the database.

**Verified** — the persistence test passed on local Postgres 16 from a scratch copy without Testcontainers, 2026-10-02; the copy is not committed. Gates green.

**Decisions** — the owner's, 2026-10-02: serve the ledger's balance for the operating account and say plainly that outflows are not recorded. The contract's D1–D5 on #106.

**Open** — the fund screen still shows `—` for the operating account and the disbursement register in the journal's place: `web`, next · **recording operating expenses** — until then the operating figure only grows · a payment into either account can settle any stream's charges (`RECEIVABLE` is not split by stream), so "two ledgers" (PM-FEE-019's acceptance) is two bank accounts, not two sets of books · `money.posting` has no index: the journal read and the arrears reads scan it · the journal carries no description or document number, and is not paged · `source` is found by loading the rows of the runs, payments and payouts in range.

**Read first next time** — #68, this entry, `app/src/main/kotlin/zues/app/money/Journal.kt`, `web/app/(console)/entrance/fund/page.tsx`.

---

## WEB-17 · 2026-10-02 · the fund screen shows the journal and the operating account's figure

**Did** — the screen's half of #68 (#108, `lane:web`; the API's was S-G1-02i, #107).
- **The operating account's card** reads `GET …/operating-account`. While the API says outflows are not recorded, its figure is named "Постъпления по сметката" under the badge "Само постъпления", with a note that it is not the bank's balance; it is called "Салдо" only once the API says outflows are recorded. Committed and available stay `—`: the API serves neither. An entrance with none registered is told so; any other refusal is shown as it came.
- **A journal section** reads `GET …/journal` for a month (`?period=YYYY-MM`, this month in Sofia by default): each journal's date, what wrote it (Начисление · Плащане · Изплащане от фонда), and its debits and credits by account in words. Legs of one journal on one side of one account are one line with their sum and how many postings it holds. Chips narrow it to the fund's account, the operating one or the cash box (`?account=fund|operating|cash`) through the API's own filter. The footer totals the debits and the credits; a journal that did not load shows no figure.
- Every link keeps the register's filter, the month and the account. The month helpers moved to `web/lib/console.ts`, shared with the charges screen, which now refuses a month like `2026-13` instead of asking the API for it.
- `…/fund-accounts` is no longer called by any screen.

**Rules covered** — PM-FUND-004, PM-FUND-005, PM-PMC-008 (the web's part: the two accounts shown apart, the entrance's journal as the API reads it).

**Tests added** — `tools/check_e2e.py`: both new calls validated against the contract; the fund screen for September shows the operating figure under its honest name with the note, and the journal — the run with its 18 postings, an overpayment, the totals; the journal narrowed to the operating account, to the fund's, and to the cash box (none); an empty month; the links keeping the month, the account and the filter; a debit in the debit column and a credit in the credit column; the fund's payout in the month the API dates it. Sixteen mutants, each caught: the figure called a balance, the month ignored, the account chip not passed, every leg its own line, the source not shown, a link dropping the month or the account, the figure missing, the totals wrong, the badge claiming a book balance, credits before debits, the columns swapped, the cash chip reading the fund's account, the fund chip reading the operating one, a payout not named, the note removed.

**Verified** — the whole chain on this machine, 2026-10-02: the API from its jar on a scratch Postgres 16 database, the seed, the production web build started three times as CI does; `check_e2e.py` holds.

**Decisions** — none (the contract's D1–D5 on #108; the operating figure's naming is the owner's choice of 2026-10-02). After the review: a journal that did not load shows no totals; only the API's own "no operating account" 404 reads as none registered; "статия" for a journal entry and "записване" for a leg; the column is "Вид", not "Документ".

**Open** — one path no check reaches: an operating-account read that fails other than "none registered" (the mutant taking it for none survives — it needs a fault in the API) · the journal's legs carry no unit names, and the journal is not paged · recording operating expenses — until then the operating figure only grows · `/debts` still shows gross amounts (S-G1-02h) · the "Банково извлечение" and the sign-off buttons stay disabled until sign-in.

**Read first next time** — `web/README.md` (Status, `/entrance/fund`), `web/app/(console)/entrance/fund/page.tsx`.

---

## WEB-18 · 2026-10-06 · the debts screen shows what is owed after the advance

**Did** — the screen's half of #73 item 2 (#110, `lane:web`; the API's was S-G1-02h, #103).
- **Three figures per unit, each the API's:** Неплатено (`totalMinor`), Аванс (`advanceMinor`), Дължимо (`netMinor`). The screen subtracts nothing.
- **A unit whose advance covers all it has unpaid** stays listed, reads "покрито с аванс", and is not counted as owing or overdue. The header counts the units that owe; the footer says how many have something unpaid and how many of them owe.
- **An entrance's line** shows what is owed after the advances, with the unpaid and the part the advances cover beside it when there is any — worded "покрито с аванси", since it is less than the units' advances add up to.
- The rows stay in the API's order, largest unpaid first, and the screen says so.
- **`tools/seed_demo.py` issues October's run**, so units that overpaid in September hold an advance against a new debt. September's figures are unchanged. A database seeded earlier has no October run until it is recreated.

**Rules covered** — PM-DEBT-001 (the web's part).

**Tests added** — `tools/check_e2e.py`: `/debts?asOf=2026-10-20` — three units owe and three are overdue of six with something unpaid; the entrance after the advances; a unit with no advance, one covered in full, one covered in part; the API's row order; the footer. `/debts?asOf=2026-10-10` — covered in part and not yet due. September's two dates now read all three columns. Ten mutants, each caught: "owed" showing the unpaid, a covered unit counted as owing or as overdue, the entrance's total unpaid, the advance not shown, any advance taken for full cover, the entrance not saying what the advances cover, the screen subtracting the whole advance itself, the rows re-sorted by what is owed, the footer counting covered units as owing.

**Verified** — the whole chain on this machine, 2026-10-06: the API from its jar on a scratch Postgres 16 database, the seed, the production web build started three times as CI does; `check_e2e.py` holds.

**Decisions** — none (the contract's D1–D5 on #110; netting in the read and showing it is the owner's answer on #73, 2026-10-01).

**Open** — #73 can close: items 1–3 are delivered and item 4 was decided · the unit statement still shows gross · interest and the escalation ladder have no backend · recording operating expenses · #100, #58, #57.

**Read first next time** — `web/README.md` (Status, `/debts`), `web/app/(console)/(firm)/debts/page.tsx`.

---
