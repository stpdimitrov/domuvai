"use client";

import { useActionState } from "react";
import type { Schemas } from "@/lib/api/client";
import { eur } from "@/lib/money";
import { intake, type ProfileState } from "./actions";
import { statusOf } from "./status";

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

/** The law's streams, keys and named costs (PM-FEE-001, PM-FEE-011), the API's codes in words. */
const STREAM = { MANAGEMENT: "Управление", MAINTENANCE: "Поддръжка", REPAIR_FUND: "Фонд за ремонт" };
const KEY = { PER_PERSON: "на живущ", BY_IDEAL_PARTS: "по идеални части", PER_UNIT: "на обект" };
const ITEM = { CONCIERGE: "Портиер", WATER: "Вода", HEATING: "Отопление" };
/** The rows of the tariff form: no script adds one, so a few stand ready and the empty ones are left out. */
const LINES = 4;
const options = (words: Record<string, string>) => Object.entries(words).map(([code, w]) => <option key={code} value={code}>{w} ({code})</option>);

/**
 * The intake's steps (ADR-012): a fee sheet uploaded, the API's proposal for its columns, the mapping the person
 * confirms, and the dry-run or the import recorded with the tariff the firm billed on. Every form works without a
 * script — the server's action answers with the proposal, the confirmed mapping, the verdict, or the API's refusal and
 * the form as it was. The page proposes and compares nothing of its own: every column, every unplaced one, every
 * missing required field, and which bills reproduce to the cent and which do not, is the API's.
 */
