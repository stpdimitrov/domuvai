import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import { undone, refused } from "./refusal.ts";
import { tariffOf, type TariffInput } from "./tariff.ts";

const base: TariffInput = {
  decision: "d-1", validFrom: "2027-01",
  "line-stream-0": "MANAGEMENT", "line-key-0": "PER_PERSON", "line-kind-0": "rate", "line-amount-0": "6,50",
  "line-stream-1": "REPAIR_FUND", "line-key-1": "BY_IDEAL_PARTS", "line-kind-1": "total", "line-amount-1": "600", "line-item-1": "",
  "price-item-0": "WATER", "price-item-1": "", "price-price-0": "2,05",
};

test("PM-FEE-012 the body carries only schema fields, amounts as integer minor units and no float", () => {
  const got = tariffOf(base, ["d-1"]);
  assert.deepEqual(got, {
    decisionId: "d-1", validFrom: "2027-01",
    lines: [{ stream: "MANAGEMENT", key: "PER_PERSON", rateMinor: 650 }, { stream: "REPAIR_FUND", key: "BY_IDEAL_PARTS", totalMinor: 60000 }],
    consumption: [{ item: "WATER", priceMinor: 205 }],
  });
  const text = JSON.stringify(got);
  assert.ok(!/\d\.\d/.test(text), text);
  assert.ok(Object.values(got as object).flat().every((v) => v !== undefined));
});

test("PM-FEE-012 validTo and an item go out only when typed; months as typed", () => {
  const got = tariffOf({ ...base, validTo: " 2027-12 ", "line-item-0": "CLEANING" }, ["d-1"]) as { validTo: string; lines: { item?: string }[] };
  assert.equal(got.validTo, "2027-12");
  assert.equal(got.lines[0].item, "CLEANING");
  assert.ok(!("item" in got.lines[1]));
  assert.ok(!("validTo" in (tariffOf(base, ["d-1"]) as object)));
});

test("PM-FEE-012 the decision is required, and only a passed decision of the entrance", () => {
  assert.ok("refused" in tariffOf({ ...base, decision: "" }, ["d-1"]));
  assert.ok("refused" in tariffOf({ ...base, decision: "typed-by-hand" }, ["d-1"]));
  assert.ok("refused" in tariffOf(base, []));
  assert.ok("refused" in tariffOf({ ...base, validFrom: " " }, ["d-1"]));
});

test("PM-FEE-012 malformed input is refused before posting", () => {
  for (const amount of ["6,555", "-1", "x", "1e3", ""]) assert.ok("refused" in tariffOf({ ...base, "line-amount-0": amount }, ["d-1"]), amount);
  assert.ok("refused" in tariffOf({ ...base, "line-kind-0": "" }, ["d-1"]));
  assert.ok("refused" in tariffOf({ ...base, "line-key-0": "METERED" }, ["d-1"]));
  assert.ok("refused" in tariffOf({ ...base, "price-price-0": "" }, ["d-1"]));
  assert.ok("refused" in tariffOf({ decision: "d-1", validFrom: "2027-01" }, ["d-1"]));
});

test("PM-FEE-015 the API's 409 is shown in its words, and a 403 is shown as a refusal of the person", () => {
  const said = "a tariff version already begins 2027-01";
  const conflict = undone(refused({ error: { error: said }, response: { status: 409 } }), "запишете тарифна версия");
  assert.ok(conflict.includes(said), conflict);
  const forbidden = undone(refused({ error: { error: "only the manager or the board" }, response: { status: 403 } }), "запишете тарифна версия");
  assert.ok(forbidden.startsWith("Нямате право") && forbidden.includes("only the manager or the board"), forbidden);
});

test("PM-FEE-015 a version row is never editable: the screen has no edit or delete control, one form that records", () => {
  const dir = new URL("../app/(console)/entrance/charges/tariff/", import.meta.url);
  const [page, form] = ["page.tsx", "TariffForm.tsx"].map((f) => readFileSync(new URL(f, dir), "utf8"));
  assert.ok(!/api\.(PUT|PATCH|DELETE)/.test(page + form));
  assert.ok(!/Редактир|Изтрий|Промени версия/.test(page + form));
  assert.equal((page.match(/<form/g) ?? []).length, 0);
  assert.equal((form.match(/<form/g) ?? []).length, 1);
});

test("PM-SEC-001 the screen holds no role check of its own: who may record is the API's", () => {
  const dir = new URL("../app/(console)/entrance/charges/tariff/", import.meta.url);
  const text = ["page.tsx", "TariffForm.tsx", "actions.ts"].map((f) => readFileSync(new URL(f, dir), "utf8")).join("\n");
  assert.ok(!/titleRole|mandate|\bBM\b|\bMB\b/.test(text.replace(/\/\/.*|\/\*[\s\S]*?\*\//g, "")));
});
