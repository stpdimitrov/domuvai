/**
 * The charge run the screen asks for, and the tariff version it is billed on (WEB-60). Pure: no import but `eur`, so it
 * is tested on Node's own runner. The API bills the tariff version in force for the period and ignores any lines a caller
 * sends (TARIFF-02a, PM-FEE-012), so the request holds no tariff: the screen shows that version and names it.
 */
import type { components } from "./api/schema.d.ts";
import { eur } from "./money.ts";

type Schemas = components["schemas"];

/** The body of both the preview and the issue: only what the API's `StoredChargeRunRequest` holds — an extra field fails the build. */
export type RunRequest = Schemas["StoredChargeRunRequest"];

const STREAM: Record<string, string> = { MANAGEMENT: "управление", MAINTENANCE: "поддръжка на общи части", REPAIR_FUND: "фонд „Ремонт“" };
const KEY: Record<string, string> = { PER_PERSON: "живущ", PER_UNIT: "обект" };

/** One line of the version in force, in words: every figure is the API's, as stored (PM-FEE-012). */
// Rule: PM-FEE-012
export const lineWords = (l: Schemas["AdoptedLine"]): string =>
  `${STREAM[l.stream] ?? l.stream}${l.item ? ` (${l.item})` : ""} ${l.rateMinor != null ? `${eur(l.rateMinor)}/${KEY[l.key] ?? l.key}` : `${eur(l.totalMinor ?? 0)} по ${l.key === "BY_IDEAL_PARTS" ? "идеални части" : l.key}`}`;

/**
 * The run asked for [period]: the first day of the month is its legal date, the multiple (PM-FEE-010) the person gave
 * is passed on as given — a whole number, as the API takes no other. No tariff travels with it.
 */
// Rule: PM-FEE-012, PM-FEE-010
export function runRequest(period: string, multiple: string): RunRequest | { refused: string } {
  const typed = multiple.trim();
  if (typed && !/^\d{1,9}$/.test(typed)) return { refused: `Кратността „${typed}“ не е цяло число.` };
  return { period, legalDate: `${period}-01`, ...(typed && { businessMultiplier: Number(typed) }) };
}

/**
 * The run as text, with the version it is billed on, so the one issued can be held to the one previewed. A guard
 * against issuing what the person did not see, not the PM-FEE-015 backstop: the key travels in the form, and an issued
 * run is kept unaltered by the API (a period billed is its 409). The version is read just before the call: a version adopted
 * in between is the API's to bill, and its immutability is the backstop.
 */
// Rule: PM-FEE-015
export const runKey = (versionId: string, request: RunRequest) => JSON.stringify([versionId, request.period, request.businessMultiplier ?? null]);

/** Whether the run to issue is the one previewed: [previewed] is the key the preview's answer carried in the form. */
// Rule: PM-FEE-015
export const asPreviewed = (previewed: string, versionId: string, request: RunRequest) => previewed === runKey(versionId, request);
