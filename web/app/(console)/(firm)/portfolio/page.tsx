import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entrances, eur, inTurn, reach } from "@/lib/console";

export const metadata: Metadata = { title: "Портфейл — Етаж" };

type Entrance = Schemas["EntranceView"];

type Fund = { kind: "ok"; view: Schemas["FundView"] } | { kind: "none" } | { kind: "failed" };

type Row =
  | { entrance: Entrance; kind: "ok"; units: number; arrears: Schemas["EntranceArrears"]; fund: Fund }
  | { entrance: Entrance; kind: "failed"; message: string };

type Loaded =
  | { kind: "ok"; asOf: string; rows: Row[] }
  | { kind: "down" }
  | { kind: "unlisted"; message: string }
  | { kind: "none" };

const refusal = (r: { error?: { error?: string }; response: Response }) => r.error?.error ?? `HTTP ${r.response.status}`;

/**
 * One screen, one server-side aggregation (ADR-011): every entrance with its units, what it owes as of a date
 * (PM-DEBT-001 — money's read, after the advances) and its repair fund's balance and what is available (PM-FUND-009) —
 * a few entrances at a time. Every amount is the API's; the page adds none of its own, so it shows no firm-wide total.
 * An entrance that failed is shown, never left out.
 */
async function load(asOf: string): Promise<Loaded> {
  const listed = await entrances();
  if (listed.kind !== "ok") return listed;
  if (listed.entrances.length === 0) return { kind: "none" };

  const rows = await inTurn(listed.entrances, async (entrance): Promise<Row> => {
    const path = { entranceId: entrance.id };
    const [units, arrears, fund] = await Promise.all([
      reach(() => api.GET("/api/registry/entrances/{entranceId}/units", { params: { path } })),
      reach(() => api.GET("/api/money/entrances/{entranceId}/arrears", { params: { path, query: { asOf } } })),
      reach(() => api.GET("/api/money/entrances/{entranceId}/fund", { params: { path } })),
    ]);
    if (!units || !arrears) return { entrance, kind: "failed", message: "Бекендът не подаде входа." };
    if (!units.data) return { entrance, kind: "failed", message: `API отказа обектите на входа: ${refusal(units)}` };
    if (!arrears.data) return { entrance, kind: "failed", message: `API отказа задълженията на входа: ${refusal(arrears)}` };
    return {
      entrance,
      kind: "ok",
      units: units.data.length,
      arrears: arrears.data,
      // 404: the entrance has no repair fund account yet — a fact about it, not a failure
      fund: fund?.data ? { kind: "ok", view: fund.data } : fund?.response.status === 404 ? { kind: "none" } : { kind: "failed" },
    };
  });
  return { kind: "ok", asOf, rows };
}

const owed = (r: Row) => (r.kind === "ok" ? r.arrears.netMinor : 0);
const owing = (r: Row) => owed(r) > 0;
const fundBelowZero = (r: Row) => r.kind === "ok" && r.fund.kind === "ok" && r.fund.view.balanceMinor < 0;

// The filters that work today. The design's others — overdue tasks, mandates, collection rate — need what is not kept yet.
const SHOWN: Record<string, { label: string; keep: (r: Row) => boolean }> = {
  all: { label: "Всички", keep: () => true },
  owing: { label: "С дължимо", keep: owing },
  fund: { label: "Отрицателен фонд", keep: fundBelowZero },
};
const NOT_KEPT = ["С просрочени задачи", "Мандат < 60 дни", "Събираемост < 80%"];

const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia" }).format(new Date());
const date = (iso: string) => iso.split("-").reverse().join(".");
/** A real calendar day, YYYY-MM-DD — 2026-02-31 is not one. */
const isDay = (value: string) => {
  const [y, m, d] = value.split("-").map(Number);
  const day = new Date(Date.UTC(y, m - 1, d));
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && day.getUTCFullYear() === y && day.getUTCMonth() === m - 1 && day.getUTCDate() === d;
};

const DIM = { color: "#6B6F6C" };
const FAILED = { color: "#8E2318" };
const PLAIN = { color: "inherit", textDecoration: "none" };

export default async function PortfolioPage({ searchParams }: { searchParams: Promise<{ asOf?: string; show?: string }> }) {
  const query = await searchParams;
  const asOf = query.asOf && isDay(query.asOf) ? query.asOf : today();
  const show = query.show && query.show in SHOWN ? query.show : "all";
  const view = await load(asOf);

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="title">Портфейл</span>
          <span className="divider" />
          <span className="meta">
            {view.kind === "ok" && (
              <>
                {count(view.rows.length, "вход", "входа")} ·{" "}
                {count(new Set(view.rows.map((r) => r.entrance.condominiumId)).size, "сграда", "сгради")} ·{" "}
                {count(view.rows.reduce((n, r) => n + (r.kind === "ok" ? r.units : 0), 0), "обект", "обекта")} ·{" "}
              </>
            )}
            към <span style={{ fontVariantNumeric: "tabular-nums" }}>{date(asOf)}</span>
          </span>
        </div>
      </div>

      {view.kind !== "ok" ? (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "unlisted" && <>API отказа списъка на входовете: {view.message}</>}
            {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход и обектите му в <code>registry</code>, после опреснете.</>}
          </div>
        </div>
      ) : (
        <Portfolio view={view} show={show} dated={query.asOf && isDay(query.asOf) ? query.asOf : undefined} />
      )}
    </>
  );
}

