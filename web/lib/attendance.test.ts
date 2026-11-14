import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const source = (f: string) => readFileSync(new URL(`../app/(console)/entrance/assembly/${f}`, import.meta.url), "utf8");
const [actions, form, page] = ["actions.ts", "AttendanceForm.tsx", "page.tsx"].map(source);
const code = (s: string) => s.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, "");          // the rules are quoted in the comments; what is scanned is the code

test("PM-SYS-014 the register line goes under the form's key: kept when the answer is lost or a 5xx, renewed after a refusal", () => {
  assert.match(actions, /header: \{ "Idempotency-Key": key \}/);
  assert.match(actions, /unknownOutcome\("вписването"\), values, key \}/);
  assert.match(actions, /refused: undoneBy\(answer, "впишете лицето в присъствения списък"\), values, key: answer\.response\.status < 500 \? randomUUID\(\) : key/);
  assert.match(code(form), /<input type="hidden" name="key" value=\{state\.key \?\? mintedKey\} \/>/);
  assert.match(page, /<AttendanceForm entrance=\{entrance\} assembly=\{a\.id\} mode=\{a\.mode\} parties="parties-attendance" mintedKey=\{randomUUID\(\)\} \/>/);
  assert.equal(actions.match(/api\.POST\(\s*"[^"]*\/attendance"/g)?.length, 1);        // the one write of the register that is keyed here
});
