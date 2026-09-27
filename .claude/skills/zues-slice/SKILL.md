---
name: zues-slice
description: "Plan and execute one implementation slice on the ЗУЕС condominium platform, or any multi-session build where context drift between sessions is the main risk. Use at the start of every session, before writing code."
---

# Drift-proof slice

The risk in a long multi-session build is not difficulty. It is that a later session forgets what an earlier one decided and quietly contradicts it. Memory cannot be trusted. Every step below replaces memory with something checkable.

This skill is the **procedure**. The lists it applies — boot order, contract fields, exit checks, banned words, standing rules — have one home: `CLAUDE.md`. Read them there; do not restate them here, or they drift.

## 1. Boot — before anything else

Follow **Boot, in order** in `CLAUDE.md`, every document, from the repo. Never continue from memory, never skip because the topic feels familiar.

If a boot document is missing, say so and stop. Do not reconstruct context from the conversation, a personal vault, or anything outside this repo.

## 2. Claim, then branch

- The slice is a GitHub Issue from `docs/TESTPLAN.md`. Confirm it is claimed by this developer.
- Check that no open PR touches this slice's module: `gh pr list --state open`. If one does, stop — one module per developer at a time.
- `git fetch && git switch -c slice/S-nn-<name> origin/main`. Never work on `main`.

## 3. State the slice contract — before writing code

Write out the contract in the shape `CLAUDE.md` gives (Slice · Module · Branch · Rules · Interprets · Out of scope · Done when) and get confirmation. Quote each rule's text from `docs/RULES.md` verbatim.

Quoting the rules back is not ceremony. A misread rule caught here costs a sentence; caught after the code costs the slice.

## 4. Size

One module per slice, ~400 lines of reviewable diff at most. Generation is free; human review time is not. If the slice will not fit, split it and say so rather than writing more.

## 5. Exit checks — all must pass

Run every item under **Exit checks** in `CLAUDE.md` and report each one pass/fail with evidence. A check you did not run is a fail, not a pass.

## 6. Fresh-context review

Before declaring the slice done, review the diff against the rule texts as if someone else wrote it. Re-reading your own code with the intent still in mind finds nothing. Where a subagent is available, hand it the diff and the rule texts with no other context — or run `zues-audit` in a fresh session.

## 7. Close — session log, then PR

Append to `docs/SESSIONLOG.md` — at the end, never editing an earlier entry — and commit it with the slice:

```
## S-nn · YYYY-MM-DD · <slice name>
**Did** — one or two lines
**Rules covered** — PM-XXX-000 …
**Tests added** — names
**Decisions** — ADR-nnn, or "none"
**Open** — what the next session must pick up
**Read first next time** — two or three documents
```

Then `git fetch && git rebase origin/main`, push the branch, and open a PR whose description is the slice contract. On a conflict in a generated doc, take `origin/main`'s copy and re-run the generator — never hand-merge. Merge only when CI is green. Never push to `main`.

## 8. When a rule and existing code disagree

The rule wins. Raise a change plan naming the rule ID, the current behaviour and the correction, and get it confirmed before editing. If the rule itself looks wrong, that is an ADR and a finding for counsel, not a licence to code around it.
