"use client";

import { useActionState } from "react";
import { PURPOSE } from "@/lib/disbursement";
import { signOff, type DisbursementState } from "./actions";

/**
 * A disbursement signed off (WEB-42). Works without a script — the server's action takes the person back to the list,
 * or answers with the API's refusal and the form as it was. The signatory is never a field: the action reads the
 * session's own party (owner, 2026-10-28, "Confirm Q1"). The decisions are this entrance's passed ones, never typed.
 */
// Rule: PM-FUND-006, PM-FUND-007, PM-FUND-008, PM-SEC-001
export default function SignOffForm({ entrance, decisions, unread, mintedKey }: {
  entrance: string; decisions: { id: string; label: string }[]; unread?: string; mintedKey: string;
}) {
  const [state, act, pending] = useActionState<DisbursementState, FormData>(signOff, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <div className="wide acct-title">Подписване на разход от фонда</div>
      <div className="wide as-hint">
        Фондът се разходва само за работи и оборудване, мерки по техническия паспорт и цели, решени от ОС (PM-FUND-006), на основание решение на ОС,
        подписано от титуляря на сметката (PM-FUND-007). Без решение — само аварийен ремонт, ако разполагаемото го покрива, с обосновка (PM-FUND-008).
        Подписвате като лицето, с което сте влезли.
      </div>
      {unread && <div className="wide as-refused" role="alert">{unread}</div>}
      <label>За какво
        <select name="purpose" defaultValue={input?.purpose ?? ""}>
          <option value="">— изберете</option>
          {Object.entries(PURPOSE).map(([code, w]) => <option key={code} value={code}>{w} ({code})</option>)}
        </select>
      </label>
      <label>Сума, €<input name="amount" defaultValue={input?.amount} placeholder="0,00" inputMode="decimal" /></label>
      <label className="wide">Мярка от техническия паспорт (само за мярка по паспорта)
        <input name="measure" defaultValue={input?.measure} />
      </label>
      <fieldset className="wide">
        <legend>Основание</legend>
        <label><input type="radio" name="basis" value="DECISION" defaultChecked={(input?.basis ?? "DECISION") === "DECISION"} /> Решение на общото събрание</label>
        <select name="decision" defaultValue={input?.decision ?? ""}>
          <option value="">— изберете от приетите</option>
          {decisions.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}
        </select>
        <label><input type="radio" name="basis" value="EMERGENCY" defaultChecked={input?.basis === "EMERGENCY"} /> Аварийен ремонт, без решение (само работи)</label>
        <input name="justification" defaultValue={input?.justification} placeholder="Каква е аварията" aria-label="Обосновка на аварията" />
      </fieldset>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Подписва се…" : "Подпиши разхода"}</button>
      </div>
    </form>
  );
}
