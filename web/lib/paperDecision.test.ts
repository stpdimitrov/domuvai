import assert from "node:assert/strict";
import { test } from "node:test";
import { CUSTODIAN_NOT_SHOWN, FIELDS, originalOf, paperOffer, recordOf, recordRefused, type PaperDecision } from "./paperDecision.ts";

const decision: PaperDecision = {
  id: "11111111-1111-1111-1111-111111111111", decisionText: "Месечни вноски за управление.", protocolDate: "2026-03-12",
  protocolRef: "Протокол № 4", archiveLocation: "Папка „Протоколи“ у управителя", custodian: "Мария Иванова",
};
const typed = { decisionText: "Ремонт", protocolDate: "2026-03-12", protocolRef: "Протокол № 4", archiveLocation: "Шкаф 2", custodian: "Мария Иванова" };

test("PM-GA-022 every record shown names where the paper original is kept", () => {
  assert.ok(originalOf(decision).startsWith("Оригиналът на хартия е в: Папка „Протоколи“ у управителя"));
});

test("PM-DOC-007 the record is the five fields as typed, each sent to the API", () => {
  assert.deepEqual([...FIELDS].sort(), Object.keys(typed).sort());
  assert.deepEqual(recordOf(typed), typed);
});

test("PM-DOC-007 a field left empty is not sent, so the API refuses it as missing in its words", () => {
  const body = recordOf({ ...typed, protocolDate: "", archiveLocation: "" });
  assert.ok(!("protocolDate" in body) && !("archiveLocation" in body));
  assert.equal(body.custodian, "Мария Иванова");
});

test("PM-DOC-007 the record names who keeps the paper original when the API does", () => {
  assert.ok(originalOf(decision).endsWith("пази го: Мария Иванова"));
});

test("PM-SEC-002 a custodian the API does not name is said in words, never a blank value", () => {
  const { custodian: _, ...withheld } = decision;
  assert.equal(originalOf(withheld), `Оригиналът на хартия е в: Папка „Протоколи“ у управителя · пази го: ${CUSTODIAN_NOT_SHOWN}`);
});

test("PM-SEC-001 a record the API refuses is said in its words, naming the rule, with nothing said to be done", () => {
  const answer = { error: { error: "not allowed", rule: "PM-SEC-001" }, response: { status: 403 } };
  assert.equal(recordRefused(answer), "Нямате право да впишете решение от хартиен протокол: PM-SEC-001: not allowed");
});

test("PM-SEC-001 what is missing from a record is the API's refusal in its words", () => {
  const answer = { error: { error: "the protocol's date is required" }, response: { status: 400 } };
  assert.equal(recordRefused(answer), "Системата не прие това: the protocol's date is required");
});

test("PM-FEE-012 a paper decision is offered by the id a charge run cites, with its protocol and its text", () => {
  assert.deepEqual(paperOffer(decision), {
    id: decision.id,
    label: "ОС на хартия 2026-03-12, Протокол № 4: Месечни вноски за управление.",
  });
});

test("PM-FUND-007 a long or broken decision text is one short line in the option, cut on a whole character, its id kept", () => {
  const { id, label } = paperOffer({ ...decision, decisionText: `Първи ред\n\n   втори ${"я".repeat(63)}😀${"б".repeat(40)}` });
  assert.equal(id, decision.id);
  assert.ok(label.includes("Първи ред втори я"), label);
  assert.ok(label.endsWith("😀…"), label);
  assert.ok(!label.includes("\n") && !label.includes("б"), label);
  assert.ok(label.isWellFormed(), label);
});

test("PM-FUND-007 a text within the limit is shown whole", () => {
  assert.ok(!paperOffer({ ...decision, decisionText: "я".repeat(80) }).label.endsWith("…"));
  assert.ok(paperOffer({ ...decision, decisionText: "я".repeat(81) }).label.endsWith("…"));
});
