# Property Management Business Rules — Bulgaria (ЗУЕС + commercial layer)

**Version:** 1.3 · **Legal baseline:** 3 September 2026 · **233 rules, 16 domains** · **Jurisdiction:** Republic of Bulgaria
**Primary source:** Закон за управление на етажната собственост (ЗУЕС), обн. ДВ бр. 6/2009, in force 1.05.2009, materially rewritten by ДВ бр. 82/29.09.2023 (staged entry into force through 31.12.2024).
**Supporting:** Закон за собствеността (ЗС), ЗУТ + Наредба № 5/2006 (технически паспорт), ГПК чл. 410, ЗАНН, ЗМДТ чл. 67, ЗЗЛД/GDPR, ЗЕДЕУУ, Наредба за ЕИСЕС (чл. 47а ал. 3 ЗУЕС).

> ⚠️ **Not legal advice.** Every numeric threshold marked `⚠` below must be re-verified against the current consolidated statute before it is hard-coded. Bulgaria adopted the euro on 1 January 2026; all statutory BGN amounts were redenominated at 1.95583 BGN/EUR. Treat monetary constants as configuration, never as literals in code.

---

## 1. How to use this file with Claude Code

This file is written to be dropped into a repository as durable context.

```
your-repo/
  CLAUDE.md            # points at this file
  docs/RULES.md        # this file
  docs/rules.json      # same rules, machine-readable
```

The repo's `CLAUDE.md` holds the working agreement. It is the single place those constraints are written.

**Prompt recipes** (paste directly into Claude Code):

- `Implement PM-GA-010 … PM-GA-024 (quorum + reconvening) in src/domain/assembly/. Read docs/RULES.md first. Write table-driven tests named after each rule ID.`
- `Audit src/domain/fees/ against domain FEE in docs/RULES.md. Report each rule as IMPLEMENTED / PARTIAL / MISSING with file:line. Do not change code.`
- `Generate the Prisma schema for the entities in §4 of docs/RULES.md. Enforce every invariant marked INVARIANT as a DB constraint where possible, otherwise as a domain guard.`
- `A rule changed: PM-FEE-030 now allows per-unit allocation. Find every affected code path, test and migration, and produce a change plan before editing.`

**Modality keywords** follow RFC 2119: **MUST** = statutory, non-negotiable; **MUST NOT** = prohibited; **SHOULD** = strong product default, overridable by configuration; **MAY** = configurable option.

---

## 2. Actors

| Code | Actor | BG | Notes |
|---|---|---|---|
| OWN | Owner | собственик | Holds ideal parts; votes |
| USR | User/holder | ползвател | Right of use; votes where the owner assigned it |
| OCC | Occupant | обитател | Resident without title; no vote, pays maintenance |
| BM | Building manager | управител / председател на УС | Elected volunteer, one building |
| MB | Management board | управителен съвет | Odd number, ≥3 members |
| CTL | Controller / control board | контрольор / контролен съвет | Audits the cashbox |
| CSH | Cashier | касиер | Optional separate role |
| PMC | Professional manager | професионален управител-търговец | Trader entered in the MRRB register; manages many buildings |
| ASC | Owners' association | сдружение на собствениците | Separate legal person, BULSTAT-registered |
| MUN | Municipality / district | общинска/районна администрация | Keeps the public register, issues penal decrees |
| INV | Developer / investor | инвеститор | Closed complexes |

**Deployment modes the app MUST support side by side:** `SELF_MANAGED` (BM is an unpaid owner running one building) and `PROFESSIONAL` (PMC staff running a portfolio). Every rule below is tagged `BM`, `PMC` or `BOTH`.

---

## 3. Rule format

`| ID | Modality | Rule | Source | Scope | Acceptance / app behaviour |`

`⚠` in the Source column = numeric or deadline not confirmed against the consolidated statute; expose as configuration and flag in the UI.

---

## 4. Core entities (data model contract)

| Entity | Key fields | Invariants |
|---|---|---|
| `Condominium` | id, address, cadastral id, EIS code, entrances[] | INVARIANT: sum of unit ideal parts per entrance = 100.00% |
| `Entrance` | id, condominium_id, management_form | An entrance MAY be a separate ЕС with its own GA and manager |
| `Unit` (самостоятелен обект) | id, entrance_id, type, area_m2, ideal_parts_pct, separate_entrance | ideal_parts_pct stored as DECIMAL(7,4) |
| `Party` | id, names, id_type, contact | Natural or legal person |
| `Title` | unit_id, party_id, role (OWN/USR), share, from, to | Co-ownership supported; votes split by share |
| `Household` | unit_id, member, from, to, is_child_under_6 | Drives per-capita fee counts |
| `Animal` | unit_id, species, vet_passport_no | Counts as +1 occupant for fee purposes |
| `ManagementMandate` | entrance_id, body (MB/BM/PMC), from, to, elected_by_protocol_id | Max 2 years; auto-extends until successor elected |
| `Assembly` | entrance_id, convened_by, notice_posted_at, scheduled_at, mode, session_no, quorum_pct, status | |
| `AgendaItem` | assembly_id, ordinal, text, required_majority_rule_id | |
| `Vote` | agenda_item_id, party_id, unit_id, ideal_parts, choice, channel, cast_at | channel: IN_PERSON / PROXY / VIDEO / ABSENTEE |
| `Proxy` | assembly_id, principal_party_id, agent_party_id, form, scope | |
| `Protocol` | assembly_id, drafted_at, announced_at, appeal_deadline, file_hash | |
| `ChargeRun` | entrance_id, period, basis, status | Monthly billing cycle |
| `ChargeLine` | unit_id, component, base, quantity, amount, allocation_key | |
| `FundAccount` | entrance_id, iban, purpose (REPAIR_RENEWAL / OPERATING) | INVARIANT: repair fund IBAN ≠ operating IBAN ≠ PMC own account |
| `Payment` / `Arrear` | unit_id, amount, due_date, paid_at, interest | |
| `WorkOrder` | entrance_id, category, urgency, budget_source, approval_rule_id | |
| `ComplianceTask` | entrance_id, obligation_code, due_date, evidence_doc_id | Lift, fire, technical passport, filings |
| `Document` | entity_ref, kind, retention_until, hash, signed_by | |
| `RegistryFiling` | entrance_id, registry (MUNICIPAL/EIS/BULSTAT), filed_at, ack_ref | |

---

## 5. Rules

### A. ORG — scope, buildings, ideal parts

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-ORG-001 | MUST | The system MUST model condominium ownership at the level of a building **or a separate entrance**, because each entrance may run its own general assembly, manager and accounts. | чл. 9, чл. 10 ЗУЕС | BOTH | Creating a building with 3 entrances allows 3 independent GAs and 3 fund accounts. |
| PM-ORG-002 | MUST | Each unit MUST carry its ideal parts of the common parts as a percentage; the sum per entrance MUST equal 100%. | чл. 7 ал. 2 т. 2 ЗУЕС | BOTH | Saving a unit set summing to 99.98% is rejected with the delta shown. |
| PM-ORG-003 | SHOULD | Where the title deed does not state ideal parts, the system SHOULD offer computation by built-up area ratio and mark the value `DERIVED`. | ЗС чл. 40 | BOTH | Derived values render with a warning badge in voting screens. |
| PM-ORG-004 | MUST | Voting weight MUST be computed from ideal parts, never from unit count, unless the GA lawfully adopted a per-unit key for a fee decision. | чл. 17 ал. 1 ЗУЕС | BOTH | A 2-unit owner with 12% outvotes 5 owners holding 10%. |
| PM-ORG-005 | MUST | Co-owned units MUST split voting weight by title share, and the system MUST prevent double counting. | чл. 17 ЗУЕС | BOTH | Two 50% co-owners voting oppositely cancel out; total cast = unit's ideal parts. |
| PM-ORG-006 | MUST | The system MUST support the **closed complex** regime: management governed by a notarised contract between developer and owners, registered with the Registry Agency against each unit's file. | чл. 2 ал. 1–2 ЗУЕС | BOTH | Marking an entrance `CLOSED_COMPLEX` swaps GA-driven fee rules for contract-driven ones and requires an uploaded registered contract. |
| PM-ORG-007 | MUST | For closed complexes the system MUST store the registration entry reference, because an unregistered contract is not opposable to later buyers. | чл. 2 ал. 2 ЗУЕС | PMC | Missing entry reference raises a compliance flag on ownership change. |
| PM-ORG-008 | SHOULD | The system SHOULD distinguish common parts by nature (общи по предназначение) from those by agreement, and attach each to a cost centre. | ЗС чл. 38 | BOTH | Roof, façade, stairwell, lift shaft are seeded per building type. |
| PM-ORG-009 | MUST | Units with a separate street entrance used for business MUST be flagged, because their fee multiplier differs. | чл. 51 ал. 3 ЗУЕС | BOTH | Flag drives PM-FEE-034. |
| PM-ORG-010 | MAY | The system MAY group entrances into a "site" for shared assets (boiler room, parking, playground) with its own allocation key. | practice | PMC | Shared asset costs allocate across participating entrances before per-unit split. |
| PM-ORG-011 | MUST | Ownership changes MUST be effective-dated, and all fee, vote and arrears logic MUST resolve the party as of the relevant date. | чл. 7 ал. 3 ЗУЕС | BOTH | A sale on the 14th splits the month's charge at the configured convention. |
| PM-ORG-012 | MUST | GA decisions MUST bind subsequent owners, users and occupants; the system MUST surface the full decision history to a new owner on transfer. | чл. 11 ал. 4 ЗУЕС | BOTH | New owner onboarding shows all in-force decisions and outstanding plans. |

