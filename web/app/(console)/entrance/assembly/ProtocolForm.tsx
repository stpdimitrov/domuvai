"use client";

import { useActionState } from "react";
import { postProtocol, type ProtocolState } from "./actions";

/**
 * Entering the day the assembly's protocol was posted (PM-GA-023): the 30 days to seek annulment of its decisions run
 * from it, and it is entered once, so the person confirms it first. It works without a script: the server's action
 * answers either with the assembly's page, each decision showing the posting and its windows as the api serves them,
 * or with the api's refusal and the day as it was filled.
 */
// Rule: PM-GA-023
export default function ProtocolForm({ entrance, assembly }: { entrance: string; assembly: string }) {
  const [state, act, pending] = useActionState<ProtocolState, FormData>(postProtocol, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(state)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <label>
        Протоколът е обявен на
        <input type="date" name="postedOn" defaultValue={v.postedOn} required />
      </label>
      <label className="check wide">
        <input type="checkbox" name="final" required /> Вписвам деня на обявяване — вписва се веднъж и от него тече срокът за обжалване
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши обявяването"}</button>
        <span className="as-hint">Дали денят е между заседанието и днес и кой може да го впише — преценява системата. Без него решенията остават предварителни.</span>
      </div>
    </form>
  );
}
