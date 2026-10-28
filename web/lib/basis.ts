/**
 * The basis a charge run is issued on, as the person typed it (WEB-38). Pure: no import but `minorOf`, so it is tested
 * on Node's own runner. It reads euros to minor units and checks one thing of its own — that every line cites a
 * decision this entrance's assembly passed; the law, the keys and the amounts are the API's to refuse.
 */
import { minorOf } from "./money.ts";

export type BasisLine = { stream: string; key: string; per: string; amount: string; decision: string };
export type BasisInput = { multiple: string; lines: BasisLine[] };
export type TariffLine = { stream: string; key: string; decisionId: string; rateMinor?: number; totalMinor?: number };
export type Basis = { lines: TariffLine[]; businessMultiplier?: number };

/**
 * Rule: PM-FEE-012 — no amount is billed without a GA decision behind it: a line whose decision is not among [passed]
 * (the ids of this entrance's PASSED decisions, read from the assembly API, never from the form) is refused here,
 * before any call. Rule: PM-FEE-016 — euros as typed are integer minor units; no float.
 */
// Rule: PM-FEE-012, PM-FEE-016
export function basisOf(input: BasisInput, passed: ReadonlySet<string>): Basis | { refused: string } {
  const lines: TariffLine[] = [];
  for (const [j, l] of input.lines.entries()) {
    if (!l.stream) continue;                                    // an empty row
    if (!passed.has(l.decision)) return { refused: `Ред ${j + 1}: изберете прието решение на общото събрание на този вход — без решение не се начислява.` };
    const minor = minorOf(l.amount);
    if (minor === null) return { refused: `Ред ${j + 1}: сумата е в евро, с най-много два знака след десетичната запетая.` };
    lines.push({ stream: l.stream, key: l.key, decisionId: l.decision, ...(l.per === "total" ? { totalMinor: minor } : { rateMinor: minor }) });
  }
  if (lines.length === 0) return { refused: "Посочете поне един ред от тарифата." };
  const multiple = input.multiple.trim();
  if (multiple && !/^\d{1,9}$/.test(multiple)) return { refused: `Кратността „${multiple}“ не е цяло число.` };
  return { lines, ...(multiple && { businessMultiplier: Number(multiple) }) };
}

/** The basis as text, so the one issued can be held to the one previewed (PM-FEE-015: an issued run is never altered). */
export const basisKey = (period: string, basis: Basis) => JSON.stringify([period, basis.businessMultiplier ?? null, basis.lines]);