### B. BOOK — owners' book, residents, animals

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-BOOK-001 | MUST | Every building or entrance MUST have a Book of the Condominium; the system MAY keep it purely electronic. | чл. 7 ал. 1 ЗУЕС | BOTH | Electronic book is the system of record; export produces the ministry-approved layout. |
| PM-BOOK-002 | MUST | The book MUST record: unit designation and built area, ideal parts %, owner/user names, household members, periods of non-use, temporary occupants, and agreed owner–user rights. | чл. 7 ал. 2 ЗУЕС | BOTH | A unit missing ideal parts or owner name cannot be marked "book complete". |
| PM-BOOK-003 | MUST | Owners and users MUST file a declaration for entry in the book within **15 days** of acquiring title or use, and on any change of the declared data. | чл. 7 ал. 3 ЗУЕС | BOTH | Day 16 without declaration raises an overdue task assigned to the manager. |
| PM-BOOK-004 | MUST | The system MUST provide the declaration in the ministry-approved template. | чл. 7 ал. 7 ЗУЕС | BOTH | Template version is configurable and versioned. |
| PM-BOOK-005 | MUST | Animals kept in a unit MUST be recorded in a separate section of the book, entered by the chair/manager from the owner's declaration, including veterinary passport data. | чл. 7 ал. 6 ЗУЕС | BOTH | Adding an animal writes to the animals section and recalculates fee headcount. |
| PM-BOOK-006 | MUST | Read access to book data MUST be limited to the management board/manager, the control board/controller, the owner for their own data, and Ministry of Interior bodies on lawful request. | чл. 7 ал. 4 ЗУЕС | BOTH | Owner A querying owner B's household returns 403 and an audit entry. |
| PM-BOOK-007 | MUST | The system MUST log every access to and export of book data with actor, purpose and timestamp. | GDPR art. 5(2), 32 | BOTH | Audit log is immutable and exportable for a supervisory authority. |
| PM-BOOK-008 | MUST | Occupancy periods MUST be stored as date ranges, since absence and short stays change both fees and headcount. | чл. 7 ал. 2 т. 5–6, чл. 51 ЗУЕС | BOTH | Fee engine reads occupancy as of the billing period. |
| PM-BOOK-009 | SHOULD | The system SHOULD let owners self-serve their declaration through a resident portal with identity verification. | product | BOTH | Declaration submitted online lands as a pending entry for manager approval. |
| PM-BOOK-010 | MUST | Personal data in the book MUST be retained only while the legal basis lasts, then anonymised, with retention configurable per field group. | GDPR art. 5(1)(e) | BOTH | A former occupant's household record is anonymised after the retention window. |
| PM-BOOK-011 | MUST NOT | The system MUST NOT expose ID numbers (ЕГН) or full addresses in any list visible to other residents. | GDPR art. 5(1)(c) | BOTH | Resident directory shows unit and display name only. |
| PM-BOOK-012 | SHOULD | The system SHOULD reconcile the book against the fee headcount monthly and flag mismatches. | practice | PMC | Monthly exception report lists units where declared persons ≠ billed persons. |

### C. GOV — governance bodies, mandates, association

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-GOV-001 | MUST | The management forms MUST be modelled as: general assembly of owners and/or an owners' association. | чл. 9 ЗУЕС | BOTH | Entrance carries `management_form` ∈ {GA, ASSOCIATION, GA+ASSOCIATION, CLOSED_COMPLEX}. |
| PM-GOV-002 | MUST | Bodies MUST be: general assembly (deliberative) and management board or sole manager (executive). | чл. 10 ЗУЕС | BOTH | Entrance governance config exposes exactly these two bodies; no third body type is creatable. |
| PM-GOV-003 | MUST | A management board MUST have an odd number of members, minimum 3; a sole manager is the alternative. | чл. 19 ЗУЕС | BOTH | Saving a 4-member board is rejected. |
| PM-GOV-004 | MUST | Board/manager mandate MUST NOT exceed **2 years**, and the incumbent continues in office until a successor is elected. | чл. 19, чл. 21 ЗУЕС | BOTH | At mandate end the status becomes `EXPIRED_ACTING` and an election task is raised 60 days before expiry. |
| PM-GOV-005 | MUST | The election of a new board MUST be scheduled no later than the mandate expiry date. | чл. 21 ЗУЕС | BOTH | Countdown widget on the manager dashboard. |
| PM-GOV-006 | MUST | Only owners and users MAY be members of the management or control board. | чл. 19 ЗУЕС | BOTH | Selecting an occupant as a board candidate is blocked. |
| PM-GOV-007 | MUST | A control board (or single controller) MAY be elected for a 2-year term and MUST audit the cashbox at least once a year, reporting to the GA. | чл. 24 ЗУЕС | BOTH | Annual audit task auto-created; GA agenda template includes the controller's report. |
| PM-GOV-008 | MUST | The control board MUST have read access to all condominium documentation. | чл. 24 ЗУЕС | BOTH | `CTL` role gets read-all, write-none except its own reports. |
| PM-GOV-009 | SHOULD | Refusal to serve on the board SHOULD be recordable only on the statutory grounds (lasting incapacity through illness or prolonged absence during the year). | чл. 20 ЗУЕС | BOTH | Declining a nomination requires a reason code. |
| PM-GOV-010 | MUST | The GA MAY delegate all or part of the executive powers to a professional manager-trader entered in the register, by a majority of **more than 50% of ideal parts**. | чл. 19 ЗУЕС | PMC | Onboarding a building into a PMC portfolio requires an uploaded protocol meeting this majority. |
| PM-GOV-011 | MUST | An owners' association MAY be founded to absorb EU, state or municipal funds; founding requires owners holding at least **67% of ideal parts**. | чл. 25 ЗУЕС | BOTH | The "apply for renovation programme" flow blocks until an association exists. |
| PM-GOV-012 | MUST | After the founding meeting, the chair MUST file for entry in the municipal register within **14 days**, and apply for BULSTAT registration within **7 days** of receiving the certificate. | чл. 29 ЗУЕС ⚠ | BOTH | Two chained statutory tasks with hard due dates and evidence upload. |
| PM-GOV-013 | MUST | The association agreement MUST record name, seat, purpose, decision-making order, and composition and mandate of its bodies. | чл. 28 ЗУЕС | BOTH | Document generator produces the agreement from stored data. |
| PM-GOV-014 | MUST | The system MUST keep the association and the general assembly as distinct decision-making tracks where both exist, with separate protocols. | чл. 9, чл. 25 ЗУЕС | BOTH | Protocol picker forces selection of the deciding body. |
| PM-GOV-015 | MUST | Remuneration of board, controller and cashier MUST be settable by GA decision, and the GA MAY resolve to pay nothing where they are owners, users or occupants. | чл. 11 ал. 1 т. 16, 16а ЗУЕС | BOTH | Remuneration = 0 is a valid configured state, not a missing value. |
| PM-GOV-016 | MUST | The GA MUST adopt internal rules (правилник за вътрешния ред); the system MUST treat their absence as a compliance breach. | чл. 11 ал. 3 ЗУЕС | BOTH | Dashboard shows "internal rules missing" until a version is published. |
| PM-GOV-017 | SHOULD | Internal rules SHOULD be versioned, with the adopting protocol linked and the in-force version resolvable at any past date. | чл. 11 ал. 1 т. 1 ЗУЕС | BOTH | Violation records cite the rule version in force on the incident date. |
| PM-GOV-018 | MUST | Handover between managers MUST transfer the book, all protocols in original, the cashbox records and the fund balance, and MUST be recorded as a signed handover act. | чл. 23 ЗУЕС | BOTH | Deactivating a mandate without a handover act raises a blocking task. |

