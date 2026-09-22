import type { Metadata } from "next";

export const metadata: Metadata = { title: "Портфейл — Етаж" };

type Risk = "crit" | "warn" | "calm";
type Tone = "late" | "warn" | "none";

interface EntranceRow {
  addr: string;
  units: number;
  overdue: number;
  next: string;
  nextDate: string;
  nextTone: Tone;
  law: string;
  collect: string;
  collectLate?: boolean;
  debt: string;
  fund: string;
  fundLate?: boolean;
  mandate: string;
  mandateWarn?: boolean;
  risk: Risk;
  riskLabel: string;
}

// Sample portfolio (the design's data). Modeled as typed rows so wiring this to the api's
// cross-entrance query later is a data-source swap, not a rewrite. Sorted by risk, descending.
const ROWS: EntranceRow[] = [
  { addr: "ул. Шипка 14, вх. Б", units: 24, overdue: 3, next: "Отчет пред ОС", nextDate: "18.09", nextTone: "late", law: "чл. 11, ал. 1, т. 4", collect: "71,2%", collectLate: true, debt: "€4.120,80", fund: "€1.240,50", mandate: "31.10.26", mandateWarn: true, risk: "crit", riskLabel: "Критичен" },
  { addr: "ж.к. Младост 3, бл. 318, вх. 2", units: 36, overdue: 2, next: "Проверка асансьор", nextDate: "02.09", nextTone: "late", law: "Наредба, чл. 22", collect: "68,4%", collectLate: true, debt: "€6.842,10", fund: "−€310,00", fundLate: true, mandate: "14.04.27", risk: "crit", riskLabel: "Критичен" },
  { addr: "бул. Витоша 102, вх. А", units: 18, overdue: 1, next: "Книга на собствениците", nextDate: "11.09", nextTone: "late", law: "чл. 7, ал. 1", collect: "84,0%", debt: "€1.980,00", fund: "€8.415,60", mandate: "05.11.26", mandateWarn: true, risk: "warn", riskLabel: "Внимание" },
  { addr: "ул. Цар Асен 9, вх. А", units: 12, overdue: 1, next: "Свикване на ОС", nextDate: "28.09", nextTone: "warn", law: "чл. 13, ал. 1", collect: "76,9%", debt: "€2.415,40", fund: "€3.102,00", mandate: "30.06.27", risk: "warn", riskLabel: "Внимание" },
  { addr: "ул. Шипка 14, вх. А", units: 22, overdue: 0, next: "Отчет пред ОС", nextDate: "30.09", nextTone: "warn", law: "чл. 11, ал. 1, т. 4", collect: "79,5%", debt: "€2.104,60", fund: "€5.640,00", mandate: "31.10.26", mandateWarn: true, risk: "warn", riskLabel: "Внимание" },
  { addr: "ж.к. Изгрев, бл. 12, вх. 1", units: 28, overdue: 0, next: "Противопожарна проверка", nextDate: "12.10", nextTone: "none", law: "Наредба 8121з-647", collect: "91,3%", debt: "€860,20", fund: "€12.480,00", mandate: "18.02.27", risk: "calm", riskLabel: "Спокоен" },
  { addr: "ул. Раковски 141, вх. Б", units: 16, overdue: 0, next: "Годишен отчет фонд", nextDate: "15.10", nextTone: "none", law: "чл. 50, ал. 3", collect: "94,8%", debt: "€412,00", fund: "€6.930,40", mandate: "09.09.27", risk: "calm", riskLabel: "Спокоен" },
  { addr: "ул. Дунав 3, вх. А", units: 9, overdue: 0, next: "Актуализация живущи", nextDate: "20.10", nextTone: "none", law: "чл. 51, ал. 1", collect: "88,1%", debt: "€640,00", fund: "€2.180,00", mandate: "12.12.26", risk: "calm", riskLabel: "Спокоен" },
  { addr: "ж.к. Люлин 7, бл. 704, вх. 3", units: 32, overdue: 0, next: "Проверка асансьор", nextDate: "04.11", nextTone: "none", law: "Наредба, чл. 22", collect: "82,6%", debt: "€3.208,00", fund: "€9.870,20", mandate: "27.03.27", risk: "calm", riskLabel: "Спокоен" },
  { addr: "ул. Гурко 22, вх. А", units: 14, overdue: 0, next: "Отчет пред ОС", nextDate: "14.11", nextTone: "none", law: "чл. 11, ал. 1, т. 4", collect: "96,2%", debt: "€180,40", fund: "€4.260,00", mandate: "21.05.27", risk: "calm", riskLabel: "Спокоен" },
  { addr: "ул. Оборище 55, вх. Б", units: 20, overdue: 0, next: "Застраховка общи части", nextDate: "01.12", nextTone: "none", law: "решение ОС, т. 6", collect: "89,7%", debt: "€1.104,00", fund: "€7.320,80", mandate: "08.08.27", risk: "calm", riskLabel: "Спокоен" },
  { addr: "ул. Ангел Кънчев 8, вх. А", units: 11, overdue: 0, next: "Актуализация живущи", nextDate: "08.12", nextTone: "none", law: "чл. 51, ал. 1", collect: "98,4%", debt: "€60,00", fund: "€1.845,00", mandate: "03.10.27", risk: "calm", riskLabel: "Спокоен" },
];

