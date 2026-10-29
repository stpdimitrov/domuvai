"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { unknownOutcome } from "@/lib/refusal";
import { entranceOf, entranceRefused, partyOf, partyRefused } from "@/lib/registry";

/** What a form here holds after a submission: the API's refusal in its words, the form as typed, and what the API did. */
export type RegistryState<T = never> = { refused?: string; values?: Record<string, string>; done?: T };

const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
const fields = (form: FormData, names: string[]) => Object.fromEntries(names.map((n) => [n, text(form, n)]));

/** An entrance registered (WEB-50). Rule: PM-ORG-001 — a new building's address or the building it joins; only the administrator may (Rule: PM-SEC-001): anybody else is shown the API's refusal, the form kept. */
// Rule: PM-ORG-001, PM-SEC-001
export async function registerEntrance(_: RegistryState<{ entranceId: string; condominiumId: string }>, form: FormData): Promise<RegistryState<{ entranceId: string; condominiumId: string }>> {
  const values = fields(form, ["label", "managementForm", "address", "condominiumId"]);
  const body = entranceOf({ label: values.label, managementForm: values.managementForm, address: values.address, condominiumId: values.condominiumId });
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/registry/entrances", { body }));
  // no answer: the entrance may be registered — sent again it would be registered twice
  if (!answer) return { values, refused: unknownOutcome("регистрирането на входа") };
  if (!answer.data) return { values, refused: entranceRefused(answer) };
  return { done: answer.data };
}

/** A party registered (WEB-50). Rule: PM-BOOK-011 — the identity number is sent and not kept: it is left out of the values the form holds after an answer. Rule: PM-SEC-001 — who may is the API's. */
// Rule: PM-BOOK-011, PM-SEC-001
export async function registerParty(_: RegistryState<{ partyId: string; fullName: string }>, form: FormData): Promise<RegistryState<{ partyId: string; fullName: string }>> {
  const typed = fields(form, ["fullName", "idType", "idValue"]);
  const { idValue: _kept, ...values } = typed;
  const body = partyOf({ fullName: typed.fullName, idType: typed.idType, idValue: typed.idValue });
  if ("refused" in body) return { values, refused: body.refused };
  const answer = await reach(() => api.POST("/api/registry/parties", { body }));
  if (!answer) return { values, refused: unknownOutcome("регистрирането на лицето") };
  if (!answer.data) return { values, refused: partyRefused(answer) };
  return { done: { partyId: answer.data.partyId, fullName: body.fullName } };
}