### D. GA — general assembly lifecycle

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-GA-001 | MUST | A general assembly MUST be convened at least **once per calendar year**. | чл. 12 ЗУЕС | BOTH | If no GA is held by 31 December, a red compliance flag is raised on 1 January. |
| PM-GA-002 | MUST | The GA MUST be convenable by the management board/manager and by the control board/controller. | чл. 12 ЗУЕС | BOTH | Convene action available to `MB`, `BM`, `CTL`. |
| PM-GA-003 | MUST | Owners holding at least **20% of ideal parts** MUST be able to demand convening; if the demand is not met, they may convene it themselves. | чл. 12 ЗУЕС ⚠ | BOTH | Petition feature accumulates signatures until the 20% threshold, then unlocks self-convening. |
| PM-GA-004 | MUST | The notice (покана) MUST be signed by the convenors and posted at a visible, publicly accessible place at the building entrance **at least 7 days** before the meeting. | чл. 13 ЗУЕС | BOTH | Scheduling a GA 6 days out is blocked; the system records the posting act with photo evidence. |
| PM-GA-005 | MUST | In urgent cases the notice period MAY be reduced to **24 hours**, and the urgency MUST be recorded. | чл. 13 ЗУЕС ⚠ | BOTH | Urgent flag requires a free-text justification stored on the assembly. |
| PM-GA-006 | MUST | The notice MUST state date, hour, place and the full agenda; decisions outside the announced agenda MUST be blocked. | чл. 13 ЗУЕС | BOTH | Adding an agenda item after notice posting forces re-notice with a new 7-day clock. |
| PM-GA-007 | MUST | Posting of the notice MUST be evidenced by a certifying act signed by the convenor and one owner. | чл. 13 ЗУЕС ⚠ | BOTH | Assembly cannot move to `NOTICED` without the posting act. |
| PM-GA-008 | SHOULD | Electronic notification (email, portal, SMS) SHOULD be sent in addition to physical posting, never instead of it. | чл. 13 ЗУЕС | BOTH | Sending email alone leaves the assembly in `DRAFT`. |
| PM-GA-009 | MUST | An owner or user unable to attend MAY appoint a proxy: an adult household member, another owner, or a third party under written authorisation. | чл. 14 ЗУЕС | BOTH | Proxy registration captures principal, agent, scope and form. |
| PM-GA-010 | MUST | One person MUST NOT represent more than the statutory maximum number of owners. | чл. 14 ЗУЕС ⚠ (commonly 3) | BOTH | Registering a 4th proxy for the same agent is rejected with the configured limit shown. |
| PM-GA-011 | SHOULD | A lawyer's authorisation SHOULD be accepted without notarisation; relatives acting as proxies SHOULD be verified against the book. | 2023 amendments ⚠ | BOTH | Proxy form type drives the validation path. |
| PM-GA-012 | MUST | Quorum: the GA is held if owners representing at least **51% of ideal parts** are present in person or by proxy. | чл. 15 ал. 1 ЗУЕС | BOTH | Live quorum meter; at 50.99% the meeting cannot open. |
| PM-GA-013 | MUST | If quorum is not reached, the meeting MUST be postponed by **one hour** and is then valid with at least **26% of ideal parts**. | чл. 15 ал. 2 ЗУЕС | BOTH | Session 2 opens automatically 60 minutes later with the lowered threshold. |
| PM-GA-014 | MUST | If the reduced quorum still fails, the meeting MUST be held **the following day** and is lawful regardless of the ideal parts represented. | чл. 15 ал. 3 ЗУЕС | BOTH | Session 3 has `quorum_pct = 0`; the agenda is carried over unchanged. |
| PM-GA-015 | MUST | Where one natural or legal person owns more than **51% of ideal parts**, the quorum MUST be at least **75%**. | чл. 15 ал. 4 ЗУЕС | BOTH | Quorum rule is selected from the ownership concentration at notice date. |
| PM-GA-016 | MUST | The system MUST support in-person, video-conference and mixed (hybrid) sessions, with reliable identification of remote participants. | 2023 amendments, чл. 16/17а ЗУЕС | BOTH | Remote attendee is counted in quorum only after identity verification passes. |
| PM-GA-017 | MUST | Absentee (non-attending) voting MUST be available for the statutorily listed decisions within **7 days** after the meeting. | чл. 17а ЗУЕС | BOTH | Only agenda items tagged `ABSENTEE_ELIGIBLE` accept post-meeting ballots; the tally reopens until day 7. |
| PM-GA-018 | MUST | Absentee ballots MUST be handwritten-signed declarations or signed with a qualified electronic signature. | чл. 17а ЗУЕС, ЗЕДЕУУ | BOTH | A plain portal click is rejected unless a QES is attached. |
| PM-GA-019 | MUST | Minutes (протокол) MUST be drawn up within **7 days** of the meeting. | чл. 16 ЗУЕС | BOTH | Day 8 without minutes raises an overdue compliance task. |
| PM-GA-020 | MUST | A notice that the minutes have been drawn up MUST be posted at a visible, publicly accessible place; that posting starts the challenge period. | чл. 16 ЗУЕС | BOTH | `announced_at` sets `appeal_deadline = announced_at + 30 days`. |
| PM-GA-021 | MUST | Minutes MUST record date, place, agenda, attendance with ideal parts, votes per item and the decisions taken. | чл. 16 ЗУЕС | BOTH | Generated PDF fails validation if attendance percentages are missing. |
| PM-GA-022 | MUST | Original paper protocols MUST be preserved by the management board even where the system holds a scan. | чл. 23 ЗУЕС | BOTH | Each protocol records the physical archive location. |
| PM-GA-023 | MUST | Any owner MAY seek annulment of an unlawful GA decision before the district court within **30 days** of receiving the decision under чл. 16 ал. 7. | чл. 40 ал. 1–2 ЗУЕС | BOTH | Decision shows a countdown; after 30 days status becomes `FINAL`. |
| PM-GA-024 | MUST | Filing a challenge MUST NOT suspend execution of the decision unless a court orders otherwise. | чл. 40 ал. 3 ЗУЕС | BOTH | Litigation flag does not pause the execution clock; a court stay does. |
| PM-GA-025 | MUST | GA decisions MUST be executed within the term they state, or within **14 days** of their announcement where no term is stated. | чл. 38 ал. 1 ЗУЕС | BOTH | Every decision creates an execution task with a computed due date. |
| PM-GA-026 | SHOULD | The system SHOULD generate the notice, attendance sheet, ballot, protocol and decision extract from one dataset. | practice | BOTH | Zero re-keying between notice and protocol. |

### E. VOTE — majorities and decision routing

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-VOTE-001 | MUST | Each agenda item MUST be bound to a required-majority rule before the meeting opens. | чл. 17 ЗУЕС | BOTH | An unbound item cannot be put to a vote. |
| PM-VOTE-002 | MUST | Default majority: more than 50% of the ideal parts represented at the meeting. | чл. 17 ЗУЕС | BOTH | An item with no explicit threshold resolves to >50% of represented ideal parts and states the denominator used. |
| PM-VOTE-003 | MUST | Superstructure and extension of the building (надстрояване и пристрояване) MUST require **100%** of ideal parts. | чл. 17 ЗУЕС | BOTH | Item type `EXTENSION` cannot pass at 99.9%. |
| PM-VOTE-004 | MUST | Granting rights of use over common parts or changing their designated purpose MUST require the highest statutory majority for that act. | чл. 17 ЗУЕС ⚠ | BOTH | Item type `COMMON_PART_USE_RIGHT` uses the configured threshold with source citation. |
| PM-VOTE-005 | MUST | Useful expenses (полезни разходи) and taking credit MUST require **75%** of ideal parts. | чл. 17 ЗУЕС | BOTH | An item typed USEFUL_EXPENSE or CREDIT fails at 74.9% and passes at 75%. |
| PM-VOTE-006 | MUST | Expulsion of an owner, user or occupant MUST require **75%** of ideal parts, excluding the shares of the person concerned. | чл. 17 ЗУЕС, ЗС чл. 45 | BOTH | The respondent's ideal parts are removed from both numerator and denominator. |
| PM-VOTE-007 | MUST | Major repair, major renewal and energy-efficiency works MUST require **51%** of ideal parts. | чл. 17 ЗУЕС | BOTH | An item typed MAJOR_REPAIR, MAJOR_RENEWAL or ENERGY_EFFICIENCY fails at 50.9% and passes at 51%. |
| PM-VOTE-008 | MUST | Necessary and urgent expenses for maintaining or restoring the common parts MUST NOT be refusable by the GA. | чл. 11 ал. 2 ЗУЕС | BOTH | A vote rejecting a `NECESSARY` work order is flagged unlawful and blocked from becoming a decision. |
| PM-VOTE-009 | MUST | The GA MAY authorise the management board to decide urgent repairs and non-deferrable expenses without a further meeting. | чл. 11 ал. 1 т. 14 ЗУЕС | BOTH | A standing authorisation with a monetary cap unlocks the manager's emergency-spend path. |
| PM-VOTE-010 | MUST | Decisions on advertising installations, connection to district heating or gas, and letting of common parts MUST use their own configured majorities. | чл. 11 ал. 1 т. 10 ЗУЕС | BOTH | Each is a distinct agenda item type. |
| PM-VOTE-011 | MUST | Vote tallies MUST be computed on ideal parts, reported to 2 decimals, and MUST show the denominator used (represented vs total). | чл. 17 ЗУЕС | BOTH | Result line reads e.g. "62.41% of 78.10% represented; threshold 51% of total — NOT PASSED". |
| PM-VOTE-012 | MUST | Where a threshold is expressed against **all** ideal parts, the system MUST NOT satisfy it from the represented subset. | чл. 17 ЗУЕС | BOTH | Explicit `denominator` field per majority rule: `TOTAL` or `REPRESENTED`. |
| PM-VOTE-013 | MUST | Absentee votes cast within the 7-day window MUST be merged into the tally before the result is declared final. | чл. 17а ЗУЕС | BOTH | Provisional result is labelled `PROVISIONAL` until day 7 closes. |
| PM-VOTE-014 | MUST NOT | The system MUST NOT allow a vote to be edited after the item is closed; corrections MUST be recorded as annotated reversals. | audit | BOTH | Vote records are append-only. |
| PM-VOTE-015 | SHOULD | Owners in arrears SHOULD retain voting rights unless a lawful decision provides otherwise. | чл. 17 ЗУЕС | BOTH | Debt does not disable the ballot; it may be displayed as context. |
| PM-VOTE-016 | MUST | Every decision MUST store the majority rule applied, the tally, the quorum session number and the resulting status. | чл. 16, 17 ЗУЕС | BOTH | Reproducible from the audit trail years later. |

