# ADR-010 — Backend language: Kotlin on the JVM

| | |
|---|---|
| **Status** | **Accepted** · 2026-09-14 |
| **Date** | 2026-09-14 |
| **Deciders** | Stoyan Dimitrov (team of three developers, Java background) |
| **Supersedes** | The unstated TypeScript backend — `DEVBRIEF.md` §Stack, and the language of `packages/*` + `apps/zues-calc`. Those become the migration in §5 |
| **Blocks** | A12 scaffold, the re-port of slice S-G1-01a, the gate-pack retarget |

---

## 1 Context

No ADR ever recorded the backend language. It was TypeScript by default — the packages, `zues-calc`, and the stack line in the Developer Brief. Two facts, surfaced late, decide it deliberately.

**The team is Java.** Three developers, all with a Java background, the lead included; future hires come from the same pool. The bulk of the code is written by an AI agent that is equally fluent in Kotlin and TypeScript.

**The owner and the writer are different people.** When an agent writes the code but *humans* must review, debug, extend and hire around it — and the code decides money, majorities and deadlines that end up as court exhibits — the language must be optimised for the humans who own it, not the writer who is indifferent to it. The humans read Java fluently and TypeScript second.

That reframing neutralises the three arguments that had favoured TypeScript:

- **"One language, fungible devs."** The team isn't a TypeScript team, and the frontend is TypeScript *regardless* (the browser leaves no choice), so the stack is polyglot either way. Better to be strong on the **backend**, where the legal weight sits, than uniformly weak in TS.
- **"Fast AI-assisted loop favours TS."** The agent is just as fast in Kotlin.
- **"JVM ops overhead."** Not overhead for a team that already runs the JVM.

**Timing.** Only ~600 lines of TypeScript exist and no framework is scaffolded (A12 is still ahead). The port is roughly a day now and a rewrite after A12. This is the moment to decide.

---

## 2 Decision

**The backend is Kotlin on the JVM, Spring Boot, organised as a Spring Modulith modular monolith. The frontend stays TypeScript / Next.js.**

- **Kotlin over Java.** Java developers adopt Kotlin in days; its null-safety, `data`/`sealed` classes and `@JvmInline value class` fit the "make illegal states unrepresentable" style this codebase already uses (the branded `Money` and `IdealParts`). Java + Spring Boot is the zero-learning-curve fallback if the team prefers maximum familiarity.
- **Topology unchanged (ADR-003).** One `api` modular monolith + `worker` + `web`, one Postgres, schema per module, extraction-ready. Only the enforcement *toolchain* changes — recorded as the 2026-09-14 amendment to ADR-003.
- **Persistence.** Postgres 16, jOOQ or Spring Data JDBC, Flyway migrations, RLS by `entrance_id`. Correctness stays in the database (ADR-001, ADR-006).
- **Jobs.** JobRunr / db-scheduler (Postgres-backed) — no broker, no second datastore.
- **`@zues/law`** becomes a Kotlin library — still a pinned dependency, still the only place a legal number exists (ADR-001 unchanged).
- **Frontend.** Next.js (TypeScript) lean SSR PWA — unchanged. The agent carries more of it; the humans own the Kotlin backend.

---

## 3 Options considered

| Option | Team familiarity | Maintainability of legal core | Kernel sharing | Effort now | Verdict |
|---|---|---|---|---|---|
| **Kotlin · Spring Boot · Spring Modulith** ✅ | High | High — owners' strongest language | Server-only (see §4 note) | ~1 day | **Chosen** |
| Java · Spring Boot | Highest | High | Server-only | ~1 day | Fallback if Kotlin is unwanted |
| Stay TypeScript | Low for this team | Lower — owners review in their 2nd language | Single shared kernel (browser + server) | 0 | Rejected — optimises for the writer, not the owners |
| Kotlin Multiplatform everywhere | Low | Medium | Single kernel to JVM + JS | High | Rejected now — niche; reconsider only for the one offline function in §4 |

