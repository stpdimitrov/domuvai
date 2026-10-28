import assert from "node:assert/strict";
import { test } from "node:test";
import { asPreviewed, basisKey, basisOf, type Basis, type BasisLine } from "./basis.ts";

const passed = new Set(["d-1", "d-2"]);
const line = (l: Partial<BasisLine>): BasisLine => ({ stream: "MANAGEMENT", key: "PER_PERSON", per: "rate", amount: "6,00", decision: "d-1", ...l });

test("PM-FEE-012 a line citing no passed decision of the entrance is refused", () => {
  for (const decision of ["", "GA-2026-03-12-4", "d-3"]) {
    const got = basisOf({ multiple: "", lines: [line({}), line({ decision })] }, passed);
    assert.ok("refused" in got && got.refused.startsWith("Ред 2:"), decision);
  }
  assert.ok("refused" in basisOf({ multiple: "", lines: [line({})] }, new Set()));
});

test("PM-FEE-016 euros typed are minor units, a rate or a total as chosen; empty rows are left out", () => {
  const got = basisOf({ multiple: "3", lines: [line({}), line({ stream: "" }), line({ stream: "REPAIR_FUND", key: "BY_IDEAL_PARTS", per: "total", amount: "600", decision: "d-2" })] }, passed);
  assert.deepEqual(got, {
    businessMultiplier: 3,
    lines: [
      { stream: "MANAGEMENT", key: "PER_PERSON", decisionId: "d-1", rateMinor: 600 },
      { stream: "REPAIR_FUND", key: "BY_IDEAL_PARTS", decisionId: "d-2", totalMinor: 60000 },
    ],
  });
  assert.ok("refused" in basisOf({ multiple: "", lines: [line({ amount: "6,005" })] }, passed));
  assert.ok("refused" in basisOf({ multiple: "1,5", lines: [line({})] }, passed));
  assert.ok("refused" in basisOf({ multiple: "", lines: [line({ stream: "" })] }, passed));
});

test("PM-FEE-015 the basis issued is held to the one previewed: any change gives another key", () => {
  const of = (amount: string, period = "2026-11") => basisKey(period, basisOf({ multiple: "", lines: [line({ amount })] }, passed) as never);
  assert.equal(of("6"), of("6,00"));
  assert.notEqual(of("6"), of("6,01"));
  assert.notEqual(of("6"), of("6", "2026-12"));
  const basis = (amount: string) => basisOf({ multiple: "", lines: [line({ amount })] }, passed) as Basis;
  assert.ok(asPreviewed(of("6"), "2026-11", basis("6,00")));
  for (const [previewed, period, amount] of [[of("6"), "2026-11", "6,01"], [of("6"), "2026-12", "6"], ["", "2026-11", "6"]] as const)
    assert.ok(!asPreviewed(previewed, period, basis(amount)), `${period} ${amount}`);
});
