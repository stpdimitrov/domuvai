import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { cancelOf, disbursementRefused, payOf, signOffOf, type SignOffInput } from "./disbursement.ts";

const passed = ["d-1", "d-2"];
const typed = (p: Partial<SignOffInput>): SignOffInput =>
  ({ amount: "480", purpose: "WORKS", basis: "DECISION", decision: "d-1", measure: "", justification: "", ...p });

test("PM-FUND-006 the body carries the purpose code, a measure only for a passport measure, a decision only from the passed list", () => {
  assert.deepEqual(signOffOf(typed({}), passed), { amountMinor: 48000, purpose: "WORKS", decisionId: "d-1" });
  assert.deepEqual(signOffOf(typed({ purpose: "PASSPORT_MEASURE", measure: " ТП 2019, мярка 4.2 " }), passed),
    { amountMinor: 48000, purpose: "PASSPORT_MEASURE", decisionId: "d-1", passportMeasure: "ТП 2019, мярка 4.2" });
  assert.equal("passportMeasure" in signOffOf(typed({ purpose: "GA_PURPOSE", measure: "ТП 2019" }), passed), false);
  for (const p of [{ purpose: "" }, { purpose: "FURNITURE" }, { amount: "" }, { amount: "12,345" }, { amount: "0" }, { amount: "0,00" }, { purpose: "PASSPORT_MEASURE", measure: " " },
    { decision: "" }, { decision: "GA-typed-by-hand" }, { basis: "" }]) {
    assert.ok("refused" in signOffOf(typed(p), passed), JSON.stringify(p));
  }
});

test("PM-FUND-007 no body names a signatory, typed or otherwise; a sign-off needs a passed decision, a cancel a reason, a pay a date", () => {
  const smuggled = { ...typed({}), authorisedBy: "p-typed" } as SignOffInput;
  assert.deepEqual(signOffOf(smuggled, passed), { amountMinor: 48000, purpose: "WORKS", decisionId: "d-1" });
  assert.ok("refused" in signOffOf(typed({ decision: "d-3" }), passed));
  assert.deepEqual(payOf("2026-10-27"), { paidOn: "2026-10-27" });
  assert.ok("refused" in payOf(""));
  assert.deepEqual(cancelOf(" изпълнителят се отказа "), { reason: "изпълнителят се отказа" });
  assert.ok("refused" in cancelOf("  "));
});

test("PM-FUND-007 a sign-in of unknown party is not refused here: no screen asks who it is, and the API's refusal is rendered in its words", () => {
  const [actions, page] = ["actions.ts", "page.tsx"].map((f) =>
    readFileSync(new URL(`../app/(console)/entrance/fund/disbursements/${f}`, import.meta.url), "utf8").replace(/\/\*[^]*?\*\/|\/\/.*$/gm, ""));
  for (const source of [actions, page]) assert.doesNotMatch(source, /identity\/me|partyId|\bparty\b/);
  for (const form of ["SignOffForm", "PayForm", "CancelForm"]) assert.doesNotMatch(page, new RegExp(`&&\\s*\\(?\\s*<${form}\\b`), form);
  const said = "an entrance's accounts are kept by its manager and its board";
  for (const act of ["подпишете разход от фонда", "отбележите разхода като платен", "оттеглите разхода"]) {
    assert.equal(disbursementRefused({ error: { error: said, rule: "PM-SEC-001" }, response: { status: 403 } }, act),
      `Нямате право да ${act}: PM-SEC-001: ${said}`);
  }
});

test("PM-FUND-008 an emergency is works only, carries its justification and no decision", () => {
  const emergency = typed({ basis: "EMERGENCY", justification: " теч от покрива " });
  assert.deepEqual(signOffOf(emergency, passed), { amountMinor: 48000, purpose: "WORKS", emergencyJustification: "теч от покрива" });
  assert.deepEqual(signOffOf({ ...emergency, decision: "" }, []), signOffOf(emergency, passed));
  assert.ok("refused" in signOffOf({ ...emergency, justification: " " }, passed));
  assert.ok("refused" in signOffOf({ ...emergency, purpose: "GA_PURPOSE" }, passed));
  assert.ok("refused" in signOffOf({ ...emergency, purpose: "PASSPORT_MEASURE", measure: "ТП 2019" }, passed));
});

test("PM-SEC-001 the API's 403 and 409 are shown in its words", () => {
  const said = (status: number, error: string) => disbursementRefused({ error: { error }, response: { status } }, "подпишете разход от фонда");
  assert.equal(said(403, "PM-SEC-001: the entrance's accounts are kept by its manager or board"),
    "Нямате право да подпишете разход от фонда: PM-SEC-001: the entrance's accounts are kept by its manager or board");
  assert.equal(said(409, "an emergency disbursement of 1000000 exceeds the 14500 available (PM-FUND-008)"),
    "Системата не прие това: an emergency disbursement of 1000000 exceeds the 14500 available (PM-FUND-008)");
  assert.match(disbursementRefused({ response: { status: 400 } }, "подпишете разход от фонда"), /HTTP 400/);
});
