import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/assembly/${f}`, import.meta.url), "utf8");
const [actions, closeForm, mergeForm, protocolForm, page] = ["actions.ts", "CloseForm.tsx", "MergeForm.tsx", "ProtocolForm.tsx", "page.tsx"].map(source);
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code
const act = (name: string) => actions.slice(actions.indexOf(`export async function ${name}(`), actions.indexOf("\nexport ", actions.indexOf(`export async function ${name}(`) + 1));

test("PM-SYS-014 an item's close goes under the form's key: kept when the answer is lost or a 5xx, renewed after a refusal", () => {
  const close = act("closeItem");
  assert.match(close, /\/agenda\/\{itemId\}\/decision", \{\s*params: \{ path: \{[^}]*\}, header: \{ "Idempotency-Key": key \} \}/);
  assert.match(close, /refused: undoneBy\(answer, "приключите точката"\), values, key: randomUUID\(\)/);
  assert.match(close, /unknownOutcome\("приключването на точката"\), values, key \}/);
  assert.match(code(closeForm), /<input type="hidden" name="key" value=\{state\.key \?\? mintedKey\} \/>/);
  assert.match(page, /<CloseForm entrance=\{entrance\} assembly=\{assembly\} item=\{item\.id\} mintedKey=\{randomUUID\(\)\} \/>/);
  assert.equal(actions.match(/api\.POST\(\s*"[^"]*\/agenda\/\{itemId\}\/decision"/g)?.length, 1);   // the one write of a close, keyed here
});

test("PM-SYS-014 the absentee merge goes under the form's key: kept when the answer is lost or a 5xx, renewed after a refusal", () => {
  const merge = act("mergeBallots");
  assert.match(merge, /\/absentee-merge", \{\s*params: \{ path: \{[^}]*\}, header: \{ "Idempotency-Key": key \} \}/);
  assert.match(merge, /refused: undoneBy\(answer, "слеете неприсъствените бюлетини"\), key: randomUUID\(\)/);
  assert.match(merge, /unknownOutcome\("сливането на бюлетините"\), key \}/);
  assert.match(code(mergeForm), /<input type="hidden" name="key" value=\{state\.key \?\? mintedKey\} \/>/);
  assert.match(page, /<MergeForm entrance=\{entrance\} assembly=\{assembly\} item=\{item\.id\} mintedKey=\{randomUUID\(\)\} \/>/);
  assert.equal(actions.match(/api\.POST\(\s*"[^"]*\/absentee-merge"/g)?.length, 1);
});

test("PM-SYS-014 the protocol's posting goes under the form's key: kept when the answer is lost or a 5xx, renewed after a refusal", () => {
  const post = act("postProtocol");
  assert.match(post, /\/protocol-posting", \{\s*params: \{ path: \{[^}]*\}, header: \{ "Idempotency-Key": key \} \}/);
  assert.match(post, /refused: undoneBy\(answer, "впишете обявяването на протокола"\), values, key: randomUUID\(\)/);
  assert.match(post, /unknownOutcome\("вписването на обявяването"\), values, key \}/);
  assert.match(code(protocolForm), /<input type="hidden" name="key" value=\{state\.key \?\? mintedKey\} \/>/);
  assert.match(page, /<ProtocolForm entrance=\{entrance\} assembly=\{a\.id\} mintedKey=\{randomUUID\(\)\} \/>/);
  assert.equal(actions.match(/api\.POST\(\s*"[^"]*\/protocol-posting"/g)?.length, 1);
});
