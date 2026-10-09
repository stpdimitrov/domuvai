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
npm test         # the web's own tests — Node's test runner, no package (lib/**/*.test.ts)
```

Live screens call `api` from this Next.js server (never from the browser), at `API_URL`
(default `http://localhost:8080`). With the backend down, a live screen says so instead of failing.

### Against the real API and a database

The whole chain on one machine — Postgres 16, the API (JDK 21), the demo entrance seeded through the API (and a
small second one with a business unit, for the charges screen's multiple — WEB-16):

```bash
brew install postgresql@16
PG="$(brew --prefix postgresql@16)/bin"
"$PG/pg_ctl" -D "$(brew --prefix)/var/postgresql@16" -l "$(brew --prefix)/var/log/postgresql@16.log" start
"$PG/psql" -d postgres -c "CREATE ROLE postgres LOGIN SUPERUSER PASSWORD 'postgres'"
"$PG/createdb" -O postgres domuvai
DOMUVAI_AUTH=off ./gradlew :app:bootRun       # from the repo root; Flyway migrates the empty database. Off in so many
                                              # words: the api does not start without sign-in configured (ADR-011)
python3 tools/seed_demo.py                    # prints the entrance id; a database seeded before WEB-18 has no October run — recreate it to get one
cd web && API_URL=http://localhost:8080 npm run dev
```

The role and password are the local defaults in `app/src/main/resources/application.yml`, nothing more.
`npm run dev` always serves the console (see [Who the console is served to](#who-the-console-is-served-to)). The CI
check needs a production build started twice — told to run without sign-in, and not. Stop `npm run dev` first: the build and the dev server
share `.next`.

```bash
cd web && npm run build
DOMUVAI_AUTH=off DOMUVAI_CONTACT_EMAIL=demo@example.test API_URL=http://localhost:8080 npx next start -p 3001 &
DOMUVAI_CONTACT_EMAIL= npx next start -p 3002 &
DOMUVAI_CONTACT_EMAIL='mailto:nobody@example.test' npx next start -p 3003 &
DOMUVAI_AUTH_ISSUER=http://localhost:9/realms/domuvai DOMUVAI_AUTH_CLIENT_ID=domuvai-web DOMUVAI_AUTH_CLIENT_SECRET="$(openssl rand -hex 24)" \
  DOMUVAI_SESSION_SECRET="$(openssl rand -hex 32)" DOMUVAI_WEB_URL=http://localhost:3004 npx next start -p 3004 &   # sign-in configured, nobody signed in
for port in 3001 3002 3003 3004; do curl -sf -o /dev/null --retry 30 --retry-connrefused --retry-delay 1 "http://localhost:$port/"; done
cd .. && python3 tools/check_e2e.py --api http://localhost:8080 --web http://localhost:3001 \
  --closed-web http://localhost:3002 --bad-contact-web http://localhost:3003 --signin-web http://localhost:3004 \
  --contact demo@example.test \
  --entrance <id>
```

### Who the console is served to

The console names debtors and what they owe (PM-DEBT-011), so a server serves it to a person who is signed in and to
nobody else (AUTH-03, ADR-011). `web/middleware.ts` decides, before any route is matched, from the running server's
environment — never baked into the build, so one build serves any of these ways:

- **Sign-in configured** — all five settings of [Sign-in](#sign-in). Every path but the landing and the three
  sign-in routes needs a session: a page asked for without one sends the person to sign in and back to it; anything
  else asked without one (a client navigation, a prefetch, a HEAD) is a 401.
- **Not configured** — closed. Every path but the landing answers with a 404: the same response for a console page
  and for a path that does not exist, however it is asked. The landing shows no link into a closed console: no
  `Вход`, and its two `Започнете безплатно` buttons go to the demo section. A setting missing or malformed is not
  configured; a deployment that loses its settings stays shut.
- **`DOMUVAI_AUTH=off`** — the console served with no sign-in, because the server is told so in so many words: the
  api's word and the api's rule. `next dev` — which `npm run dev` binds to `127.0.0.1`, this machine alone — runs
  this way with nothing set. `off` beside any sign-in setting is closed: one or the other.

Only the build's static files (`/_next/static/`) pass unasked — code and the design's sample text, never what the
API returns. A screen added later is behind the same rule; there is no list of console paths to keep.

Set these in the server's environment, never in a `web/.env*` file: `next start` reads those too, so a file shipped
beside the build would open the console. `DOMUVAI_AUTH=off` only where everyone who can reach the server may see
every name and amount in its database — a developer's machine, CI, a demo seeded with made-up people. Never for
real data. (`DOMUVAI_CONSOLE`, the switch before sign-in, is gone and opens nothing.)

### The demo request

The landing sends a demo request nowhere by itself and stores none, so it never says one arrived (WEB-15, #79).
Where to write is the server's setting: **`DOMUVAI_CONTACT_EMAIL`** in the server's environment, read per request
like the sign-in settings (`lib/contact.ts`); the repository holds no address. With it, the form writes the request
out as a letter the visitor opens in their own mail, or copies — it reaches us when they send it — and the landing
shows the address. Without it, or with a value that is not a plain address, the landing offers no form and says
requests are not taken through the site yet — its "Заявете демо" buttons still lead to that section, so set the
address before the landing is public. Storing a request, or sending it from the server, is the owner's choice and not built.

The address is **`office@newcleardigital.com`** (the owner, 2026-10-01). No deployment exists yet, so nothing sets it
for a public server: set `DOMUVAI_CONTACT_EMAIL=office@newcleardigital.com` in that server's environment when one
does. On a developer's machine put the same line in `web/.env.local`, which git ignores.

## Sign-in

The web signs a person in at the realm in `infra/keycloak/` by the authorization-code flow with PKCE (S256) —
written by hand on Web Crypto and `fetch`, no package (ADR-011, AUTH-03).

Five settings, all or nothing, in the server's environment:

| Setting | What |
|---|---|
| `DOMUVAI_AUTH_ISSUER` | the realm's issuer URL — the value the api is given |
| `DOMUVAI_AUTH_CLIENT_ID` | the web's client in the realm: `domuvai-web` |
| `DOMUVAI_AUTH_CLIENT_SECRET` | that client's secret, read from Keycloak's admin console — never committed |
| `DOMUVAI_SESSION_SECRET` | what the session cookie is sealed under: 32 characters or more, random (`openssl rand -hex 32`). Changing it signs everyone out |
| `DOMUVAI_WEB_URL` | this site's own address, with no path. Every address a person is sent to is built from it, never from a request |

The issuer and the web's address are https; http is accepted for this machine only.

- `/auth/login?return=<path>` begins a sign-in: the browser goes to the issuer, keeping — sealed, httpOnly, for ten
  minutes and one answer — the state, nonce and PKCE verifier the answer will be held to.
- `/auth/callback` finishes it: the state compared before the issuer is asked anything, the verifier sent with the
  code, the ID token checked (signature, issuer, audience, expiry, nonce). Then the person goes to the path they
  asked for, if it is a path on this site, else to `/portfolio`. A refusal is logged with its reason — which names
  no token — and the browser is told only that sign-in failed.
- **The session** is a sealed cookie (AES-256-GCM): httpOnly, SameSite=Lax, and `Secure` with the `__Host-` prefix
  wherever the web is https. It holds the access and refresh tokens; no page's script can read it. `lib/api/client.ts`
  passes the access token to the api as a bearer, on the server. Middleware renews a session whose access token is
  about to run out; refused by the issuer is signed out.
- **Sign-out** is the `Изход` button in the console's footer: a POST to `/auth/logout` from this site's own pages.
  The cookie is taken back and the issuer is told, from this server, to end its own session.
- **Who is signed in** stands beside it: the name the issuer gives the person, and what the API answers this
  request's own token (`GET /api/identity/me`) — the login it read, and whether that login is tied to a registered
  party (`без лице в книгата` when it is tied to none, which is every login until AUTH-04 can tie one).

`lib/auth/`: `settings.ts` (the settings, the return path) · `oidc.ts` (begin, finish, renew, end) · `idToken.ts` ·
`seal.ts` · `gate.ts` (who is served what; the cookies) · `party.ts`, `server.ts` (the wiring). `*.test.ts` run them
against `fakeIssuer.ts`, which signs real tokens and checks the verifier as an issuer does.

## The API client

`lib/api/schema.d.ts` is **generated** from the contract (`docs/api/openapi.json`, itself generated
from the Kotlin controllers — ADR-013) by `openapi-typescript`; never edit it. `lib/api/client.ts` is the
typed `openapi-fetch` client, marked `server-only`. A path, parameter or body the API does not accept
is a type error. Every call gives up after 10 seconds, so one hung call cannot hold a page — the screen says the
backend did not answer. When the backend changes the contract, run `npm run gen:api` and commit the result.

## CI

`.github/workflows/web.yml` runs on every PR and `main` push that touches `web/` or the contract:
`npm ci`, then **the generated client must match the contract** (`gen:api` + `git diff --exit-code`),
then `npm run build` (strict type-check of every use). A breaking API change fails here, not in
production (ADR-011 §2.2). It is folder-scoped (ADR-011), so it does not run when neither changes — **do not make it a required status check**: a required
check that never runs blocks the merge.

`.github/workflows/e2e.yml` (E2E-01) runs **the whole chain on every PR**, signed in: Postgres → the API from its jar
(Flyway on an empty database) → `tools/seed_demo.py` through the public API → a real Keycloak that imports
`infra/keycloak/domuvai-realm.json` → the API restarted **closed** against it → `next start` → `tools/check_e2e.py`.
The check signs in as a person does — through Keycloak's own form — and holds an httpOnly cookie and no token; then
it validates every response the live screens use against the contract, reads the seeded figures off the screens,
and fails when a screen calls an operation it does not cover; the console's strip must carry the login the API read
from the web's bearer; sign-out must end the session at the web and at Keycloak. Before signing in, every path in
the build's route manifest must send a load to sign in and answer any other way of asking with a 401 — a forged
session cookie counting for nothing — and the build must hold no prerendered page. A second server from the same
build, with nothing set, must answer every such path with the 404 a missing page gets, and its landing must link to
none of them (PM-DEBT-011). The client's secret, the person and the password are made in that run; none is in the
repository. It runs on every PR and is a required check on `main`.

On a machine with no issuer the check runs as in [Against the real API and a database](#against-the-real-api-and-a-database):
the web told to run without sign-in, the API open — and it says that sign-in itself was not checked.

## Layout

```
app/
  layout.tsx       root layout, <html lang="bg">, fonts (Literata + IBM Plex Sans/Mono)
  page.tsx         the landing (Етаж) at /  — rendered per request: it links into the console only where it is served
  HeroVideo.tsx    client component: the boomerang hero background
  globals.css      tokens + base + hover styles
  (console)/       the manager console — a route group (no URL segment)
    layout.tsx     the console flex shell + console.css (sidebar differs by context)
    console.css    console shell, table, timeline and card styles
    (firm)/            firm-wide context
      layout.tsx       firm sidebar + main
      FirmSidebar.tsx  firm navigation (client; active from the path)
      portfolio/
        page.tsx       /portfolio — every entrance with what it owes and its fund (live)
    entrance/          single-entrance context
      layout.tsx       entrance sidebar + main
      EntranceSidebar.tsx  entrance navigation (client)
      page.tsx         /entrance — the entrance: overdue book declarations, its accounts (live)
lib/
  api/schema.d.ts  GENERATED from docs/api/openapi.json — never edit
  api/client.ts    the typed, server-only client for `api`
  console.ts       what the live entrance screens share: the entrance from `?entrance=` (else the
                   first by name), a call that may find the backend down, euros from minor units
  auth/            sign-in, and who the console is served to (PM-DEBT-011) — see Sign-in
middleware.ts      the console to a signed-in person only; closed where sign-in is not configured
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
- **The console's footer** (WEB-22, PM-SYS-010) — every screen under the console's layout ends in the rule
  catalogue's version and the engine's, read from `GET /api/law/version` on each request
  (`app/(console)/CatalogueFooter.tsx`); the web keeps no copy, and says so when the API does not answer.
  `/assembly` sits outside that layout and has no footer.
- **`/portfolio`** — the console's firm-wide portfolio (screen 01) — **live** (WEB-19): every registered
  entrance with its number of units, what it owes after the advances as of `?asOf=` (default today in Sofia;
  `GET …/arrears`, `netMinor`), and its repair fund's balance beside what is available (`GET …/fund`; "няма
  сметка" when it has no fund account). The fund's figures are today's, whatever the date. Largest owed first.
  `?show=owing` and `?show=fund` (a fund below zero) filter. The design's other columns — overdue statutory
  tasks, the next deadline, the collection rate, the mandate's end, the risk — show a dash until a backend
  serves them, and the screen adds no firm-wide total of its own. A name links to the entrance, what is owed
  to `/debts`, the fund's balance to the entrance's fund screen.
- **`/debts`** — firm-wide arrears (screen 05 Задължения) — **live** (WEB-13): every entrance's debtors
  from one arrears read per entrance (`GET …/arrears?asOf=`), joined to its units and to their owners on the
  read date — what each has unpaid, the advance it holds and what it owes after it (WEB-18), the entrance's
  total after the advances, and the oldest debt's days overdue, as `money` computes them; the screen subtracts
  nothing itself. A unit whose advance covers all it has unpaid stays listed, marked as covered, and is not
  counted as owing or overdue. `?asOf=YYYY-MM-DD` (default: today, Europe/Sofia). An entrance where nothing is
  unpaid is left out. The
  design's interest, escalation ladder (Покана → Нотариална → Решение на ОС → Заповед) and next action are not
  built — those columns show `—`, and the two buttons stay disabled. **It names debtors and their debts, so it
  must never be reachable by the public** (PM-DEBT-011) — served only to a person who is signed in (see
  [Who the console is served to](#who-the-console-is-served-to)), and see TODO before launch.
- **`/compliance`** — the firm's regulatory standing (screen 07 Съответствие): register / insurance /
  management-contract status cards, and a filings-and-declarations table.
- **`/entrance`** — a single entrance (screen 02) — **live** (WEB-20): `?entrance=<id>` (default: the first
  by name), its units by kind and its management form; in the calendar panel, who is past the deadline to
  declare for the book, with the registry's own due date (`GET …/book/declarations/overdue`, names only) —
  the only statutory deadline a backend serves yet, and the panel says so; the operating account (what was
  paid in, never a balance) and the repair fund (balance, available, committed), or that the entrance has
  none. Manager, mandate and last assembly show a dash, and the next-assembly card says assemblies are not kept yet. The screen counts no
  days overdue and adds up no ideal parts. Uses the **entrance** sidebar, whose entrance links — like the
  screen's tabs — carry `?entrance=` on.
- **`/entrance/charges`** — the monthly charge run (screen 03 Начисления) — **live** (WEB-11): the
  engine's preview (`POST …/charge-runs/preview`) joined server-side to the entrance's units and
  owners. `?period=YYYY-MM` (default: this month) and `?entrance=<id>` (default: the first
  registered). Hover an amount for its derivation. The basis is a visibly labelled **demo** — the
  assembly module does not serve GA decisions yet — so confirming is disabled. The design's
  exemptions, coefficient and elevator columns show `—`: the API has no field for them yet.
  `?multiple=<n>` is the assembly's multiple for business use (WEB-16, PM-FEE-010): the screen holds
  no figure and the demo basis has none — a field on the screen takes it, the API decides whether it
  is in range, and a run refused for want of it is shown as refused, with the field.
- **`/entrance/fund`** — cash & repair fund (screen 06 Каса и фонд) — **live** (WEB-12): the fund's
  account (IBAN and holder, чл. 50 ЗУЕС) with balance, committed and available side by side
  (`GET …/fund`); every disbursement signed off against it — purpose, basis (the GA decision, the
  passport measure, or the emergency and its justification), amount, committed / paid / cancelled,
  filtered by `?status=`; and the fund's handover statements as issued (`GET …/fund/handover-statements`)
  — never called signed: the parties' signatures are not recorded yet. `?entrance=<id>` as on the
  charges screen. Read-only: signing off, paying out, cancelling and issuing a handover need sign-in.
  The operating account's card (`GET …/operating-account`, WEB-17) shows what was paid into it —
  named so, never a balance, while the API says outflows are not recorded — with `—` for committed
  and available, which the API does not serve. Below, the entrance's **journal** for a month
  (`GET …/journal`; `?period=YYYY-MM`, this month by default): each journal's date, what wrote it,
  and its debits and credits by account — legs of one journal on one side of one account shown as one line —
  narrowed to the fund's account, the operating one or the cash box by `?account=fund|operating|cash`.
  The API serves no description or document number, so none is shown. The operating account, the
  statements and the journal each fail alone — the fund stays on screen.
- **`/assembly`** — the live general assembly (screen 04 Общо събрание): a full-bleed, no-sidebar
  view — session-quorum banner, agenda, the item being voted (quorum + tally with the majority
  threshold), and live attendance with proxies. Has its own stylesheet (`assembly.css`).

The console has two navigation contexts — the **firm** sidebar (`(firm)/`) and the **entrance**
sidebar (`entrance/`) — as sibling nested layouts under one flex shell. A handful of nav items
(Обекти, Календар на сроковете, Доставчици, Документи, Екип, …) remain placeholders — screens not
in the imported design.

Every other screen is still typed mock data. None is prerendered: a console page is rendered for the signed-in
person who asked (see [Sign-in](#sign-in)), and CI fails on a prerendered one.

**Auth (ADR-011):** OIDC · the `api` validates JWT and issues nothing · the session is an
httpOnly cookie in this Next.js BFF. The provider is Keycloak.

## TODO before launch

- **Permissions before real residents' data** (ADR-011, AUTH-04). The console is served to a signed-in person only
  (AUTH-03), and `/debts` shows debtors' names and what they owe (PM-DEBT-011) — but until AUTH-04 anyone who can
  sign in sees every entrance. Run the api closed (`DOMUVAI_AUTH_ISSUER`, `DOMUVAI_AUTH_AUDIENCE`) wherever the
  web signs in, and never `DOMUVAI_AUTH=off` on either beside real data.
- Set `DOMUVAI_CONTACT_EMAIL=office@newcleardigital.com` where the landing is served (see "The demo request"), and
  decide where a demo request goes — a stored lead, a CRM, a mail provider (#79). Until then the visitor sends it
  from their own mail (WEB-15).
- Re-host the hero clip in `HeroVideo.tsx` on a domuvai-owned origin (currently the design
  tool's CDN URL).
- Add a lint step (ESLint is not configured yet).
