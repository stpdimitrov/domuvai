"use client";

import { useActionState } from "react";
import { eur } from "@/lib/money";
import { issueRun, type IssueState } from "./actions";
import type { Passed } from "./decisions";

/** The law's streams and keys (PM-FEE-001), the API's codes in words. */
const STREAM = { MANAGEMENT: "Управление", MAINTENANCE: "Поддръжка", REPAIR_FUND: "Фонд за ремонт" };
const KEY = { PER_PERSON: "на живущ", BY_IDEAL_PARTS: "по идеални части", PER_UNIT: "на обект" };
/** The rows of the tariff: no script adds one, so a few stand ready and the empty ones are left out. */
const LINES = 3;
const options = (words: Record<string, string>) => Object.entries(words).map(([code, w]) => <option key={code} value={code}>{w} ({code})</option>);

/**
 * The run issued (WEB-38): a tariff stated line by line, each citing a decision the entrance's assembly passed, previewed
 * by the API, then issued as previewed. Works without a script — the server's action answers with the preview, the run
 * issued, or the API's refusal and the form as it was. The page computes nothing: every figure is the API's.
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export default function IssueForm({ entrance, period, multiple, passed, unread }: { entrance: string; period: string; multiple: string; passed: Passed[]; unread?: string }) {
  const [state, act, pending] = useActionState<IssueState, FormData>(issueRun, {});
  const { input, preview, issued } = state;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="period" value={period} />
      <input type="hidden" name="multiple" value={input?.multiple ?? multiple} />
      {state.previewed && <input type="hidden" name="previewed" value={state.previewed} />}
      <div className="wide acct-title">Издаване на начисленията за {period}</div>
      <div className="wide as-hint">
        Всеки ред посочва прието решение на общото събрание на този вход (PM-FEE-012) — демо базисът по-горе не се издава.
        Издадените начисления не се променят и периодът не се издава повторно; поправка става с кредитно или дебитно известие (PM-FEE-015).
        Издаването записва задълженията в системата — не превежда и не събира пари. Никой вход не се таксува реално, докато реална таблица с такси не бъде възпроизведена до цент (ADR-012 §7).
      </div>
      {unread && <div className="wide as-refused" role="alert">{unread}</div>}
      {!unread && passed.length === 0 && <div className="wide as-refused" role="alert">Няма прието решение на общото събрание на този вход — без решение не се начислява.</div>}
      {Array.from({ length: LINES }, (_, j) => {
        const l = input?.lines[j];
        return (
          <div key={j} className="wide" style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <select name={`stream-${j}`} defaultValue={l?.stream ?? ""} aria-label={`Ред ${j + 1}: перо`}><option value="">— празен ред</option>{options(STREAM)}</select>
            <select name={`key-${j}`} defaultValue={l?.key ?? "PER_PERSON"} aria-label={`Ред ${j + 1}: разпределение`}>{options(KEY)}</select>
            <select name={`per-${j}`} defaultValue={l?.per ?? "rate"} aria-label={`Ред ${j + 1}: вид сума`}>
              <option value="rate">ставка, €</option>
              <option value="total">обща сума, €</option>
            </select>
            <input name={`amount-${j}`} defaultValue={l?.amount} placeholder="0,00" aria-label={`Ред ${j + 1}: сума в евро`} style={{ width: 90 }} />
            <select name={`decision-${j}`} defaultValue={l?.decision ?? ""} aria-label={`Ред ${j + 1}: решение на общото събрание`}>
              <option value="">— решение на ОС</option>
              {passed.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}
            </select>
          </div>
        );
      })}
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {preview && !issued && (
        <div className="wide">
          Изчислено от API за {preview.period}: {preview.charges.length} обекта, общо <strong>{eur(preview.totalMinor)}</strong> · {preview.engineVersion} · закон {preview.lawVersion}
          <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
            {preview.charges.map((c) => <li key={c.unitId}>{c.designation}: {eur(c.totalMinor)}</li>)}
          </ul>
        </div>
      )}
      {issued && (
        <div className="wide" role="status">
          Издадено: {issued.period}, {issued.lineCount} реда, общо <strong>{eur(issued.totalMinor)}</strong> · <code>{issued.chargeRunId}</code>
        </div>
      )}
      <div className="wide">
        <button type="submit" name="step" value="preview" className="chip active" disabled={pending}>{pending ? "Изчислява се…" : "Прегледай"}</button>
        {state.previewed && !issued && <button type="submit" name="step" value="issue" className="chip" disabled={pending}>Издай начисленията</button>}
        <span className="as-hint">Прегледът не записва нищо. Издава се само прегледаният базис.</span>
      </div>
    </form>
  );
}
