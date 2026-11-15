import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { unansweredBy } from "@/lib/refusal";
import { statusOf } from "../status";
import RevertForm from "./RevertForm";

export const metadata: Metadata = { title: "Внасяне — Етаж" };

/**
 * One recorded import (ADR-012; STAGE1-ADDENDUM §1) — its status, its counts, the hash of the sheet it holds and, when
 * a commit or a revert was blocked, why — as the API reads it with the signed-in person's bearer. A commit or a revert
 * is settled after its answer, so the page is read again by a link, never polled. Who may read it is the API's (the
 * manager or the board, G-1); a refusal is shown in its words. The commit is asked in the intake's form, which carries
 * the sheet the API checks against this hash.
 */
// Rule: PM-SEC-001, PM-FEE-014
export default async function ImportPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ entrance?: string }> }) {
  const { id } = await params;
  const entrance = (await searchParams).entrance;
  const view = await entranceAt(entrance);
  const again = `/entrance/intake/${id}${entrance ? `?entrance=${encodeURIComponent(entrance)}` : ""}`;
  const answer = await reach(() => api.GET("/api/intake/imports/{id}", { params: { path: { id } } }));
  const record = answer?.data;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <Link href={`/entrance/intake${entrance ? `?entrance=${encodeURIComponent(entrance)}` : ""}`} className="meta">Внасяне на таблица /</Link>
          <span className="meta">{id}</span>
        </div>
      </div>
      <div style={{ padding: "16px 24px" }}>
        <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
          {!answer && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {answer && !record && unansweredBy(answer, "внасянето")}
          {record && (
            <>
              <div className="as-form">
                <div className="wide acct-title">Внасяне {record.id}: {statusOf(record.status)}</div>
                <div className="wide">Редове: {record.rowsParsed} · различават се: {record.differing} · нарушения: {record.violations}</div>
                <div className="wide">Съдържание (SHA-256): <code>{record.sourceSha}</code></div>
                {record.commitBlockedBy && <div className="wide as-refused" role="status">Приемането е спряно: {record.commitBlockedBy}</div>}
                {record.revertBlockedBy && <div className="wide as-refused" role="status">Отмяната е спряна: {record.revertBlockedBy}</div>}
                <div className="wide"><a href={again} className="chip">Прочети отново</a></div>
              </div>
              <RevertForm id={record.id} again={again} />
            </>
          )}
        </div>
      </div>
    </>
  );
}
