import assert from "node:assert/strict";
import { test } from "node:test";
import { sofiaInstant, sofiaTime } from "./sofia.ts";

test("a time typed in Sofia is the instant Sofia's clock shows it at, summer and winter", () => {
  assert.equal(sofiaInstant("2026-11-05T18:30"), "2026-11-05T16:30:00Z");     // EET, UTC+2
  assert.equal(sofiaInstant("2026-07-01T09:00"), "2026-07-01T06:00:00Z");     // EEST, UTC+3
  assert.equal(sofiaInstant("2026-01-01T00:00"), "2025-12-31T22:00:00Z");     // the day before, in UTC
  assert.equal(sofiaTime("2026-11-05T16:30:00Z"), "05.11.2026 18:30");
  assert.equal(sofiaTime("2026-07-01T06:00:00Z"), "01.07.2026 09:00");
  for (const local of ["2026-11-05T18:30", "2026-07-01T09:00", "2026-03-29T04:00", "2026-10-25T02:59"]) {
    const instant = sofiaInstant(local)!;
    assert.equal(sofiaTime(instant), local.slice(8, 10) + "." + local.slice(5, 7) + "." + local.slice(0, 4) + " " + local.slice(11));
  }
});

test("a time the clocks skip in spring is no time; one they repeat in autumn is its first, summer occurrence", () => {
  assert.equal(sofiaInstant("2026-03-29T03:00"), null);                       // 03:00 → 04:00 on the last Sunday of March
  assert.equal(sofiaInstant("2026-03-29T03:59"), null);
  assert.equal(sofiaInstant("2026-03-29T02:59"), "2026-03-29T00:59:00Z");
  assert.equal(sofiaInstant("2026-03-29T04:00"), "2026-03-29T01:00:00Z");
  assert.equal(sofiaInstant("2026-10-25T03:30"), "2026-10-25T00:30:00Z");     // 04:00 → 03:00 on the last Sunday of October: EEST, the earlier
  assert.equal(sofiaInstant("2026-10-25T04:00"), "2026-10-25T02:00:00Z");
});

test("anything but a datetime-local value is no time", () => {
  for (const bad of ["", "2026-11-05", "2026-11-05 18:30", "2026-11-05T18:30:00", "2026-02-30T10:00", "2026-11-05T24:00", "2026-13-01T10:00",
    "2026-11-05T18:60", "05.11.2026 18:30", "2026-11-05T18:30Z", "x"]) {
    assert.equal(sofiaInstant(bad), null, bad);
  }
  assert.equal(sofiaTime("not a time"), "not a time");
});
