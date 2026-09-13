# How two people work on this

> **The repository is the shared context.** Conversation context is never shared between people — not memory, not chat history, not session state. Everything a session needs to boot is committed here, or the second person works blind.

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

- One module per slice. One branch per slice. One PR per slice. Two people never open the same module at once.
- **The slice contract is the PR description** — the reviewer sees intent and the quoted rule texts, not just a diff.
- The session log entry commits with the slice.
- `git worktree` (`claude --worktree <name>`) for isolated parallel sessions.
- CI is the referee. It does not care who wrote the code or which model produced it.

## The one true serialisation point

**A8 — event schemas — must be merged before any parallel module work.** Two people building against an uncommitted event contract is the fastest route to the drift this whole protocol exists to prevent. Everything else parallelises. This cannot.

## Splitting the work

| | Actions | Why |
|---|---|---|
| **Person A** | A6 → A12 → A13 → A14 → A15 → A20 | The critical path. Lose a lost week off this line |
| **Person B** | A9 OpenAPI · A10 DDL · A24 evidence · A22 notify | Genuine slack |
| **Both** | Gates A21, A26, A30, A35 | A gate is a stop for everyone |

Two people give roughly **60–70%** of single-person calendar, not 50%. Integration and event-contract coordination eat the rest.

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

## Before the second person starts

- [ ] Docs in `docs/` — done
- [ ] `CLAUDE.md` current — done
- [ ] `.claude/skills/` populated — done
- [ ] The eight Proposed ADRs Accepted
- [ ] A8 event schemas merged
- [ ] A13 CI gate pack green on an empty repo
- [ ] Branch protection on, PRs required
