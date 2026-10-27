"use client";

import { useActionState } from "react";
import { revertImport, type RevertState } from "../actions";
import { statusOf } from "../status";

/**
 * The revert, asked with its reason (STAGE1-ADDENDUM §1) — a no-script form; the API answers REVERTING, or refuses in
 * its own words with the reason kept. Whether this import can be reverted is the API's, so the form is always here.
 */
// Rule: PM-SEC-001
export default function RevertForm({ id, again }: { id: string; again: string }) {
  const [state, act, pending] = useActionState<RevertState, FormData>(revertImport, {});
  return (
    <form action={act} key={state.reason} className="as-form" style={{ marginTop: 16 }}>
      <input type="hidden" name="import" value={id} />
      <div className="wide acct-title">Отмяна на внасянето</div>
      <label className="wide">Причина<input name="reason" required defaultValue={state.reason} /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.view && (
        <div className="wide" role="status">
          Отмяната е поискана: {statusOf(state.view.status)}. Регистърът премахва редовете след този отговор — <a href={again}>прочетете внасянето отново</a>.
        </div>
      )}
      <div className="wide">
        <button type="submit" className="chip" disabled={pending}>{pending ? "Отменя се…" : "Отмени внасянето"}</button>
        <span className="as-hint">Отмяната премахва от регистъра всичко, което внасянето е добавило. Може да се отмени само прието внасяне.</span>
      </div>
    </form>
  );
}
