import type { Metadata } from "next";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entrances, eur, inTurn, reach } from "@/lib/console";

export const metadata: Metadata = { title: "Задължения — Етаж" };

type Entrance = Schemas["EntranceView"];
type Owing = Schemas["UnitArrears"];

type Group =
  | { entrance: Entrance; kind: "ok"; arrears: Schemas["EntranceArrears"]; units: Map<string, string>; owners: Map<string, string>; namesFailed?: string }
  | { entrance: Entrance; kind: "failed"; message: string };

type Loaded =
  | { kind: "ok"; asOf: string; groups: Group[]; entranceCount: number }
  | { kind: "down" }
  | { kind: "unlisted"; message: string }
  | { kind: "none" };

const refusal = (r: { error?: { error?: string }; response: Response }) => r.error?.error ?? `HTTP ${r.response.status}`;

/**
 * One screen, one server-side aggregation (ADR-011): every entrance's arrears as of a date (PM-DEBT-001, PM-DEBT-002 —
 * money ages them), joined to its units and to their owners on that date — a few entrances at a time. Every amount is
 * the API's; the page adds none of its own. An entrance where nothing is unpaid is left out; one that failed is shown.
 */
async function load(asOf: string): Promise<Loaded> {
  const listed = await entrances();
  if (listed.kind !== "ok") return listed;
  if (listed.entrances.length === 0) return { kind: "none" };

  const groups = await inTurn(listed.entrances, async (entrance): Promise<Group | null> => {
    const path = { entranceId: entrance.id };
    const arrears = await reach(() => api.GET("/api/money/entrances/{entranceId}/arrears", { params: { path, query: { asOf } } }));
    if (!arrears) return { entrance, kind: "failed", message: "Бекендът не подаде задълженията на входа." };
    if (!arrears.data) return { entrance, kind: "failed", message: `API отказа задълженията на входа: ${refusal(arrears)}` };
    if (arrears.data.units.length === 0) return null;

    const [units, owners] = await Promise.all([
      reach(() => api.GET("/api/registry/entrances/{entranceId}/units", { params: { path } })),
      reach(() => api.GET("/api/registry/entrances/{entranceId}/owners", { params: { path, query: { on: asOf } } })),
    ]);
    const names = new Map<string, string[]>();
    for (const o of owners?.data ?? []) {
      if (o.titleRole === "OWN") names.set(o.unitId, [...(names.get(o.unitId) ?? []), o.partyName]);   // an owner, not a user
    }
    return {
      entrance,
      kind: "ok",
      arrears: arrears.data,
      units: new Map((units?.data ?? []).map((u) => [u.id, u.designation])),
      owners: new Map([...names].map(([unit, list]) => [unit, list.sort((a, b) => a.localeCompare(b, "bg")).join(", ")])),
      namesFailed: units?.data && owners?.data ? undefined : "Обектите или собствениците не се заредиха — сумите са от API.",
    };
  });
  return { kind: "ok", asOf, groups: groups.filter((g): g is Group => g !== null), entranceCount: listed.entrances.length };
}

/**
 * The band of a unit's oldest open debt: the most overdue band holding an amount. Money bands each debt, charge and
 * credits together, so a band holds an amount exactly when a debt in it is still open — the most overdue one is the
 * oldest. CURRENT means nothing is overdue yet.
 */
const oldestBand = (u: Owing) => [...u.buckets].reverse().find((b) => b.amountMinor > 0)?.band ?? "CURRENT";
const overdue = (u: Owing) => oldestBand(u) !== "CURRENT";
const TONE: Record<string, string | undefined> = { "90+": "#8E2318", "61-90": "#8E2318", "31-60": "#7A5210" };

const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia" }).format(new Date());
const date = (iso: string) => iso.split("-").reverse().join(".");
/** A real calendar day, YYYY-MM-DD — 2026-02-31 is not one. */
const isDay = (value: string) => {
  const [y, m, d] = value.split("-").map(Number);
  const day = new Date(Date.UTC(y, m - 1, d));
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && day.getUTCFullYear() === y && day.getUTCMonth() === m - 1 && day.getUTCDate() === d;
};

// The чл. 38 ЗУЕС → чл. 410 ГПК ladder, as the design draws it — no step is recorded yet (PM-DEBT-009).
const LEGEND = [
  { c: "#17191A", t: "1 Покана" },
  { c: "#7A5210", t: "2 Нотариална покана" },
  { c: "#A32B23", t: "3 Решение на ОС" },
  { c: "#5B1B14", t: "4 Заповед за изпълнение" },
];
const DIM = { color: "#6B6F6C" };
const FAILED = { color: "#8E2318" };

