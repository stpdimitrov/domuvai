import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, eur, reach } from "@/lib/console";
import { sofiaToday } from "@/lib/payment";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { ageing, byPeriod, COMPONENT, KEY, oldest, type StatementLine } from "@/lib/statement";

export const metadata: Metadata = { title: "Сметка на обект — Етаж" };

const BOX = { margin: "16px 24px", background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" };
const ROW = { display: "grid", gridTemplateColumns: "minmax(140px, 1.2fr) minmax(110px, 1fr) 90px minmax(160px, 2fr) 100px", gap: 12 };

/** The unit's arrears as of the date asked (Rule: PM-DEBT-001): every figure, band and due date the API's. */
// Rule: PM-DEBT-001
function Arrears({ arrears }: { arrears: Schemas["UnitArrears"] }) {
  return (
    <div style={BOX} role="region" aria-label="Задължения">
      <div className="acct-title">Задължения към {arrears.asOf}</div>
      <div>Дължимо {eur(arrears.totalMinor)} · аванс {eur(arrears.advanceMinor)} · нетно {eur(arrears.netMinor)}</div>
      <ul style={{ margin: "4px 0", paddingLeft: 18 }}>
        {ageing(arrears.buckets).map((b) => <li key={b.label}>{b.label}: {eur(b.amountMinor)}</li>)}
      </ul>
      <div>{oldest(arrears.oldestDebt)}</div>
      <div className="as-hint">Падежът е датата на начислението плюс срока за плащане, изчислен от системата.</div>
    </div>
  );
}

function Line({ line }: { line: StatementLine }) {
  return (
    <div style={ROW}>
      <div>{COMPONENT[line.component] ?? line.component}{line.item && <> · {line.item}</>}</div>
      <div>{KEY[line.allocationKey] ?? line.allocationKey} ({line.allocationKey})</div>
      <div className="num">{line.quantity}</div>
      <div>{line.derivation}</div>
      <div className="num">{eur(line.amountMinor)}</div>
    </div>
  );
}

/**
 * The unit's itemised statement (Rule: PM-FEE-018): per period, every line with the key applied, the count it used and
 * the derivation, as the API words it; consumption lines apart, with their item and reading (Rule: PM-FEE-017). The
 * balance is the API's, from the ledger — the web sums nothing.
 */
// Rule: PM-FEE-018, PM-FEE-017
function Statement({ statement }: { statement: Schemas["UnitStatement"] }) {
  const periods = byPeriod(statement.lines);
  return (
    <div style={BOX} role="region" aria-label="Извлечение">
      <div className="acct-title">Извлечение · салдо {eur(statement.balanceMinor)}</div>
      {periods.length === 0 && <div>Няма начисления за обекта.</div>}
      {periods.length > 0 && (
        <div style={{ ...ROW, fontWeight: 500 }}>
          <div>Перо</div><div>Ключ</div><div className="num">Брой / показание</div><div>Как е изчислено</div><div className="num">Сума</div>
        </div>
      )}
      {periods.map((p) => (
        <div key={p.period} style={{ marginTop: 10 }}>
          <div style={{ fontWeight: 500 }}>Период {p.period}</div>
          {p.keyed.map((l, i) => <Line key={`k${i}`} line={l} />)}
          {p.metered.length > 0 && <div className="as-hint">По потребление — отделно от законовите ключове</div>}
          {p.metered.map((l, i) => <Line key={`m${i}`} line={l} />)}
        </div>
      ))}
    </div>
  );
}

/**
 * A unit's statement and arrears (WEB-40). One screen, one server-side aggregation (ADR-011): the units to choose from
 * (the registry's), and for the unit asked, its statement and its arrears as of `?asOf=` (today in Sofia unless given).
 * Who may read them is the API's (PM-SEC-002); a refusal is shown in its words.
 */
// Rule: PM-FEE-018, PM-DEBT-001
export default async function StatementPage({ searchParams }: { searchParams: Promise<{ entrance?: string; unit?: string; asOf?: string }> }) {
  const asked = await searchParams;
  const view = await entranceAt(asked.entrance);
  const unit = typeof asked.unit === "string" && asked.unit ? asked.unit : null;
  const asOf = typeof asked.asOf === "string" && asked.asOf ? asked.asOf : sofiaToday();
  const loaded = view.kind === "ok"
    ? await reach(() => Promise.all([
      api.GET("/api/registry/entrances/{entranceId}/units", { params: { path: { entranceId: view.entrance.id } } }),
      unit ? api.GET("/api/money/units/{unitId}/statement", { params: { path: { unitId: unit } } }) : null,
      unit ? api.GET("/api/money/units/{unitId}/arrears", { params: { path: { unitId: unit }, query: { asOf } } }) : null,
    ]))
    : null;
  const [units, statement, arrears] = loaded ?? [null, null, null];
  const designation = new Map((units?.data ?? []).map((u) => [u.id, u.designation]));
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Сметка на обект{unit && <> · {designation.get(unit) ?? unit}</>}</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {!loaded && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          <form method="get" action="/entrance/statement" className="as-form" style={{ margin: "16px 24px" }}>
            <input type="hidden" name="entrance" value={view.entrance.id} />
            {units && !units.data && <div className="wide as-refused" role="alert">{unansweredBy(units, "обектите на входа")}</div>}
            <label>Обект
              <select name="unit" defaultValue={unit ?? ""}>
                <option value="">— изберете</option>
                {(units?.data ?? []).map((u) => <option key={u.id} value={u.id}>{u.designation}</option>)}
              </select>
            </label>
            <label>Задължения към<input type="date" name="asOf" defaultValue={asOf} /></label>
            <div className="wide"><button type="submit">Покажи</button></div>
          </form>
          {arrears?.data && <Arrears arrears={arrears.data} />}
          {arrears && !arrears.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(arrears, "задълженията на обекта")}</div>}
          {statement?.data && <Statement statement={statement.data} />}
          {statement && !statement.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(statement, "извлечението на обекта")}</div>}
        </>
      ) : (
        <div style={{ padding: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
          {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
          {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход в <code>registry</code>, после опреснете.</>}
          {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
        </div>
      )}
    </>
  );
}
