/**
 * A disbursement from the fund as the person typed it, made the API's body (WEB-42): signed off. Paying and cancelling are WEB-42b.
 * Pure: no import but `minorOf` and `undone`, so it is tested on Node's own runner. The actor is the signed-in person's
 * own party, read from the API on every write (owner, 2026-10-28, "Confirm Q1") — never typed, never picked. Whether
 * that party holds the fund's account and whether the fund covers an emergency are the API's.
 */
import { minorOf } from "./money.ts";
import { refused, undone } from "./refusal.ts";

/** What a disbursement may be for (PM-FUND-006), the API's purpose codes in words. */
export const PURPOSE: Record<string, string> = {
  WORKS: "работи и оборудване (чл. 48–49)",
  PASSPORT_MEASURE: "мярка по техническия паспорт",
  GA_PURPOSE: "друга цел, решена от ОС",
};

export type SignOffInput = { amount: string; purpose: string; basis: string; decision: string; measure: string; justification: string };
export type SignOffBody = {
  amountMinor: number; purpose: string; authorisedBy: string; decisionId?: string; passportMeasure?: string; emergencyJustification?: string;
};

export const NO_PARTY = "Входът ви не е свързан с лице — разход от фонда се подписва от лице. Администраторът свързва входа ви с лице.";

/**
 * Rule: PM-FUND-006 — a purpose code, and a decision or, for a passport measure, the measure named as well. Rule:
 * PM-FUND-007 — signed by the session's own party on a GA decision chosen from this entrance's passed ones ([passed],
 * read again on this write), never a decision typed. Rule: PM-FUND-008 — without a decision only as an emergency: works
 * only, its justification recorded, no decision sent. Whether the fund covers it is the API's (its 409).
 */
// Rule: PM-FUND-006, PM-FUND-007, PM-FUND-008, PM-FEE-016
export function signOffOf(input: SignOffInput, party: string | undefined, passed: string[]): SignOffBody | { refused: string } {
  if (!party) return { refused: NO_PARTY };
  if (!(input.purpose in PURPOSE)) return { refused: "Изберете за какво е разходът (PM-FUND-006)." };
  const amountMinor = minorOf(input.amount);
  if (amountMinor == null) return { refused: `„${input.amount}“ не е сума в евро — например 120 или 120,50.` };
  const measure = input.measure.trim();
  if (input.purpose === "PASSPORT_MEASURE" && !measure) return { refused: "Посочете мярката от техническия паспорт (PM-FUND-006)." };
  const signed = { amountMinor, purpose: input.purpose, authorisedBy: party };
  if (input.basis === "EMERGENCY") {
    if (input.purpose !== "WORKS") return { refused: "Без решение на ОС, като авария, се възлагат само ремонтни работи (PM-FUND-008)." };
    const justification = input.justification.trim();
    if (!justification) return { refused: "Опишете аварията — обосновката се записва (PM-FUND-008)." };
    return { ...signed, emergencyJustification: justification };
  }
  if (input.basis !== "DECISION") return { refused: "Изберете основание: решение на общото събрание или аварийен ремонт." };
  if (!passed.includes(input.decision)) return { refused: "Изберете приетото решение на общото събрание, на което се разходва (PM-FUND-007)." };
  return { ...signed, decisionId: input.decision, ...(input.purpose === "PASSPORT_MEASURE" && { passportMeasure: measure }) };
}

/**
 * Rule: PM-SEC-001 — who may sign off is the API's (the manager or the board, then the fund's holder):
 * its 403 and its 409 are shown in its words; [act] is the verb phrase after "да".
 */
// Rule: PM-SEC-001
export const disbursementRefused = (answer: { error?: unknown; response: { status: number } }, act: string): string =>
  undone(refused(answer), act);
