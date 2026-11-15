"use client";

import { useActionState } from "react";
import { ABSENCE_ROWS, ANIMAL_ROWS } from "@/lib/animals";
import { enterAbsences, enterAnimals, leaveAnimal, type AbsencesState, type AnimalLeavingState, type AnimalsState } from "./actions";

export type UnitChoice = { id: string; designation: string };

const FORM = { margin: "16px 24px" } as const;
const ROW = { display: "flex", gap: 12, alignItems: "end", flexWrap: "wrap" } as const;

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

const Ids = ({ ids }: { ids: string[] }) => <>{ids.map((id) => <code key={id} style={{ marginRight: 8 }}>{id}</code>)}</>;

/**
 * The animals of a unit entered in the book (WEB-46) — works without a script. Each row is an animal: the species and
 * the veterinary passport number as typed, and from when; the web keeps no list of species and checks no passport format
 * (Rule: PM-BOOK-005). The days are sent as typed (Rule: PM-BOOK-008).
 */
// Rule: PM-BOOK-005, PM-BOOK-008
export function AnimalsForm({ entrance, units }: { entrance: string; units: UnitChoice[] }) {
  const [state, act, pending] = useActionState<AnimalsState, FormData>(enterAnimals, {});
  const rows = state.input?.rows;
  return (
    <form action={act} key={JSON.stringify(state.input ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Вписване на животни</div>
      <div className="wide as-hint">
        Животните се водят в отделен раздел на книгата от управителя и управителния съвет, по декларация на собственика, с данните от ветеринарния
        паспорт (чл. 7, ал. 6 ЗУЕС). Празен ред е без животно. Животните още не се четат в книгата — тя дава само брой живущи и периоди на неползване.
      </div>
      <UnitSelect units={units} chosen={state.input?.unit} />
      {Array.from({ length: ANIMAL_ROWS }, (_, i) => (
        <div key={i} className="wide" style={ROW}>
          <label>Вид {i + 1}<input type="text" name={`species${i}`} defaultValue={rows?.[i]?.species} autoComplete="off" /></label>
          <label>Ветеринарен паспорт<input type="text" name={`passport${i}`} defaultValue={rows?.[i]?.passport} autoComplete="off" /></label>
          <label>От дата<input type="date" name={`from${i}`} defaultValue={rows?.[i]?.from ?? ""} /></label>
        </div>
      ))}
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.entered && (
        <div className="wide" role="status">
          Вписани са {state.entered.length} животни. Номера им, за записване на напускане: <Ids ids={state.entered} />
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши животните"}</button></div>
    </form>
  );
}

/** An animal's leaving recorded (WEB-46): the day, as typed; the range it closes is the API's (Rule: PM-BOOK-008). */
// Rule: PM-BOOK-008
export function AnimalLeavingForm({ entrance, units, today }: { entrance: string; units: UnitChoice[]; today: string }) {
  const [state, act, pending] = useActionState<AnimalLeavingState, FormData>(leaveAnimal, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Напускане на животно</div>
      <UnitSelect units={units} chosen={input?.unit} />
      <label className="wide">Номер на животното<input type="text" name="animal" defaultValue={input?.animal} autoComplete="off" /></label>
      <label>Напуснало на<input type="date" name="on" defaultValue={input?.on || today} /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.left && <div className="wide" role="status">Престоят е затворен: от {state.left.validFrom} до {state.left.validTo}.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши напускането"}</button></div>
    </form>
  );
}

/**
 * The absences of a unit entered in the book (WEB-46): two days a row, as typed. Whether an absence exempts a charge and
 * whether it came in time is the API's — no number of days and no grace window is typed here (Rule: PM-FEE-006,
 * PM-FEE-007); the days are sent as typed (Rule: PM-BOOK-008).
 */
// Rule: PM-BOOK-008, PM-FEE-006, PM-FEE-007
export function AbsencesForm({ entrance, units }: { entrance: string; units: UnitChoice[] }) {
  const [state, act, pending] = useActionState<AbsencesState, FormData>(enterAbsences, {});
  const rows = state.input?.rows;
  return (
    <form action={act} key={JSON.stringify(state.input ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Вписване на отсъствия</div>
      <div className="wide as-hint">
        Отсъствието е период: първият ден, в който го няма, и денят, в който се връща (чл. 51 ЗУЕС). Освобождава ли от такса и в срок ли е подадено — решава системата, не тази страница. Отсъствие, вписано
        със закъснение, се отчита от деня, в който е вписано, а не от деня на подаване на декларацията — системата не приема ден на подаване
        (известен проблем, #215). Вписаното се чете в книгата като периоди на неползване.
      </div>
      <UnitSelect units={units} chosen={state.input?.unit} />
      {Array.from({ length: ABSENCE_ROWS }, (_, i) => (
        <div key={i} className="wide" style={ROW}>
          <label>Отсъства от {i + 1}<input type="date" name={`absentFrom${i}`} defaultValue={rows?.[i]?.from ?? ""} /></label>
          <label>Връща се на<input type="date" name={`absentTo${i}`} defaultValue={rows?.[i]?.to ?? ""} /></label>
        </div>
      ))}
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.entered && (
        <div className="wide" role="status">
          Вписани са {state.entered.length} отсъствия. Номера им: <Ids ids={state.entered} />
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши отсъствията"}</button></div>
    </form>
  );
}
