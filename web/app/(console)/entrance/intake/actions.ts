"use server";

import { api, type Schemas } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { minorOf } from "@/lib/money";
import { undoneBy, unknownOutcome } from "@/lib/refusal";

type Field = Schemas["ProposedMapping"]["mapping"][string];
type Line = Schemas["FeeSheetDryRunRequest"]["lines"][number];

/** The tariff a dry-run is asked with, as the person typed it — kept so the form comes back as it was filled. */
export type RunInput = { period: string; legalDate: string; multiplier: string; lines: { stream: string; key: string; item: string; per: string; amount: string; decision: string }[] };

/**
 * What the page holds between the two steps: the sheet as read, the API's proposal for it and, once confirmed, the
 * mapping the person chose. Nothing is stored — the sheet travels in the form, as no API keeps it before the dry-run.
 */
export type ProfileState = {
  refused?: string;
  sheet?: { name: string; carried: string };   // the csv as base64, so the form returns its bytes unchanged
  proposal?: Schemas["ProposedMapping"];
  columns?: string[];
  confirmed?: Record<string, Field>;
  run?: RunInput;
  report?: Schemas["DryRunReport"];
  recorded?: string;                             // the import's id, as the API recorded it
  recordedNow?: boolean;                         // this answer is the record's, not a later dry-run's
  committed?: Schemas["CommitResult"];           // the commit's answer: COMMITTING, the rows, what a person must record
};


// Rule: PM-SEC-001
/** One action for the page's two forms, so the upload's answer is the state the mapping form is drawn from. */
export async function intake(previous: ProfileState, form: FormData): Promise<ProfileState> {
  const step = form.get("step");
  if (step === "dry-run" || step === "record" || step === "commit") return runSheet(previous, form, step);
  return step === "confirm" ? confirmMapping(previous, form) : profileSheet(previous, form);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Profiling a fee sheet (ADR-012 steps 1–2; Rule: PM-SEC-001) — the API's, asked with the signed-in person's bearer:
 * which columns it can place on a domain field, which it cannot, and which required field no column carries. Who may
 * ask is the API's (the manager or the board, G-1); a refusal is shown in its words. The page reads the file as UTF-8
 * text and drops a leading byte-order mark, nothing more: the columns are the API's to find.
 */
// Rule: PM-SEC-001
async function profileSheet(_: ProfileState, form: FormData): Promise<ProfileState> {
  const entrance = typeof form.get("entrance") === "string" ? (form.get("entrance") as string) : "";
  if (!UUID.test(entrance)) return { refused: "Входът не е посочен." };
  const file = form.get("sheet");
  if (!(file instanceof File) || file.size === 0) return { refused: "Изберете таблицата (CSV) с таксите на входа." };
  const csv = (await file.text()).replace(/^\uFEFF/, "");
  return propose(entrance, csv, { name: file.name, carried: Buffer.from(csv, "utf8").toString("base64") }, "профилирате таблицата");
}

/**
 * Confirming the mapping (ADR-012 step 3, HUMAN_RELEASE) — the person's choice, column by column, kept with the sheet
 * for the dry-run that follows. The proposal shown beside it is asked of the API again for the same sheet, so what
 * the page says the API proposed is the API's answer, never one the form carried back. The page does not judge the
 * choice — a field chosen twice or a required one left out is the dry-run's to refuse.
 */
// Rule: PM-SEC-001
async function confirmMapping(_: ProfileState, form: FormData): Promise<ProfileState> {
  const got = carriedBy(form);
  if ("refused" in got) return got;
  const { entrance, csv, sheet, columns, confirmed } = got;
  const state = await propose(entrance, csv, sheet, "потвърдите разпределението");
  // refused: the sheet and the choices are kept, so the form comes back as it was filled
  return state.refused ? { ...state, sheet, columns, confirmed } : { ...state, confirmed };
}

const textOf = (form: FormData) => (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string) : "");

/** The sheet and the person's choice per column, as the form carried them. */
function carriedBy(form: FormData) {
  const text = textOf(form);
  const entrance = text("entrance");
  if (!UUID.test(entrance)) return { refused: "Входът не е посочен." };
  // a form posts its line breaks as CRLF; the sheet travels as base64, so the API reads the bytes it profiled
  const carried = text("csv");
  const sheet = { name: text("sheetName"), carried };
  const csv = Buffer.from(carried, "base64").toString("utf8");
  if (!csv) return { refused: "Таблицата не е получена — изберете я отново." };
  const columns = form.getAll("column").filter((c): c is string => typeof c === "string");
  const confirmed: Record<string, Field> = {};
  columns.forEach((column, i) => {
    const field = text(`field-${i}`);
    if (field) confirmed[column] = field as Field;     // the API checks it at the dry-run, not the page
  });
  return { entrance, csv, sheet, columns, confirmed };
}

/**
 * The dry-run, or the import recorded (ADR-012 step 4; Rule: PM-FEE-014, PM-FEE-012, PM-SEC-001) — the confirmed
 * mapping and the tariff the firm's fees were built on, each line with the assembly's decision behind it, sent to the
 * API. Its verdict is shown as it answered: which bills it reproduced to the cent and which it did not is the API's,
 * never compared here. A dry-run stores nothing; a record stores the verdict and answers the import's id. Who may do
 * either is the API's (the manager or the board, G-1); a refusal is shown in its words, the form kept.
 */
