/**
 * The resident's two screens as data (WEB-58): Начало's blocks from `GET /api/registry/me/units`, and Моята сметка's
 * lines from `GET /api/money/units/{unitId}/statement`. Pure: no import beyond the pure helpers, so it is tested on
 * Node's own runner. The api decides who sees what; this only words what it gave. It computes no money.
 */
import { COMPONENT, KEY, type StatementLine } from "./statement.ts";
import { eur } from "./money.ts";

export type MyUnitRow = { unitId: string; entranceLabel: string; designation: string; unitType: string; titleRole: string; share: string };
export type Block = { unitId: string; where: string; roleLabel: string; share: string | null; statementHref: string | null };

const gcd = (a: bigint, b: bigint): bigint => (b === BigInt(0) ? a : gcd(b, a % b));

/** An exact decimal ("0.5", "0.3333") as a fraction ("1/2") by integer arithmetic; "1" is whole, so it is not shown. */
// Rule: PM-ORG-005
export function shareOf(decimal: string): string | null {
  const m = /^(\d+)(?:\.(\d+))?$/.exec(decimal.trim());
  if (!m) return decimal || null;
  const frac = m[2] ?? "";
  const den = BigInt("1" + "0".repeat(frac.length));
  const num = BigInt(m[1] + frac);
  if (num === den) return null;
  const g = gcd(num, den);
  return `${num / g}/${den / g}`;
}

/**
 * Rule: PM-SEC-002 — Начало lists exactly the rows the api returned. Only an OWN row links to Моята сметка (the api
 * lets the owner read the unit's charges); a USR row is labelled "ползвател" and links nowhere. A title the web does
 * not know is shown by its code and links nowhere.
 */
// Rule: PM-SEC-002
export function blocks(rows: MyUnitRow[]): Block[] {
  return rows.map((r) => ({
    unitId: r.unitId,
    where: `${r.entranceLabel} · ${r.designation} · ${r.unitType}`,
    roleLabel: r.titleRole === "OWN" ? "собственик" : r.titleRole === "USR" ? "ползвател" : r.titleRole,
    share: shareOf(r.share),
    statementHref: r.titleRole === "OWN" ? `/my/statement/${encodeURIComponent(r.unitId)}` : null,
  }));
}

/** What an OWN block says it owes: the api's ledger balance as euros, shown as it is — no arithmetic. */
// Rule: PM-FEE-016
export const owes = (balanceMinor: number): string => (balanceMinor > 0 ? `Дължите ${eur(balanceMinor)}` : balanceMinor < 0 ? `Имате преплатено ${eur(-balanceMinor)}` : "Нямате дължими суми");

/**
 * Rule: PM-FEE-018 — one line worded in Bulgarian. `how` is the api's `derivationBg` as it came (already formatted from
 * minor units, so the web computes nothing); when the api sent none, it is the stream's key and the count used. The
 * api's English `derivation` is never shown. Rule: PM-FEE-016 — `amount` is the line's minor units through `eur`.
 */
// Rule: PM-FEE-018, PM-FEE-016
export function lineText(l: StatementLine): { what: string; how: string; amount: string } {
  const what = l.allocationKey === "METERED" && l.item ? `${COMPONENT[l.component] ?? l.component} · ${l.item}` : COMPONENT[l.component] ?? l.component;
  const how = l.derivationBg?.trim() ? l.derivationBg : `${KEY[l.allocationKey] ?? l.allocationKey} · ${l.quantity}`;
  return { what, how, amount: eur(l.amountMinor) };
}

/** Rule: PM-FEE-018 — nothing charged is a statement, not an error. */
export const emptyStatement = "Няма начислени суми";

/**
 * Rule: PM-SEC-002 — a refused statement says only that it is not available: no figure, no other unit, and none of
 * the api's words (they may name a rule or an entrance). The web never tries another unit.
 */
// Rule: PM-SEC-002
export const refusedStatement = (status: number | null): string =>
  status === null ? "Бекендът не отговори. Опитайте отново по-късно." : status === 401 ? "Входът ви не беше приет. Излезте и влезте отново." : "Сметката още не е налична";
