import assert from "node:assert/strict";
import { test } from "node:test";
import { askedOf, debtLabel, designationOf, offerFor, worthAsking, type Offered } from "./openDebts.ts";
import { paymentOf } from "./payment.ts";

const offered: Offered = {
  debts: [
    { debtDate: "2026-08-01", dueOn: "2026-08-20", outstandingMinor: 12050 },
    { debtDate: "2026-09-01", dueOn: "2026-09-20", outstandingMinor: 8000 },
  ],
};

test("PM-DEBT-008 an offered debt shows its date, the day it fell due and what is outstanding", () => {
  assert.equal(debtLabel(offered.debts[0]), "Задължение от 2026-08-01 · падеж 2026-08-20 · остава €120,50");
});

test("PM-DEBT-008 nothing chosen is nothing designated: the payment goes oldest debt first, and no date is sent", () => {
  assert.equal(designationOf("", offered), "");
  const body = paymentOf({ unit: "u-1", amount: "50", valueDate: "2026-10-28", receivedInto: "CASH", designated: designationOf("", offered) });
  assert.ok(!("designatedDebtDate" in body));
});

test("PM-DEBT-008 a debt chosen from the list is the one designated", () => {
  assert.equal(designationOf("2026-09-01", offered), "2026-09-01");
  const body = paymentOf({ unit: "u-1", amount: "50", valueDate: "2026-10-28", receivedInto: "CASH", designated: designationOf("2026-09-01", offered) });
  assert.equal((body as { designatedDebtDate?: string }).designatedDebtDate, "2026-09-01");
});

test("PM-DEBT-008 a choice the list offered for another unit or date is dropped when the list changes", () => {
  const other: Offered = { debts: [{ debtDate: "2026-09-01", dueOn: "2026-09-20", outstandingMinor: 8000 }] };
  assert.equal(designationOf("2026-08-01", other), "");
  assert.equal(designationOf("2026-08-01", { debts: [] }), "");
});

test("PM-SEC-002 the web decides nothing: a read the API refused, or not yet answered, offers nothing (the refusal itself is the API's, MoneyAccessIT)", () => {
  assert.equal(designationOf("2026-08-01", { unread: "API отказа задълженията: HTTP 403" }), "");
  assert.equal(designationOf("2026-08-01", null), "");
});

test("PM-DEBT-008 the list is asked for only once a unit and the payment's date are chosen", () => {
  assert.equal(worthAsking("u-1", "2026-10-28"), true);
  assert.equal(worthAsking("", "2026-10-28"), false);
  assert.equal(worthAsking("u-1", ""), false);
});

test("PM-DEBT-008 an answer to an earlier unit or date is not the list for the one chosen now", () => {
  const answer = { asked: askedOf("u-1", "2026-10-28"), offered };
  assert.equal(offerFor("u-1", "2026-10-28", answer), offered);
  assert.equal(offerFor("u-2", "2026-10-28", answer), null);
  assert.equal(offerFor("u-1", "2026-10-29", answer), null);
  assert.equal(offerFor("", "2026-10-28", { asked: askedOf("", "2026-10-28"), offered }), null);
  assert.equal(offerFor("u-1", "2026-10-28", null), null);
});
