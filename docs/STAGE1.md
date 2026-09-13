# Stage 1 — Architecture & Requirements Baseline

**Version 1.0 · 10 September 2026 · status: baseline for build**

This is the single document a team starts from. Everything needed to begin building a production system: what it is, what the words mean, what the services are, what they promise each other, and what has to be true before code is merged.

> [!warning] What this replaces
> Where any earlier note disagrees with this one, **this one wins**.

| Document | Status |
|---|---|
| `Property Management App - Architecture` | **Superseded.** It described a modular monolith with three deployables. The system is microservices, twelve deployables. |
| `ЗУЕС Service Blueprint` (artifact) | **Current.** The three diagrams are the picture of this document. |
| `Property Management App - SaaS Plan` | **Current.** Business model and pilot gates, unchanged. |
| `Property Management App - ЗУЕС Build Sequence` | **Current, needs one edit.** Its "five decisions" become the nine in §8. |
| `Property Management App` (project note) | **Wrong.** Assumption A1 says residential lettings. This system is етажна собственост. |
| `ЗУЕС Rules Catalogue` | **Current.** 228 rules — the requirements source for everything below. |

---

## 1. The product in one page

A platform that runs Bulgarian condominium buildings lawfully. The law (ЗУЕС) is the specification; the 228-rule catalogue is that law expressed as testable requirements.

| | |
|---|---|
| **Users** | An unpaid elected домоуправител running one building, and professional management firms running portfolios. Same product, two modes. |
| **Surfaces** | **Mobile-first.** A resident-and-manager PWA on phones, and a desktop console for firm staff. Two apps, one design system, one API. |
| **Sold through** | Three doors, one product: management firms (чл. 19 delegation), developers at handover (чл. 2), buildings direct (чл. 9). |
| **Revenue** | Thin software over a payment rail. Arrears recovery is the business; the take rate is the wedge. |
| **Never** | Holds client money. чл. 50 puts the fund account in the chair's name. We initiate; we do not custody. |
| **First milestone** | A 90-day paid pilot with one firm, 8–15 buildings, four gates. |

---

## 2. Semantics — the words, and the ones that are banned

Every identifier in code, every API field and every event uses these terms. No synonyms.

| Term (BG) | Term (code) | Means |
|---|---|---|
| етажна собственост | `Condominium` | A building under the condominium regime |
| вход | `Entrance` | One entrance. **The tenant boundary.** May have its own assembly, manager, and fund account |
| самостоятелен обект | `Unit` | An apartment, shop or office with its own title |
| идеални части | `IdealParts` | A unit's share of the common parts, `numeric(9,6)`, summing to exactly 100.000000 per entrance |
| собственик | `Owner` | Holds title. Votes |
| ползвател | `Holder` | Right of use. Votes where the owner assigned it |
| обитател | `Occupant` | Lives there without title. No vote, pays maintenance |
| домоуправител / управител | `Manager` | The elected executive, a natural person |
| управителен съвет | `Board` | Odd number, minimum three |
| контрольор | `Controller` | Audits the cashbox once a year |
| общо събрание | `Assembly` | The general assembly. **One assembly may have up to three sessions** |
| протокол | `Protocol` | The minutes. A legal document, not a log entry |
| правилник за вътрешния ред | `HouseRules` | Internal rules, versioned |
| сдружение на собствениците | `Association` | A registered legal person, for grant programmes |
| фонд "Ремонт и обновяване" | `RenewalFund` | The repair fund. Its own IBAN, in the chair's or association's name |
| професионален управител | `ManagingFirm` | A trader in the МРРБ register. **Not a tenant — a grantee** |
| закрит комплекс | `ClosedComplex` | Managed by a registered developer contract under чл. 2 |

### Banned words

| Banned | Use instead | Why |
|---|---|---|
| `tenant` | `Entrance` for the isolation unit, `Occupant` for the person | Two different meanings collide. This rename in month four costs a schema migration |
| `building` (as tenant key) | `Entrance` | A building may hold several independent entrances |
| `user` (as domain term) | `Owner`, `Holder`, `Occupant`, `StaffMember` | The statute distinguishes them and gives them different rights |
| `fee` | `ManagementCharge`, `MaintenanceCharge`, `FundContribution` | Three legal streams with different allocation keys |
| `balance` | `LedgerBalance` (derived) | Never a stored mutable column |

