import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, eur, reach, type EntranceAt } from "@/lib/console";

export const metadata: Metadata = { title: "Вход — Етаж" };

type Entrance = Schemas["EntranceView"];
type Failed = { failed: string };

type Loaded =
  | {
      kind: "ok";
      entrance: Entrance;
      units: Schemas["UnitView"][];
      overdue: Schemas["OverdueDeclaration"][] | Failed;
      fund: Schemas["FundView"] | null | Failed;                    // null: none registered
      operating: Schemas["OperatingAccountView"] | null | Failed;
    }
  | Exclude<EntranceAt, { kind: "ok" }>
  | { kind: "refused"; entrance: Entrance; message: string };

const refusal = (r: { error?: { error?: string }; response: Response }) => r.error?.error ?? `HTTP ${r.response.status}`;
const failed = <T,>(value: T | Failed): value is Failed => value !== null && typeof value === "object" && "failed" in value;

/**
 * One screen, one server-side aggregation (ADR-011): the entrance, its units, who is past the deadline to declare
 * for the book (PM-BOOK-003 — the registry's list and its due dates, names only, PM-BOOK-011), and its two accounts
 * (PM-FUND-009). Every date and amount is the API's. The declarations and each account fail alone; without the units
 * there is no entrance to show.
 */
async function load(entranceId: string | undefined): Promise<Loaded> {
  const at = await entranceAt(entranceId);
  if (at.kind !== "ok") return at;
  const { entrance } = at;

  const path = { entranceId: entrance.id };
  const [units, overdue, fund, operating] = await Promise.all([
    reach(() => api.GET("/api/registry/entrances/{entranceId}/units", { params: { path } })),
    reach(() => api.GET("/api/registry/entrances/{entranceId}/book/declarations/overdue", { params: { path } })),
    reach(() => api.GET("/api/money/entrances/{entranceId}/fund", { params: { path } })),
    reach(() => api.GET("/api/money/entrances/{entranceId}/operating-account", { params: { path } })),
  ]);
  if (!units) return { kind: "down" };
  if (!units.data) return { kind: "refused", entrance, message: refusal(units) };
  return {
    kind: "ok",
    entrance,
    units: units.data,
    overdue: overdue?.data
      ?? { failed: overdue ? `API отказа просрочените декларации: ${refusal(overdue)}` : "Бекендът не подаде просрочените декларации." },
    // The API's 404 says which account the entrance does not have — a fact about it, said as one. Any other refusal,
    // another 404 among them, is shown as it came.
    fund: fund?.data
      ?? (fund?.response.status === 404 && refusal(fund).includes("PM-FUND-001") ? null
        : { failed: fund ? `API отказа фонда: ${refusal(fund)}` : "Бекендът не подаде фонда." }),
    operating: operating?.data
      ?? (operating?.response.status === 404 && refusal(operating).includes("no operating account") ? null
        : { failed: operating ? `API отказа оперативната сметка: ${refusal(operating)}` : "Бекендът не подаде оперативната сметка." }),
  };
}

// The registry keeps a unit's kind as text. The two the seed and the import write get a short word; any other is shown as it is.
const KIND: Record<string, string> = { APARTMENT: "жил.", SHOP: "търг." };
const FORM: Record<string, string> = { GA: "общо събрание", ASSOCIATION: "сдружение на собствениците", CLOSED_COMPLEX: "затворен комплекс" };
const ROLE: Record<string, string> = { OWN: "собственик", USR: "ползвател" };   // the registry's TitleRole

const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia" }).format(new Date());
const date = (iso: string) => iso.split("-").reverse().join(".");
const iban = (value: string) => value.replace(/\s+/g, "").replace(/(.{4})(?=.)/g, "$1 ");

/** The units by kind, counted by the word shown — most numerous first. */
const kinds = (units: Schemas["UnitView"][]) => {
  const by = new Map<string, number>();
  for (const u of units) {
    const word = KIND[u.unitType] ?? u.unitType.toLowerCase();
    by.set(word, (by.get(word) ?? 0) + 1);
  }
  return [...by].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], "bg")).map(([word, n]) => `${n} ${word}`).join(" / ");
};

