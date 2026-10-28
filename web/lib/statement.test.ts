import assert from "node:assert/strict";
import { test } from "node:test";
import { ageing, byPeriod, oldest, type StatementLine } from "./statement.ts";

const line = (l: Partial<StatementLine>): StatementLine =>
  ({ period: "2026-09", component: "MAINTENANCE", allocationKey: "PER_PERSON", quantity: 2, amountMinor: 900, derivation: "2 × 4,50 €", ...l });

test("PM-FEE-018 every line is kept per period, in the API's order, its key, count and derivation unchanged", () => {
  const lines = [
    line({ period: "2026-08" }),
    line({ period: "2026-08", component: "REPAIR_FUND", allocationKey: "BY_IDEAL_PARTS", quantity: 1.25, amountMinor: 750, derivation: "1,25% × 600 €" }),
    line({}),
  ];
  const got = byPeriod(lines);
  assert.deepEqual(got.map((p) => p.period), ["2026-08", "2026-09"]);
  assert.deepEqual(got.flatMap((p) => p.keyed), lines);
  assert.deepEqual(byPeriod([]), []);
});

test("PM-FEE-017 a consumption line stands apart from the statutory keys' lines, with its item and reading", () => {
  const water = line({ allocationKey: "METERED", item: "WATER", quantity: 7.5, amountMinor: 1575, derivation: "7,5 м³ × 2,10 €" });
  const [p] = byPeriod([line({}), water]);
  assert.deepEqual(p.keyed, [line({})]);
  assert.deepEqual(p.metered, [water]);
});

test("PM-DEBT-001 the arrears are aged in the API's bands and order, and the oldest debt's due date is shown", () => {
  const buckets = [{ band: "CURRENT", amountMinor: 0 }, { band: "0-30", amountMinor: 900 }, { band: "90+", amountMinor: 1200 }, { band: "NEW", amountMinor: 5 }];
  assert.deepEqual(ageing(buckets), [
    { label: "в срок", amountMinor: 0 }, { label: "0–30 дни", amountMinor: 900 }, { label: "над 90 дни", amountMinor: 1200 }, { label: "NEW", amountMinor: 5 },
  ]);
  assert.match(oldest({ dueOn: "2026-06-15", overdueDays: 135 }), /падеж 15\.06\.2026 — просрочено с 135 дни/);
  assert.match(oldest({ dueOn: "2026-10-30", overdueDays: -2 }), /падеж 30\.10\.2026 — в срок/);
  assert.equal(oldest(undefined), "Няма открито задължение.");
});
