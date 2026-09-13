# law-watch

Detects changes in Bulgarian condominium law. **Never applies them.**

## Why it is split

A wrong legal number produces wrong bills and a fine on your client. A scraper
cannot tell whether "51%" became "50%" or whether the sentence around it changed
meaning. So: detect automatically, apply after a human reads it (PM-LAW-001).

The one exception is scheduled numbers — minimum wage, statutory interest. Those
may be applied automatically, as a **new dated row**, never as an edit
(PM-LAW-003).

## Files

| File | Does |
|---|---|
| `sources.yml` | What to watch, how often, and which two values may auto-apply |
| `watch.py` | Polls, hashes, archives, reports. Writes no rule and no constant |
| `impact.py` | Changed article → affected rule IDs → files and tests |
| `state.json` | Last hash per source (created on first run) |
| `archive/` | What each source said, and when (PM-LAW-004) |

## Use

```bash
pip install pyyaml requests

python3 watch.py --once                 # all sources
python3 watch.py --once --source dv_issues

python3 impact.py "чл. 51"              # what a change to чл. 51 touches
python3 impact.py "чл. 15" "чл. 17" --code ../../src
python3 impact.py "чл. 46б" --json
```

`impact.py` works today against `rules.json` — 228 rules, each carrying its
source article. A change to чл. 51 names ten rules without anyone searching.

## Before the first real run

Each `kind: index` source needs one CSS selector, filled in after looking at the
page once. They are not guessed here on purpose: a guessed selector returns an
empty list and looks like "no change", which is the exact failure PM-LAW-008
exists to catch.

Also check what dv.parliament.bg allows, and whether it offers an export. If
scraping is fragile, buy an API from a legal database (Ciela, APIS, Lex Alert).
An amendment you miss costs more than the subscription.

## The five steps

| Step | Effort | Gives you |
|---|---|---|
| 1. Keyword watcher | 2 days | Daily check of new ДВ issues. **This is 90% of the value** |
| 2. Text diff | 1 week | Which articles actually moved |
| 3. Impact report | done | Article → rules → files → tests |
| 4. Auto-apply scheduled numbers | 3 days | Minimum wage lands as a new dated row |
| 5. Agent drafts the rule change | 1 week | Human approves (PM-AI-002, PM-LAW-009) |

Step 3 is already written. Start with step 1.
