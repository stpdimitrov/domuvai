/**
 * A title recorded through the web (WEB-51): who owns or uses a unit, for what share, from and to which day. Pure: no import but
 * `book` and `refusal`, so it is tested on Node's own runner. The web sends what was typed and judges nothing — the share's range
 * (PM-ORG-005), the days and what they mean for the party in force on a date (PM-ORG-011), whether the party and the unit exist and
 * who may enter the book (PM-BOOK-005, PM-SEC-001) are the API's, in its words.
 */
import { ROLE } from "./book.ts";
import { undoneBy } from "./refusal.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const DECIMAL = /^(?:\d+(?:[.,]\d*)?|[.,]\d+)$/;      // the shape of a decimal only; its value is the API's

/** The request's `AssignTitleRequest`. The share stays the exact decimal string typed — never a number (ADR-006). */
export type NewTitle = { partyId: string; titleRole: string; share: string; validFrom?: string; validTo?: string };

/**
 * Rule: PM-ORG-005, PM-ORG-011 — a title as typed: the unit, the party (its number — no party list is read yet, #300), the role from
 * the API's closed list (`TitleRole`: `OWN`, `USR`, pinned by a test against the Kotlin and the CHECK), the share as a decimal
 * string and, if given, the days it starts and ends. A blank day is left out: the API starts a title today in Sofia (PM-SYS-004) and
 * the web computes no date. Whether the share is in (0, 1], whether the days are real and in order, and whether the co-owners'
 * shares add up are the API's — the last is not checked by anyone, which is a catalogue question, not the web's judgement.
 */
// Rule: PM-ORG-005, PM-ORG-011
export function titleOf(t: { unit: string; party: string; role: string; share: string; validFrom: string; validTo: string }): { unitId: string; body: NewTitle } | { refused: string } {
  const [unitId, partyId, titleRole, share, validFrom, validTo] = [t.unit.trim(), t.party.trim(), t.role.trim(), t.share.trim().replace(",", "."), t.validFrom.trim(), t.validTo.trim()];
  if (!UUID.test(unitId)) return { refused: "Изберете обекта." };
  if (!UUID.test(partyId)) return { refused: "Посочете номера на лицето, във вида, в който го дава системата." };
  if (!Object.hasOwn(ROLE, titleRole)) return { refused: titleRole ? `„${titleRole}“ не е вид право.` : "Изберете вида на правото." };
  if (!DECIMAL.test(share)) return { refused: "Посочете дяла като десетична дроб — 1 за целия обект, 0.5 за половината." };
  for (const day of [validFrom, validTo]) if (day && !DAY.test(day)) return { refused: `„${day}“ не е дата.` };
  return { unitId, body: { partyId, titleRole, share, ...(validFrom && { validFrom }), ...(validTo && { validTo }) } };
}

/** The owners the API reads back, as `OwnerResponse` — taken by name, so a field added later is not shown by this line. */
export type Owner = { unitId: string; partyName: string; titleRole: string; share: number };

/** Rule: PM-BOOK-011 — the unit's owners and users on the day, by name, role and share, and by nothing else. */
// Rule: PM-BOOK-011
export const ownersOf = (owners: Owner[], unitId: string): string[] =>
  owners
    .filter((o) => o.unitId === unitId)
    .sort((a, b) => a.partyName.localeCompare(b.partyName, "bg") || a.titleRole.localeCompare(b.titleRole))
    .map(({ partyName, titleRole, share }) => `${partyName} · ${ROLE[titleRole] ?? titleRole} · дял ${share}`);

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001 — only the manager or the board enter the book; anybody else, the owner included, is the API's 403, in its words. */
// Rule: PM-SEC-001
export const titleRefused = (answer: Answer): string => undoneBy(answer, "впишете право върху обект");