// Rule: PM-SEC-001, PM-FEE-014
export default function IntakeForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<ProfileState, FormData>(intake, {});
  const { sheet, proposal, confirmed } = state;
  const columns = state.columns ?? (proposal ? [...Object.keys(proposal.mapping), ...proposal.unmappedColumns] : []);
  const mapping = !!sheet && columns.length > 0;
  const page = (id: string) => `/entrance/intake/${id}?entrance=${encodeURIComponent(entrance)}`;
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
          {state.refused && !state.run && <div className="as-refused" role="alert">{state.refused}</div>}
          <div className="wide">
            <button type="submit" className="chip active" disabled={pending}>{pending ? "Потвърждава се…" : "Потвърди разпределението"}</button>
            <span className="as-hint">Дали разпределението стига за изчислението, казва пробното изчисление — то е следващата стъпка.</span>
          </div>
        </form>
      )}

      {confirmed && (
        <form action={act} key={JSON.stringify([confirmed, state.run])} className="as-form" style={{ marginTop: 16 }}>
          <input type="hidden" name="entrance" value={entrance} />
          <input type="hidden" name="sheetName" value={sheet?.name ?? ""} />
          <input type="hidden" name="csv" value={sheet?.carried ?? ""} />
          {state.recorded && <input type="hidden" name="recorded" value={state.recorded} />}
          <div className="wide acct-title">Потвърдено разпределение</div>
          <ul className="wide" style={{ margin: 0, paddingLeft: 18 }}>
            {columns.map((column, i) => (
              <li key={i}>
                „{column}“ → {confirmed[column] ? named(confirmed[column]) : "не се внася"}
                <input type="hidden" name="column" value={column} />
                <input type="hidden" name={`field-${i}`} value={confirmed[column] ?? ""} />
              </li>
            ))}
          </ul>
          <div className="wide acct-title">Тарифа, по която фирмата е изчислила таблицата</div>
          <label>Период (ГГГГ-ММ)<input name="period" required pattern="\d{4}-\d{2}" defaultValue={state.run?.period} /></label>
          <label>Юридическа дата<input name="legalDate" type="date" required defaultValue={state.run?.legalDate} /></label>
          <label>Множител за стопанска дейност (ако има)<input name="multiplier" inputMode="numeric" defaultValue={state.run?.multiplier} /></label>
          {Array.from({ length: LINES }, (_, j) => {
            const l = state.run?.lines[j];
            return (
              <div key={j} className="wide" style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <select name={`stream-${j}`} defaultValue={l?.stream ?? ""} aria-label={`Ред ${j + 1}: перо`}><option value="">— празен ред</option>{options(STREAM)}</select>
                <select name={`key-${j}`} defaultValue={l?.key ?? "PER_PERSON"} aria-label={`Ред ${j + 1}: разпределение`}>{options(KEY)}</select>
                <select name={`item-${j}`} defaultValue={l?.item ?? ""} aria-label={`Ред ${j + 1}: разход`}><option value="">— цялото перо</option>{options(ITEM)}</select>
                <select name={`per-${j}`} defaultValue={l?.per ?? "rate"} aria-label={`Ред ${j + 1}: вид сума`}>
                  <option value="rate">ставка, €</option>
                  <option value="total">обща сума, €</option>
                </select>
                <input name={`amount-${j}`} defaultValue={l?.amount} placeholder="0,00" aria-label={`Ред ${j + 1}: сума в евро`} style={{ width: 90 }} />
                <input name={`decision-${j}`} defaultValue={l?.decision} placeholder="решение на ОС" aria-label={`Ред ${j + 1}: решение на общото събрание`} />
              </div>
            );
          })}
          {state.refused && state.run && <div className="as-refused" role="alert">{state.refused}</div>}
          <div className="wide">
            <button type="submit" name="step" value="dry-run" className="chip active" disabled={pending}>{pending ? "Изчислява се…" : "Пробно изчисление"}</button>
            {!state.recorded && <button type="submit" name="step" value="record" className="chip" disabled={pending}>Запиши внасянето</button>}
            {state.recorded && <button type="submit" name="step" value="commit" className="chip" disabled={pending}>Приеми внасянето</button>}
            <span className="as-hint">Пробното изчисление не записва нищо. Записът пази присъдата на системата и съдържанието на таблицата; в регистъра не влиза нищо, докато внасянето не бъде прието. Приемането изпраща същата таблица и тарифа — системата проверява, че са записаните.</span>
          </div>
        </form>
      )}

      {state.committed && (
        <div className="as-form" style={{ marginTop: 16 }} role="status">
          <div className="wide acct-title">Приемането е поискано: {statusOf(state.committed.status)}</div>
          <div className="wide">
            Нови обекти: {state.committed.rowsCreated} · променени: {state.committed.rowsChanged}. Регистърът ги приема след този отговор — <a href={page(state.committed.importId)}>прочетете внасянето</a>.
          </div>
          {state.committed.manualEntries.length > 0 && (
            <>
              <div className="wide">Не се внасят — записват се от човек, по декларация:</div>
              <table className="wide">
                <thead><tr><th>Обект</th><th>Поле</th><th>По таблицата</th><th>Правило</th></tr></thead>
                <tbody>
                  {state.committed.manualEntries.map((m, i) => (
                    <tr key={i}><td>{m.designation}</td><td>{named(m.field)}</td><td>{m.value}</td><td>{m.rule}</td></tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>
      )}

      {state.report && (
        <div className="as-form" style={{ marginTop: 16 }}>
          {state.recorded && <div className="wide acct-title">{state.recordedNow ? "Внасянето е записано" : "Записано по-рано"}: <a href={page(state.recorded)}>{state.recorded}</a></div>}
          <div className="wide acct-title">
            {state.report.reproduced ? "Таблицата е възпроизведена до стотинка." : "Таблицата не е възпроизведена."}
          </div>
          <div className="wide">Редове: {state.report.rowsParsed} · съвпадат: {state.report.matched} · различават се: {state.report.differing}</div>
          {state.report.differences.length > 0 && (
            <table className="wide">
              <thead><tr><th>Обект</th><th>По таблицата</th><th>По системата</th><th>Разлика</th></tr></thead>
              <tbody>
                {state.report.differences.map((d, i) => (
                  <tr key={i}><td>{d.designation}</td><td className="num">{eur(d.theirMinor)}</td><td className="num">{eur(d.ourMinor)}</td><td className="num">{eur(d.deltaMinor)}</td></tr>
                ))}
              </tbody>
            </table>
          )}
          {state.report.violations.length > 0 && (
            <ul className="wide as-refused" role="status" style={{ margin: 0, paddingLeft: 18 }}>
              {state.report.violations.map((v, i) => <li key={i}>{v}</li>)}
            </ul>
          )}
        </div>
      )}
    </>
  );
}
