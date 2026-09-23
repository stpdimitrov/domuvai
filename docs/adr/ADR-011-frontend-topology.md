# ADR-011 — Frontend topology: a separate app, the API seam, and when to build it

| | |
|---|---|
| **Status** | **Accepted** · 2026-09-21 — repo layout = **monorepo** and the auth *architecture* (OIDC · stateless `api` · Next.js BFF session) are decided; the auth **provider** is deferred to the first frontend auth slice (**Keycloak** marked as the default to revisit). See **Decision · 2026-09-21** below |
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

3. **Repo layout — DECIDED (2026-09-21): monorepo.** Recommended and chosen: **monorepo (`web/` folder in this repo)**, because domuvai's culture is "generate never type, one fact one home," and a monorepo makes contract changes atomic (endpoint + regenerated OpenAPI + regenerated TS client + screen in one PR) and lets the gate pack enforce the client is in sync. The alternative — a separate `domuvai-web` repo — gives cleaner separation and matches the owner's SunnyEscape split-repo pattern, at the cost of two-PR lockstep and contract-drift risk. **The owner chose the monorepo on 2026-09-21** — see the Decision section below. (Re-introducing npm for a real Next.js UI is *not* the S-12 mistake, which was duplicating the domain in TS.)

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

## 5 Open questions — resolved 2026-09-21

1. Repo layout — **resolved: monorepo** (see Decision below).
2. Auth mechanism — **resolved: OIDC + stateless `api` + Next.js BFF session**; the concrete provider is deferred to the first FE auth slice (see Decision below).
3. BFF vs SPA-direct — **resolved: a thin BFF** — the session lives in the Next.js server; per-screen aggregation is server-side, never in the browser (the S-12 lesson).

## Decision · 2026-09-21 · repo layout and auth

The owner (Stoyan Dimitrov) resolved the two open sub-decisions, moving this ADR to **Accepted**.

### Repo layout — **monorepo** (`web/` in this repo)

`web/` (Next.js/TS) lives beside `app/` (Kotlin/Gradle) in this repository, as **siblings with independent toolchains and folder-scoped CI**, deployed independently (`web` on edge, `api` on JVM — ADR-003 unchanged). A monorepo is *not* a unified build.

**Why, for this team:** three full-stack developers on Claude Code (WF-01), contract-first, and a gate pack that already fails on drifted generated docs. A monorepo makes a contract change **atomic** — endpoint (Kotlin) + regenerated `openapi.json` + regenerated TS client + screen in **one PR, one CI run** — and lets the gate pack fail the build when the **TS client is out of sync with the spec**. The owner has already lived the polyrepo tax on SunnyEscape (two repos in lockstep on every contract change); avoiding it here is the point.

**What reverses it:** a dedicated **frontend-only** developer joins, or the FE deploy cadence must diverge hard from the API (§4). Neither holds today.

### Auth — **OIDC · stateless `api` · session in the Next.js BFF** (architecture Accepted; provider deferred)

- The **`api` is a stateless OAuth2 resource server**: it *validates* JWT access tokens (JWKS) and **never issues credentials** (ADR-009). Identity is asserted by the IdP.
- The **session lives in `web` as an httpOnly, secure cookie** — a thin BFF. The browser never holds a raw JWT; the Next.js server exchanges the cookie for the bearer when it calls `api`.
- The **token is authentication, not authorization.** It carries the party identity; the `api` resolves party → entrance and enforces via the **policy module + RLS backstop** (ADR-002, ADR-005). The entrance stays the only isolation key.
- **Do not hand-roll auth.** A ЗУЕС platform is a court-exhibit system with GDPR access-logging duties (PM-BOOK-007); a managed OIDC IdP is the responsible call, and it maps to the three audiences (residents on mobile, managers, firm console) via roles/claims.

**Provider — deferred, Keycloak marked as the default.** The concrete IdP is chosen at the **first frontend auth slice**, when a Gate-1 screen actually needs login. The marked default to revisit is **Keycloak self-hosted** — EU-resident (GDPR/PM-BOOK-007), OIDC/SAML, with a path to broker Bulgarian e-ID / QES (Evrotrust, B-Trust) for ballots later. The provider decision must satisfy: **EU data residency**, email/phone login for non-technical residents, and a future e-ID/QES brokering path. Because the architecture is provider-agnostic (standard OIDC), deferring the provider does **not** block the frontend structure or S-41b.

**What this unblocks:** with the intake contract frozen (S-41), every Gate-1 backend item is contract-complete, so the Gate-1 frontend (a `web/` app in this repo) can start as soon as the owner gives the go-ahead.

## Amendment · 2026-09-23 · Frontend built UI-first, ahead of the generated client

The Gate-1 green light (S-41) was reached and the frontend was built from the owner's Claude Design canvases: a marketing landing and the seven-screen manager console (`/portfolio`, `/entrance`, `/entrance/charges`, `/entrance/fund`, `/debts`, `/compliance`, `/assembly`), merged in PRs #14–#22.

**What deviated from §2.2.** §2.2 makes a **typed client generated from the OpenAPI** the enforced contract boundary ("no hand-written `fetch`"). To move at the pace of the design hand-off, the screens were built **UI-first with hand-modeled typed mock data** shaped like the expected API responses — *not* the generated client, and with **no live `fetch` at all** (the screens are static). This does not violate "no hand-written fetch", but it **defers the enforced boundary**.

**Consequences, accepted.**
- **Good:** the design is validated as running, responsive React now; every screen's data is typed, so wiring is a source-swap; the console shell, scroll-aware nav and the two nav contexts (firm / entrance) are proven.
- **Bad, and accepted:** the mock data can **drift** from the real contract until the client lands; and the console runs **ahead of the backend** — screens for `assembly` (GA), `compliance` (REG) and the arrears escalation exist before those modules do. They are **design-validated shells**, not feature-complete.

**Convergence (restores §2.2).** The next frontend milestone is to **generate the OpenAPI TS client and replace the mock data screen by screen**, starting with the Gate-1 screens whose backend exists (`portfolio` / `entrance` / `charges` / `fund` / `debts`). At that point the generated client becomes the enforced boundary as §2.2 intends. The **auth provider** (Keycloak, marked above) is picked at the first screen that needs login.

**A gap this exposed.** CI (`ci.yml`) runs only the backend gate pack — **`web/` is ungated**, so a broken web build merges silently. Adding a `web` CI job (install · typecheck · `next build`) is the immediate next frontend slice.
