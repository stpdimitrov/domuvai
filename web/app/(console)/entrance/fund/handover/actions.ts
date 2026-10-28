"use server";

import { randomUUID } from "node:crypto";
import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { handoverOf, handoverRefused } from "@/lib/handover";

/** What the issue form holds after a refusal: the form as typed, and the key it was sent with. */
export type HandoverState = { refused?: string; input?: Record<string, string>; key?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LOST = "Системата не отговори — не е ясно дали отчетът е издаден. Изпратете формата отново без промяна: със същия ключ не се издава втори път.";

/**
 * A handover statement issued (WEB-43). Rule: PM-FUND-010 — the body is `handoverOf`'s; the API builds the statement from
 * its ledger beside the bank balance typed, and the person is taken to it, read back. Rule: PM-SEC-001 — who may issue
 * is the API's (the manager or the board): a refusal is shown in its words, the form kept and the key renewed, since a
 * refusal recorded nothing; an answer lost, or a 5xx that may have recorded it, keeps the key, so the same form sent again
 * is the same write. The issuer is the sign-in: the body names no actor.
 */
// Rule: PM-FUND-010, PM-SEC-001
export async function issue(_: HandoverState, form: FormData): Promise<HandoverState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const [entranceId, key] = [text("entrance"), text("key")];
  const input = { handoverOn: text("handoverOn"), from: text("from"), outgoing: text("outgoing"), incoming: text("incoming"), bankBalance: text("bankBalance") };
  if (!UUID.test(entranceId) || !UUID.test(key)) return { input, key: randomUUID(), refused: "Входът не е посочен." };
  const body = handoverOf(input);
  if ("refused" in body) return { input, key, refused: body.refused };
  const answer = await reach(() =>
    api.POST("/api/money/entrances/{entranceId}/fund/handover-statements", { params: { path: { entranceId }, header: { "Idempotency-Key": key } }, body }),
  );
  if (answer?.data) redirect(`/entrance/fund/handover?entrance=${entranceId}&statement=${answer.data.id}`);
  if (!answer || answer.response.status >= 500) return { input, key, refused: LOST };
  return { input, key: randomUUID(), refused: handoverRefused(answer) };
}
