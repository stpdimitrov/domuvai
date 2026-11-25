import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, eur, reach } from "@/lib/console";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { lineWords } from "@/lib/basis";
import NoEntrance from "@/app/(console)/NoEntrance";
import { passedDecisions } from "../decisions";
import TariffForm from "./TariffForm";

export const metadata: Metadata = { title: "Тарифни версии — Етаж" };

/**
 * The entrance's tariff versions, newest first, and the form that records a new one (WEB-61). Rule: PM-FEE-012 — each
 * version is shown with its lines, consumption prices, decision and months, every figure the API's. Rule: PM-FEE-015 —
 * a version is shown and never edited or deleted: a changed tariff is a new version. Rule: PM-SEC-001 — who may read or
 * record is the API's; a refusal is shown in its words.
 */
// Rule: PM-FEE-012, PM-FEE-015, PM-SEC-001
export default async function TariffPage({ searchParams }: { searchParams: Promise<{ entrance?: string; done?: string }> }) {
  const asked = await searchParams;
  const view = await entranceAt(typeof asked.entrance === "string" ? asked.entrance : undefined);
  const loaded = view.kind === "ok" ? await reach(() => api.GET("/api/money/entrances/{entranceId}/tariff-versions", { params: { path: { entranceId: view.entrance.id } } })) : null;
  const decisions = view.kind === "ok" ? await passedDecisions(view.entrance.id) : null;
  const done = typeof asked.done === "string" ? asked.done : undefined;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill"><span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span></span>
          <span className="meta">Тарифни версии</span>
          {view.kind === "ok" && <Link href={`/entrance/charges?entrance=${view.entrance.id}`} className="chip">◀ Начисления</Link>}
        </div>
      </div>
      {view.kind === "down" && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
      {view.kind === "unlisted" && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unanswered(view, "списъка на входовете")}</div>}
      {view.kind === "none" && <div style={{ padding: "16px 24px" }}><NoEntrance /></div>}
      {view.kind === "unknown" && <div style={{ padding: "16px 24px" }}>Входът <code>{view.id}</code> не е регистриран.</div>}
      {view.kind === "ok" && (
        <>
          {done && <div role="status" style={{ margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>Тарифната версия е записана: <code>{done}</code>.</div>}
          {!loaded && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {loaded && !loaded.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(loaded, "тарифните версии")}</div>}
          {loaded?.data && loaded.data.length === 0 && <div style={{ margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>Няма записана тарифна версия — без решение на общото събрание не се начислява (PM-FEE-012).</div>}
          {loaded?.data?.map((v) => (
            <section key={v.id} style={{ margin: "16px 24px 0", background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "12px 16px" }}>
              <div style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>
                В сила от {v.validFrom}{v.validTo ? ` до ${v.validTo}` : ""}
              </div>
              <div style={{ font: "400 12.5px/1.6 'IBM Plex Sans'", color: "#3D413E", marginTop: 6 }}>
                {[...v.lines.map(lineWords), ...v.consumption.map((m) => `${m.item} ${eur(m.priceMinor)}`)].join(" · ") || "—"}
              </div>
              <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 4 }}>
                По решение на общото събрание <code>{v.decisionId}</code> · версия <code>{v.id}</code>
              </div>
            </section>
          ))}
          <TariffForm
            entrance={view.entrance.id}
            decisions={decisions && "passed" in decisions ? decisions.passed : []}
            unread={decisions && "refused" in decisions ? decisions.refused : undefined}
          />
        </>
      )}
    </>
  );
}
