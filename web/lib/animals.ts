/**
 * A unit's animals and absences entered in the book, and an animal's leaving recorded (WEB-46). Pure: no import but
 * `household` and `refusal`, so it is tested on Node's own runner. What an animal or an absence does to a charge, and
 * whether a declaration came in time, is the API's (PM-FEE-006, PM-FEE-007): the web types no number of days and no
 * grace window, does no arithmetic on a date, applies no exemption, and shows what the API answered.
 */
import { leavingOf } from "./household.ts";
import { undoneBy } from "./refusal.ts";

/** How many animals / absences one submission can enter — the form works without a script, so its rows are fixed; more are a second submission. */
export const ANIMAL_ROWS = 4;
export const ABSENCE_ROWS = 3;

/** A row of the animals form as typed: the species ("" = no animal in this row), the veterinary passport number and from when. */
export type AnimalRow = { species: string; passport: string; from: string };

/** A row of the absences form as typed: the first day absent and the first day back, as the API takes them (the span is `[absentFrom, absentTo)`). */
export type AbsenceRow = { from: string; to: string };

/** The request's `NewAnimalRequest`: the species and the passport number as typed, the day only when typed. */
export type NewAnimal = { species: string; vetPassportNo?: string; validFrom?: string };

/** The request's `NewAbsenceRequest`. */
export type NewAbsence = { absentFrom: string; absentTo: string };

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Rule: PM-BOOK-005, PM-BOOK-008 — the animals as typed, one `NewAnimalRequest` each: the species and the veterinary
 * passport number go as typed (the web keeps no list of species and checks no passport format), the day they began only
 * when typed (the date range is the API's). A row with nothing in it is no animal; none at all is refused here.
 */
// Rule: PM-BOOK-005, PM-BOOK-008
export function animalsOf(rows: AnimalRow[]): { animals: NewAnimal[] } | { refused: string } {
  const animals: NewAnimal[] = [];
  for (const [i, row] of rows.entries()) {
    const [species, passport, from] = [row.species.trim(), row.passport.trim(), row.from.trim()];
    if (!species && !passport && !from) continue;
    if (!species) return { refused: `Ред ${i + 1}: посочете вида на животното.` };
    if (from && !DAY.test(from)) return { refused: `Ред ${i + 1}: „${from}“ не е дата.` };
    animals.push({ species, ...(passport && { vetPassportNo: passport }), ...(from && { validFrom: from }) });
  }
  return animals.length ? { animals } : { refused: "Посочете поне едно животно." };
}

/**
 * Rule: PM-BOOK-008, PM-FEE-006, PM-FEE-007 — the absences as typed, one `NewAbsenceRequest` each, both days sent as
 * typed. Whether the span exempts, and whether it was filed in time, is the API's: the two days are not compared here.
 */
// Rule: PM-BOOK-008, PM-FEE-006, PM-FEE-007
export function absencesOf(rows: AbsenceRow[]): { absences: NewAbsence[] } | { refused: string } {
  const absences: NewAbsence[] = [];
  for (const [i, row] of rows.entries()) {
    const [absentFrom, absentTo] = [row.from.trim(), row.to.trim()];
    if (!absentFrom && !absentTo) continue;
    for (const day of [absentFrom, absentTo]) if (!DAY.test(day)) return { refused: `Ред ${i + 1}: ${day ? `„${day}“ не е дата` : "посочете и двата дни"}.` };
    absences.push({ absentFrom, absentTo });
  }
  return absences.length ? { absences } : { refused: "Посочете поне едно отсъствие." };
}

/** Rule: PM-BOOK-008 — the day an animal left, as typed; the same reading of a day as a resident's leaving (WEB-45). */
// Rule: PM-BOOK-008
export const leavingOfAnimal = leavingOf;

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001, PM-BOOK-005 — who may enter the book is the API's: its refusal and its conflicts, in its words, nothing said to be entered. */
// Rule: PM-SEC-001, PM-BOOK-005
export const animalsRefused = (answer: Answer): string => undoneBy(answer, "вписвате животни в книгата");

/** The same for a leaving (Rule: PM-SEC-001, PM-BOOK-008). */
// Rule: PM-SEC-001, PM-BOOK-008
export const animalLeavingRefused = (answer: Answer): string => undoneBy(answer, "записвате напускане на животно");

/** The same for an absence — whether it was filed in time is the API's (Rule: PM-SEC-001, PM-FEE-007). */
// Rule: PM-SEC-001, PM-FEE-007
export const absencesRefused = (answer: Answer): string => undoneBy(answer, "вписвате отсъствия в книгата");
