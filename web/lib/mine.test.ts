import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { blocks, emptyStatement, lineText, owes, refusedStatement, shareOf, type MyUnitRow } from "./mine.ts";
import { byPeriod, type StatementLine } from "./statement.ts";

const row = (o: Partial<MyUnitRow>): MyUnitRow => ({ unitId: "11111111-1111-1111-1111-111111111111", entranceLabel: "Вх. А", designation: "ап. 4", unitType: "APARTMENT", titleRole: "OWN", share: "1", ...o });
const line = (o: Partial<StatementLine>): StatementLine => ({ period: "2026-11", component: "MAINTENANCE", allocationKey: "PER_PERSON", quantity: 3, amountMinor: 1860, derivation: "3 person(s)", ...o });

test("PM-SEC-002 only an OWN row links to Моята сметка, a USR row is labelled and links nowhere", () => {
  const [own, usr] = blocks([row({}), row({ unitId: "22222222-2222-2222-2222-222222222222", titleRole: "USR" })]);
  assert.equal(own.statementHref, "/my/statement/11111111-1111-1111-1111-111111111111");
  assert.equal(own.roleLabel, "собственик");
  assert.equal(usr.statementHref, null);
  assert.equal(usr.roleLabel, "ползвател");
  assert.equal(blocks([row({ titleRole: "XYZ" })])[0].statementHref, null);        // an unknown title links nowhere
  assert.equal(shareOf("0.5"), "1/2");
  assert.equal(shareOf("1"), null);
  assert.equal(blocks([row({ share: "0.25" })])[0].share, "1/4");
});

test("PM-SEC-002 a refused statement shows no figure and names no other unit", () => {
  for (const s of [403, 404, 500]) {
    const said = refusedStatement(s);
    assert.equal(said, "Сметката още не е налична");
    assert.doesNotMatch(said, /€|\d/);
  }
  assert.match(refusedStatement(401), /^Входът ви не беше приет/);
  assert.doesNotMatch(refusedStatement(null), /€|\d/);
  const page = readFileSync(new URL("../app/(resident)/my/statement/[unitId]/page.tsx", import.meta.url), "utf8");
  assert.match(page, /refusedStatement\(/);
  assert.doesNotMatch(page, /error\.|\.message/);                                    // none of the api's words are shown
  assert.equal((page.match(/api\.GET\(/g) ?? []).length, 1);                         // one read, never another unit
});

test("PM-FEE-018 every line shows its stream, key, count and amount in Bulgarian", () => {
  assert.deepEqual(lineText(line({})), { what: "Поддръжка", how: "на живущ · 3", amount: "€18,60" });
  assert.deepEqual(lineText(line({ component: "MANAGEMENT", allocationKey: "BY_IDEAL_PARTS", quantity: 1, amountMinor: 450 })), { what: "Управление", how: "по идеални части · 1", amount: "€4,50" });
  assert.equal(lineText(line({ allocationKey: "METERED", item: "Вода" })).what, "Поддръжка · Вода");
  assert.doesNotMatch(JSON.stringify(lineText(line({}))), /person/);                  // the English derivation is not shown
  const ps = byPeriod([line({}), line({ period: "2026-10" }), line({ allocationKey: "METERED", item: "Вода" })]);
  assert.equal(ps[0].keyed.length + ps[0].metered.length, 2);                         // metered stands apart, none dropped
});

test("PM-FEE-018 an empty statement says nothing is charged, not an error", () => {
  assert.equal(emptyStatement, "Няма начислени суми");
  assert.deepEqual(byPeriod([]), []);
  assert.equal(owes(0), "Нямате дължими суми");
  assert.equal(owes(-500), "Имате преплатено €5,00");
});

test("PM-FEE-016 a balance in minor units is shown as €24,80 with no float arithmetic", () => {
  assert.equal(owes(2480), "Дължите €24,80");
  assert.equal(owes(100000), "Дължите €1.000,00");
  const code = readFileSync(new URL("./mine.ts", import.meta.url), "utf8").replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, "");
  assert.doesNotMatch(code, /parseFloat|toFixed|Math\.|\/ ?100|\* ?100/);
});
