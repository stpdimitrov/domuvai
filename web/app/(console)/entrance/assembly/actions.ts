"use server";

import { createHash } from "node:crypto";
import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { proxyOf, proxyRefused } from "@/lib/proxy";
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
  const values = Object.fromEntries(["entrance", "assembly", "text", "itemType", "absenteeEligible"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { text: values.text, itemType: values.itemType, absenteeEligible: values.absenteeEligible === "on" },   // Rule: PM-GA-017 — the drafter's tag
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

export type SittingState = { refused?: string };

/**
 * Weighing the quorum of the sitting the assembly is at, and opening it if met (Rule: PM-GA-012, PM-GA-013, PM-GA-014)
 * — the api's act, once per sitting. Which sitting is at hand, whether its hour has come, the threshold in force and
 * the share the register makes present are all the api's; so is who may weigh it (PM-SEC-001). A refusal is shown in
 * its words; a sitting weighed short is the api's answer too, shown among the sittings on the page it returns to.
 */
// Rule: PM-GA-012
// Rule: PM-GA-013
// Rule: PM-GA-014
// Rule: PM-SEC-001
export async function openSitting(_: SittingState, form: FormData): Promise<SittingState> {
  const entrance = String(form.get("entrance") ?? ""), assembly = String(form.get("assembly") ?? "");
  if (!UUID.test(entrance) || !UUID.test(assembly)) return { refused: "Събранието не е посочено." };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/sittings", {
      params: { path: { entranceId: entrance, assemblyId: assembly } },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "претеглите кворума") : unknownOutcome("претеглянето на кворума") };
  }
  redirect(`/entrance/assembly?entrance=${entrance}&assembly=${assembly}`);
}

export type AttendanceState = { refused?: string; values?: Record<string, string> };

/**
 * Entering a person in the register of the sitting (Rule: PM-GA-012, PM-GA-016) — the api's act. Whether this
 * person keeps the register (PM-SEC-001), whether it is the day, which sitting the line is for, whether the person is
 * an owner or a registered proxy's representative, whether they may be present that way and whom the line brings are
 * all the api's; a refusal is shown in its words, the form kept as filled. The person is a party id until #300.
 */
// Rule: PM-GA-012
// Rule: PM-GA-016
// Rule: PM-SEC-001
export async function recordAttendance(_: AttendanceState, form: FormData): Promise<AttendanceState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "attendeePartyId", "channel"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };
  if (!UUID.test(values.attendeePartyId)) return { refused: "Посочете идентификатора на лицето, което вписвате.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { attendeePartyId: values.attendeePartyId, channel: values.channel },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "впишете лицето в присъствения списък") : unknownOutcome("вписването"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}`);
}

export type VerifyState = { refused?: string; how?: string };

/**
 * Recording that a remote participant was reliably identified, and how (Rule: PM-GA-016) — the api's act; from then
 * on the api counts their presence. Who may record it, and that nobody verifies their own identity, are the api's.
 */
// Rule: PM-GA-016
// Rule: PM-SEC-001
export async function verifyIdentity(_: VerifyState, form: FormData): Promise<VerifyState> {
  const entrance = String(form.get("entrance") ?? ""), assembly = String(form.get("assembly") ?? ""), line = String(form.get("attendance") ?? "");
  const how = String(form.get("how") ?? "").trim();
  if (!UUID.test(entrance) || !UUID.test(assembly) || !UUID.test(line)) return { refused: "Редът от списъка не е посочен.", how };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance/{attendanceId}/identity-verification", {
      params: { path: { entranceId: entrance, assemblyId: assembly, attendanceId: line } },
      body: { how },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "отбележите самоличността като проверена") : unknownOutcome("проверката на самоличността"), how };
  }
  redirect(`/entrance/assembly?entrance=${entrance}&assembly=${assembly}`);
}

export type VoteState = { refused?: string; values?: Record<string, string> };

/**
 * Recording how an owner present at the sitting voted on an item, or correcting it (Rule: PM-VOTE-001, PM-VOTE-014,
 * PM-ORG-005) — the api's act. Whether this person runs the assembly (PM-SEC-001), whether the sitting is open and it
 * is its day, whether the owner is present, which units they vote for and with what weight, and whether the item is
 * closed are all the api's. A second vote for the same owner is a correction: it carries the why the form asks for,
 * and the api records it as a reversal — the web edits nothing. A refusal is shown in its words, the form kept.
 */
