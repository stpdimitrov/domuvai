import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { AGENT_KIND, FORM, proxyOf, proxyRefused, SCOPE } from "./proxy.ts";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/assembly/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "ProxyForm.tsx", "page.tsx"].map(source);
const library = readFileSync(new URL("./proxy.ts", import.meta.url), "utf8");
const proxyKt = readFileSync(new URL("../../app/src/main/kotlin/zues/app/assembly/Proxy.kt", import.meta.url), "utf8");
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const [AGENT, PRINCIPAL] = ["3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b", "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"];
const said = (status: number, error: string, limit?: number) => ({ error: { error, ...(limit !== undefined && { limit }) }, response: { status } });
const typed = { agentKind: "OWNER", agent: AGENT, form: "WRITTEN", scope: "WHOLE_AGENDA", items: "", principal: "" };

test("PM-GA-009 a proxy goes to the API as typed — for oneself with no principal, the items read for listed items only", () => {
  assert.deepEqual(proxyOf(typed), { agentKind: "OWNER", agentPartyId: AGENT, form: "WRITTEN", items: [], scope: "WHOLE_AGENDA" });
  assert.deepEqual(proxyOf({ ...typed, agent: ` ${AGENT} `, principal: ` ${PRINCIPAL} ` }), { agentKind: "OWNER", agentPartyId: AGENT, form: "WRITTEN", items: [], scope: "WHOLE_AGENDA", principalPartyId: PRINCIPAL });
  // items are sent for LISTED_ITEMS only, as numbers; whether they exist is the API's
  assert.deepEqual(proxyOf({ ...typed, scope: "LISTED_ITEMS", items: "1, 3 ;4" }), { agentKind: "OWNER", agentPartyId: AGENT, form: "WRITTEN", items: [1, 3, 4], scope: "LISTED_ITEMS" });
  assert.deepEqual(proxyOf({ ...typed, items: "7" }), { agentKind: "OWNER", agentPartyId: AGENT, form: "WRITTEN", items: [], scope: "WHOLE_AGENDA" });
  assert.deepEqual((proxyOf({ ...typed, scope: "LISTED_ITEMS" }) as { items: number[] }).items, []);   // none named: the API's 400, in its words
  // an agent that is no id, a list outside the three, items that are no numbers, a principal that is no id: refused before any call
  for (const bad of [{ agent: "" }, { agent: "ап. 1" }, { agentKind: "" }, { agentKind: "SPOUSE" }, { form: "ORAL" }, { form: "constructor" }, { agentKind: "toString" }, { scope: "PART" }, { principal: "Стефан" }, { scope: "LISTED_ITEMS", items: "първа" }]) {
    assert.ok("refused" in proxyOf({ ...typed, ...bad }), JSON.stringify(bad));
  }
  assert.match(actions, /authorisationHash/);
  assert.match(actions, /createHash\("sha256"\)/);                               // the document's hash is taken here, from the file attached
  assert.match(form, /<input type="file" name="authorisation"/);
  assert.match(actions, /document\.size > 0/);                                   // no file: no hash — a proxy appointed in person carries no document
  // the principal is the signed-in person's unless one is typed: the web names none of its own
  assert.doesNotMatch(code(library + actions + form), /principalPartyId\s*[:=]\s*["'`][0-9a-f]/i);
});

test("PM-GA-009 the kinds, scopes and forms offered are the API's AgentKind, ProxyScope and AuthorisationForm, and no other", () => {
  // Proxy.kt: the spec gives all three as bare strings (ADR-013), so the code is the list
  const listed = (name: string) => proxyKt.match(new RegExp(`enum class ${name}\\s*\\{([^}]*)\\}`))?.[1].split(",").map((k) => k.trim()).filter(Boolean);
  assert.deepEqual(Object.keys(AGENT_KIND), listed("AgentKind"));
  assert.deepEqual(Object.keys(SCOPE), listed("ProxyScope"));
  assert.deepEqual(Object.keys(FORM), listed("AuthorisationForm"));
  assert.match(library, /Proxy\.kt/);                                            // the comment that says where the lists come from
  assert.match(form, /Object\.entries\(AGENT_KIND\)[^]*Object\.entries\(FORM\)[^]*Object\.entries\(SCOPE\)/);
  assert.match(page, /import \{ FORM, SCOPE \} from "@\/lib\/proxy"/);           // the register words the same lists
});

test("PM-GA-010 the limit is the API's: no number of the web's own, the one the refusal carries shown", () => {
  for (const s of [library, actions, form].map(code)) assert.doesNotMatch(s, /MAX_PROXIES|maxProxies|\.length\s*[>=<]=?\s*\d|\bcount\(|limit\s*[<>]/);
  const refusal = proxyRefused(said(409, "one person represents at most 5 principals at an assembly (law) — PM-GA-010", 5));
  assert.match(refusal, /^Системата не прие това: one person represents at most 5 principals at an assembly \(law\) — PM-GA-010/);
  assert.match(refusal, /най-много 5/);
  assert.doesNotMatch(proxyRefused(said(409, "this principal already has a proxy at this assembly")), /най-много/);   // no limit named, none shown
});

test("PM-GA-011 the form drives the API's validation path: sent as chosen, the web runs none of its own", () => {
  for (const f of ["WRITTEN", "NOTARISED", "LAWYER_AUTHORISATION"]) assert.equal((proxyOf({ ...typed, agentKind: "THIRD_PARTY", form: f }) as { form: string }).form, f);
  // a relative is not looked up in the book, a lawyer's authorisation is not weighed against a notary's: nothing here ties a form to a kind
  assert.doesNotMatch(code(library + actions + form), /form\s*[=!]==?\s*["']|agentKind\s*[=!]==?\s*["']|HOUSEHOLD_MEMBER\s*&&|THIRD_PARTY\s*&&/);
});

test("PM-SEC-001 who may appoint or record is the API's: its refusal in its words, nothing said to be recorded", () => {
  const forbidden = proxyRefused(said(403, "only the principal or the assembly's officers"));
  assert.match(forbidden, /^Нямате право да впишете пълномощно: only the principal/);
  assert.match(proxyRefused(said(404, "no assembly")), /^Системата не прие това: no assembly/);
  // the web gates nothing on a role: the form is offered, whatever office the signed-in person holds
  for (const s of [actions, form].map(code)) assert.doesNotMatch(s, /\b(OWN|USR|BM|MB|CTL|CSH|isOwner)\b/);
  assert.match(page, /<ProxyForm entrance=\{entrance\} assembly=\{a\.id\} \/>/);
  // a write that got no answer is not said to be done or undone
  assert.match(actions, /unknownOutcome\("вписването на пълномощното"\)/);
  assert.deepEqual(actions.match(/api\.POST\(\s*"[^"]*\/proxies"/g)?.length, 1);
});
