# zues-calc — Gate 1 in a command line

Reads a firm's own unit list and tariff, computes the bill with the same pure functions the platform will use, and diffs it against the firm's own figures.

**No database, no HTTP, no services, no authentication.** If this cannot reproduce their spreadsheet to the cent, the rules are wrong and nothing else matters yet.

```bash
npm install
npx tsx apps/zues-calc/src/cli.ts apps/zues-calc/sample/units.csv \
  --tariff apps/zues-calc/sample/tariff.json \
  --compare apps/zues-calc/sample/expected.csv
```

Exit code is non-zero when any unit differs, so it works as a CI gate.

## units.csv

| Column | Meaning | Rule |
|---|---|---|
| `unit_id`, `designation` | identity | — |
| `ideal_parts` | exact decimal percentage, e.g. `12.500000`. Must sum to 100% | PM-ORG-002 |
| `occupants` | persons resident beyond the statutory threshold | PM-FEE-008 |
| `children_under_6` | not counted for management and maintenance | PM-FEE-005 |
| `animals` | each adds one occupant equivalent | PM-FEE-009 |
| `absent_days` | days absent in the period, on a filed declaration | PM-FEE-006/007 |
| `business_use` | separate street entrance, pays a multiple | PM-ORG-009, PM-FEE-010 |

Semicolon or comma separated, quoted fields and Cyrillic headers both fine.

## tariff.json

A tariff exists only because the general assembly adopted it — every line needs a `decision_id`, or the run refuses to bill (PM-FEE-012).

Either `rate_minor` (per person / per unit / per ideal part) **or** `total_minor` (a pot split by the key). Amounts are integer minor units in EUR (PM-FEE-016).

`REPAIR_FUND` must be `BY_IDEAL_PARTS`; the assembly cannot move it (PM-FEE-004, PM-FUND-003).

## What it prints

Every line states how the number was derived (PM-FEE-018), and every run ends with the constants in it that are **not yet confirmed** against the consolidated statute. Those are the questions for counsel, and nothing should be billed before they are answered.

## Reading a failure

`✗ GATE 1 FAILED` means the rules are wrong, not the code. That is the point of running it on day 15 rather than month four. Take the differing units to the firm and ask how they compute them — the answer is a rule change, and the rule catalogue is where it goes.
