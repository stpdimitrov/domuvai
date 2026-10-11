# ADR-002 — One hand-written policy module, with row-level security as the backstop

| | |
|---|---|
| **Status** | **Accepted** · 2026-09-13 · amended 2026-10-10 (what AUTH-04 built — see the amendment at the end) |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | — |
| **Blocks** | A12 scaffold, A18 identity-org, A19 registry, every read of owners'-book data |

---

## 1 Context

чл. 7 ал. 4 ЗУЕС does not describe a role list. It describes a **relationship**: the management board and the manager may read the book, the controller may read all condominium documentation (PM-GOV-008), an owner may read *their own* data (PM-SEC-002), and the Ministry of Interior may read it only through a logged lawful request (PM-SEC-003). Firm staff get access **per building**, not per firm (PM-PMC-007), and every act is attributable to a named individual, never to the company (PM-PMC-013).

Hand-rolled relationship-based authorization is where security defects concentrate, which argues for an engine. Three facts argue against.

**The relation set is fixed by statute and small.** Zanzibar-style engines earn their complexity when users create arbitrary sharing graphs at runtime — share this document with that person. Nothing here is shared at runtime. The relations are the ones чл. 7 ал. 4 enumerates, and adding one requires an amendment to the law.

**The hard requirement is temporal, not relational.** PM-SEC-011: the system must prove, for any past date, who held which role in which building. Mandates expire at two years (PM-GOV-004); grants change. Policy engines model *now*. Asking one to answer "who could read this in March 2025" is the same fight ADR-001 had with rules engines, for the same reason.

**Authorization sits in the hot path of every read.** A network hop to an external decision point on every book access is latency the p95 budget in Stage 1 §7 cannot absorb.

---

## 2 Decision

**One hand-written policy module — `packages/policy` — holding the чл. 7 ал. 4 matrix exactly once, evaluated against effective-dated role grants. Postgres row-level security is the independent backstop.**

Two layers, and they fail independently:

| Layer | Scope | Catches |
|---|---|---|
| **RLS on `entrance_id`** | Coarse, at the database | Any cross-entrance leak, including one caused by a bug in the policy module |
| **`packages/policy`** | Fine, in the application | Who inside an entrance may see what, as at which date, for which purpose |

That independence is the point. A defect in the policy module cannot leak data across entrances, because the database refuses the rows regardless. No single mistake is sufficient.

The policy module takes `(subject, action, resource, as_at_date)` and returns a decision plus the rule ID that produced it. The date is a required argument, not a default — resolving a past authorization question is an ordinary call, not a special path.

---

## 3 Options considered

| Option | Temporal queries | Hot-path cost | Verdict |
|---|---|---|---|
| **OpenFGA / Zanzibar-style** | Models *now*. Answering PM-SEC-011 means replaying tuple history, which is not what it is for | Network hop per check | Rejected. Pays for runtime sharing flexibility that a statute forbids us from using |
| **Cedar / Oso — embedded policy DSL** | Better, still awkward as-at | In-process, acceptable | Rejected, narrowly. The policy is a dozen statutory rules; a DSL puts a translation layer between чл. 7 ал. 4 and the code, and rule IDs end up outside the type system |
| **Framework RBAC (guards + roles)** | None | Cheap | Rejected. чл. 7 ал. 4 is relationship plus time. A role name cannot express "their own unit" |
| **Hand-written policy module + RLS backstop** ✅ | A required argument | In-process | **Chosen.** One implementation, rule IDs in the types, and a second independent layer that does not share its bugs |

---

## 4 Consequences

**Good**

- One implementation of the matrix. `packages/policy` is named in the repo layout for exactly this reason — the same check cannot drift between modules.
- A past authorization question is an ordinary call with a date argument.
- Every decision returns the rule ID that produced it, so a disclosure log records *why*, not only *what*.
- No vendor in the hot path, no external decision point to operate.

**Bad, and accepted**

- We own the correctness. An engine would have brought a tested evaluator; we bring tests instead.
- A future requirement for genuine runtime sharing — an owner delegating read access to their accountant, say — would strain the model and should reopen this.

**How it is enforced**

