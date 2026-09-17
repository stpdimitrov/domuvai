---
name: zues-adr
description: "Write or evaluate an architecture decision record in the domuvai house format, traced to rule IDs. Use before writing code that depends on a decision not yet recorded — no code before its ADR is Accepted — or to record a technology, topology or design choice with real trade-offs. Produces the house structure (context · decision · options · consequences · what would reverse it) and keeps Status honest."
---

# Decide, on the record

A decision that is not written down is relitigated in code — quietly, differently, by whoever touches the file next. An ADR records the decision, the forces behind it, the options rejected and *why*, and what would reverse it, so a later session builds on it instead of re-deciding it. **No code before its ADR is Accepted.**

## When an ADR is required

- A technology, topology, data-model or boundary choice with real trade-offs — anything you would otherwise "just decide" in code.
- A rule and existing code disagree **and the code is right**: the rule may be wrong. That is an ADR, and possibly a question for counsel — never a silent code change.
- A cross-cutting constraint that future slices must honour (an isolation key, a money invariant, an enforcement mechanism).

Not for a reversible local choice with no downstream cost. An ADR is for decisions expensive to unwind.

## The house format — follow it exactly

Number sequentially: the next free `ADR-0nn`, filename `docs/adr/ADR-0nn-<kebab-title>.md`.

```
# ADR-0nn — <one-line decision, stated as the outcome>

| | |
|---|---|
| **Status** | **Proposed** · YYYY-MM-DD |
| **Date** | YYYY-MM-DD |
| **Deciders** | <named person; and counsel/owner when they co-decide> |
| **Supersedes** | <ADR or doc section, or —> |
| **Blocks** | <what cannot proceed until this is Accepted> |

## 1 Context
The forces, not opinions. Cite the rule IDs (PM-XXX-000) and ADRs the decision must satisfy. State the operating reality (team size, pilot status) when it drives the choice.

## 2 Decision
The decision, in the imperative. What changes, and — as important — what explicitly does not.

## 3 Options considered
| Option | Complexity | Fixes <the core problem> | Verdict |
|---|---|---|---|
| A · … | | | Rejected — why |
| B · … ✅ | | | **Chosen** |

The distinction between the chosen option and its nearest rival is the whole decision — make it explicit.

## 4 Consequences
**Good** — what this buys. **Bad, and accepted** — the costs taken on with eyes open. **How it is enforced** — the CI gate, test or constraint that keeps it true (a boundary CI does not enforce is a comment).

## 5 What would reverse this
The measured trigger — not a judgement call — that would flip the decision.

## 6 Deferred
What this ADR deliberately does not settle, and where it is settled instead.
```

## Keep Status honest

- Status starts **Proposed**. It moves to **Accepted** only when every named decider has accepted.
- If counsel or the owner is a co-decider, it **stays Proposed until they answer** — accepting without the answer is recording a decision nobody made. (ADR-004 and ADR-007 stay Proposed on counsel; do not build against them.)
- An Accepted ADR is not rewritten. A later change is an **`## Amendment · YYYY-MM-DD · <title>`** appended to it — the original decision and its date stay legible.

## Close

- Add the ADR to the table in `docs/INDEX.md`, and — when it changes what code may assume — to "Decisions already made" in `CLAUDE.md`. One fact, one home: link, do not restate the reasoning.
- Every force cited as a rule ID must exist in `rules.json`.
