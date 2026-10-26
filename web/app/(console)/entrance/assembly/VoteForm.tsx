"use client";

import { useActionState } from "react";
import { castVote, type VoteState } from "./actions";

/**
 * Recording an owner's vote on one item of the agenda (PM-VOTE-001, PM-VOTE-014, PM-ORG-005): the owner, by party id
 * until #300, and how they voted; a reason only when it corrects a vote already recorded — the api then records a
 * reversal, never an edit. It works without a script: the server's action answers either with the assembly's page,
 * the rows the api recorded, or with the api's refusal and the fields as they were filled.
 */
// Rule: PM-VOTE-001
// Rule: PM-VOTE-014
// Rule: PM-ORG-005
export default function VoteForm({ entrance, assembly, item }: { entrance: string; assembly: string; item: string }) {
  const [state, act, pending] = useActionState<VoteState, FormData>(castVote, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="item" value={item} />
      <label>
        Собственик
        <input type="text" name="ownerPartyId" defaultValue={v.ownerPartyId} required placeholder="идентификатор на собственика" maxLength={36} />
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
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши гласа"}</button>
        <span className="as-hint">
          Гласува присъстващ собственик — лично или чрез пълномощник. За кои обекти и с каква тежест, дали заседанието е открито и
          точката не е приключена — преценява системата. Записан глас не се променя: поправката се записва като нов ред.
        </span>
      </div>
    </form>
  );
}
