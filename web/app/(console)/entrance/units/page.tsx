import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { typesIn } from "@/lib/units";
import { TitleForm } from "./TitleForm";
import PartyPicker from "../PartyPicker";
import { UnitsForm } from "./UnitsForm";
import NoEntrance from "@/app/(console)/NoEntrance";

export const metadata: Metadata = { title: "Обекти и идеални части — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;
const SOURCE: Record<string, string> = { DECLARED: "по нотариален акт", DERIVED: "изчислени по площ" };

/**
 * The entrance's units with their ideal parts as the API serves them, and the form that registers them (WEB-50b); under it, the form
 * that records a title for one of them (WEB-51: Rule: PM-ORG-005, PM-ORG-011, PM-BOOK-005). The sum
 * (Rule: PM-ORG-002), the derivation (Rule: PM-ORG-003) and who may enter the book (Rule: PM-SEC-001) are the API's: the page
 * adds, derives and gates nothing, and offers the form whatever the list said.
 */
// Rule: PM-ORG-002, PM-ORG-003, PM-SEC-001
export default async function UnitsPage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
  const view = await entranceAt((await searchParams).entrance);
  const listed = view.kind === "ok"
    ? await reach(() => api.GET("/api/registry/entrances/{entranceId}/units", { params: { path: { entranceId: view.entrance.id } } }))
    : null;
  const units = [...(listed?.data ?? [])].sort((a, b) => a.designation.localeCompare(b.designation, "bg", { numeric: true }) || a.id.localeCompare(b.id));
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill"><span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span></span>
          <span className="meta">Обекти и идеални части</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          <div style={BODY}>
            <div className="acct-title">Обекти във входа</div>
            {!listed && <div className="as-refused" role="alert">Бекендът не отговаря на <code>{API_URL}</code>.</div>}
            {listed && !listed.data && <div className="as-refused" role="alert">{unansweredBy(listed, "обектите на входа")}</div>}
            {listed?.data && units.length === 0 && <div>Във входа няма вписан обект.</div>}
            <ul>
              {units.map((u) => (
                <li key={u.id}>
                  {u.designation} · {u.unitType} · {u.areaM2 != null ? `${u.areaM2} кв. м` : "без площ"} · идеални части {u.idealPartsPct}% ({SOURCE[u.idealPartsSource] ?? u.idealPartsSource}){u.businessUse && " · стопанска дейност"}{u.separateEntrance && " · самостоятелен вход"}
                </li>
              ))}
            </ul>
          </div>
          <UnitsForm entrance={view.entrance.id} types={typesIn(units)} />
          <PartyPicker id="parties-title" entrance={view.entrance.id} margin="16px 24px 0" />
          <TitleForm entrance={view.entrance.id} units={units.map((u) => ({ id: u.id, label: `${u.designation} · ${u.unitType}` }))} parties="parties-title" />
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
