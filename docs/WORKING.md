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

The boot order has one home: **Boot, in order** in [`CLAUDE.md`](../CLAUDE.md). Every session loads it; the `zues-slice` skill follows it.

## The loop

```
slice contract → branch → code + tests → exit checks → PR → review → merge → session log
```

- One module per slice. One branch per slice. One PR per slice. **No two developers open the same lane at once** — claim it first (`tools/lanes.py`, see *Splitting the work*).
- **Never push to `main`.** Push the branch, open the PR, let CI gate it, merge when green — branch protection requires it. The session that opened the PR merges it (auto-merge on; the slice skill's §7) — nobody else is asked to. The direct-to-`main` shortcut a lone session may take does not scale past one person (the second push is rejected).
- **Merge `origin/main` in before you push or update a PR** — never rebase a pushed branch or force-push — so conflicts surface in your session, not at merge.
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
| Schema | Do not edit an applied migration. Each schema change is a **new** file `V<yyyyMMddHHmm>__desc.sql`, stamped with the real UTC minute (`date -u +%Y%m%d%H%M`); `V1__init.sql` is the baseline. The schema gate reads every `V*.sql` (`CREATE TABLE` and `ALTER TABLE … ADD COLUMN`) and fails on a duplicate version — S-41b and S-42 both wrote `…1200` on the same day, which no text merge flags and Flyway refuses. |
| The session log | One file per entry in `docs/sessionlog/`, named by the UTC minute and the slice — two entries are two files and cannot conflict. `docs/SESSIONLOG.md` is the archive before 2026-10-10 (still `merge=union`, for a branch cut before the change) and takes no new entry. |
| `law/…/Constants.kt`, `law/…/Keys.kt`, `rules.json` | Structured and rarely changed — coordinate the change; do **not** union-merge (it would break the syntax). |

## Splitting the work

The foundation (the A-plan) is built; work is now module slices from `docs/TESTPLAN.md`. Split by **module** — the only place code truly collides — so two developers are never in one lane and there is almost nothing to merge. A lane is a module (ADR-003) or the `web` deployable.

**Lanes are claimed, not assigned** — there is no table of names to keep current, and it works the same for one developer or three:

- `python3 tools/lanes.py` prints the live map, read from GitHub: who holds each lane, the free lanes with their next `TESTPLAN.md` slice, and warnings — a PR working in a lane nobody holds, or held by someone else.
- A lane is **held** while an open issue labelled `lane:<lane>` has an assignee: the slice issue, opened by `tools/lanes.py claim` with the slice contract as its body. The PR's `Closes #n` frees it on merge.
- Two sessions claiming one free lane at once: the lower issue number holds it, and `claim` tells the other to close theirs.
- **A claim belongs to the session that opened it, not to a GitHub account.** One owner's sessions all sign in as the same person, so `check` and `claim` treat every open claim as someone else's: a held lane is refused unless the session names the claim it opened itself (`check <lane> --mine <n>`).
- A slice that must touch a second lane claims both in one issue: `claim <lane> --also <other lane> …`. Both are held, and both are freed, together.
- A **finding** for another lane is the same label with no assignee: counted on the map, claimed by nobody until someone takes it into a slice.
- A developer who prefers a lane sets `git config zues.lane <lane>`: it is listed first, never reserved.

Only three of fourteen modules have code, so there is ample non-overlapping surface. Three developers give roughly **50–65%** of single-developer calendar, not a third — integration, review and event-contract coordination eat the rest, and the coordination cost rises with the third person.

## One owner, several sessions

The same rules as for several developers, plus what only shows up when the sessions share one machine and one GitHub account:

- **One session coordinates and writes no slice.** It starts the others, each in its own worktree with a brief that names its lane. A question for the owner goes to the coordinator as one message — the options, the recommended one first, and what is blocked meanwhile — and the owner answers there. A session that has merged a slice reports *Done · Still open · what it would propose next* and stops; the coordinator assigns the next slice. Nobody picks their own.
- **A lane held is a lane held** — see the claim rules above; "it says the lane is mine" is true of every session.
- **Machine resources are named by the slice**: a scratch database is `domuvai_<slice id>`, created and dropped by the session that needs it; the end-to-end harness's ports (the API's 8081, the web's 3010–3012) belong to one session at a time, named in its brief; the owner's own servers (8080, 3000) are nobody's to stop.
- **A decision a session built is recorded where decisions live** — an ADR or an amendment to one (`zues-adr`) — not only in its log entry.
- **Nobody audits their own work**: after a batch of parallel slices, `zues-audit` runs in a fresh session over what was merged.

## Skills

Committed to `.claude/skills/`, not a personal account, so every session gets the same version. They load in a session opened on this repository; a session opened elsewhere (a coordinator, say) links them — `ln -s <repo>/.claude/skills/zues-* ~/.claude/skills/` — and never keeps a copy, which would drift.

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
