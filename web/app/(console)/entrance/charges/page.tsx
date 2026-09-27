import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";

export const metadata: Metadata = { title: "Начисления — Етаж" };

type UnitCharge = Schemas["UnitChargeResponse"];

/**
 * The basis a run is computed from — a general-assembly decision (PM-FEE-012). The assembly module
 * does not serve decisions yet, and the API takes the tariff with the request, so this page posts
 * the design's decision as a DEMO basis and says so on screen. Never bill from it (ADR-012 §7).
 */
const DEMO_BASIS = {
  decision: "Решение на ОС от 12.03.2026, т. 4",
  lines: [
    { stream: "MANAGEMENT", key: "PER_PERSON", decisionId: "GA-2026-03-12-4", rateMinor: 600 },
    { stream: "MAINTENANCE", key: "PER_PERSON", decisionId: "GA-2026-03-12-4", rateMinor: 450 },
    { stream: "REPAIR_FUND", key: "BY_IDEAL_PARTS", decisionId: "GA-2026-03-12-4", totalMinor: 60_000 },
  ] satisfies Schemas["TariffLineRequest"][],
};

const STREAM_LABEL: Record<string, string> = { MANAGEMENT: "управление", MAINTENANCE: "поддръжка на общи части", REPAIR_FUND: "фонд „Ремонт“" };
const describe = (l: Schemas["TariffLineRequest"]) =>
  `${STREAM_LABEL[l.stream] ?? l.stream} ${l.rateMinor != null ? `${eur(l.rateMinor)}/${l.key === "PER_PERSON" ? "живущ" : "обект"}` : `${eur(l.totalMinor ?? 0)} по идеални части`}`;

const MONTHS = ["януари", "февруари", "март", "април", "май", "юни", "юли", "август", "септември", "октомври", "ноември", "декември"];

const thisPeriod = () =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia", year: "numeric", month: "2-digit" }).format(new Date()).slice(0, 7);
const shift = (period: string, by: number) => {
  const [y, m] = period.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
};
const monthName = (period: string) => `${MONTHS[Number(period.slice(5)) - 1]} ${period.slice(0, 4)}`;

const eur = (minor: number) =>
  "€" + (minor / 100).toLocaleString("de-DE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const pct = (value: number) => value.toLocaleString("de-DE", { minimumFractionDigits: 4, maximumFractionDigits: 6 }) + "%";

type Loaded =
  | { kind: "ok"; entrance: Schemas["EntranceView"]; run: Schemas["ChargeRunResponse"]; ideal: Map<string, number>; owners: Map<string, string> }
  | { kind: "down" }
  | { kind: "none" }
  | { kind: "unknown"; id: string }
  | { kind: "refused"; message: string };

/** One screen, one server-side aggregation (ADR-011): the run, joined to its units and owners. */
async function load(entranceId: string | undefined, period: string, legalDate: string): Promise<Loaded> {
  const reach = async <T,>(call: () => Promise<T>) => { try { return await call(); } catch { return null; } };

  const listed = await reach(() => api.GET("/api/registry/entrances"));
  if (!listed) return { kind: "down" };
  const all = listed.data ?? [];
  const entrance = entranceId ? all.find((e) => e.id === entranceId) : all[0];
  if (!entrance) return entranceId ? { kind: "unknown", id: entranceId } : { kind: "none" };

  const path = { entranceId: entrance.id };
  const fetched = await reach(() => Promise.all([
    api.POST("/api/money/entrances/{entranceId}/charge-runs/preview", {
      params: { path },
      body: { period, legalDate, lines: DEMO_BASIS.lines },
    }),
    api.GET("/api/registry/entrances/{entranceId}/units", { params: { path } }),
    api.GET("/api/registry/entrances/{entranceId}/owners", { params: { path, query: { on: legalDate } } }),
  ]));
  if (!fetched) return { kind: "down" };
  const [run, units, owners] = fetched;
  if (!run.data) return { kind: "refused", message: run.error?.error ?? `HTTP ${run.response.status}` };

  const names = new Map<string, string[]>();
  for (const o of owners.data ?? []) names.set(o.unitId, [...(names.get(o.unitId) ?? []), o.partyName]);
  return {
    kind: "ok",
    entrance,
    run: run.data,
    ideal: new Map((units.data ?? []).map((u) => [u.id, u.idealPartsPct])),
    owners: new Map([...names].map(([unit, list]) => [unit, list.join(", ")])),
  };
}

const stream = (c: UnitCharge, name: string) => c.lines.filter((l) => l.stream === name);
const sum = (lines: UnitCharge["lines"]) => lines.reduce((total, l) => total + l.amountMinor, 0);
const how = (lines: UnitCharge["lines"]) => lines.map((l) => l.derivation).join("\n");

const HEADERS: { label: string; num?: boolean; ink?: boolean }[] = [
  { label: "Обект" },
  { label: "Собственик" },
  { label: "Живущи", num: true },
  { label: "Освобождавания" },
  { label: "Коеф.", num: true },
  { label: "Ид. части", num: true },
  { label: "Управл.", num: true },
  { label: "Общи ч.", num: true },
  { label: "Асанс.", num: true },
  { label: "Фонд", num: true },
  { label: "Общо", num: true, ink: true },
];

const DIM = { color: "#6B6F6C" };

export default async function ChargesPage({ searchParams }: { searchParams: Promise<{ entrance?: string; period?: string }> }) {
  const query = await searchParams;
  const period = /^\d{4}-\d{2}$/.test(query.period ?? "") ? query.period! : thisPeriod();
  const view = await load(query.entrance, period, `${period}-01`);
  const at = (p: string) => `?period=${p}${view.kind === "ok" ? `&entrance=${view.entrance.id}` : ""}`;

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">
            Начисления · <span style={{ fontWeight: 500, color: "#17191A" }}>{monthName(period)}</span>
          </span>
          <span className="badge warn">Преглед</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href={at(shift(period, -1))} className="chip">◀ {monthName(shift(period, -1)).split(" ")[0]}</Link>
          <Link href={at(shift(period, 1))} className="chip">{monthName(shift(period, 1)).split(" ")[0]} ▶</Link>
        </div>
      </div>

      {view.kind !== "ok" ? (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход и обектите му в <code>registry</code>, после опреснете.</>}
            {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
            {view.kind === "refused" && <>API отказа изчислението: {view.message}</>}
          </div>
        </div>
      ) : (
        <Run view={view} />
      )}
    </>
  );
}