const FILTERS = [
  { label: "Всички 62", active: true },
  { label: "С просрочия 4" },
  { label: "Мандат < 60 дни 7" },
  { label: "Събираемост < 80% 9" },
  { label: "Отрицателен фонд 3" },
];

function dateClass(tone: Tone): string {
  if (tone === "late") return "late";
  if (tone === "warn") return "warn-text";
  return "";
}

export default function PortfolioPage() {
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="title">Портфейл</span>
          <span className="divider" />
          <span className="meta">62 входа · 41 сгради · 1.184 обекта</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <div className="search">Търси вход, адрес, собственик…</div>
          <span className="today">вт, 22.09.2026</span>
        </div>
      </div>

      <div style={{ padding: "16px 24px 0" }}>
        <div style={{ font: "400 13px/1.5 'IBM Plex Sans'", color: "#3D413E" }}>
          <span style={{ fontWeight: 500, color: "#8E2318" }}>4 входа</span> с просрочени задачи по ЗУЕС ·{" "}
          <span style={{ fontWeight: 500, color: "#7A5210" }}>7 мандата</span> изтичат до 30.11.2026 ·{" "}
          <span style={{ fontWeight: 500 }}>9 входа</span> под 80% събираемост · общо вземания{" "}
          <span style={{ fontWeight: 500, fontVariantNumeric: "tabular-nums" }}>€41.806,20</span>
        </div>

        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 14 }}>
          <div style={{ display: "flex", gap: 6 }}>
            {FILTERS.map((f) => (
              <button key={f.label} type="button" className={`chip${f.active ? " active" : ""}`}>
                {f.label}
              </button>
            ))}
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <span style={{ font: "400 12px/1 'IBM Plex Sans'", color: "#6B6F6C" }}>
              Сортирано по <span style={{ color: "#17191A", fontWeight: 500 }}>Риск ↓</span>
            </span>
            <button type="button" className="chip">Износ CSV</button>
          </div>
        </div>
      </div>

      <div className="pf-card">
        <div className="pf-grid pf-head">
          <div>Вход</div>
          <div className="num">Просрочени</div>
          <div>Следващ срок</div>
          <div className="num">Събираемост</div>
          <div className="num">Задължения</div>
          <div className="num">Фонд „Ремонт“</div>
          <div className="num">Мандат до</div>
          <div className="num" style={{ color: "#17191A" }}>Риск ↓</div>
        </div>

        {ROWS.map((r) => (
          <div key={r.addr} className={`pf-grid pf-row${r.overdue > 0 ? " flagged" : ""}`}>
            <div className="ellipsis">
              <span style={{ fontWeight: 500 }}>{r.addr}</span> <span className="dim">· {r.units} об.</span>
            </div>
            <div className="num" style={{ color: r.overdue > 0 ? "#8E2318" : "#6B6F6C", fontWeight: r.overdue > 0 ? 500 : 400 }}>
              {r.overdue}
            </div>
            <div className="ellipsis">
              {r.next} · <span className={`num ${dateClass(r.nextTone)}`} style={{ display: "inline" }}>{r.nextDate}</span>{" "}
              <span className="law">{r.law}</span>
            </div>
            <div className={`num${r.collectLate ? " late" : ""}`}>{r.collect}</div>
            <div className="num" style={{ fontWeight: r.risk === "crit" ? 500 : 400 }}>
              {r.debt}
            </div>
            <div className={`num${r.fundLate ? " late" : ""}`}>{r.fund}</div>
            <div className={`num${r.mandateWarn ? " warn-text" : ""}`}>{r.mandate}</div>
            <div className="num">
              <span className={`badge ${r.risk}`}>{r.riskLabel}</span>
            </div>
          </div>
        ))}

        <div className="pf-grid pf-foot">
          <div>Общо 62 входа</div>
          <div className="num late">9</div>
          <div className="dim" style={{ fontWeight: 400 }}>показани 12 от 62</div>
          <div className="num">86,1%</div>
          <div className="num">€41.806,20</div>
          <div className="num">€284.120,60</div>
          <div />
          <div />
        </div>
      </div>
      <div style={{ height: 24, flex: "none" }} />
    </>
  );
}
