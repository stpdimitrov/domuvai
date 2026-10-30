import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { partyOptions, withMyself } from "./partyOption.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/${f}`, import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const [BORIS, ANA, IVA] = ["3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b", "9a1c7d2e-0b3f-4c5d-8e6a-7f8091a2b3c4", "c0d1e2f3-1111-4222-8333-444455556666"];
const row = (partyId: string, partyName: string, titleRole = "OWN") => ({ partyId, partyName, titleRole, unitId: "u", share: 0.5 });

/** Each form, the page that sets it beside its picker, the fields the picker fills, and the action that reads them. */
const FORMS = [
  { form: "assembly/AttendanceForm.tsx", page: "assembly/page.tsx", fields: ["attendeePartyId"], action: "assembly/actions.ts" },
  { form: "assembly/VoteForm.tsx", page: "assembly/page.tsx", fields: ["ownerPartyId"], action: "assembly/actions.ts" },
  { form: "assembly/BallotForm.tsx", page: "assembly/page.tsx", fields: ["ownerPartyId"], action: "assembly/actions.ts" },
  { form: "assembly/NoticeForm.tsx", page: "assembly/page.tsx", fields: ["coSignatoryPartyId"], action: "assembly/actions.ts" },
  { form: "assembly/ProxyForm.tsx", page: "assembly/page.tsx", fields: ["agentPartyId", "principalPartyId"], action: "assembly/actions.ts" },
  { form: "declarations/DeclarationForm.tsx", page: "declarations/page.tsx", fields: ["declarant"], action: "declarations/actions.ts" },
  { form: "units/TitleForm.tsx", page: "units/page.tsx", fields: ["party"], action: "units/actions.ts" },
  { form: "fund/handover/HandoverForm.tsx", page: "fund/handover/page.tsx", fields: ["outgoing", "incoming"], action: "fund/handover/actions.ts" },
];

test("PM-BOOK-006 an option is one row's name beside that row's own 8-character id, and sends the whole id", () => {
  assert.deepEqual(partyOptions([row(BORIS, "Борис Петров"), row(ANA, "Ана Иванова")]), [
    { partyId: ANA, label: "Ана Иванова 9a1c7d2e" },
    { partyId: BORIS, label: "Борис Петров 3f2b8c1e" },
  ]);
});

test("PM-BOOK-006 a party under several units or roles stands once; a role asked keeps that role's rows only", () => {
  assert.deepEqual(partyOptions([row(BORIS, "Борис Петров"), row(BORIS, "Борис Петров"), row(BORIS, "Борис Петров", "USR")]).map((o) => o.partyId), [BORIS]);
  assert.deepEqual(partyOptions([row(BORIS, "Борис Петров", "USR"), row(ANA, "Ана Иванова"), row(IVA, "Ива Тодорова", "USR")], "OWN").map((o) => o.partyId), [ANA]);
  assert.deepEqual(partyOptions([row(BORIS, "Борис Петров", "USR"), row(BORIS, "Борис Петров")], "OWN").map((o) => o.partyId), [BORIS], "the role is the row's, not the party's first");
});

test("PM-SEC-002 an empty or absent list offers nothing, and a row served with no name offers its id alone", () => {
  assert.deepEqual(partyOptions([]), []);
  assert.deepEqual(partyOptions(undefined), []);
  assert.deepEqual(partyOptions([{ partyId: BORIS }]), [{ partyId: BORIS, label: "3f2b8c1e" }]);
  assert.deepEqual(Object.keys(partyOptions([row(BORIS, "Борис Петров")])[0]), ["partyId", "label"], "no share, no unit, no role goes to the browser");
});

test("PM-SEC-002 myself is the login's own id, first and once, with no name; a login tied to no party is offered nothing", () => {
  const read = partyOptions([row(ANA, "Ана Иванова"), row(BORIS, "Борис Петров")]);
  assert.deepEqual(withMyself([], BORIS), [{ partyId: BORIS, label: "Аз 3f2b8c1e" }]);
  assert.deepEqual(withMyself(read, BORIS).map((o) => o.label), ["Аз 3f2b8c1e", "Ана Иванова 9a1c7d2e"]);
  assert.deepEqual(withMyself(read, undefined), read);
});

test("PM-BOOK-006 each form's field names its picker's list, the fields stay the ones its action reads; forms 6 and 7 stay typed", () => {
  for (const { form, page, fields, action } of FORMS) {
    const [text, shown, acting] = [code(source(form)), code(source(page)), code(source(action))];
    for (const field of fields) {
      assert.match(text, new RegExp(`name="${field}" list=\\{parties\\}`), `${form}: ${field}`);
      assert.match(acting, new RegExp(`"${field}"`), `${action} no longer reads ${field}`);
    }
    assert.doesNotMatch(text, /PartyPicker|readParties/, `${form}: a picker inside the form would submit it`);
    assert.match(shown, /<PartyPicker\b/, `${page}: no picker`);
  }
  assert.doesNotMatch(source("mandates/MandateForms.tsx"), /\blist=|PartyPicker/, "the administrator reads no book");
  assert.doesNotMatch(source("mandates/page.tsx"), /PartyPicker/);
});

test("PM-BOOK-007 the owners are read on the click, by one action, and no page with a picker reads them on load", () => {
  for (const page of ["assembly/page.tsx", "declarations/page.tsx", "fund/handover/page.tsx"]) {
    assert.doesNotMatch(code(source(page)), /\/owners"/, `${page} reads the owners on load`);
  }
  assert.match(code(source("parties.ts")), /entranceId\}\/owners"/);
  assert.doesNotMatch(code(source("parties.ts")), /\/book"|purpose/, "the owners read needs no typed purpose, and the book is not its source");
  const picker = code(source("PartyPicker.tsx"));
  assert.match(picker, /useActionState<PartiesState, FormData>\(readParties, \{\}\)/);
  assert.doesNotMatch(picker, /useEffect|localStorage|sessionStorage|formAction/, "nothing read on mount, nothing kept in the browser, no field of the form submitted");
});

test("PM-GA-007 the co-signatory is picked from the owners as of the posting day; the handover from the mandates as of its day", () => {
  const page = code(source("assembly/page.tsx"));
  assert.match(page, /<PartyPicker id="parties-notice" entrance=\{entrance\} role="OWN" dayField="postedAt" \/>/);
  assert.match(page, /id=\{`parties-vote-\$\{item\.id\}`\} entrance=\{entrance\} role="OWN" myself=\{myself\}/);
  assert.match(page, /id=\{`parties-ballot-\$\{item\.id\}`\} entrance=\{entrance\} role="OWN" \/>/);
  assert.match(page, /id="parties-attendance" entrance=\{entrance\} myself=\{myself\}/);
  assert.match(code(source("fund/handover/page.tsx")), /<PartyPicker id="parties-handover" entrance=\{view\.entrance\.id\} source="mandates" dayField="handoverOn"/);
});
