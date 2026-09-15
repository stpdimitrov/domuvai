# Module template — extraction-ready by construction

**Every module, and every slice from S-G1-01b onward, is authored to this shape.** ADR-003 §2 requires that a future split into services be *mechanical*, not a rewrite; ADR-003 §5 splits one module at a time on a measured trigger. This template is how that stays true by construction rather than by memory. Stack: **Kotlin · Spring Boot · Spring Modulith** (ADR-010).

---

## The one idea

Design every module's **public boundary** today as if it were already a network boundary — but only the *public* boundary, never inside the module. If in-process calls already look like the remote calls they would become, extraction is a transport swap, not a redesign.

---

## Skeleton

```
modules/<module>/
├─ api/                     ← the ONLY package other modules may import
│   ├─ commands/            ← input DTOs for state changes
│   ├─ queries/             ← input/output DTOs for reads
│   └─ events/              ← published domain events (versioned, envelope-wrapped)
├─ internal/               ← package-private; a boundary test forbids outside access
│   ├─ domain/              ← pure Kotlin: value classes, sealed types, PURE functions
│   ├─ app/                 ← command & query handlers   // Rule: PM-XXX-000
│   ├─ store/               ← repositories — THIS module's schema only
│   └─ outbox/             ← emits domain events in the same tx as the write
└─ db/migration/           ← Flyway migrations for this module's schema only
```

`@zues/law` (dated constants, deadline calculator, majorities) is a pinned dependency of `domain/`, never called over HTTP (ADR-001).

---

## The six rules

1. **Cross-module access only through the published `api` package** — verified, not hoped. `ApplicationModules.of(App::class.java).verify()` and ArchUnit fail the build if one module touches another's `internal`. (The `api` package is the Kotlin replacement for the TypeScript `index.ts` named in ADR-003 §4.)
2. **One transaction writes one module's schema + the outbox. Never two modules' tables.** Cross-module effects happen in the *other* module's own transaction, reacting to an event. A transaction spanning two modules' tables would become a distributed transaction the day you split — so it is never written.
3. **Cross-module reactions go through outbox events** — Spring Modulith externalized application events. In-process today; one config flip from a broker on extraction. Every consumer is idempotent on `event_id`.
4. **Cross-module reads go through the module's query API, never a cross-schema JOIN or FK.** Referential integrity across modules is enforced at the API boundary, not by a foreign key that could not survive a split.
5. **Every command is idempotent** (`Idempotency-Key`); **every consumer is idempotent** (`event_id`). Exactly what at-least-once broker delivery will need.
6. **Coarse, DTO-based, serializable module APIs.** One call per use case; DTOs across boundaries (never persistence entities); no shared mutable state between modules.

---

## The three laws as build checks

| Law | Checked by |
|---|---|
| One write per module per transaction (+ outbox) | ArchUnit: a `store` repository is used only by its own module's `app`; a test asserts a command touches one schema |
| Cross-module reads via the query API | Spring Modulith verify + a check that no SQL names another module's schema |
| Reactions via outbox events | Spring Modulith externalized events; direct cross-module calls limited to the published `api` |

Plus the ADR-003 §4 checks that are language-neutral and unchanged: one Postgres schema per module; RLS on `entrance_id` (a test attempts a cross-entrance read and expects zero rows); a charge run reads the owning module's tables, never a projection.

---

## Where boundaries — and future splits — fall

You cannot make a seam both *split* and *strongly consistent*. So boundaries are drawn on the consistency grain, and splits happen on the async-tolerant seams first.

| Edge | Nature | Split cost |
|---|---|---|
| `assembly` / `money` **read** `registry` (ideal parts, occupancy) to compute weights and bills | synchronous, consistency-critical | **expensive** — keep bundled, or add a staleness-guarded projection |
| a vote closes → `notify`, `compliance`, `evidence`, `agent` react | async, event-driven | **cheap** — pure event consumers that own their data |

Extraction order, decided by consistency, not taste:

- **First (nearly free):** `notify`, `evidence`, `compliance`, `agent` — event consumers that own their data.
- **With care:** `assembly` — reads `registry`'s *slow-moving* ideal parts; a projection with a staleness guard is tolerable.
- **Last / keep together:** `registry` + `money` — the charge run's transactional dependency on **live occupancy** is the hard one; split only with a managed, guarded projection.

## Extracting a module later — the checklist the template earns

1. Point the module's datasource at a new Postgres; run *its* Flyway migrations there. (Its schema was already private — nothing to untangle.)
2. Publish its `api` as REST/gRPC; callers swap the in-process bean for a client stub — **same signatures and DTOs**.
3. Flip Spring Modulith's externalized-events config so its outbox drains to the broker; subscribers already consume the same events idempotently.
4. `entrance_id` already rides the JWT and the event envelope — cross-process context works unchanged.
5. Any synchronous read *into* the module becomes an RPC; add a guarded projection only if latency or coupling demands it.

---

## The slice disciplines inside a module (unchanged by the language)

- Every handler implementing a rule carries `// Rule: PM-XXX-000`; every rule with an acceptance criterion gets a test named after its ID.
- A legal number lives only in `@zues/law`, resolved at the relevant legal date, never `now()`.
- An unverified (`⚠`) rule's number is read from config with a `// TODO(legal): PM-XXX-000`, never a plausible literal.
- Money is integer minor units; ideal parts are exact integer millionths; no floats.
- Every stored charge, decision or compliance task carries `basis`, `basis_hash`, `law_version` and `engine_version`.
