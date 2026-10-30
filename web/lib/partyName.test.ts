import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { nameFor } from "./partyName.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/${f}`, import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const [BORIS, ANA] = ["3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b", "9a1c7d2e-0b3f-4c5d-8e6a-7f8091a2b3c4"];

test("PM-BOOK-006 a row's name is the one served for that id; an id the row has no name for shows the id alone", () => {
  const names = { [BORIS]: "Борис Петров" };
  assert.equal(nameFor(BORIS, names), "Борис Петров");
  assert.equal(nameFor(ANA, names), undefined, "a name never stands beside an id it is not for");
  assert.equal(nameFor(undefined, names), undefined);
});

test("PM-SEC-002 no `names` — the API withheld them, as from an owner — is no name; the web adds none of its own", () => {
  assert.equal(nameFor(BORIS, undefined), undefined);
  assert.equal(nameFor(BORIS, {}), undefined);
  assert.equal(nameFor("constructor", {}), undefined, "an inherited property is not a name");
});

test("PM-BOOK-011 a hostile name stays text: the helper returns it untouched and the component never writes markup", () => {
  const hostile = `<img src=x onerror=alert(1)>`;
  assert.equal(nameFor(BORIS, { [BORIS]: hostile }), hostile);
  const component = code(source("PartyName.tsx"));
  assert.ok(!/dangerouslySetInnerHTML|innerHTML/.test(component));
  assert.match(component, /\{name\}/);
});

test("PM-BOOK-006 the name goes beside the 8-character id with the whole id on hover; the id alone when there is no name", () => {
  const component = code(source("PartyName.tsx"));
  assert.match(component, /id\.slice\(0, 8\)/);
  assert.match(component, /title=\{id\}/);
  assert.match(component, /name \?/);
});

test("PM-BOOK-006 the pages name only the ids the API names: attendee, represents, principal, agent, owner, mandates' party — the rest stay Party", () => {
  const assembly = code(source("entrance/assembly/page.tsx"));
  const mandates = code(source("entrance/mandates/page.tsx"));
  for (const id of ["l.attendeePartyId", "p", "p.principalPartyId", "p.agentPartyId", "r.ownerPartyId"]) {
    assert.match(assembly, new RegExp(`<PartyName id=\\{${id.replace(".", "\\.")}\\} name=\\{nameFor\\(${id.replace(".", "\\.")}, (l|p|r)\\.names\\)\\} />`), id);
  }
  assert.match(assembly, /<PartyName id=\{m\.partyId\} name=\{m\.partyName\} \/>/);
  assert.match(mandates, /<PartyName id=\{m\.partyId\} name=\{m\.partyName\} \/>/);
  for (const id of ["r.unitId", "a.convenedBy", "quorum.data.concentratedBy", "r.recordedBy", "r.reverses", "d.closedBy", "d.mergedBy", "d.supersedes"]) {
    assert.ok(assembly.includes(`<Party id={${id}} />`), `${id} stays an id`);
  }
  assert.ok(!/<code>\{m\.partyId/.test(mandates));
});
