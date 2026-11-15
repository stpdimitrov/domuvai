import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/paper-decisions/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "PaperDecisionForm.tsx", "page.tsx"].map(source);
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code

test("PM-SYS-014 a paper decision goes under the form's key: kept when the answer is lost or a 5xx, renewed after a refusal and after a record", () => {
  assert.match(actions, /header: \{ "Idempotency-Key": key \}/);
  assert.match(actions, /refused: unknownOutcome\("вписването на решението"\), key \}/);
  assert.match(actions, /refused: recordRefused\(answer\), key: answer\.response\.status < 500 \? randomUUID\(\) : key \}/);
  assert.match(actions, /return \{ done: answer\.data, key: randomUUID\(\) \}/);
  assert.match(code(form), /<input type="hidden" name="key" value=\{state\.key \?\? mintedKey\} \/>/);
  assert.match(page, /<PaperDecisionForm entrance=\{view\.entrance\.id\} mintedKey=\{randomUUID\(\)\} \/>/);
  assert.equal(actions.match(/api\.POST\(\s*"[^"]*\/paper-decisions"/g)?.length, 1);   // the one write of a paper decision, keyed here
});
