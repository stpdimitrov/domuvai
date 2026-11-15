"use client";

import { useActionState, useCallback, useEffect, useRef, useState } from "react";
import { unstable_rethrow, useRouter } from "next/navigation";
import { castVote, resendVote, type VoteState } from "./actions";
import { unknownOutcome } from "@/lib/refusal";
import { drain, enqueue, queuedFor, queuedRow, voteOf, type QueuedVote } from "@/lib/voteQueue";

const NONE: VoteState = {};

/**
 * Recording an owner's vote on one item of the agenda (PM-VOTE-001, PM-VOTE-014, PM-ORG-005): the owner, by party id
 * or picked from the list beside the form, and how they voted; a reason only when it corrects a vote already recorded — the api then records a
 * reversal, never an edit. It works without a script: the server's action answers either with the assembly's page,
 * the rows the api recorded, or with the api's refusal and the fields as they were filled.
 * Rule: PM-SYS-014 — with a script, a vote sent while the browser is offline, or whose send got no answer or a 5xx, is
 * kept on this device under its key (`lib/voteQueue.ts`) and shown as "изпраща се", never as recorded; the form takes a
 * new key. It is resent under its own key on reconnect and on page load, by the login that sent it only; a 2xx refreshes
 * the page — the api's rows are what shows it recorded — a refusal leaves the queue and is shown, the rest stay queued.
 */
// Rule: PM-VOTE-001
// Rule: PM-VOTE-014
// Rule: PM-ORG-005
// Rule: PM-SYS-014
export default function VoteForm({ entrance, assembly, item, parties, mintedKey, by }: { entrance: string; assembly: string; item: string; parties: string; mintedKey: string; by?: string }) {
  const router = useRouter();
  const [live, setLive] = useState(false);                     // hydrated: until then the form posts to the server as before
  const [kept, setKept] = useState<QueuedVote[]>([]);
  const [dropped, setDropped] = useState<string[]>([]);       // the api's refusals of resent votes
  const draining = useRef(false);

  // Rule: PM-SYS-014
  const keep = (vote: QueuedVote, form: FormData): VoteState =>
    enqueue(localStorage, vote)
      ? (setKept(queuedFor(localStorage, item, by)), { key: crypto.randomUUID() })
      : { refused: unknownOutcome("записването на гласа"), values: Object.fromEntries([...form].map(([n, v]) => [n, String(v)])), key: vote.key };

  const [served, serve, serving] = useActionState<VoteState, FormData>(castVote, NONE);
  const [sent, send, sending] = useActionState<VoteState, FormData>(async (prev, form) => {
    setDropped([]);
    const vote = voteOf(form, by);
    if (vote && !navigator.onLine) return keep(vote, form);
    try {
      const answer = await castVote(prev, form);
      return vote && answer.unsettled ? keep(vote, form) : answer;
    } catch (e) {
      unstable_rethrow(e);                                     // a recorded vote's redirect goes on to the page
      if (vote) return keep(vote, form);
      return { ...prev, refused: unknownOutcome("записването на гласа") };
    }
  }, NONE);
  const state = sent === NONE ? served : sent;
  const v = state.values ?? {};

  // Rule: PM-SYS-014
  const flush = useCallback(async () => {
    if (!by || draining.current || !navigator.onLine) return;
    draining.current = true;
    try {
      const done = await drain(localStorage, (q) => q.item === item && q.by === by, resendVote);
      setKept(queuedFor(localStorage, item, by));
      setDropped((d) => [...d, ...done.flatMap(({ outcome: o }) => (o.kind === "refused" ? [o.message] : []))]);
      if (done.some(({ outcome }) => outcome.kind === "recorded")) router.refresh();
    } finally {
      draining.current = false;
    }
  }, [by, item, router]);

  useEffect(() => {
    setLive(true);
    setKept(queuedFor(localStorage, item, by));
    void flush();
    window.addEventListener("online", flush);
    return () => window.removeEventListener("online", flush);
  }, [by, item, flush]);

  return (
    <form action={live ? send : serve} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="item" value={item} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <label>
        Собственик
        <input type="text" name="ownerPartyId" list={parties} defaultValue={v.ownerPartyId} required placeholder="идентификатор на собственика" maxLength={36} />
      </label>
      <label>
        Глас
        <select name="choice" defaultValue={v.choice ?? "FOR"}>
          <option value="FOR">за</option>
          <option value="AGAINST">против</option>
          <option value="ABSTAIN">въздържал се</option>
        </select>
      </label>
      <label className="wide">
        Поправка — защо (само ако гласът на собственика вече е записан)
        <input type="text" name="correction" defaultValue={v.correction} maxLength={500} placeholder="причина за поправката" />
      </label>
      {kept.map(queuedRow).map((row) => <div className="as-hint wide" role="status" key={row.key}>{row.text} — {row.label}</div>)}
      {dropped.map((m, i) => <div className="as-refused" role="alert" key={i}>{m}</div>)}
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={serving || sending}>{serving || sending ? "Записва се…" : "Запиши гласа"}</button>
        <span className="as-hint">
          Гласува присъстващ собственик — лично или чрез пълномощник. За кои обекти и с каква тежест, дали заседанието е открито и
          точката не е приключена — преценява системата. Записан глас не се променя: поправката се записва като нов ред.
        </span>
      </div>
    </form>
  );
}
