---
name: zues-slice
description: "Plan and execute one implementation slice on the ЗУЕС condominium platform, or any multi-session build where context drift between sessions is the main risk. Use at the start of every session, before writing code."
---

# Drift-proof slice

The risk in a long multi-session build is not difficulty. It is that a later session forgets what an earlier one decided and quietly contradicts it. Memory cannot be trusted. Every step below replaces memory with something checkable.

## 1. Boot — before anything else

Read, in this order. Never continue from memory, never skip because the topic feels familiar.

1. `ЗУЕС 00 — Index` in the vault — what exists, what is superseded
2. `ЗУЕС — Session Log` — the top entry only
3. `Property Management App - Developer Brief` — the words and the nine non-negotiables
4. The domain notes under `ЗУЕС Rules/` **for this slice only**
5. `docs/adr/` in the repo — decisions already made
6. `git log --oneline -10` and any failing tests

If the vault is unreachable, say so and stop. Do not reconstruct context from the conversation.

## 2. State the slice contract — before writing code

Write this out and get confirmation. It is the cheapest place to catch a wrong reading.

```
Slice:        S-nn <name>
Service:      one service only
Rules:        PM-XXX-000 … (quote each rule's text back, do not paraphrase)
Interprets:   how I read each rule, in one line each
Out of scope: what this slice deliberately does not touch
Done when:    the exit checks in §4 pass
```

Quoting the rules back is not ceremony. A misread rule caught here costs a sentence; caught after the code costs the slice.

## 3. Size

- One service per slice. Never two.
- Maximum ~400 lines of reviewable diff. Generation is free; human review time is not.
- If the slice will not fit, split it and say so rather than writing more.

## 4. Exit checks — all must pass

- Every rule in the contract has a test named after its ID
- Tests pass
- No rule ID in code that is absent from `rules.json`
- No banned word in an identifier (`tenant`, `user` as a domain term, `fee`, `balance` as a stored column, `building` as the tenant key)
- No unverified rule's number written as a literal — config lookup plus `TODO(legal): PM-XXX-000`
- No cross-service database access
- Counts and lists in docs are generated, never typed

## 5. Fresh-context review

Before declaring the slice done, review the diff against the rule texts as if someone else wrote it. Re-reading your own code with the intent still in mind finds nothing. Where a subagent is available, hand it the diff and the rule texts with no other context.

## 6. Close — append to the session log

Append, never edit an earlier entry:

```
## S-nn · YYYY-MM-DD · <slice name>
**Did** — one or two lines
**Rules covered** — PM-XXX-000 …
**Tests added** — names
**Decisions** — ADR-nnnn, or "none"
**Open** — what the next session must pick up
**Read first next time** — two or three notes
```

## 7. Standing rules

- **Never invent a legal threshold.** No rule covering the case means stop and ask.
- **One fact, one home.** A fact written in two documents will drift. Link instead of restating.
- **Generate, never type.** Counts, service lists, traceability tables.
- **Read before patching.** A heading-replace patch wipes the section. Read it first.
- **No code before its ADR.** If a decision is not recorded, record it or stop.
- **The repo is the source of truth**, the vault is the record of why, the conversation is disposable.

## 8. When a rule and existing code disagree

The rule wins. Raise a change plan before editing. If the rule itself looks wrong, that is a finding for counsel, not a licence to code around it.