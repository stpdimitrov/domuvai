"use server";

import { revalidatePath } from "next/cache";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { sofiaToday } from "@/lib/payment";
import { unknownOutcome, unansweredBy } from "@/lib/refusal";
import { ownersOf, titleOf, titleRefused } from "@/lib/titles";
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

/** What the title form holds after a submission: the fields as typed, and the API's answer — the title's number and the owners read back, or why not. */
export type TitleState = {
  typed?: Record<string, string>;
  refused?: string;
  done?: { titleId: string; on: string; owners?: string[]; ownersRefused?: string };
};

/**
 * One title recorded for a unit (WEB-51). Rule: PM-ORG-005, PM-ORG-011 — the share and the days go as typed; the share's range, the
 * days, the party and the unit are the API's, in its words. Rule: PM-SEC-001, PM-BOOK-005 — who may enter the book is the API's.
 * Once recorded, the unit's owners are read back as of the title's first day (today in Sofia if none was typed): names only.
 */
// Rule: PM-ORG-005, PM-ORG-011, PM-BOOK-005, PM-SEC-001
export async function recordTitle(_: TitleState, form: FormData): Promise<TitleState> {
  const [unit, party, role, share, validFrom, validTo] = ["unit", "party", "role", "share", "validFrom", "validTo"].map((name) => text(form, name));
  const typed = { unit, party, role, share, validFrom, validTo };
  const entranceId = text(form, "entrance").trim();
  if (!UUID.test(entranceId)) return { typed, refused: "Входът не е посочен." };
  const title = titleOf(typed);
  if ("refused" in title) return { typed, refused: title.refused };
  const unitId = title.unitId;
  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/titles", { params: { path: { entranceId, unitId } }, body: title.body }));
  // no answer: the title may be recorded — sent again it would be a second one for the same party and unit
  if (!answer) return { typed, refused: unknownOutcome("вписването на правото") };
  if (!answer.data) return { typed, refused: titleRefused(answer) };
  const on = title.body.validFrom ?? sofiaToday();
  const owners = await reach(() => api.GET("/api/registry/entrances/{entranceId}/owners", { params: { path: { entranceId }, query: { on } } }));
  return { done: { titleId: answer.data.titleId, on, ...(owners?.data ? { owners: ownersOf(owners.data, unitId) } : { ownersRefused: unansweredBy(owners, "собствениците на входа") }) } };
}