### F. FEE — charges for management and maintenance

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-FEE-001 | MUST | The system MUST separate three cost streams: **management costs**, **maintenance costs** of the common parts, and **repair/renewal** costs. They have different legal allocation keys. | чл. 6 ал. 1 т. 9–10, чл. 50, 51 ЗУЕС | BOTH | Chart of accounts has three roots; a charge line cannot be untyped. |
| PM-FEE-002 | MUST | Management and maintenance costs MUST be allocated **equally per person** — owners, users and occupants — regardless of floor, unless the GA lawfully adopted another key. | чл. 51 ал. 1 ЗУЕС | BOTH | Default allocation key = `PER_CAPITA`. |
| PM-FEE-003 | MUST | The GA MUST be able to set the allocation key for management and maintenance to per capita, by ideal parts, or per unit, and the choice MUST be traceable to a protocol. | чл. 11 ал. 1 т. 5, 2023 amendments | BOTH | Changing the key without a linked protocol is rejected. |
| PM-FEE-004 | MUST | Repair, reconstruction, conversion, major repair and major renewal costs MUST be allocated **by ideal parts**. | чл. 6 ал. 1 т. 9, чл. 48–50 ЗУЕС | BOTH | Allocation key is fixed for this stream and not user-editable. |
| PM-FEE-005 | MUST | Children under **6 years** MUST NOT be counted for management and maintenance charges. | чл. 51 ал. 2 ЗУЕС | BOTH | A household member whose 6th birthday falls mid-period starts being counted from the following period. |
| PM-FEE-006 | MUST | Owners, users or occupants absent for more than **30 days in a calendar year** MUST be exempt for that period, or pay a reduced share where the GA so decided. | чл. 51 ал. 2 ЗУЕС ⚠ | BOTH | Absence declaration drives proration; the GA-set reduction (commonly 50%) is configurable. |
| PM-FEE-007 | MUST | The exemption MUST require a filed declaration; the system MUST NOT apply it retroactively beyond the configured window. | чл. 51 ЗУЕС | BOTH | Late declarations are accepted only within the configured grace period. |
| PM-FEE-008 | MUST | Persons residing in a unit for more than 30 days MUST be counted as occupants even without title. | чл. 7 ал. 2 т. 6, чл. 51 ЗУЕС | BOTH | Tenant registration triggers headcount recalculation. |
| PM-FEE-009 | MUST | Each animal kept in a unit MUST add the equivalent of **one occupant** to the charges for electricity, water, heating and cleaning of the common parts. | чл. 51 ЗУЕС | BOTH | Registering a dog increases the unit's per-capita count by 1. |
| PM-FEE-010 | MUST | Units used for business or professional activity with access through the common parts MUST be charged **3 to 5 times** the standard rate, as set by the GA; a separate street entrance reverts them to the standard rate. | чл. 51 ал. 3 ЗУЕС | BOTH | Multiplier is per unit, bounded to [3,5], and requires a protocol reference. |
| PM-FEE-011 | MUST | Concierge/doorman costs MUST follow the same allocation rules as maintenance. | чл. 51 ЗУЕС | BOTH | A concierge cost line uses the same allocation key as maintenance and inherits its exemptions. |
| PM-FEE-012 | MUST | The GA MUST set the amount of the monetary contributions for management and maintenance; the system MUST NOT bill an amount without a GA decision behind it. | чл. 11 ал. 1 т. 5 ЗУЕС | BOTH | Every tariff version carries `protocol_id` and validity dates. |
| PM-FEE-013 | MUST | The GA MUST adopt an annual budget of income and expenses and approve the annual accounts. | чл. 11 ал. 1 т. 4 ЗУЕС | BOTH | Budget vs actual report is a first-class screen. |
| PM-FEE-014 | MUST | Charges MUST be computed per billing period with a snapshot of headcount, ideal parts, multipliers and tariff, so a past bill is exactly reproducible. | audit | BOTH | Re-running May 2026 next year reproduces the identical figures. |
| PM-FEE-015 | MUST | Fee changes MUST be effective-dated and MUST NOT alter already-issued bills; corrections MUST be issued as credit or debit notes. | accounting | BOTH | Immutable issued charges. |
| PM-FEE-016 | MUST | All monetary amounts MUST be stored in euro with minor units as integers; historic BGN records MUST retain the original amount, the rate 1.95583 and the converted value. | euro adoption 01.01.2026 | BOTH | No floating-point money; dual display for pre-2026 history. |
| PM-FEE-017 | SHOULD | The system SHOULD support per-unit metered or consumption-based components (heating, water) alongside the statutory keys. | practice | PMC | Consumption lines are separately typed and excluded from the statutory keys. |
| PM-FEE-018 | MUST | The system MUST produce, per unit and per period, an itemised statement showing the key applied, the count used and the derivation of the amount. | transparency | BOTH | Any resident can trace their bill to the rule. |
| PM-FEE-019 | SHOULD | The system SHOULD let the GA set a separate contribution for the operating float distinct from the repair fund, held in a distinct account. | чл. 50 ЗУЕС | BOTH | Two accounts, two ledgers, no transfers without a decision. |
| PM-FEE-020 | MUST | Revenue from advertising or letting of common parts MUST be credited to the condominium, and its use MUST follow a GA decision. | чл. 11 ал. 1 т. 10 ЗУЕС | BOTH | Income is posted to the condominium's ledger, never the manager's. |

### G. FUND — "Repair and Renewal" fund

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-FUND-001 | MUST | The general assembly of owners or of the association MUST create and maintain a **"Ремонт и обновяване"** fund. | чл. 50 ал. 1 ЗУЕС | BOTH | An entrance without a fund raises a compliance flag. |
| PM-FUND-002 | MUST | The fund MUST be financed by monthly contributions set by GA decision, **not less than 1% of the national minimum wage**. | чл. 50 ЗУЕС ⚠ (base per unit vs per owner to confirm) | BOTH | Minimum wage is a dated configuration value; a below-minimum tariff is rejected with the computed floor shown. |
| PM-FUND-003 | MUST | Fund contributions MUST be allocated **by ideal parts**. | чл. 50 ЗУЕС | BOTH | A unit holding 4.20% of ideal parts is charged 4.20% of the period's fund contribution. |
| PM-FUND-004 | MUST | Fund monies MUST be held in a **special-purpose bank account** opened in the name of the chair of the management board (manager) or of the association. | чл. 50 ЗУЕС | BOTH | The account record stores IBAN, holder and mandate; it cannot equal the operating account. |
| PM-FUND-005 | MUST NOT | Fund monies MUST NOT be commingled with a management company's own funds or with another building's funds. | чл. 50 ЗУЕС, fiduciary duty | PMC | Ledger enforces one fund per entrance; cross-entrance postings are impossible. |
| PM-FUND-006 | MUST | Fund monies MAY be spent only on: works under чл. 48–49 and equipment, measures required by the building's technical passport, and other purposes decided by the GA. | чл. 50 ЗУЕС | BOTH | Every fund disbursement requires a purpose code and a linked decision or passport measure. |
| PM-FUND-007 | MUST | Disbursement MUST be authorised by the chair of the management board (manager) on the basis of a GA decision. | чл. 50 ЗУЕС | BOTH | Two-factor approval: decision reference + authorised signatory. |
| PM-FUND-008 | MUST | Emergency repairs MAY be ordered by the manager without a prior decision where the fund has sufficient balance; the system MUST record the emergency justification. | чл. 50, чл. 11 ал. 1 т. 14 ЗУЕС | BOTH | Emergency path checks available balance before releasing the work order. |
| PM-FUND-009 | MUST | The fund balance MUST be shown net of committed but unpaid work orders. | accounting | BOTH | "Available" ≠ "balance"; both are displayed. |
| PM-FUND-010 | MUST | The fund MUST follow the building on a change of manager or management company; termination MUST produce a reconciled balance statement. | чл. 23 ЗУЕС | PMC | Offboarding generates a fund handover statement signed by both parties. |
| PM-FUND-011 | SHOULD | The system SHOULD project fund adequacy against the technical passport's remedial measures and flag underfunding. | Наредба № 5/2006 | PMC | Multi-year plan with a funding gap indicator. |