const DIM = { color: "#6B6F6C" };
const FAILED = { color: "#8E2318" };
const NOTE = { font: "400 11px/1.5 'IBM Plex Sans'", color: "#6B6F6C", marginTop: 4 };

export default async function EntrancePage({ searchParams }: { searchParams: Promise<{ entrance?: string | string[] }> }) {
  const query = await searchParams;
  const view = await load(typeof query.entrance === "string" ? query.entrance : undefined);
  const entrance = view.kind === "ok" || view.kind === "refused" ? view.entrance : undefined;
  const late = view.kind === "ok" && !failed(view.overdue) ? view.overdue.length : 0;
  const q = entrance ? `?entrance=${entrance.id}` : "";

  // The design's tabs: the ones with a screen behind them are links, and carry the entrance.
  const tabs: { label: string; href?: string }[] = [
    { label: "Календар", href: `/entrance${q}` },
    { label: "Обекти" },
    { label: "Начисления", href: `/entrance/charges${q}` },
    { label: "Каса и фонд", href: `/entrance/fund${q}` },
    { label: "Събрания" },
    { label: "Задължения", href: "/debts" },
    { label: "Документи" },
  ];

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          {entrance && <span className="entrance-pill"><span className="name">{entrance.label}</span></span>}
          {view.kind === "ok" && <span className="meta">{count(view.units.length, "обект", "обекта")}</span>}
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          {late > 0 && <span className="pill-badge">{count(late, "просрочена декларация", "просрочени декларации")}</span>}
          <span className="today">{date(today())}</span>
        </div>
      </div>

      <div className="tabbar">
        {tabs.map((t, i) =>
          t.href ? (
            <Link key={t.label} href={t.href} className={`tab${i === 0 ? " active" : ""}`} style={{ textDecoration: "none" }}>{t.label}</Link>
          ) : (
            <span key={t.label} className="tab" title="Още не се води в системата." style={{ opacity: 0.45, cursor: "not-allowed" }}>{t.label}</span>
          ),
        )}
      </div>

      {view.kind !== "ok" ? (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "unlisted" && <>API отказа списъка на входовете: {view.message}</>}
            {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход и обектите му в <code>registry</code>, после опреснете.</>}
            {view.kind === "unknown" && <>Вход <code>{view.id}</code> не е регистриран. <Link href="/portfolio">Към портфейла</Link></>}
            {view.kind === "refused" && <>API отказа обектите на входа: {view.message}</>}
          </div>
        </div>
      ) : (
        <Body view={view} q={q} />
      )}
    </>
  );
}

