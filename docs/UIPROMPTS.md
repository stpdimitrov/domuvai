# Claude Design prompts

Two prompts. Paste one at a time into Claude Design. Each is self-contained — Claude Design does not have the project context, so everything it needs is in the prompt.

---

## Prompt 1 — Mobile app, mobile-first

```
Design a mobile-first app for managing a Bulgarian apartment building under
етажна собственост (condominium law). Portrait phone artboards, 390×844.

WHO USES IT
1. A resident or owner. Often elderly. Opens it a few times a month, usually to
   see what they owe and pay it. Not technical. May be using a five-year-old
   Android phone.
2. A домоуправител — a neighbour, unpaid, elected for two years, who has to run
   the building lawfully and has no training. Opens it weekly.

THE UI IS IN BULGARIAN. Use real Bulgarian labels, not English placeholders.
Amounts are in euro (Bulgaria adopted the euro on 1 January 2026), written as
€24,80 with a comma decimal.

DRAW THESE ARTBOARDS

Resident:
1. Начало — one card showing what I owe this month and a large primary "Плати"
   button; below it, the next building assembly with a countdown in days; below
   that, two or three recent notices.
2. Моята сметка — the bill itemised, and crucially a plain-language explanation
   of HOW it was calculated: "поддръжка — 3 души × €6,20", "дете под 6 г. —
   не се брои", "куче — брои се като един обитател", "ремонтен фонд — 4,20% от
   идеалните части". This screen exists because residents constantly dispute
   bills and the manager has to explain the maths by hand.
3. Плащане — confirm amount, choose bank, one tap. Money goes to the building's
   own account; the app never holds it.
4. Общо събрание — the notice, the agenda as a list, and a vote control for the
   item being voted. Show a live quorum meter as a horizontal bar with three
   marks at 51%, 26% and "any" — Bulgarian law reconvenes the same meeting up to
   three times at falling thresholds, and people need to see which session
   they're in.
5. Сигнал за повреда — take a photo, pick a place (стълбище, асансьор, покрив),
   two lines of text, send. Then the same screen showing status.
6. Документи — protocols and monthly reports as a simple dated list.

Домоуправител:
7. Табло — a list of statutory deadlines with days remaining, colour-coded.
   Examples: "Протокол от ОС — остават 3 дни", "Подаване в общината — просрочено
   с 2 дни". This is the single most valuable screen in the product: missing one
   of these is a fine.
8. Такси за месеца — run the monthly charges, see the total, one confirm.
9. Задължения — who owes, how long, and an escalation button.

DESIGN CONSTRAINTS
- Accessibility is a requirement, not a nice-to-have: minimum 17px body text,
  tap targets at least 48px, WCAG AA contrast. Assume 70-year-old eyes.
- One primary action per screen. No screen should offer two equally weighted
  buttons.
- Numbers use tabular figures and align on the decimal.
- Deadlines are shown as "остават 3 дни", never as a raw date alone.
- No dashboards with six tiles. This is not an analytics product.
- Bottom tab bar, four items maximum.
- Design light and dark.

DO NOT
- Do not use a card with a rounded corner and a coloured left rail for every
  block. Spend borders and shadows only where something must be separated.
- No hero illustrations, no onboarding carousel, no emoji as icons.
- Do not invent numbers that look like legal thresholds. Use the ones above.

Give me a colour palette and type pairing that suits a serious civic utility —
something a pensioner and a property manager both trust. Not a fintech gradient,
not a startup pastel.
```

---

## Prompt 2 — Desktop web app

```
Design the desktop web application for a Bulgarian condominium management
platform, used by staff at a professional property management firm
(професионален домоуправител). Artboards at 1440×1024.

WHO USES IT
A property officer at a firm that manages 40–80 buildings. They are at a desk
all day, in this tool constantly, and their job is to make sure no building
misses a legal deadline and every building's money reconciles. Power user.
Density is a feature, not a problem.

THE UI IS IN BULGARIAN. Amounts in euro, written €1.240,50.

KEY DOMAIN FACTS THAT SHAPE THE SCREENS
- The unit of everything is an "вход" (entrance), not a building. One building
  can hold several entrances, each with its own assembly, manager and bank
  account. Never blend two entrances' money.
- Every entrance has statutory deadlines running against it at all times.
- A general assembly is one meeting with up to three sessions at falling quorum
  thresholds: 51%, then 26% an hour later, then any share the next day.
- Votes are weighted by "идеални части" — each apartment's share of the common
  parts, to six decimals, summing to exactly 100%.

DRAW THESE ARTBOARDS

1. Портфейл — the portfolio screen. A dense table of every entrance, one row
   each, with columns for: overdue statutory tasks, next deadline, collection
   rate, arrears, fund balance, mandate expiry. Sortable. The top of the screen
   answers one question: which building will bite me this month. Use a severity
   chip, not just a number, so risk reads at a glance.
2. Вход — drill-down on one entrance. Left rail navigation, main area showing
   the статутен календар (statutory calendar) as a vertical timeline of what is
   due and what is late, with the legal article cited next to each item.
3. Начисления — the monthly charge run. A table of units with the calculation
   basis visible per row: number of people, exemptions applied, multiplier for
   commercial premises, ideal parts for the fund portion. A confirm bar at the
   bottom with the total. This must look auditable, because it is.
4. Общо събрание — running a live assembly. Left: the agenda. Centre: the item
   being voted, with a large live quorum and result readout showing percentage
   of ideal parts, and explicitly which denominator is being used (all ideal
   parts, or only those represented). Right: attendance list with proxies
   marked. Include the state where the first session failed quorum and the
   second is open at 26%.
5. Задължения — arrears by entrance with an escalation ladder: reminder →
   formal notice → assembly decision → court payment order. Show where each
   debtor sits on that ladder.
6. Каса и фонд — the ledger for one entrance. Double-entry, with the repair fund
   as a separate account and its own IBAN shown. A clear "available" figure that
   is not the same as the balance.
7. Съответствие на фирмата — the firm's own compliance: register entry expiry,
   professional liability insurance validity, filings submitted.

DESIGN CONSTRAINTS
- Information design over decoration. This screen is operated, not read.
- Semantic colour for state (ok / due soon / overdue) kept separate from the
  brand accent.
- Tabular numerals everywhere money or percentages appear.
- Every table row is scannable in one line; no wrapping.
- Persistent left navigation; entrance context always visible in the header, so
  a user never acts on the wrong building.
- Design light and dark.

DO NOT
- No big-number KPI tiles across the top. The portfolio table is the summary.
- No sidebar full of icons without labels.
- Do not soften it into a consumer app. These people want density and speed.

Give me a palette and type system that reads as a professional legal-financial
tool: precise, quiet, and legible at small sizes in a dense table.
```

---

## How to use them

Run prompt 1 first. Whatever palette and type pairing it produces, paste that into prompt 2 so both surfaces share one system. Then ask for a component sheet — buttons, chips, table rows, quorum meter, deadline row — as the handoff to the developers.
