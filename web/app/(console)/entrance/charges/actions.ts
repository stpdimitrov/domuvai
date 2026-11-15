"use server";

import { api, type Schemas } from "@/lib/api/client";
import { asPreviewed, basisKey, basisOf, type BasisInput } from "@/lib/basis";
import { reach } from "@/lib/console";
import { undoneBy, unknownOutcome } from "@/lib/refusal";
import { passedDecisions } from "./decisions";

/** What the issue form holds: the basis as typed, the API's preview of it, and the issued run or the API's refusal. */
export type IssueState = {
  refused?: string;
  input?: BasisInput;
  preview?: Schemas["ChargeRunResponse"];
  previewed?: string;                       // the basis the preview was computed on — only that one may be issued
  issued?: Schemas["ChargeRunIssued"];
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A charge run previewed, then issued, on a basis the person states (WEB-38). Rule: PM-FEE-012 — every line cites a
 * decision this entrance's assembly passed, read from the assembly API on each step; the demo basis is never issued.
 * Rule: PM-FEE-015 — an issued run is never altered: that is the API's (a period already billed is its 409, shown in its
 * words). The form issues only the basis the person previewed — a changed one is previewed again. Rule: PM-SEC-001 — who may preview or issue is the
 * API's (the manager or the board, G-3); a refusal is shown in its words, the form kept. Every figure is the API's.
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export async function issueRun(_: IssueState, form: FormData): Promise<IssueState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string) : "");
  const [entranceId, period, step] = [text("entrance"), text("period"), text("step")];
  const input: BasisInput = {
    multiple: text("multiple"),
    lines: Array.from({ length: [...form.keys()].filter((k) => k.startsWith("stream-")).length }, (_, j) => ({
      stream: text(`stream-${j}`), key: text(`key-${j}`), per: text(`per-${j}`), amount: text(`amount-${j}`), decision: text(`decision-${j}`),
    })),
  };
  if (!UUID.test(entranceId) || !/^\d{4}-\d{2}$/.test(period)) return { input, refused: "Входът или периодът не са посочени." };
  const decisions = await passedDecisions(entranceId);
  if ("refused" in decisions) return { input, refused: decisions.refused };
  const basis = basisOf(input, new Set(decisions.passed.map((d) => d.id)));
  if ("refused" in basis) return { input, refused: basis.refused };

  const body = { period, legalDate: `${period}-01`, ...basis };
  const path = { entranceId };
  if (step !== "issue") {
    const answer = await reach(() => api.POST("/api/money/entrances/{entranceId}/charge-runs/preview", { params: { path }, body }));
    if (!answer?.data) return { input, refused: answer ? undoneBy(answer, "изчислите начисленията") : "Системата не отговори — нищо не е записано. Опитайте отново." };
    return { input, preview: answer.data, previewed: basisKey(period, basis) };
  }
  if (!asPreviewed(text("previewed"), period, basis)) return { input, refused: "Базисът е променен след прегледа — прегледайте го отново, преди да го издадете." };
  const answer = await reach(() => api.POST("/api/money/entrances/{entranceId}/charge-runs", { params: { path }, body }));
  // a run may be stored before its answer is lost: the person is told to look, not to issue again
  if (!answer?.data) return { input, refused: answer ? undoneBy(answer, "издадете начисленията") : unknownOutcome("издаването на начисленията") };
  return { input, issued: answer.data };
}
