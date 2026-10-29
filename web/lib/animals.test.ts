import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ABSENCE_ROWS, absencesOf, absencesRefused, ANIMAL_ROWS, animalLeavingRefused, animalsOf, animalsRefused, leavingOfAnimal } from "./animals.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/animals/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "AnimalsForm.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./animals.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const animal = (species: string, passport = "", from = "") => ({ species, passport, from });
const said = (status: number, error: string) => ({ error: { error }, response: { status } });

test("PM-BOOK-005 an animal goes to the API with its species and passport number as typed; the web keeps no species list and checks no passport", () => {
  assert.deepEqual(animalsOf([animal(" куче ", " BG-123/45 "), animal(""), animal(""), animal("")]), { animals: [{ species: "куче", vetPassportNo: "BG-123/45" }] });
  assert.deepEqual(animalsOf([animal("котка"), animal("")]), { animals: [{ species: "котка" }] });   // the passport number is optional, as the API's
  assert.deepEqual(animalsOf([animal(""), animal(" ")]), { refused: "Посочете поне едно животно." });
  assert.ok("refused" in animalsOf([animal("", "BG-1")]));                                           // a passport with no species is no animal
  assert.equal(ANIMAL_ROWS, 4);
  // no list of species, no passport pattern
  for (const s of [library, actions, form].map(code)) assert.doesNotMatch(s, /куче|котка|dog|cat\b|\[A-Z\]|passport\.test|PASSPORT_RE/i);
});

test("PM-BOOK-005 the animals are entered by the API alone; the page reads no animals of its own and says the book does not serve them yet", () => {
  assert.deepEqual(actions.match(/api\.(GET|POST)\(\s*"[^"]+"/g)?.map((c) => c.replace(/\s+/g, " ")), [
    'api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/animals"',
    'api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/animals/{animalId}/end"',
    'api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/absences"',
  ]);
  assert.doesNotMatch(code(page + form), /api\.(GET|POST)\(\s*"[^"]*\/(book|animals)/);   // the page reads no book and lists no animals
  assert.match(actions, /entered: answer\.data\.animalIds/);
  assert.match(form, /Животните още не се четат в книгата/);
  // an unanswered write is not said to be done or undone: each action says so before it reads the answer's data
  for (const act of actions.split("export async function").slice(1)) assert.ok(act.indexOf("unknownOutcome(") > 0 && act.indexOf("unknownOutcome(") < act.indexOf("answer.data"));
});

test("PM-BOOK-008 the days go to the API as typed: an animal's stay, its leaving and an absence's span are never computed or compared here", () => {
  assert.deepEqual(animalsOf([animal("куче", "", " 2026-10-01 ")]), { animals: [{ species: "куче", validFrom: "2026-10-01" }] });
  for (const from of ["1.10.2026", "утре"]) assert.ok("refused" in animalsOf([animal("куче", "", from)]), from);
  assert.deepEqual(leavingOfAnimal(" 2026-10-15 "), { on: "2026-10-15" });
  for (const on of ["", "15.10.2026"]) assert.ok("refused" in leavingOfAnimal(on), JSON.stringify(on));
  assert.deepEqual(absencesOf([{ from: " 2026-07-01 ", to: "2026-08-15" }, { from: "", to: "" }, { from: "", to: "" }]), { absences: [{ absentFrom: "2026-07-01", absentTo: "2026-08-15" }] });
  assert.deepEqual(absencesOf([{ from: "", to: "" }]), { refused: "Посочете поне едно отсъствие." });
  for (const row of [{ from: "2026-07-01", to: "" }, { from: "", to: "2026-07-01" }, { from: "1.7.2026", to: "2026-07-02" }]) assert.ok("refused" in absencesOf([row]), JSON.stringify(row));
  assert.equal(ABSENCE_ROWS, 3);
  // a span that runs backwards, or a leaving not after the move-in, is the API's 400 in its words — the two days are not compared here
  assert.deepEqual(absencesOf([{ from: "2026-08-15", to: "2026-07-01" }]), { absences: [{ absentFrom: "2026-08-15", absentTo: "2026-07-01" }] });
  for (const s of [library, actions].map(code)) assert.doesNotMatch(s, /new Date|Date\.|getTime|[<>]=? *(typed|on|from|to|absent\w*)\b/);
  for (const s of [form, page].map(code)) assert.doesNotMatch(s, /new Date|Date\.|getTime/);
  assert.match(animalLeavingRefused(said(400, "the move-out (2026-10-01) must be after the move-in (2026-10-01)")), /^Системата не прие това: the move-out \(2026-10-01\) must be after the move-in/);
});

test("PM-FEE-006 whether an absence exempts a charge is the API's: the page types no number of days and applies no exemption", () => {
  for (const s of [library, actions, form, page].map(code)) assert.doesNotMatch(s, /(?<![\w.])30(?![\d.])|(?<![\w.]|ал\. )6(?![\d.])|(?<![\w.])183(?![\d.])|exempt|освобод\w+ *=|prorat/i);
  // what the API answered is what the page shows: the ids it gave, not a figure of its own
  assert.match(actions, /entered: answer\.data\.absenceIds/);
  assert.match(form, /решава системата, не тази страница/);
});

test("PM-FEE-007 whether a declaration came in time is the API's: no grace window here, and the late-entry fact (#215) is stated, not worked around", () => {
  assert.doesNotMatch(code(library + actions + form + page), /grace|window|срок *[<>=]|handedIn|filedOn|submittedOn/i);
  // the form takes no "handed in" day because the API has none: the request is the two days and nothing else
  assert.deepEqual(Object.keys((absencesOf([{ from: "2026-07-01", to: "2026-07-10" }]) as { absences: object[] }).absences[0]!).sort(), ["absentFrom", "absentTo"]);
  assert.match(form, /#215/);
  assert.match(form, /системата не приема ден на подаване/);
  assert.match(absencesRefused(said(409, "PM-FEE-007: too late")), /^Системата не прие това: PM-FEE-007: too late/);   // a refusal for lateness is shown in the API's words
});

test("PM-SEC-001 who may enter the book is the API's: its 403 and its conflicts are shown in its words, the web gates nothing", () => {
  const words = "PM-BOOK-005: the book is entered by the manager or the board";
  assert.equal(animalsRefused(said(403, words)), `Нямате право да вписвате животни в книгата: ${words}`);
  assert.equal(animalLeavingRefused(said(403, words)), `Нямате право да записвате напускане на животно: ${words}`);
  assert.equal(absencesRefused(said(403, words)), `Нямате право да вписвате отсъствия в книгата: ${words}`);
  assert.match(animalsRefused({ response: { status: 409 } }), /HTTP 409/);
  // no role, no session, no hidden button: the three forms are offered whatever the list said
  assert.doesNotMatch(page + form, /role ===|isManager|isBoard|canEnter|disabled=\{!/);
  assert.match(page, /<AnimalsForm[^>]*\/>\s*<AnimalLeavingForm[^>]*\/>\s*<AbsencesForm/);
});
