import assert from "node:assert/strict";
import { test } from "node:test";
import { asPreviewed, lineWords, runKey, runRequest, type RunRequest } from "./basis.ts";

test("PM-FEE-012 the body asked for carries no lines, only the run's own fields", () => {
  const got = runRequest("2027-01", "3");
  assert.deepEqual(got, { period: "2027-01", legalDate: "2027-01-01", businessMultiplier: 3 });
  assert.deepEqual(runRequest("2027-01", " "), { period: "2027-01", legalDate: "2027-01-01" });
  for (const typed of ["1,5", "-1", "x", "1234567890"]) assert.ok("refused" in runRequest("2027-01", typed), typed);
  assert.ok(!("lines" in (got as object)));
});

test("PM-FEE-012 a line of the version in force is shown as the API stored it", () => {
  assert.equal(lineWords({ stream: "MANAGEMENT", key: "PER_PERSON", rateMinor: 600 }), "управление €6,00/живущ");
  assert.equal(lineWords({ stream: "REPAIR_FUND", key: "BY_IDEAL_PARTS", totalMinor: 60000 }), "фонд „Ремонт“ €600,00 по идеални части");
});

test("PM-FEE-015 the run issued is held to the one previewed: another version, period or multiple gives another key", () => {
  const run = (period: string, multiple: string) => runRequest(period, multiple) as RunRequest;
  const key = runKey("v-1", run("2027-01", ""));
  assert.ok(asPreviewed(key, "v-1", run("2027-01", " ")));
  for (const [previewed, version, request] of [[key, "v-2", run("2027-01", "")], [key, "v-1", run("2027-02", "")], [key, "v-1", run("2027-01", "2")], ["", "v-1", run("2027-01", "")]] as const)
    assert.ok(!asPreviewed(previewed, version, request));
});
