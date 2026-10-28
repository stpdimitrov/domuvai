import assert from "node:assert/strict";
import { test } from "node:test";
import { paymentOf, type PaymentInput } from "./payment.ts";

const typed = (p: Partial<PaymentInput>): PaymentInput =>
  ({ unit: "u-1", amount: "120,50", valueDate: "2026-10-28", receivedInto: "CASH", designated: "", ...p });

test("PM-DEBT-008 no designation is sent unless the payer gives one: the API then applies oldest debt first", () => {
  assert.deepEqual(paymentOf(typed({})), { unitId: "u-1", amountMinor: 12050, valueDate: "2026-10-28", receivedInto: "CASH" });
  assert.deepEqual(paymentOf(typed({ designated: " " })), { unitId: "u-1", amountMinor: 12050, valueDate: "2026-10-28", receivedInto: "CASH" });
  assert.deepEqual(paymentOf(typed({ designated: "2026-09-01" })),
    { unitId: "u-1", amountMinor: 12050, valueDate: "2026-10-28", receivedInto: "CASH", designatedDebtDate: "2026-09-01" });
});

test("PM-FEE-016 euros typed are integer minor units; what is not a sum is refused before any call", () => {
  assert.equal((paymentOf(typed({ amount: "300" })) as { amountMinor: number }).amountMinor, 30000);
  assert.equal((paymentOf(typed({ amount: "0.07" })) as { amountMinor: number }).amountMinor, 7);
  for (const amount of ["", "12,345", "-5", "1e3", "abc"]) assert.ok("refused" in paymentOf(typed({ amount })), amount);
  assert.ok("refused" in paymentOf(typed({ unit: "" })));
  assert.ok("refused" in paymentOf(typed({ valueDate: "" })));
});
