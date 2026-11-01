"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import type { Offered } from "@/lib/openDebts";
import { unansweredBy } from "@/lib/refusal";

/**
 * The debts a payment on [asOf] could be designated to (WEB-53), as the API lists them for the unit, the oldest first.
 * Rule: PM-DEBT-008 — these are the dates a designation is accepted for. Rule: PM-SEC-002 — who may read them is the API's,
 * enforced there; a refusal (a unit with nothing posted included) or no answer is returned in words, with no list, and the
 * payment can still be recorded oldest first. Called by the form as the unit or the date changes.
 */
// Rule: PM-DEBT-008, PM-SEC-002
export async function openDebtsOf(unit: string, asOf: string): Promise<Offered> {
  const answer = await reach(() =>
    api.GET("/api/money/units/{unitId}/open-debts", { params: { path: { unitId: String(unit) }, query: { asOf: String(asOf) } } }),
  );
  return answer?.data ? { debts: answer.data } : { unread: unansweredBy(answer, "откритите задължения на обекта") };
}
