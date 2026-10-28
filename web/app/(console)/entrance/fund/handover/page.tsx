import type { Metadata } from "next";
import Link from "next/link";
import { randomUUID } from "node:crypto";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, eur, reach } from "@/lib/console";
import { PURPOSE } from "@/lib/disbursement";
import { matches, NOT_SIGNED } from "@/lib/handover";
import { sofiaToday } from "@/lib/payment";
import { unanswered, unansweredBy } from "@/lib/refusal";
import HandoverForm from "./HandoverForm";

export const metadata: Metadata = { title: "Предаване на фонда — Етаж" };

type View = Schemas["HandoverStatementView"];

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;
const date = (iso?: string) => (iso ? iso.slice(0, 10).split("-").reverse().join(".") : "—");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A fund handover statement issued, listed and opened (WEB-43). One screen, one server-side aggregation (ADR-011): the
 * statements as the API issued them, newest first, and — with `?statement=<id>` — one read back through its own
 * endpoint, where the API has checked its basis against its hash. Every figure is the API's. The web repeats the hash
 * check on the text it shows, so a mismatch here means the bytes changed on the way. Every statement carries the one
 * line saying it is not signed by the parties yet (#57). Rule: PM-FUND-010. Reading is the API's call (READ_FUND), its 403
 * shown in its words (PM-SEC-001); the form is shown to everyone signed in and issuing is the API's call too.
 */
// Rule: PM-FUND-010, PM-SEC-001
export default async function HandoverPage({ searchParams }: { searchParams: Promise<{ entrance?: string; statement?: string }> }) {
  const asked = await searchParams;
  const view = await entranceAt(asked.entrance);
  const statementId = asked.statement && UUID.test(asked.statement) ? asked.statement : undefined;
  const loaded = view.kind === "ok"
    ? await reach(() => Promise.all([
      api.GET("/api/money/entrances/{entranceId}/fund/handover-statements", { params: { path: { entranceId: view.entrance.id } } }),
      statementId
        ? api.GET("/api/money/entrances/{entranceId}/fund/handover-statements/{statementId}", { params: { path: { entranceId: view.entrance.id, statementId } } })
        : Promise.resolve(null),
    ]))
    : null;
  const [list, opened] = loaded ?? [null, null];
  const link = (id: string) => `/entrance/fund/handover?entrance=${view.kind === "ok" ? view.entrance.id : ""}&statement=${id}`;
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Предаване на фонда</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <>
          {!loaded && <div className="as-refused" role="alert" style={BODY}>Бекендът не отговаря на <code>{API_URL}</code>.</div>}
          {asked.statement && !statementId && <div className="as-refused" role="alert" style={BODY}>„{asked.statement}“ не е идентификатор на отчет.</div>}
          {opened && !opened.data && <div className="as-refused" role="alert" style={BODY}>{unansweredBy(opened, "отчета")}</div>}
          {opened?.data && <Opened view={opened.data} />}
          <HandoverForm entrance={view.entrance.id} today={sofiaToday()} mintedKey={randomUUID()} />
          <div style={BODY}>
            <div className="acct-title">Издадени отчети · най-новият първо</div>
            {list && !list.data && <div className="as-refused" role="alert">{unansweredBy(list, "отчетите")}</div>}
            {list?.data?.length === 0 && <div className="as-hint">Няма издадени отчети при предаване.</div>}
            {list?.data?.map(({ id, statement: s }) => (
              <div key={id} style={{ borderTop: "1px solid #DEDDD9", padding: "10px 0" }}>
                <div>
                  {date(s.handoverOn)} · {s.from ? `${date(s.from)} – ${date(s.handoverOn)}` : `до ${date(s.handoverOn)}`}
                  {" · "}от <code>{s.outgoingPartyId}</code> към <code>{s.incomingPartyId}</code>
                </div>
                <div>
                  крайно по книгата {eur(s.closingMinor)} · банка {eur(s.bankBalanceMinor)} · разлика {eur(s.differenceMinor)}
                  {" · "}<span className={`badge ${s.reconciled ? "green" : "crit"}`}>{s.reconciled ? "Съвпада с банката" : "Разлика с банката"}</span>
                  {" · "}<Link href={link(id)}>отвори</Link>
                </div>
                <div className="as-hint">{NOT_SIGNED}</div>
              </div>
            ))}
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

/**
 * One statement as the API issued it: the movement of the fund, the two sides, the canonical basis verbatim, its hash,
 * the versions it was made under, and the web's own check of the hash against the text shown (Rule: PM-FUND-010).
 */
// Rule: PM-FUND-010
function Opened({ view: { id, statement: s, basis, basisHash, lawVersion, engineVersion } }: { view: View }) {
  const ok = matches(basis, basisHash);
  return (
    <div style={BODY} data-statement={id}>
      <div className="acct-title">Отчет от {date(s.issuedOn)} · предаване на {date(s.handoverOn)}</div>
      <div className="as-hint">{NOT_SIGNED}</div>
      <div>Предаваща страна <code>{s.outgoingPartyId}</code> · приемаща страна <code>{s.incomingPartyId}</code></div>
      <div>Сметка на фонда {s.iban} · {s.holderName}</div>
      <div>
        начално {eur(s.openingMinor)} + постъпления {eur(s.receivedMinor)} − изплатени {eur(s.paidOutMinor)} = крайно {eur(s.closingMinor)}
        {" · "}банка {eur(s.bankBalanceMinor)} · разлика {eur(s.differenceMinor)} · {s.reconciled ? "съвпада с банката" : "разлика с банката"}
      </div>
      <div>поети, неплатени {eur(s.committedMinor)} · разполагаемо след тях {eur(s.availableMinor)}</div>
      {s.inherited.map((d) => (
        <div key={d.disbursementId}>
          наследено: {PURPOSE[d.purpose] ?? d.purpose} · {d.decisionId ? `решение ${d.decisionId}` : `аварийно, без решение: ${d.emergencyJustification ?? "—"}`}
          {d.passportMeasure && <> · мярка: {d.passportMeasure}</>} · {eur(d.amountMinor)} · поето на {date(d.committedOn)}
        </div>
      ))}
      <div>закон {lawVersion} · двигател {engineVersion}</div>
      <div>хеш на основата <code>{basisHash}</code></div>
      <div className={ok ? undefined : "as-refused"} role={ok ? "status" : "alert"}>
        {ok
          ? "Хешът съвпада с показания текст на основата (SHA-256, UTF-8)."
          : "Хешът НЕ съвпада с показания текст на основата — текстът е променен по пътя. Не го използвайте."}
      </div>
      <div className="as-hint">Основата, както я е записал системата — всеки може да сметне SHA-256 от този текст:</div>
      <pre style={{ whiteSpace: "pre-wrap", wordBreak: "break-all", font: "400 11.5px/1.5 'IBM Plex Mono', monospace" }}>{basis}</pre>
    </div>
  );
}
