import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";
import { entranceOf, entranceRefused, ID_TYPE, MANAGEMENT_FORM, partyOf, partyRefused } from "./registry.ts";

const read = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");
const [actions, forms, page] = ["actions.ts", "RegistryForms.tsx", "page.tsx"].map((f) => read(`../app/(console)/(firm)/registry/${f}`));
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const ID = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
const said = (status: number, error: string) => ({ error: { error }, response: { status } });

test("PM-ORG-001 an entrance is sent as typed — a new building's address or the building it joins, how it is run from the database's closed list", () => {
  // V1__init.sql: the CHECK on management_form — the same three, in the same order
  const migrations = new URL("../../app/src/main/resources/db/migration/", import.meta.url);
  const checks = readdirSync(migrations).flatMap((f) => [...read(`../../app/src/main/resources/db/migration/${f}`).matchAll(/management_form[^\n]*IN \(([^)]*)\)/g)]);
  assert.equal(checks.length, 1, "management_form is checked in one place");
  assert.deepEqual(Object.keys(MANAGEMENT_FORM), checks[0][1].split(",").map((s) => s.trim().replace(/'/g, "")));
  assert.deepEqual(entranceOf({ label: " А ", managementForm: "GA", address: " ул. Раковски 1 ", condominiumId: "" }), { label: "А", managementForm: "GA", address: "ул. Раковски 1" });
  assert.deepEqual(entranceOf({ label: "Б", managementForm: "ASSOCIATION", address: "", condominiumId: ` ${ID} ` }), { label: "Б", managementForm: "ASSOCIATION", condominiumId: ID });
  for (const bad of [{ label: " " }, { managementForm: "" }, { managementForm: "TENANT" }, { condominiumId: "7" }]) assert.ok("refused" in entranceOf({ label: "А", managementForm: "GA", address: "x", condominiumId: "", ...bad }), JSON.stringify(bad));
  // the API's rule that exactly one of the two is given is the API's: both, or neither, is sent as typed
  assert.ok(!("refused" in entranceOf({ label: "А", managementForm: "GA", address: "x", condominiumId: ID })));
  assert.ok(!("refused" in entranceOf({ label: "А", managementForm: "GA", address: "", condominiumId: "" })));
});

test("PM-BOOK-011 a party is sent with an optional identity that is not kept in the form and not shown back", () => {
  // IdType in Party.kt: the same four, in the same order
  const kotlin = read("../../app/src/main/kotlin/zues/app/registry/Party.kt");
  assert.deepEqual(Object.keys(ID_TYPE), /enum class IdType \{([^}]*)\}/.exec(kotlin)?.[1].split(",").map((s) => s.trim()));
  assert.deepEqual(partyOf({ fullName: " Иван Петров ", idType: "", idValue: "" }), { fullName: "Иван Петров" });
  assert.deepEqual(partyOf({ fullName: "Иван", idType: "EGN", idValue: " 7501010001 " }), { fullName: "Иван", idType: "EGN", idValue: "7501010001" });
  for (const bad of [{ fullName: " " }, { idType: "SSN" }]) assert.ok("refused" in partyOf({ fullName: "Иван", idType: "", idValue: "", ...bad }), JSON.stringify(bad));
  // the value is dropped from what the form holds after an answer, and the success line names the person, never the number
  assert.match(code(actions), /const \{ idValue: _kept, \.\.\.values \} = typed;/);
  assert.doesNotMatch(code(forms), /defaultValue=\{v\.idValue\}|state\.done\.idValue/);
  assert.match(code(actions), /done: \{ partyId: answer\.data\.partyId, fullName: body\.fullName \}/);
});

test("PM-SEC-001 who registers is the API's: its 403 in its words, no role gate, no outcome guessed, every form offered", () => {
  for (const [refused, act] of [[entranceRefused, "регистрирате вход"], [partyRefused, "регистрирате лице"]] as const) {
    assert.match(refused(said(403, "само администраторът")), new RegExp(`^Нямате право да ${act}: само администраторът`));
    assert.match(refused(said(400, "give an address")), /^Системата не прие това: give an address/);
  }
  for (const s of [actions, forms, page].map(code)) assert.doesNotMatch(s, /\b(SYS_ADMIN|isAdmin|administers|admin)\b|\.roles\b|\brole\s*(===|!==|\.includes)/);
  assert.match(page, /<EntranceForm \/>\s*<PartyForm \/>/);
  assert.equal((actions.match(/unknownOutcome\(/g) ?? []).length, 2);
  // the sidebar entry equals no card heading, and the buttons are not substrings of one another (the e2e finds a form by its button)
  const sidebar = read("../app/(console)/(firm)/FirmSidebar.tsx");
  const [, entry] = /label: "([^"]+)", href: "\/registry"/.exec(sidebar) ?? [];
  const headings = [...forms.matchAll(/className="wide acct-title">([^<]+)</g)].map((m) => m[1]);
  assert.ok(entry && headings.length === 2 && !headings.includes(entry), headings.join("|"));
  const buttons = [...forms.matchAll(/: "([^"]+)"\}<\/button>/g)].map((m) => m[1]);
  assert.deepEqual(buttons, ["Регистрирай входа", "Регистрирай лицето"]);
  for (const a of buttons) for (const b of buttons) assert.ok(a === b || !b.includes(a), `${a} / ${b}`);
});

test("PM-SEC-001 the empty-list words (#363) are one component, said no more than the API says, and tell nobody to register an entrance", () => {
  const consoleDir = new URL("../app/(console)/", import.meta.url);
  const component = read("../app/(console)/NoEntrance.tsx");
  assert.match(component, /не заемате роля в нито един/);
  assert.doesNotMatch(code(component), /Регистрирайте/);
  const walk = (dir: URL): string[] => readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(new URL(`${e.name}/`, dir)) : [new URL(e.name, dir).pathname]));
  const old = walk(consoleDir).filter((f) => /\.tsx?$/.test(f) && readFileSync(f, "utf8").includes("Няма регистриран вход"));
  assert.deepEqual(old, [], "the old words are gone from every screen");
  const used = walk(consoleDir).filter((f) => /page\.tsx$/.test(f) && readFileSync(f, "utf8").includes('kind === "none" && <NoEntrance />'));
  assert.equal(used.length, 19);
});
