"use client";

import { useActionState } from "react";
import { cancel, pay, type DisbursementState } from "./actions";

/** The payout the fund's bank made, recorded for one committed disbursement (WEB-42b) — the date it paid, as typed. The signatory is the session's own party, read by the action. */
// Rule: PM-FUND-007, PM-SEC-001
export function PayForm({ entrance, disbursement, today, mintedKey }: { entrance: string; disbursement: string; today: string; mintedKey: string }) {
  const [state, act, pending] = useActionState<DisbursementState, FormData>(pay, {});
  return (
    <form action={act} key={JSON.stringify(state.input ?? null)} style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="disbursement" value={disbursement} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <label>Платен от банката на <input type="date" name="paidOn" defaultValue={state.input?.paidOn ?? today} /></label>
      <button type="submit" className="chip" disabled={pending}>{pending ? "Записва се…" : "Платен"}</button>
      {state.refused && <div className="as-refused" role="alert" style={{ flexBasis: "100%" }}>{state.refused}</div>}
    </form>
  );
}

/** A committed disbursement withdrawn, with its reason (WEB-42b). */
// Rule: PM-FUND-007, PM-SEC-001
export function CancelForm({ entrance, disbursement, mintedKey }: { entrance: string; disbursement: string; mintedKey: string }) {
  const [state, act, pending] = useActionState<DisbursementState, FormData>(cancel, {});
  return (
    <form action={act} key={JSON.stringify(state.input ?? null)} style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="disbursement" value={disbursement} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <label>Причина <input name="reason" defaultValue={state.input?.reason} /></label>
      <button type="submit" className="chip" disabled={pending}>{pending ? "Оттегля се…" : "Оттегли"}</button>
      {state.refused && <div className="as-refused" role="alert" style={{ flexBasis: "100%" }}>{state.refused}</div>}
    </form>
  );
}
