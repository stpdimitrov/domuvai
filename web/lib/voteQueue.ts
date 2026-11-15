/**
 * The device's queue of votes not yet confirmed (Rule: PM-SYS-014) — "Actions queued on a device while offline MUST
 * carry an idempotency key and MUST NOT be shown as performed until the server confirms." A vote the console could not
 * hand to the api — the browser offline, no answer, or a 5xx — waits here under the Idempotency-Key its form minted and
 * is shown as "изпраща се", never as recorded. It is resent under that same key, so the api (VOTE-KEY-01) records it once
 * however often it goes. Only the api's 2xx or its refusal (4xx) takes it off. Pure: the shelf (the browser's
 * localStorage) is handed in, so it is tested on Node's own runner.
 */

/** The kernel's `QUEUED_LABEL_BG` (`kernel/.../OfflineAction.kt`); a test keeps the two equal. */
export const QUEUED_LABEL = "изпраща се";

export type Shelf = { getItem(name: string): string | null; setItem(name: string, value: string): void };

/** A vote as its form sent it, the key it went under, and the login (party id) that sent it — only that login resends it. */
export type QueuedVote = {
  key: string; by: string; entrance: string; assembly: string; item: string; ownerPartyId: string; choice: string; correction: string;
};

/** The api recorded it (2xx), refused it (4xx), or it is not known (a 5xx, or no answer: it may be recorded). */
export type Outcome = { kind: "recorded" } | { kind: "refused"; message: string } | { kind: "unknown" };

const SHELF = "domuvai.queue.votes";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CHOICES: Record<string, string> = { FOR: "за", AGAINST: "против", ABSTAIN: "въздържал се" };

/** Retryable answers — a login expired while offline (401), a timeout (408), too many requests (429) — keep it queued too. */
// Rule: PM-SYS-014
export const outcomeOf = (status: number | null): Outcome["kind"] =>
  status === null || status >= 500 || [401, 408, 429].includes(status) ? "unknown" : status >= 200 && status < 300 ? "recorded" : "refused";

/** The vote a form carries, or `null` when an id, the key, the login or the choice is missing — that one is not queued. */
// Rule: PM-SYS-014
export function voteOf(form: { get(name: string): unknown }, by: string | undefined): QueuedVote | null {
  const text = (name: string) => { const v = form.get(name); return typeof v === "string" ? v.trim() : ""; };
  const vote = { key: text("key"), by: by ?? "", entrance: text("entrance"), assembly: text("assembly"), item: text("item"),
    ownerPartyId: text("ownerPartyId"), choice: text("choice"), correction: text("correction") };
  const ids = [vote.key, vote.by, vote.entrance, vote.assembly, vote.item, vote.ownerPartyId];
  return ids.every((id) => UUID.test(id)) && vote.choice in CHOICES ? vote : null;
}

/** Every vote on the shelf; a shelf unreadable or holding something else is an empty queue. */
export function queued(shelf: Shelf): QueuedVote[] {
  try {
    const kept: unknown = JSON.parse(shelf.getItem(SHELF) ?? "[]");
    return Array.isArray(kept) ? kept.filter((v) => typeof v?.key === "string" && typeof v?.by === "string") : [];
  } catch {
    return [];
  }
}

/** The votes one login queued on one item: the only ones its form shows or resends. */
export const queuedFor = (shelf: Shelf, item: string, by: string | undefined): QueuedVote[] =>
  queued(shelf).filter((v) => v.item === item && v.by === by);

const put = (shelf: Shelf, votes: QueuedVote[]): boolean => {
  try {
    shelf.setItem(SHELF, JSON.stringify(votes));
    return true;
  } catch {
    return false;                                   // private mode or a full shelf: nothing was kept
  }
};

/** Keeps a vote, once per key. `false`: the shelf took nothing, so the vote is not queued. */
// Rule: PM-SYS-014
export const enqueue = (shelf: Shelf, vote: QueuedVote): boolean =>
  put(shelf, [...queued(shelf).filter((v) => v.key !== vote.key), vote]);

/** Off the shelf once the api recorded or refused it; an unknown outcome leaves it queued. */
// Rule: PM-SYS-014
export function settle(shelf: Shelf, key: string, outcome: Outcome): void {
  if (outcome.kind !== "unknown") put(shelf, queued(shelf).filter((v) => v.key !== key));
}

/** A queued vote as its form shows it: whose, how — and "изпраща се", never as recorded. */
// Rule: PM-SYS-014
export const queuedRow = (vote: QueuedVote): { key: string; text: string; label: string } =>
  ({ key: vote.key, text: `${vote.ownerPartyId.slice(0, 8)} · ${CHOICES[vote.choice] ?? vote.choice}`, label: QUEUED_LABEL });

/**
 * Resends the queued votes `which` picks, one at a time under its own key, and settles each. A send that throws (still
 * no signal) is an unknown outcome and ends the round: the rest wait for the next.
 */
// Rule: PM-SYS-014
export async function drain(
  shelf: Shelf, which: (v: QueuedVote) => boolean, send: (v: QueuedVote) => Promise<Outcome>,
): Promise<{ vote: QueuedVote; outcome: Outcome }[]> {
  const settled: { vote: QueuedVote; outcome: Outcome }[] = [];
  for (const vote of queued(shelf).filter(which)) {
    const outcome = await send(vote).catch((): Outcome => ({ kind: "unknown" }));
    settle(shelf, vote.key, outcome);
    settled.push({ vote, outcome });
    if (outcome.kind === "unknown") break;
  }
  return settled;
}
