import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { handoverOf, handoverRefused, matches, NOT_SIGNED, type HandoverInput } from "./handover.ts";

const out = "11111111-1111-4111-8111-111111111111";
const into = "22222222-2222-4222-8222-222222222222";
const typed = (p: Partial<HandoverInput> = {}): HandoverInput =>
  ({ handoverOn: "2026-10-28", from: "", outgoing: out, incoming: into, bankBalance: "570,00", ...p });

test("PM-FUND-010 the body carries the dates as typed, the bank balance in minor units, both party ids, and from only when given", () => {
  assert.deepEqual(handoverOf(typed()), { handoverOn: "2026-10-28", outgoingPartyId: out, incomingPartyId: into, bankBalanceMinor: 57000 });
  assert.deepEqual(handoverOf(typed({ from: " 2026-01-01 ", bankBalance: " 0 ", outgoing: ` ${out} ` })),
    { handoverOn: "2026-10-28", from: "2026-01-01", outgoingPartyId: out, incomingPartyId: into, bankBalanceMinor: 0 });
});

test("PM-FUND-010 an empty date, a missing or malformed bank balance, or a malformed party id is refused before any call", () => {
  for (const p of [{ handoverOn: "" }, { handoverOn: "  " }, { bankBalance: "" }, { bankBalance: "  " }, { bankBalance: "570,001" }, { bankBalance: "abc" }, { bankBalance: "-5" },
    { outgoing: "" }, { outgoing: "p-1" }, { incoming: "" }, { incoming: "22222222-2222-4222-8222" }, { incoming: `${into}x` }]) {
    assert.ok("refused" in handoverOf(typed(p)), JSON.stringify(p));
  }
  // equal sides, a handover in the future, a period after the handover are the API's 400 — nothing here guesses them
  assert.ok(!("refused" in handoverOf(typed({ incoming: out }))));
  assert.ok(!("refused" in handoverOf(typed({ handoverOn: "2099-01-01" }))));
});

test("PM-FUND-010 matches(basis, basisHash) is true for the pair and false when one byte of the basis changes", () => {
  assert.ok(matches("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
  assert.ok(matches('{"a":"б"}', "04e29d171d3b3ca6cc0ab6b385823c60f8989e516cf0b18d6daf493e0c5092ba"));   // UTF-8 bytes, not characters
  assert.ok(!matches('{"a":"в"}', "04e29d171d3b3ca6cc0ab6b385823c60f8989e516cf0b18d6daf493e0c5092ba"));
  assert.ok(!matches("abd", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
  assert.ok(!matches("abc", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"));      // the API's hash is lowercase hex
});

test("PM-FUND-010 nothing calls a statement signed: one 'not signed' line, exported once, and no screen of this slice says otherwise", () => {
  assert.match(NOT_SIGNED, /^Не е подписан от страните/);
  assert.match(NOT_SIGNED, /#57/);
  const read = (f: string) => readFileSync(new URL(f, import.meta.url), "utf8");
  const [list, page, form] = ["../app/(console)/entrance/fund/page.tsx", "../app/(console)/entrance/fund/handover/page.tsx",
    "../app/(console)/entrance/fund/handover/HandoverForm.tsx"].map(read);
  assert.ok(list.includes("NOT_SIGNED") && page.includes("NOT_SIGNED"), "both screens show the one constant");
  // the handover screen has no word that says a party signed, agreed, accepted or finalised (the fund page's disbursement column "Подписан" is another thing)
  const code = (src: string) => src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "").replaceAll("NOT_SIGNED", "");
  assert.doesNotMatch(code(page) + code(form), /подписан|приет|съгласен|окончателен|signed|accepted|final/i);
});

test("PM-SEC-001 the API's 403, 400 and 409 are shown in its words", () => {
  const said = (status: number, error: string) => handoverRefused({ error: { error }, response: { status } });
  assert.equal(said(403, "PM-SEC-001: the entrance's accounts are kept by its manager or board"),
    "Нямате право да издадете отчет при предаване на фонда: PM-SEC-001: the entrance's accounts are kept by its manager or board");
  assert.equal(said(400, "the outgoing and the incoming side are the same party (PM-FUND-010)"),
    "Системата не прие това: the outgoing and the incoming side are the same party (PM-FUND-010)");
  assert.equal(said(409, "party 2222 is not registered"), "Системата не прие това: party 2222 is not registered");
});
