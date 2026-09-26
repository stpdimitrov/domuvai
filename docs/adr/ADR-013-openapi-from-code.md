# ADR-013 — The OpenAPI contract is generated from the running code; the catalogue keeps the rules

| | |
|---|---|
| **Status** | **Accepted** · 2026-09-26 — the owner accepted option C (the spec is generated from the running code) |
| **Date** | 2026-09-26 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | A9's generation source (S-07: `docs/api/openapi.json` generated from the hand catalogue in `tools/build_openapi.py`). The catalogue stays — as the rule-traceability map and the list of planned operations |
| **Blocks** | The slice that implements this; **WEB-11**, the generated TS client (ADR-011 §2.2 convergence) |

---

## 1 Context

**ADR-011 §2.2** makes a typed client, generated from the backend's OpenAPI, the enforced boundary between `web` and `api`: *"A breaking API change fails the frontend build, not production."* That holds only if the spec describes the API that runs.

It does not. `docs/api/openapi.json` is the **A9 design contract** (S-07, 2026-09-13), generated from a hand catalogue **before any backend code existed** so that "the surface cannot drift from the decisions". The code was then built slice by slice (S-09 … S-41) against the rules, not against the catalogue, and nothing ever compared the two. Measured on `main`, 2026-09-26 (WEB-10):

| Check | Result |
|---|---|
| Catalogued operations that match a running endpoint | **9 of 27** |
| Running endpoints that are not catalogued | **15** — incl. `GET /entrances`, `GET …/fund-accounts`, `GET /units/{id}/arrears`: the reads the console screens need |
| Catalogued operations that do not run | **18** — modules not built yet (assembly, compliance, documents, matters), plus renames (`/fund` vs `/fund-accounts`; `/imports/{id}/dry-run` vs `…/fee-sheet/dry-run`) |
| Path shape | code: `/api/<module>/…` · spec: no module prefix, `servers: …/v1` |
| JSON field names | code: `camelCase` (Jackson default, no naming strategy) · spec: `snake_case` |
| Declared conventions | `Idempotency-Key` on every write (**PM-SYS-014**) — implemented nowhere; a 409 problem body carrying `rule_id` — no HTTP error handler exists |
| Check linking spec ⇔ code | **none** — gate 6/9 validates OpenAPI 3.1 syntax and that cited rule IDs exist |

A TS client generated from this spec compiles, then 404s on most calls and reads `undefined` for every `snake_case` field. It would also send an `Idempotency-Key` the server ignores.

Forces: the house rule **generate, never type** (the gate already fails on drifted generated docs); **one fact, one home**; the rule traceability A9 built (every operation cites rule IDs, validated against `rules.json`); money stays integer minor units on the wire (**PM-FEE-016**, ADR-006); the team — three full-stack developers on Claude Code (WF-01), one repository (ADR-011).

## 2 Decision

1. **The published contract `docs/api/openapi.json` is generated from the running code.** springdoc-openapi reads the controllers and their Kotlin request/response types. A test in the gate pack boots the application and writes the raw spec; the openapi gate publishes it; the existing *generated files must be committed* check fails the build when the committed file differs. The spec states what runs — nothing more.
2. **The Python catalogue stops being the spec's source and becomes the traceability map.** It maps each operation (method + real path) to the rule IDs it serves. The gate fails when a **running endpoint has no catalogue entry** (an endpoint that serves no rule) or when an entry cites a rule absent from `rules.json` (the A9 guard, kept). The rule IDs are merged into the published spec as `x-rules`, so a reader of the contract still sees which rule each operation serves.
3. **Operations not built yet stay in the catalogue, marked `planned`.** The design intent A9 captured is kept; it is never published as if it ran.
4. **The wire format is what runs.** Paths stay `/api/<module>/…` (module-tagged, ADR-003). JSON stays **`camelCase`**: idiomatic for the TS client, no written standard asks for `snake_case`, and switching would churn every endpoint and test for no user-visible gain.
5. **A9's conventions become code obligations, not spec claims.** `Idempotency-Key` on writes (PM-SYS-014) and the 409 problem body with `rule_id` appear in the published spec when the code implements them. Until then they are tracked by their rules in `TESTPLAN.md`, not asserted by a document.

**What does not change:** ADR-011 — contract-first, generated TS client, monorepo; this ADR is what makes its §2.2 true. The `rules.json` guard. The WORKING.md / CLAUDE.md rule that generated docs are regenerated, never hand-merged — `openapi.json` stays generated; only its source moves.

## 3 Options considered

| Option | Complexity | Makes the spec describe the running API | Verdict |
|---|---|---|---|
| A · Keep the hand catalogue, correct it by hand | Low | Once — then nothing stops the next drift | Rejected — it already drifted with nothing to stop it |
| B · Hand catalogue + a conformance gate on method + path | Medium | Paths only. Request/response bodies stay hand-typed, so the `camelCase`/`snake_case` split, and every later DTO change, goes unseen | Rejected |
| C · Spec generated from the code (springdoc) + catalogue kept for rule traceability ✅ | Medium | Paths **and** bodies — both come from the controllers and their types | **Chosen** |
| D · Spec-first: generate Kotlin server interfaces from the spec (openapi-generator) | High | Yes, at compile time | Rejected — rewrites 24 working endpoints; generated DTOs clash with the domain's value types (`Money`, exact decimals, ADR-006); inverts a build that works |

**B vs C is the whole decision.** B checks that the right doors exist; C also checks what goes through them. A generated TS client breaks at runtime on bodies — field names, optionality, types — which is exactly what B cannot see.

## 4 Consequences

**Good**
- ADR-011 §2.2 becomes true: a Kotlin DTO change → regenerated spec → regenerated TS client → the `web` build fails in the **same PR** (once WEB-11 adds the spec to the `web` workflow's `paths`).
- The console's missing reads are typed the day they ship; built vs planned is explicit instead of implied.
- Traceability gets stricter: a new endpoint cannot merge without citing the rules it serves.

**Bad, and accepted**
- One new dependency (springdoc-openapi, its Spring Boot 3 line) that must move with Spring Boot upgrades.
- The spec test boots the application; the gate pack gets slower by about one integration test.
- A9's prose conventions leave the published spec until the code implements them. The spec gets thinner and more honest.
- The 15 uncatalogued endpoints need rule citations, read from their rule-named tests via `TRACEABILITY.md` — a one-off cost in the implementing slice.

**How it is enforced** — gate 1/9 (tests, integration tests included) produces the raw spec; gate 6/9 publishes it and runs the catalogue checks (every running endpoint catalogued, every cited rule known, valid OpenAPI 3.1); the committed-generated-files check fails on any drift.

## 5 What would reverse this

A consumer **outside this repository** must build against an operation **before it is implemented** — a partner integration or a separate team. That surface then goes spec-first (option D, scoped to it), because code cannot describe what does not exist yet.

## 6 Deferred

- **The TS client generator** (e.g. `openapi-typescript` + `openapi-fetch`, `orval`, `hey-api`) → WEB-11.
- **Auth in the spec** (OIDC bearer `securitySchemes`) → the first frontend auth slice (ADR-011, provider deferred).
- **Versioning and a public base URL** (`/v1`, `api.domuvai.bg`) → before the first consumer outside this repository.
- **Implementing the A9 conventions** (PM-SYS-014 `Idempotency-Key`; the `rule_id` problem body) → their own slices, tracked in `TESTPLAN.md`.
