"use client";

import { useActionState } from "react";
import { KIND } from "@/lib/declaration";
import { applyRetention, file, type DeclarationState, type RetentionState } from "./actions";

export type UnitChoice = { id: string; designation: string };

const FORM = { margin: "16px 24px" } as const;

/**
 * A declaration filed for the book (WEB-47) — works without a script. The unit and what it is for; no date and no
 * template: the system dates the declaration and files it on the template in force (Rule: PM-BOOK-003, PM-BOOK-004).
 * A declarant is typed only by the manager or the board, recording one handed in (Rule: PM-BOOK-005).
 */
// Rule: PM-BOOK-003, PM-BOOK-004, PM-BOOK-005
export function DeclarationForm({ entrance, units }: { entrance: string; units: UnitChoice[] }) {
  const [state, act, pending] = useActionState<DeclarationState, FormData>(file, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Подаване на декларация</div>
      <div className="wide as-hint">
        Собствениците и ползвателите подават декларация за вписване в книгата при придобиване и при всяка промяна (чл. 7, ал. 3 ЗУЕС). Датата на подаване
        и образецът са на системата.
      </div>
      <label>Обект
        <select name="unit" defaultValue={input?.unit ?? ""}>
          <option value="">— изберете —</option>
          {units.map((u) => <option key={u.id} value={u.id}>{u.designation}</option>)}
        </select>
      </label>
      <label>За какво е
        <select name="kind" defaultValue={input?.kind ?? ""}>
          <option value="">— изберете —</option>
          {Object.entries(KIND).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className="wide">Номер на декларанта<input type="text" name="declarant" defaultValue={input?.declarant} autoComplete="off" /></label>
      <div className="wide as-hint">
        Празно — подавате за себе си. Управителят и управителният съвет вписват декларация, предадена им от собственик или ползвател, като посочват номера
        му; системата проверява дали той държи обекта.
      </div>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.filed && (
        <div className="wide" role="status">
          Декларацията е подадена на {state.filed.filedOn}, по образец {state.filed.templateVersion}. Номер: <code>{state.filed.id}</code>
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Подава се…" : "Подай декларацията"}</button></div>
    </form>
  );
}

/**
 * The retention run (WEB-47): one button, behind a confirmation — the anonymising cannot be taken back. The window
 * and the day are the API's (Rule: PM-BOOK-010); the figures shown are what it answered.
 */
// Rule: PM-BOOK-010
export function RetentionForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<RetentionState, FormData>(applyRetention, {});
  return (
    <form action={act} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Срок на съхранение</div>
      <div className="wide as-hint">
        Личните данни в книгата се пазят, докато има основание за това. Пускането заличава връзките на живущите с поименни лица и номерата на ветеринарните
        паспорти на животните, на които срокът е изтекъл. Срокът и денят са на системата. Могат да го пуснат управителят и управителният съвет.
      </div>
      <label className="check wide">
        <input type="checkbox" name="confirm" value="yes" required /> Разбирам, че заличаването е необратимо
      </label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.applied && (
        <div className="wide" role="status">
          Заличаването е пуснато за {state.applied.on}: развързани са {state.applied.householdUnlinked} живущи, изчистени са {state.applied.animalPassportsCleared} паспорта.
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Пуска се…" : "Пусни заличаването"}</button></div>
    </form>
  );
}
