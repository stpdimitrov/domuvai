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
```

## Layout

```
app/
  layout.tsx       root layout, <html lang="bg">, fonts (Literata + IBM Plex Sans/Mono)
  page.tsx         the landing (Етаж) at /  — static marketing
  HeroVideo.tsx    client component: the boomerang hero background
  globals.css      tokens + base + hover styles
  (console)/       the manager console — a route group (no URL segment)
    layout.tsx     the console shell (sidebar + main column)
    Sidebar.tsx    firm navigation (client; active state from the path)
    console.css    console shell, table and status-badge styles
    portfolio/
      page.tsx     /portfolio — the manager's risk-sorted portfolio dashboard
```

## Status

Imported from the Claude Design project (`70a25109-…`) and re-implemented as idiomatic React
— the design tool's `<x-dc>` runtime is always discarded. Screens carry the design's sample
data, modeled as typed rows, so wiring each to the API later is a data-source swap.

- **`/`** — the landing (`Етаж`), static marketing.
- **`/portfolio`** — the manager console's portfolio dashboard (screen 01 of 7), on the shared
  console shell. Sidebar nav items beyond Портфейл are placeholders, activated as each screen
  lands (Вход, Начисления, Общо събрание, Задължения, Каса и фонд, Съответствие).

Still static: no auth, no API. The generated API client + auth arrive when a Gate-1 screen
needs live data.

**Auth (ADR-011):** OIDC · the `api` validates JWT and issues nothing · the session is an
httpOnly cookie in this Next.js BFF. Provider deferred (Keycloak marked as the default).

## TODO before launch

- Re-host the hero clip in `HeroVideo.tsx` on a domuvai-owned origin (currently the design
  tool's CDN URL).
- Add the OpenAPI-generated client + a `web` build/lint CI job.