// Rule: PM-VOTE-001
// Rule: PM-VOTE-014
// Rule: PM-ORG-005
// Rule: PM-SEC-001
export async function castVote(_: VoteState, form: FormData): Promise<VoteState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "item", "ownerPartyId", "choice", "correction"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly) || !UUID.test(values.item)) return { refused: "Точката от дневния ред не е посочена.", values };
  if (!UUID.test(values.ownerPartyId)) return { refused: "Посочете идентификатора на собственика, чийто глас записвате.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/votes", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly, itemId: values.item } },
      body: { ownerPartyId: values.ownerPartyId, choice: values.choice, correction: values.correction || undefined },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "запишете гласа") : unknownOutcome("записването на гласа"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}`);
}

export type CloseState = { refused?: string; values?: Record<string, string> };

/**
 * Closing an item of the agenda and storing its decision (Rule: PM-VOTE-011, PM-VOTE-014, PM-VOTE-016, PM-SEC-001) —
 * the api's act, with no body: it tallies the votes in effect on ideal parts against the majority the item was bound
 * to, on its own denominator, and stores the decision PROVISIONAL. Whether this person runs the assembly, whether the
 * sitting is open and it is its day, and whether the item is already closed are all the api's. The one thing asked
 * here is that the person confirms it: once closed, no vote on the item is taken. A refusal is shown in its words.
 */
// Rule: PM-VOTE-011
// Rule: PM-VOTE-014
// Rule: PM-VOTE-016
// Rule: PM-SEC-001
export async function closeItem(_: CloseState, form: FormData): Promise<CloseState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "item"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly) || !UUID.test(values.item)) return { refused: "Точката от дневния ред не е посочена.", values };
  if (form.get("final") !== "on") return { refused: "Потвърдете, че приключвате точката — след това по нея не се гласува.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/decision", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly, itemId: values.item } },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "приключите точката") : unknownOutcome("приключването на точката"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}`);
}

export type BallotState = { refused?: string; entered?: boolean; values?: Record<string, string> };

/**
 * Entering an absent owner's ballot on an item tagged for them, from their handwritten-signed declaration (Rule:
 * PM-GA-017, PM-GA-018, PM-SEC-001) — the api's act: the declaration's reference, the SHA-256 of its scan and the day
 * it was received. Whether this person runs the assembly, whether the item is tagged and closed, whether the day is in
 * the 7-day window, whether the owner was represented at the sitting or already has a ballot, and each unit's weight
 * are all the api's. The rows it entered are not shown: who reads ballots is open (#298) — the form says only that it
 * was entered. A refusal is shown in its words, the form kept.
 */
// Rule: PM-GA-017
// Rule: PM-GA-018
// Rule: PM-SEC-001
export async function enterBallot(_: BallotState, form: FormData): Promise<BallotState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "item", "ownerPartyId", "choice", "documentRef", "documentSha256", "receivedOn"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly) || !UUID.test(values.item)) return { refused: "Точката от дневния ред не е посочена.", values };
  if (!UUID.test(values.ownerPartyId)) return { refused: "Посочете идентификатора на собственика, чиято бюлетина вписвате.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/absentee-ballots", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly, itemId: values.item } },
      body: {
        ownerPartyId: values.ownerPartyId, choice: values.choice, signature: "HANDWRITTEN",   // a QES waits for its provider (PM-GA-018)
        documentRef: values.documentRef, documentSha256: values.documentSha256.toLowerCase(), receivedOn: values.receivedOn,
      },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "впишете бюлетината") : unknownOutcome("вписването на бюлетината"), values };
  }
  return { entered: true };
}

export type MergeState = { refused?: string };

/**
 * Merging the absent owners' ballots into an item's tally (Rule: PM-VOTE-013, PM-GA-017, PM-SEC-001) — the api's act,
 * with no body: it tallies the votes in effect and every ballot, and stores a decision superseding the provisional one.
 * Whether this person runs the assembly, whether the item is tagged and closed and whether day 7 is over are all the
 * api's. The one thing asked here is that the person confirms it: the merge is entered once. A refusal is shown in its
 * words.
 */