function Run({ view }: { view: Extract<Loaded, { kind: "ok" }> }) {
  const { run, ideal, owners } = view;
  const idealSum = run.charges.reduce((total, c) => total + (ideal.get(c.unitId) ?? 0), 0);
  const totals = ["MANAGEMENT", "MAINTENANCE", "REPAIR_FUND"].map((s) => run.charges.reduce((t, c) => t + sum(stream(c, s)), 0));

  return (
    <>
      <div style={{ padding: "16px 24px 0" }}>
        <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "12px 16px", display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 24 }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>
              Базис на изчислението <span className="tag-mini warn" style={{ marginLeft: 6, letterSpacing: 0, textTransform: "none" }}>демо</span>
            </div>
            <div style={{ font: "400 12.5px/1.6 'IBM Plex Sans'", color: "#3D413E", marginTop: 6 }}>
              {[DEMO_BASIS.decision, ...DEMO_BASIS.lines.map(describe)].join(" · ")}
            </div>
            <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 4 }}>
              Демо базис: модулът „Общи събрания“ ще подава решенията. Не се начислява от него.
            </div>
          </div>
          <div style={{ flex: "none", textAlign: "right" }}>
            <div style={{ font: "400 11.5px/1 'IBM Plex Sans'", color: "#6B6F6C" }}>Изчислено от API</div>
            <div style={{ font: "400 12.5px/1.6 'IBM Plex Mono', monospace" }}>{run.engineVersion}</div>
            <div style={{ font: "400 11.5px/1 'IBM Plex Sans'", color: "#6B6F6C", marginTop: 4 }}>закон {run.lawVersion} · към {run.legalDate}</div>
          </div>
        </div>
      </div>

      <div className="pf-card">
        <div className="ch-grid ch-head">
          {HEADERS.map((h) => (
            <div key={h.label} className={h.num ? "num" : ""} style={h.ink ? { color: "#17191A" } : undefined}>{h.label}</div>
          ))}
        </div>

        {run.charges.map((c) => (
          <div key={c.unitId} className="ch-grid ch-row">
            <div style={{ fontWeight: 500 }}>{c.designation}</div>
            <div className="ellipsis">{owners.get(c.unitId) ?? "—"}</div>
            <div className="num">{c.chargeablePersons}</div>
            <div style={DIM}>—</div>
            <div className="num" style={DIM}>—</div>
            <div className="num">{ideal.has(c.unitId) ? pct(ideal.get(c.unitId)!) : "—"}</div>
            {["MANAGEMENT", "MAINTENANCE"].map((s) => (
              <div key={s} className="num" title={how(stream(c, s))}>{eur(sum(stream(c, s)))}</div>
            ))}
            <div className="num" style={DIM}>—</div>
            <div className="num" title={how(stream(c, "REPAIR_FUND"))}>{eur(sum(stream(c, "REPAIR_FUND")))}</div>
            <div className="num" style={{ fontWeight: 500 }}>{eur(c.totalMinor)}</div>
          </div>
        ))}

        <div className="ch-grid ch-foot">
          <div>Общо</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>{run.charges.length} обекта</div>
          <div className="num">{run.charges.reduce((t, c) => t + c.chargeablePersons, 0)}</div>
          <div />
          <div />
          <div className="num">{pct(idealSum)}</div>
          <div className="num">{eur(totals[0])}</div>
          <div className="num">{eur(totals[1])}</div>
          <div className="num" style={DIM}>—</div>
          <div className="num">{eur(totals[2])}</div>
          <div className="num">{eur(run.totalMinor)}</div>
        </div>
      </div>

      <div className="action-bar">
        <div>
          <div style={{ font: "400 12px/1.5 'IBM Plex Sans'", color: "#5C605E" }}>
            Изчислено от API: {run.charges.length} обекта · сумата на идеалните части е{" "}
            <span style={{ color: "#14584A", fontWeight: 500 }}>{pct(idealSum)}</span> · посочете сума, за да видите как е изведена
          </div>
          <div style={{ font: "400 11.5px/1.4 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>
            Преглед — нищо не е записано. Издаването изисква решение на ОС, не демо базис.
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
          <div style={{ textAlign: "right" }}>
            <div style={{ font: "400 11px/1 'IBM Plex Sans'", letterSpacing: ".1em", textTransform: "uppercase", color: "#6B6F6C" }}>Общо за начисляване</div>
            <div style={{ font: "500 24px/1.2 'IBM Plex Sans'", fontVariantNumeric: "tabular-nums" }}>{eur(run.totalMinor)}</div>
          </div>
          <button type="button" className="btn-confirm" disabled title="Издаването изисква решение на ОС" style={{ opacity: 0.5, cursor: "not-allowed" }}>Потвърди начисленията</button>
        </div>
      </div>
    </>
  );
}
