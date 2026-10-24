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

- Run `python3 tools/lanes.py`. Work only in a **free** lane — nobody is assigned one — and heed its warnings (a PR working in a lane nobody holds).
- Write the slice contract (§3) to a file, then claim the lane with it: `python3 tools/lanes.py claim <lane> --title "S-nn <name> (<lane> · <rule ids>)" --body-file <contract.md>`. If it names another holder, or says you lost the race, stop and pick another lane.
- `git fetch && git switch -c slice/S-nn-<name> origin/main`. Never work on `main`. The PR says `Closes #n`, so merging it frees the lane.

## 3. State the slice contract — before writing code

Write out the contract in the shape `CLAUDE.md` gives (Slice · Module · Branch · Rules · Interprets · Out of scope · Done when) and get confirmation. Quote each rule's text from `docs/RULES.md` verbatim.

Quoting the rules back is not ceremony. A misread rule caught here costs a sentence; caught after the code costs the slice.

## 4. Size

One module per slice, ~400 lines of reviewable diff at most. Generation is free; human review time is not. If the slice will not fit, split it and say so rather than writing more.

## 5. Exit checks — all must pass

Run every item under **Exit checks** in `CLAUDE.md` and report each one pass/fail with evidence. A check you did not run is a fail, not a pass.

## 6. Fresh-context review — before the slice can merge

Before declaring the slice done, review the diff against the rule texts as if someone else wrote it. Re-reading your own code with the intent still in mind finds nothing. Hand the diff and the rule texts, verbatim and with nothing else, to the `zues-reviewer` agent (`.claude/agents/zues-reviewer.md` — read-only, a cheaper model than the one that wrote the code): `git diff origin/main... > <scratch>/slice.diff`, then the path and the texts. Or run `zues-audit` in a fresh session.

Apply the findings before the PR can merge. If you open the PR while the review runs, so CI starts on what only CI can run, open it as a **draft**; it is yours to mark ready the moment the findings are in (§7), and never the owner's to merge. A PR merged before its review was applied is not re-opened: land the review as a follow-up slice `S-nn-r` — its own claim, a new migration for any schema change, a new log entry — as S-G1-03c-r (#45) and S-G1-02d-r (#60) did.

## 7. Close — session log, PR, merge. Merging is yours

Write the entry as a new file, `docs/sessionlog/<yyyyMMddHHmm>-<slice id>.md` (the real UTC minute: `date -u +%Y%m%d%H%M`) — never editing an earlier entry, never appending to the archive `docs/SESSIONLOG.md` — and commit it with the slice:

```
## S-nn · YYYY-MM-DD · <slice name>
**Did** — one or two lines
**Rules covered** — PM-XXX-000 …
**Tests added** — names
**Decisions** — ADR-nnn, or "none"
**Open** — what the next session must pick up
**Read first next time** — two or three documents
**Cost** — $x · context yk   (the session's own figures: `get_session` → usage.cost_usd, context_usage)
```

Then deliver it. **The owner never merges a PR, and is never asked to watch for one: from the push to the next slice, delivery is the session's job.**

1. **Bring `main` in:** `git fetch && git merge origin/main` — never rebase a pushed branch, never force-push. A conflict in a generated doc: take `origin/main`'s copy and re-run the generator, never hand-merge. The session log cannot conflict: each entry is its own file. Run `tools/check.sh <lane>` (the gate pack, quiet, the lane's tests) — one validated push per round; when the API contract changed, also `cd web && npm run build` — the gate pack does not build the web client, and a field the spec marks required breaks every caller that omits it (S-G1-02e, #63). Then push the branch (never `main`). Fix conflicts without asking.
2. **Open the PR** — the slice contract is its description, and it says `Closes #n` — and **switch auto-merge on (squash) at once**. The ruleset on `main` requires `gates`, so GitHub merges only when CI is green. If GitHub refuses auto-merge with "clean status", CI is already green: merge it now, `gh pr merge <n> --squash`. Turn the app's CI monitor on (auto-fix) for the PR.
3. **Keep it moving until it merges.** A failing check or a conflict wakes the session through the CI monitor: fix it, run the gates, push. A draft waiting on the review is marked ready (`gh pr ready <n>`) with auto-merge on as soon as the findings are applied. Nothing is left for the owner to do.
4. **Never poll CI** — no watch loops, no scheduled checks. The session is not woken when a PR merges, so whenever it is next woken — the owner's message, a CI event, a subagent's report — it looks at the open PR once (`gh pr view <n>`) and carries on: merged → step 5; green and still open → merge it; failing → fix it. It never asks the owner to report that a PR merged.
5. **After the merge:** sync `main` (`git switch main && git merge --ff-only origin/main`), confirm the claim issue closed — and **end the session**. The next slice starts in a new session that boots from the repo (§1): the handover and the log entries carry everything; a session carried on re-reads its whole growing context on every call. Nothing is stacked on unmerged work.

Repository settings (auto-merge, rulesets) and Claude's own permissions stay the owner's to change.

## 7a. A question for the owner

A decision no rule settles — who may see or do something, what a word in a rule means — is the owner's. Put it as an issue every session sees: `python3 tools/lanes.py ask --title "…" --body-file q.md`, the options with the recommended one first, what is blocked meanwhile. Build the strictest reading meanwhile and say so in the PR. When the answer comes (a comment on the issue), quote it with its date in the log entry of the slice that builds it, and close the issue.

## 8. When a rule and existing code disagree

The rule wins. Raise a change plan naming the rule ID, the current behaviour and the correction, and get it confirmed before editing. If the rule itself looks wrong, that is an ADR and a finding for counsel, not a licence to code around it.
