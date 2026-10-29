"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { entryRefused, leavingOf, leavingRefused, membersOf, ROWS, type MemberRow } from "@/lib/household";
import { unknownOutcome } from "@/lib/refusal";

/** What the household form holds after a submission: the form as typed, and the API's answer — the residents entered, or why not. */
export type HouseholdState = { input?: { unit: string; rows: MemberRow[] }; entered?: string[]; refused?: string };

/** The same for a leaving: the form as typed, and the stay the API closed, or why not. */
export type LeavingState = { input?: { unit: string; member: string; on: string }; left?: { validFrom: string; validTo: string }; refused?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");

/**
 * A unit's household entered in the book (WEB-45). Rule: PM-FEE-005, PM-FEE-008, PM-BOOK-008 — each resident goes to the
 * API as ticked and dated as typed; who counts for a charge is the API's. Rule: PM-BOOK-005, PM-SEC-001 — who may enter
 * the book is the API's (the manager or the board): a refusal is shown in its words, the form kept, nothing said to be
 * entered. The ids it gives back are shown, for a leaving to be recorded against; the entry is read back through the
 * book's own read, with its purpose (WEB-44), not a second path.
 */
// Rule: PM-FEE-005, PM-FEE-008, PM-BOOK-008, PM-BOOK-005, PM-SEC-001
export async function enter(_: HouseholdState, form: FormData): Promise<HouseholdState> {
  const [entranceId, unitId] = [text(form, "entrance"), text(form, "unit")];
  const rows = Array.from({ length: ROWS }, (_, i) => ({ kind: text(form, `kind${i}`), from: text(form, `from${i}`) }));
  const input = { unit: unitId, rows };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  if (!UUID.test(unitId)) return { input, refused: "Изберете обект." };
  const body = membersOf(rows);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/household", { params: { path: { entranceId, unitId } }, body }));
  // no answer: the household may be entered — the book says; sent again it would be entered twice
  if (!answer) return { input, refused: unknownOutcome("вписването на живущите") };
  return answer.data ? { input, entered: answer.data.memberIds } : { input, refused: entryRefused(answer) };
}

/**
 * A resident's leaving recorded (WEB-45). Rule: PM-BOOK-008 — the day is sent as typed and the occupancy range it closes
 * is the API's (it refuses a day not after the move-in, in its words). Rule: PM-BOOK-005, PM-SEC-001 — who may record it
 * is the API's; a refusal is shown in its words, the form kept.
 */
// Rule: PM-BOOK-008, PM-BOOK-005, PM-SEC-001
export async function leave(_: LeavingState, form: FormData): Promise<LeavingState> {
  const [entranceId, unitId, memberId] = [text(form, "entrance"), text(form, "unit"), text(form, "member")];
  const input = { unit: unitId, member: memberId, on: text(form, "on") };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  if (!UUID.test(unitId)) return { input, refused: "Изберете обект." };
  if (!UUID.test(memberId)) return { input, refused: "Номерът на живущия не е във вида, в който го дава системата при вписването." };
  const body = leavingOf(input.on);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() =>
    api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/household/{memberId}/end", { params: { path: { entranceId, unitId, memberId } }, body }));
  if (!answer) return { input, refused: unknownOutcome("записването на напускането") };
  return answer.data ? { input, left: { validFrom: answer.data.validFrom, validTo: answer.data.validTo } } : { input, refused: leavingRefused(answer) };
}
