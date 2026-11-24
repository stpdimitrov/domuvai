"use server";

import { api, type Schemas } from "@/lib/api/client";
import { asPreviewed, runKey, runRequest } from "@/lib/basis";
import { monthName, reach } from "@/lib/console";
import { undoneBy, unknownOutcome } from "@/lib/refusal";
import { NONE_IN_FORCE, versionInForce } from "./version";

/** What the issue form holds: the multiple as given, the API's preview, and the issued run or the API's refusal. */
export type IssueState = {
  refused?: string;
  multiple?: string;
  preview?: Schemas["ChargeRunResponse"];
  previewed?: string;                       // the version and run the preview was computed on — only that one may be issued
  issued?: Schemas["ChargeRunIssued"];
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A charge run previewed, then issued, on the tariff version in force (WEB-60). Rule: PM-FEE-012 — the API bills that
 * version and ignores any lines, so none are posted: the body holds only the run's own fields. A period with no version
 * in force is not previewed or issued. Rule: PM-FEE-015 — an issued run is never altered: that is the API's (a period
 * already billed is its 409, shown in its words). Only the run previewed on a version is issued on it; a version that
 * changed in between is previewed again. Rule: PM-SEC-001 — who may preview or issue is the API's (the manager or the
 * board); a refusal is shown in its words, the form kept. Every figure is the API's.
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export async function issueRun(_: IssueState, form: FormData): Promise<IssueState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string) : "");
  const [entranceId, period, step, multiple] = [text("entrance"), text("period"), text("step"), text("multiple")];
  if (!UUID.test(entranceId) || !/^\d{4}-\d{2}$/.test(period)) return { multiple, refused: "Входът или периодът не са посочени." };
  const body = runRequest(period, multiple);
  if ("refused" in body) return { multiple, refused: body.refused };
  const read = await versionInForce(entranceId, period);
  if ("refused" in read) return { multiple, refused: read.refused };
  if ("none" in read) return { multiple, refused: NONE_IN_FORCE(monthName(period)) };

  const path = { entranceId };
  const key = runKey(read.version.id, body);
  if (step !== "issue") {
    const answer = await reach(() => api.POST("/api/money/entrances/{entranceId}/charge-runs/preview", { params: { path }, body }));
    if (!answer?.data) return { multiple, refused: answer ? undoneBy(answer, "изчислите начисленията") : "Системата не отговори — нищо не е записано. Опитайте отново." };
    return { multiple, preview: answer.data, previewed: key };
  }
  if (!asPreviewed(text("previewed"), read.version.id, body)) return { multiple, refused: "Тарифата или кратността са променени след прегледа — прегледайте отново, преди да издадете." };
  const answer = await reach(() => api.POST("/api/money/entrances/{entranceId}/charge-runs", { params: { path }, body }));
  // a run may be stored before its answer is lost: the person is told to look, not to issue again
  if (!answer?.data) return { multiple, refused: answer ? undoneBy(answer, "издадете начисленията") : unknownOutcome("издаването на начисленията") };
  return { multiple, issued: answer.data };
}