function Body({ view, q }: { view: Extract<Loaded, { kind: "ok" }>; q: string }) {
  const { entrance, units, overdue, fund, operating } = view;
  return (
    <div className="entrance-body">
      <div className="panel-card">
        <div className="panel-head">
          <span className="panel-title">Статутен календар</span>
          <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>само декларациите за книгата</span>
        </div>

        <div className="panel-scroll">
          {failed(overdue) ? (
            <div style={{ ...FAILED, font: "400 13px/1.5 'IBM Plex Sans'" }}>{overdue.failed}</div>
          ) : (
            <>
              <div className="cal-group" style={{ color: overdue.length > 0 ? "#8E2318" : "#6B6F6C", padding: "0 0 9px" }}>
                Просрочени декларации · {overdue.length}
              </div>
              {overdue.length === 0 && (
                <div style={{ ...DIM, font: "400 13px/1.5 'IBM Plex Sans'", paddingBottom: 10 }}>Няма просрочена декларация за книгата на собствениците.</div>
              )}
              {overdue.map((d, i) => (
                <div className="cal-item" key={`${d.unitId}-${d.partyName}-${d.titleRole}-${d.acquiredOn}-${i}`}>
                  <div className="cal-date" style={{ color: "#8E2318" }} title="срокът за подаване, както го води регистърът">{date(d.dueOn)}</div>
                  <div className="cal-rail">
                    <span className="cal-dot" style={{ background: "#A32B23" }} />
                    {i < overdue.length - 1 && <span className="cal-line" />}
                  </div>
                  <div>
                    <div className="cal-title">Декларация за вписване в книгата — {d.designation}</div>
                    <div className="cal-law">чл. 7, ал. 3 ЗУЕС · {ROLE[d.titleRole] ?? d.titleRole.toLowerCase()} от {date(d.acquiredOn)}</div>
                  </div>
                  <div className="cal-side">
                    <span className="badge crit">Просрочена</span>
                    <span className="cal-note">{d.partyName}</span>
                  </div>
                </div>
              ))}
            </>
          )}
          <div className="rule" />
          <div style={{ ...DIM, font: "400 12px/1.5 'IBM Plex Sans'", paddingTop: 12 }}>
            Останалите срокове по ЗУЕС — отчети, покани, мандати, проверки — още не се водят в системата.
          </div>
        </div>
      </div>

      <div className="entrance-aside">
        <div className="kv-card">
          <div className="kv-head">Дело на входа</div>
          <div className="kv-body">
            <div className="kv-row"><span>Обекти</span><span className="v">{units.length}{units.length > 0 && ` · ${kinds(units)}`}</span></div>
            <div className="kv-row"><span>Форма на управление</span><span className="v" style={{ fontVariantNumeric: "normal" }}>{FORM[entrance.managementForm] ?? entrance.managementForm}</span></div>
            <div className="kv-row"><span>Управител</span><span className="v" style={DIM}>—</span></div>
            <div className="kv-row"><span>Мандат до</span><span className="v" style={DIM}>—</span></div>
            <div className="kv-row"><span>Последно ОС</span><span className="v" style={DIM}>—</span></div>
            <div className="kv-row">
              <span>Книга на собствениците</span>
              {failed(overdue) ? <span style={FAILED}>не се зареди</span>
                : overdue.length > 0 ? <span style={{ color: "#8E2318", fontWeight: 500 }}>{count(overdue.length, "просрочена декларация", "просрочени декларации")}</span>
                  : <span>без просрочени декларации</span>}
            </div>
          </div>
        </div>

        <div className="kv-card">
          <div className="kv-head">Сметки на входа</div>
          <div style={{ padding: "12px 14px" }}>
            <div className="acct-label">Оперативна сметка</div>
            {failed(operating) ? <div style={{ ...NOTE, ...FAILED }}>{operating.failed}</div>
              : operating === null ? <div style={NOTE}>Входът няма оперативна сметка в регистъра.</div>
                : (
                  <>
                    <div className="iban">{iban(operating.iban)}</div>
                    <div className="acct-amt">{eur(operating.balanceMinor)}</div>
                    <div style={NOTE}>
                      {operating.outflowsRecorded
                        ? "Салдо по сметката."
                        : "Постъпления по сметката — не салдото в банката: разходите от нея още не се водят."}
                    </div>
                  </>
                )}
            <div className="rule" style={{ margin: "10px 0" }} />
            <div className="acct-label">Фонд „Ремонт и обновяване“</div>
            {failed(fund) ? <div style={{ ...NOTE, ...FAILED }}>{fund.failed}</div>
              : fund === null ? <div style={NOTE}>Входът няма сметка на фонда в регистъра.</div>
                : (
                  <>
                    <div className="iban">{iban(fund.iban)}</div>
                    <div className="acct-amt">{eur(fund.balanceMinor)}</div>
                    <div style={NOTE}>Разполагаемо {eur(fund.availableMinor)} — {eur(fund.committedMinor)} поети, неплатени</div>
                  </>
                )}
            <Link href={`/entrance/fund${q}`} className="btn-solid" style={{ marginTop: 10 }}>Отвори касата и фонда</Link>
          </div>
        </div>

        <div className="kv-card">
          <div className="kv-head">Следващо събрание</div>
          <div style={{ padding: "12px 14px" }}>
            <div style={{ ...DIM, font: "400 13px/1.45 'IBM Plex Sans'" }}>Общите събрания още не се водят в системата.</div>
          </div>
        </div>
      </div>
    </div>
  );
}
