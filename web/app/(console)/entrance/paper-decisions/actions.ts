"use server";

import { randomUUID } from "node:crypto";
import { api, type Schemas } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { FIELDS, recordOf, recordRefused, type PaperDecisionInput } from "@/lib/paperDecision";
import { unknownOutcome } from "@/lib/refusal";

/** What the form holds after a submission: the API's refusal in its words, the form as typed, and the record the API answered with. */
export type PaperDecisionState = { refused?: string; values?: Partial<PaperDecisionInput>; done?: Schemas["PaperDecisionView"]; key?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");

/**
 * A decision recorded from its paper protocol (WEB-54). Rule: PM-GA-022, PM-DOC-007 — all five fields are the API's to
 * require, the paper's location and custodian among them. Rule: PM-SEC-001 — only the deployment's administrator may:
 * anybody else is shown the API's refusal, the form kept. The record is shown from the POST's answer: the list on the
 * page is refused to the administrator.
 * Rule: PM-SYS-014 — the record carries an Idempotency-Key minted once per form: a refusal recorded nothing, so the form
 * comes back under a new key; an answer lost, or a 5xx, keeps the key, so the same form sent again is the same record,
 * never a second. A record made renews it, so the next one typed is another. A page reloaded mints a new key.
 */
// Rule: PM-GA-022, PM-DOC-007, PM-SEC-001, PM-SYS-014
export async function record(_: PaperDecisionState, form: FormData): Promise<PaperDecisionState> {
  const entranceId = text(form, "entrance");
  const values = Object.fromEntries(FIELDS.map((name) => [name, text(form, name)])) as PaperDecisionInput;
  const key = text(form, "key");
  if (!UUID.test(entranceId)) return { values, refused: "Входът не е посочен.", key: UUID.test(key) ? key : randomUUID() };
  if (!UUID.test(key)) return { values, refused: "Формата е изтекла — опитайте отново.", key: randomUUID() };
  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/paper-decisions", { params: { path: { entranceId }, header: { "Idempotency-Key": key } }, body: recordOf(values) }),
  );
  // no answer: the decision may be recorded — and a record is never changed; the same form, sent again, carries the same key
  if (!answer) return { values, refused: unknownOutcome("вписването на решението"), key };
  if (!answer.data) return { values, refused: recordRefused(answer), key: answer.response.status < 500 ? randomUUID() : key };
  return { done: answer.data, key: randomUUID() };
}
