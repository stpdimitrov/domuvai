import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { entryRefused, leavingOf, leavingRefused, membersOf, ROWS } from "./household.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/household/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "HouseholdForm.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./household.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const row = (kind: string, from = "") => ({ kind, from });

test("PM-FEE-005 a resident goes to the API as ticked, with no name and no birth date; the web does no age arithmetic", () => {
  assert.deepEqual(membersOf([row("adult"), row("child"), row(""), row("")]), { members: [{ isChildUnder6: false }, { isChildUnder6: true }] });
  // a row with no kind is no resident; none at all is refused before any call
  assert.deepEqual(membersOf([row(""), row(" ")]), { refused: "Посочете поне един живущ." });
  assert.ok("refused" in membersOf([row("pensioner")]));
  // the page types no age and no birth date: no `Date` arithmetic, no field but the schema's two
  for (const s of [library, actions, form].map(code)) assert.doesNotMatch(s, /new Date|Date\.|getFullYear|birth|рождени|ЕГН|egn/i);
  assert.match(library, /isChildUnder6: typedKind === "child"/);
});

test("PM-FEE-005 the form's label for a child follows the intake's, and asks nothing the API does not take", () => {
  assert.match(form, /KIND/);
  assert.match(library, /child: "Деца под 6 г\."/);
  assert.equal(ROWS, 4);
});

test("PM-FEE-008 the page types no number of days: who counts as an occupant is the API's, shown as it answers", () => {
  for (const s of [library, actions, form, page].map(code)) assert.doesNotMatch(s, /(?<![\w.])30(?![\d.])|(?<![\w.]|ал\. )6(?![\d.]| г)|(?<![\w.])183(?![\d.])/);
  // what the API answered is what the page shows: the ids it gave and the stay it closed, not a figure of its own
  assert.match(actions, /entered: answer\.data\.memberIds/);
  assert.match(actions, /validFrom: answer\.data\.validFrom, validTo: answer\.data\.validTo/);
});

test("PM-BOOK-008 the days go to the API as typed; the occupancy range is the API's, never computed here", () => {
  assert.deepEqual(membersOf([row("adult", "2026-10-01")]), { members: [{ isChildUnder6: false, validFrom: "2026-10-01" }] });
  assert.deepEqual(membersOf([row("adult", " 2026-10-01 ")]), { members: [{ isChildUnder6: false, validFrom: "2026-10-01" }] });
  for (const from of ["1.10.2026", "утре", "2026-1-1"]) assert.ok("refused" in membersOf([row("adult", from)]), from);
  assert.deepEqual(leavingOf(" 2026-10-15 "), { on: "2026-10-15" });
  for (const on of ["", "  ", "15.10.2026"]) assert.ok("refused" in leavingOf(on), JSON.stringify(on));
  // a day not after the move-in is the API's 400, shown in its words — the web does not compare the two days
  assert.doesNotMatch(code(library), /[<>]=? *(typed|on|from)/);
  assert.match(leavingRefused({ error: { error: "the move-out (2026-10-01) must be after the move-in (2026-10-01)" }, response: { status: 400 } }),
    /^Системата не прие това: the move-out \(2026-10-01\) must be after the move-in/);
});

test("PM-BOOK-005 the household is entered by the API alone, and its answer is read back through the book, not a path of this page's own", () => {
  assert.deepEqual(actions.match(/api\.(GET|POST)\(\s*"[^"]+"/g)?.map((c) => c.replace(/\s+/g, " ")), [
    'api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/household"',
    'api.POST("/api/registry/entrances/{entranceId}/units/{unitId}/household/{memberId}/end"',
  ]);
  assert.doesNotMatch(code(page + form), /api\.(GET|POST)\(\s*"[^"]*\/book/);   // the page reads no book: WEB-44's read, with a purpose, is the way
  assert.match(page, /\/entrance\/book\?entrance=/);
  // an unanswered write is not said to be done or undone: each action says so before it reads the answer's data
  for (const act of actions.split("export async function").slice(1)) assert.ok(act.indexOf("unknownOutcome(") > 0 && act.indexOf("unknownOutcome(") < act.indexOf("answer.data"));
});

test("PM-SEC-001 who may enter the book is the API's: its 403 and its conflicts are shown in its words, the web gates nothing", () => {
  const said = (status: number, error: string) => ({ error: { error }, response: { status } });
  assert.equal(entryRefused(said(403, "PM-BOOK-005: the book is entered by the manager or the board")),
    "Нямате право да вписвате живущи в книгата: PM-BOOK-005: the book is entered by the manager or the board");
  assert.equal(leavingRefused(said(403, "PM-BOOK-005: the book is entered by the manager or the board")),
    "Нямате право да записвате напускане на живущ: PM-BOOK-005: the book is entered by the manager or the board");
  assert.match(entryRefused({ response: { status: 409 } }), /HTTP 409/);
  // no role, no session, no hidden button: both forms are offered whatever the list said
  assert.doesNotMatch(page + form, /role ===|isManager|isBoard|canEnter|disabled=\{!/);
  assert.match(page, /<HouseholdForm[^>]*\/>\s*<LeavingForm/);
});
