# ADR-008 — Every majority carries its denominator explicitly

| | |
|---|---|
| **Status** | **Accepted** · 2026-09-13 |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | — |
| **Blocks** | A15 `@zues/law` majorities, the `assembly` module, the decision and tally schema |

---

## 1 Context

The subtle one. It fails quietly, months later, when a decision is challenged.

**Bulgarian law uses two denominators.** Some thresholds run against **all** ideal parts, others against the parts **represented** at the meeting (PM-VOTE-002's default: more than 50% of the parts represented). PM-VOTE-012 forbids satisfying an all-parts threshold from the represented subset. A percentage alone cannot tell them apart, and the arithmetic passes either way — which is why the error survives review.

**The report must say which one was used.** PM-VOTE-011: tallies are computed on ideal parts, reported to 2 decimals, and must show the denominator — `62.41% of 78.10% represented; threshold 51% of total — NOT PASSED`. That line is the exhibit; a stored percentage cannot reconstruct it.

**The represented denominator moves between sittings.** The quorum ladder — PM-GA-012 (51% of ideal parts), PM-GA-013 (26% after one hour), PM-GA-014 (any share the next day) — gives the same assembly a different represented total in each session; PM-GA-015 raises quorum to 75% where one person holds more than 51%. A decision that does not record **which session** it belongs to is not reproducible — PM-VOTE-016.

**The result is not final when announced.** Absentee votes merge within 7 days (PM-VOTE-013); the tally declared in the room is provisional until it closes.

**Binding happens before the meeting.** PM-VOTE-001: each agenda item is bound to its majority rule before the meeting opens, and an unbound item cannot be put to a vote. A denominator chosen while counting is chosen by whoever counts.

---

## 2 Decision

**A majority is a typed value in `@zues/law`, never a bare percentage.**

```
MajorityRule = { threshold, denominator: TOTAL | REPRESENTED, exclusions }
```

Bound to the agenda item at notice time (PM-VOTE-001), applied by the pure `tallyVote`. Every tally row stores:

| Stored | Why |
|---|---|
| `rule_id`, `law_version`, `engine_version` | Which rule, which data, which code — ADR-001 |
| `threshold`, `denominator`, `exclusions` | The rule as applied, not as looked up today |
| `total_ideal_parts`, `represented_ideal_parts` | Both denominators — the represented one **for this session** |
| `session_number` | Which rung of the ladder — PM-GA-012/013/014, PM-VOTE-016 |
| `status` · `PROVISIONAL` \| `FINAL` | The absentee window — PM-VOTE-013 |

**Both denominators are stored on every tally**, including the unused one: the line names the one applied (PM-VOTE-011), and a reader must be able to check the other years later.

---

## 3 Options considered

| Option | Shape | Verdict |
|---|---|---|
| **A · Bare percentage** | `threshold` | **Rejected.** Cannot distinguish TOTAL from REPRESENTED, so PM-VOTE-012 is unenforceable and the PM-VOTE-011 line cannot be generated. Precisely how decisions get annulled under чл. 40 |
| **B · Percentage + boolean** | `threshold, ofRepresented` | **Rejected.** Two denominators fit; exclusions do not. PM-VOTE-006 removes the affected person's shares — a third state no boolean expresses, so it migrates into caller code and drifts |
| **C · Typed `MajorityRule` value** ✅ | `{threshold, denominator, exclusions}` | **Chosen.** The distinction sits in the type: the wrong denominator is a compile error, not a court finding. Exclusions have a home, and the value is dated data in `@zues/law` |

---

## 4 Consequences

**Good**

- A class of annulment risk becomes unrepresentable: no call site can pass a percentage without saying against what.
- The result line, the decision record and the чл. 40 defence are one object, generated once.
- The quorum ladder and PM-GA-015's concentration case are data, not branches in `assembly`.

**Bad, and accepted**

- Every majority in VOTE must be enumerated in `@zues/law` before the feature using it ships: no default, no fallback.
- Exclusions need ownership resolved at the record date, coupling the tally to `registry` history.
- `PROVISIONAL` is a visible product state: the chair wants a number in the room and cannot have a final one for 7 days.
- The session number threads through the assembly state machine, easy to lose on a reconvened sitting (PM-GA-013/014).

**How it is enforced**

1. `tallyVote` accepts `MajorityRule` only: no numeric overload, so a bare percentage does not compile.
2. An agenda item without a bound rule cannot open for voting — constraint plus a test on PM-VOTE-001.
3. A table-driven suite runs **every** majority in VOTE against both denominators, asserting a TOTAL rule is never satisfied from the represented subset (PM-VOTE-012) and exclusions applying before the ratio.
4. A tally row missing either denominator, the session number or the status fails the constraint (PM-VOTE-016); the rendered line is snapshot-tested to 2 decimals and names the denominator (PM-VOTE-011).
5. Publishing a result as `FINAL` is refused while the absentee window is open (PM-VOTE-013).

---

## 5 What would reverse this

A statutory change collapsing the two denominators into one. `denominator` becomes a constant and the type degrades to a percentage — a deletion, not a migration; every stored tally stays readable, because it recorded what it used.

Repeal of absentee merging removes `status` alone. Neither touches the value's shape.

---

## 6 Deferred

- The exclusion set beyond PM-VOTE-006, and whether any exclusion moves the *denominator* too.
- How proxies count into represented ideal parts.
- Whether merging absentee votes amends the tally or supersedes it. Append-only says supersede; the chair will want one number.
- The rounding convention behind the 2-decimal report: `@zues/law`, not a formatter.
