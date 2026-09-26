# domuvai — project context

Bulgarian condominium management (етажна собственост) under ЗУЕС. The statute is the specification.

## Domain rules — read before writing any domain code

All behaviour is governed by `docs/RULES.md` (machine-readable mirror `docs/rules.json`, **233 rules**, catalogue v1.3, legal baseline 2026-09-03).

### Hard constraints

- **Never invent a legal threshold, deadline, majority or fine.** If no rule ID covers the case, stop and ask.
- Rules marked `⚠` (`verified: false` in JSON) carry **unconfirmed numerics**. Implement the mechanism, read the number from configuration, emit `TODO(legal): PM-XXX-000`. Do not pick a plausible number.
- Every function or handler implementing a rule carries `// Rule: PM-XXX-000`.
- Every rule with an Acceptance column gets a test named after its ID:
  `` fun `PM-GA-013 reconvened assembly is valid at 26% of ideal parts`() `` (JUnit 5, backtick name).
- **A legal number exists in exactly one place** — `@zues/law`, as dated configuration. The engine resolves the value in force on the relevant **legal date** (PM-SYS-002), never `Date.now()`. (ADR-001)
- Every stored charge, decision or compliance task carries `basis`, `basis_hash`, `law_version` **and `engine_version`**. (ADR-001 amendment)
- Money is integer minor units in EUR (PM-FEE-016). No floats. Pre-2026 BGN records keep the original amount, the 1.95583 rate and the converted value. (ADR-006)
- Ideal parts are exact decimals summing to 100% per entrance. No floats. (ADR-006)
- Deadlines use Europe/Sofia calendar days through one shared utility (PM-SYS-004/005).
- Votes, decisions, money postings and personal-data access are append-only and audited (PM-VOTE-014, PM-SEC-004).

### Words, and the banned ones

| Say | Never |
|---|---|
| `Entrance` for the isolation unit | `tenant` |
| `Occupant` for the resident | bare `user` as a domain term |
| `charge`, `posting` | `fee`, `balance` as stored column names |
| `entrance_id` as the tenant key | `building` as the tenant key |

`tenant` means two things — the isolation unit and the person renting. That collision found in month four is a schema migration.

### Two deployment modes, one codebase

- `SELF_MANAGED` — an unpaid owner-manager running one entrance. No invoicing, no portfolio, guided wizards (PM-PMC-014/015).
- `PROFESSIONAL` — a firm running a portfolio. Per-entrance staff access, per-entrance ledgers, **no cross-entrance transfers** (PM-PMC-007/008).

Every feature states which mode it serves. Rules tagged `BOTH` apply in both.

## Decisions already made — do not relitigate in code

| ADR | Decision |
|---|---|
| 001 | Dated data + pure functions. **No rules engine.** |
| 002 | One policy module (`:policy`), RLS by `entrance_id` as an independent backstop |
| 003 | **Three deployables** (`api`, `worker`, `web`), **fourteen modules**, boundaries enforced in CI (Spring Modulith + ArchUnit; ADR-003 amendment), one Postgres, schema per module |
| 004 | A shared facility is a cost-sharing agreement with a custodian entrance — **never a second tenant axis** |
| 005 | The entrance is the isolation unit; the account belongs to it, not to the firm |
| 006 | Integer minor units, exact decimals, double-entry, one fund per entrance |
| 007 | **The platform never holds money.** Payment initiation only |
| 008 | Every majority carries its denominator explicitly (`TOTAL` \| `REPRESENTED`) |
| 009 | The agent holds no write credential; prohibited capabilities are not implemented |
| 010 | Backend is **Kotlin · Spring Boot · Spring Modulith**; frontend stays Next.js/TS. Driven by the team's Java background |
| 011 | Frontend is a **separate Next.js app**, contract-first (OpenAPI client), built gate-by-gate. **Monorepo** — `web/` beside `app/` in this repo, sibling toolchains, folder-scoped CI. **Auth: OIDC · stateless `api` (validates JWT, issues nothing) · session in the Next.js BFF (httpOnly cookie)**; the token is authN, the policy module + RLS are authZ. IdP **provider deferred** to the first FE auth slice (**Keycloak** marked as the default — EU-resident, e-ID path) |
| 012 | **Intake is format-agnostic** — map any firm's columns onto our known domain fields per import, never a canonical spreadsheet. **Go-live gate:** build freely, but do **not** bill real money until a real fee sheet reproduces to the cent (ADR-012 §7) |
| 013 | The published OpenAPI contract (`docs/api/openapi.json`) is **generated from the running code** (springdoc) — never hand-written. The catalogue in `tools/build_openapi.py` is the **rule-traceability map**: every running endpoint must cite its rule IDs; unbuilt operations stay, marked planned. Wire format = what runs: `/api/<module>/…` paths, `camelCase` JSON |

**Eleven of thirteen are Accepted.** ADR-004 (shared facilities) and ADR-007 (no custody) name counsel as a co-decider and remain Proposed — do not build against them (ADR-004 gates `registry` and `maintenance`, ADR-007 gates `rail`). ADR-011 (frontend) is **Accepted** (2026-09-21): monorepo + OIDC/BFF, provider deferred. ADR-013 (OpenAPI from code) is **Accepted** (2026-09-26). Everything else is decided — build on it, do not relitigate it in code.

