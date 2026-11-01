import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { PAPER_DECISIONS } from "@/lib/paperDecision";
import { unanswered, unansweredBy } from "@/lib/refusal";
import NoEntrance from "@/app/(console)/NoEntrance";
import PaperDecisionForm from "./PaperDecisionForm";
import PaperRecord from "./PaperRecord";

export const metadata: Metadata = { title: "Решения от хартиени протоколи — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;

/**
 * The decisions the entrance's general assembly took on paper, and the form that records one (WEB-54). The paper is the
 * original and the page says so; every record names where it is kept (Rule: PM-GA-022, PM-DOC-007). Who may read,
 * who may record and who is told the custodian are the API's (Rule: PM-SEC-001, PM-SEC-002): the form is offered to
 * everyone, and a refusal is shown in the API's words.
 */
// Rule: PM-GA-022, PM-DOC-007, PM-SEC-001, PM-SEC-002
export default async function PaperDecisionsPage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
  const { entrance: asked } = await searchParams;
  const view = await entranceAt(asked);
  const read = view.kind === "ok"
    ? await reach(() => api.GET("/api/assembly/entrances/{entranceId}/paper-decisions", { params: { path: { entranceId: view.entrance.id } } }))
    : null;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill"><span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span></span>
          <span className="meta">Хартиени протоколи</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          <div style={BODY}>
            <div className="acct-title">Решения от хартиени протоколи</div>
            <div className="as-hint">
              Оригиналът е хартиеният протокол: пази го управата, а записът тук и всяко сканирано копие не го заместват.
              Вписаното решение може да се посочи при начисления и при разход от фонда.
            </div>
            {!read && <div className="as-refused" role="alert">Бекендът не отговаря на <code>{API_URL}</code>.</div>}
            {read && !read.data && <div className="as-refused" role="alert">{unansweredBy(read, PAPER_DECISIONS)}</div>}
            {read?.data?.length === 0 && <div>Няма вписано решение от хартиен протокол.</div>}
            <ul>
              {read?.data?.map((d) => <li key={d.id}><PaperRecord decision={d} /></li>)}
            </ul>
          </div>
          <PaperDecisionForm entrance={view.entrance.id} />
        </>
      ) : (
        <div style={BODY}>
          {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
          {view.kind === "none" && <NoEntrance />}
          {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
        </div>
      )}
    </>
  );
}
