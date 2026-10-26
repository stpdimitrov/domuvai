"use client";

import { useActionState } from "react";
import type { Schemas } from "@/lib/api/client";
import { intake, type ProfileState } from "./actions";

type Field = Schemas["ProposedMapping"]["mapping"][string];

/** The domain fields a column may carry (ADR-012), the API's codes in words; the code is shown beside each. */
const FIELD: Record<Field, string> = {
  DESIGNATION: "Обект (означение)",
  IDEAL_PARTS: "Идеални части",
  OCCUPANTS: "Брой лица",
  FEE_MINOR: "Такса по таблицата",
  BUILT_AREA: "Застроена площ",
  OWNER_NAME: "Собственик",
  CHILDREN_UNDER_6: "Деца под 6 г.",
  ANIMALS: "Домашни животни",
  ABSENT_DAYS: "Дни отсъствие",
  BUSINESS_USE: "Стопанска дейност",
};
const named = (f: Field) => `${FIELD[f] ?? f} (${f})`;

/**
 * The intake's first three steps (ADR-012): a fee sheet uploaded, the API's proposal for its columns, and the mapping
 * the person confirms. Both forms work without a script — the server's action answers with the proposal, the
 * confirmed mapping, or the API's refusal and the form as it was. The page proposes nothing of its own: every column,
 * every unplaced one and every missing required field is the API's.
 */
// Rule: PM-SEC-001
export default function IntakeForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<ProfileState, FormData>(intake, {});
  const { sheet, proposal, confirmed } = state;
  const columns = state.columns ?? (proposal ? [...Object.keys(proposal.mapping), ...proposal.unmappedColumns] : []);
  const mapping = !!sheet && columns.length > 0;
  return (
    <>
      <form action={act} className="as-form">
        <input type="hidden" name="entrance" value={entrance} />
        <input type="hidden" name="step" value="profile" />
        <label className="wide">
          Таблица с таксите (CSV, UTF-8)
          <input type="file" name="sheet" accept=".csv,text/csv" required />
        </label>
        {state.refused && !mapping && <div className="as-refused" role="alert">{state.refused}</div>}
        <div className="wide">
          <button type="submit" className="chip active" disabled={pending}>{pending ? "Чете се…" : "Прочети колоните"}</button>
          <span className="as-hint">
            Таблицата е на фирмата — в нейния вид. Системата предлага коя колона какво носи; вие потвърждавате или поправяте.
            Нищо не се записва на тази стъпка.
          </span>
        </div>
      </form>

      {mapping && (
        <form action={act} key={JSON.stringify([columns, confirmed])} className="as-form" style={{ marginTop: 16 }}>
          <input type="hidden" name="entrance" value={entrance} />
          <input type="hidden" name="step" value="confirm" />
          <input type="hidden" name="sheetName" value={sheet.name} />
          <input type="hidden" name="csv" value={sheet.carried} />
          <div className="wide acct-title">Колони на „{sheet.name}“</div>
          {proposal && proposal.unmappedColumns.length > 0 && (
            <div className="wide as-hint">{confirmed && "Предложението на системата: "}Системата не разпозна: {proposal.unmappedColumns.map((c) => `„${c}“`).join(", ")} — изберете поле или оставете „не се внася“.</div>
          )}
          {proposal && proposal.missingRequired.length > 0 && (
            <div className="wide as-refused" role="status">{confirmed && "Предложението на системата: "}Нито една колона не носи задължителното: {proposal.missingRequired.map(named).join(", ")}.</div>
          )}
          {columns.map((column, i) => (
            <label key={i}>
              „{column}“
              <input type="hidden" name="column" value={column} />
              <select name={`field-${i}`} defaultValue={confirmed?.[column] ?? proposal?.mapping[column] ?? ""}>
                <option value="">— не се внася</option>
                {(Object.keys(FIELD) as Field[]).map((f) => <option key={f} value={f}>{named(f)}</option>)}
              </select>
            </label>
          ))}
          {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
          <div className="wide">
            <button type="submit" className="chip active" disabled={pending}>{pending ? "Потвърждава се…" : "Потвърди разпределението"}</button>
            <span className="as-hint">Дали разпределението стига за изчислението, казва пробното изчисление — то е следващата стъпка.</span>
          </div>
        </form>
      )}

      {confirmed && proposal && (
        <div className="as-form" style={{ marginTop: 16 }}>
          <div className="wide acct-title">Потвърдено разпределение</div>
          <ul className="wide" style={{ margin: 0, paddingLeft: 18 }}>
            {columns.map((column, i) => (
              <li key={i}>„{column}“ → {confirmed[column] ? named(confirmed[column]) : "не се внася"}</li>
            ))}
          </ul>
          <div className="wide as-hint">
            Пазят се само в тази страница, с таблицата. Пробното изчисление по тях — всяка сметка срещу таблицата, до стотинка —
            още не е тук; дотогава нищо не е записано.
          </div>
        </div>
      )}
    </>
  );
}