// Rule: PM-SEC-001, PM-FEE-014, PM-FEE-012
async function runSheet(_: ProfileState, form: FormData, step: "dry-run" | "record" | "commit"): Promise<ProfileState> {
  const got = carriedBy(form);
  if ("refused" in got) return got;
  const { entrance, csv, sheet, columns, confirmed } = got;
  const text = textOf(form);
  const run: RunInput = {
    period: text("period"), legalDate: text("legalDate"), multiplier: text("multiplier"),
    lines: Array.from({ length: [...form.keys()].filter((k) => k.startsWith("stream-")).length }, (_, j) => ({
      stream: text(`stream-${j}`), key: text(`key-${j}`), item: text(`item-${j}`),
      per: text(`per-${j}`), amount: text(`amount-${j}`), decision: text(`decision-${j}`),
    })),
  };
  // once recorded, the id stays with the form, so a later dry-run does not offer to record the same sheet again
  const recorded = UUID.test(text("recorded")) ? text("recorded") : undefined;
  const kept = { sheet, columns, confirmed, run, recorded };
  const lines: Line[] = [];
  for (const [j, l] of run.lines.entries()) {
    if (!l.stream) continue;                                    // an empty row
    const minor = minorOf(l.amount);
    if (minor === null) return { ...kept, refused: `Ред ${j + 1}: сумата е в евро, с най-много два знака след десетичната запетая.` };
    lines.push({ stream: l.stream, key: l.key, decisionId: l.decision, ...(l.item && { item: l.item }),
      ...(l.per === "total" ? { totalMinor: minor } : { rateMinor: minor }) });
  }
  const multiple = run.multiplier.trim();
  if (multiple && !/^\d{1,3}$/.test(multiple)) return { ...kept, refused: "Множителят за стопанска дейност е цяло число." };
  const body = { csv, mapping: confirmed, period: run.period, legalDate: run.legalDate, lines, ...(multiple && { businessMultiplier: Number(multiple) }) };
  if (step === "dry-run") {
    const answer = await reach(() => api.POST("/api/intake/entrances/{entranceId}/fee-sheet/dry-run", { params: { path: { entranceId: entrance } }, body }));
    if (!answer?.data) return { ...kept, refused: answer ? undoneBy(answer, "изчислите пробно") : "Системата не отговори — нищо не е записано. Опитайте отново." };
    return { ...kept, report: answer.data };
  }
  if (step === "commit") return commitSheet(kept, body);   // the commit's answer stands in place of the earlier verdict: the API ran it again
  const answer = await reach(() => api.POST("/api/intake/entrances/{entranceId}/imports", { params: { path: { entranceId: entrance } }, body }));
  // a record may be stored before its answer is lost: the person is told to look, not to ask again
  if (!answer?.data) return { ...kept, refused: answer ? undoneBy(answer, "запишете внасянето") : unknownOutcome("записването на внасянето") };
  return { ...kept, report: answer.data.report, recorded: answer.data.importId, recordedNow: true };
}

/**
 * The recorded import committed (STAGE1-ADDENDUM §1 step 6; Rule: PM-SEC-001, PM-FEE-014) — the same sheet, mapping
 * and tariff sent again, as the API checks the sheet's hash against the record and runs the dry-run once more. Whether
 * it still reproduces, and may commit, is the API's. It answers COMMITTING: the registry adopts the rows after the
 * answer, so the import's page is read for COMMITTED or COMMIT_BLOCKED. What needs a declaration is listed for a
 * person to record, never adopted. Who committed is the sign-in's party (G-1), so the body names nobody.
 */
// Rule: PM-SEC-001, PM-FEE-014
async function commitSheet(kept: ProfileState, sheet: Schemas["FeeSheetDryRunRequest"]): Promise<ProfileState> {
  if (!kept.recorded) return { ...kept, refused: "Внасянето не е записано — запишете го, преди да го приемете." };
  const answer = await reach(() => api.POST("/api/intake/imports/{id}/commit", { params: { path: { id: kept.recorded! } }, body: { sheet } }));
  if (!answer?.data) return { ...kept, refused: answer ? undoneBy(answer, "приемете внасянето") : unknownOutcome("приемането на внасянето") };
  return { ...kept, committed: answer.data };
}

/** What the import's page holds: the reason as typed, and the revert's answer or the API's refusal. */
export type RevertState = { refused?: string; reason?: string; view?: Schemas["ImportView"] };

/**
 * A committed import reverted wholesale, with its reason (STAGE1-ADDENDUM §1; Rule: PM-SEC-001) — the API's to allow:
 * the manager or the board (G-1), only a COMMITTED import or one whose revert was blocked. It answers REVERTING; the
 * page is read again for REVERTED or REVERT_BLOCKED. Who reverted is the sign-in's party, so the body names nobody.
 */
// Rule: PM-SEC-001
export async function revertImport(_: RevertState, form: FormData): Promise<RevertState> {
  const text = textOf(form);
  const [id, reason] = [text("import"), text("reason")];
  if (!UUID.test(id)) return { reason, refused: "Внасянето не е посочено." };
  if (!reason.trim()) return { reason, refused: "Напишете защо внасянето се отменя." };
  const answer = await reach(() => api.POST("/api/intake/imports/{id}/revert", { params: { path: { id } }, body: { reason } }));
  if (!answer?.data) return { reason, refused: answer ? undoneBy(answer, "отмените внасянето") : unknownOutcome("отмяната на внасянето") };
  return { reason, view: answer.data };
}

// Rule: PM-SEC-001
async function propose(entrance: string, csv: string, sheet: NonNullable<ProfileState["sheet"]>, act: string): Promise<ProfileState> {
  const answer = await reach(() =>
    api.POST("/api/intake/entrances/{entranceId}/fee-sheet/profile", {
      params: { path: { entranceId: entrance } },
      body: { csv },
    }),
  );
  // profiling stores nothing, so an unanswered call may simply be asked again
  if (!answer?.data) return { refused: answer ? undoneBy(answer, act) : "Системата не отговори — нищо не е записано. Опитайте отново." };
  return { sheet, proposal: answer.data };
}
