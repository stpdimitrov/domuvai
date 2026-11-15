import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL } from "@/lib/api/client";
import { entranceAt, reach } from "@/lib/console";
import { byPurpose, FUND_OFFICES, fundMissing, HOLDER, PURPOSE, unheld, type Account } from "@/lib/fundAccount";
import { unanswered, unansweredBy } from "@/lib/refusal";
import AccountForm from "./AccountForm";
import NoEntrance from "@/app/(console)/NoEntrance";
import PartyPicker from "../../PartyPicker";

export const metadata: Metadata = { title: "Банкови сметки — Етаж" };

/**
 * The entrance's bank accounts as the API lists them (WEB-41). Rule: PM-FUND-004 — each account's IBAN, its holder and
 * in whose name it is opened, the fund's apart from the operating account. Rule: PM-FUND-001 — an entrance with no
 * "Ремонт и обновяване" account is flagged. Who may read the list is the API's (PM-SEC-002, PM-GOV-008, enforced there);
 * a refusal is shown in its words. One screen, one server-side read (ADR-011); the form is offered whatever the list
 * said, and the API decides whether it may be used (PM-SEC-001).
 */
// Rule: PM-FUND-004, PM-FUND-001
export default async function AccountsPage({ searchParams }: { searchParams: Promise<{ entrance?: string; registered?: string }> }) {
  const asked = await searchParams;
  const view = await entranceAt(asked.entrance);
  const listed = view.kind === "ok"
    ? await reach(() => api.GET("/api/money/entrances/{entranceId}/fund-accounts", { params: { path: { entranceId: view.entrance.id } } }))
    : null;
  const accounts: Account[] | null = listed?.data ?? null;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Банкови сметки</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {!listed && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {listed && !accounts && <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>{unansweredBy(listed, "сметките на входа")}</div>}
          {accounts && fundMissing(accounts) && (
            <div className="as-refused" role="alert" style={{ margin: "16px 24px" }}>
              Входът няма регистрирана сметка на фонд „Ремонт и обновяване“. Фондът се създава от общото събрание и се поддържа задължително (чл. 50 ал. 1 ЗУЕС, PM-FUND-001).
            </div>
          )}
          {accounts && <Accounts accounts={accounts} registered={asked.registered} />}
          {/* the list unread: every purpose is offered, and the API says which is held */}
          {(accounts ? unheld(accounts) : Object.keys(PURPOSE)).length > 0 && (
            <>
              <PartyPicker id="parties-fund-holder" entrance={view.entrance.id} source="mandates" offices={FUND_OFFICES} margin="16px 24px 0" />
              <AccountForm entrance={view.entrance.id} purposes={accounts ? unheld(accounts) : Object.keys(PURPOSE)} parties="parties-fund-holder" />
            </>
          )}
          <div style={{ margin: "0 24px 16px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            <Link href={`/entrance/fund?entrance=${view.entrance.id}`}>Към касата и фонда</Link>
          </div>
        </>
      ) : (
        <div style={{ padding: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
          {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
          {view.kind === "none" && <NoEntrance />}
          {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
        </div>
      )}
    </>
  );
}

/** Each purpose on its own (PM-FUND-004): its account as the API stores it, or that none is registered. */
// Rule: PM-FUND-004
function Accounts({ accounts, registered }: { accounts: Account[]; registered?: string }) {
  return (
    <>
      {byPurpose(accounts).map(({ purpose, accounts: held }) => (
        <div key={purpose} style={{ margin: "16px 24px", background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
          <div className="acct-title">{PURPOSE[purpose] ?? purpose}</div>
          {held.length === 0 && <div>Няма регистрирана сметка.</div>}
          {held.map((a) => (
            <div key={a.id} role={a.id === registered ? "status" : undefined}>
              <div>IBAN <code>{a.iban}</code>{a.id === registered && <> · регистрирана току-що</>}</div>
              <div>Титуляр: {a.holderName} · на името на {HOLDER[a.holderKind] ?? a.holderKind} ({a.holderKind})</div>
            </div>
          ))}
        </div>
      ))}
    </>
  );
}
