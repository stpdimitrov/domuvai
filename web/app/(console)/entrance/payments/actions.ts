"use server";

import { randomUUID } from "node:crypto";
import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { paymentOf, type PaymentInput } from "@/lib/payment";
import { undoneBy } from "@/lib/refusal";

/** What the payment form holds after a refusal: the payment as typed, and the key it was sent with. */
export type PaymentState = { refused?: string; input?: PaymentInput; key?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A payment received, recorded (WEB-39). Rule: PM-DEBT-008 — the API allocates it, oldest debt first unless the payer
 * designated one, and stores the rule applied; the person is taken to the payment's allocation as the API reads it
 * back. Rule: PM-SEC-001 — who may record one is the API's (the manager or the board, G-3); a refusal is shown in its
 * words, the form kept. The Idempotency-Key is minted once per form. A refusal recorded nothing, so the form comes back
 * under a new key; an answer lost keeps the key, so the same form sent again is the same payment, never a second one. Nothing here moves money (ADR-007): it records what was received.
 */
// Rule: PM-DEBT-008, PM-SEC-001
export async function recordPayment(_: PaymentState, form: FormData): Promise<PaymentState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const [entranceId, key] = [text("entrance"), text("key")];
  const input: PaymentInput = {
    unit: text("unit"), amount: text("amount"), valueDate: text("valueDate"), receivedInto: text("receivedInto"), designated: text("designated"),
  };
  if (!UUID.test(entranceId) || !UUID.test(key)) return { input, key: randomUUID(), refused: "Входът не е посочен." };
  const body = paymentOf(input);
  if ("refused" in body) return { input, key: randomUUID(), refused: body.refused };

  const answer = await reach(() =>
    api.POST("/api/money/entrances/{entranceId}/payments", { params: { path: { entranceId }, header: { "Idempotency-Key": key } }, body }),
  );
  if (!answer?.data) {
    // the payment may be stored before its answer is lost: the same form, sent again unchanged, carries the same key
    const lost = "Системата не отговори — не е ясно дали плащането е записано. Изпратете формата отново без промяна: със същия ключ то не се записва втори път.";
    return answer ? { input, key: randomUUID(), refused: undoneBy(answer, "запишете плащането") } : { input, key, refused: lost };
  }
  redirect(`/entrance/payments?entrance=${entranceId}&payment=${answer.data.paymentId}`);
}
