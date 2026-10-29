import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { typesIn, unitsOf, unitsRefused } from "./units.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/units/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "UnitsForm.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./units.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const said = (status: number, error: string) => ({ error: { error }, response: { status } });

test("PM-ORG-002 declared ideal parts go as the exact decimal string typed, never a number; the sum is the API's", () => {
  assert.deepEqual(unitsOf(" 1 ; APARTMENT ; 62,50 ; 4.2000 \n\n2;SHOP;41.2;95.8000\r\n"), {
    units: [
      { designation: "1", unitType: "APARTMENT", businessUse: false, separateEntrance: false, areaM2: 62.5, idealParts: "4.2000" },
      { designation: "2", unitType: "SHOP", businessUse: false, separateEntrance: false, areaM2: 41.2, idealParts: "95.8000" },
    ],
  });
  // a set that does not sum to 100 is sent as typed: PM-ORG-002's refusal, with the delta, is the API's words
  const short = unitsOf("1;APARTMENT;;50\n2;APARTMENT;;49.98");
  assert.ok("units" in short && short.units.every((u) => typeof u.idealParts === "string"));
  assert.equal(unitsRefused(said(400, "ideal parts sum to 99.98%, 0.02% short of 100% (PM-ORG-002)")), "Системата не прие това: ideal parts sum to 99.98%, 0.02% short of 100% (PM-ORG-002)");
  // no sum, no float and no 100 in the code
  for (const s of [library, actions, form, page].map(code)) assert.doesNotMatch(s, /parseFloat|\b100\b|reduce\(|\.toFixed/);
});

test("PM-ORG-003 left-out ideal parts are left out of the request — the API derives them by area and marks them DERIVED; mixing is the API's refusal", () => {
  assert.deepEqual(unitsOf("1;APARTMENT;60\n2;SHOP;40;"), {
    units: [
      { designation: "1", unitType: "APARTMENT", businessUse: false, separateEntrance: false, areaM2: 60 },
      { designation: "2", unitType: "SHOP", businessUse: false, separateEntrance: false, areaM2: 40 },
    ],
  });
  // declared for some and not others, or no area: sent as typed — the API judges it, in its words
  const mixed = unitsOf("1;APARTMENT;60;50\n2;APARTMENT;40");
  assert.ok("units" in mixed && mixed.units[0].idealParts === "50" && !("idealParts" in mixed.units[1]));
  assert.ok("units" in unitsOf("1;APARTMENT"));
  assert.equal(unitsRefused(said(409, "entrance already has units")), "Системата не прие това: entrance already has units");
  // the page names the derived source in words, and says nothing of its own about how it is derived
  assert.match(code(page), /DERIVED: "изчислени по площ"/);
});

test("WEB-50b a line the web cannot read is refused by its number; the optional flags are да or не; the type is free text", () => {
  for (const [typed, line] of [[";APARTMENT", 1], ["1;APARTMENT\n2;", 2], ["1;APARTMENT;abc", 1], ["1;A;;4,2,1", 1], ["1;A;;;maybe", 1], ["1;A;;;;;;", 1], ["\n\n3", 3]] as const) {
    const r = unitsOf(typed);
    assert.ok("refused" in r && r.refused.startsWith(`Ред ${line}:`), `${JSON.stringify(typed)} ${JSON.stringify(r)}`);
  }
  assert.deepEqual(unitsOf("  \n "), { refused: "Въведете поне един обект — по един на ред." });
  assert.deepEqual(unitsOf("А1;Ателие;;;да;не"), { units: [{ designation: "А1", unitType: "Ателие", businessUse: true, separateEntrance: false }] });
  assert.deepEqual(unitsOf("А2;GARAGE;;;не;ДА"), { units: [{ designation: "А2", unitType: "GARAGE", businessUse: false, separateEntrance: true }] });
  // the web pins no list of types: it suggests the ones the entrance already uses
  assert.deepEqual(typesIn([{ unitType: "SHOP" }, { unitType: "APARTMENT" }, { unitType: "SHOP" }]), ["APARTMENT", "SHOP"]);
  assert.doesNotMatch([library, actions, form, page].map(code).join(), /APARTMENT|GARAGE|OFFICE|SHOP/);
});

test("PM-SEC-001 who may enter the book is the API's: its 403 in its words, no role gate, no outcome guessed, the form offered to everyone", () => {
  assert.match(unitsRefused(said(403, "само управителят или управата")), /^Нямате право да впишете обекти: само управителят или управата/);
  assert.match(unitsRefused({ error: {}, response: { status: 401 } }), /^Входът ви не беше приет/);
  for (const s of [actions, form, page, library].map(code)) assert.doesNotMatch(s, /\b(SYS_ADMIN|isAdmin|administers|admin|BM|MB)\b|\.roles\b|\brole\s*(===|!==|\.includes)/);
  assert.match(page, /<UnitsForm entrance=\{view\.entrance\.id\}/);
  assert.equal((actions.match(/unknownOutcome\(/g) ?? []).length, 1);
  // a failed answer keeps what was typed; a refusal says nothing was entered, an unknown outcome says to look first
  assert.match(code(actions), /refused: unitsRefused\(answer\)/);
  assert.match(code(actions), /if \(!answer\) return \{ typed, refused: unknownOutcome/);
  // the sidebar's entry points at the page, and the button is not another card's
  assert.match(readFileSync(new URL("../app/(console)/entrance/EntranceSidebar.tsx", import.meta.url), "utf8"), /label: "Обекти и идеални части", href: "\/entrance\/units"/);
  assert.match(form, /"Впиши обектите"/);
});
