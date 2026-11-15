import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { petitionIdOf } from "@/lib/petition";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { sofiaLocal } from "@/lib/sofia";
import { ConveneOnPetitionForm, OpenForm, SignForm } from "./PetitionForms";
import NoEntrance from "@/app/(console)/NoEntrance";

export const metadata: Metadata = { title: "Петиции за събрание — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;

/**
 * An assembly petition (WEB-48): opened, read as weighed today, signed, convened on. No list is served, so a petition is
 * found by the id opening it answered (typed here). What the signatories hold against the threshold, and why it cannot be
 * weighed, are the API's — shown as answered, no number typed here (Rule: PM-GA-003 ⚠). Who may open, sign or convene is
 * the API's (Rule: PM-SEC-001): every form is offered, and a refusal is shown in its words.
 */
// Rule: PM-GA-003, PM-SEC-001
export default async function PetitionsPage({ searchParams }: { searchParams: Promise<{ entrance?: string; petition?: string }> }) {
  const { entrance: asked, petition: typed } = await searchParams;
  const view = await entranceAt(asked);
  const id = petitionIdOf(typed);
  const read = view.kind === "ok" && id
    ? await reach(() => api.GET("/api/assembly/entrances/{entranceId}/petitions/{petitionId}", { params: { path: { entranceId: view.entrance.id, petitionId: id } } }))
    : null;
  const p = read?.data;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill"><span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span></span>
          <span className="meta">Петиции за свикване на събрание</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          <OpenForm entrance={view.entrance.id} />
          <form method="get" action="/entrance/petitions" className="as-form" style={{ margin: "16px 24px" }}>
            <input type="hidden" name="entrance" value={view.entrance.id} />
            <div className="wide acct-title">Прочитане на петиция</div>
            <label className="wide">Номер на петицията<input type="text" name="petition" defaultValue={typed} autoComplete="off" required /></label>
            <div className="wide"><button type="submit" className="chip active">Покажи</button></div>
          </form>
          {typed && !id && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Номерът на петицията не е във вида, в който го дава системата.</div>}
          {id && !read && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {read && !p && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(read, "петицията")}</div>}
          {p && id && (
            <>
              <div style={BODY} role="status">
                <div className="acct-title">{p.subject}</div>
                <div>Открита на {sofiaLocal(p.openedAt)} · номер <code>{p.id}</code></div>
                <div>
                  Подписали: {p.signatories.length}. Събрани {p.heldPct}% от идеалните части при праг {p.thresholdPct}% ({p.thresholdSource}
                  {p.thresholdVerified ? "" : "; прагът не е потвърден"}) към {p.weighedOn}{p.derivedParts ? " — идеалните части са изведени" : ""}.
                </div>
                <div>{p.unlocked ? "Отключена: подписалите могат да свикат събранието." : "Не е отключена."}</div>
                {p.cannotWeigh.length > 0 && <div>Не може да се претегли: {p.cannotWeigh.join("; ")}</div>}
                {p.assemblyId && <div>Свикано събрание: <Link href={`/entrance/assembly?entrance=${view.entrance.id}&assembly=${p.assemblyId}`}>{p.assemblyId}</Link></div>}
                <div className="as-hint">Подписалите се показват с номера си, докато имената не се четат от системата.</div>
                <ul>{p.signatories.map((s) => <li key={s}><code>{s}</code></li>)}</ul>
              </div>
              <SignForm entrance={view.entrance.id} petition={id} />
              <ConveneOnPetitionForm entrance={view.entrance.id} petition={id} />
            </>
          )}
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
