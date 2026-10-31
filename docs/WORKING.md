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
| Generated docs — `TESTPLAN.md`, `FUNCTIONAL.md`, `api/openapi.json`, `docs/events/*` | They are derived — never hand-merge. Take `origin/main`'s copy, re-run the generators (the gate pack does), commit the regenerated result. |
| `docs/TRACEABILITY.md` | Not committed, so it cannot conflict: generated locally by `python3 tools/traceability.py` and by the gate pack. Every slice that adds a test changes its counts, which made any two open PRs conflict. |
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
- A slice that must touch a second lane claims both in one issue: `claim <lane> --also <other lane> …`. Both are held, and both are freed, together. **CI enforces it**: the `gates` job fails a PR that changes a lane its closing claim does not name (`tools/lanes.py covers <pr>`); label the claim issue with the missing lane if it is free, or wait for its holder, then re-run the job.
- A **finding** for another lane is the same label with no assignee: counted on the map, claimed by nobody until someone takes it into a slice.
- A developer who prefers a lane sets `git config zues.lane <lane>`: it is listed first, never reserved.

Only three of fourteen modules have code, so there is ample non-overlapping surface. Three developers give roughly **50–65%** of single-developer calendar, not a third — integration, review and event-contract coordination eat the rest, and the coordination cost rises with the third person.

## Several developers, several sessions

Everything a session needs to take over is in the repository or on its GitHub issues — never only in a conversation. That is what lets the team grow: a developer, a coordinator or a cloud session boots from the same three places.

| What | Its one home | Read by |
|---|---|---|
| Who is working where | claim issues (`lane:<lane>`, assigned) — `python3 tools/lanes.py` | every boot; CI (`covers`) refuses a PR touching an unclaimed lane |
| What each lane last did, and what it left open | its newest entry in `docs/sessionlog/` — the map prints it per lane | every boot |
| What waits on the owner | issues labelled `owner-question` — `python3 tools/lanes.py ask` opens one | every boot (the map lists them) |
| What the owner decided | the issue's answer, then the slice's log entry ("the owner's, <date>: …") and, when code may assume it, an ADR | the slice that builds it; the next boot |
| The whole picture at a point in time | the newest `H-` handover entry in `docs/sessionlog/` | every boot |

- **A question for the owner is an issue, not a chat message.** Options with the recommended one first, what is blocked meanwhile, and the slice that asks — `python3 tools/lanes.py ask --title … --body-file …`. A session that asks builds the strictest reading meanwhile and says so. The answer is a comment on the issue (the owner's, or relayed verbatim by a coordinator); the session that builds it quotes it in its log entry and the issue is closed. Nobody acts on an answer that exists only in a conversation.
- **A handover (`H-nn`) is written** by whoever coordinates, after each batch of merged slices, before any `/compact`, and at least once a day while sessions run in parallel. It says, per lane: what merged since the last handover, what is in flight (PR, claim), what is open (findings, owner questions by issue number, counsel questions), and the next slice proposed. A handover restates no decision: it links the entry or issue that holds it.
- **A coordinator** (optional) starts sessions in their own worktrees with briefs that name the lane, puts questions to the owner through `owner-question` issues, assigns the next slice, and writes the handovers. It writes no slice code. **It is a fresh session too, opened on this repository** so the hooks apply: it boots status-only, does its one job and ends. A long-lived coordinator outside the repository escapes the size gates and pays for its whole context on every note it receives (the one that ran 2026-10-01…31 reached 11 MB and retired at H-13). Sessions without a coordinator follow the same rules on their own.
- **Machine resources are named by the slice**: a scratch database is `domuvai_<slice id>`, created and dropped by the session that needs it; the end-to-end harness's ports (the API's 8081, the web's 3010–3012) belong to one session at a time; a developer's own servers are nobody else's to stop.
- **A decision a session built is recorded where decisions live** — an ADR or an amendment to one (`zues-adr`) — not only in its log entry.
- **Nobody audits their own work**: after a batch of parallel slices, `zues-audit` runs in a fresh session over what was merged.
- **Credentials are each developer's own.** A push refused with an account error is checked first against the identity git actually uses (`git credential fill` — the keychain can hold another account's login), and is never worked around by switching accounts in a session.

## Before you claim, and while you build

- **Claim before you read code.** The claim gate measures the transcript, so a session that reads the module first can be refused before it starts (G-5's first session, 1359k). Contract and plan from the issue or a note, claim, then read only the lines you edit.
- A new dependency on a controller must also be mocked in `WireFormatTest` and `OpenApiContractTest`.
- A change to a summary in `tools/build_openapi.py` needs `npm run gen:api` in `web/`: the gates do not do it, and CI's build fails on a stale `schema.d.ts`.
- The test JVM runs with sign-in off, so a guard is proven only by a `jwt()` test.
- An owner's answer closes its question; if nothing builds it at once, open a `lane:` issue saying so in the same step (#253, #298 and #254 were answered, closed and then built by nobody until a check found them: #382, #383, #384).

## Persistence tests without Docker

The `*IT` tests run on a real Postgres through Testcontainers (`@Testcontainers(disabledWithoutDocker = true)`). On a machine with no container runtime they are **skipped, not run** — the gate pack still says green, and a migration or SQL fault turns up only in CI (#266: a column added twice; every IT and the e2e job failed there). Run the ones your slice touches against a local Postgres 16 before you push:

1. `createdb -h localhost -U postgres domuvai_<slice id>` — a scratch database of your own.
2. Copy the test to `…ScratchLocalIT.kt` beside it: remove the `@Testcontainers(…)` annotation and the `companion object` with the container and its `@DynamicPropertySource`, and rename the class.
3. `DB_URL='jdbc:postgresql://localhost:5432/domuvai_<slice id>?currentSchema=registry,identity_org,assembly,money,maintenance,compliance,evidence,app,intake,public' ./gradlew :app:test --tests '*ScratchLocalIT'` — then check its report shows the tests **run**, not skipped.
4. Delete the copy (never commit it) and `dropdb` the scratch database. Never leave a copy in the tree while running mutants: without `DB_URL` it runs against your own `domuvai` database.

Gate 9 also refuses a migration that adds a column its table already has — the failure #266 hit — on any machine.

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
