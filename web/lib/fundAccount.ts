/**
 * An entrance's bank accounts as the API lists them, and one to register made the API's body (WEB-41). Pure: no import
 * but `undone`, so it is tested on Node's own runner. Whether an IBAN is valid, whether it is held elsewhere and whether
 * the person may register one are all the API's; the web only says what is held and sends what was typed.
 */
import { refused, undone } from "./refusal.ts";

export type Account = { id: string; purpose: string; iban: string; holderName: string; holderKind: string; holderParty?: string };
export type AccountInput = { purpose: string; iban: string; holderName: string; holderKind: string };
export type AccountBody = { purpose: string; iban: string; holderName: string; holderKind: string };

/** The purposes an entrance keeps one account each for, the fund's first (PM-FUND-001), the API's codes in words. */
export const PURPOSE: Record<string, string> = {
  REPAIR_RENEWAL: "Сметка на фонд „Ремонт и обновяване“",
  OPERATING: "Оперативна сметка",
};
/** In whose name the account is opened (PM-FUND-004): the chair of the board (the manager), or the association. */
export const HOLDER: Record<string, string> = {
  MANAGER: "председателя на управителния съвет (управителя)",
  ASSOCIATION: "сдружението на собствениците",
};

/**
 * Rule: PM-FUND-004 — the fund's account is kept apart from the operating account: each purpose is listed on its own,
 * in the order above, then any purpose the web has no words for, as the API named it.
 */
// Rule: PM-FUND-004
export function byPurpose(accounts: Account[]): { purpose: string; accounts: Account[] }[] {
  const purposes = [...new Set([...Object.keys(PURPOSE), ...accounts.map((a) => a.purpose)])];
  return purposes.map((purpose) => ({ purpose, accounts: accounts.filter((a) => a.purpose === purpose) }));
}

/**
 * Rule: PM-FUND-001 — an entrance with no "Ремонт и обновяване" account is flagged, and the form opens on it.
 * Rule: PM-FUND-004 — a purpose already held is not offered again: the entrance keeps one account per purpose.
 */
// Rule: PM-FUND-004
export const unheld = (accounts: Account[]): string[] => Object.keys(PURPOSE).filter((p) => !accounts.some((a) => a.purpose === p));
// Rule: PM-FUND-001
export const fundMissing = (accounts: Account[]): boolean => !accounts.some((a) => a.purpose === "REPAIR_RENEWAL");

/**
 * Rule: PM-FUND-004 — the account is in someone's name: the holder's name and kind are required. The IBAN is sent as
 * typed; the API normalises it, checks it and refuses one already registered (PM-FUND-005).
 */
// Rule: PM-FUND-004
export function accountOf(input: AccountInput): AccountBody | { refused: string } {
  const [iban, holderName] = [input.iban.trim(), input.holderName.trim()];
  if (!(input.purpose in PURPOSE)) return { refused: "Изберете за какво е сметката." };
  if (!iban) return { refused: "Въведете IBAN на сметката." };
  if (!holderName) return { refused: "Въведете на чие име е открита сметката." };
  if (!(input.holderKind in HOLDER)) return { refused: "Изберете дали сметката е на името на управителя или на сдружението." };
  return { purpose: input.purpose, iban, holderName, holderKind: input.holderKind };
}

/**
 * Rule: PM-SEC-001 — who may register an account is the API's (the manager or the board, G-3): its refusal is said in
 * its own words, and a conflict (an account of that purpose held, an IBAN registered elsewhere) as it came.
 */
// Rule: PM-SEC-001
export const registrationRefused = (answer: { error?: unknown; response: { status: number } }): string =>
  undone(refused(answer), "регистрирате сметка на входа");
