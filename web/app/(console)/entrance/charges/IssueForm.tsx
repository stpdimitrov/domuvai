"use client";

import { useActionState } from "react";
import { eur } from "@/lib/money";
import { issueRun, type IssueState } from "./actions";

/**
 * The run issued (WEB-60): the tariff version in force, shown above, previewed by the API, then issued as previewed.
 * No tariff is typed — the API bills the version in force. Works without a script — the server's action answers with the
 * preview, the run issued, or the API's refusal and the form as it was. The page computes nothing: every figure is the API's.
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export default function IssueForm({ entrance, period, multiple }: { entrance: string; period: string; multiple: string }) {
  const [state, act, pending] = useActionState<IssueState, FormData>(issueRun, {});
  const { preview, issued } = state;
  return (
    <form action={act} key={state.multiple ?? ""} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="period" value={period} />
      <input type="hidden" name="multiple" value={state.multiple ?? multiple} />
      {state.previewed && <input type="hidden" name="previewed" value={state.previewed} />}
      <div className="wide acct-title">Издаване на начисленията за {period}</div>
      <div className="wide as-hint">
        Начислява се по тарифата в сила, която е показана по-горе и е по решение на общото събрание (PM-FEE-012) — тук не се въвеждат суми.
        Издадените начисления не се променят и периодът не се издава повторно; поправка става с кредитно или дебитно известие (PM-FEE-015).
        Издаването записва задълженията в системата — не превежда и не събира пари. Никой вход не се таксува реално, докато реална таблица с такси не бъде възпроизведена до цент (ADR-012 §7).
      </div>
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
        <span className="as-hint">Прегледът не записва нищо. Издава се само прегледаното.</span>
      </div>
    </form>
  );
}
