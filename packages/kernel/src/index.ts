/**
 * Types that are expensive to get wrong. ADR-006.
 * No floats anywhere in this file, by design.
 */

/** Money is integer minor units in EUR. Rule: PM-FEE-016 */
export type Money = { readonly amount_minor: number; readonly currency: 'EUR' };

export const eur = (amount_minor: number): Money => {
  if (!Number.isInteger(amount_minor)) {
    throw new TypeError(`Money must be integer minor units, got ${amount_minor} (PM-FEE-016)`);
  }
  return { amount_minor, currency: 'EUR' };
};

export const addMoney = (...xs: Money[]): Money =>
  eur(xs.reduce((s, x) => s + x.amount_minor, 0));

export const formatMoney = (m: Money): string =>
  `${(m.amount_minor / 100).toFixed(2)} €`;

/**
 * Ideal parts as a percentage, held as exact integer millionths of a percent.
 * 12.345600% is 12_345_600. Never a float. Rule: PM-ORG-002
 */
export type IdealParts = number & { readonly __brand: 'IdealParts' };
export const PPM = 1_000_000;
export const WHOLE: IdealParts = (100 * PPM) as IdealParts;

export const idealParts = (decimalString: string): IdealParts => {
  const m = /^(\d{1,3})(?:\.(\d{1,6}))?$/.exec(decimalString.trim());
  if (!m) throw new TypeError(`ideal parts must be an exact decimal string, got "${decimalString}" (PM-ORG-002)`);
  const whole = Number(m[1]);
  const frac = Number((m[2] ?? '').padEnd(6, '0'));
  return (whole * PPM + frac) as IdealParts;
};

export const formatIdealParts = (p: IdealParts): string =>
  `${Math.floor(p / PPM)}.${String(p % PPM).padStart(6, '0')}`;

/** Rule: PM-ORG-002 — the sum per entrance MUST equal 100% */
export const assertPartsSumTo100 = (parts: readonly IdealParts[]): void => {
  const total = parts.reduce((s, p) => s + p, 0);
  if (total !== WHOLE) {
    throw new RangeError(
      `ideal parts sum to ${formatIdealParts(total as IdealParts)}%, must be 100.000000% (PM-ORG-002)`,
    );
  }
};

/**
 * Split a pot into shares by weight so the parts sum EXACTLY to the total.
 * Largest remainder: never lose or invent a cent. Rule: PM-FEE-004, PM-FUND-003
 */
export const allocateByWeight = (total: Money, weights: readonly number[]): Money[] => {
  const sum = weights.reduce((s, w) => s + w, 0);
  if (sum <= 0) return weights.map(() => eur(0));
  const exact = weights.map((w) => (total.amount_minor * w) / sum);
  const floors = exact.map(Math.floor);
  let remainder = total.amount_minor - floors.reduce((s, f) => s + f, 0);
  const order = exact
    .map((e, i) => ({ i, frac: e - Math.floor(e) }))
    .sort((a, b) => b.frac - a.frac || a.i - b.i);
  const out = [...floors];
  for (const { i } of order) {
    if (remainder <= 0) break;
    out[i] = (out[i] ?? 0) + 1;
    remainder -= 1;
  }
  return out.map(eur);
};

export type EntranceId = string & { readonly __brand: 'EntranceId' };