### H. DEBT — arrears, certificates and enforcement

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-DEBT-001 | MUST | Unpaid contributions MUST accrue as an arrear per unit with a due date derived from the GA decision or the internal rules. | чл. 6 ал. 1 т. 9–10 ЗУЕС | BOTH | Ageing buckets 0–30/31–60/61–90/90+. |
| PM-DEBT-002 | MUST | Where a decision sets no execution term, obligations MUST fall due **14 days** after the decision is announced. | чл. 38 ал. 1 ЗУЕС | BOTH | Due-date engine reads the decision's announcement date. |
| PM-DEBT-003 | MUST | Where an owner, user or occupant fails to comply with a decision in the set term, the chair (manager) MUST be able to apply for a **payment order under чл. 410 ал. 1 т. 1 ГПК**. | чл. 38 ал. 2 ЗУЕС | BOTH | One-click generation of the чл. 410 application packet with the decision, tally and arrears statement attached. |
| PM-DEBT-004 | MUST | The enforcement packet MUST include the decision, evidence of its announcement, the debtor's identification and the itemised claim. | ГПК чл. 410, ЗУЕС чл. 38 | BOTH | Packet validation refuses to export if the announcement act is missing. |
| PM-DEBT-005 | MUST | For a writ of execution to remove an owner, user or occupant, the written warning under **чл. 45 ал. 2 ЗС** MUST be attached. | чл. 38 ал. 3 ЗУЕС | BOTH | The expulsion workflow enforces: GA decision at 75% → written warning → court filing. |
| PM-DEBT-006 | SHOULD | Statutory default interest SHOULD be computed from the due date, with the rate held as dated configuration. | ЗЗД / ПМС rate | BOTH | Interest recalculates on partial payments. |
| PM-DEBT-007 | MUST | The manager MUST be able to issue a **certificate of outstanding obligations** for a unit, dated and signed, for use on sale. | чл. 23 ЗУЕС | BOTH | Certificate generation is logged; the figure is frozen at issue. |
| PM-DEBT-008 | MUST | Payments MUST be allocated to the oldest debt first unless the payer designates otherwise, and the rule applied MUST be visible. | ЗЗД чл. 76 | BOTH | Allocation is explainable per payment. |
| PM-DEBT-009 | MUST | Dunning MUST be staged (reminder → formal notice → decision → чл. 410), with each step evidenced and timestamped. | practice + ЗУЕС чл. 38 | BOTH | The court packet shows a complete escalation history. |
| PM-DEBT-010 | MAY | The GA MAY resolve to remit, defer or reschedule financial obligations. | чл. 11 ал. 1 т. 13 ЗУЕС | BOTH | Write-off requires a protocol reference; no manual balance edits. |
| PM-DEBT-011 | MUST NOT | The system MUST NOT publish debtor names or amounts in any publicly accessible place. | GDPR art. 6, 5(1)(a) | BOTH | Notices posted at the entrance show unit identifiers only if the internal rules permit; personal data is never posted. |
| PM-DEBT-012 | MUST | Arrears MUST attach to the obligation of the person liable at the time of accrual, not automatically to the new owner, and the system MUST make this explicit on transfer. | ЗЗД, practice ⚠ | BOTH | Transfer screen shows pre-transfer and post-transfer balances separately. |

### I. MNT — maintenance, works and the compliance calendar

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-MNT-001 | MUST | Works MUST be classified as **necessary**, **urgent**, **useful** or **luxury**, because the class determines the majority and the funding source. | чл. 11, 17, 48–50 ЗУЕС | BOTH | Class is mandatory on every work order and drives the approval route. |
| PM-MNT-002 | MUST | Necessary works for preserving or restoring the common parts MUST proceed even if the GA votes against them. | чл. 11 ал. 2 ЗУЕС | BOTH | The system escalates rather than closes such a work order. |
| PM-MNT-003 | MUST | Urgent works MUST have an expedited path: manager decision under a standing authorisation, funded from the repair fund, ratified at the next GA. | чл. 11 ал. 1 т. 14, чл. 50 ЗУЕС | BOTH | Emergency work order auto-creates a ratification agenda item. |
| PM-MNT-004 | MUST | Owners MUST grant access to their unit for survey, design, measurement, construction and installation works on the common parts. | чл. 6 ал. 1 т. 12 ЗУЕС | BOTH | Access request workflow with notice period, refusal reason and escalation. |
| PM-MNT-005 | MUST | The GA MUST be able to commission a **technical passport** for existing buildings, and the system MUST track passport existence, validity and the remedial measures it prescribes. | чл. 11 ал. 1 т. 15 ЗУЕС, Наредба № 5/2006 | BOTH | Each passport measure becomes a scheduled compliance task with a fund estimate. |
| PM-MNT-006 | MUST | The system MUST maintain a compliance calendar of statutory periodic obligations per building, each with an owner, due date and evidence document. | ЗУТ, ЗУЕС, sectoral ordinances | BOTH | Overdue obligations appear on the portfolio risk board. |
| PM-MNT-007 | MUST | Lifts MUST be under a maintenance contract with a licensed service body and undergo periodic technical supervision inspections at the statutory interval. | Наредба за безопасната експлоатация и технически надзор на асансьори ⚠ | BOTH | Lift asset carries next-inspection date; a lapse blocks "compliant" status. |
| PM-MNT-008 | MUST | Fire-safety obligations for the common parts MUST be tracked: extinguisher servicing, evacuation signage and plans, clear escape routes. | Наредба № 8121з-647/2014 ⚠ | BOTH | Annual fire-safety task with certificate upload. |
| PM-MNT-009 | MUST | Owners MUST ensure the safe operation of installations and equipment in the building; the system MUST record inspections of boilers, gas, electrical and water installations. | чл. 6 ал. 1 т. 18 ЗУЕС | BOTH | Asset register with inspection cadence per asset type. |
| PM-MNT-010 | MUST | Playgrounds, common green areas and parking within the condominium MUST be included in the asset and inspection register where present. | sectoral ordinances ⚠ | BOTH | Optional asset types enabled per building profile. |
| PM-MNT-011 | MUST NOT | Owners MUST NOT carry out works that impair the load-bearing structure or the architectural appearance, or that seize common parts; the system MUST provide a violation register. | чл. 6 ал. 1 т. 3, 5, 19 ЗУЕС | BOTH | Violation record links photos, the internal-rules version and the constative protocol. |
| PM-MNT-012 | MUST | Energy-efficiency renovation under state or EU programmes MUST require an owners' association and MUST track the application, audit, works and monitoring stages. | чл. 25 ЗУЕС, ЗЕЕ | BOTH | Programme workflow blocks at the association gate. |
| PM-MNT-013 | SHOULD | Work orders SHOULD support tendering: at least the configured number of quotes, comparison and a GA or board approval record. | practice | PMC | Approval above a threshold requires N quotes attached. |
| PM-MNT-014 | SHOULD | Residents SHOULD be able to report defects with photo and location, and see the status of their report. | product | BOTH | Ticket SLA per urgency class. |
| PM-MNT-015 | MUST | The GA MUST adopt a plan for repairs, reconstruction and conversion of the common parts, and amendments to it. | чл. 11 ал. 1 т. 8–9 ЗУЕС | BOTH | Multi-year plan entity linked to budget and fund projections. |
| PM-MNT-016 | MUST | Warranty periods for completed works MUST be tracked with the contractor's liability window. | ЗУТ / contract | PMC | Defect within warranty routes to the contractor, not to the fund. |

