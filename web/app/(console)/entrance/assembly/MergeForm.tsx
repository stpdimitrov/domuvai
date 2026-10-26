"use client";

import { useActionState } from "react";
import { mergeBallots, type MergeState } from "./actions";

/**
 * Merging the absent owners' ballots into an item's tally (PM-VOTE-013): the api tallies again and stores a decision
 * superseding the provisional one, once, so the person confirms it first. It works without a script: the server's
 * action answers either with the assembly's page, the merged decision as the api stored it, or with the api's refusal.
 */
// Rule: PM-VOTE-013
export default function MergeForm({ entrance, assembly, item }: { entrance: string; assembly: string; item: string }) {
  const [state, act, pending] = useActionState<MergeState, FormData>(mergeBallots, {});
  return (
    <form action={act} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="item" value={item} />
      <label className="check wide">
        <input type="checkbox" name="final" required /> Сливам неприсъствените бюлетини — решението се преброява отново и сливането не се повтаря
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Слива се…" : "Слей бюлетините"}</button>
        <span className="as-hint">Дали 7-дневният срок е изтекъл и кой може да слее бюлетините — преценява системата. Без това решението остава предварително.</span>
      </div>
    </form>
  );
}
