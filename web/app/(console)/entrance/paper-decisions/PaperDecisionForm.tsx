"use client";

import { useActionState } from "react";
import { record, type PaperDecisionState } from "./actions";
import PaperRecord from "./PaperRecord";

/**
 * A decision recorded from its paper protocol (WEB-54) — works without a script. Offered whoever signs in: only the
 * deployment's administrator may (Rule: PM-SEC-001), the API's refusal is shown in its words and the fields kept. What a
 * record must hold is the API's too (Rule: PM-GA-022, PM-DOC-007); what it answers is shown here, the list above being refused to the administrator.
 */
// Rule: PM-GA-022, PM-DOC-007, PM-SEC-001
export default function PaperDecisionForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<PaperDecisionState, FormData>(record, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Вписване на решение от протокол</div>
      <div className="wide as-hint">
        Решението се вписва такова, каквото е в протокола: не посочва заседание, кворум или мнозинство и след вписването не се променя.
        Кой може да вписва и какво е задължително, решава системата.
      </div>
      <label className="wide">Текст на решението<textarea name="decisionText" defaultValue={v.decisionText} rows={4} required /></label>
      <label>Дата на протокола<input type="date" name="protocolDate" defaultValue={v.protocolDate} required /></label>
      <label>Протокол (номер или описание)<input type="text" name="protocolRef" defaultValue={v.protocolRef} required autoComplete="off" /></label>
      <label>Къде се пази оригиналът<input type="text" name="archiveLocation" defaultValue={v.archiveLocation} required autoComplete="off" /></label>
      <label>Кой пази оригинала<input type="text" name="custodian" defaultValue={v.custodian} required autoComplete="off" /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Решението е вписано. <PaperRecord decision={state.done} /></div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши решението"}</button></div>
    </form>
  );
}
