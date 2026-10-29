"use client";

import { useActionState } from "react";
import { registerUnits, type UnitsState } from "./actions";

/**
 * An entrance's units typed one to a line (WEB-50b) — works without a script. Offered whoever signs in: only the manager or the
 * board enter the book (Rule: PM-SEC-001), the API's refusal is shown in its words and the list kept. Ideal parts: all declared, or
 * none, for the API to derive by area (Rule: PM-ORG-002, PM-ORG-003).
 */
// Rule: PM-ORG-002, PM-ORG-003, PM-SEC-001
export function UnitsForm({ entrance, types }: { entrance: string; types: string[] }) {
  const [state, act, pending] = useActionState<UnitsState, FormData>(registerUnits, {});
  return (
    <form action={act} key={state.typed ?? ""} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Регистриране на обекти</div>
      <div className="wide as-hint">
        По един обект на ред: <code>обозначение; вид; площ; идеални части; стопанска дейност; самостоятелен вход</code> — последните две са да или не и се посочват винаги, защото обектът не може да се поправи след вписването. Площта и идеалните части може да са празни, но разделителите остават.
        Идеалните части са или за всички обекти, както са в нотариалните актове, или за никой — тогава системата ги изчислява по площ и ги отбелязва като изчислени.
        Дали сборът на идеалните части е верен, дали площта стига и може ли входът да получи още обекти, решава системата.
        {types.length > 0 && <> Видове, вече използвани във входа: {types.map((t) => <code key={t}>{t} </code>)}</>}
      </div>
      <label className="wide">Обекти
        <textarea name="units" defaultValue={state.typed} rows={10} required spellCheck={false} placeholder={"1; вид; 62.50; 25.0000; не; не\n2; вид; 41.20; 16.5000; не; не"} />
      </label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Вписани са {state.done.length} обекта. Номера: {state.done.map((id) => <code key={id}>{id} </code>)}</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписват се…" : "Впиши обектите"}</button></div>
    </form>
  );
}
