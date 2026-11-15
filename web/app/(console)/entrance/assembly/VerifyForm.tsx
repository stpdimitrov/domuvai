"use client";

import { useActionState } from "react";
import { verifyIdentity, type VerifyState } from "./actions";

/**
 * Recording that a remote participant was reliably identified, and how (PM-GA-016): one line of the register, one
 * statement. It works without a script: the server's action answers either with the assembly's page, the line now
 * counted as the api says, or with the api's refusal and the statement as typed.
 */
// Rule: PM-GA-016
export default function VerifyForm({ entrance, assembly, attendance }: { entrance: string; assembly: string; attendance: string }) {
  const [state, act, pending] = useActionState<VerifyState, FormData>(verifyIdentity, {});
  return (
    <form action={act} key={state.how ?? ""} className="as-verify">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="attendance" value={attendance} />
      <input type="text" name="how" defaultValue={state.how} required placeholder="как е установена самоличността" maxLength={500} aria-label="Как е установена самоличността" />
      <button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Самоличността е проверена"}</button>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
    </form>
  );
}
