import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { drain, enqueue, outcomeOf, QUEUED_LABEL, queued, queuedFor, queuedRow, settle, voteOf, type Outcome, type QueuedVote, type Shelf } from "./voteQueue.ts";

const [KEY, KEY2, ME, OTHER, E, A, I, OWNER] = [
  "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b",
  "9a1c7d2e-0b3f-4c5d-8e6a-7f8091a2b3c4", "c0d1e2f3-1111-4222-8333-444455556666", "d0d1e2f3-1111-4222-8333-444455556666",
  "e0d1e2f3-1111-4222-8333-444455556666", "f0d1e2f3-1111-4222-8333-444455556666"];
const shelf = (): Shelf & { m: Map<string, string> } => { const m = new Map<string, string>(); return { m, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v) }; };
const vote = (key = KEY, by = ME): QueuedVote => ({ key, by, entrance: E, assembly: A, item: I, ownerPartyId: OWNER, choice: "FOR", correction: "" });
const form = (o: Record<string, string>) => ({ get: (n: string) => o[n] ?? null });
const source = (f: string) => readFileSync(new URL(f, import.meta.url), "utf8");

test("PM-SYS-014 a queued vote's label is \"изпраща се\" — the kernel's text — and it is not shown as recorded", () => {
  assert.equal(QUEUED_LABEL, source("../../kernel/src/main/kotlin/zues/kernel/OfflineAction.kt").match(/QUEUED_LABEL_BG: String = "([^"]*)"/)?.[1]);
  const s = shelf();
  assert.ok(enqueue(s, vote()));
  assert.deepEqual(queuedFor(s, I, ME).map(queuedRow), [{ key: KEY, text: "f0d1e2f3 · за", label: "изпраща се" }]);
  const form = source("../app/(console)/entrance/assembly/VoteForm.tsx");
  assert.match(form, /\{row\.text\} — \{row\.label\}/, "the form shows each queued vote with its label");
  assert.doesNotMatch(form.replace(/\/\*[^]*?\*\/|\/\/.*$/gm, ""), /redirect|router\.push/, "the form never moves on as if the vote were recorded; only the api's answer does");
  assert.deepEqual(queuedFor(s, I, OTHER), [], "another login neither sees nor resends it");
});

test("PM-SYS-014 a vote resent twice goes under one key, and the api records it once", async () => {
  const s = shelf();
  const recorded = new Map<string, QueuedVote>();                  // the api: one record per Idempotency-Key (VOTE-KEY-01)
  const sent: string[] = [];
  let lose = true;
  const api = async (v: QueuedVote): Promise<Outcome> => {
    sent.push(v.key);
    if (!recorded.has(v.key)) recorded.set(v.key, v);
    if (lose) { lose = false; return { kind: "unknown" }; }        // recorded, but the answer was lost
    return { kind: "recorded" };                                    // the replay answers the first record
  };
  enqueue(s, vote());
  enqueue(s, vote());                                               // queued twice is still one
  assert.equal(queued(s).length, 1);
  await drain(s, () => true, api);
  assert.equal(queued(s).length, 1, "a lost answer keeps it queued");
  await drain(s, () => true, api);
  assert.deepEqual(sent, [KEY, KEY]);
  assert.equal(recorded.size, 1);
  assert.deepEqual(queued(s), []);
  const actions = source("../app/(console)/entrance/assembly/actions.ts");
  assert.match(actions, /const answer = await postVote\(v, vote\.key\);/, "the resend goes under the key the vote was queued with");
  assert.match(actions, /header: \{ "Idempotency-Key": key \}/);
  assert.match(source("../app/(console)/entrance/assembly/VoteForm.tsx"), /drain\(localStorage, \(q\) => q\.item === item && q\.by === by, resendVote\)/);
});

test("PM-SYS-014 a 4xx clears the vote from the queue", async () => {
  const s = shelf();
  enqueue(s, vote());
  enqueue(s, vote(KEY2));
  const done = await drain(s, (v) => v.key === KEY, async () => ({ kind: "refused", message: "Точката е приключена." }));
  assert.deepEqual(done.map((d) => d.outcome), [{ kind: "refused", message: "Точката е приключена." }]);
  assert.deepEqual(queued(s).map((v) => v.key), [KEY2], "only the refused vote leaves, the one not picked stays");
  assert.equal(outcomeOf(409), "refused");
  assert.equal(outcomeOf(400), "refused");
  assert.equal(outcomeOf(403), "refused");
  assert.equal(outcomeOf(201), "recorded");
});

test("PM-SYS-014 a 5xx or a lost answer keeps the vote queued, and the round stops", async () => {
  const s = shelf();
  enqueue(s, vote());
  enqueue(s, vote(KEY2));
  const tried: string[] = [];
  await drain(s, () => true, async (v) => { tried.push(v.key); throw new TypeError("Failed to fetch"); });
  assert.deepEqual(tried, [KEY], "no signal: the rest wait for the next round");
  await drain(s, () => true, async () => ({ kind: "unknown" }));
  settle(s, KEY, { kind: "unknown" });
  assert.deepEqual(queued(s).map((v) => v.key), [KEY, KEY2]);
  assert.equal(outcomeOf(500), "unknown");
  assert.equal(outcomeOf(503), "unknown");
  assert.equal(outcomeOf(null), "unknown");
  for (const retry of [401, 408, 429]) assert.equal(outcomeOf(retry), "unknown");
});

test("PM-SYS-014 a form with no login, no key or a stray choice is not queued; a shelf that takes nothing says so", () => {
  const filled = { key: KEY, entrance: E, assembly: A, item: I, ownerPartyId: OWNER, choice: "AGAINST", correction: " " };
  assert.deepEqual(voteOf(form(filled), ME), { ...filled, by: ME, correction: "" });
  assert.equal(voteOf(form(filled), undefined), null);
  assert.equal(voteOf(form({ ...filled, key: "" }), ME), null);
  assert.equal(voteOf(form({ ...filled, choice: "MAYBE" }), ME), null);
  const full: Shelf = { getItem: () => null, setItem: () => { throw new Error("QuotaExceededError"); } };
  assert.equal(enqueue(full, vote()), false);
  assert.deepEqual(queued({ getItem: () => "{not json", setItem: () => {} }), []);
});