export default async function DebtsPage({ searchParams }: { searchParams: Promise<{ asOf?: string }> }) {
  const query = await searchParams;
  const asOf = query.asOf && isDay(query.asOf) ? query.asOf : today();
  const view = await load(asOf);

  const ok = view.kind === "ok" ? view.groups.filter((g) => g.kind === "ok") : [];
  const failed = view.kind === "ok" ? view.groups.length - ok.length : 0;
  const listed = ok.flatMap((g) => g.arrears.units);
  const late = listed.filter(overdue).length;

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="title">Задължения</span>
          <span className="divider" />
          <span className="meta">
            {view.kind === "ok" && (
              <>
                {count(listed.length, "обект", "обекта")} с неплатено, {late} в просрочие ·{" "}
                {count(ok.length, "вход", "входа")} от {view.entranceCount} ·{" "}
                {failed > 0 && <span style={FAILED}>{count(failed, "вход не се зареди", "входа не се заредиха")} · </span>}
              </>
            )}
            към <span style={{ fontVariantNumeric: "tabular-nums" }}>{date(asOf)}</span>
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <button type="button" className="chip" disabled title="Поканите още не се водят в системата." style={{ opacity: 0.5, cursor: "not-allowed" }}>Генерирай покани</button>
          <button type="button" className="chip active" disabled title="Стъпките по чл. 38 ЗУЕС още не се водят в системата." style={{ opacity: 0.5, cursor: "not-allowed" }}>Следваща стъпка</button>
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
        <Debts view={view} listed={listed.length} failed={failed} />
      )}
    </>
  );
}

function Debts({ view, listed, failed }: { view: Extract<Loaded, { kind: "ok" }>; listed: number; failed: number }) {
  return (
    <>
      <div style={{ padding: "16px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 14, font: "400 12px/1 'IBM Plex Sans'", color: "#5C605E", opacity: 0.55 }}>
          <span style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>Стълбица</span>
          {LEGEND.map((l) => (
            <span key={l.t} style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <span style={{ width: 26, height: 5, background: l.c }} />
              {l.t}
            </span>
          ))}
        </div>
        <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>
          стъпките по чл. 38 ЗУЕС и лихвата още не се водят · сортирано по дължимо ↓
        </span>
      </div>

      <div className="pf-card" style={{ margin: "14px 24px 24px" }}>
        <div className="dt-grid pf-head">
          <div>Обект</div>
          <div>Собственик към {date(view.asOf)}</div>
          <div className="num">Дължимо</div>
          <div className="num">Лихва</div>
          <div className="num">Просрочие</div>
          <div>Стъпка</div>
          <div>Следващо действие</div>
        </div>

        {view.groups.length === 0 && (
          <div className="dt-grid pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма неплатени задължения към {date(view.asOf)}.</div></div>
        )}

        {view.groups.map((g, gi) => (
          <div key={g.entrance.id}>
            <div className={`dt-group${gi > 0 ? " mid" : ""}`}>
              <span>
                {g.entrance.label}{" "}
                <span className="gnote">{g.kind === "ok" ? `· ${count(g.arrears.units.length, "обект", "обекта")} с неплатено` : ""}</span>
              </span>
              <span className="gtotal">{g.kind === "ok" ? eur(g.arrears.totalMinor) : "—"}</span>
            </div>
            {g.kind === "failed" && (
              <div className="dt-grid pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{g.message}</div></div>
            )}
            {g.kind === "ok" && g.namesFailed && (
              <div className="dt-grid pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{g.namesFailed}</div></div>
            )}
            {g.kind === "ok" && g.arrears.units.map((u) => (
              <div key={u.unitId} className="dt-grid pf-row">
                <div style={{ fontWeight: 500 }}>{g.units.get(u.unitId) ?? "—"}</div>
                <div className="ellipsis">{g.owners.get(u.unitId) ?? "—"}</div>
                <div className="num" style={{ fontWeight: 500 }}>{eur(u.totalMinor)}</div>
                <div className="num" style={DIM}>—</div>
                <div
                  className="num"
                  style={overdue(u) ? { color: TONE[oldestBand(u)] } : DIM}
                  title={u.oldestDebt ? `падеж ${date(u.oldestDebt.dueOn)} — датата на начислението плюс срока за плащане` : undefined}
                >
                  {!u.oldestDebt ? "—" : overdue(u) ? count(u.oldestDebt.overdueDays, "ден", "дни") : "в срок"}
                </div>
                <div style={DIM}>—</div>
                <div className="action" style={DIM}>—</div>
              </div>
            ))}
          </div>
        ))}

        <div className="dt-grid pf-foot">
          <div>Общо</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>
            {count(listed, "обект", "обекта")}
            {failed > 0 && <span style={FAILED}> · без {count(failed, "незареден вход", "незаредени входа")}</span>}
          </div>
          <div /><div /><div /><div /><div />
        </div>
      </div>
    </>
  );
}
