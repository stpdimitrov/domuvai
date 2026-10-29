import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { endingOf, endRefused, mandateOf, OFFICE, recordRefused } from "./mandate.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/mandates/${f}`, import.meta.url), "utf8");
const [actions, forms, page] = ["actions.ts", "MandateForms.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./mandate.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const ID = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
const said = (status: number, error: string) => ({ error: { error }, response: { status } });

test("PM-GOV-004 a mandate is sent as typed, for the API's closed list of offices — the web types no term and does no date arithmetic", () => {
  // OFFICES in MandateAdministration.kt (line 152 refuses anything else): the same four, in the same order
  const kotlin = readFileSync(new URL("../../app/src/main/kotlin/zues/app/identity_org/MandateAdministration.kt", import.meta.url), "utf8");
  const listed = /val OFFICES = listOf\(([^)]*)\)/.exec(kotlin)?.[1].split(",").map((s) => s.trim().replace(/"/g, ""));
  assert.deepEqual(Object.keys(OFFICE), listed);
  const typed = { body: "CTL", party: ` ${ID} `, protocolRef: " протокол 3 ", validFrom: "2026-10-01", validTo: "2026-12-31" };
  assert.deepEqual(mandateOf(typed), { body: "CTL", partyId: ID, protocolRef: "протокол 3", validFrom: "2026-10-01", validTo: "2026-12-31" });
  for (const bad of [{ body: "" }, { body: "OWN" }, { party: "1" }, { protocolRef: " " }, { validFrom: "" }, { validTo: "утре" }]) assert.ok("refused" in mandateOf({ ...typed, ...bad }), JSON.stringify(bad));
  assert.deepEqual(endingOf({ mandate: ID, on: "2026-10-01", protocolRef: " акт " }), { mandateId: ID, on: "2026-10-01", protocolRef: "акт" });
  for (const bad of [{ mandate: "7" }, { on: "" }, { on: "вчера" }, { protocolRef: "" }]) assert.ok("refused" in endingOf({ mandate: ID, on: "2026-10-01", protocolRef: "акт", ...bad }), JSON.stringify(bad));
  // the term and the election are the API's (PM-GOV-004, PM-GOV-005): no year, no sum and no comparison of dates in the page's code
  for (const s of [library, actions, forms, page].map(code)) assert.doesNotMatch(s, /\b[23]\b\s*(\*|years?|г\b)|plusYears|setFullYear|Date\.(parse|now|UTC)|new Date|[<>]=?\s*(validTo|validFrom|electBy)/);
});

test("PM-GOV-005 the election's due day is shown as the API answered, in its standing — the web counts down nothing (the countdown widget of its acceptance is not built here)", () => {
  assert.match(page, /m\.standing\.electBy/);
  assert.match(page, /m\.standing\.actingSince/);
  assert.match(page, /ELECTION_DUE: "изборът се дължи"/);
  assert.doesNotMatch(code(page), /daysUntil|countdown|Math\./);
});

test("PM-SEC-001 only the administrator records and ends mandates: the API's refusal in its words, no role gate, no outcome guessed", () => {
  for (const refused of [recordRefused, endRefused]) {
    const text = refused(said(403, "само администраторът"));
    assert.match(text, /^Нямате право да /);
    assert.match(text, /само администраторът/);
    assert.match(refused(said(409, "вече е свързан")), /^Системата не прие това: вече е свързан/);
  }
  // every form is offered whoever signs in, and the page gates nothing on a role
  for (const s of [actions, forms, page].map(code)) assert.doesNotMatch(s, /\b(SYS_ADMIN|isAdmin|administers|admin)\b|\.roles\b|\brole\s*(===|!==|\.includes)/);
  assert.match(page, /<RecordForm [^>]*\/>\s*<EndForm [^>]*\/>/);
  // a write that got no answer is not said to be done or undone
  assert.equal((actions.match(/unknownOutcome\(/g) ?? []).length, 2);
});
