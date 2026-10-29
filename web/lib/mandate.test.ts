import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { endingOf, endRefused, mandateOf, OFFICE, recordRefused, tieOf, tieRefused, untieOf, untieRefused } from "./mandate.ts";

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
  for (const refused of [recordRefused, endRefused, tieRefused, untieRefused]) {
    const text = refused(said(403, "само администраторът"));
    assert.match(text, /^Нямате право да /);
    assert.match(text, /само администраторът/);
    assert.match(refused(said(409, "вече е свързан")), /^Системата не прие това: вече е свързан/);
  }
  assert.match(tieRefused(said(403, "x")), /^Нямате право да свържете вход с лице:/);
  assert.match(untieRefused(said(403, "x")), /^Нямате право да развържете вход:/);
  // every form is offered whoever signs in, and the page gates nothing on a role
  for (const s of [actions, forms, page].map(code)) assert.doesNotMatch(s, /\b(SYS_ADMIN|isAdmin|administers|admin)\b|\.roles\b|\brole\s*(===|!==|\.includes)/);
  assert.match(page, /<RecordForm [^>]*\/>\s*<EndForm [^>]*\/>\s*<TieForm \/>\s*<UntieForm \/>/);
  // a write that got no answer is not said to be done or undone
  assert.equal((actions.match(/unknownOutcome\(/g) ?? []).length, 4);
  // … and each one answers before the data is read, in its own action
  for (const name of ["record", "end", "tie", "untie"]) {
    const body = new RegExp(`export async function ${name}\\(([^]*?)\\n}\\n`).exec(actions)?.[1] ?? "";
    assert.match(body, /if \(!answer\) return \{ values, refused: unknownOutcome\([^]*?if \(!answer\.data\)/, name);
  }
});

test("PM-SEC-001 the sidebar entry equals no card heading, and the forms' buttons are not substrings of one another (the e2e finds a form by its button)", () => {
  const sidebar = readFileSync(new URL("../app/(console)/entrance/EntranceSidebar.tsx", import.meta.url), "utf8");
  assert.match(sidebar, /label: "Мандати и входове", href: "\/entrance\/mandates"/);
  const headings = [...forms.matchAll(/className="wide acct-title">([^<]+)</g)].map((m) => m[1]).concat([...page.matchAll(/className="acct-title">([^<]+)</g)].map((m) => m[1]));
  assert.ok(headings.length >= 5 && !headings.includes("Мандати и входове"), headings.join("|"));
  const buttons = [...forms.matchAll(/: "([^"]+)"\}<\/button>/g)].map((m) => m[1]);
  assert.deepEqual(buttons.slice(-2), ["Свържи входа", "Развържи входа"]);
  for (const a of buttons) for (const b of buttons) assert.ok(a === b || !b.includes(a), `${a} / ${b}`);
});

test("PM-SEC-001 a login is tied to a party and untied as typed — an issuer only when typed, a login already tied or the administrator's own is the API's refusal in its words", () => {
  assert.deepEqual(tieOf({ party: ` ${ID} `, subject: " e2e-spare-1a2b " }), { partyId: ID, subject: "e2e-spare-1a2b" });
  for (const bad of [{ party: "" }, { party: "7" }, { subject: " " }]) assert.ok("refused" in tieOf({ party: ID, subject: "x", ...bad }), JSON.stringify(bad));
  assert.deepEqual(untieOf({ issuer: " ", subject: " s1 " }), { subject: "s1" });
  assert.deepEqual(untieOf({ issuer: " https://idp/realms/r ", subject: "s1" }), { subject: "s1", issuer: "https://idp/realms/r" });
  assert.ok("refused" in untieOf({ issuer: "", subject: "" }));
  // LoginAdministration.kt / Logins.kt: a login or party already tied is a 409, the administrator's own login a 400 — said as the API says it
  const already = "this login is already tied to a party, or the party to another login of this issuer";
  assert.match(tieRefused(said(409, already)), new RegExp(`^Системата не прие това: ${already}`));
  assert.match(tieRefused(said(400, "the administrator's login")), /^Системата не прие това: the administrator's login/);
  // nothing in the tie's or the untie's code gates on who asks, and an untie of a login tied to none is shown as "nothing done"
  assert.doesNotMatch(code(library), /\b(SYS_ADMIN|isAdmin|administers)\b/);
  assert.match(forms, /state\.done\.untied \?/);
});
