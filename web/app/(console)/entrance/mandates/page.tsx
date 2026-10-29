import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { OFFICE } from "@/lib/mandate";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { EndForm, RecordForm } from "./MandateForms";

export const metadata: Metadata = { title: "Мандати — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;
const date = (iso: string) => iso.split("-").reverse().join(".");
const STANDING: Record<string, string> = { IN_TERM: "в мандат", ELECTION_DUE: "изборът се дължи", EXPIRED_ACTING: "изпълнява след срока" };

/**
 * The mandates in force at the entrance today, and the forms that record one and end one (WEB-49).
 * "Today", the term and when an election is due are the API's (Rule: PM-GOV-004, PM-GOV-005) — shown as answered, no date
 * arithmetic here. Only the deployment's administrator may record or end one (Rule: PM-SEC-001): every form is
 * offered, and a refusal is shown in the API's words.
 */
// Rule: PM-GOV-004, PM-GOV-005, PM-SEC-001
export default async function MandatesPage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
  const { entrance: asked } = await searchParams;
  const view = await entranceAt(asked);
  const read = view.kind === "ok" ? await reach(() => api.GET("/api/identity/entrances/{entranceId}/mandates", { params: { path: { entranceId: view.entrance.id } } })) : null;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill"><span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span></span>
          <span className="meta">Мандати на управата</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          <div style={BODY}>
            <div className="acct-title">Мандати на входа днес</div>
            {!read && <div className="as-refused" role="alert">Бекендът не отговаря на <code>{API_URL}</code>.</div>}
            {read && !read.data && <div className="as-refused" role="alert">{unansweredBy(read, "мандатите на входа")}</div>}
            {read?.data?.length === 0 && <div>Няма вписан мандат в сила днес.</div>}
            <ul>
              {read?.data?.map((m) => (
                <li key={m.mandateId}>
                  {OFFICE[m.role] ?? m.role} · лице <code>{m.partyId ?? "—"}</code> · от {date(m.validFrom)} до {date(m.validTo)} · {STANDING[m.standing.kind] ?? m.standing.kind}
                  {m.standing.actingSince && <> от {date(m.standing.actingSince)}</>}{m.standing.electBy && <> (изборът — до {date(m.standing.electBy)})</>}{m.standing.until && <> (в сила до {date(m.standing.until)})</>} · номер на мандата <code>{m.mandateId}</code>
                </li>
              ))}
            </ul>
            <div className="as-hint">Лицата се показват с номерата си, докато имената не се четат от системата.</div>
          </div>
          <RecordForm entrance={view.entrance.id} />
          <EndForm entrance={view.entrance.id} />
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
