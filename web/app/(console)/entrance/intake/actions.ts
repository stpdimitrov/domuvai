"use server";

import { api, type Schemas } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { undoneBy } from "@/lib/refusal";

type Field = Schemas["ProposedMapping"]["mapping"][string];

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
};

// Rule: PM-SEC-001
/** One action for the page's two forms, so the upload's answer is the state the mapping form is drawn from. */
export async function intake(previous: ProfileState, form: FormData): Promise<ProfileState> {
  return form.get("step") === "confirm" ? confirmMapping(previous, form) : profileSheet(previous, form);
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
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string) : "");
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
    if (field) confirmed[column] = field as Field;     // not sent anywhere yet: the dry-run (WEB-36) is where the API checks it
  });
  const state = await propose(entrance, csv, sheet, "потвърдите разпределението");
  // refused: the sheet and the choices are kept, so the form comes back as it was filled
  return state.refused ? { ...state, sheet, columns, confirmed } : { ...state, confirmed };
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