function Portfolio({ view, show, dated }: { view: Extract<Loaded, { kind: "ok" }>; show: string; dated?: string }) {
  // Largest owed first, then by name — `entrances()` lists them by name, and the sort is stable. A failed one stays on top.
  const sorted = [...view.rows].sort((a, b) => Number(b.kind === "failed") - Number(a.kind === "failed") || owed(b) - owed(a));
  const rows = sorted.filter((r) => r.kind === "failed" || SHOWN[show].keep(r));
  const failed = view.rows.filter((r) => r.kind === "failed").length;
  const at = (params: Record<string, string | undefined>) => {
    const q = new URLSearchParams(Object.entries(params).filter((p): p is [string, string] => p[1] !== undefined));
    return q.size ? `?${q}` : "";
  };

  return (
    <>
      <div style={{ padding: "16px 24px 0" }}>
        <div style={{ font: "400 13px/1.5 'IBM Plex Sans'", color: "#3D413E" }}>
          <span style={{ fontWeight: 500 }}>{count(view.rows.filter(owing).length, "вход дължи", "входа дължат")}</span> към {date(view.asOf)} ·{" "}
          <span style={{ fontWeight: 500 }}>{count(view.rows.filter(fundBelowZero).length, "вход", "входа")}</span> с отрицателен фонд
          {failed > 0 && <span style={FAILED}> · {count(failed, "вход не се зареди", "входа не се заредиха")}</span>}
        </div>

        <div style={{ marginTop: 14 }}>
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
            {Object.entries(SHOWN).map(([key, f]) => (
              <Link key={key} href={`/portfolio${at({ asOf: dated, show: key === "all" ? undefined : key })}`} className={`chip${key === show ? " active" : ""}`} style={{ textDecoration: "none" }}>
                {f.label} {view.rows.filter(f.keep).length}
              </Link>
            ))}
            {NOT_KEPT.map((label) => (
              <button key={label} type="button" className="chip" disabled title="Още не се води в системата." style={{ opacity: 0.5, cursor: "not-allowed" }}>
                {label}
              </button>
            ))}
          </div>
          <div style={{ font: "400 11.5px/1.4 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 10 }}>
            задачите по ЗУЕС, събираемостта, мандатите и рискът още не се водят · сортирано по дължимо ↓
          </div>
        </div>
      </div>

      <div className="pf-card" style={{ margin: "14px 24px 24px" }}>
        <div className="pl-grid pf-head">
          <div>Вход</div>
          <div className="num">Просрочени</div>
          <div>Следващ срок</div>
          <div className="num">Събираемост</div>
          <div className="num" title="неплатеното след авансите">Дължимо ↓</div>
          <div className="num" title="салдото на сметката на фонда днес">Фонд „Ремонт“</div>
          <div className="num" title="салдото без поетите, неплатени задължения">Разполагаемо</div>
          <div className="num">Мандат до</div>
          <div className="num">Риск</div>
        </div>

        {rows.length === 0 && (
          <div className="pl-grid pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма вход за този филтър.</div></div>
        )}

        {rows.map((r) => (
          <div key={r.entrance.id} className={`pl-grid pf-row${fundBelowZero(r) ? " flagged" : ""}`}>
            <div className="ellipsis">
              <Link href={`/entrance?entrance=${r.entrance.id}`} style={{ ...PLAIN, fontWeight: 500 }}>{r.entrance.label}</Link>
              {r.kind === "ok" && <span className="dim"> · {r.units} об.</span>}
            </div>
            {r.kind === "failed" ? (
              <div style={{ ...FAILED, gridColumn: "2 / -1" }}>{r.message}</div>
            ) : (
              <>
                <div className="num" style={DIM}>—</div>
                <div style={DIM}>—</div>
                <div className="num" style={DIM}>—</div>
                <div className="num" style={owing(r) ? { fontWeight: 500 } : DIM}>
                  <Link href={`/debts${at({ asOf: dated })}`} style={PLAIN}>{eur(r.arrears.netMinor)}</Link>
                </div>
                {r.fund.kind === "ok" ? (
                  <>
                    <div className={`num${r.fund.view.balanceMinor < 0 ? " late" : ""}`}>
                      <Link href={`/entrance/fund?entrance=${r.entrance.id}`} style={PLAIN}>{eur(r.fund.view.balanceMinor)}</Link>
                    </div>
                    <div className={`num${r.fund.view.availableMinor < 0 ? " late" : ""}`}>{eur(r.fund.view.availableMinor)}</div>
                  </>
                ) : (
                  <div className="num" style={{ ...(r.fund.kind === "failed" ? FAILED : DIM), gridColumn: "span 2" }}>
                    {r.fund.kind === "none" ? "няма сметка" : "фондът не се зареди"}
                  </div>
                )}
                <div className="num" style={DIM}>—</div>
                <div className="num" style={DIM}>—</div>
              </>
            )}
          </div>
        ))}

        <div className="pl-grid pf-foot">
          <div>Общо {count(view.rows.length, "вход", "входа")}</div>
          <div />
          <div className="dim" style={{ fontWeight: 400 }}>
            {rows.length === view.rows.length ? "показани всички" : `показани ${rows.length} от ${view.rows.length}`}
            {failed > 0 && <span style={FAILED}> · без {count(failed, "незареден вход", "незаредени входа")}</span>}
          </div>
          <div /><div /><div /><div /><div /><div />
        </div>
      </div>
    </>
  );
}