A lint rule fails the build on the banned words.

---

## 3. Requirements source

`docs/rules.json` — 228 rules, 16 domains, catalogue version 1.2, legal baseline 3 Sep 2026.

| Binding | Meaning |
|---|---|
| **MUST / MUST NOT** | Statutory. No discretion |
| **SHOULD** | Product default, overridable by configuration |
| **MAY** | Configurable option |
| `verified: false` (24 rules) | The number is unconfirmed. Implement the mechanism, read the value from configuration, emit `TODO(legal)`. Never guess |

Rules bind code three ways: every implementing function carries `// Rule: PM-XXX-000`; every rule with an acceptance criterion has a test named after its ID; CI fails if a rule has no test.

---

## 4. Deployables

Thirteen: one edge, one read layer, eleven services. Each owns its own database. No service reads another's tables.

### 4.0 gateway — the edge

- **Purpose** — turn a person into an `entrance_id` before any service sees the request.
- **Does** — authentication, resolves the caller's active grants, mints a short-lived token carrying `entrance_id` and role, rate limits, writes the access audit record.
- **Never** — holds domain data.
- **Rules** — PM-SEC-001, PM-SEC-002.


### 4.0a bff — read models for the phone

- **Purpose** — a phone on 3G cannot call five services to paint a home screen. The BFF keeps per-screen read models, built from events, and serves one request per screen.
- **Owns** — projections only. No domain truth, so a stale projection can never block a write.
- **Publishes** — nothing.
- **Consumes** — every event, to rebuild its projections.
- **Rebuild** — any projection is reconstructible from the event log; a corrupted read model is a replay, not an incident.
- **Why a service and not the gateway** — the gateway must stay stateless. Projections are state.

### 4.1 law — the statute as data

- **Owns** — catalogue versions, dated legal constants, majority rules, obligation definitions, calendar rules, and the archive of every source document retrieved.
- **Components** — catalogue store · constants store · deadline calculator · **watcher** (poller, differ, impact mapper) at `services/law/watcher/`.
- **Publishes** — `LawSourceChanged`, `CatalogueVersionPublished`, `ConstantAdded`.
- **Consumes** — nothing. It is the root of the dependency graph.
- **API** — `GET /catalogue/{version}` · `GET /constants?code=&on=` · `POST /impact` (article → rule IDs) · `GET /obligations`.
- **Consumed how** — for *computation*, as a pinned package `@zues/law@<version>`, so a redeploy elsewhere can never change a deadline. HTTP is for admin screens and the watcher only.
- **Rules** — LAW (10), SYS (12).

**Watcher build order** — step 3 (impact report) is proven: a changed article resolves to rule IDs through each rule's `source`, and to files through the `// Rule:` tags. Steps: 1 keyword watch on new ДВ issues · 2 text diff · 3 impact report · 4 auto-apply scheduled numbers · 5 agent drafts the change, human approves. Step 1 alone is most of the value.

### 4.2 registry — property and the owners' book

- **Owns** — condominium, entrance, unit, ideal parts, closed-complex contracts, the book of the condominium, households, animals, occupancy periods.
- **Publishes** — `UnitChanged`, `IdealPartsChanged`, `OccupancyChanged`, `BookEntryFiled`.
- **Consumes** — `GrantChanged` (to know who may read).
- **API** — entrances, units, book entries, declarations.
- **Invariant** — ideal parts per entrance sum to exactly 100.000000, deferred constraint.
- **Rules** — ORG (12), BOOK (12).

### 4.3 identity & org — people, grants, and the firm

- **Owns** — parties, titles, role grants, mandates, associations; and the commercial side: firm, management contract, register entry, liability insurance, staff, client invoices.
- **Publishes** — `GrantChanged`, `MandateExpired`, `TitleTransferred`, `InsuranceLapsed`.
- **Consumes** — `DecisionTaken` (a protocol elects a manager → grants issue).
- **API** — grants, mandates, org profile, contracts.
- **Invariant** — every grant traces to a protocol; `valid_to` inherited from the mandate.
- **Rules** — GOV (18), PMC (18), SEC (11).

