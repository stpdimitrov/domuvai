"use client";

import { useActionState } from "react";
import { issue, type HandoverState } from "./actions";

/**
 * A handover statement issued (WEB-43) — works without a script. The two sides are party ids until #300 serves names.
 * The bank balance is the figure on the bank's own statement for that day, never filled in from the book: a figure
 * copied from the book would always match. The key is minted by the page, once per form.
 */
// Rule: PM-FUND-010, PM-SEC-001
export default function HandoverForm({ entrance, today, mintedKey }: { entrance: string; today: string; mintedKey: string }) {
  const [state, act, pending] = useActionState<HandoverState, FormData>(issue, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <div className="wide acct-title">Издаване на отчет при предаване на фонда</div>
      <div className="wide as-hint">
        Фондът следва сградата при смяна на управителя или управляващата фирма (чл. 23 ЗУЕС, PM-FUND-010). Системата съставя отчета от книгата и го
        поставя до салдото по банковото извлечение — въведете го от извлечението, не от книгата. Грешен отчет не се поправя: издава се нов.
      </div>
      <label>Дата на предаване<input type="date" name="handoverOn" defaultValue={input?.handoverOn ?? today} /></label>
      <label>От (по желание — началото на периода)<input type="date" name="from" defaultValue={input?.from} /></label>
      <label>Предаваща страна (идентификатор на лице)<input type="text" name="outgoing" defaultValue={input?.outgoing} autoComplete="off" /></label>
      <label>Приемаща страна (идентификатор на лице)<input type="text" name="incoming" defaultValue={input?.incoming} autoComplete="off" /></label>
      <label>Салдо по банковото извлечение, евро<input type="text" name="bankBalance" defaultValue={input?.bankBalance} inputMode="decimal" autoComplete="off" /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Издава се…" : "Издай отчета"}</button>
      </div>
    </form>
  );
}
