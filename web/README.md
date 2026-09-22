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
  layout.tsx     root layout, <html lang="bg">, fonts (Literata + IBM Plex Sans)
  page.tsx       the landing (Етаж) — static marketing, no API calls
  HeroVideo.tsx  client component: the boomerang hero background
  globals.css    tokens + base + hover styles
```

## Status

First slice — the **landing page** (`Етаж`), imported from the Claude Design project and
re-implemented as idiomatic React (the design tool's `<x-dc>` runtime was discarded). It is
static: no auth, no API. The authenticated consoles (resident / manager / firm) and the
generated API client come in later slices, once a Gate-1 screen needs data.

**Auth (ADR-011):** OIDC · the `api` validates JWT and issues nothing · the session is an
httpOnly cookie in this Next.js BFF. Provider deferred (Keycloak marked as the default).

## TODO before launch

- Re-host the hero clip in `HeroVideo.tsx` on a domuvai-owned origin (currently the design
  tool's CDN URL).
- Add the OpenAPI-generated client + a `web` build/lint CI job.
