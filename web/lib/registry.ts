/**
 * Entrances and parties registered (WEB-50). Pure: no import but `refusal`, so it is tested on Node's own runner. Who may
 * register either (PM-SEC-001) is the API's: the web gates nothing — it sends the form as typed and shows the API's answer
 * in its words, a 403 included.
 */
import { undoneBy } from "./refusal.ts";

/**
 * How an entrance is run — the closed list the `management_form` column accepts (the CHECK in `V1__init.sql`; the spec gives
 * `RegisterEntranceRequest.managementForm` as a bare string, so the list is read from the code, not invented here).
 */
export const MANAGEMENT_FORM: Record<string, string> = { GA: "общо събрание", ASSOCIATION: "сдружение", CLOSED_COMPLEX: "затворен комплекс" };

/** What identifies a party — `IdType` in `Party.kt` (the spec gives `RegisterPartyRequest.idType` as a bare string). */
export const ID_TYPE: Record<string, string> = { EGN: "ЕГН", LNCH: "ЛНЧ", BULSTAT: "БУЛСТАТ", PASSPORT: "паспорт" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The request's `RegisterEntranceRequest`, as typed — a blank field is left out. */
export type NewEntrance = { label: string; managementForm: string; address?: string; condominiumId?: string };

/**
 * Rule: PM-ORG-001 — an entrance as typed: a label, how it is run, and a new building's address or the number of the building
 * it joins. That exactly one of the two is given is the API's (its 400 in its words); the web checks what was typed is a word
 * from the list and an id.
 */
// Rule: PM-ORG-001
export function entranceOf(t: { label: string; managementForm: string; address: string; condominiumId: string }): NewEntrance | { refused: string } {
  const [label, managementForm, address, condominiumId] = [t.label.trim(), t.managementForm.trim(), t.address.trim(), t.condominiumId.trim()];
  if (!label) return { refused: "Посочете означението на входа." };
  if (!Object.hasOwn(MANAGEMENT_FORM, managementForm)) return { refused: managementForm ? `„${managementForm}“ не е начин на управление.` : "Изберете начина на управление." };
  if (condominiumId && !UUID.test(condominiumId)) return { refused: "Посочете номера на сградата, във вида, в който го дава системата." };
  return { label, managementForm, ...(address && { address }), ...(condominiumId && { condominiumId }) };
}

/** The request's `RegisterPartyRequest`, as typed. */
export type NewParty = { fullName: string; idType?: string; idValue?: string };

/**
 * Rule: PM-BOOK-011 — a party as typed: the full name, and optionally what identifies them. The identity number goes to the
 * API and nowhere else: it is not kept in the form after an answer, and not shown back.
 */
// Rule: PM-BOOK-011
export function partyOf(t: { fullName: string; idType: string; idValue: string }): NewParty | { refused: string } {
  const [fullName, idType, idValue] = [t.fullName.trim(), t.idType.trim(), t.idValue.trim()];
  if (!fullName) return { refused: "Посочете пълното име." };
  if (idType && !Object.hasOwn(ID_TYPE, idType)) return { refused: `„${idType}“ не е вид на идентификатора.` };
  return { fullName, ...(idType && { idType }), ...(idValue && { idValue }) };
}

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001 — only the administrator registers an entrance; a party, the administrator or an entrance's manager or board: anybody else is the API's 403, in its words. */
// Rule: PM-SEC-001
export const entranceRefused = (answer: Answer): string => undoneBy(answer, "регистрирате вход");
// Rule: PM-SEC-001
export const partyRefused = (answer: Answer): string => undoneBy(answer, "регистрирате лице");
