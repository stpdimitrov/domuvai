"use client";

import { useActionState } from "react";
import { OFFICE } from "@/lib/mandate";
import { end, record, type MandateState } from "./actions";

const FORM = { margin: "16px 24px" } as const;

/**
 * A mandate recorded from its protocol (WEB-49) — works without a script. Offered whoever signs in: only the deployment's
 * administrator may (Rule: PM-SEC-001), the API's refusal is shown in its words and the fields kept. The term a mandate may
 * run (Rule: PM-GOV-004) is the API's, and so is what it succeeds.
 */
// Rule: PM-GOV-004
export function RecordForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<MandateState<{ mandateId: string; succeeded: string[] }>, FormData>(record, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Записване на мандат</div>
      <div className="wide as-hint">Мандатът се вписва по протокола, с който е даден. Колко дълъг може да бъде и кои мандати наследява, решава системата.</div>
      <label>Длъжност
        <select name="body" defaultValue={v.body || "CTL"}>
          {Object.entries(OFFICE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Номер на лицето<input type="text" name="party" defaultValue={v.party} maxLength={36} required autoComplete="off" /></label>
      <label>От (първи ден)<input type="date" name="validFrom" defaultValue={v.validFrom} required /></label>
      <label>До (ден)<input type="date" name="validTo" defaultValue={v.validTo} required /></label>
      <label className="wide">Протокол<input type="text" name="protocolRef" defaultValue={v.protocolRef} maxLength={200} required /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && (
        <div className="wide" role="status">
          Мандатът е вписан. Номер: <code>{state.done.mandateId}</code>.{state.done.succeeded.length > 0 && <> Приключва мандатите: {state.done.succeeded.map((s) => <code key={s}>{s} </code>)}</>}
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши мандата"}</button></div>
    </form>
  );
}

/** A mandate's end (WEB-49): its id from the list above, the day, the protocol — once, and not in the future: the API's rules (Rule: PM-GOV-004). */
// Rule: PM-GOV-004
export function EndForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<MandateState<{ mandateId: string; on: string }>, FormData>(end, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Край на мандат</div>
      <label>Номер на мандата<input type="text" name="mandate" defaultValue={v.mandate} maxLength={36} required autoComplete="off" /></label>
      <label>Приключил на<input type="date" name="on" defaultValue={v.on} required /></label>
      <label className="wide">Протокол или акт<input type="text" name="protocolRef" defaultValue={v.protocolRef} maxLength={200} required /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Мандат <code>{state.done.mandateId}</code> е приключен на {state.done.on}.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши края"}</button></div>
    </form>
  );
}
