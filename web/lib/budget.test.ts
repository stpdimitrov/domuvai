import assert from "node:assert/strict";
import { test } from "node:test";
import { check, entryInForce, residentRoutes } from "./budget.ts";

const entry = (from: string, bytes: number) => ({ from, bytes, floorDevice: "Android от 2021", floorNetwork: "3G" });

test("PM-SYS-015 a route over the budget fails the check and names the route and the bytes over", () => {
  const v = check([{ route: "/my", bytes: 50_001 }, { route: "/my/bill", bytes: 40_000 }], [entry("2026-11-01", 50_000)], "2026-11-22");
  assert.equal(v.ok, false);
  assert.ok(v.lines.includes("PM-SYS-015: /my is over the budget: 50001 B, 1 B over"));
  assert.ok(!v.lines.some((l) => l.includes("/my/bill is over")));
});

test("PM-SYS-015 a route at the budget passes", () => {
  assert.equal(check([{ route: "/my", bytes: 50_000 }], [entry("2026-11-01", 50_000)], "2026-11-22").ok, true);
});

test("PM-SYS-015 of two dated entries the one in force on the given day is used, never a later one", () => {
  const entries = [entry("2026-12-01", 10_000), entry("2026-11-01", 50_000)];
  assert.equal(entryInForce(entries, "2026-11-30")?.bytes, 50_000);
  assert.equal(entryInForce(entries, "2026-12-01")?.bytes, 10_000);
  assert.equal(check([{ route: "/my", bytes: 20_000 }], entries, "2026-11-30").ok, true);
  assert.equal(check([{ route: "/my", bytes: 20_000 }], entries, "2026-12-01").ok, false);
});

test("PM-SYS-015 a build with a (resident) route and no budget entry in force fails", () => {
  const sizes = [{ route: "/my", bytes: 1 }];
  for (const entries of [[], [entry("2026-12-01", 50_000)]]) {
    const v = check(sizes, entries, "2026-11-22");
    assert.equal(v.ok, false);
    assert.ok(v.lines.at(-1)!.includes("no entry of web/budget.json is in force on 2026-11-22"));
  }
  assert.equal(check([], [entry("2026-11-01", 50_000)], "2026-11-22").ok, false);   // and no (resident) route holds nothing
  for (const bad of [{ ...entry("2026-11-01", 50_000), bytes: 0 }, { ...entry("2026-02-30", 50_000) }, { from: "2026-11-01", bytes: 5 }])
    assert.equal(check(sizes, [bad], "2026-11-22").ok, false);
  assert.equal(check(sizes, [entry("2026-11-01", 50_000), entry("2026-11-01", 60_000)], "2026-11-22").ok, false);   // two the same day: neither
});

test("PM-SYS-015 a route loads its page's files and every layout's above it, the root's included, and nothing else", () => {
  const pages = {
    "/layout": ["static/css/root.css", "static/chunks/app/layout.js"],
    "/(resident)/layout": ["static/css/resident.css", "static/chunks/app/(resident)/layout.js"],
    "/(resident)/my/page": ["static/chunks/main-app.js", "static/chunks/app/(resident)/my/page.js"],
    "/(console)/layout": ["static/css/console.css"],
    "/(console)/entrance/page": ["static/chunks/entrance.js"],
    "/auth/login/route": ["static/chunks/route.js"],
  };
  assert.deepEqual(residentRoutes(pages), [{
    route: "/my",
    files: ["static/chunks/app/(resident)/layout.js", "static/chunks/app/(resident)/my/page.js", "static/chunks/app/layout.js", "static/chunks/main-app.js", "static/css/resident.css", "static/css/root.css"],
  }]);
});