### J. REG — statutory registers, filings and reporting

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-REG-001 | MUST | The chair of the management board (manager) MUST file the condominium's data with the municipal or district administration's public register after election, within the statutory term. | чл. 46б ЗУЕС ⚠ (1 month; some administrations state 14 days) | BOTH | Election of a manager creates a filing task with a hard due date and evidence slot. |
| PM-REG-002 | MUST | Changes in the filed data MUST be notified to the administration within the statutory term. | чл. 46б ЗУЕС ⚠ (commonly 14 days) | BOTH | Any change to manager, contact or form of management re-opens the filing task. |
| PM-REG-003 | MUST | The filing MUST include the building identifier, form of management, the management body's members and contacts, the repair-fund contribution amount and a certified copy of the protocol. | чл. 46б ЗУЕС | BOTH | Filing packet generator assembles all elements. |
| PM-REG-004 | MUST | The system MUST integrate with, or at minimum produce filings for, the **Unified Information System of Condominium Ownership (ЕИСЕС)** — a central public electronic register comprising the register of professional managers and the register of condominiums. | чл. 47а ал. 3, чл. 47в, чл. 47д ЗУЕС; Наредба за ЕИСЕС | BOTH | Each entrance stores its unique EIS identification code once assigned. |
| PM-REG-005 | MUST | The system MUST record the association's BULSTAT registration and keep its identifiers with the entity. | чл. 29 ЗУЕС | BOTH | Association without BULSTAT cannot be used as a grant applicant. |
| PM-REG-006 | MUST | The GA MUST adopt the statement of the number of users of properties in the condominium required under **ЗМДТ чл. 67 ал. 15**, and the system MUST generate it. | чл. 11 ал. 1 т. 18 ЗУЕС, ЗМДТ | BOTH | Annual task before the municipal deadline; output matches the municipality's template. |
| PM-REG-007 | MUST | The GA MAY resolve to apply to the municipality for the required number of waste containers; the system MUST track the application. | чл. 11 ал. 1 т. 17 ЗУЕС | BOTH | Container application is a tracked task with a decision reference and a municipal acknowledgement slot. |
| PM-REG-008 | MUST | The system MUST retain acknowledgements from authorities as evidence of timely filing. | ЗАНН defence | BOTH | Filing status is `FILED` only with an acknowledgement reference or upload. |
| PM-REG-009 | MUST | The manager MUST post a **monthly income and expense report** at a visible, publicly accessible place, and the system MUST generate and archive it. | чл. 23 ЗУЕС | BOTH | Missing month raises a compliance flag; PDF is archived and hash-stamped. |
| PM-REG-010 | MUST | The manager MUST keep the income and expenses book together with the monthly reports. | чл. 23 ЗУЕС | BOTH | Ledger export matches the posted reports exactly. |
| PM-REG-011 | MUST | The annual accounts MUST be presented to and approved by the GA. | чл. 11 ал. 1 т. 4 ЗУЕС | BOTH | Approval is recorded as a decision, not a status toggle. |
| PM-REG-012 | SHOULD | Associations SHOULD be supported with their separate statutory reporting obligations as legal persons. | ЗЮЛНЦ/ЗСч analogy ⚠ | PMC | Entity-type-driven report set. |

### K. PMC — professional management company (commercial layer)

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-PMC-001 | MUST | A professional manager MUST be a **trader** entered in the register of professional managers kept in the ЕИСЕС; the system MUST store the registration number and validity. | чл. 47а ЗУЕС | PMC | Company profile without a valid registration shows a blocking banner. |
| PM-PMC-002 | MUST | Registration MUST be treated as time-limited (certificate valid **5 years**) with a renewal task raised well before expiry. | чл. 47а ЗУЕС ⚠ | PMC | 180/90/30-day renewal reminders. |
| PM-PMC-003 | MUST | The professional manager MUST hold **professional liability insurance**, concluded within **15 days** of receiving the registration certificate and submitted within **7 days** thereafter, then renewed without interruption. | Наредба за ЕИСЕС ⚠ | PMC | Insurance record with policy dates; a gap sets the company to `NON_COMPLIANT`. |
| PM-PMC-004 | MUST | Delegation to a professional manager MUST rest on a GA decision taken by more than **50% of ideal parts**, and the system MUST store the protocol. | чл. 19 ЗУЕС | PMC | Building onboarding is blocked without it. |
| PM-PMC-005 | MUST | The management contract MUST define the scope of delegated powers and their limits. | чл. 19 ЗУЕС | PMC | Powers are a checklist that drives in-app permissions for that building. |
| PM-PMC-006 | SHOULD | The management contract term SHOULD be capped at **2 years**, and automatic-renewal clauses SHOULD be treated as void. | 2023 amendments ⚠ | PMC | Contract end date is mandatory; auto-renewal toggle is disabled by default with a legal note. |
| PM-PMC-007 | MUST | The system MUST be multi-tenant at portfolio level with hard data isolation per building; staff access MUST be granted per building. | GDPR art. 32, fiduciary duty | PMC | A property officer assigned to Building A cannot read Building B. |
| PM-PMC-008 | MUST | Each building's money MUST be held and reported separately; the system MUST NOT permit inter-building transfers. | чл. 50 ЗУЕС | PMC | Attempting a cross-building journal entry is rejected at the ledger layer. |
| PM-PMC-009 | MUST | The company's own revenue (management fee) MUST be a distinct ledger from the buildings' funds, invoiced to the condominium or association. | accounting, ЗДДС | PMC | Two ledgers, two document series. |
| PM-PMC-010 | MUST | Offboarding a building MUST produce a complete handover pack: book, protocols, ledgers, fund balance, contracts, compliance history, and MUST NOT be blocked by commercial disputes. | чл. 23 ЗУЕС | PMC | One-click export; the audit trail records what was handed over and when. |
| PM-PMC-011 | SHOULD | The system SHOULD support a portfolio risk board ranking buildings by overdue filings, expiring mandates, fund adequacy and arrears. | product | PMC | One screen answers "which building will bite me this month". |
| PM-PMC-012 | SHOULD | The system SHOULD support standardised service catalogues and SLAs per contract tier, with breach reporting. | product | PMC | SLA breach visible before the client raises it. |
| PM-PMC-013 | MUST | Staff actions taken on behalf of a building MUST be attributable to a named individual, not to the company. | audit | PMC | Every posting carries `acting_user_id` and `on_behalf_of_entrance_id`. |
| PM-PMC-014 | MUST | The system MUST support a self-managed building with no company at all, with the same statutory features and no commercial modules. | product / чл. 9 ЗУЕС | BM | Feature flags by deployment mode; the volunteer manager never sees invoicing. |
| PM-PMC-015 | SHOULD | The self-managed mode SHOULD be usable by a non-specialist: guided GA wizard, pre-filled templates, plain-language deadlines. | product | BM | A first-time domoupravitel can run a lawful GA end to end without reading the statute. |
| PM-PMC-016 | MUST | Where a company acts as cashier, the accountability rules for the cashbox and the annual control-board audit MUST still apply. | чл. 24 ЗУЕС | PMC | Controller retains read access to company-kept records for that building. |
| PM-PMC-017 | MUST | The system MUST record which statutory duties the GA delegated and which remain with the elected chair, since some acts remain personal (e.g. signing the чл. 410 application). | чл. 19, чл. 38 ал. 2 ЗУЕС ⚠ | PMC | Duty matrix per building; undelegatable acts are marked and routed to the chair. |
| PM-PMC-018 | SHOULD | Client (owner) communications SHOULD be templated per building with a full send history admissible as evidence. | practice | PMC | Every notice has a delivery record. |

### L. DOC — documents, evidence and retention

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-DOC-001 | MUST | Every legally significant document MUST be stored immutably with a content hash, author, timestamp and the entity it belongs to. | evidence | BOTH | Re-upload creates a new version; nothing is overwritten. |
| PM-DOC-002 | MUST | Protocols, notices, posting acts, constative protocols, filings and certificates MUST each be a typed document kind with mandatory fields. | чл. 13, 16, 23, 57 ЗУЕС | BOTH | Untyped uploads are not accepted for statutory slots. |
| PM-DOC-003 | MUST | The system MUST generate the constative protocol for a breach, signed by the management board, or by the manager and two owners/users where there is no board. | чл. 57 ЗУЕС | BOTH | Signature requirements adapt to the governance configuration. |
| PM-DOC-004 | MUST | The constative protocol MUST record the person, the breach, and the time, date and place of the act. | чл. 57 ЗУЕС | BOTH | Validation blocks submission with missing elements. |
| PM-DOC-005 | MUST | Documents MUST be retained for the statutory or contractual period, with a defensible deletion log thereafter. | GDPR art. 5(1)(e) | BOTH | Retention policy per document kind, configurable. |
| PM-DOC-006 | SHOULD | Documents SHOULD be exportable as a single, court-ready bundle per matter, in chronological order with an index. | litigation | BOTH | "Export case file" for arrears or expulsion. |
| PM-DOC-007 | MUST | Where the law requires a paper original, the system MUST record its physical location and custodian rather than claim the scan is the original. | чл. 23 ЗУЕС | BOTH | Archive location is a mandatory field on protocols. |
| PM-DOC-008 | SHOULD | Qualified electronic signatures SHOULD be supported and their validation evidence stored with the document. | ЗЕДЕУУ, eIDAS | BOTH | Signature validity report is stored at signing time, not recomputed later. |
| PM-DOC-009 | MUST | Photographic evidence captured in the app MUST retain its capture timestamp and be stored unmodified alongside any compressed display copy. | чл. 13 ЗУЕС, evidence | BOTH | The original bytes are what a court sees; the resized copy is only for the screen. |

