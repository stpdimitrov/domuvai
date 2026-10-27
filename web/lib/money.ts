/** Integer minor units as euros, €1.234,56 — integer arithmetic only, no floats (PM-FEE-016). */
// Rule: PM-FEE-016
export const eur = (minor: number) => {
  const abs = Math.abs(minor);
  const euros = ((abs - (abs % 100)) / 100).toLocaleString("de-DE");
  return `${minor < 0 ? "−" : ""}€${euros},${String(abs % 100).padStart(2, "0")}`;
};

/** Euros as typed, at most two decimals, to minor units as text — the digits joined, never a float (PM-FEE-016). */
// Rule: PM-FEE-016
export const minorOf = (typed: string): number | null => {
  const m = /^(\d{1,13})(?:[.,](\d{1,2}))?$/.exec(typed.trim());
  return m ? Number(m[1] + (m[2] ?? "").padEnd(2, "0")) : null;
};
