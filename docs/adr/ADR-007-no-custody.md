# ADR-007 — The platform never holds money

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov · counsel on the licensing route in §2 |
| **Supersedes** | — |
| **Blocks** | The `rail` module, A4 licensing, the arrears-recovery line, every price on the sheet |

---

## 1 Context

The most commercially consequential decision in the project. One article settles it.

**чл. 50 names the account holder, and it is not us.** PM-FUND-004: fund monies sit in a **special-purpose bank account** in the name of the chair of the management board — or of the association — never the operating account. That holder owes a fiduciary duty to one building; a software vendor cannot be one.

**Pooling is a breach, not an optimisation.** PM-FUND-005 forbids commingling fund monies with a management company's own funds *or with another building's*. PM-PMC-008 requires each building's money held separately and rejects inter-building transfers at the ledger layer. A pooled client account is both failures at once.

**So there is no float.** Not a float we decline to earn on — no balance the platform controls, at any moment. Anyone modelling this as an e-money business has not read чл. 50.

**Release is already a two-person act.** PM-FUND-007: payment out is authorised by the chair on a GA decision — decision reference *plus* authorised signatory. PM-AI-008: the agent may only propose a draft, and holds **no write credential on any ledger or rail**.

What remains: initiate and reconcile, never hold — under PSD2, payment initiation (PIS) with account information (AIS), touching no client funds.

---

## 2 Decision

**No condominium money rests in an account the platform controls. The platform initiates and reconciles into the entrance's own чл. 50 account.**

| Step | Who acts | Platform's part |
|---|---|---|
| Pay | Resident, from their own bank | Initiates (PIS) to the entrance's stored IBAN; funds never route through us |
| Land | The entrance's чл. 50 account | Nothing. No balance, ever |
| Match | Platform | Reconciles the credit (AIS) to unit and charge, with `basis` and `law_version` |
| Post | Platform proposes | A **draft** entry. PM-AI-008 — the agent cannot release it |
| Release | Chair or authorised signatory | Two-factor per PM-FUND-007 |

**Licensing route.** Ship under a licensed partner's PIS/AIS permissions; take our own БНБ authorisation only if volume justifies it. Neither involves custody, so the design is identical.

**Revenue, stated once.** Payments revenue is the per-initiation fee and the arrears-recovery success fee — no interest, float or settlement-timing line. The floor subscription and firm tier carry the rest.

---

## 3 Options considered

| Option | What it would require | Verdict |
|---|---|---|
| **A · Hold funds as e-money** | An EMI licence, safeguarding, a balance in our name | **Rejected.** Illegal under чл. 50 before it is expensive: PM-FUND-004 names the chair or the association, and no drafting makes a vendor either |
| **B · Pooled client account with sub-ledgers** | One collection account, segregation in our books | **Rejected.** Commingling with another building's funds is precisely PM-FUND-005. PM-PMC-008 wants separation in the *account*; a sub-ledger is the mechanism, not the defence |
| **C · Initiation into the entrance's own account** ✅ | PIS + AIS, a stored IBAN, reconciliation of partial and mismatched credits | **Chosen.** Money moves resident → entrance: no custody, no safeguarding, no float, and the чл. 50 account stays where the statute put it |
| **D · Bookkeeping only, no payments** | Nothing regulated | **Rejected** — it deletes the revenue that funds the build. Recorded honestly: it is the fallback if licensing fails. The product survives; the pricing does not |

A and B fail on law, not on cost. D fails on commerce.

---

## 4 Consequences

**Good**

- The regulatory surface is initiation and data access: no safeguarding, no client-money audit, no insolvency risk.
- A building that leaves takes its account with it. ADR-005's offboarding costs nothing to honour.
- PM-FUND-005 and PM-PMC-008 are satisfied structurally: no account exists from which a cross-building transfer could be built.

**Bad, and accepted**

- **No float income, ever.** The largest revenue line we are deliberately not building. It is not ours to earn.
- Reconciliation is harder without control of the credit; unmatched and partial payments are a permanent cost on `rail`.
- Partner dependency until own authorisation — which is why D is recorded, not dismissed.

**How it is enforced**

1. No code path may create or control an account holding condominium money.
2. `rail` may initiate **only** to an entrance IBAN stored with holder and mandate per PM-FUND-004, never to the operating account.
3. A test asserts no transfer can be constructed between two entrance accounts (PM-PMC-008), rejected at the ledger layer, not in the UI.
4. A posting from an initiation is created `DRAFT`, released only on decision reference plus signatory (PM-FUND-007); the agent role holds no write scope on ledger or rail (PM-AI-008).
5. A change to this design is a **counsel gate**, not a code review: the regulatory answer follows the money flow.

---

## 5 What would reverse this

Only a change to who may lawfully hold the account:

1. A statutory amendment to чл. 50 permitting a third party to hold fund monies — and
2. a licensing case that pays for itself.

The second without the first is irrelevant. An **owners' association** as holder — the alternative PM-FUND-004 already permits — changes *who* holds the account, not *whether we do*: an account-holder type, not a new ADR.

---

## 6 Deferred

- Which licensed partner, and on what terms — A4. The decision is partner-agnostic.
- Whether own БНБ authorisation is worth taking, and at what volume.
- Inbound credits that never match a unit.
