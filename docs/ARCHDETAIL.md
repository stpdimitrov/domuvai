# Architecture, in detail

**ЗУЕС condominium platform · 12 September 2026**

Five drawings. One shows what runs; three show what happens when something is actually done; one shows the shape every service shares. The per-service contracts are in the Developer Brief and are not repeated here.

---

## 1 · Deployment and data ownership

Thirteen deployables. Every service owns exactly one database and reads no other. The `law` package is a pinned dependency, not a call.

```mermaid
flowchart TB
  subgraph CL["Clients"]
    PWA["Resident + manager PWA<br/>mobile-first · installable<br/>offline queue · presigned upload"]
    CONSOLE["Firm console<br/>desktop · dense · keyboard"]
  end

  GW["<b>gateway</b><br/>authn · grants → entrance_id<br/>token mint · rate limit · access audit<br/><i>holds no domain data</i>"]

  BFF["<b>bff</b><br/>per-screen projections<br/>home · bill · assembly · portfolio"]
  BFFDB[("bff_db<br/>read models only<br/>rebuildable by replay")]

  subgraph REC["The record — survives a change of firm"]
    LAW["<b>law</b><br/>catalogue versions · dated constants<br/>deadline calculator · source watcher"]
    LAWDB[("law_db<br/>constants · majority rules<br/>obligations · source archive")]
    REG["<b>registry</b><br/>condominium · entrance · unit<br/>ideal_parts · owners book<br/>household · animal · occupancy"]
    REGDB[("registry_db")]
    IDO["<b>identity-org</b><br/>party · title · role_grant · mandate<br/>association · firm · contract · insurance"]
    IDODB[("identity_db")]
    EVI["<b>evidence</b><br/>document · content hash · retention<br/>audit_event · disclosure · case file"]
    EVIDB[("evidence_db")]
    OBJ[("object storage<br/>content-addressed<br/>object lock")]
  end

  subgraph ACT["The acts — legal effect"]
    ASM["<b>assembly</b><br/>assembly · session · proxy · vote<br/>majority_rule · protocol · appeal clock"]
    ASMDB[("assembly_db")]
    MON["<b>money</b><br/>tariff · charge_run · journal · posting<br/>renewal fund · receivable · dunning"]
    MONDB[("money_db")]
    MNT["<b>maintenance</b><br/>asset · inspection · passport measure<br/>work_order · vendor · warranty"]
    MNTDB[("maintenance_db")]
    NOT["<b>notify</b><br/>notice · posting act<br/>delivery record"]
    NOTDB[("notify_db")]
  end

  subgraph WAT["The watchers — own no truth"]
    CMP["<b>compliance</b><br/>obligation · task · filing<br/>risk board · sanctions register"]
    CMPDB[("compliance_db")]
    AGT["<b>agent</b><br/>capability registry · action_proposal<br/>grounding · quality metrics"]
    AGTDB[("agent_db")]
    RAIL["<b>rail</b><br/>payment_intent · bank_event<br/>reconciliation_candidate"]
    RAILDB[("rail_db")]
  end

  PKG[["@zues/law — pinned package<br/>constants · deadlines · majorities<br/><i>consumed by version, never over HTTP</i>"]]

  BUS[["Event bus · envelope carries entrance_id, law_version, causation_id"]]

  subgraph EXT["External"]
    PSD2["PSD2 partner<br/>licensed — we are not"]
    MODEL["Model provider<br/>DPA · per-entrance opt-out"]
    EIS["ЕИСЕС + municipality"]
    QES["QES provider<br/>Evrotrust / B-Trust"]
  end

  PWA --> GW
  CONSOLE --> GW
  GW -->|"reads, one call per screen"| BFF
  GW -->|"writes · JWT carries entrance_id"| ACT
  GW --> REC
  GW --> WAT

  BFF --- BFFDB
  LAW --- LAWDB
  REG --- REGDB
  IDO --- IDODB
  EVI --- EVIDB
  EVI --- OBJ
  ASM --- ASMDB
  MON --- MONDB
  MNT --- MNTDB
  NOT --- NOTDB
  CMP --- CMPDB
  AGT --- AGTDB
  RAIL --- RAILDB

  LAW -.publishes.-> PKG
  PKG -.-> MON
  PKG -.-> ASM
  PKG -.-> CMP

  REC --> BUS
  ACT --> BUS
  RAIL --> BUS
  BUS --> CMP
  BUS --> EVI
  BUS --> NOT
  BUS --> BFF

  RAIL <--> PSD2
  AGT --> MODEL
  CMP --> EIS
  ASM --> QES
```

**Read it as three bands.** The record is what a building keeps when it fires its management firm. The acts are the things with legal effect. The watchers own nothing, so a slow filing can never block a charge run.

**The two powerless services.** `agent` has no write credential anywhere — it produces proposals. `rail` never holds a balance — money lands in the entrance's own IBAN, because чл. 50 puts that account in the chair's name.

---

## 2 · How a tenant is resolved

Nothing in the system runs before `entrance_id` is known. This is the one path that must never have an exception.

