/**
 * A unit's statement and arrears, as the API serves them, laid out for the screen (WEB-40). Pure: no import, so it is
 * tested on Node's own runner. It groups and labels; it computes no amount, no due date and no band — those are the API's.
 */

export type StatementLine = {
  period: string; component: string; allocationKey: string; quantity: number; amountMinor: number; derivation: string; item?: string;
};
export type Period = { period: string; keyed: StatementLine[]; metered: StatementLine[] };
export type Bucket = { band: string; amountMinor: number };

export const COMPONENT: Record<string, string> = { MANAGEMENT: "Управление", MAINTENANCE: "Поддръжка", REPAIR_FUND: "Фонд за ремонт" };
export const KEY: Record<string, string> = { PER_PERSON: "на живущ", BY_IDEAL_PARTS: "по идеални части", PER_UNIT: "на обект", METERED: "по показание" };

/**
 * Rule: PM-FEE-018 — per unit and per period, every line with its key, the count used and its derivation: the lines
 * are grouped by period in the order the API gives them, none dropped, none changed. Rule: PM-FEE-017 — a consumption
 * line (key METERED) stands apart from the statutory keys' lines, with its item and its reading.
 */
// Rule: PM-FEE-018, PM-FEE-017
export function byPeriod(lines: StatementLine[]): Period[] {
  const periods = new Map<string, Period>();
  for (const line of lines) {
    const p = periods.get(line.period) ?? { period: line.period, keyed: [], metered: [] };
    (line.allocationKey === "METERED" ? p.metered : p.keyed).push(line);
    periods.set(line.period, p);
  }
  return [...periods.values()];
}

const BAND: Record<string, string> = { CURRENT: "в срок", "0-30": "0–30 дни", "31-60": "31–60 дни", "61-90": "61–90 дни", "90+": "над 90 дни" };

/**
 * Rule: PM-DEBT-001 — the unit's arrears aged in the API's bands, in its order, each with its amount; a band the web
 * does not know is shown by its code, never dropped or re-banded.
 */
// Rule: PM-DEBT-001
export const ageing = (buckets: Bucket[]) => buckets.map((b) => ({ label: BAND[b.band] ?? b.band, amountMinor: b.amountMinor }));

/** `YYYY-MM-DD` as the console writes a date, `DD.MM.YYYY`. */
export const day = (iso: string) => iso.split("-").reverse().join(".");

/** Rule: PM-DEBT-001 — the oldest open debt's due date, as the API derived it, and how far past it the date asked is. */
// Rule: PM-DEBT-001
export const oldest = (debt: { dueOn: string; overdueDays: number } | undefined) =>
  !debt ? "Няма открито задължение."
    : debt.overdueDays === 0 ? `Най-старото открито задължение е с падеж ${day(debt.dueOn)} — падежът е денят, към който се гледа.`
    : debt.overdueDays > 0 ? `Най-старото открито задължение е с падеж ${day(debt.dueOn)} — просрочено с ${debt.overdueDays} ${debt.overdueDays === 1 ? "ден" : "дни"}.`
      : `Най-старото открито задължение е с падеж ${day(debt.dueOn)} — в срок.`;
