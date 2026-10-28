import type { Metadata } from "next";
import Link from "next/link";
import { randomUUID } from "node:crypto";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, eur, reach } from "@/lib/console";
import { sofiaToday } from "@/lib/payment";
import { unanswered, unansweredBy } from "@/lib/refusal";
import PaymentForm from "./PaymentForm";

export const metadata: Metadata = { title: "Плащания — Етаж" };

const RULE: Record<string, string> = {
  OLDEST_FIRST: "към най-старото задължение първо",
  DESIGNATED: "първо към задължението, посочено от платеца, остатъкът — към най-старото",
};
const INTO: Record<string, string> = { OPERATING: "оперативната сметка", REPAIR_RENEWAL: "сметката на фонд „Ремонт и обновяване“", CASH: "в брой" };

/**
 * A payment's allocation as the API reads it back (Rule: PM-DEBT-008 — the rule applied, visible, and what each debt
 * received). Who may read it is the API's (PM-SEC-002, enforced there); a refusal is shown in its words. Every figure is the API's.
 */
// Rule: PM-DEBT-008
function Allocation({ payment, unit }: { payment: Schemas["PaymentView"]; unit: string }) {
  return (
    <div style={{ margin: "16px 24px", background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }} role="status">
      <div className="acct-title">Плащане {eur(payment.amountMinor)} · {unit} · {payment.valueDate} · {INTO[payment.receivedInto] ?? payment.receivedInto}</div>
      <div>
        Разпределено {RULE[payment.allocationRule] ?? payment.allocationRule} ({payment.allocationRule})
        {payment.designatedDebtDate && <> · посочено задължение от {payment.designatedDebtDate}</>}
      </div>
      {payment.allocation.length === 0
        ? <div>Обектът няма задължение към датата на плащането — нищо не е погасено.</div>
        : (
          <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
            {payment.allocation.map((p) => <li key={p.debtDate}>Задължение от {p.debtDate}: {eur(p.amountMinor)}</li>)}
          </ul>
        )}
      <div>Неразпределено (аванс): {eur(payment.unallocatedMinor)}</div>
      <div className="as-hint"><code>{payment.paymentId}</code></div>
    </div>
  );
}

/**
 * A payment received, recorded and its allocation shown (WEB-39). One screen, one server-side aggregation (ADR-011):
 * the units to choose from (the registry's), and, after a record, the payment as the API reads it back (`?payment=`).
 * The key the form is sent with is minted here, once per form.
 */
// Rule: PM-DEBT-008
export default async function PaymentsPage({ searchParams }: { searchParams: Promise<{ entrance?: string; payment?: string }> }) {
  const asked = await searchParams;
  const view = await entranceAt(asked.entrance);
  const loaded = view.kind === "ok"
    ? await reach(() => {
      const path = { entranceId: view.entrance.id };
      return Promise.all([
        api.GET("/api/registry/entrances/{entranceId}/units", { params: { path } }),
        typeof asked.payment === "string" ? api.GET("/api/money/entrances/{entranceId}/payments/{paymentId}", { params: { path: { ...path, paymentId: asked.payment } } }) : null,
      ]);
    })
    : null;
  const [units, payment] = loaded ?? [null, null];
  const designation = new Map((units?.data ?? []).map((u) => [u.id, u.designation]));
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Плащания</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {payment?.data && <Allocation payment={payment.data} unit={designation.get(payment.data.unitId) ?? payment.data.unitId} />}
          {payment && !payment.data && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(payment, "разпределението на плащането")}</div>}
          {!loaded && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          <PaymentForm
            entrance={view.entrance.id}
            units={(units?.data ?? []).map((u) => ({ id: u.id, designation: u.designation }))}
            unread={units && !units.data ? unansweredBy(units, "обектите на входа") : undefined}
            today={sofiaToday()}
            mintedKey={randomUUID()}
          />
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