1. No module implements its own permission check. Importing anything other than `packages/policy` for an authorization decision fails the dependency rule in CI.
2. Every read of owners'-book data writes a disclosure record with actor, purpose, timestamp and rule ID (PM-BOOK-007).
3. RLS is on for every tenant-scoped table, with a test that attempts a cross-entrance read as each role and expects zero rows.
4. A test replays a past date and asserts the role set matches the grants in force then (PM-SEC-011).
5. ЕГН and full addresses are absent from every list-shaped response by type, not by filter (PM-BOOK-011).

---

## 5 What would reverse this

- A requirement for owner-initiated runtime sharing outside the statutory matrix.
- More than about three consumers of the policy module outside this codebase — at which point an external decision point starts to pay for itself.

---

## 6 Deferred

- Whether the Ministry of Interior lawful-request workflow (PM-SEC-003) is a policy path or a separate audited export. Decide when the first request arrives; until then it is not implemented at all.

---

## Amendment · 2026-10-10 · What AUTH-04 built — where roles come from, who the administrator is, and what "sign-in off" means

**Why this is here.** §2 decides the shape: one policy module, `(subject, action, resource, as_at_date)` → a decision and its rule, row-level security as the backstop. It does not say where a role comes from, who gives the first one, or what happens where nobody is signed in. Slices AUTH-04a…e (#155, #160, #165, #167, #177, #180; 2026-10-09/10) had to settle those to build anything. They were settled in the session, recorded on issues and in `docs/SESSIONLOG.md`, and built — **before** this record. That is the wrong order (no code before its ADR), and this amendment is the catch-up: it records decisions already made and running, dated as such. Nothing here is new.

**Deciders.** Stoyan Dimitrov decided the points marked *owner* below (2026-10-09 in the AUTH-04 session, recorded on #151; 2026-10-10 through the coordinating session, recorded on #171 and #178). The points marked *session* were decided by the building session, stated in each slice's contract before it merged, and stand until the owner says otherwise. **Status of the amendment: Accepted** for the owner's points; the session's points are recorded here so that they can be accepted or reversed deliberately rather than inherited silently.

### 1 Roles are derived, not copied — *owner*

Three options were put (#151): **A** derive each role from the fact that already says it; **B** whoever creates an entrance or commits its import becomes its manager; **C** a separate table of grants for every role. **A was chosen.** B makes a self-declared manager with no protocol behind it; C keeps each fact twice — a title and an OWN grant, a mandate and a BM grant — and the two drift.

| Role (PM-SEC-001) | Comes from | In force on a date |
|---|---|---|
| OWN · USR | `registry.title` (PM-ORG-011) | `[valid_from, valid_to)` |
| BM · MB | `identity_org.management_mandate` | from `valid_from`; past `valid_to` until a successor (PM-GOV-004) |
| CTL · CSH | `identity_org.management_mandate` | `[valid_from, valid_to)` |
| SYS_ADMIN | the deployment's configuration | while configured — not dated (see §6 below) |
| OCC · PMC staff · MUN | nobody holds them | until their modules exist |

The policy module reads no table and imports no other module. The modules that own the facts answer it through one port, `RoleSource` (`TitleRoles` in `registry`, `MandateRoles` and `Administrators` in `identity_org`), and each answer names its entrance; an answer for another entrance is dropped, not trusted. So §2's "role grants" are not a table: STAGE1 §5's `role_grant` is not built and is not planned.

*Session:* a mandate's last day is excluded · a successor is the day recorded on the mandate, or failing that the first day of a later executive mandate of the entrance · the controller and the cashier do not continue past their dates — PM-GOV-004 names the board and the manager only · mandates are recorded in the order they began, and a new executive mandate ends the ones it succeeds (*owner*, 2026-10-10: it stamps the predecessor automatically; PM-GOV-003 and PM-GOV-006 are not enforced at recording — they are `assembly`'s).

### 2 The administrator — *owner*

SYS_ADMIN is the login, or logins, named in `DOMUVAI_AUTH_ADMINS` — subjects of the api's own issuer. **Never created through the API**: nobody promotes themselves. It may do exactly two things — **tie and untie a login to a party**, and **record and end a mandate of an entrance** from its protocol — and it **does not read the book**: чл. 7 ал. 4 does not name it (PM-BOOK-006). This is how the first manager of an entrance comes to exist: the operator records the mandate from the paper protocol.

*Session:* the administrator is a login, not a party, and its login is never tied to one — tied, it would hold that party's roles · its lines in the matrix are carried on their own kind of resource only (a login is the deployment's, a mandate an entrance's), and nothing an entrance's source says can make an administrator · every tie, untie, record, end and refused attempt by a signed-in login is an insert-only entry (`identity_org.login_act`, `identity_org.mandate_act`; PM-SEC-004), written in the act's own transaction · a mandate's row is kept by a trigger: added, its end recorded once, nothing else changed, never removed (PM-SEC-011).

### 3 The matrix, and its version — *session*

The matrix is data in one place, `Policy.MATRIX`, with `Policy.MATRIX_VERSION`; a test pins every line to the number, so a change to the matrix is a change to the version (PM-SEC-001: "testable and versioned"). Every decision carries the version and the rule that decided; a refusal cites the rule that governs the action. Version 4, as running:

| Action | Who | Rule |
|---|---|---|
| read an entrance's book, its access log, the owners list, the overdue-declarations list | BM · MB · CTL | PM-BOOK-006 |
| read one unit's entry in the book | BM · MB · CTL · an OWN holder of that unit | PM-BOOK-006 |
| anonymise what is past its retention window | BM · MB | PM-BOOK-010 |
| tie or untie a login | SYS_ADMIN | PM-SEC-001 |
| record or end a mandate of an entrance | SYS_ADMIN | PM-SEC-001 |

Everything else is refused. *Owner* (2026-10-10, #178): the two names lists are read by the manager, the board and the controller; **the retention pass is run by the manager and the board only** — the controller reads all and writes none (PM-GOV-008), the administrator does neither; every served and refused read of the lists is entered in `registry.book_access` with the reader from the sign-in and a **fixed purpose naming the list**, since a screen states none (PM-BOOK-007).

*Session:* who may is decided **as at the day of the access, in Sofia** — never the date the book or a list is asked for; a former manager does not read "as of" his own term · the reader is the party the api's own record ties the token's login to, never a parameter and never a claim · the decision comes first and a refused caller learns nothing else — not whether the entrance exists, nor what the request lacked · a refusal is a 403 and an entry that stays · the guard is on what an HTTP caller is given; other modules that use a module's services in-process are not callers.

### 4 With sign-in switched off — *owner* for the lists and the retention pass, *session* for the book

`DOMUVAI_AUTH=off` (ADR-011) means there is no token, so nobody is asking and nothing can be decided. "Off" is the mode said in so many words; a setting that is missing or misspelt is not off.

| | With sign-in off |
|---|---|
| the book, its access log | refused (*session*, AUTH-04b) |
| the owners list, the overdue-declarations list | served, and entered as given to nobody — outcome `OPEN` (*owner*, Q3) |
| the retention pass | **refused** — "an act that cannot be undone never runs with nobody accountable" (*owner*, Q3) |
| tying a login, recording a mandate | refused: there is no login, so no administrator (*session*) |

The lists stay served so that the console works on a developer's machine, where no identity provider runs. The cost, accepted: one more thing that depends on that switch being off in production — the application refuses to start open unless told, and says so loudly when it is.

### 5 How it is enforced — what §4 promised, and what exists

- **One implementation of the matrix:** holds. `ModularityTests` verifies `policy` depends on no module.
- **A disclosure record with actor, purpose, timestamp and rule (§4.2):** holds for the book, its log, the two lists and the retention pass.
- **A test replays a past date (§4.4):** holds — `MandateRolesIT`, `MandateAdministrationIT`.
- **Every slice:** a fresh-context security review before merge, and mutants that must be killed — "always allow", "the date ignored", "the actor taken from the caller" among them.
- **Not yet true — §4.1 and §4.3:** no CI rule forbids a module from deciding access on its own, and **row-level security is not built**. Until it is, the policy module is the only lock, and §2's "no single mistake is sufficient" does not yet hold.

### 6 Deferred — still open after AUTH-04

- **Row-level security** (§2, §4.3) — its own slice.
- **The unguarded operations** — only the nine named in §3 ask the policy; every other operation of the api asks only for a valid token. Listed by priority in #164, with owners' names still reaching callers through the money module's statements and arrears (PM-SEC-002).
- **For counsel:** whether "the owner for their own data" (PM-BOOK-006) is the whole entry of the owner's unit or only the person's own line — the matrix has the line, no endpoint serves it · whether the control board continues in office past its term as the management board does (чл. 24) · whether PM-SEC-011's "prove, for any past date" means as the record now stands or as it was known on the day — a mandate recorded late changes the answer for days already past.
- **For the owner:** an administrator can untie a manager's login and tie another login to that party, and can record a mandate on an invented protocol reference; both are on the record, and nothing reads or alerts on the record yet.
- **PM-SEC-011 is not met for SYS_ADMIN:** who was an administrator on a past day is configuration, not dated data.
- A reader-stated purpose for the names lists; an owner's read of their own data; a resident directory (PM-BOOK-011); who may see an entrance's units; no separate database role guards the insert-only tables against the database's owner.
- §6's Ministry of Interior request (PM-SEC-003): unchanged — not built, and refused.

### 7 What would reverse this amendment

- A second source for the same role appears — a grants table beside titles or mandates — or a role is needed that no existing fact says (firm staff per building, PM-PMC-007): that reopens §1.
- An administrator needs to act for more than one deployment's issuer, or must be provable for a past date: that reopens §2 in favour of dated data.
- An identity provider can run wherever the console runs: then §4's exception for the lists has no reason left, and they are refused with sign-in off like everything else.

---

## Amendment · 2026-10-11 · After the audit — the term is the law's, and "successor" has one reading

A read-only audit of the 2026-10-10 amendment's code (a fresh session, at `main` 223476d) found three places where what was built and what was written disagreed. Each is corrected in its own slice; this records what the first two changed in the decisions above — the third (the administrator and a login already tied) adds its own lines when it lands. **Deciders:** Stoyan Dimitrov for the points marked *owner* (2026-10-11, through the coordinating session); the building session for the rest.

- **§1's table — "past `valid_to` until a successor (PM-GOV-004)".** The 2026-10-10 text read a successor as "the first day of a later executive mandate of the entrance". That was one of two readings in the code: the recording side never ended a board member still in term, the reading side ended an expired one on any later executive mandate — so a member lost the office at term end because a colleague had been by-elected. *Owner* (2026-10-11): **a board member past term continues until a recorded end, a later sole manager, or a board member whose mandate begins on or after that member's own last day; a by-election during the term succeeds nobody.** "Last day" is built as the day the term ends — `valid_to`, the first day outside it. One function, `Mandate.succeeds`, now says it, and both sides ask it (AUD-02, #194): the recording side on the day a successor is recorded, so it ends a manager or a member even within their own dates; the reading side only of a mandate whose dates have run out and that nobody ended. *Session:* a firm's mandate succeeds a manager always and a board member as another member would. The accepted risk and the question for counsel are `docs/RULES.md` §7, question 11.
- **The mandate's term.** It lived only as an SQL check, against PM-SYS-001 and PM-SYS-002. It is now dated constants in `law` — the board's and the manager's (PM-GOV-004), the controller's (PM-GOV-007), and the cashier's, unconfirmed (*owner*, 2026-10-11: the cashier keeps the cap) — read on the mandate's first day where a mandate is recorded; the SQL check is dropped (*session*), and gate 8 reads migrations for durations (AUD-01, #190).
- **§2 — "the administrator … its login is never tied to one".** As written on 2026-10-10 this held only where a tie is made: a subject already tied to a party could be added to `DOMUVAI_AUTH_ADMINS`, and one login would then read the book and say who everybody is. *Session* (AUD-03, #199): it now holds whichever came first. **At decision time**, a login tied to a party never administers, named or not — this alone closes the finding, for a tie made before the list was written and for one made after by a path that does not ask. Of the two powers the tied login keeps its party's: that rests on a mandate or a title, the administrator's on a line of configuration. **At start**, the application also looks every named administrator up in its own record of ties and **does not come up** if one is tied, naming the subject: it closes nothing further, it makes the mistake seen when it is made instead of leaving an administrator who is quietly refused everything. Accepted with it: a tie written for an administrator's subject by SQL, or by code that does not ask, stops the api at its next restart — recovered by taking the subject off the list or removing the tie; whoever can write that table can already do worse.
