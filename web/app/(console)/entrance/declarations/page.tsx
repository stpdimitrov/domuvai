import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { DeclarationForm, RetentionForm } from "./DeclarationForm";

export const metadata: Metadata = { title: "Декларации за книгата — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;

/**
 * A declaration filed for the book, and the retention run (WEB-47). The page reads only the entrance's units, for the
 * choice; who is past the deadline is on the entrance's own page and what was filed is read through the book (WEB-44).
 * Who may file, record or run the retention is the API's (Rule: PM-SEC-001): both forms are offered whatever the list
 * said, and a refusal is shown in its words.
 */
// Rule: PM-BOOK-003, PM-BOOK-004, PM-BOOK-005, PM-BOOK-010, PM-SEC-001
export default async function DeclarationsPage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
  const view = await entranceAt((await searchParams).entrance);
  const listed = view.kind === "ok"
    ? await reach(() => api.GET("/api/registry/entrances/{entranceId}/units", { params: { path: { entranceId: view.entrance.id } } }))
    : null;
  const units = (listed?.data ?? []).map((u) => ({ id: u.id, designation: u.designation }))
    .sort((a, b) => a.designation.localeCompare(b.designation, "bg", { numeric: true }) || a.id.localeCompare(b.id));
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Декларации за книгата</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {!listed && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {listed && !listed.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(listed, "обектите на входа")}</div>}
          <DeclarationForm entrance={view.entrance.id} units={units} />
          <RetentionForm entrance={view.entrance.id} />
          <div style={BODY} className="as-hint">
            Кой е просрочил декларацията си е на <Link href={`/entrance?entrance=${view.entrance.id}`}>страницата на входа</Link>; подаденото се чете в{" "}
            <Link href={`/entrance/book?entrance=${view.entrance.id}`}>книгата</Link> — с цел на четенето.
          </div>
        </>
      ) : (
        <div style={BODY}>
          {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
          {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход в <code>registry</code>, после опреснете.</>}
          {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
        </div>
      )}
    </>
  );
}
