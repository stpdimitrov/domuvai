import "server-only";
import { api } from "@/lib/api/client";
import { inTurn, reach } from "@/lib/console";
import { unansweredBy } from "@/lib/refusal";

/** A decision a tariff line may cite: its id, and what the person sees to choose it. */
export type Passed = { id: string; label: string };

/**
 * Rule: PM-FEE-012 — the decisions this entrance's assembly passed, as the assembly API stores them: every assembly,
 * each agenda item, its decision where one is stored (an item not closed is a 404) and its outcome PASSED. Read again
 * on every step, so the form never vouches for a decision. A read the API refuses is said in its words.
 */
// Rule: PM-FEE-012, PM-SEC-001
export async function passedDecisions(entranceId: string): Promise<{ passed: Passed[] } | { refused: string }> {
  const listed = await reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies", { params: { path: { entranceId } } }));
  if (!listed?.data) return { refused: unansweredBy(listed, "общите събрания на входа") };
  const assemblies = await inTurn(listed.data, async (a) => ({
    summary: a,
    read: await reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}", { params: { path: { entranceId, assemblyId: a.id } } })),
  }));
  const items = assemblies.flatMap(({ summary, read }) => (read?.data?.agenda ?? []).map((item) => ({ summary, item })));
  const unread = assemblies.find(({ read }) => !read?.data);
  if (unread) return { refused: unansweredBy(unread.read, "общото събрание") };
  const decided = await inTurn(items, async ({ summary, item }) => ({
    summary, item,
    read: await reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/decision", {
      params: { path: { entranceId, assemblyId: summary.id, itemId: item.id } },
    })),
  }));
  const failed = decided.find(({ read }) => !read || (!read.data && read.response.status !== 404));
  if (failed) return { refused: unansweredBy(failed.read, "решенията на общото събрание") };
  return {
    passed: decided
      .filter(({ read }) => read?.data?.outcome === "PASSED")
      .map(({ summary, item, read }) => ({
        id: read!.data!.id,
        label: `ОС ${summary.scheduledAt.slice(0, 10)}, т. ${item.ordinal}: ${item.text} — ${read!.data!.result} (${read!.data!.status})`,
      })),
  };
}
