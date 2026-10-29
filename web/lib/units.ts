/**
 * An entrance's units registered through the web (WEB-50b). Pure: no import but `refusal`, so it is tested on Node's own runner.
 * The web parses what was typed, one unit to a line, and sends it; every judgement is the API's, shown in its words — the
 * sum of the ideal parts (PM-ORG-002), declared against derived parts, a unit with no area, an entrance that already has
 * units (PM-ORG-003), and who may enter the book (PM-SEC-001).
 */
import { undoneBy } from "./refusal.ts";

/** The request's `NewUnitRequest`. The ideal parts stay the exact decimal string typed — never a number (ADR-006). */
export type NewUnit = { designation: string; unitType: string; businessUse: boolean; separateEntrance: boolean; areaM2?: number; idealParts?: string };

const DECIMAL = /^\d+(?:[.,]\d+)?$/;
const YES = new Set(["да", "yes", "true"]);
const NO = new Set(["", "не", "no", "false"]);

/**
 * Rule: PM-ORG-002, PM-ORG-003 — the units as typed, one to a line: `обозначение;вид;площ;идеални части`, and optionally
 * `;стопанска дейност;самостоятелен вход` as `да`/`не` (both `не` when left out). The ideal parts, if every unit gives them,
 * are the title deeds' (PM-ORG-002); if none does, the API derives them by area and marks them `DERIVED` (PM-ORG-003).
 * Whether the set is all of one or the other, sums to 100%, or has the areas it needs is the API's. A blank line is skipped;
 * a line the web cannot read is refused by its number.
 */
// Rule: PM-ORG-002, PM-ORG-003
export function unitsOf(typed: string): { units: NewUnit[] } | { refused: string } {
  const units: NewUnit[] = [];
  for (const [i, raw] of typed.split(/\r?\n/).entries()) {
    if (!raw.trim()) continue;
    const at = `Ред ${i + 1}:`;
    const cells = raw.split(";").map((c) => c.trim());
    if (cells.length > 6) return { refused: `${at} повече от шест полета — обозначение; вид; площ; идеални части; стопанска дейност; самостоятелен вход.` };
    const [designation, unitType, area = "", parts = "", business = "", separate = ""] = cells;
    if (!designation) return { refused: `${at} липсва обозначение.` };
    if (!unitType) return { refused: `${at} липсва вид на обекта (втората колона).` };
    if (area && !DECIMAL.test(area)) return { refused: `${at} площта „${area}“ не е число.` };
    if (parts && !DECIMAL.test(parts)) return { refused: `${at} идеалните части „${parts}“ не са число.` };
    const [businessUse, separateEntrance] = [business, separate].map((c) => c.toLowerCase());
    for (const flag of [businessUse, separateEntrance]) if (!YES.has(flag) && !NO.has(flag)) return { refused: `${at} „${flag}“ — отговорът е да или не.` };
    units.push({
      designation, unitType, businessUse: YES.has(businessUse), separateEntrance: YES.has(separateEntrance),
      ...(area && { areaM2: Number(area.replace(",", ".")) }), ...(parts && { idealParts: parts.replace(",", ".") }),
    });
  }
  return units.length ? { units } : { refused: "Въведете поне един обект — по един на ред." };
}

/** The kinds of unit already in the entrance, as the units list gives them — suggestions only; the web pins no list. */
export const typesIn = (units: { unitType: string }[]): string[] => [...new Set(units.map((u) => u.unitType))].sort((a, b) => a.localeCompare(b, "bg"));

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001 — only the manager or the board enter the book; anybody else is the API's 403, in its words. */
// Rule: PM-SEC-001
export const unitsRefused = (answer: Answer): string => undoneBy(answer, "впишете обекти");
