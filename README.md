# domuvai

**Управление на етажна собственост по ЗУЕС.** Софтуер, който третира закона като спецификация.

*Condominium management for Bulgaria, built so that the statute is the specification — not an afterthought.*

> **Изготвя. Вие подписвате.**
> The AI drafts. A named officer signs. It holds no write credential and never touches money.

---

## Quick start (developers)

New here? Three steps:

1. **Clone:** `git clone https://github.com/stpdimitrov/domuvai.git && cd domuvai`
2. **Auth once:** `gh auth login` → GitHub.com · HTTPS · "Authenticate Git"=Yes · Login with a web browser. Without this your Claude session can't open PRs and falls back to pushing `main` directly.
3. **Open Claude Code in the repo** — it loads [CLAUDE.md](CLAUDE.md) automatically. Read the **"Working in parallel"** section it shows you.

Then take one slice from [docs/TESTPLAN.md](docs/TESTPLAN.md), claim it with a GitHub Issue, stay in **your one module**, and build it on a `slice/S-nn-*` branch → `./tools/gates.sh` green → open a PR. **Never push to `main`.**

## Status — backend implementation in progress

**Stage 1 — the rule catalogue, the ADRs, the gate pack — is done; the backend is now built slice by slice.** The pure domain (`:kernel`, `:law`, `:charges`) and the `registry`, `money` and `intake` modules have code; [docs/SESSIONLOG.md](docs/SESSIONLOG.md) is where the last slice stopped.

| | |
|---|---|
| Rule catalogue | **233 rules**, 16 domains, legal baseline 2026-09-03 |
| Unconfirmed numerics | **24** — mechanism built, number in configuration, `TODO(legal)` |
| Architecture decisions | **11**, eight Accepted (ADR-004, ADR-007 await counsel) |
| Modules | **14** in **3** deployables — **3** with code so far |

## Read in this order

| # | Document | Answers |
|---|---|---|
| 1 | [CLAUDE.md](CLAUDE.md) | The words, the banned words, the standing rules. Loaded by every session |
| 2 | [docs/INDEX.md](docs/INDEX.md) | What exists, what is superseded |
| 3 | [docs/SESSIONLOG.md](docs/SESSIONLOG.md) | Top entry only — where the last session stopped |
| 4 | [docs/FUNCTIONAL.md](docs/FUNCTIONAL.md) | **Non-developers start here.** What the app does, in business language |
| 5 | [docs/DEVBRIEF.md](docs/DEVBRIEF.md) | **Developers start here.** The nine things you cannot break |
| 6 | [docs/adr/](docs/adr/) | Every decision, why, and what would reverse it |
| 7 | [docs/RULES.md](docs/RULES.md) | The requirements source. 233 rules with чл. citations |

## The five things that govern everything

1. **Never invent a legal threshold.** No rule covering the case means stop and ask.
2. **The entrance is the isolation unit** — not the building, not the firm. (ADR-005)
3. **The platform never holds money.** чл. 50 puts the fund in the chair's name. (ADR-007)
4. **A legal number exists in exactly one place** — `@zues/law`, dated and versioned. (ADR-001)
5. **Generate, never type.** Counts, service lists, traceability tables.

## Tools

```bash
pip install -r tools/requirements.txt   # once
./tools/gates.sh                        # all nine checks, the same ones CI runs

python3 tools/build_functional.py   # regenerate docs/FUNCTIONAL.md from rules.json — fails if a rule has no module
python3 tools/md2pdf.py <in.md> <out.html> "<title>"
node    tools/buildall.js '[["<in.html>","<out.pdf>"]]'   # fails the build on a Mermaid syntax error
node    tools/mmcheck.js <file.md>                        # parse-check diagrams only
```

## Contributing

One module per slice, one branch, one PR — the slice contract is the PR description. **Never push to `main`; stay in your own module; rebase on `origin/main` before you push.** See the **"Working in parallel"** section of [CLAUDE.md](CLAUDE.md), [docs/WORKING.md](docs/WORKING.md), and the `zues-slice` skill in `.claude/skills/`.

---

*Not legal advice. Rules marked unconfirmed must be verified against the consolidated statute before release.*
