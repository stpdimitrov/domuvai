/**
 * A declaration filed for the book, and the retention run (WEB-47). Pure: no import but `refusal`, so it is tested on
 * Node's own runner. The filing date and the template are the system's (PM-BOOK-003, PM-BOOK-004) and the retention
 * window is the API's (PM-BOOK-010): the web types no date, no number of days and no template, and shows what the API
 * answered.
 */
import { undoneBy } from "./refusal.ts";

/**
 * What a declaration is for — the closed list of the API's `DeclarationKind` (`BookDeclaration.kt`). The spec gives the
 * field as a bare string (the enum is not emitted, ADR-013), so the list is read from the code, not invented here; a
 * kind outside it is the API's 400.
 */
export const KIND: Record<string, string> = { ACQUISITION: "придобиване на собственост или ползване", CHANGE: "промяна на декларирани данни" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The request's `FileDeclarationRequest`: no date goes with it — the system dates it — and `partyId` only when someone is recorded for. */
export type NewDeclaration = { kind: string; unitId: string; partyId?: string };

/**
 * Rule: PM-BOOK-003, PM-BOOK-005 — the declaration as typed. A declarant is named only when the manager or the board
 * records one handed in; left empty, the signed-in person files for themself. Whether that party holds the unit is
 * the API's to say (its 404, in its words); the web checks only that what was typed is an id.
 */
// Rule: PM-BOOK-003, PM-BOOK-005
export function declarationOf(typed: { unit: string; kind: string; declarant: string }): NewDeclaration | { refused: string } {
  const [unit, kind, declarant] = [typed.unit.trim(), typed.kind.trim(), typed.declarant.trim()];
  if (!UUID.test(unit)) return { refused: "Изберете обект." };
  if (!(kind in KIND)) return { refused: kind ? `„${kind}“ не е вид декларация.` : "Изберете за какво е декларацията." };
  if (declarant && !UUID.test(declarant)) return { refused: "Номерът на декларанта не е във вида, в който го дава системата." };
  return { kind, unitId: unit, ...(declarant && { partyId: declarant }) };
}

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001, PM-BOOK-003 — who may file, or record, is the API's: its refusal and its conflicts, in its words, nothing said to be filed. */
// Rule: PM-SEC-001, PM-BOOK-003
export const filingRefused = (answer: Answer): string => undoneBy(answer, "подавате декларация за книгата");

/** Rule: PM-SEC-001, PM-BOOK-010 — the retention run is the manager's and the board's: a 403 is shown in the API's words, nothing said to be anonymised. */
// Rule: PM-SEC-001, PM-BOOK-010
export const retentionRefused = (answer: Answer): string => undoneBy(answer, "пускате заличаването на данни след срока на съхранение");
