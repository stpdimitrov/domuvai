"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { partyOptions, type PartyOption } from "@/lib/partyOption";
import { sofiaToday } from "@/lib/payment";
import { unansweredBy } from "@/lib/refusal";

export type PartiesState = { options?: PartyOption[]; refused?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The parties a picker offers, read on the click and never on a page load (WEB-52b) — the api's reads, asked with the signed-in
 * person's bearer: `GET …/owners`, which the API serves under its standing purpose and enters in the book's access log per answer,
 * or `GET …/mandates`. As of the day the form's act is about, when it has one, else today. Who may read is the api's; a refusal
 * is shown in its words. Only each party's id and name leave here: no share, no unit.
 */
// Rule: PM-BOOK-006, PM-BOOK-007, PM-SEC-001, PM-SEC-002
export async function readParties(_: PartiesState, form: FormData): Promise<PartiesState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const [entranceId, on] = [text("entrance"), text("on")];
  if (!UUID.test(entranceId)) return { refused: "Входът не е посочен." };
  if (text("source") === "mandates") {
    const answer = await reach(() => api.GET("/api/identity/entrances/{entranceId}/mandates", { params: { path: { entranceId }, query: on ? { on } : {} } }));   // no day: "today" is the api's own
    return answer?.data ? { options: partyOptions(answer.data) } : { refused: unansweredBy(answer, "мандатите на входа") };
  }
  const answer = await reach(() => api.GET("/api/registry/entrances/{entranceId}/owners", { params: { path: { entranceId }, query: { on: on || sofiaToday() } } }));
  return answer?.data ? { options: partyOptions(answer.data, text("role") || undefined) } : { refused: unansweredBy(answer, "собствениците на входа") };
}
