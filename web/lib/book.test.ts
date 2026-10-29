import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { bookRefused, completeness, partyLine, purposeOf, sofiaTime } from "./book.ts";

const page = ["page.tsx", "BookForm.tsx", "actions.ts"].map((f) => readFileSync(new URL(`../app/(console)/entrance/book/${f}`, import.meta.url), "utf8")).join("\n");

test("PM-BOOK-007 a read with no purpose is refused before any call; a purpose is sent as typed, trimmed", () => {
  for (const typed of ["", "   ", "\n\t"]) assert.ok("refused" in purposeOf(typed), JSON.stringify(typed));
  assert.deepEqual(purposeOf("  годишна проверка на декларациите "), { purpose: "годишна проверка на декларациите" });
  // the action returns before either GET when the purpose is refused: the check comes first in its source
  const action = page.slice(page.indexOf("export async function read"));
  assert.ok(action.indexOf('"refused" in given') < action.indexOf("api.GET"), "the purpose is checked after a call");
  for (const call of action.match(/api\.GET\([^]*?\}\s*\}\s*\}\)/g) ?? []) assert.match(call, /query: \{ purpose/);
  assert.equal((action.match(/api\.GET/g) ?? []).length, 2);
});

test("PM-BOOK-007 the page itself reads nothing of the book: only the form's action calls the book or its log", () => {
  const [pageOnly, form] = ["page.tsx", "BookForm.tsx"].map((f) => readFileSync(new URL(`../app/(console)/entrance/book/${f}`, import.meta.url), "utf8"));
  for (const source of [pageOnly, form]) assert.doesNotMatch(source, /api\.(GET|POST)|\/api\/registry/);
});

test("PM-BOOK-011 a party is shown by name, role and share only — a field the API might add is never shown", () => {
  const leaky = { name: "Иван Петров", role: "OWN", share: "1", egn: "7501010010", address: "ул. Шипка 14" } as never;
  const line = partyLine(leaky);
  assert.equal(line, "Иван Петров · собственик · дял 1");
  assert.ok(!line.includes("7501010010") && !line.includes("Шипка"));
  assert.equal(partyLine({ name: "Мария Иванова", role: "USR", share: "0.5" }), "Мария Иванова · ползвател · дял 0.5");
});

test("PM-BOOK-011 the page code reads no party field outside the schema's name, role and share, and names no ЕГН or address", () => {
  assert.doesNotMatch(page, /egn|ЕГН|address|адрес|personalId|identity/i);
  assert.match(page, /parties\.map\(\(p, i\) => <div key=\{i\}>\{partyLine\(p\)\}<\/div>\)/);    // the one place a party is drawn
  assert.doesNotMatch(page.replace(/partyLine\(p\)/, ""), /\bp\.\w+/);
});

test("PM-BOOK-006 PM-SEC-001 a 403 is shown as the API's refusal in its words, the rule it names first", () => {
  const answer = { error: { error: "only the manager, the board and the controller read the book", rule: "PM-BOOK-006" }, response: { status: 403 } };
  assert.equal(bookRefused(answer, "книгата на етажната собственост"),
    "Нямате право да четете книгата на етажната собственост: PM-BOOK-006: only the manager, the board and the controller read the book");
  assert.equal(bookRefused({ error: { error: "a purpose is required to read the book (PM-BOOK-007)" }, response: { status: 400 } }, "книгата"),
    "API отказа книгата: a purpose is required to read the book (PM-BOOK-007)");
});

test("PM-BOOK-001 PM-BOOK-002 the book and each unit are shown complete or not as the API says, with every field the book records", () => {
  assert.deepEqual(completeness(true), { label: "пълно", badge: "green" });
  for (const not of [false, undefined, null, "true", 1]) assert.deepEqual(completeness(not), { label: "непълно", badge: "crit" }, String(not));
  assert.match(page, /completeness\(book\.complete\)\.label/);
  assert.match(page, /completeness\(u\.complete\)\.label/);
  for (const field of ["u.designation", "u.builtAreaM2", "u.idealParts", "u.parties", "u.householdCount", "u.nonUse"]) assert.ok(page.includes(field), field);
});

test("PM-BOOK-007 a log entry's time is shown in Sofia's time, the instant the API recorded", () => {
  assert.equal(sofiaTime("2026-10-29T08:05:00Z"), "29.10.2026, 10:05:00");
  assert.equal(sofiaTime("2026-07-01T21:30:15.123456Z"), "02.07.2026, 00:30:15");
});
