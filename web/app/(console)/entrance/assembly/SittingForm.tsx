"use client";

import { useActionState } from "react";
import { sofiaTime } from "@/lib/sofia";
import { openSitting, type SittingState } from "./actions";

/**
 * Weighing the quorum of the sitting at hand and opening it (PM-GA-012, PM-GA-013, PM-GA-014): one act, no fields —
 * the register as it stands is what is weighed. It works without a script: the server's action answers either with
 * the assembly's page, the sitting among those weighed, or with the api's refusal. The sitting's number and the hour
 * it opens from are the meter's, as the api serves them: after a sitting weighed short, the next one and its hour.
 */
// Rule: PM-GA-012
// Rule: PM-GA-013
// Rule: PM-GA-014
export default function SittingForm({ entrance, assembly, at }: { entrance: string; assembly: string; at?: { sessionNo: number; opensFrom: string } }) {
  const [state, act, pending] = useActionState<SittingState, FormData>(openSitting, {});
  return (
    <form action={act} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="weigh" value="sitting" />
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>
          {pending ? "Претегля се…" : `Претегли кворума и открий заседание${at ? ` № ${at.sessionNo}` : ""}`}
        </button>
        <span className="as-hint">
          {at && <>Заседанието може да започне от {sofiaTime(at.opensFrom)}. </>}Кворумът се претегля веднъж за заседанието, по
          присъствения списък в този момент. Дали е настъпил часът, кое заседание е и какъв е прагът — преценява системата по закона.
        </span>
      </div>
    </form>
  );
}
