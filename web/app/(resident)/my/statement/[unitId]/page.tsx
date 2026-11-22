import { connection } from "next/server";
import { api } from "@/lib/api/client";
import { emptyStatement, lineText, owes, refusedStatement } from "@/lib/mine";
import { byPeriod } from "@/lib/statement";

/**
 * Rule: PM-FEE-018, PM-SEC-002, PM-SYS-015 — Моята сметка: one unit's statement, as the api serves it, newest period
 * first. The unitId in the path is never trusted: the api decides, and a refusal shows no figure. The statement is
 * the unit's, not the person's share of it. Server-rendered, no client script.
 */
// Rule: PM-FEE-018, PM-SEC-002, PM-SYS-015
export default async function MyStatement({ params }: { params: Promise<{ unitId: string }> }) {
  await connection();
  const { unitId } = await params;
  const r = await api.GET("/api/money/units/{unitId}/statement", { params: { path: { unitId } } }).catch(() => null);
  const back = <p><a href="/my">← Начало</a></p>;
  if (!r?.data) return <>{back}<h1>Моята сметка</h1><p>{refusedStatement(r ? r.response.status : null)}</p></>;
  const periods = byPeriod(r.data.lines).sort((a, b) => (a.period < b.period ? 1 : a.period > b.period ? -1 : 0));
  return (
    <>
      {back}
      <h1>Моята сметка</h1>
      <p className="amount">{owes(r.data.balanceMinor)}</p>
      {periods.length === 0 && <p>{emptyStatement}</p>}
      {periods.map((p) => (
        <section key={p.period} className="period">
          <h2>Период {p.period}</h2>
          <ul>
            {[...p.keyed, ...p.metered].map((l, i) => {
              const t = lineText(l);
              return <li key={i}><span>{t.what}<small>{t.how}</small></span><b>{t.amount}</b></li>;
            })}
          </ul>
        </section>
      ))}
    </>
  );
}
