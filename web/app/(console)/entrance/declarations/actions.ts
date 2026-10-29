"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { declarationOf, filingRefused, retentionRefused } from "@/lib/declaration";
import { unknownOutcome } from "@/lib/refusal";

/** What the declaration form holds after a submission: the form as typed, and the API's answer — the declaration as filed, or why not. */
export type DeclarationState = {
  input?: { unit: string; kind: string; declarant: string };
  filed?: { id: string; filedOn: string; templateVersion: string };
  refused?: string;
};

/** What the retention form holds after a submission: the API's three figures, or why not. */
export type RetentionState = { applied?: { on: string; householdUnlinked: number; animalPassportsCleared: number }; refused?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");

/**
 * A declaration filed for entry in the book (WEB-47). Rule: PM-BOOK-003, PM-BOOK-004 — the date it is filed on and the
 * template it is on are the system's: the form has no date field, and what the API answers is shown. Rule: PM-BOOK-005,
 * PM-SEC-001 — with no declarant named the signed-in person files for themself; naming one is the manager or the board
 * recording it in their name, and whether the person may, and holds the unit, is the API's: a refusal is shown in its
 * words, the form kept.
 */
// Rule: PM-BOOK-003, PM-BOOK-004, PM-BOOK-005, PM-SEC-001
export async function file(_: DeclarationState, form: FormData): Promise<DeclarationState> {
  const entranceId = text(form, "entrance");
  const input = { unit: text(form, "unit"), kind: text(form, "kind"), declarant: text(form, "declarant") };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  const body = declarationOf(input);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/book/declarations", { params: { path: { entranceId } }, body }));
  // no answer: the declaration may be filed — sent again it would be filed twice
  if (!answer) return { input, refused: unknownOutcome("подаването на декларацията") };
  return answer.data
    ? { input, filed: { id: answer.data.id, filedOn: answer.data.filedOn, templateVersion: answer.data.templateVersion } }
    : { input, refused: filingRefused(answer) };
}

/**
 * The retention run (WEB-47). Rule: PM-BOOK-010 — personal data past its retention window is anonymised, irreversibly,
 * as of today in Sofia: the web sends no body, computes no window and no date, and shows the three figures the API
 * answers. The run is behind an explicit confirmation, checked here too — the form works without a script. Rule:
 * PM-SEC-001 — only the manager and the board may; a refusal is shown in the API's words and nothing said to be done.
 */
// Rule: PM-BOOK-010, PM-SEC-001
export async function applyRetention(_: RetentionState, form: FormData): Promise<RetentionState> {
  const entranceId = text(form, "entrance");
  if (!UUID.test(entranceId)) return { refused: "Входът не е посочен." };
  if (text(form, "confirm") !== "yes") return { refused: "Потвърдете, че разбирате, че заличаването не може да се върне." };

  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/book/retention", { params: { path: { entranceId } } }));
  if (!answer) return { refused: unknownOutcome("заличаването") };
  return answer.data
    ? { applied: { on: answer.data.on, householdUnlinked: answer.data.householdUnlinked, animalPassportsCleared: answer.data.animalPassportsCleared } }
    : { refused: retentionRefused(answer) };
}
