"use client";

import { useActionState } from "react";
import { KIND, ROWS } from "@/lib/household";
import { enter, leave, type HouseholdState, type LeavingState } from "./actions";

export type UnitChoice = { id: string; designation: string };

const FORM = { margin: "16px 24px" } as const;

function UnitSelect({ units, chosen }: { units: UnitChoice[]; chosen?: string }) {
  return (
    <label>Обект
      <select name="unit" defaultValue={chosen ?? ""}>
        <option value="">— изберете —</option>
        {units.map((u) => <option key={u.id} value={u.id}>{u.designation}</option>)}
      </select>
    </label>
  );
}

/**
 * The residents of a unit entered in the book (WEB-45) — works without a script. Each row is a resident: ticked as a
 * child under 6 or not, and from when; no age or number of days is typed here, who counts is the API's
 * (Rule: PM-FEE-005, PM-FEE-008). The days are sent as typed (Rule: PM-BOOK-008).
 */
// Rule: PM-FEE-005, PM-FEE-008, PM-BOOK-008
export function HouseholdForm({ entrance, units }: { entrance: string; units: UnitChoice[] }) {
  const [state, act, pending] = useActionState<HouseholdState, FormData>(enter, {});
  const rows = state.input?.rows;
  return (
    <form action={act} key={JSON.stringify(state.input ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Вписване на живущи</div>
      <div className="wide as-hint">
        Книгата се води от управителя и управителния съвет (чл. 7, ал. 6 ЗУЕС). Кой се брои за таксите и от кога е на системата — тук се посочва само
        кои са живущите. Празен ред е без живущ.
      </div>
      <UnitSelect units={units} chosen={state.input?.unit} />
      {Array.from({ length: ROWS }, (_, i) => (
        <div key={i} className="wide" style={{ display: "flex", gap: 12, alignItems: "end", flexWrap: "wrap" }}>
          <label>Живущ {i + 1}
            <select name={`kind${i}`} defaultValue={rows?.[i]?.kind ?? ""}>
              <option value="">—</option>
              {Object.entries(KIND).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
          <label>От дата<input type="date" name={`from${i}`} defaultValue={rows?.[i]?.from ?? ""} /></label>
        </div>
      ))}
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.entered && (
        <div className="wide" role="status">
          Вписани са {state.entered.length} живущи. Номера им, за записване на напускане: {state.entered.map((id) => <code key={id} style={{ marginRight: 8 }}>{id}</code>)}
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши живущите"}</button></div>
    </form>
  );
}

/** A resident's leaving recorded (WEB-45): the day, as typed; the range it closes is the API's (Rule: PM-BOOK-008). */
// Rule: PM-BOOK-008
export function LeavingForm({ entrance, units, today }: { entrance: string; units: UnitChoice[]; today: string }) {
  const [state, act, pending] = useActionState<LeavingState, FormData>(leave, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Напускане на живущ</div>
      <UnitSelect units={units} chosen={input?.unit} />
      <label className="wide">Номер на живущия<input type="text" name="member" defaultValue={input?.member} autoComplete="off" /></label>
      <label>Напуснал на<input type="date" name="on" defaultValue={input?.on || today} /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.left && <div className="wide" role="status">Престоят е затворен: от {state.left.validFrom} до {state.left.validTo}.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши напускането"}</button></div>
    </form>
  );
}
