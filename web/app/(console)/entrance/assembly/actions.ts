"use server";

import { createHash } from "node:crypto";
import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { undoneBy, unknownOutcome } from "@/lib/refusal";
import { sofiaInstant } from "@/lib/sofia";

export type ConveneState = { refused?: string; values?: Record<string, string> };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Convening an assembly (Rule: PM-GA-002, PM-GA-005) — the api's act, asked with the signed-in person's bearer. The
 * page decides nothing of the law: whether this person holds the office they convene as today, whether the urgency
 * is justified, whether the time leaves room for the notice — all the api's, and a refusal is shown in its words,
 * the form kept as filled. The one thing read here is the time typed, as Sofia's clock shows it.
 */
export async function convene(_: ConveneState, form: FormData): Promise<ConveneState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "convenedAs", "scheduledAt", "place", "mode", "urgencyReason"].map((n) => [n, text(n)]));
  const urgent = form.get("urgent") === "on";
  if (urgent) values.urgent = "on";
  if (!UUID.test(values.entrance)) return { refused: "Входът не е посочен.", values };
  const scheduledAt = sofiaInstant(values.scheduledAt);
  if (!scheduledAt) {
    return { refused: "Посочете дата и час, каквито ги показва часовникът в София — този час не съществува или не е попълнен.", values };
  }

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies", {
      params: { path: { entranceId: values.entrance } },
      body: {
        convenedAs: values.convenedAs,
        scheduledAt,
        place: values.place,
        mode: values.mode,
        urgent,
        // sent whenever typed: a reason without urgency is the api's to refuse (PM-GA-005), not the page's to drop
        ...(values.urgencyReason && { urgencyReason: values.urgencyReason }),
      },
    }),
  );
  if (!answer?.data) {
    // no answer at all (the backend down, or the call given up after its time): the api may have convened it already
    return { refused: answer ? undoneBy(answer, "свикате общо събрание") : unknownOutcome("свикването"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${answer.data.id}`);
}

export type AgendaState = { refused?: string; values?: Record<string, string> };

/**
 * Putting an item on the agenda (Rule: PM-GA-006, PM-VOTE-004) — the api's act, asked with the signed-in person's
 * bearer. Whether this person runs the assembly, whether its agenda is still open and which majority the item's type
 * is bound to are all the api's; a refusal is shown in its words, the form kept as filled. An item added after the
 * notice was posted voids it (PM-GA-006): the api says so, and the page says it on the assembly it returns to.
 */
export async function addAgendaItem(_: AgendaState, form: FormData): Promise<AgendaState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "text", "itemType"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { text: values.text, itemType: values.itemType },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "добавите точка в дневния ред") : unknownOutcome("добавянето на точката"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}${answer.data.noticeVoided ? "&voided=1" : ""}`);
}

export type NoticeState = { refused?: string; values?: Record<string, string> };

/**
 * The posting act (Rule: PM-GA-004, PM-GA-007, PM-SYS-013) — the api's, asked with the signed-in person's bearer: the
 * only way an assembly becomes NOTICED. The photograph of the posted notice goes as its SHA-256, taken here from the
 * file uploaded (the file itself is the evidence store's, not this page's). Whether this person may sign the act,
 * whether the co-signatory is an owner that day and whether the meeting is far enough away are all the api's; a
 * refusal is shown in its words, the fields kept — the photograph is chosen again, as no browser keeps a file.
 */
// Rule: PM-GA-004
// Rule: PM-GA-007
// Rule: PM-SYS-013
export async function postNotice(_: NoticeState, form: FormData): Promise<NoticeState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "postedAt", "coSignatoryPartyId"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };
  if (!UUID.test(values.coSignatoryPartyId)) return { refused: "Посочете идентификатора на собственика, който подписва протокола с вас.", values };
  const postedAt = sofiaInstant(values.postedAt);
  if (!postedAt) {
    return { refused: "Посочете кога е обявена поканата, както го показва часовникът в София — този час не съществува или не е попълнен.", values };
  }
  const photo = form.get("photo");
  if (!(photo instanceof File) || photo.size === 0) return { refused: "Приложете снимка на обявената покана.", values };
  const photoHash = createHash("sha256").update(Buffer.from(await photo.arrayBuffer())).digest("hex");

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/notice-posting", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { postedAt, coSignatoryPartyId: values.coSignatoryPartyId, photoHash },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "впишете обявяването на поканата") : unknownOutcome("вписването на обявяването"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}`);
}

export type ScheduleState = { refused?: string; values?: Record<string, string> };

/**
 * Moving the meeting (Rule: PM-GA-004, PM-GA-006) — the api's act. Whether the new time leaves room for a notice is
 * the api's; so is what becomes of a posted notice: when the api answers with the assembly a draft again, the notice
 * it had stated another date, hour or place and is void, and the page says so on the assembly it returns to.
 */
// Rule: PM-GA-004
// Rule: PM-GA-006
export async function reschedule(_: ScheduleState, form: FormData): Promise<ScheduleState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "wasNoticed", "scheduledAt", "place"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };
  const scheduledAt = sofiaInstant(values.scheduledAt);
  if (!scheduledAt) {
    return { refused: "Посочете дата и час, каквито ги показва часовникът в София — този час не съществува или не е попълнен.", values };
  }

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/schedule", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { scheduledAt, place: values.place },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "преместите събранието") : unknownOutcome("преместването"), values };
  }
  // the api's answer (its view carries no noticeVoided), against the status the form was shown on: a stale form on an
  // assembly already a draft says nothing more than the draft itself
  const voided = values.wasNoticed === "1" && answer.data.status === "DRAFT";
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}${voided ? "&moved=1" : ""}`);
}
