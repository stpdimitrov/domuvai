import assert from "node:assert/strict";
import { test } from "node:test";
import { eur, minorOf } from "./money.ts";

test("euros as typed are minor units by their digits, with at most two decimals, or nothing", () => {
  for (const [typed, minor] of [["2,50", 250], ["2.5", 250], ["6", 600], ["0,07", 7], ["1234.56", 123456], [" 12,30 ", 1230]] as const)
    assert.equal(minorOf(typed), minor, typed);
  for (const typed of ["", "2,505", "-1", "1 000", "€5", "1e3", "2,", "12345678901234"]) assert.equal(minorOf(typed), null, typed);
  assert.equal(eur(minorOf("1234,5")!), "€1.234,50");
});
