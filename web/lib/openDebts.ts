/**
 * The debts the payment form offers to designate (WEB-53): a unit's open debts as the API lists them for the
 * payment's value date, shown as options. Pure: no import but `eur`, so it is tested on Node's own runner. The web
 * decides nothing here — which debts are open, their due days and what is outstanding are the API's (Rule:
 * PM-SEC-002, enforced there); a read it refused offers nothing, and the payment is then recorded oldest first.
 */
import { eur } from "./money.ts";

export type OpenDebt = { debtDate: string; dueOn: string; outstandingMinor: number };

/** What the form is told: the API's list, or the words for why there is none — never an invented list. */
export type Offered = { debts: OpenDebt[] } | { unread: string };

/** One debt as an option: its date (the value the payer designates), the day it fell due and what is outstanding. */
// Rule: PM-DEBT-008
export const debtLabel = (d: OpenDebt) => `Задължение от ${d.debtDate} · падеж ${d.dueOn} · остава ${eur(d.outstandingMinor)}`;

/** Whether there is anything to ask the API: a unit and the payment's value date, both chosen. */
export const worthAsking = (unit: string, valueDate: string) => unit !== "" && valueDate !== "";

/**
 * The designation the form sends: the one chosen, if the debts offered now still include it, else none — oldest
 * debt first, the default. A choice made for another unit or date is never carried over to this one, and while the
 * list is on its way (or was refused) nothing is designated.
 */
// Rule: PM-DEBT-008
export const designationOf = (chosen: string, offered: Offered | null): string =>
  offered && "debts" in offered && offered.debts.some((d) => d.debtDate === chosen) ? chosen : "";

/** What a read was asked for, so an answer is used only for the unit and date it was asked about. */
export const askedOf = (unit: string, valueDate: string) => `${unit}|${valueDate}`;

/** The list that applies to the unit and date now chosen: an answer to an earlier question, or none yet, is no list. */
export const offerFor = (unit: string, valueDate: string, answer: { asked: string; offered: Offered } | null): Offered | null =>
  worthAsking(unit, valueDate) && answer?.asked === askedOf(unit, valueDate) ? answer.offered : null;
