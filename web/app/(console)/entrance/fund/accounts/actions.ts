"use server";

import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { accountOf, registrationRefused, type AccountInput } from "@/lib/fundAccount";
import { unknownOutcome } from "@/lib/refusal";

/** What the account form holds after a refusal: the account as typed. */
export type AccountState = { refused?: string; input?: AccountInput };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * An entrance's bank account registered (WEB-41). Rule: PM-FUND-004 — the account in its holder's name, one per
 * purpose; the API refuses a second one, and an IBAN registered anywhere (PM-FUND-005). Rule: PM-SEC-001 — who may
 * register one is the API's (the manager or the board, G-3); a refusal is shown in its words, the form kept. The person
 * is taken to the list as the API reads it back. Only the account's identity is stored: nothing here moves money (ADR-007).
 */
// Rule: PM-FUND-004, PM-SEC-001
export async function registerAccount(_: AccountState, form: FormData): Promise<AccountState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string) : "");
  const entranceId = text("entrance").trim();
  const input: AccountInput = { purpose: text("purpose"), iban: text("iban"), holderName: text("holderName"), holderKind: text("holderKind") };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  const body = accountOf(input);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() => api.POST("/api/money/entrances/{entranceId}/fund-accounts", { params: { path: { entranceId } }, body }));
  // no answer: the account may be registered — the list says; sent again, the API refuses the IBAN it already holds
  if (!answer) return { input, refused: unknownOutcome("регистрирането на сметката") };
  if (!answer.data) return { input, refused: registrationRefused(answer) };
  redirect(`/entrance/fund/accounts?entrance=${entranceId}&registered=${answer.data.fundAccountId}`);
}
