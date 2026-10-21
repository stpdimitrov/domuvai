"use server";

import { redirect } from "next/navigation";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { undoneBy, unknownOutcome } from "@/lib/refusal";
import { sofiaInstant } from "@/lib/sofia";

export type ConveneState = { refused?: string; values?: Record<string, string> };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Convening an assembly (Rule: PM-GA-002, PM-GA-005) — the api's act, asked with the signed-in person's bearer. The
 * page decides nothing of the law: whether this person holds the office they convene as today, whether the urgency
 * is justified, whether the time leaves room for the notice — all the api's, and a refusal is shown in its words,
 * the form kept as filled. The one thing read here is the time typed, as Sofia's clock shows it.
 */
export async function convene(_: ConveneState, form: FormData): Promise<ConveneState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const values = Object.fromEntries(["entrance", "convenedAs", "scheduledAt", "place", "mode", "urgencyReason"].map((n) => [n, text(n)]));
  const urgent = form.get("urgent") === "on";
  if (urgent) values.urgent = "on";
  if (!UUID.test(values.entrance)) return { refused: "Входът не е посочен.", values };
  const scheduledAt = sofiaInstant(values.scheduledAt);
  if (!scheduledAt) {
    return { refused: "Посочете дата и час, каквито ги показва часовникът в София — този час не съществува или не е попълнен.", values };
  }

  const answer = await reach(() =>
    api.POST("/api/assembly/entrances/{entranceId}/assemblies", {
      params: { path: { entranceId: values.entrance } },
      body: {
        convenedAs: values.convenedAs,
        scheduledAt,
        place: values.place,
        mode: values.mode,
        urgent,
        // sent whenever typed: a reason without urgency is the api's to refuse (PM-GA-005), not the page's to drop
        ...(values.urgencyReason && { urgencyReason: values.urgencyReason }),
      },
    }),
  );
  if (!answer?.data) {
    // no answer at all (the backend down, or the call given up after its time): the api may have convened it already
    return { refused: answer ? undoneBy(answer, "свикате общо събрание") : unknownOutcome("свикването"), values };
  }
  redirect(`/entrance/assembly?entrance=${values.entrance}&assembly=${answer.data.id}`);
}