### 4.4 assembly — decisions

- **Owns** — assemblies, sessions, notices sent for approval, proxies, votes, majority applications, protocols, appeal clocks.
- **Publishes** — `AssemblyConvened`, `SessionOpened`, `DecisionTaken`, `ProtocolAnnounced`.
- **Consumes** — `UnitChanged` and `IdealPartsChanged` (voting weight), `GrantChanged`.
- **API** — convene, register attendance, cast vote, close item, publish protocol.
- **Invariant** — votes append-only; every decision stores the majority rule, denominator, tally and session number.
- **Rules** — GA (26), VOTE (16).

### 4.5 money — charges, ledger, fund, arrears

- **Owns** — tariffs, charge runs, ledger accounts, journals, postings, the renewal fund, receivables, dunning, debt certificates, чл. 410 claim data.
- **Publishes** — `ChargeIssued`, `PaymentPosted`, `ArrearAged`, `FundDisbursed`.
- **Consumes** — `DecisionTaken` (a tariff needs a protocol), `OccupancyChanged` (headcount), `IdealPartsChanged`, `BankEventMatched`.
- **API** — run charges, unit statement, ledger, fund, arrears, debt certificate.
- **Invariants** — all postings in a journal share one `entrance_id` (check constraint); issued charges immutable; money is `bigint` minor units.
- **Rules** — FEE (20), FUND (11), DEBT (12).

### 4.6 maintenance — assets and works

