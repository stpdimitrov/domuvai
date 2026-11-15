import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { declarationOf, filingRefused, KIND, retentionRefused } from "./declaration.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/declarations/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "DeclarationForm.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./declaration.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const [UNIT, PARTY] = ["3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b", "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"];
const said = (status: number, error: string) => ({ error: { error }, response: { status } });

test("PM-BOOK-003 a declaration goes to the API as typed — for oneself with no declarant, with no date of the web's own", () => {
  assert.deepEqual(declarationOf({ unit: UNIT, kind: "ACQUISITION", declarant: "" }), { kind: "ACQUISITION", unitId: UNIT });
  assert.deepEqual(declarationOf({ unit: ` ${UNIT} `, kind: "CHANGE", declarant: "  " }), { kind: "CHANGE", unitId: UNIT });
  // a bad unit, a kind outside the list, no kind: refused before any call
  for (const typed of [{ unit: "", kind: "CHANGE" }, { unit: "ап. 1", kind: "CHANGE" }, { unit: UNIT, kind: "SALE" }, { unit: UNIT, kind: "" }]) {
    assert.ok("refused" in declarationOf({ ...typed, declarant: "" }), JSON.stringify(typed));
  }
  // the filing date is the system's: no date field, no clock, no date arithmetic in the page's code
  for (const s of [library, actions, form].map(code)) assert.doesNotMatch(s, /new Date|Date\.|type="date"|name="(filedOn|on|date)"|\b\d+ дни\b/);
  assert.match(actions, /filed: \{ id: answer\.data\.id, filedOn: answer\.data\.filedOn, templateVersion: answer\.data\.templateVersion \}/);
});

test("PM-BOOK-003 the kinds offered are the API's DeclarationKind, and no other", () => {
  // BookDeclaration.kt: `enum class DeclarationKind { ACQUISITION, CHANGE }` — the spec gives a bare string (ADR-013), so the code is the list
  const kt = readFileSync(new URL("../../app/src/main/kotlin/zues/app/registry/BookDeclaration.kt", import.meta.url), "utf8");
  const listed = kt.match(/enum class DeclarationKind\s*\{([^}]*)\}/)?.[1].split(",").map((k) => k.trim()).filter(Boolean);
  assert.deepEqual(Object.keys(KIND), listed);
  assert.match(library, /BookDeclaration\.kt/);                                  // the comment that says where the list comes from
});

// PM-BOOK-003's acceptance (day 16 with no declaration raises an overdue task) and its 15-day window are the API's; the overdue list is
// the entrance page's. What this web slice owes is that the day and the declarant of a filing are the ones the API decides.
test("PM-BOOK-004 the template is the system's: the page names none and shows the version the API answered", () => {
  assert.doesNotMatch(code(library + actions + form), /образец\s*[=:]|template\s*[=:]\s*["'`]|templateVersion\s*[=:]\s*["'`]/);
  assert.match(form, /state\.filed\.templateVersion/);
  assert.match(actions, /templateVersion: answer\.data\.templateVersion/);        // the version is the API's answer, not a literal of the page's
  assert.match(form, /state\.filed\.filedOn/);
});

// The API's policy cites PM-BOOK-005 for RECORD_DECLARATION (Policy.kt): the book is entered by the manager or the board from the
// owner's declaration. The rule's animals-section acceptance is WEB-46's; this test covers a declaration recorded in a declarant's name.
test("PM-BOOK-005 a declarant is named only when typed — a party id, which the API checks holds the unit", () => {
  assert.deepEqual(declarationOf({ unit: UNIT, kind: "ACQUISITION", declarant: PARTY }), { kind: "ACQUISITION", unitId: UNIT, partyId: PARTY });
  assert.deepEqual(declarationOf({ unit: UNIT, kind: "ACQUISITION", declarant: ` ${PARTY} ` }), { kind: "ACQUISITION", unitId: UNIT, partyId: PARTY });
  assert.ok("refused" in declarationOf({ unit: UNIT, kind: "ACQUISITION", declarant: "Стефан Димов" }));
  // that the party holds the unit is the API's 404, shown in its words — the web does not look titles up
  assert.match(filingRefused(said(404, "no owner or user 9a8b of unit 3f2b")), /^Системата не прие това: no owner or user 9a8b of unit 3f2b$/);
  assert.deepEqual(actions.match(/api\.(GET|POST)\(\s*"[^"]+"/g)?.map((c) => c.replace(/\s+/g, " ")), [
    'api.POST("/api/registry/entrances/{entranceId}/book/declarations"',
    'api.POST("/api/registry/entrances/{entranceId}/book/retention"',
  ]);
  assert.doesNotMatch(code(page + form), /api\.(GET|POST)\(\s*"[^"]*\/(titles|parties|owners)/);
});

test("PM-BOOK-010 the retention run is one call behind a confirmation, with no window and no day of the web's own", () => {
  // the run is not asked for without the confirmation — in the form and, with no script, in the action
  assert.match(form, /<input type="checkbox" name="confirm" value="yes" required/);
  assert.match(actions, /text\(form, "confirm"\) !== "yes"\) return \{ refused/);
  const run = actions.split("export async function applyRetention")[1];
  assert.ok(run.indexOf('"confirm"') < run.indexOf("api.POST"));
  // no body, no date, no number of years: what is shown is what the API answered
  assert.doesNotMatch(run, /body|new Date|Date\./);
  assert.doesNotMatch(code(library + actions + form), /\b\d+ (години|месеца|дни)\b|retentionYears|window/i);
  assert.match(actions, /applied: \{ on: answer\.data\.on, householdUnlinked: answer\.data\.householdUnlinked, animalPassportsCleared: answer\.data\.animalPassportsCleared \}/);
  // an unanswered run is not said to be done or undone
  assert.ok(run.indexOf("unknownOutcome(") > 0 && run.indexOf("unknownOutcome(") < run.indexOf("answer.data"));
});

// PM-BOOK-006's acceptance (owner A reading owner B gets a 403 and an audit entry) is the API's and WEB-44's; here the page reads no book of its own.
test("PM-BOOK-006 the page reads no book of its own: what was filed is read through WEB-44's read", () => {
  assert.doesNotMatch(code(page + form + actions), /api\.GET\(\s*"[^"]*\/book/);
  assert.match(page, /\/entrance\/book\?entrance=/);
});

// PM-BOOK-007's acceptance (the log is immutable and exportable) is the API's; the retention run is entered in it by the API (kind RETENTION).
test("PM-BOOK-007 the overdue list stays the entrance page's, and the page keeps no log of its own", () => {
  assert.match(page, /\/entrance\?entrance=/);
  assert.doesNotMatch(code(page + form + actions), /access-log|localStorage|console\.log/);
});

test("PM-SEC-001 who may file, record or run the retention is the API's: its 403 in its words, no gate of the web's", () => {
  assert.equal(filingRefused(said(403, "PM-BOOK-003: not your unit")), "Нямате право да подавате декларация за книгата: PM-BOOK-003: not your unit");
  assert.equal(retentionRefused(said(403, "PM-BOOK-010: the retention is run by the manager or the board")),
    "Нямате право да пускате заличаването на данни след срока на съхранение: PM-BOOK-010: the retention is run by the manager or the board");
  assert.match(filingRefused({ response: { status: 409 } }), /HTTP 409/);
  assert.doesNotMatch(page + form, /role ===|isManager|isBoard|canFile|canRun|disabled=\{!/);
  assert.match(page, /<DeclarationForm[^>]*\/>\s*<RetentionForm/);
});