```mermaid
sequenceDiagram
  autonumber
  participant U as Resident
  participant G as gateway
  participant I as identity-org
  participant S as any service
  participant D as service_db

  U->>G: request + session token
  G->>I: which grants does this party hold, as of now?
  I-->>G: grants[] with scope and valid_to
  Note over G: mandate expired → grant absent → no entrance
  G->>G: mint short-lived token<br/>entrance_id + role
  G->>S: call + token
  S->>S: tenant guard reads entrance_id<br/>opens transaction
  S->>D: SET LOCAL app.entrance = …
  D-->>S: RLS now filters every row
  S->>D: the actual query
  D-->>S: rows for this entrance only
  S-->>G: result
  G->>G: write access audit record
  G-->>U: response
```

**Two locks, not one.** The guard sets the entrance; row-level security enforces it. A handler that forgets a `WHERE` clause returns nothing rather than another building's ledger.

**Expiry is data.** A mandate ends and the grant is simply absent. No job runs, nothing has to remember.

---

## 3 · Closing a vote — the hardest path

One human action fans out across four services. This is the flow that justifies the outbox, and the one a monolith would have done in a single commit.

```mermaid
sequenceDiagram
  autonumber
  participant M as Manager
  participant A as assembly
  participant B as Event bus
  participant C as compliance
  participant MO as money
  participant N as notify
  participant E as evidence

  M->>A: close agenda item
  A->>A: tally on ideal parts<br/>apply majority rule + denominator
  Note over A: 51% of TOTAL, or of REPRESENTED?<br/>stored explicitly, no default
  A->>A: write vote tally, decision,<br/>and outbox row — one transaction
  A-->>M: provisional result
  Note over A: absentee ballots may still arrive<br/>for 7 days — result is PROVISIONAL
  A->>B: DecisionTaken
  B->>C: DecisionTaken
  C->>C: deadline from @zues/law,<br/>never from a model
  C->>C: raise execution task<br/>due = stated term, else +14 days
  B->>MO: DecisionTaken
  MO->>MO: tariff version now has a protocol<br/>→ becomes billable
  A->>A: draft protocol within 7 days
  A->>B: ProtocolAnnounced
  B->>N: ProtocolAnnounced
  N->>N: post notice, capture photo act
  N->>E: store evidence, original bytes
  B->>E: audit the decision
  E->>E: appeal clock = announced_at + 30 days
```

**The cost of microservices, stated plainly.** In one database this is one commit. Split, it needs the outbox, idempotency keys and replay tooling — budget two extra weeks, and build them before the first gate.

**What the events carry** — every message has `entrance_id`, `law_version` and `causation_id`, so any decision can be reconstructed years later with the law that produced it.

---

## 4 · A resident pays — and we never touch the money

```mermaid
sequenceDiagram
  autonumber
  participant R as Resident
  participant P as PWA
  participant RA as rail
  participant PS as PSD2 partner
  participant BK as Bank
  participant EN as Entrance IBAN<br/>(chair's name, чл. 50)
  participant MO as money
  participant C as Cashier

  R->>P: tap Плати
  P->>RA: create intent (amount + reference from the ledger)
  RA->>PS: initiate payment
  PS-->>P: hand off to the bank app
  P->>BK: deep link
  BK->>EN: transfer
  BK-->>P: return by deep link
  Note over P,RA: the return is untrusted —<br/>it is a hint, not a fact
  PS->>RA: account information: bank_event
  RA->>RA: match to intent and open receivable
  RA->>MO: BankEventMatched → reconciliation candidate
  MO-->>C: candidate awaiting release
  C->>MO: release the match
  MO->>MO: journal posting — only now does it exist
```

**Three things this drawing is arguing.** We never have a balance, so no e-money licence and no float in the model. The bank event is the truth, not the browser redirect. And a human releases the posting — the agent may rank and explain candidates, it may not release them.

---

## 5 · Inside every service

The same skeleton, everywhere. Learn it once.

```mermaid
flowchart LR
  API["HTTP API<br/>OpenAPI spec<br/>Idempotency-Key required"]
  EVC["Event consumer<br/>idempotent on event_id"]
  GUARD["<b>Tenant guard</b><br/>reads entrance_id<br/>opens txn · SET LOCAL app.entrance<br/><i>nothing runs before this</i>"]
  CH["Command handlers<br/>// Rule: PM-XXX-000"]
  DOM["Domain<br/>pure functions<br/>no clock · no I/O · no model"]
  PKG[["@zues/law<br/>pinned version"]]
  REPO["Repository<br/>tenant-scoped queries only"]
  OUT["Outbox<br/>same transaction as the write"]
  PUB["Publisher<br/>drains outbox → bus"]
  DB[("service_db<br/>RLS by entrance_id<br/>no UPDATE on append-only tables<br/>domain constraints in SQL")]

  API --> GUARD
  EVC --> GUARD
  GUARD --> CH
  CH --> DOM
  PKG -.-> DOM
  CH --> REPO
  CH --> OUT
  REPO --> DB
  OUT --> DB
  OUT --> PUB
```

**Where correctness is enforced.** Not in review — in the database. Ideal parts summing to 100.000000 is a deferred trigger. One entrance per journal is a check constraint. Append-only is a missing `UPDATE` grant. A wrong number cannot be written, rather than being caught later by someone reading carefully.

---

**Companion documents** — Developer Brief for the per-service contracts and the event catalogue; Stage 1 Baseline for standards, schema spine and non-functionals.
