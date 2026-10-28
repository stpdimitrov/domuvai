import assert from "node:assert/strict";
import { test } from "node:test";
import { cancelOf, disbursementRefused, NO_PARTY, payOf, signOffOf, type SignOffInput } from "./disbursement.ts";

const me = "p-1";
const passed = ["d-1", "d-2"];
const typed = (p: Partial<SignOffInput>): SignOffInput =>
  ({ amount: "480", purpose: "WORKS", basis: "DECISION", decision: "d-1", measure: "", justification: "", ...p });

test("PM-FUND-006 the body carries the purpose code, a measure only for a passport measure, a decision only from the passed list", () => {
  assert.deepEqual(signOffOf(typed({}), me, passed), { amountMinor: 48000, purpose: "WORKS", authorisedBy: me, decisionId: "d-1" });
  assert.deepEqual(signOffOf(typed({ purpose: "PASSPORT_MEASURE", measure: " ТП 2019, мярка 4.2 " }), me, passed),
    { amountMinor: 48000, purpose: "PASSPORT_MEASURE", authorisedBy: me, decisionId: "d-1", passportMeasure: "ТП 2019, мярка 4.2" });
  assert.equal("passportMeasure" in signOffOf(typed({ purpose: "GA_PURPOSE", measure: "ТП 2019" }), me, passed), false);
  for (const p of [{ purpose: "" }, { purpose: "FURNITURE" }, { amount: "" }, { amount: "12,345" }, { amount: "0" }, { amount: "0,00" }, { purpose: "PASSPORT_MEASURE", measure: " " },
    { decision: "" }, { decision: "GA-typed-by-hand" }, { basis: "" }]) {
    assert.ok("refused" in signOffOf(typed(p), me, passed), JSON.stringify(p));
  }
});

test("PM-FUND-007 the signatory is the session's party and nothing typed; pay and cancel carry it too; a cancel needs a reason, a pay a date", () => {
  assert.equal((signOffOf(typed({}), "p-2", passed) as { authorisedBy: string }).authorisedBy, "p-2");
  assert.deepEqual(signOffOf(typed({}), undefined, passed), { refused: NO_PARTY });
  const smuggled = { ...typed({}), authorisedBy: "p-typed" } as SignOffInput;
  assert.equal((signOffOf(smuggled, me, passed) as { authorisedBy: string }).authorisedBy, me);
  assert.ok("refused" in signOffOf(typed({ decision: "d-3" }), me, passed));
  assert.deepEqual(payOf("2026-10-27", me), { paidOn: "2026-10-27", paidBy: me });
  assert.ok("refused" in payOf("", me));
  assert.deepEqual(payOf("2026-10-27", undefined), { refused: NO_PARTY });
  assert.deepEqual(cancelOf(" изпълнителят се отказа ", me), { cancelledBy: me, reason: "изпълнителят се отказа" });
  assert.ok("refused" in cancelOf("  ", me));
  assert.deepEqual(cancelOf("причина", undefined), { refused: NO_PARTY });
});

test("PM-FUND-008 an emergency is works only, carries its justification and no decision", () => {
  const emergency = typed({ basis: "EMERGENCY", justification: " теч от покрива " });
  assert.deepEqual(signOffOf(emergency, me, passed), { amountMinor: 48000, purpose: "WORKS", authorisedBy: me, emergencyJustification: "теч от покрива" });
  assert.deepEqual(signOffOf({ ...emergency, decision: "" }, me, []), signOffOf(emergency, me, passed));
  assert.ok("refused" in signOffOf({ ...emergency, justification: " " }, me, passed));
  assert.ok("refused" in signOffOf({ ...emergency, purpose: "GA_PURPOSE" }, me, passed));
  assert.ok("refused" in signOffOf({ ...emergency, purpose: "PASSPORT_MEASURE", measure: "ТП 2019" }, me, passed));
});

test("PM-SEC-001 the API's 403 and 409 are shown in its words", () => {
  const said = (status: number, error: string) => disbursementRefused({ error: { error }, response: { status } }, "подпишете разход от фонда");
  assert.equal(said(403, "PM-SEC-001: the entrance's accounts are kept by its manager or board"),
    "Нямате право да подпишете разход от фонда: PM-SEC-001: the entrance's accounts are kept by its manager or board");
  assert.equal(said(409, "an emergency disbursement of 1000000 exceeds the 14500 available (PM-FUND-008)"),
    "Системата не прие това: an emergency disbursement of 1000000 exceeds the 14500 available (PM-FUND-008)");
  assert.match(disbursementRefused({ response: { status: 400 } }, "подпишете разход от фонда"), /HTTP 400/);
});