- **Owns** — assets (lift, boiler, fire equipment, playground), inspection schedules, technical passport and its measures, work orders, tenders, vendors, warranties, defect reports.
- **Publishes** — `WorkOrderRaised`, `WorkOrderClosed`, `InspectionDue`, `PassportMeasureAdded`.
- **Consumes** — `DecisionTaken` (works above the manager's cap need a majority), `FundDisbursed`.
- **API** — assets, inspections, work orders, quotes, defect reports.
- **Rules** — MNT (16).

### 4.7 notify — notices and proof of delivery

- **Owns** — notice templates, posting acts with photo evidence, delivery records across every channel, resident messages.
- **Publishes** — `NoticePosted`, `DeliveryRecorded`, `DeliveryFailed`.
- **Consumes** — `AssemblyConvened`, `ProtocolAnnounced`, `ArrearAged`, `ComplianceTaskOverdue`.
- **API** — send notice, record posting act, delivery history.
- **Why a service** — posting a notice at the entrance is a legal act whose evidence starts the 7-day and 30-day clocks. It is not a mail helper.
- **Rules** — PM-GA-004, PM-GA-007, PM-GA-008, PM-GA-020, PM-SYS-007.

### 4.8 compliance — the clock and the filings

- **Owns** — materialised obligations per entrance, statutory tasks, filings to the municipality and ЕИСЕС, the ЗМДТ statement, the risk board, the sanctions exposure register.
- **Publishes** — `ComplianceTaskRaised`, `ComplianceTaskOverdue`, `FilingSubmitted`, `FilingAcknowledged`.
- **Consumes** — everything. Owns no domain truth, so it can never block a write.
- **API** — tasks, filings, risk board.
- **Invariant** — deadlines come from the `law` package, never from a model.
- **Rules** — REG (12), part of SYS.

### 4.9 evidence — documents and the audit record

- **Owns** — documents (content-addressed, object-locked), retention policies, the audit log, disclosure records, and the composed чл. 410 case file.
- **Publishes** — `DocumentStored`, `CaseFileComposed`.
- **Consumes** — every event that produces a document or an auditable act.
- **API** — store, fetch, compose case file, export matter bundle.
- **Invariant** — append-only; the application role has no `UPDATE` or `DELETE`.
- **Rules** — DOC (8), PM-SEC-003, PM-SEC-004.

### 4.10 agent — the AI worker

- **Owns** — the capability registry, action proposals, grounding records, per-capability quality metrics.
- **Publishes** — `ProposalCreated`.
- **Consumes** — nothing that grants it authority.
- **API** — ask, draft, propose. Every call carries the requesting user's token.
- **Never** — holds a write credential on any other service's data. Effects fire only from a `release` in the owning service.
- **Rules** — AI (14).

### 4.11 rail — payments, without custody

- **Owns** — payment intents, bank events, reconciliation candidates, PSD2 partner state.
- **Publishes** — `PaymentInitiated`, `BankEventReceived`, `BankEventMatched`.
- **Consumes** — `ChargeIssued`.
- **API** — create intent, list candidates, confirm match (human release only).
- **Never** — holds a balance. Money moves into the entrance's own IBAN.
- **Rules** — PM-DEBT-*, PM-FUND-004, PM-AI-008.

---

## 5. Event catalogue

Every message carries an envelope: `event_id`, `type`, `version`, `entrance_id`, `occurred_at`, `causation_id`, `correlation_id`, `law_version`.

| Event | Producer | Consumers | Carries |
|---|---|---|---|
| `CatalogueVersionPublished` | law | all | version, approved_by, source ДВ ref |
| `ConstantAdded` | law | money, compliance | code, value, in_force_from |
| `LawSourceChanged` | law | compliance | source_id, articles, impacted rule IDs |
| `UnitChanged` · `IdealPartsChanged` | registry | assembly, money | unit, ideal parts, valid_from |
| `OccupancyChanged` | registry | money | unit, persons, animals, period |
| `GrantChanged` · `MandateExpired` | identity & org | gateway, all | party, scope, role, valid_to |
| `DecisionTaken` | assembly | money, maintenance, compliance, identity | decision type, majority rule, tally, execution due |
| `ProtocolAnnounced` | assembly | notify, compliance, evidence | protocol, announced_at, appeal deadline |
| `ChargeIssued` | money | rail, notify, compliance | unit, period, amount, basis_hash |
| `PaymentPosted` · `ArrearAged` | money | notify, compliance | unit, amount, bucket |
| `FundDisbursed` | money | maintenance, compliance | work order, amount |
| `WorkOrderRaised` · `WorkOrderClosed` | maintenance | money, compliance | asset, class, cost |
| `InspectionDue` | maintenance | compliance, notify | asset, statutory interval |
| `NoticePosted` · `DeliveryRecorded` | notify | assembly, evidence, compliance | act, evidence doc, channel |
| `ComplianceTaskRaised` · `…Overdue` | compliance | notify | obligation code, due date, rule ID |
| `BankEventMatched` | rail | money | intent, unit, amount |
| `ProposalCreated` | agent | owning service | capability, grounding, payload |

**Delivery** — at least once. Every consumer is idempotent on `event_id`. Producers write the event and the state change in one transaction (outbox). Ordering is guaranteed per `entrance_id` only.

---

## 6. Cross-cutting standards

### 6.1 Tenancy

- The tenant is the **entrance**. A firm is a set of grants, never a tenant.
- Shared tables, `entrance_id` on every row, Postgres RLS, `SET LOCAL app.entrance` per transaction.
- Not schema-per-tenant: thousands of entrances make migrations impossible.
- The gateway is the only place `entrance_id` is derived. Services never trust a body field.
- Firm data lives in an `org` scope in identity & org, never mixed with entrance data.

### 6.2 Authorization

Relationship-based, not role-based. чл. 7 ал. 4 defines a matrix of **data class × relationship to this entrance**, resolved as of a date. A grant expires when the mandate that issued it expires. RLS is the second lock, not the first.

### 6.3 Temporal law

- Constants are dated rows; the engine resolves the value in force on the **legal date**, never `now()`.
- Computation reads `law` as a pinned package. HTTP calls to `law` never feed a calculation.
- Every charge, decision and task stores the `law_version` that produced it.
- Publishing a new catalogue version must not move a single historical figure.

### 6.4 Money

`bigint` minor units, `char(3)` currency, EUR since 1 Jan 2026. Pre-2026 records keep the original BGN amount, the 1.95583 rate and the converted value. No floats anywhere. Balances are derived from postings, never stored.

### 6.5 The AI boundary

Three classes, enforced at runtime: `AUTONOMOUS` (read, draft, rank), `HUMAN_RELEASE` (anything with legal effect), `PROHIBITED` (deciding, voting, holding a proxy, moving money). The agent runs under the requesting user's permissions. Every interaction is logged with its grounding.

### 6.6 Errors, idempotency, time

Problem+JSON error bodies with a stable `code`. Every write endpoint takes an `Idempotency-Key`. All legal deadlines computed in Europe/Sofia through one shared utility; UTC in storage.

### 6.7 Schema spine — where a wrong type is a rewrite

| Table | The column that matters | Enforced by |
|---|---|---|
| `unit` | `ideal_parts numeric(9,6)` | Deferred trigger: sum per entrance = 100.000000 exactly |
| `posting` | `amount_minor bigint`, `currency char(3)` | Journal must balance; all postings in a journal share one `entrance_id` — a check constraint, so commingling is impossible rather than forbidden |
| `legal_constant` | `in_force_from/to`, `source_ref` | Exclusion constraint on overlapping ranges per code |
| `charge_run` | `basis jsonb`, `basis_hash` | Insert-only; issued lines immutable |
| `majority_rule` | `threshold`, `denominator` | `denominator` is a `NOT NULL` enum — no default |
| `assembly` | `session_no smallint`, `quorum_pct` | One assembly, up to three sessions, one agenda |
| `role_grant` | `scope_type`, `scope_id`, `valid_from/to`, `source_protocol_id` | Every grant traces to a protocol; expiry is data, not a job |
| `vote`, `audit_event` | — | Append-only: the application role has no `UPDATE` or `DELETE` |
| `action_proposal` | `capability`, `grounding jsonb`, `status` | Effects fire only from a `release` row signed by a human holding the role |

Full entity list: `docs/RULES.md` §4.

### 6.8 Reproducing a past number

A charge computed in 2024 must recompute identically in 2029. Three mechanisms, not full bitemporality:

1. `legal_constant` rows resolved at the **legal date**.
2. `valid_from` / `valid_to` on the entities whose change has legal effect: title, household member, occupancy, ideal parts, tariff, mandate, role grant, majority rule.
3. `charge_run.basis` + `basis_hash` — every input frozen at run time. The charge engine is a pure function: no clock, no I/O, no model.

A correction is a credit note, never an edit.

### 6.9 The client contract

The phone is a first-class client, not a responsive afterthought.

- **Two apps.** A resident-and-manager PWA, installable, mobile-first. A desktop console for firm staff. They share a design system and the same API; they do not share a codebase-wide layout.
- **One request per screen.** Screens are served by the BFF. A mobile view never fans out across services.
- **Offline queue.** A write made without signal is queued with its `Idempotency-Key` and shown as *pending*, never as done. A queued vote is not a cast vote (PM-SYS-014).
- **Photographs are evidence.** Captured originals upload directly to object storage via a presigned URL, keep their capture timestamp, and are stored unmodified beside any resized display copy (PM-DOC-009).
- **Push is not delivery.** Push and email are convenience. The posting act with evidence is the statutory delivery (PM-SYS-013).
- **Devices hold almost nothing.** Only the viewer's own data may be cached offline; the owners' book never is (PM-SEC-012).
- **Sign-in suits the user.** Residents get a magic link or SMS code with a long session. Managers and firm staff get a shorter session and step-up authentication before a release action.
- **Payments leave the app.** PSD2 initiation hands off to the bank app and returns by deep link. Treat the return as untrusted; the truth is the bank event.

---

## 7. Non-functional requirements

| Area | Requirement |
|---|---|
| **Availability** | 99.5% monthly for manager and resident surfaces |
| **The statutory deadline is an SLO** | Zero missed statutory tasks caused by the system. A raised-late task is an incident, not a bug |
| **Load shape** | The peak is the 1st of the month: charge runs across the portfolio, statements, then the payment flood. Load tests must have this shape, not an averaged one |
| **Latency** | p95 under 400 ms for reads; a charge run for a 60-unit entrance under 5 s |
| **Durability** | RPO 15 min, RTO 4 h. Recovery of a *single entrance* must be rehearsed, not only the whole cluster |
| **Data residency** | EU region. Documents object-locked |
| **Retention** | Per data class: statutory retention against GDPR minimisation, resolved as policy, not case by case |
| **Auditability** | Any past charge, vote or disclosure reconstructible with the law version that produced it |
| **Device floor** | Resident surface works on the configured floor — a five-year-old Android on 3G. The floor is a dated config value, not folklore |
| **Mobile performance** | First meaningful paint under 2.5 s on the floor device; screen payload budget enforced in CI (PM-SYS-015) |
| **Accessibility** | The resident surface is used by elderly owners. WCAG AA, 17 px minimum body text, 48 px tap targets |
| **Language** | Bulgarian first. Statutory documents are Bulgarian only |

---

## 8. Decisions that cannot be cheaply undone

| # | Decision | Rules |
|---|---|---|
| 1 | Temporal legal constants — the value in force on the legal date | PM-SYS-001/002 |
| 2 | The isolation unit is the **entrance** | PM-ORG-001, PM-FUND-005 |
| 3 | Double-entry ledger, one fund per entrance | PM-FUND-004 |
| 4 | Exact decimals for ideal parts, integer minor units for money | PM-ORG-002, PM-FEE-016 |
| 5 | Explicit denominator on every majority rule | PM-VOTE-012 |
| 6 | The account belongs to the entrance, not the firm | PM-GOV-004, PM-PMC-010 |
| 7 | The agent holds no write credential | PM-AI-002/008 |
| 8 | No custody, ever | PM-FUND-004, чл. 50 |
| 9 | Law is consumed as a pinned package, not a network call | PM-LAW-002/007 |

Each becomes an ADR in `docs/adr/` before any code is written.

---

## 9. Repository layout

```
zues/
  docs/
    STAGE1.md              this document
    RULES.md  rules.json   the 228 rules
    adr/                   nine ADRs
    events/                event schemas, versioned
  packages/
    law/                   the pinned package every service computes with
    kernel/                EntranceId, Money, IdealParts, event envelope
    policy/                the чл. 7 ал. 4 matrix, one implementation
  services/
    gateway/
    law/
      api/
      watcher/             poller, differ, impact mapper
    registry/  identity-org/  assembly/  money/
    maintenance/  notify/  compliance/  evidence/
    agent/  rail/
  apps/
    web/                   one Next.js app, three route groups
  infra/                   terraform, CI, migrations
```

Every service has the same skeleton: `api/` · `app/` · `domain/` · `infra/` · `migrations/` · `tests/`.

---

## 10. Traceability

One row per rule, generated, checked in CI:

`rule_id → service → endpoint or consumer → event → test name → source article`

CI fails when a rule has no test, when a rule ID appears in code but not in the catalogue, or when a `verified: false` rule's number appears as a literal instead of a config lookup.

---

## 11. Build order

Stage 1 is this document. Stage 2 is the walking skeleton. The 90-day pilot gates from the SaaS plan stand unchanged; the services simply say who does what.

| Block | Services | Gate |
|---|---|---|
| Week 0 | — | A firm has paid, and the payments licence question has a written answer |
| Days 1–15 | law, gateway, registry, money (charges only) | **G1** — the fee engine reproduces the firm's spreadsheet to the cent |
| Days 16–45 | identity & org, assembly, notify, evidence | **G2** — one lawful general assembly, end to end |
| Days 46–75 | money (ledger, fund, arrears), rail | **G3** — collection beats the portfolio average; one чл. 410 packet accepted |
| Days 76–90 | compliance, maintenance, agent (plumbing only) | **G4** — risk board green; the firm asks to pay 3× |

The agent ships in the pilot with **zero model-backed capabilities enabled**.

---

## 12. Open before build

1. Payments — partner licence or own PISP authorisation from БНБ. Blocks the revenue model.
2. The four money-touching unverified rules: fund minimum base, чл. 46б deadline, arrears on transfer, euro sanction ranges.
3. What dv.parliament.bg allows, and whether a legal-database API is worth buying.
4. QES provider for absentee ballots — Evrotrust or B-Trust.
5. A signed pilot firm. Everything above is theory until then.
