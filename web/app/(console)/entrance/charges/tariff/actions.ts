"use server";

import { redirect } from "next/navigation";
import { api, type Schemas } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { undoneBy, unknownOutcome } from "@/lib/refusal";
import { tariffOf, type TariffBody } from "@/lib/tariff";
import { passedDecisions } from "../decisions";

/** What the record form holds after a refusal: the form as typed, and the API's words. */
export type TariffState = { refused?: string; input?: Record<string, string> };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A tariff version recorded (WEB-61). Rule: PM-FEE-012 — the body is `tariffOf`'s, typed as the API's RecordTariffVersion,
 * the decision chosen from the entrance's passed ones read again on this write; nothing is sent without one. Rule:
 * PM-FEE-015 — a version is never altered: a changed tariff is a new one, and a month that already begins a version is the
 * API's 409, shown in its words with the form kept. Rule: PM-SEC-001 — who may record is the API's (the manager or the
 * board); a refusal is shown in its words, with no role check here.
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export async function recordTariff(_: TariffState, form: FormData): Promise<TariffState> {
  const input: Record<string, string> = {};
  for (const [name, value] of form.entries()) if (typeof value === "string") input[name] = value;
  const entranceId = (input.entrance ?? "").trim();
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  const decisions = await passedDecisions(entranceId);
  if ("refused" in decisions) return { input, refused: decisions.refused };
  const body: TariffBody | { refused: string } = tariffOf(input, decisions.passed.map((d) => d.id));
  if ("refused" in body) return { input, refused: body.refused };
  const answer = await reach(() => api.POST("/api/money/entrances/{entranceId}/tariff-versions", { params: { path: { entranceId } }, body }));
  if (answer?.data) redirect(`/entrance/charges/tariff?entrance=${entranceId}&done=${(answer.data as Schemas["TariffVersionView"]).id}`);
  return { input, refused: answer ? undoneBy(answer, "запишете тарифна версия") : unknownOutcome("записването на тарифната версия") };
}
