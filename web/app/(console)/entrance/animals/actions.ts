"use server";

import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { ABSENCE_ROWS, ANIMAL_ROWS, absencesOf, absencesRefused, animalLeavingRefused, animalsOf, animalsRefused, leavingOfAnimal,
  type AbsenceRow, type AnimalRow } from "@/lib/animals";
import { unknownOutcome } from "@/lib/refusal";

/** What the animals form holds after a submission: the form as typed, and the API's answer — the animals entered, or why not. */
export type AnimalsState = { input?: { unit: string; rows: AnimalRow[] }; entered?: string[]; refused?: string };

/** The same for an animal's leaving: the form as typed, and the stay the API closed, or why not. */
export type AnimalLeavingState = { input?: { unit: string; animal: string; on: string }; left?: { validFrom: string; validTo: string }; refused?: string };

/** The same for absences: the form as typed, and the ids the API gave, or why not. */
export type AbsencesState = { input?: { unit: string; rows: AbsenceRow[] }; entered?: string[]; refused?: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (form: FormData, name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");

/**
 * A unit's animals entered in the book (WEB-46). Rule: PM-BOOK-005 — the species and the veterinary passport number go to
 * the API as typed; Rule: PM-SEC-001 — who may enter the book is the API's (the manager or the board): a refusal is shown
 * in its words, the form kept, nothing said to be entered. The ids it gives back are shown, for a leaving to be recorded
 * against; animals are not in the book's read yet (it serves counts and non-use spans), so there is no read-back of them.
 */
// Rule: PM-BOOK-005, PM-BOOK-008, PM-SEC-001
export async function enterAnimals(_: AnimalsState, form: FormData): Promise<AnimalsState> {
  const [entranceId, unitId] = [text(form, "entrance"), text(form, "unit")];
  const rows = Array.from({ length: ANIMAL_ROWS }, (_, i) => ({ species: text(form, `species${i}`), passport: text(form, `passport${i}`), from: text(form, `from${i}`) }));
  const input = { unit: unitId, rows };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  if (!UUID.test(unitId)) return { input, refused: "Изберете обект." };
  const body = animalsOf(rows);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/animals", { params: { path: { entranceId, unitId } }, body }));
  // no answer: the animals may be entered — the book says; sent again they would be entered twice
  if (!answer) return { input, refused: unknownOutcome("вписването на животните") };
  return answer.data ? { input, entered: answer.data.animalIds } : { input, refused: animalsRefused(answer) };
}

/**
 * An animal's leaving recorded (WEB-46). Rule: PM-BOOK-008 — the day is sent as typed and the range it closes is the
 * API's (it refuses a day not after the move-in, in its words). Rule: PM-SEC-001 — who may record it is the API's.
 */
// Rule: PM-BOOK-008, PM-SEC-001
export async function leaveAnimal(_: AnimalLeavingState, form: FormData): Promise<AnimalLeavingState> {
  const [entranceId, unitId, animalId] = [text(form, "entrance"), text(form, "unit"), text(form, "animal")];
  const input = { unit: unitId, animal: animalId, on: text(form, "on") };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  if (!UUID.test(unitId)) return { input, refused: "Изберете обект." };
  if (!UUID.test(animalId)) return { input, refused: "Номерът на животното не е във вида, в който го дава системата при вписването." };
  const body = leavingOfAnimal(input.on);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() =>
    api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/animals/{animalId}/end", { params: { path: { entranceId, unitId, animalId } }, body }));
  if (!answer) return { input, refused: unknownOutcome("записването на напускането") };
  return answer.data ? { input, left: { validFrom: answer.data.validFrom, validTo: answer.data.validTo } } : { input, refused: animalLeavingRefused(answer) };
}

/**
 * A unit's absences entered in the book (WEB-46). Rule: PM-BOOK-008 — each span goes as two days typed; Rule: PM-FEE-006,
 * PM-FEE-007 — whether it exempts, and whether it came in time, is the API's: the page applies no exemption and shows the
 * ids it answers and its refusals. Rule: PM-SEC-001 — who may enter it is the API's. The entry is read back through the
 * book's own read, with its purpose (WEB-44): its `nonUse`, not a path of this page's own.
 */
// Rule: PM-BOOK-008, PM-FEE-006, PM-FEE-007, PM-SEC-001
export async function enterAbsences(_: AbsencesState, form: FormData): Promise<AbsencesState> {
  const [entranceId, unitId] = [text(form, "entrance"), text(form, "unit")];
  const rows = Array.from({ length: ABSENCE_ROWS }, (_, i) => ({ from: text(form, `absentFrom${i}`), to: text(form, `absentTo${i}`) }));
  const input = { unit: unitId, rows };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  if (!UUID.test(unitId)) return { input, refused: "Изберете обект." };
  const body = absencesOf(rows);
  if ("refused" in body) return { input, refused: body.refused };

  const answer = await reach(() => api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/absences", { params: { path: { entranceId, unitId } }, body }));
  if (!answer) return { input, refused: unknownOutcome("вписването на отсъствията") };
  return answer.data ? { input, entered: answer.data.absenceIds } : { input, refused: absencesRefused(answer) };
}
