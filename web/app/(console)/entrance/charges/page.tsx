import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, eur, isPeriod, monthName, reach, shiftPeriod as shift, thisPeriod, type EntranceAt } from "@/lib/console";
import { unanswered, unansweredBy } from "@/lib/refusal";
import { lineWords } from "@/lib/basis";
import IssueForm from "./IssueForm";
import { NONE_IN_FORCE, versionInForce } from "./version";
import NoEntrance from "@/app/(console)/NoEntrance";

export const metadata: Metadata = { title: "Начисления — Етаж" };

type UnitCharge = Schemas["UnitChargeResponse"];

const pct = (value: number) => value.toLocaleString("de-DE", { minimumFractionDigits: 4, maximumFractionDigits: 6 }) + "%";

type Loaded =
  | { kind: "ok"; entrance: Schemas["EntranceView"]; run: Schemas["ChargeRunResponse"]; ideal: Map<string, number>; owners: Map<string, string>; namesFailed?: string; version: Schemas["TariffVersionView"] }
  | { kind: "noversion"; entrance: Schemas["EntranceView"]; message: string }
  | Exclude<EntranceAt, { kind: "ok" }>
  | { kind: "refused"; entrance: Schemas["EntranceView"]; message: string; aboutMultiple: boolean };

/**
 * Rule: PM-FEE-010 — the multiple a business unit pays is the assembly's figure. The screen holds none, and the tariff
 * version has none: the person at the screen gives it (`?multiple=`), it is passed on as given, and the API decides
 * against the law whether it is in range. The screen reads it only as far as "a whole number" — the API takes no
 * other — and says so when it is not one, rather than dropping it.
 */
type Multiple = { given: string; value: number | undefined };   // `given` as typed ("" when none); `value` when it is a whole number
const multipleOf = (given: string | string[] | undefined): Multiple => {
  const typed = (typeof given === "string" ? given : "").trim();
  return { given: typed, value: /^-?\d{1,9}$/.test(typed) ? Number(typed) : undefined };
};

/** One screen, one server-side aggregation (ADR-011): the run, joined to its units and owners. */
async function load(entranceId: string | undefined, period: string, legalDate: string, { given, value: multiple }: Multiple): Promise<Loaded> {
  const at = await entranceAt(entranceId);
  if (at.kind !== "ok") return at;
  const { entrance } = at;
  if (given && multiple == null) {
    return { kind: "refused", entrance, aboutMultiple: true, message: `API отказа изчислението: кратността „${given}“ не е цяло число.` };
  }

  // Rule: PM-FEE-012 — the run is billed on the version in force for the period; with none, nothing is previewed.
  const inForce = await versionInForce(entrance.id, period);
  if ("refused" in inForce) return { kind: "refused", entrance, message: inForce.refused, aboutMultiple: false };
  if ("none" in inForce) return { kind: "noversion", entrance, message: NONE_IN_FORCE(monthName(period)) };

  const path = { entranceId: entrance.id };
  const body: Schemas["StoredChargeRunRequest"] = { period, legalDate, ...(multiple != null && { businessMultiplier: multiple }) };   // no lines: the API bills the version in force
  const fetched = await reach(() => Promise.all([
    api.POST("/api/money/entrances/{entranceId}/charge-runs/preview", { params: { path }, body }),
    api.GET("/api/registry/entrances/{entranceId}/units", { params: { path } }),
    api.GET("/api/registry/entrances/{entranceId}/owners", { params: { path, query: { on: legalDate } } }),
  ]));
  if (!fetched) return { kind: "down" };
  const [run, units, owners] = fetched;
  if (!run.data) {
    const message = run.error?.error ?? `HTTP ${run.response.status}`;
    return { kind: "refused", entrance, message: unansweredBy(run, "изчислението"), aboutMultiple: message.includes("PM-FEE-010") };   // the API names the rule it refuses on
  }

  const names = new Map<string, string[]>();
  for (const o of owners.data ?? []) {
    if (o.titleRole === "OWN") names.set(o.unitId, [...(names.get(o.unitId) ?? []), o.partyName]);   // an owner, not a user
  }
  return {
    kind: "ok",
    entrance,
    run: run.data,
    ideal: new Map((units.data ?? []).map((u) => [u.id, u.idealPartsPct])),
    version: inForce.version,
    owners: new Map([...names].map(([unit, list]) => [unit, list.sort((a, b) => a.localeCompare(b, "bg")).join(", ")])),   // the API lists them in no fixed order
    // The amounts are money's and stay; what the registry did not give is said to be refused, never left as a dash.
    namesFailed: [
      !units.data && unansweredBy(units, "обектите на входа"),
      !owners.data && unansweredBy(owners, "собствениците на входа"),
    ].filter(Boolean).join(" ") || undefined,
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

/** The field for the assembly's multiple: a plain form, so it works with no script, and keeps the entrance and the period. */
function MultipleForm({ period, entrance, multiple }: { period: string; entrance: string; multiple: Multiple }) {
  return (
    <form method="get" style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8, marginTop: 10, font: "400 12.5px/1.4 'IBM Plex Sans'" }}>
      <input type="hidden" name="period" value={period} />
      <input type="hidden" name="entrance" value={entrance} />
      <label htmlFor="multiple">Кратност за стопанска или професионална дейност — по решение на ОС</label>
      <input id="multiple" name="multiple" type="number" step={1} defaultValue={multiple.given} style={{ width: 64, height: 28, padding: "0 8px", border: "1px solid #8F938C", borderRadius: 4, font: "400 13px/1 'IBM Plex Mono', monospace" }} />
      <button type="submit" className="chip" style={{ cursor: "pointer" }}>Приложи</button>
    </form>
  );
}

