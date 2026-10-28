"use server";

import { randomUUID } from "node:crypto";
import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { disbursementRefused, signOffOf, type SignOffInput } from "@/lib/disbursement";
import { passedDecisions } from "../../charges/decisions";

/** What a disbursement form holds after a refusal: the form as typed, and the key it was sent with. */
export type DisbursementState = { refused?: string; input?: Record<string, string>; key?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LOST = "Системата не отговори — не е ясно дали е записано. Изпратете формата отново без промяна: със същия ключ не се записва втори път.";

const typedIn = (form: FormData, names: string[]) =>
  Object.fromEntries(names.map((n) => [n, typeof form.get(n) === "string" ? (form.get(n) as string).trim() : ""]));

/**
 * The signed-in person's own party, read from the API on this write (owner, 2026-10-28, "Confirm Q1"): never a field
 * anyone types or picks. `undefined` when the sign-in is tied to no party; `null` when the API did not say.
 */
// Rule: PM-FUND-007, PM-SEC-001
async function myParty(): Promise<string | undefined | null> {
  const me = await reach(() => api.GET("/api/identity/me"));
  return me?.data ? me.data.partyId ?? undefined : null;
}

/**
 * The API's answer to a write: the disbursement as it now stands takes the person back to the list; a refusal recorded
 * nothing, so the form comes back under a new key; an answer lost, or a 5xx that may have recorded it, keeps the key,
 * so the same form sent again is the same write, never a second one.
 */
// Rule: PM-FUND-007, PM-SEC-001
function settled(
  answer: { data?: { status: string }; error?: unknown; response: { status: number } } | null, entranceId: string, state: DisbursementState, act: string,
): DisbursementState {
  if (answer?.data) redirect(`/entrance/fund/disbursements?entrance=${entranceId}&done=${answer.data.status.toLowerCase()}`);
  if (!answer || answer.response.status >= 500) return { ...state, refused: LOST };
  return { ...state, key: randomUUID(), refused: disbursementRefused(answer, act) };
}

/**
 * A disbursement signed off (WEB-42). Rule: PM-FUND-006, PM-FUND-007, PM-FUND-008 — the body is `signOffOf`'s, the
 * decisions it may rest on read again from the assembly API on this write. Rule: PM-SEC-001 — who may sign is the API's.
 */
// Rule: PM-FUND-006, PM-FUND-007, PM-FUND-008, PM-SEC-001
export async function signOff(_: DisbursementState, form: FormData): Promise<DisbursementState> {
  const { entrance: entranceId, key, ...input } = typedIn(form, ["entrance", "key", "amount", "purpose", "basis", "decision", "measure", "justification"]);
  if (!UUID.test(entranceId) || !UUID.test(key)) return { input, key: randomUUID(), refused: "Входът не е посочен." };
  const [party, decisions] = await Promise.all([myParty(), passedDecisions(entranceId)]);
  if (party === null) return { input, key, refused: "Системата не каза кой сте — не е изпратено нищо. Опитайте отново." };
  if ("refused" in decisions && input.basis === "DECISION") return { input, key, refused: decisions.refused };
  const body = signOffOf(input as SignOffInput, party, "passed" in decisions ? decisions.passed.map((d) => d.id) : []);
  if ("refused" in body) return { input, key: randomUUID(), refused: body.refused };
  const answer = await reach(() =>
    api.POST("/api/money/entrances/{entranceId}/fund/disbursements", { params: { path: { entranceId }, header: { "Idempotency-Key": key } }, body }),
  );
  return settled(answer, entranceId, { input, key }, "подпишете разход от фонда");
}