// Rule: PM-VOTE-013
// Rule: PM-GA-017
// Rule: PM-SEC-001
export async function mergeBallots(_: MergeState, form: FormData): Promise<MergeState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const [entrance, assembly, item] = ["entrance", "assembly", "item"].map(text);
  if (!UUID.test(entrance) || !UUID.test(assembly) || !UUID.test(item)) return { refused: "Точката от дневния ред не е посочена." };
  if (form.get("final") !== "on") return { refused: "Потвърдете сливането — то се вписва веднъж и заменя предварителното решение." };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/absentee-merge", {
      params: { path: { entranceId: entrance, assemblyId: assembly, itemId: item } },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "слеете неприсъствените бюлетини") : unknownOutcome("сливането на бюлетините") };
  }
  redirect(`/entrance/assembly?entrance=${entrance}&assembly=${assembly}`);
}

export type ProtocolState = { refused?: string; values?: Record<string, string> };

/**
 * Entering the day the assembly's protocol was posted (Rule: PM-GA-023, PM-SEC-001) — the api's act: the 30 days to
 * seek annulment of its decisions run from it. Whether this person runs the assembly, whether a sitting opened, whether
 * the day is from the sitting's to today and whether it is already entered are all the api's; so are the windows it
 * opens. The one thing asked here is that the person confirms it: the posting is entered once. A refusal is shown in
 * its words, the form kept.
 */
// Rule: PM-GA-023
// Rule: PM-SEC-001
export async function postProtocol(_: ProtocolState, form: FormData): Promise<ProtocolState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "postedOn"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };
  if (!values.postedOn) return { refused: "Посочете деня, в който протоколът е обявен.", values };
  if (form.get("final") !== "on") return { refused: "Потвърдете деня — обявяването се вписва веднъж.", values };

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/protocol-posting", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { postedOn: values.postedOn },
    }),
  );
  if (!answer?.data) {
    return { refused: answer ? undoneBy(answer, "впишете обявяването на протокола") : unknownOutcome("вписването на обявяването"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}`);
}

export type ProxyState = { refused?: string; values?: Record<string, string> };

/**
 * A proxy appointed for the assembly, or recorded from a written authorisation (Rule: PM-GA-009, PM-GA-010, PM-GA-011,
 * PM-SEC-001) — the api's act, asked with the signed-in person's bearer. With no principal named the signed-in person appoints
 * their own; naming one records the document handed over, and the SHA-256 of the file attached goes with it (the file itself
 * is not kept by this call). Who may, whether the agent is who the kind says, whether the form suffices and how many one person
 * may represent are the api's; a refusal is shown in its words, the fields kept — the file is chosen again.
 */
// Rule: PM-GA-009
// Rule: PM-GA-010
// Rule: PM-GA-011
// Rule: PM-SEC-001
export async function appointProxy(_: ProxyState, form: FormData): Promise<ProxyState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "assembly", "agentKind", "agentPartyId", "form", "scope", "items", "principalPartyId"].map((n) => [n, text(n)]));
  if (!UUID.test(values.entrance) || !UUID.test(values.assembly)) return { refused: "Събранието не е посочено.", values };
  const body = proxyOf({ agentKind: values.agentKind, agent: values.agentPartyId, form: values.form, scope: values.scope, items: values.items, principal: values.principalPartyId });
  if ("refused" in body) return { refused: body.refused, values };
  const document = form.get("authorisation");
  const authorisationHash = document instanceof File && document.size > 0 ? createHash("sha256").update(Buffer.from(await document.arrayBuffer())).digest("hex") : undefined;

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies", {
      params: { path: { entranceId: values.entrance, assemblyId: values.assembly } },
      body: { ...body, ...(authorisationHash && { authorisationHash }) },
    }),
  );
  // no answer: the proxy may be registered — and a principal has one per assembly
  if (!answer) return { refused: unknownOutcome("вписването на пълномощното"), values };
  if (!answer.data) return { refused: proxyRefused(answer), values };
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${values.assembly}`);
}
