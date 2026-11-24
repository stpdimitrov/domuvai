import "server-only";
import { api, type Schemas } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { unansweredBy } from "@/lib/refusal";

/**
 * Rule: PM-FEE-012 — the tariff version in force for a period, as the API serves it (`?period=` answers the one in force
 * then, or none). The screen picks no version of its own: a run is billed on this one and no other. Read again on every
 * step, so the form never vouches for a version. Rule: PM-SEC-001 — who may read it is the API's; a refusal is its words.
 */
// Rule: PM-FEE-012, PM-SEC-001
export async function versionInForce(entranceId: string, period: string): Promise<{ version: Schemas["TariffVersionView"] } | { none: true } | { refused: string }> {
  const answer = await reach(() => api.GET("/api/money/entrances/{entranceId}/tariff-versions", { params: { path: { entranceId }, query: { period } } }));
  if (!answer?.data) return { refused: unansweredBy(answer, "тарифата в сила") };
  const [version] = answer.data;
  return version ? { version } : { none: true };
}

export const NONE_IN_FORCE = (month: string) =>
  `Няма тарифна версия в сила за ${month} — без решение на общото събрание не се начислява (PM-FEE-012). Нищо не се изчислява и не се издава.`;
