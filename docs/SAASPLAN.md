# SaaS plan — ЗУЕС condominium platform

> [!abstract] State
> One product, three doors, thin software over a payments rail. Decided 2026-09-09: sell to all three buyer types because the law is one; monetise fee collection rather than seats; fund a single 90-day **paid** pilot with one професионален домоуправител firm. Built over the rules catalogue (233 rules) and sequenced by the implementation sequence.

> [!todo] Next
> - Week 1: confirm the payments model is legal — can we initiate payments into the building's own account under a partner licence, or do we need our own PISP authorisation from БНБ
> - Week 1: sign one домоуправител firm for a paid 90-day pilot, 8–15 buildings, money changes hands from day one
> - Week 2: counsel on the four blocking legal questions (fund minimum base, чл. 46б deadline, arrears on transfer, sanction ranges)
> - Decide the legal entity and whether Newclear Digital is the vendor or a separate company owns this

## The strategy in one line

The law is one, so the product is one. Only the door changes.

## Three doors, one product

| Door | Who signs | What one signature brings | Legal hook |
|---|---|---|---|
| **A — Professional firm** | Управител-търговец entered in the МРРБ register | 20–80 buildings at once, already digital-ish, has a budget | чл. 19 delegation by >50% of ideal parts |
| **B — Developer** | Инвеститор at handover of a closed complex | A whole complex, pre-populated, before a single owner has an opinion | чл. 2 — notarised contract registered against every unit |
| **C — Building direct** | Elected домоуправител | One building, no budget, huge volume | чл. 9 — general assembly as the form of management |

The domain model is identical across all three. What differs is the contract, the onboarding path and who pays. Do **not** fork the product per door; fork the funnel.

### Sequencing, not simultaneity

All three, but in this order. A funds the company, B is the land-grab, C is the volume that makes A and B defensible.

1. **A first** — cash, density, and the fastest possible feedback on whether the 233 rules match reality.
2. **B second** — long sales cycle, so start it early and let it close while A pays the bills.
3. **C last, self-serve, free or near-free** — never sold by a human. It exists to catch the buildings that fall out of A and B.

## The flywheel the law hands you

> [!important] Structural churn is the growth engine, if the account is the building
> Mandates cap at **2 years** (чл. 19, 21). Firms get fired and rehired constantly. PM-PMC-010 makes a clean handover a legal obligation that commercial disputes cannot block.
>
> So: **the account belongs to the building, not the firm.** When a firm loses a building, the building stays on the platform and shops for a new firm — on your platform. When a self-managed building gives up and hires a firm, you route it to a firm already using you. Competitors who hold buildings hostage by locking data are breaking чл. 23 and PM-PMC-010; you win those buildings by complying harder than they do.
>
> The moat is not lock-in. It is that you are the only party every side trusts to hold the record.

## Money

### The constraint that decides the architecture

чл. 50 ЗУЕС: fund monies are held in a special-purpose account **in the name of the chair of the management board or of the association**. Not in yours. PM-FUND-004 and PM-FUND-005 make commingling a fiduciary breach.

**You therefore cannot be the account holder, and there is no float.** Anyone modelling this as an e-money business has not read чл. 50.

### What you can be

A **payment initiator and reconciler**. Money moves from the resident straight into the building's own account; you initiate it, match it to the unit, and post the ledger entry the manager releases.

- PSD2 payment initiation (PIS) plus account information (AIS) — under a licensed partner first, own БНБ authorisation later if volume justifies it.
- Euro since 1 January 2026 means SEPA instant, no FX, no conversion excuse. The timing is good.
- The AI never touches the rail (PM-AI-008); it proposes, a human releases.

### Revenue lines, in order of how real they are

| Line | Mechanism | Why it works |
|---|---|---|
| **Arrears recovery fee** | Success fee on debt recovered through the dunning ladder and the чл. 410 packet | Arrears run 20–30% of billings. Recovering half of that is worth more to a building than the software costs. Paid out of money that did not exist before. |
| **Per-initiation fee** | A few cents per collected payment, paid by the building | Replaces a касиер walking door to door with cash. Scales with the thing you actually improve. |
| **Floor subscription** | Small per-building monthly, so a quiet building is not free to serve | Covers compliance features that generate no transactions. |
| **Firm tier** | Portfolio features — risk board, SLAs, ЕИСЕС filings, own registration and insurance tracking | The only place seat-based pricing survives. |

> [!warning] The number that kills this
> A 12-flat building paying €15 per flat per month is €180 of billings. A 1% take rate is €1.80. **Take rate alone does not build a company at Bulgarian ticket sizes.** The arrears line is the business; the take rate is the wedge that gets you the data to run it. Validate arrears economics on one real portfolio before building anything else.

## The 90-day paid pilot

One firm. 8–15 buildings. **They pay from day one** — a free pilot tests nothing except politeness.

| Days | Goal | Done when |
|---|---|---|
| **1–15** | One building end to end on paper. Import the book, ideal parts, tariff, arrears. | The fee engine reproduces their own spreadsheet **to the cent**, including children under 6, absences, animals and the business multiplier. If it cannot, the rules are wrong and everything stops here. |
| **16–45** | One real general assembly run through the product. | Notice posted with evidence, quorum ladder handled live, votes on ideal parts, minutes in 7 days, 30-day appeal clock running. A lawyer could not fault the protocol. |
| **46–75** | Money. Payment initiation on 2 buildings, reconciliation, dunning ladder, one чл. 410 packet exported. | Collection rate on those two buildings beats their portfolio average. |
| **76–90** | Portfolio. All buildings loaded, plus the firm's own compliance — register entry, insurance, ЕИСЕС, monthly reports. | The firm's risk board is green and they can see which building would have bitten them. |

### Do not build in these 90 days

Resident chat, mobile apps, a vendor marketplace, accounting integrations, any second country, and any AI capability above `HUMAN_RELEASE`. The pilot tests the ledger and the assembly, nothing else.

### Exit criteria

- They would pay **3× the pilot price** to continue, unprompted.
- The fee engine matched their spreadsheet to the cent on every building.
- At least one lawful GA ran end to end in the product.
- Collection rate improved measurably on the payment-enabled buildings.
- They will introduce you to a second firm.

## Metrics that matter

Collection rate · days-to-collect · arrears ageing · % buildings compliant (filings on time, GA held, fund adequate, mandate current) · manager hours per building per month · draft-acceptance rate per AI capability (PM-AI-014).

Not: MAU, logins, page views.

## Kill criteria

> [!danger] Stop if
> - Payment initiation into the building's own account turns out to need a licence you cannot get or partner for — the thin-software model has no revenue without it
> - Residents keep paying the касиер in cash and refuse the rail — take rate goes to zero and you are selling a compliance tool at spreadsheet prices
> - The firm's existing spreadsheet already computes fees correctly and they will not pay for compliance — the thesis was that the law is the pain; it would be wrong

## Open risks

> [!warning] The 24 unverified rules gate the ledger
> The fund minimum base, the чл. 46б deadline, arrears on transfer and the euro sanction ranges all touch money or filings. Get counsel before Phase 2 of the build sequence, not after.

> [!warning] No named firm yet
> Everything above is a plan until a домоуправител firm has signed. The single highest-value action this week is that signature, not more architecture.
