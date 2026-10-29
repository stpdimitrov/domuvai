"use server";

import { revalidatePath } from "next/cache";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { unknownOutcome } from "@/lib/refusal";
import { unitsOf, unitsRefused } from "@/lib/units";

/** What the units form holds after a submission: the list as typed, and the API's answer — the ids it gave, or why not. */
export type UnitsState = { typed?: string; refused?: string; done?: string[] };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string) : "");

/**
 * An entrance's units registered, the whole set in one request (WEB-50b). Rule: PM-ORG-002, PM-ORG-003 — the ideal parts go as
 * typed, or are left out for the API to derive by area; the sum, the mix of declared and derived, the areas and an entrance
 * that already has units are the API's, in its words. Rule: PM-SEC-001 — who may is the API's (the manager or the board).
 */
// Rule: PM-ORG-002, PM-ORG-003, PM-SEC-001
export async function registerUnits(_: UnitsState, form: FormData): Promise<UnitsState> {
  const [entranceId, typed] = [text(form, "entrance").trim(), text(form, "units")];
  if (!UUID.test(entranceId)) return { typed, refused: "Входът не е посочен." };
  const body = unitsOf(typed);
  if ("refused" in body) return { typed, refused: body.refused };
  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/units", { params: { path: { entranceId } }, body }));
  // no answer: the units may be registered — sent again they would be refused as a second set, or doubled
  if (!answer) return { typed, refused: unknownOutcome("регистрирането на обектите") };
  if (!answer.data) return { typed, refused: unitsRefused(answer) };
  revalidatePath("/entrance/units");                                       // the list above the form reads the units registered
  return { done: answer.data.unitIds };
}