### M. SEC — access control, privacy, integrity

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-SEC-001 | MUST | Access MUST be role-based and building-scoped; roles: OWN, USR, OCC, BM, MB, CTL, CSH, PMC staff, MUN (read-only export), SYS_ADMIN. | GDPR art. 32, чл. 7 ал. 4 ЗУЕС | BOTH | Permission matrix is testable and versioned. |
| PM-SEC-002 | MUST | An owner MUST see their own data, their unit's charges and all published condominium-level decisions and reports — and nothing else about other persons. | чл. 7 ал. 4 ЗУЕС | BOTH | Enforced at the query layer, not the UI. |
| PM-SEC-003 | MUST | Ministry of Interior access to book data MUST be possible only through a logged, lawful-request workflow. | чл. 7 ал. 4 ЗУЕС | BOTH | Disclosure record with legal basis and requesting officer. |
| PM-SEC-004 | MUST | The system MUST maintain an append-only audit trail for decisions, votes, money and personal-data access. | GDPR art. 5(2) | BOTH | Audit entries cannot be deleted by any role. |
| PM-SEC-005 | MUST | Processing MUST rest on a documented legal basis per purpose; the system MUST hold a records-of-processing register. | GDPR art. 6, 30 | BOTH | Purpose is attached to each data category. |
| PM-SEC-006 | MUST | A professional manager acting for a condominium MUST be documented as processor or controller as appropriate, with the corresponding agreement. | GDPR art. 26, 28 | PMC | Onboarding checklist includes the data agreement. |
| PM-SEC-007 | MUST | Data-subject rights (access, rectification, erasure where applicable, objection) MUST be serviceable within the statutory deadline. | GDPR art. 12, 15–21 | BOTH | Request workflow with a 30-day clock. |
| PM-SEC-008 | MUST | Personal-data breaches MUST be detectable and reportable within 72 hours. | GDPR art. 33 | BOTH | Incident workflow with the clock started at detection. |
| PM-SEC-009 | MUST NOT | Video surveillance of common parts MUST NOT be enabled without a lawful basis, a GA decision and signage; the system MUST NOT store footage beyond the configured retention. | GDPR, GA decision ⚠ | BOTH | Camera asset requires a decision reference. |
| PM-SEC-010 | MUST | All data MUST be encrypted in transit and at rest, with tenant-level key separation for professional deployments. | GDPR art. 32 | PMC | Encryption settings are asserted by an automated compliance check, not by documentation alone. |
| PM-SEC-011 | MUST | The system MUST be able to prove, for any past date, who held which role in a building. | audit | BOTH | Temporal role table. |
| PM-SEC-012 | MUST NOT | Personal data from the owners' book MUST NOT persist on a device beyond the session. Only the viewer's own data MAY be cached for offline use. | чл. 7 ал. 4 ЗУЕС, GDPR art. 5(1)(c) | BOTH | A stolen phone exposes one household's data at most, never the building's. |

### N. SYS — cross-cutting system behaviour

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-SYS-001 | MUST | Every statutory threshold, deadline, multiplier and monetary constant MUST live in dated configuration with a source citation, never as a literal. | maintainability | BOTH | Changing the minimum wage or a majority requires no code deploy. |
| PM-SYS-002 | MUST | Legal constants MUST be **temporal**: the engine MUST apply the value in force on the relevant legal date, not today's value. | rule-of-law | BOTH | Recomputing a 2024 charge uses the 2024 minimum wage and the 2024 quorum rule. |
| PM-SYS-003 | MUST | The system MUST support Bulgarian as the primary UI and document language; English MAY be offered, but statutory documents MUST be produced in Bulgarian. | practice / ЗАНН | BOTH | Document templates are Bulgarian-first. |
| PM-SYS-004 | MUST | All dates MUST be handled in Europe/Sofia local time for legal deadlines, with UTC storage. | correctness | BOTH | A 7-day notice never slips by a timezone hour. |
| PM-SYS-005 | MUST | Deadline arithmetic MUST use calendar days unless the statute says otherwise, and MUST apply the rule for deadlines falling on a non-working day. | ГПК чл. 60 ⚠ | BOTH | Single, tested deadline utility used everywhere. |
| PM-SYS-006 | MUST | The system MUST NOT let a user "skip" a statutory precondition; it MAY let them record a documented deviation with a reason, which is then visible in compliance reporting. | governance | BOTH | Overrides are evidence, not silence. |
| PM-SYS-007 | MUST | Notifications MUST be delivered through at least one legally sufficient channel plus convenience channels, and delivery MUST be evidenced. | чл. 13, 16 ЗУЕС | BOTH | Physical posting remains a first-class, recorded action. |
| PM-SYS-008 | SHOULD | The system SHOULD provide a "legal health score" per building aggregating overdue filings, missing internal rules, expired mandates, missing passport, fund adequacy and GA cadence. | product | BOTH | One number a manager can act on. |
| PM-SYS-009 | MUST | Administrative penalties MUST be modelled as a risk register per obligation, showing exposure, since acts are drawn by the municipal administration and penal decrees issued by the mayor under ЗАНН. | чл. 55–57 ЗУЕС, ЗАНН ⚠ | BOTH | Fine ranges are configuration; the UI states they must be verified. |
| PM-SYS-010 | MUST | Statutory content MUST be versioned in the app so that a change in the law produces a migration plan, not silent behavioural drift. | maintainability | BOTH | Rule catalogue version is displayed in the footer and stored on every decision. |
| PM-SYS-011 | SHOULD | The system SHOULD run a nightly compliance sweep that materialises upcoming and overdue statutory tasks per building. | product | BOTH | Tasks appear without anyone remembering to create them. |
| PM-SYS-012 | MUST | Reports and exports MUST be reproducible: the same period re-exported yields the same figures unless a documented correction intervened. | audit | BOTH | Snapshot-based reporting. |
| PM-SYS-013 | MUST | A push notification, email or in-app message MUST NOT be treated as delivery of a statutory act. The posting act with its evidence is the delivery; electronic channels are convenience only. | чл. 13, 16 ЗУЕС, PM-SYS-007 | BOTH | Marking a notice "sent by push" does not advance the assembly out of `DRAFT`; only a recorded posting act does. |
| PM-SYS-014 | MUST | Actions queued on a device while offline MUST carry an idempotency key and MUST NOT be shown as performed until the server confirms. | correctness, PM-VOTE-014 | BOTH | A queued vote renders as "изпраща се", never as cast; replaying the queue twice produces one vote. |
| PM-SYS-015 | MUST | The resident surface MUST work on the device and network floor stated in configuration, and CI MUST fail a build that exceeds the payload budget for that floor. | accessibility, product | BOTH | Budget is a dated config value; a bundle regression breaks the build, not the pilot. |

