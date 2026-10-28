"use client";

import { useActionState } from "react";
import { recordPayment, type PaymentState } from "./actions";

/** The accounts a payment is received into, the API's codes in words. */
const INTO = { OPERATING: "Оперативна сметка", REPAIR_RENEWAL: "Сметка на фонд „Ремонт и обновяване“", CASH: "В брой" };

/**
 * A payment received, recorded (WEB-39). Works without a script — the server's action takes the person to the
 * payment's allocation, or answers with the API's refusal and the form as it was, under the same key. The form
 * allocates nothing: oldest debt first, or the debt the payer named, is the API's.
 */
// Rule: PM-DEBT-008, PM-SEC-001
export default function PaymentForm({ entrance, units, unread, today, mintedKey }: {
  entrance: string; units: { id: string; designation: string }[]; unread?: string; today: string; mintedKey: string;
}) {
  const [state, act, pending] = useActionState<PaymentState, FormData>(recordPayment, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <div className="wide acct-title">Записване на получено плащане</div>
      <div className="wide as-hint">
        Плащането се отнася към най-старото задължение на обекта, освен ако платецът е посочил друго (PM-DEBT-008) — разпределението прави системата и
        го показва след записа. Записва се получено плащане — системата не превежда и не събира пари.
      </div>
      {unread && <div className="wide as-refused" role="alert">{unread}</div>}
      <label>Обект
        <select name="unit" defaultValue={input?.unit ?? ""}>
          <option value="">— изберете</option>
          {units.map((u) => <option key={u.id} value={u.id}>{u.designation}</option>)}
        </select>
      </label>
      <label>Сума, €<input name="amount" defaultValue={input?.amount} placeholder="0,00" inputMode="decimal" /></label>
      <label>Дата на плащането<input type="date" name="valueDate" defaultValue={input?.valueDate ?? today} /></label>
      <label>Постъпило в
        <select name="receivedInto" defaultValue={input?.receivedInto ?? "OPERATING"}>
          {Object.entries(INTO).map(([code, w]) => <option key={code} value={code}>{w} ({code})</option>)}
        </select>
      </label>
      <label className="wide">Посочено от платеца задължение — дата на задължението (по избор)
        <input type="date" name="designated" defaultValue={input?.designated} />
      </label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши плащането"}</button>
      </div>
    </form>
  );
}
