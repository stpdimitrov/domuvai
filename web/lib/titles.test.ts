import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ROLE } from "./book.ts";
import { ownersOf, titleOf, titleRefused } from "./titles.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/units/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "TitleForm.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./titles.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const said = (status: number, error: string) => ({ error: { error }, response: { status } });
const [UNIT, PARTY] = ["3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b", "7a1d2c3e-4f50-4a6b-8c7d-9e0f1a2b3c4d"];
const typed = { unit: UNIT, party: PARTY, role: "OWN", share: "0.5", validFrom: "", validTo: "" };

test("PM-ORG-005 a title is sent as typed — the share an exact decimal string, never a number; its range and the co-owners' sum are the API's", () => {
  assert.deepEqual(titleOf({ ...typed, unit: ` ${UNIT} `, party: ` ${PARTY} `, share: " 0,333333 " }), { unitId: UNIT, body: { partyId: PARTY, titleRole: "OWN", share: "0.333333" } });
  assert.deepEqual(titleOf({ ...typed, share: "1.000000" }), { unitId: UNIT, body: { partyId: PARTY, titleRole: "OWN", share: "1.000000" } });
  // a share outside (0, 1] is sent as typed: the API's 400, in its words — the web holds no bound
  for (const share of ["0", "1.5", "7"]) assert.deepEqual(titleOf({ ...typed, share }), { unitId: UNIT, body: { partyId: PARTY, titleRole: "OWN", share } });
  assert.equal(titleRefused(said(400, "share must be in (0, 1]")), "Системата не прие това: share must be in (0, 1]");
  for (const bad of ["", "половин", "50%", "-1", "1e-1"]) assert.ok("refused" in titleOf({ ...typed, share: bad }), bad);
  // no bound, no sum and no float in the code: a unit's co-owners are not added up by anybody here
  for (const s of [library, actions, form, page].map(code)) assert.doesNotMatch(s, /parseFloat|Number\(|\b100\b|reduce\(|\.toFixed|\bshare\s*[<>]=?/);
});

test("PM-ORG-011 the days go as typed and a blank one is left out — the web computes no date; the unit's owners are read back as of the first day", () => {
  assert.deepEqual(titleOf({ ...typed, validFrom: "2026-10-01", validTo: "2027-01-31" }), { unitId: UNIT, body: { partyId: PARTY, titleRole: "OWN", share: "0.5", validFrom: "2026-10-01", validTo: "2027-01-31" } });
  const blank = titleOf(typed);
  assert.ok("body" in blank && !("validFrom" in blank.body) && !("validTo" in blank.body));
  // order of the days, and whether they are real, are the API's; the web checks only that a typed day is a day
  assert.ok("body" in titleOf({ ...typed, validFrom: "2026-12-01", validTo: "2026-01-01" }));
  assert.ok("refused" in titleOf({ ...typed, validFrom: "утре" }));
  assert.equal(titleRefused(said(400, "validTo must be after validFrom")), "Системата не прие това: validTo must be after validFrom");
  for (const s of [library, actions, form, page].map(code)) assert.doesNotMatch(s, /Date\.(parse|now|UTC)|new Date|plusDays|setDate|[<>]=?\s*(validTo|validFrom)/);
  // read back on the first day typed, or today in Sofia from the one shared helper; the 201's number first, then names only
  assert.match(code(actions), /const on = title\.body\.validFrom \?\? sofiaToday\(\)/);
  assert.match(code(actions), /query: \{ on \}/);
  const owners = [
    { unitId: UNIT, partyName: "Мария Петрова", titleRole: "OWN", share: 0.5, idNumber: "secret" } as never,
    { unitId: UNIT, partyName: "Георги Колев", titleRole: "USR", share: 1 },
    { unitId: "elsewhere", partyName: "Друг", titleRole: "OWN", share: 1 },
  ];
  assert.deepEqual(ownersOf(owners, UNIT), ["Георги Колев · ползвател · дял 1", "Мария Петрова · собственик · дял 0.5"]);
  assert.deepEqual(ownersOf([], UNIT), []);
});

test("PM-BOOK-005 the roles are the API's closed list: TitleRole in the Kotlin and the CHECK in the schema, in the same order — the page offers no other; the API refuses one anyway", () => {
  const kotlin = readFileSync(new URL("../../app/src/main/kotlin/zues/app/registry/Title.kt", import.meta.url), "utf8");
  const listed = /enum class TitleRole \{([^}]*)\}/.exec(kotlin)?.[1].split(",").map((s) => s.trim()).filter(Boolean);
  assert.deepEqual(Object.keys(ROLE), listed);
  const schema = readFileSync(new URL("../../app/src/main/resources/db/migration/V1__init.sql", import.meta.url), "utf8");
  const checked = /title_role\s+text NOT NULL CHECK \(title_role IN \(([^)]*)\)\)/.exec(schema)?.[1].split(",").map((s) => s.trim().replace(/'/g, ""));
  assert.deepEqual(Object.keys(ROLE), checked);
  assert.ok("refused" in titleOf({ ...typed, role: "OCC" }) && "refused" in titleOf({ ...typed, role: "" }));
  assert.ok("refused" in titleOf({ ...typed, unit: "" }) && "refused" in titleOf({ ...typed, party: "1" }));
});

test("PM-SEC-001 who may enter the book is the API's: its 403 in its words, no role gate, no outcome guessed, the form offered to everyone", () => {
  assert.match(titleRefused(said(403, "само управителят или управата")), /^Нямате право да впишете право върху обект: само управителят или управата/);
  assert.match(titleRefused({ error: {}, response: { status: 401 } }), /^Входът ви не беше приет/);
  for (const s of [actions, form, page, library].map(code)) assert.doesNotMatch(s, /\b(SYS_ADMIN|isAdmin|administers|admin|BM|MB)\b|\.roles\b|\brole\s*(===|!==|\.includes)/);
  assert.match(page, /<TitleForm entrance=\{view\.entrance\.id\}/);
  assert.equal((actions.match(/unknownOutcome\(/g) ?? []).length, 2);           // the units set and the title
  // a failed answer keeps what was typed; a refusal says nothing was entered, an unknown outcome says to look first
  assert.match(code(actions), /refused: titleRefused\(answer\)/);
  assert.match(code(actions), /if \(!answer\) return \{ typed, refused: unknownOutcome\("вписването на правото"\)/);
  // the owners read can fail after the title is recorded: the number is still shown, and the failure said in the API's words
  assert.match(code(actions), /ownersRefused: unansweredBy\(owners/);
  assert.match(form, /"Впиши правото"/);
});
