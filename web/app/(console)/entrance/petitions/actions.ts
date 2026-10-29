"use server";

import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { conveningOf, conveningRefused, openRefused, petitionIdOf, signingRefused, subjectOf } from "@/lib/petition";
import { unknownOutcome } from "@/lib/refusal";

/** What a petition form holds after a submission: the API's refusal in its words, and the form as typed. */
export type PetitionState = { refused?: string; values?: Record<string, string> };

/** What the convening form holds: the assembly the API convened, or why not. */
export type ConveningState = PetitionState & { convened?: { assemblyId: string; convenedAs: string; status: string; heldPct: string; thresholdPct: string } };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");

/**
 * A petition opened (WEB-48). Rule: PM-GA-003 — an owner opens one and signs it by opening; whether the signed-in person is
 * an owner is the API's, a refusal shown in its words and the form kept. The petition it answers is read back on its page.
 */
// Rule: PM-GA-003, PM-SEC-001
export async function open(_: PetitionState, form: FormData): Promise<PetitionState> {
  const [entranceId, values] = [text(form, "entrance"), { subject: text(form, "subject") }];
  if (!UUID.test(entranceId)) return { values, refused: "Входът не е посочен." };
  const body = subjectOf(values.subject);
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/assembly/entrances/{entranceId}/petitions", { params: { path: { entranceId } }, body }));
  // no answer: the petition may be open — sent again it would be opened twice
  if (!answer) return { values, refused: unknownOutcome("откриването на петицията") };
  if (!answer.data) return { values, refused: openRefused(answer) };
  redirect(`/entrance/petitions?entrance=${entranceId}&petition=${answer.data.id}`);
}

/** A petition signed (WEB-48): the signed-in owner, for themself, once — the API's rule, shown in its words. Rule: PM-GA-003, PM-SEC-001. */
// Rule: PM-GA-003, PM-SEC-001
export async function sign(_: PetitionState, form: FormData): Promise<PetitionState> {
  const [entranceId, petitionId] = [text(form, "entrance"), petitionIdOf(text(form, "petition"))];
  if (!UUID.test(entranceId) || !petitionId) return { refused: "Петицията не е посочена." };
  const answer = await reach(() => api.POST("/api/assembly/entrances/{entranceId}/petitions/{petitionId}/signatures", { params: { path: { entranceId, petitionId } } }));
  if (!answer) return { refused: unknownOutcome("подписването") };
  if (!answer.data) return { refused: signingRefused(answer) };
  redirect(`/entrance/petitions?entrance=${entranceId}&petition=${petitionId}`);
}

/**
 * The owners convene on their petition (WEB-48). Rule: PM-GA-003 — only when the API unlocked it and found the demand unmet,
 * once, by a signatory: the web checks none of it and shows the refusal in the API's words, the form kept.
 */
// Rule: PM-GA-003, PM-SEC-001
export async function convene(_: ConveningState, form: FormData): Promise<ConveningState> {
  const [entranceId, petitionId] = [text(form, "entrance"), petitionIdOf(text(form, "petition"))];
  const values = Object.fromEntries(["demandUnmet", "mode", "place", "scheduledAt", "urgencyReason"].map((n) => [n, text(form, n)]));
  const urgent = form.get("urgent") === "on";
  if (!UUID.test(entranceId) || !petitionId) return { values, refused: "Петицията не е посочена." };
  const body = conveningOf({ demandUnmet: values.demandUnmet, mode: values.mode, place: values.place, scheduledAt: values.scheduledAt, urgent, urgencyReason: values.urgencyReason });
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/assembly/entrances/{entranceId}/petitions/{petitionId}/assembly", { params: { path: { entranceId, petitionId } }, body }));
  // no answer: the assembly may be convened — and it is convened once
  if (!answer) return { values, refused: unknownOutcome("свикването") };
  if (!answer.data) return { values, refused: conveningRefused(answer) };
  const { assemblyId, convenedAs, status, heldPct, thresholdPct } = answer.data;
  return { values, convened: { assemblyId, convenedAs, status, heldPct, thresholdPct } };
}
