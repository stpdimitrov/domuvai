import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { sofiaToday } from "@/lib/payment";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { HouseholdForm, LeavingForm } from "./HouseholdForm";

export const metadata: Metadata = { title: "Живущи в обектите — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;

/**
 * A unit's household entered in the book, and a resident's leaving recorded (WEB-45). The page reads only the entrance's
 * units, for the choice; the household written is read back through the book's own read, with a purpose (WEB-44). Who
 * may enter the book is the API's (Rule: PM-BOOK-005, PM-SEC-001): the forms are offered whatever the list said, and a
 * refusal is shown in its words.
 */
// Rule: PM-FEE-005, PM-FEE-008, PM-BOOK-008, PM-BOOK-005, PM-SEC-001
export default async function HouseholdPage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
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
          <span className="meta">Живущи в обектите</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {!listed && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {listed && !listed.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(listed, "обектите на входа")}</div>}
          <HouseholdForm entrance={view.entrance.id} units={units} />
          <LeavingForm entrance={view.entrance.id} units={units} today={sofiaToday()} />
          <div style={BODY} className="as-hint">
            Вписаното се чете в <Link href={`/entrance/book?entrance=${view.entrance.id}`}>книгата</Link> — с цел на четенето.
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
