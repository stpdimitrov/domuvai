# web — domuvai frontend

The `web` deployable (ADR-011): a Next.js / TypeScript app, a **thin view over `api`**. No
domain logic lives here — money, majorities and deadlines are decided server-side and read
through the API (contract-first, OpenAPI-generated client). This folder is a **monorepo
sibling** of `app/` (the Kotlin backend), with its own toolchain and its own build.

## Run

```bash
cd web
npm install
npm run dev      # http://localhost:3000
npm run build    # production build + type-check
npm run gen:api  # regenerate lib/api/schema.d.ts from docs/api/openapi.json
```

Live screens call `api` from this Next.js server (never from the browser), at `API_URL`
(default `http://localhost:8080`). With the backend down, a live screen says so instead of failing.

## The API client

`lib/api/schema.d.ts` is **generated** from the contract (`docs/api/openapi.json`, itself generated
from the Kotlin controllers — ADR-013) by `openapi-typescript`; never edit it. `lib/api/client.ts` is the
typed `openapi-fetch` client, marked `server-only`. A path, parameter or body the API does not accept
is a type error. When the backend changes the contract, run `npm run gen:api` and commit the result.

## CI

`.github/workflows/web.yml` runs on every PR and `main` push that touches `web/` or the contract:
`npm ci`, then **the generated client must match the contract** (`gen:api` + `git diff --exit-code`),
then `npm run build` (strict type-check of every use). A breaking API change fails here, not in
production (ADR-011 §2.2). It is folder-scoped (ADR-011), so it does not run when neither changes — **do not make it a required status check**: a required
check that never runs blocks the merge.

## Layout

```
app/
  layout.tsx       root layout, <html lang="bg">, fonts (Literata + IBM Plex Sans/Mono)
  page.tsx         the landing (Етаж) at /  — static marketing
  HeroVideo.tsx    client component: the boomerang hero background
  globals.css      tokens + base + hover styles
lib/api/
  schema.d.ts      GENERATED from docs/api/openapi.json — never edit
  client.ts        the typed, server-only client for `api`
  (console)/       the manager console — a route group (no URL segment)
    layout.tsx     the console flex shell + console.css (sidebar differs by context)
    console.css    console shell, table, timeline and card styles
    (firm)/            firm-wide context
      layout.tsx       firm sidebar + main
      FirmSidebar.tsx  firm navigation (client; active from the path)
      portfolio/
        page.tsx       /portfolio — the risk-sorted portfolio dashboard
    entrance/          single-entrance context
      layout.tsx       entrance sidebar + main
      EntranceSidebar.tsx  entrance navigation (client)
      page.tsx         /entrance — the entrance detail (statutory calendar)
```

## Status

Imported from the Claude Design project (`70a25109-…`) and re-implemented as idiomatic React
— the design tool's `<x-dc>` runtime is always discarded. Screens carry the design's sample
data, modeled as typed rows, so wiring each to the API later is a data-source swap.

The **7-screen manager console is complete** (01–07): Портфейл, Вход, Начисления, Общо събрание,
Задължения, Каса и фонд, Съответствие.

- **`/`** — the landing (`Етаж`): a full marketing site — a scroll-aware sticky nav (transparent
  over the hero, solid on scroll, with a scroll-spy underline) + mobile menu, the boomerang hero,
  the platform showcase (mock console cards), roles, steps, trust, pricing, company, an FAQ
  accordion, and a validated "заявете демо" form. Client parts: `LandingNav`, `Faq`, `DemoForm`,
  `HeroVideo`.
- **`/portfolio`** — the console's firm-wide portfolio dashboard (screen 01). A portfolio row
  links through to the entrance detail.
- **`/debts`** — firm-wide arrears (screen 05 Задължения): debtors grouped by entrance, each on
  the чл. 38 ЗУЕС → чл. 410 ГПК escalation ladder (Покана → Нотариална → Решение на ОС → Заповед).
- **`/compliance`** — the firm's regulatory standing (screen 07 Съответствие): register / insurance /
  management-contract status cards, and a filings-and-declarations table.
- **`/entrance`** — a single entrance's detail (screen 02): the statutory-deadline calendar plus
  the entrance's file, accounts and next assembly. Uses the **entrance** sidebar.
- **`/entrance/charges`** — the monthly charge run (screen 03 Начисления) — **live** (WEB-11): the
  engine's preview (`POST …/charge-runs/preview`) joined server-side to the entrance's units and
  owners. `?period=YYYY-MM` (default: this month) and `?entrance=<id>` (default: the first
  registered). Hover an amount for its derivation. The basis is a visibly labelled **demo** — the
  assembly module does not serve GA decisions yet — so confirming is disabled. The design's
  exemptions, coefficient and elevator columns show `—`: the API has no field for them yet.
- **`/entrance/fund`** — cash & repair fund (screen 06 Каса и фонд): the 501 operating and 502
  fund accounts (чл. 50 ЗУЕС) with balance / committed / available, and a double-entry journal.
- **`/assembly`** — the live general assembly (screen 04 Общо събрание): a full-bleed, no-sidebar
  view — session-quorum banner, agenda, the item being voted (quorum + tally with the majority
  threshold), and live attendance with proxies. Has its own stylesheet (`assembly.css`).

The console has two navigation contexts — the **firm** sidebar (`(firm)/`) and the **entrance**
sidebar (`entrance/`) — as sibling nested layouts under one flex shell. A handful of nav items
(Обекти, Календар на сроковете, Доставчици, Документи, Екип, …) remain placeholders — screens not
in the imported design.

Every other screen is still static (typed mock data). No auth yet.

**Auth (ADR-011):** OIDC · the `api` validates JWT and issues nothing · the session is an
httpOnly cookie in this Next.js BFF. Provider deferred (Keycloak marked as the default).

## TODO before launch

- Re-host the hero clip in `HeroVideo.tsx` on a domuvai-owned origin (currently the design
  tool's CDN URL).
- Add a lint step (ESLint is not configured yet).