export default async function ChargesPage({ searchParams }: { searchParams: Promise<{ entrance?: string; period?: string; multiple?: string }> }) {
  const query = await searchParams;
  const period = isPeriod(query.period) ? query.period : thisPeriod();
  const multiple = multipleOf(query.multiple);
  const view = await load(query.entrance, period, `${period}-01`, multiple);
  const shown = view.kind === "ok" || view.kind === "refused" || view.kind === "noversion" ? view.entrance : undefined;
  const at = (p: string) => `?period=${p}${shown ? `&entrance=${shown.id}` : ""}${multiple.value != null ? `&multiple=${multiple.value}` : ""}`;

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{shown ? shown.label : "—"}</span>
          </span>
          <span className="meta">
            Начисления · <span style={{ fontWeight: 500, color: "#17191A" }}>{monthName(period)}</span>
          </span>
          <span className="badge warn">Преглед</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href={at(shift(period, -1))} className="chip">◀ {monthName(shift(period, -1)).split(" ")[0]}</Link>
          <Link href={at(shift(period, 1))} className="chip">{monthName(shift(period, 1)).split(" ")[0]} ▶</Link>
          {shown && <Link href={`/entrance/charges/tariff?entrance=${shown.id}`} className="chip">Тарифни версии</Link>}
        </div>
      </div>

      {view.kind !== "ok" ? (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
            {view.kind === "none" && <NoEntrance />}
            {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
            {view.kind === "noversion" && view.message}
            {view.kind === "refused" && (
              <>
                {view.message}
                {view.aboutMultiple && (
                  <>
                    <div style={{ marginTop: 8, color: "#5C605E" }}>
                      За обект със стопанска или професионална дейност, достъпен през общите части, изчислението изисква кратността, която общото събрание е определило. Екранът не я задава — посочете я тук.
                    </div>
                    <MultipleForm period={period} entrance={view.entrance.id} multiple={multiple} />
                  </>
                )}
              </>
            )}
          </div>
        </div>
      ) : (
        <Run view={view} period={period} multiple={multiple} />
      )}
    </>
  );
}

function Run({ view, period, multiple }: { view: Extract<Loaded, { kind: "ok" }>; period: string; multiple: Multiple }) {
  const { run, ideal, owners, namesFailed, version } = view;
  // No ideal parts read (the units were refused): no sum of them either — a dash, never 0%.
  const idealSum = ideal.size === 0 ? "—" : pct(run.charges.reduce((total, c) => total + (ideal.get(c.unitId) ?? 0), 0));
  const totals = ["MANAGEMENT", "MAINTENANCE", "REPAIR_FUND"].map((s) => run.charges.reduce((t, c) => t + sum(stream(c, s)), 0));

  return (
    <>
      <div style={{ padding: "16px 24px 0" }}>
        <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "12px 16px", display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 24 }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>
              Тарифа в сила <span className="tag-mini" style={{ marginLeft: 6, letterSpacing: 0, textTransform: "none" }}>от {version.validFrom}{version.validTo ? ` до ${version.validTo}` : ""}</span>
            </div>
            <div style={{ font: "400 12.5px/1.6 'IBM Plex Sans'", color: "#3D413E", marginTop: 6 }}>
              {[...version.lines.map(lineWords), ...version.consumption.map((m) => `${m.item} ${eur(m.priceMinor)}`)].join(" · ")}
            </div>
            <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 4 }}>
              По решение на общото събрание <code>{version.decisionId}</code> (PM-FEE-012). Сумите са на API и не се въвеждат тук.
            </div>
            <div style={{ font: "400 12.5px/1.6 'IBM Plex Sans'", color: "#3D413E", marginTop: 8 }}>
              {multiple.value != null
                ? <>Подадена кратност: <span style={{ fontWeight: 500 }}>×{multiple.value}</span> — посочена ръчно, не е част от тарифата. Прилага се само за обект със стопанска или професионална дейност, достъпен през общите части.</>
                : <>Кратност за стопанска или професионална дейност: не е посочена.</>}
            </div>
            <MultipleForm period={period} entrance={view.entrance.id} multiple={multiple} />
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

        {namesFailed && (
          <div className="ch-grid ch-row"><div style={{ color: "#8E2318", gridColumn: "1 / -1" }}>{namesFailed} Сумите са от API.</div></div>
        )}
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
          <div className="num">{idealSum}</div>
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
            <span style={{ color: "#14584A", fontWeight: 500 }}>{idealSum}</span> · посочете сума, за да видите как е изведена
          </div>
          <div style={{ font: "400 11.5px/1.4 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>
            Преглед на тарифата в сила — нищо не е записано. Издаването е по-долу.
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
          <div style={{ textAlign: "right" }}>
            <div style={{ font: "400 11px/1 'IBM Plex Sans'", letterSpacing: ".1em", textTransform: "uppercase", color: "#6B6F6C" }}>Общо за начисляване</div>
            <div style={{ font: "500 24px/1.2 'IBM Plex Sans'", fontVariantNumeric: "tabular-nums" }}>{eur(run.totalMinor)}</div>
          </div>
        </div>
      </div>

      <IssueForm entrance={view.entrance.id} period={period} multiple={multiple.given} />
    </>
  );
}
