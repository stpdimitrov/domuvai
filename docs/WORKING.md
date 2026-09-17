# How two or three people work on this

> **The repository is the shared context.** Conversation context is never shared between people — not memory, not chat history, not session state. Everything a session needs to boot is committed here, or the next person works blind. Written for the three-developer team; the rules are identical for two, and the coordination cost grows with the third person.

## What shares, and what does not

| Shares | Does not |
|---|---|
| `CLAUDE.md` — loaded by every session | Conversation context |
| `.claude/skills/` — committed project skills | Memory, chat history |
| `docs/adr/`, `docs/RULES.md`, `rules.json` | Session state |
| Tests, hooks, CI gate pack | Anything in a personal vault or on one machine |

Coordination happens through pull requests, exactly as between humans.

## Boot order — every session, both people

1. `CLAUDE.md`
2. `docs/INDEX.md`
3. `docs/SESSIONLOG.md` — top entry only
4. `docs/DEVBRIEF.md`
5. `docs/RULES.md` — this slice's domain only
6. `docs/adr/`
7. `git log --oneline -10`, current branch, failing tests

## The loop

```
slice contract → branch → code + tests → exit checks → PR → review → merge → session log
```

- One module per slice. One branch per slice. One PR per slice. **No two developers open the same module at once** — claim the slice first (a GitHub Issue from `docs/TESTPLAN.md`).
- **Never push to `main`.** Push the branch, open the PR, let CI gate it, merge when green — branch protection requires it. The direct-to-`main` shortcut a lone session may take does not scale past one person (the second push is rejected).
- **Rebase on `origin/main` before you push or update a PR**, so conflicts surface in your session, not at merge.
- **The slice contract is the PR description** — the reviewer sees intent and the quoted rule texts, not just a diff.
- The session log entry commits with the slice.
- `git worktree` (`claude --worktree <name>`) for isolated parallel sessions.
- CI is the referee. It does not care who wrote the code or which model produced it.

## The one true serialisation point

**A8 — event schemas — must be merged before any parallel module work.** Two people building against an uncommitted event contract is the fastest route to the drift this whole protocol exists to prevent. Everything else parallelises. This cannot.

## Conflict surfaces — the files that fight on merge

Different modules barely touch, but a few shared files change on almost every slice. Do not hand-merge them:

| File(s) | On a conflict |
|---|---|
| Generated docs — `TRACEABILITY.md`, `TESTPLAN.md`, `FUNCTIONAL.md`, `api/openapi.json`, `docs/events/*` | They are derived — never hand-merge. Take `origin/main`'s copy, re-run the generators (the gate pack does), commit the regenerated result. |
| Schema | Do not edit an applied migration. Each schema change is a **new** file `V<yyyyMMddHHmm>__desc.sql`; `V1__init.sql` is the baseline. The schema gate reads every `V*.sql` (`CREATE TABLE` and `ALTER TABLE … ADD COLUMN`), so two developers' schema work never shares a file. |
| `docs/SESSIONLOG.md` | Append-only, `merge=union` (`.gitattributes`) — both entries survive. Add yours at the end; never edit an earlier one. |
| `law/…/Constants.kt`, `rules.json` | Structured and rarely changed — coordinate the change; do **not** union-merge (it would break the syntax). |

## Splitting the work

The foundation (the A-plan) is built; work is now module slices from `docs/TESTPLAN.md`. Split by **module** — the only place code truly collides — so each developer owns a different lane and there is almost nothing to merge:

| Developer | Module lane (example) |
|---|---|
| **A** | `money` · `rail` — the ledger |
| **B** | `assembly` · governance — the decision |
| **C** | `registry` · `intake` — the record and the import |

Only three of fourteen modules have code, so there is ample non-overlapping surface; rebalance the lanes as modules fill. Three developers give roughly **50–65%** of single-developer calendar, not a third — integration, review and event-contract coordination eat the rest, and the coordination cost rises with the third person.

## Skills

Committed to `.claude/skills/`, not a personal account, so both people get the same version.

| Skill | Mode |
|---|---|
| `zues-slice` | Build — boot, contract, exit checks, close |
| `zues-audit` | Verify — read-only, never edits |
| `zues-adr` | Decide — house ADR format, traced to rule IDs |

`zues-audit` is separate on purpose. Building and checking need different mindsets; reviewing your own work with the intent still in mind finds nothing.

**No generic backend or frontend skill.** A general-purpose coding skill advises what `CLAUDE.md` forbids — plausible defaults, invented constants. The constraint here is legal traceability, not general code quality.

## Model per job

| Job | Model |
|---|---|
| ADRs, schemas, review, anything iterative | **Opus 5** — 82 s to first token, and TTFT is paid per turn |
| Long module slices | **Fable 5.1**, but measure first |

Run one real slice on both and measure. It costs about $25 and decides roughly 114 hours. Whole-build token spend is $490–742 either way, so price is not the variable — wall clock is.

## Before the others start

- [x] Docs in `docs/` · `CLAUDE.md` current · `.claude/skills/` populated
- [x] A8 event schemas merged · CI gate pack green
- [ ] **Each developer runs `gh auth login`** — so their Claude session can push branches and open PRs (a session with no GitHub auth falls back to direct-to-`main`, which does not scale)
- [ ] **Branch protection on `main`:** PRs required, the gate-pack CI run a required check
- [ ] ADR-004 and ADR-007 remain Proposed pending counsel — do not build `maintenance`/`rail` against them
