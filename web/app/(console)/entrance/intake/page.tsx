import type { Metadata } from "next";
import Link from "next/link";
import { API_URL } from "@/lib/api/client";
import { entranceAt } from "@/lib/console";
import { unanswered } from "@/lib/refusal";
import IntakeForm from "./IntakeForm";

export const metadata: Metadata = { title: "Внасяне на таблица — Етаж" };

/**
 * A firm's existing fee sheet brought in (ADR-012): format-agnostic — the columns are mapped onto the domain's fields
 * per import, never read as one canonical layout. This screen holds the first three steps, upload, proposal and
 * confirmation; the dry-run and the import follow. Who may do it is the API's (the manager or the board, G-1).
 */
export default async function IntakePage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
  const view = await entranceAt((await searchParams).entrance);
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Внасяне на таблица · колони</span>
        </div>
      </div>
      <div style={{ padding: "16px 24px" }}>
        <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
          {view.kind === "ok" ? (
            <IntakeForm entrance={view.entrance.id} />
          ) : (
            <>
              {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
              {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
              {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход в <code>registry</code>, после опреснете.</>}
              {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
            </>
          )}
        </div>
      </div>
    </>
  );
}
