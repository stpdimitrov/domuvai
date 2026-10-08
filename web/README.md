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

### Against the real API and a database

The whole chain on one machine — Postgres 16, the API (JDK 21), the demo entrance seeded through the API (and a
small second one with a business unit, for the charges screen's multiple — WEB-16):

```bash
brew install postgresql@16
PG="$(brew --prefix postgresql@16)/bin"
"$PG/pg_ctl" -D "$(brew --prefix)/var/postgresql@16" -l "$(brew --prefix)/var/log/postgresql@16.log" start
"$PG/psql" -d postgres -c "CREATE ROLE postgres LOGIN SUPERUSER PASSWORD 'postgres'"
"$PG/createdb" -O postgres domuvai
./gradlew :app:bootRun                        # from the repo root; Flyway migrates the empty database
python3 tools/seed_demo.py                    # prints the entrance id; a database seeded before WEB-18 has no October run — recreate it to get one
cd web && API_URL=http://localhost:8080 npm run dev
```

The role and password are the local defaults in `app/src/main/resources/application.yml`, nothing more.
`npm run dev` always serves the console (see [The console switch](#the-console-switch)). The CI check needs a
production build started twice — switched on, and not. Stop `npm run dev` first: the build and the dev server
share `.next`.

```bash
cd web && npm run build
DOMUVAI_CONSOLE=on DOMUVAI_CONTACT_EMAIL=demo@example.test API_URL=http://localhost:8080 npx next start -p 3001 &
DOMUVAI_CONTACT_EMAIL= npx next start -p 3002 &
DOMUVAI_CONTACT_EMAIL='mailto:nobody@example.test' npx next start -p 3003 &
for port in 3001 3002 3003; do curl -sf -o /dev/null --retry 30 --retry-connrefused --retry-delay 1 "http://localhost:$port/"; done
cd .. && python3 tools/check_e2e.py --api http://localhost:8080 --web http://localhost:3001 \
  --closed-web http://localhost:3002 --bad-contact-web http://localhost:3003 --contact demo@example.test \
  --entrance <id>
```

### The console switch

The console names debtors and what they owe, and there is no sign-in yet (ADR-011), so a server serves it only
where it is switched on (WEB-14, PM-DEBT-011): **`DOMUVAI_CONSOLE=on`** in the server's environment, or
`next dev` — which `npm run dev` binds to `127.0.0.1`, this machine alone. Anywhere else — a production server
without the switch — `web/middleware.ts` answers every path but the landing with a 404 before any route is matched:
the same response for a console page and for a path that does not exist, however it is asked (a load, a client
navigation, a prefetch, a HEAD). Only the build's static files (`/_next/static/`) pass — code and the design's
sample text, never what the API returns. The landing shows no link into a closed console: no `Вход`, and its two
`Започнете безплатно` buttons go to the demo section. Closed is the default, so a screen added later is closed with
the rest. The switch is read from the running server's environment, never baked into the build: one build serves
either way.

Set it in the server's environment, never in a `web/.env*` file: `next start` reads those too, so a file shipped
beside the build would switch the console on. Switch it on only where everyone who can reach the server may see
every name and amount in its database — a developer's machine, CI, a demo seeded with made-up people. Never for
real data before sign-in.

### The demo request

The landing sends a demo request nowhere by itself and stores none, so it never says one arrived (WEB-15, #79).
Where to write is the server's setting: **`DOMUVAI_CONTACT_EMAIL`** in the server's environment, read per request
like the console switch (`lib/contact.ts`); the repository holds no address. With it, the form writes the request
out as a letter the visitor opens in their own mail, or copies — it reaches us when they send it — and the landing
shows the address. Without it, or with a value that is not a plain address, the landing offers no form and says
requests are not taken through the site yet — its "Заявете демо" buttons still lead to that section, so set the
address before the landing is public. Storing a request, or sending it from the server, is the owner's choice and not built.

The address is **`office@newcleardigital.com`** (the owner, 2026-10-01). No deployment exists yet, so nothing sets it
for a public server: set `DOMUVAI_CONTACT_EMAIL=office@newcleardigital.com` in that server's environment when one
does. On a developer's machine put the same line in `web/.env.local`, which git ignores.

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

`.github/workflows/e2e.yml` (E2E-01) runs **the whole chain on every PR**: Postgres → the API from its jar
(Flyway on an empty database) → `tools/seed_demo.py` through the public API → `next start` →
`tools/check_e2e.py`. The check validates every response the live screens use against the contract, reads the
seeded figures off the screens, and fails when a screen calls an operation it does not cover. A second server
from the same build, not switched on, must answer every path in the build's route manifest but the landing — a load,
a client navigation, a prefetch, a HEAD — with the 404 a missing page gets, and its landing must link to none of
them (PM-DEBT-011). It runs on every PR and is a required check on `main`.

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
  consoleSwitch.ts is the console switched on here? (PM-DEBT-011)
middleware.ts      where it is not, every path but the landing answers 404
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
  must never be reachable by the public** (PM-DEBT-011) — served only where the console is switched on (see
  [The console switch](#the-console-switch)), and see TODO before launch.
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

Every other screen is still static (typed mock data). No auth yet.

**Auth (ADR-011):** OIDC · the `api` validates JWT and issues nothing · the session is an
httpOnly cookie in this Next.js BFF. Provider deferred (Keycloak marked as the default).

## TODO before launch

- **Sign-in before anyone else can reach the console** (ADR-011). `/debts` shows debtors' names and what they
  owe, which PM-DEBT-011 forbids in any publicly accessible place. The charges and fund screens show owners,
  amounts and bank accounts. Until then the console is served only where it is switched on (WEB-14), and the
  API — no sign-in either — must not be reachable from the internet: the web calls it from the server only.
- Set `DOMUVAI_CONTACT_EMAIL=office@newcleardigital.com` where the landing is served (see "The demo request"), and
  decide where a demo request goes — a stored lead, a CRM, a mail provider (#79). Until then the visitor sends it
  from their own mail (WEB-15).
- Re-host the hero clip in `HeroVideo.tsx` on a domuvai-owned origin (currently the design
  tool's CDN URL).
- Add a lint step (ESLint is not configured yet).
