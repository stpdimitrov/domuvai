/**
 * A unit's household entered in the book, and a resident's leaving recorded (WEB-45). Pure: no import but `refusal`, so
 * it is tested on Node's own runner. Who counts for a charge, and from when, is the API's (PM-FEE-005, PM-FEE-008):
 * the web types no age and no number of days, does no arithmetic on a date, and shows what the API answered.
 */
import { undoneBy } from "./refusal.ts";

/** How many residents one submission can enter — the form works without a script, so its rows are fixed; more are a second submission. */
export const ROWS = 4;

/** A row of the form as typed: what the resident is ("" = no resident in this row) and from when ("" = as the API takes it). */
export type MemberRow = { kind: string; from: string };

/** The request's `NewMemberRequest`: no name and no birth date go with a resident — the API takes neither. */
export type NewMember = { isChildUnder6: boolean; validFrom?: string };

/** What a row's kind says, in the page's words; "child" carries the precedent of `entrance/intake` (CHILDREN_UNDER_6). */
export const KIND: Record<string, string> = { adult: "Лице", child: "Деца под 6 г." };

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Rule: PM-FEE-005, PM-FEE-008, PM-BOOK-008 — the residents as typed, one `NewMemberRequest` each: whether the person
 * is a child under 6 is what the person ticked (the web does no age arithmetic), and the day they began is sent as
 * typed, only when typed (the date range is the API's). A row with no kind is no resident; none at all is refused here.
 */
// Rule: PM-FEE-005, PM-FEE-008, PM-BOOK-008
export function membersOf(rows: MemberRow[]): { members: NewMember[] } | { refused: string } {
  const members: NewMember[] = [];
  for (const [i, { kind, from }] of rows.entries()) {
    const [typedKind, typedFrom] = [kind.trim(), from.trim()];
    if (!typedKind) continue;
    if (!(typedKind in KIND)) return { refused: `Ред ${i + 1}: „${typedKind}“ не е вид живущ.` };
    if (typedFrom && !DAY.test(typedFrom)) return { refused: `Ред ${i + 1}: „${typedFrom}“ не е дата.` };
    members.push({ isChildUnder6: typedKind === "child", ...(typedFrom && { validFrom: typedFrom }) });
  }
  return members.length ? { members } : { refused: "Посочете поне един живущ." };
}

/** Rule: PM-BOOK-008 — the day a resident left, as typed: the range it closes is the API's. */
// Rule: PM-BOOK-008
export const leavingOf = (typed: string): { on: string } | { refused: string } => {
  const on = typed.trim();
  return DAY.test(on) ? { on } : { refused: on ? `„${on}“ не е дата.` : "Посочете деня, в който живущият е напуснал." };
};

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001, PM-BOOK-005 — who may enter the book is the API's: its refusal and its conflicts, in its words, nothing said to be entered. */
// Rule: PM-SEC-001, PM-BOOK-005
export const entryRefused = (answer: Answer): string => undoneBy(answer, "вписвате живущи в книгата");

/** The same for a leaving (Rule: PM-SEC-001, PM-BOOK-008). */
// Rule: PM-SEC-001, PM-BOOK-008
export const leavingRefused = (answer: Answer): string => undoneBy(answer, "записвате напускане на живущ");
