# domuvai

**Управление на етажна собственост по ЗУЕС.** Софтуер, който третира закона като спецификация.

*Condominium management for Bulgaria, built so that the statute is the specification — not an afterthought.*

> **Изготвя. Вие подписвате.**
> The AI drafts. A named officer signs. It holds no write credential and never touches money.

---

## Status — Stage 1, documentation only

**No production code exists yet, and that is deliberate.** Nine architecture decisions had to be recorded first; eight of them are still *Proposed*. Code waits for *Accepted*.

| | |
|---|---|
| Rule catalogue | **233 rules**, 16 domains, legal baseline 2026-09-03 |
| Unconfirmed numerics | **24** — mechanism built, number in configuration, `TODO(legal)` |
| Architecture decisions | **9**, one Accepted |
| Modules | **14**, in **3** deployables |

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
python3 tools/build_functional.py   # regenerate docs/FUNCTIONAL.md from rules.json — fails if a rule has no module
python3 tools/md2pdf.py <in.md> <out.html> "<title>"
node    tools/buildall.js '[["<in.html>","<out.pdf>"]]'   # fails the build on a Mermaid syntax error
node    tools/mmcheck.js <file.md>                        # parse-check diagrams only
```

## Contributing

One service per slice. One branch per slice. One PR per slice. The slice contract is the PR description.
See [docs/WORKING.md](docs/WORKING.md) and the `zues-slice` skill in `.claude/skills/`.

---

*Not legal advice. Rules marked unconfirmed must be verified against the consolidated statute before release.*