**Stack (ADR-010):** Kotlin · Spring Boot · Spring Modulith backend, Next.js/TypeScript frontend. The pure domain layer already lives in Kotlin — the `:kernel`, `:law` and `:charges` Gradle modules. The principles the examples below show — integer minor units, exact ideal parts, one deadline utility — are language-neutral.

## Slice protocol

Context drift across sessions is the main risk. Conversation context is never shared between people; only this repo is.

### Boot, in order

1. `CLAUDE.md` · 2. `docs/INDEX.md` · 3. `docs/SESSIONLOG.md` (top entry only) · 4. `docs/DEVBRIEF.md` · 5. `docs/RULES.md` for **this slice only** · 6. `docs/adr/` · 7. `git log --oneline -10`, current branch, failing tests

If a boot document is missing, say so and stop. Do not reconstruct context from the conversation.

### State the slice contract before writing code

```
Slice:        S-nn <name>
Module:       one module only
Branch:       slice/S-nn-<name>
Rules:        PM-XXX-000 … (quote each rule's text back, do not paraphrase)
Interprets:   how I read each rule, one line each
Out of scope: what this slice deliberately does not touch
Done when:    the exit checks pass
```

The contract becomes the pull request description.

### Size

One module per slice. Max ~400 lines of reviewable diff. If it will not fit, split it and say so.

### Exit checks — all must pass

- Every rule in the contract has a test named after its ID
- Tests pass
- No rule ID in code that is absent from `rules.json`
- No banned word in an identifier
- No unverified rule's number as a literal — config lookup + `TODO(legal): PM-XXX-000`
- **No legal number anywhere outside `@zues/law`**
- No cross-module database access; no import between modules except through a module's published API (Spring Modulith package boundary; ADR-003)
- Counts and lists in docs are generated, never typed

### Fresh-context review

Review the diff against the rule texts as if someone else wrote it. Re-reading your own code with the intent still in mind finds nothing. Where a subagent is available, hand it the diff and the rule texts with no other context.

### Close

Append to `docs/SESSIONLOG.md` and commit it with the slice. Never edit an earlier entry.

## Working in parallel (multiple developers)

Up to three developers build here at once, each in their own Claude Code session. Sessions share nothing but this repo, so every rule below is enforced by the repo, not by anyone's memory.

- **Never push to `main`. Branch → PR → CI green → merge.** Direct-to-`main` cannot work for more than one person — the second push is rejected — and it skips the gate every merge must pass. Push the `slice/S-nn-*` branch, open a PR (the slice contract is its description), let CI run the gate pack, merge when green. Branch protection requires it.
- **One module per developer at a time.** The module is the parallelism boundary (ADR-003). Before starting, claim the slice — a GitHub Issue from `docs/TESTPLAN.md` — and check that no open PR touches your module. Never open a module someone else has in flight.
- **Rebase before you push or update a PR:** `git fetch && git rebase origin/main`, so conflicts surface in your session where you can resolve them, not at merge time.
- **Generated docs are regenerated, never hand-merged** — `docs/TRACEABILITY.md`, `docs/TESTPLAN.md`, `docs/FUNCTIONAL.md`, `docs/api/openapi.json`, `docs/events/*`. On a conflict, take `origin/main`'s version and re-run the generators (the gate pack does this), then commit. Hand-merging them corrupts the structure.
- **Schema changes go in a NEW migration file** — `V<yyyyMMddHHmm>__short_desc.sql`, never an edit to an applied migration. `V1__init.sql` is the baseline; one file per change, so two developers' schema work never touches the same file. The schema-columns gate reads every `V*.sql` (both `CREATE TABLE` and `ALTER TABLE … ADD COLUMN`).
- **`docs/SESSIONLOG.md` is append-only and union-merges** (`.gitattributes`): add your slice's entry at the end, never edit an earlier one — two appends concatenate instead of conflicting.

Full protocol and the work split: `docs/WORKING.md`.

## Standing rules

- **Never invent a legal threshold.** No rule covering the case means stop and ask.
- **One fact, one home.** A fact written in two documents will drift. Link instead of restating.
- **Generate, never type.** Counts, module lists, traceability tables.
- **Counting artefacts is not checking them.** Assert on content, not on how many things were produced.
- **A check never run against a failure is not a check.** When you add a guard, break something on purpose and confirm it fires.
- **Read before patching.** A heading-replace wipes the section.
- **No code before its ADR is Accepted.**
- **The repo is the source of truth**, the conversation is disposable, a personal vault is neither.

## When a rule and existing code disagree

The rule wins. Do not edit quietly — raise a change plan naming the rule ID, the current behaviour and the correction, and get it confirmed. If the code is right and the rule is wrong, that is an ADR and possibly a question for counsel, not a silent fix.

## Useful prompts

- `Implement PM-GA-012 … PM-GA-018 in the assembly module. Table-driven tests named by rule ID.`
- `Audit the money module against domain FEE in docs/RULES.md. Report IMPLEMENTED/PARTIAL/MISSING with file:line. No edits.`
- `Generate the schema for §4 of docs/RULES.md; enforce every INVARIANT as a DB constraint where possible.`
- `List every rule in docs/rules.json with verified=false and show where each unconfirmed number is used in code.`
