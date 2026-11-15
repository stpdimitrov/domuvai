import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { conveningOf, conveningRefused, MODE, openRefused, petitionIdOf, signingRefused, subjectOf } from "./petition.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/petitions/${f}`, import.meta.url), "utf8");
const [actions, forms, page] = ["actions.ts", "PetitionForms.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./petition.ts", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const ID = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
const said = (status: number, error: string) => ({ error: { error }, response: { status } });

test("PM-GA-003 a petition is opened on a subject, found by its id, and convened on as typed — the web weighs and decides nothing", () => {
  assert.deepEqual(subjectOf("  Ремонт на покрива "), { subject: "Ремонт на покрива" });
  assert.ok("refused" in subjectOf("   "));
  assert.equal(petitionIdOf(` ${ID} `), ID);
  for (const typed of ["", undefined, "1", "ап. 1"]) assert.equal(petitionIdOf(typed), null, String(typed));
  const typed = { demandUnmet: " не отговориха ", mode: "HYBRID", place: " входът ", scheduledAt: "2026-12-01T18:30", urgent: false, urgencyReason: " " };
  assert.deepEqual(conveningOf(typed), { demandUnmet: "не отговориха", mode: "HYBRID", place: "входът", scheduledAt: "2026-12-01T16:30:00Z", urgent: false });
  assert.deepEqual(conveningOf({ ...typed, urgent: true, urgencyReason: "авария" }), { demandUnmet: "не отговориха", mode: "HYBRID", place: "входът", scheduledAt: "2026-12-01T16:30:00Z", urgent: true, urgencyReason: "авария" });
  for (const bad of [{ demandUnmet: "" }, { place: "" }, { mode: "PHONE" }, { scheduledAt: "" }, { scheduledAt: "2026-02-30T10:00" }]) assert.ok("refused" in conveningOf({ ...typed, ...bad }), JSON.stringify(bad));
  assert.deepEqual(Object.keys(MODE), ["IN_PERSON", "VIDEO", "HYBRID"]);
  // the share and the threshold are the API's (PM-GA-003 ⚠): no percentage, no sum and no comparison in the page's code
  for (const s of [library, actions, forms, page].map(code)) assert.doesNotMatch(s, /\b20\b|0\.2\b|parseFloat|Number\(|heldPct\s*[<>]|reduce\(/);
  assert.match(page, /\{p\.heldPct\}%[^]*\{p\.thresholdPct\}%/);
  assert.match(page, /p\.unlocked \?/);
});

test("PM-GA-003 the petition's weighing is shown as the API answered — what it cannot weigh, and a threshold it has not confirmed", () => {
  assert.match(page, /p\.cannotWeigh\.join/);
  assert.match(page, /p\.thresholdVerified \? "" : /);
  assert.match(page, /p\.derivedParts/);
});

test("PM-SEC-001 who may open, sign or convene is the API's: its refusal in its words, nothing said to be done", () => {
  for (const refused of [openRefused, signingRefused, conveningRefused]) {
    const text = refused(said(403, "само собственик"));
    assert.match(text, /^Нямате право да /);
    assert.match(text, /само собственик/);
    assert.match(refused(said(409, "вече е подписана")), /^Системата не прие това: вече е подписана/);
  }
  // the web gates nothing on a role: every form is offered, whatever the weighing says
  for (const s of [actions, forms, page].map(code)) assert.doesNotMatch(s, /\b(OWN|BM|MB|CTL|isOwner)\b/);
  assert.match(page, /<SignForm [^>]*\/>\s*<ConveneOnPetitionForm/);
  // a write that got no answer is not said to be done or undone
  assert.equal((actions.match(/unknownOutcome\(/g) ?? []).length, 3);
});
