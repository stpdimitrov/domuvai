/**
 * A payment as the person typed it, made the API's body (WEB-39). Pure: no import but `minorOf`, so it is tested on
 * Node's own runner. It reads euros to minor units and sends the payer's designation only when one is given; how the
 * payment is allocated, whether the dates are real and whether the debt is owed are all the API's.
 */
import { minorOf } from "./money.ts";

export type PaymentInput = { unit: string; amount: string; valueDate: string; receivedInto: string; designated: string };
export type PaymentBody = { unitId: string; amountMinor: number; valueDate: string; receivedInto: string; designatedDebtDate?: string };

/**
 * Rule: PM-DEBT-008 — oldest debt first unless the payer designates one: the web carries the designation as given
 * and never chooses one. Left empty, it is not sent, and the API applies OLDEST_FIRST. Rule: PM-FEE-016 — euros as
 * typed are integer minor units; no float.
 */
// Rule: PM-DEBT-008, PM-FEE-016
export function paymentOf(input: PaymentInput): PaymentBody | { refused: string } {
  const amountMinor = minorOf(input.amount);
  if (!input.unit) return { refused: "Изберете обекта, за който е плащането." };
  if (amountMinor == null) return { refused: `„${input.amount}“ не е сума в евро — например 120 или 120,50.` };
  if (!input.valueDate) return { refused: "Посочете датата на плащането." };
  const designated = input.designated.trim();
  return { unitId: input.unit, amountMinor, valueDate: input.valueDate, receivedInto: input.receivedInto, ...(designated && { designatedDebtDate: designated }) };
}

/** Today in Sofia, `YYYY-MM-DD` — the date field's default only; whether a date is after today is the API's (PM-SYS-004). */
export const sofiaToday = () =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
