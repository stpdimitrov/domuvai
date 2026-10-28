import type { Metadata } from "next";
import Link from "next/link";
import { randomUUID } from "node:crypto";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, eur, reach } from "@/lib/console";
import { NO_PARTY, PURPOSE } from "@/lib/disbursement";
import { sofiaToday } from "@/lib/payment";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { passedDecisions } from "../../charges/decisions";
import { CancelForm, PayForm } from "./PayCancelForms";
import SignOffForm from "./SignOffForm";

export const metadata: Metadata = { title: "Разходване на фонда — Етаж" };

const DONE: Record<string, string> = { committed: "Разходът е подписан и е поет.", paid: "Плащането е записано.", cancelled: "Разходът е оттеглен." };

/**
 * A disbursement signed off, paid or cancelled (WEB-42, WEB-42b). One screen, one server-side aggregation (ADR-011): the fund as the API reads it
 * (its figures shown, never recomputed — PM-FUND-009 is the API's), who is signed in, and this entrance's passed
 * decisions. The committed, unpaid disbursements each carry a pay and a cancel form; paid and cancelled ones are on
 * `/entrance/fund`. Every form's key is minted here, once per form.
 */
// Rule: PM-FUND-006, PM-FUND-007, PM-FUND-008, PM-SEC-001
export default async function DisbursementsPage({ searchParams }: { searchParams: Promise<{ entrance?: string; done?: string }> }) {
  const asked = await searchParams;
  const view = await entranceAt(asked.entrance);
  const loaded = view.kind === "ok"
    ? await reach(() => Promise.all([
      api.GET("/api/money/entrances/{entranceId}/fund", { params: { path: { entranceId: view.entrance.id } } }),
      api.GET("/api/identity/me"),
    ]))
    : null;
  const [fund, me] = loaded ?? [null, null];
  const party = me?.data?.partyId;
  const today = sofiaToday();
  const decisions = view.kind === "ok" && party ? await passedDecisions(view.entrance.id) : null;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Разходване на фонда</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {asked.done && DONE[asked.done] && <div role="status" style={{ margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>{DONE[asked.done]}</div>}
          {!loaded && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {fund && !fund.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(fund, "фонда")}</div>}
          {fund?.data && (
            <div style={{ margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
              Салдо {eur(fund.data.balanceMinor)} · Поети, неплатени {eur(fund.data.committedMinor)} · Разполагаемо {eur(fund.data.availableMinor)}
              {" · "}<Link href={`/entrance/fund?entrance=${view.entrance.id}`}>платените и оттеглените — в „Каса и фонд“</Link>
            </div>
          )}
          {me && !me.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(me, "кой сте в системата")}</div>}
          {me?.data && !me.data.partyId && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{NO_PARTY}</div>}
          {fund?.data && (
            <>
              {party && <SignOffForm
                entrance={view.entrance.id}
                decisions={decisions && "passed" in decisions ? decisions.passed : []}
                unread={decisions && "refused" in decisions ? decisions.refused : undefined}
                mintedKey={randomUUID()}
              />}
              <div style={{ margin: "16px 24px" }}>
                <div className="acct-title">Поети, неплатени</div>
                {fund.data.disbursements.filter((d) => d.status === "COMMITTED").map((d) => (
                  <div key={d.id} style={{ borderTop: "1px solid #DEDDD9", padding: "10px 0", font: "400 13px/1.6 'IBM Plex Sans'" }}>
                    <div>
                      {eur(d.amountMinor)} · {PURPOSE[d.purpose] ?? d.purpose} · подписан {d.committedOn}
                      {d.decisionId && <> · решение <code>{d.decisionId}</code></>}
                      {d.passportMeasure && <> · {d.passportMeasure}</>}
                      {d.emergencyJustification && <> · аварийно, без решение: {d.emergencyJustification}</>}
                    </div>
                    {party && <PayForm entrance={view.entrance.id} disbursement={d.id} today={today} mintedKey={randomUUID()} />}
                    {party && <CancelForm entrance={view.entrance.id} disbursement={d.id} mintedKey={randomUUID()} />}
                  </div>
                ))}
                {!fund.data.disbursements.some((d) => d.status === "COMMITTED") && <div className="as-hint">Няма поет, неплатен разход.</div>}
              </div>
            </>
          )}
        </>
      ) : (
        <div style={{ padding: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
          {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
          {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход в <code>registry</code>, после опреснете.</>}
          {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
        </div>
      )}
    </>
  );
}
