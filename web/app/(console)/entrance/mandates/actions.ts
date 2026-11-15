"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { endingOf, endRefused, mandateOf, recordRefused, tieOf, tieRefused, untieOf, untieRefused } from "@/lib/mandate";
import { unknownOutcome } from "@/lib/refusal";

/** What a form here holds after a submission: the API's refusal in its words, the form as typed, and what the API did. */
export type MandateState<T = never> = { refused?: string; values?: Record<string, string>; done?: T };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
const fields = (form: FormData, names: string[]) => Object.fromEntries(names.map((n) => [n, text(form, n)]));

/**
 * A mandate recorded from its protocol (WEB-49). Rule: PM-GOV-004 — its term, and the mandates it succeeds, are the API's;
 * only the deployment's administrator may (Rule: PM-SEC-001): anybody else is shown the API's refusal, the form kept.
 */
// Rule: PM-GOV-004, PM-SEC-001
export async function record(_: MandateState<{ mandateId: string; succeeded: string[] }>, form: FormData): Promise<MandateState<{ mandateId: string; succeeded: string[] }>> {
  const values = fields(form, ["entrance", "body", "party", "protocolRef", "validFrom", "validTo"]);
  if (!UUID.test(values.entrance)) return { values, refused: "Входът не е посочен." };
  const body = mandateOf({ body: values.body, party: values.party, protocolRef: values.protocolRef, validFrom: values.validFrom, validTo: values.validTo });
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/identity/entrances/{entranceId}/mandates", { params: { path: { entranceId: values.entrance } }, body }));
  // no answer: the mandate may be recorded — sent again it would be recorded twice
  if (!answer) return { values, refused: unknownOutcome("вписването на мандата") };
  if (!answer.data) return { values, refused: recordRefused(answer) };
  return { done: { mandateId: answer.data.mandateId, succeeded: answer.data.succeeded } };
}

/** A mandate's end recorded (WEB-49): the day it ended, once, and the protocol — the API's rules (Rule: PM-GOV-004, PM-SEC-001) in its words. */
// Rule: PM-GOV-004, PM-SEC-001
export async function end(_: MandateState<{ mandateId: string; on: string }>, form: FormData): Promise<MandateState<{ mandateId: string; on: string }>> {
  const values = fields(form, ["entrance", "mandate", "on", "protocolRef"]);
  if (!UUID.test(values.entrance)) return { values, refused: "Входът не е посочен." };
  const ending = endingOf({ mandate: values.mandate, on: values.on, protocolRef: values.protocolRef });
  if ("refused" in ending) return { values, refused: ending.refused };
  const answer = await reach(() => api.POST("/api/identity/entrances/{entranceId}/mandates/{mandateId}/end", {
    params: { path: { entranceId: values.entrance, mandateId: ending.mandateId } }, body: { on: ending.on, protocolRef: ending.protocolRef },
  }));
  if (!answer) return { values, refused: unknownOutcome("приключването на мандата") };
  if (!answer.data) return { values, refused: endRefused(answer) };
  return { done: { mandateId: answer.data.mandateId, on: answer.data.on } };
}

/**
 * A login tied to a party (WEB-49b). Rule: PM-SEC-001 — only the deployment's administrator may; a login or a party already
 * tied, and the administrator's own login, are the API's refusals (409, 400) in its words, the form kept.
 */
// Rule: PM-SEC-001
export async function tie(_: MandateState<{ partyId: string; subject: string }>, form: FormData): Promise<MandateState<{ partyId: string; subject: string }>> {
  const values = fields(form, ["party", "subject"]);
  const body = tieOf({ party: values.party, subject: values.subject });
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/identity/logins", { body }));
  // no answer: the login may be tied — sent again it would be refused as already tied
  if (!answer) return { values, refused: unknownOutcome("свързването на входа") };
  if (!answer.data) return { values, refused: tieRefused(answer) };
  return { done: { partyId: answer.data.partyId, subject: answer.data.subject } };
}

/** A login untied (WEB-49b): one tied to none answers "nothing done" (200, `untied: false`) — said so, not said to be undone. Rule: PM-SEC-001. */
// Rule: PM-SEC-001
export async function untie(_: MandateState<{ untied: boolean; subject: string }>, form: FormData): Promise<MandateState<{ untied: boolean; subject: string }>> {
  const values = fields(form, ["issuer", "subject"]);
  const body = untieOf({ issuer: values.issuer, subject: values.subject });
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/identity/logins/untie", { body }));
  if (!answer) return { values, refused: unknownOutcome("развързването на входа") };
  if (!answer.data) return { values, refused: untieRefused(answer) };
  return { done: { untied: answer.data.untied, subject: body.subject } };
}
