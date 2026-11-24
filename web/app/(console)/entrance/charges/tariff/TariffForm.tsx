"use client";

import { useActionState } from "react";
import { KEYS, LINE_ROWS, PRICE_ROWS, STREAMS } from "@/lib/tariff";
import { recordTariff, type TariffState } from "./actions";

/**
 * A tariff version recorded (WEB-61). Works without a script — the server's action takes the person back to the list, or
 * answers with the API's refusal and the form as typed. Amounts are typed in euro; the decision is one of the entrance's
 * passed ones, never typed. A recorded version is never edited here: a changed tariff is a new version (PM-FEE-015).
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export default function TariffForm({ entrance, decisions, unread }: { entrance: string; decisions: { id: string; label: string }[]; unread?: string }) {
  const [state, act, pending] = useActionState<TariffState, FormData>(recordTariff, {});
  const input = state.input ?? {};
  return (
    <form action={act} key={JSON.stringify(state.input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Записване на нова тарифна версия</div>
      <div className="wide as-hint">
        Тарифата се определя от общото събрание (PM-FEE-012): избирате прието решение и месеците, в които е в сила. Записана версия не се променя и не се трие —
        промяната е нова версия (PM-FEE-015). Сумите са в евро и са само тези, които общото събрание е приело.
      </div>
      {unread && <div className="wide as-refused" role="alert">{unread}</div>}
      <label className="wide">Решение на общото събрание
        <select name="decision" defaultValue={input.decision ?? ""}>
          <option value="">— изберете от приетите</option>
          {decisions.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}
        </select>
      </label>
      <label>В сила от (ГГГГ-ММ)<input name="validFrom" defaultValue={input.validFrom} placeholder="2027-01" /></label>
      <label>До (ГГГГ-ММ, по желание)<input name="validTo" defaultValue={input.validTo} placeholder="" /></label>
      <fieldset className="wide">
        <legend>Редове на тарифата</legend>
        {Array.from({ length: LINE_ROWS }, (_, n) => (
          <div key={n} style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 6 }}>
            <select name={`line-stream-${n}`} defaultValue={input[`line-stream-${n}`] ?? ""} aria-label={`Ред ${n + 1}: поток`}>
              <option value="">— поток</option>
              {Object.entries(STREAMS).map(([code, w]) => <option key={code} value={code}>{w}</option>)}
            </select>
            <select name={`line-key-${n}`} defaultValue={input[`line-key-${n}`] ?? ""} aria-label={`Ред ${n + 1}: ключ`}>
              <option value="">— ключ</option>
              {Object.entries(KEYS).map(([code, w]) => <option key={code} value={code}>{w}</option>)}
            </select>
            <select name={`line-kind-${n}`} defaultValue={input[`line-kind-${n}`] ?? "rate"} aria-label={`Ред ${n + 1}: ставка или обща сума`}>
              <option value="rate">ставка</option>
              <option value="total">обща сума</option>
            </select>
            <input name={`line-amount-${n}`} defaultValue={input[`line-amount-${n}`]} placeholder="€ 0,00" inputMode="decimal" aria-label={`Ред ${n + 1}: сума в евро`} />
            <input name={`line-item-${n}`} defaultValue={input[`line-item-${n}`]} placeholder="вид разход (по желание)" aria-label={`Ред ${n + 1}: вид разход`} />
          </div>
        ))}
      </fieldset>
      <fieldset className="wide">
        <legend>Цени на потреблението (по желание)</legend>
        {Array.from({ length: PRICE_ROWS }, (_, n) => (
          <div key={n} style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 6 }}>
            <input name={`price-item-${n}`} defaultValue={input[`price-item-${n}`]} placeholder="вид потребление" aria-label={`Цена ${n + 1}: вид`} />
            <input name={`price-price-${n}`} defaultValue={input[`price-price-${n}`]} placeholder="€ 0,00" inputMode="decimal" aria-label={`Цена ${n + 1}: цена в евро`} />
          </div>
        ))}
      </fieldset>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши тарифната версия"}</button>
      </div>
    </form>
  );
}
