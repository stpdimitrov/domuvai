# ADR-011 — Frontend topology: a separate app, the API seam, and when to build it

| | |
|---|---|
| **Status** | **Proposed** · 2026-09-16 — the separate-frontend part restates ADR-010/ADR-003 (Accepted); the **repo-layout** choice is open and awaits the owner |
| **Date** | 2026-09-16 |
| **Deciders** | Stoyan Dimitrov |
| **Consolidates** | ADR-010 (frontend stays Next.js/TS), ADR-003 (the `web` deployable) |

---

## 1 Context

Everything built so far (slices S-09 … S-25) is **backend** — the Kotlin/Spring Boot modular monolith, the pure domain (`:kernel/:law/:charges`), the schema, and the REST API (26 OpenAPI operations as of S-24). **No frontend code exists.**

The owner asked to confirm the frontend will be a *separate application*. It already is, by prior decision:

- **ADR-010** — "the frontend stays TypeScript / Next.js … a lean SSR PWA," and "the frontend is TypeScript regardless (the browser leaves no choice)."
- **ADR-003** — three deployables: **`web` (Next.js PWA: resident / manager / firm console) → `api` (Spring modulith) → Postgres.**

The backend has been built frontend-agnostic (pure REST + generated OpenAPI), so nothing needs undoing. This ADR consolidates the *seam* discipline and puts the one open sub-decision — repo layout — on the record.

## 2 Decision

1. **The frontend is a separate deployable** (`web`): a Next.js / TypeScript SSR PWA serving resident, manager and firm consoles. It is never embedded in the Spring app; the browser is a thin view over a server that owns every judgement (money, majorities, deadlines are court exhibits).

2. **The seam is contract-first:**
   - The frontend consumes a **typed client generated from the backend's OpenAPI** (the gate pack already emits the spec). No hand-written `fetch`. A breaking API change fails the frontend build, not production.
   - **One auth strategy** across the seam (OIDC / JWT session); the `api` stays stateless; CORS is configured at the `api`.
   - **No domain logic in the browser.** The kernel is single-sourced in Kotlin (ADR-010 "Option 1"); the frontend calls the API, including the online-only live assembly tally. This is the S-12 lesson (no duplicated domain in TS) applied to the UI.

3. **Repo layout — OPEN.** Recommended: **monorepo (`web/` folder in this repo)**, because domuvai's culture is "generate never type, one fact one home," and a monorepo makes contract changes atomic (endpoint + regenerated OpenAPI + regenerated TS client + screen in one PR) and lets the gate pack enforce the client is in sync. The alternative — a separate `domuvai-web` repo — gives cleaner separation and matches the owner's SunnyEscape split-repo pattern, at the cost of two-PR lockstep and contract-drift risk. **The owner picks mono vs poly before the frontend starts.** (Re-introducing npm for a real Next.js UI is *not* the S-12 mistake, which was duplicating the domain in TS.)

## 3 When to build the frontend — the green light

The frontend tracks the backend **gate by gate**, not big-bang. The signal for a gate is: **that gate's API contract is frozen** — its endpoints are built and its OpenAPI is stable (no breaking changes expected).

**Do not wait for "the whole backend."** All four gates are months out; the first frontend milestone is Gate 1 alone.

**Gate 1 (fee engine) — the first frontend milestone.** Build the Gate-1 frontend (resident fee view, manager charge-run console) when all of these are built **and** their OpenAPI is frozen:

| Area | Endpoint / capability | State (S-25) |
|---|---|---|
| registry | entrances, units, household, animals | ✅ done |
| registry | **owners / parties** (a receivable needs a liable party) | ⛔ pending |
| registry | **book completeness + ministry export** (PM-BOOK-001/002) | ⛔ pending |
| money | charge run compute / persist / post | ✅ done |
| money | **fund account** (PM-FUND-*) | ⛔ pending |
| money | **receivable / arrears read** (a resident's balance) | ⛔ pending |
| intake | **spreadsheet import** — Gate 1's literal "reproduce the firm's spreadsheet" | ⛔ pending |

When those land (est. ~6–10 more slices) and the `registry` + `money` + `intake` OpenAPI is stable, **the assistant will announce: "Gate 1 backend is contract-complete — time to build the Gate-1 frontend."** That is the signal the owner asked for.

## 4 Consequences

- **Good:** independent deploy/scale (`web` on edge, `api` on JVM); the contract is the enforced boundary; the legal weight stays server-side; the FE can evolve in TS without touching Kotlin.
- **Cost:** two deployables to coordinate; auth spans a seam; the OpenAPI contract must stay stable per gate (hence the gate-by-gate green light).
- **Revisit when:** a dedicated FE developer joins (polyrepo separation gets more attractive); per-screen aggregation grows heavy (add Next.js API routes as a thin BFF — never push aggregation into the browser or bloat the domain modules).

## 5 Open questions

1. Repo layout — **monorepo (recommended) vs separate repo**. Owner to decide before the FE starts.
2. Auth mechanism — OIDC provider vs self-issued JWT; where the session lives.
3. Whether a BFF (Next.js API routes) is warranted, or the SPA calls `api` directly.