---

## 4 Consequences

**Good**
- The humans own the legally-critical backend in the language they read best — the single largest maintainability factor for a legal-correctness product.
- Kotlin's type system fits the domain (value classes for money, sealed classes for the allocation keys and majority denominators).
- Hiring aligns with the team's network; JVM operations are already familiar.

**Bad, and accepted**
- The frontend stays TypeScript — a smaller, contained surface the agent carries more of.
- Two toolchains (Gradle + Node), one of which the team already runs.
- A ~1-day port of the existing TypeScript (§5).
- **The single shared kernel is given up** — the honest cost, below.

**The kernel note (the one real cost).** With a Kotlin backend and a *thin* client, the legal kernel lives once (Kotlin, server) and the client renders server-computed values — correct for bills, final tallies and stored deadlines. The **only** logic pulled toward the client is the **live, in-person assembly tally/quorum**, and only when it must work **offline**. Resolve by one of: (a) online-only live tally (no duplication); (b) a small duplicated `tally()`/`quorum()` with shared golden test vectors; (c) Kotlin Multiplatform for that one function. This is **open and tracked**, gated on the product decision "must an assembly compute offline?" — not a blocker for anything before the `assembly` module.

**Integrity — what survives the switch (verified against all nine non-negotiables and the seven Accepted ADRs):**

| Invariant | Survives as |
|---|---|
| Dated data + pure functions, no rules engine (ADR-001) | Kotlin pure functions + dated `@zues/law` library |
| Integer minor units, exact ideal parts, no floats (ADR-006) | `@JvmInline value class Money(val amountMinor: Long)`; IdealParts as integer millionths |
| `basis` · `basis_hash` · `law_version` · `engine_version` (ADR-001 amendment) | Kotlin data classes on every stored result |
| RLS by `entrance_id`; entrance is the isolation unit (ADR-002, 005) | Postgres RLS — language-neutral |
| Explicit denominator, no custody, agent holds no credential (ADR-008, 007, 009) | Persistence + policy — language-neutral |
| `// Rule: PM-XXX`, a test per rule ID, no legal literal outside `@zues/law`, no banned word | Gate pack retargets its globs `*.ts` → `*.kt`; the checks are otherwise unchanged |

The rule catalogue, the 38 event contracts, the OpenAPI and the SQL DDL are all language-neutral and carry over untouched.

---

## 5 Migration (tracked; not performed by this ADR)

1. Scaffold the Gradle · Spring Boot · Spring Modulith monorepo in Kotlin — this is **A12**, now in Kotlin.
2. Port `@zues/kernel`, `@zues/law` (+ `constants.json`), `@zues/charges`, `apps/zues-calc` to Kotlin — ~600 lines.
3. **Re-do slice S-G1-01a in Kotlin** — same rules (PM-SYS-003/004/005), same tests named by rule ID. The TypeScript slice on `slice/S-G1-01a-time-deadlines` stays as the record.
4. Retarget the gate pack: source globs `*.ts` → `*.kt`; `vitest` → JUnit/Kotest; ADR-003 boundary enforcement from `dependency-cruiser` → Spring Modulith `ApplicationModules.verify()` + ArchUnit.
5. Port DB access to jOOQ / Spring Data JDBC + Flyway. The DDL in `db/migrations` is language-neutral and carried over.
6. Port the TypeScript examples in `CLAUDE.md` and the slice protocol to Kotlin (the principles they show are language-neutral).
7. Frontend: unchanged.

---

## 6 What would reverse this

A decisive shift to a non-JVM team, or a hard product requirement that the *same legal computation run natively in the browser, offline* (which would re-raise Kotlin Multiplatform, or TypeScript-everywhere). Neither is present today.

---

## 7 Deferred

- **The offline-assembly product decision** — drives which of the three kernel options in §4 is taken.
- **Kotlin vs Java** — recommendation is Kotlin; awaiting the team's confirmation. Everything above holds for either.