### O. AI — the live manager agent

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-AI-001 | MUST NOT | The AI MUST NOT be recorded as manager, chair, controller or cashier in any protocol, contract or statutory register. Those offices are held by natural persons or registered traders. | чл. 19, 46б, 47а ЗУЕС | BOTH | A mandate cannot be assigned to a non-human actor; the agent has no role in the permission matrix, only a delegation from one. |
| PM-AI-002 | MUST | Every AI-produced act with legal effect MUST be released by a named human holding the corresponding role, and the release MUST record actor, timestamp and the exact content approved. | чл. 19, 23, 38 ЗУЕС | BOTH | A notice cannot be posted, minutes published, fund money released or a чл. 410 packet exported without a human release record. |
| PM-AI-003 | MUST | Every AI capability MUST be classified AUTONOMOUS, HUMAN_RELEASE or PROHIBITED, and the runtime MUST refuse any call outside the capability's class. | governance | BOTH | A capability registry is the enforcement point; adding a capability without a class fails the build. |
| PM-AI-004 | MUST NOT | The AI MUST NOT state a legal threshold, deadline, majority or penalty that does not resolve to a rule ID and its dated constant. | PM-SYS-001, PM-SYS-002 | BOTH | An answer containing an unsourced legal number is blocked before it reaches the user. |
| PM-AI-005 | MUST | AI answers MUST be grounded only in that building's own records and the catalogue version in force, and MUST cite the source document or rule ID. | evidence, PM-SYS-010 | BOTH | With no grounding, the answer is "that is not in this building's records" — never a plausible reconstruction. |
| PM-AI-006 | MUST | The AI MUST execute under the permissions of the requesting user, never those of the manager or the company. | чл. 7 ал. 4 ЗУЕС, GDPR art. 32 | BOTH | A resident asking about a neighbour's household gets a refusal and an audit entry, even though the agent could read it for the manager. |
| PM-AI-007 | MUST | Every AI interaction MUST be logged as evidence: prompt, retrieved sources, model and catalogue versions, output, and the releasing human where one applied. | GDPR art. 5(2), ЗАНН defence | BOTH | Any AI-authored document reaching a resident is reconstructible years later. |
| PM-AI-008 | MUST NOT | The AI MUST NOT post a financial entry, move money or alter an issued charge. It MAY only propose a draft posting for human release. | чл. 50 ЗУЕС, PM-FUND-007, PM-FEE-015 | BOTH | The agent has no write credential on any ledger or payment rail. |
| PM-AI-009 | MUST | Statutory deadlines MUST come from the deterministic temporal engine, never from model inference. The model MAY draft the content of a task, never compute its due date. | PM-SYS-002, PM-SYS-005 | BOTH | Disabling the model changes no due date anywhere in the system. |
| PM-AI-010 | MUST | AI-drafted statutory documents MUST be produced in Bulgarian and MUST pass the document kind's mandatory-field validation before a human is offered the release. | чл. 13, 16, 57 ЗУЕС, PM-SYS-003 | BOTH | A draft protocol missing attendance percentages never reaches the release screen. |
| PM-AI-011 | MUST | Residents MUST be told they are interacting with an automated assistant and MUST have a route to the human manager. | EU AI Act art. 50 transparency ⚠ | BOTH | Disclosure on first contact per session; a visible escalation control at all times. |
| PM-AI-012 | MUST NOT | The AI MUST NOT decide, vote, exercise a majority, or represent an owner by proxy. It MAY compute and display a tally. | чл. 14, 17 ЗУЕС | BOTH | The agent cannot be registered as a proxy holder and cannot cast a ballot. |
| PM-AI-013 | MUST | Personal data sent to an external model provider MUST be minimised and covered by a processing agreement; the system MUST support a no-external-processing mode per building. | GDPR art. 28, 44–49 | BOTH | A building can be switched to local-only inference without losing the deterministic features. |
| PM-AI-014 | SHOULD | The system SHOULD measure each capability's draft-acceptance, correction and escalation rates, and SHOULD auto-demote a capability from AUTONOMOUS to HUMAN_RELEASE on regression. | product, safety | PMC | A capability whose correction rate crosses the threshold is demoted automatically and the owner is notified. |

### P. LAW — watching the statute

| ID | Mod. | Rule | Source | Scope | Acceptance / app behaviour |
|---|---|---|---|---|---|
| PM-LAW-001 | MUST | Statute changes MUST be detected automatically and applied manually. The watcher MAY open tasks and draft diffs; it MUST NOT write a rule, a constant or a majority threshold. | governance, PM-SYS-001 | BOTH | The watcher's database role has no write access to `legal_constant`, `majority_rule` or the rule catalogue. |
| PM-LAW-002 | MUST | Every charge, decision and compliance task MUST store the catalogue version and the constant versions that produced it. | PM-SYS-002, PM-SYS-010 | BOTH | Opening any past charge shows which law version computed it; two charges from different versions are visibly different. |
| PM-LAW-003 | MUST | Scheduled numeric values that change on a known cycle — the national minimum wage, the statutory default interest rate — MAY be applied automatically, but only as a **new dated row**, never as an edit to an existing one. | PM-SYS-001/002 | BOTH | An automatic update inserts `in_force_from` = the official effective date and closes the previous row; no historical figure moves. |
| PM-LAW-004 | MUST | Each source MUST be polled on a schedule, its content hashed, and the retrieved document archived with its retrieval timestamp and URL as evidence. | evidence, ЗАНН defence | BOTH | You can show, years later, what the law said on the day you computed a charge. |
| PM-LAW-005 | MUST | A detected change MUST produce an impact report: changed article → affected rule IDs → affected source files and tests. | PM-SYS-010 | BOTH | A change to чл. 51 names PM-FEE-002, PM-FEE-005, PM-FEE-010 and the files tagged with those IDs, without a human searching. |
| PM-LAW-006 | MUST | A rule change MUST be released only after a named human with legal sign-off approves it, and the approval MUST record the ДВ issue and article. | PM-AI-002 analogue | BOTH | The catalogue version cannot be published without an approval row carrying a source reference. |
| PM-LAW-007 | MUST | Publishing a new catalogue version MUST NOT alter any historical computation. | PM-FEE-014, PM-SYS-002 | BOTH | After a catalogue upgrade, re-running a past period reproduces its original `basis_hash`. |
| PM-LAW-008 | MUST | If a source is unreachable for the configured number of consecutive runs, the system MUST raise an alert. Silence MUST NOT be treated as "no change". | operational | BOTH | A dead scraper is a visible incident, not a quiet gap in coverage. |
| PM-LAW-009 | SHOULD | The agent MAY draft the amended rule text and the migration note; it MUST NOT approve them. | PM-AI-002, PM-AI-004 | BOTH | Draft arrives as an `action_proposal` with the source article quoted and cited. |
| PM-LAW-010 | SHOULD | Bills in progress SHOULD be watched alongside published amendments, so a change is known before it is in force. | product | PMC | A bill touching ЗУЕС raises a low-priority watch item months ahead of the ДВ publication. |

---

## 6. Sanctions exposure (model as configuration, verify before use)

| Obligation breached | Liable | Indicative range | Source |
|---|---|---|---|
| Owner/occupant fails to comply with ЗУЕС duties or internal rules | natural person | lower band | чл. 55 ЗУЕС ⚠ |
| Same, legal person | legal person | higher band | чл. 55 ЗУЕС ⚠ |
| Failure to file the declaration for the book (чл. 7 ал. 3) | owner/user | mid band | чл. 55 ЗУЕС ⚠ |
| Disturbing the internal order | natural/legal person | mid band | чл. 55 ЗУЕС ⚠ |
| Failure to file/update data in the municipal register | manager/chair | higher band | чл. 55–56а ЗУЕС ⚠ |
| Carrying out professional management without registration | trader | highest band | чл. 55/56 ЗУЕС ⚠ |
| Professional manager without valid liability insurance | trader | highest band | чл. 56в ЗУЕС ⚠ |

> Published sources disagree on the exact figures and on which amounts were redenominated into euro on 1 January 2026. **Do not hard-code any figure in this table.** Model it as `SanctionRule { code, subject_type, min_amount, max_amount, repeat_multiplier, currency, in_force_from, source_ref }` and populate from a verified consolidated text.

**Procedure to encode:** breach observed → constative protocol by the management board, or by the manager plus two owners/users (чл. 57) → act drawn by the municipal/district administration → penal decree issued by the mayor or an authorised official → appeal and enforcement under ЗАНН.

---

## 7. Open questions to resolve with counsel before release

1. Exact deadline in чл. 46б for the initial municipal filing (1 month vs 14 days) and for change notifications.
2. Maximum number of principals one proxy may represent (чл. 14).
3. Whether the repair-fund minimum of 1% of the minimum wage is per unit, per owner or per household.
4. Precise majority for granting use rights over, or changing the purpose of, common parts.
5. Current euro-denominated sanction ranges under чл. 55–56в and their entry-into-force dates.
6. Status and deadlines of the ЕИСЕС ordinance and whether an API exists for machine filing.
7. Whether the 2-year cap and the nullity of auto-renewal apply to all professional management contracts or only to closed complexes.
8. Treatment of arrears on transfer of ownership — which obligations follow the unit and which follow the person.
9. Whether an entrance may lawfully adopt a per-unit key for maintenance costs, and with what majority.
10. Retention periods mandated for condominium documents versus GDPR minimisation.

---

## 8. Sources

- Закон за управление на етажната собственост — consolidated text as notified to EUR-Lex (NIM:202507017).
- MRRB — ЗУЕС page, model internal rules, book and declaration templates.
- Наредба за единната информационна система на етажната собственост — public consultation, strategy.bg 10957.
- Commentary on the ДВ бр. 82/2023 amendments (Труд и право; DPC; MH Legal; Адвокат Рашков).
- Case-law annotated текстове на чл. 38, 40, 50, 51, 57 ЗУЕС.
