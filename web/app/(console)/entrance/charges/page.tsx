import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "Начисления · октомври 2026 — Етаж" };

interface ChargeRow {
  obj: string;
  owner: string;
  residents: number;
  exempt?: { text: string; tone: "calm" | "warn" };
  coef: string;
  coefHi?: boolean; // commercial coefficient, highlighted green
  ideal: string;
  mgmt: string;
  common: string;
  elevator: string; // "—" when the object does not pay it
  fund: string;
  total: string;
  flagged?: boolean;
}

// The October 2026 charge run for the entrance (the design's numbers). This is exactly what the
// backend's computeChargeRun produces per object — wiring it later replaces this array.
const ROWS: ChargeRow[] = [
  { obj: "ап. 1", owner: "Г. Иванов", residents: 2, coef: "1,00", ideal: "3,845120%", mgmt: "€12,00", common: "€9,00", elevator: "—", fund: "€23,07", total: "€44,07" },
  { obj: "ап. 2", owner: "С. Тодорова", residents: 1, exempt: { text: "Дете < 6 г.", tone: "calm" }, coef: "1,00", ideal: "3,210400%", mgmt: "€6,00", common: "€4,50", elevator: "—", fund: "€19,26", total: "€29,76" },
  { obj: "ап. 4", owner: "Н. Стоянов", residents: 3, coef: "1,00", ideal: "4,102880%", mgmt: "€18,00", common: "€13,50", elevator: "€9,00", fund: "€24,62", total: "€65,12" },
  { obj: "ап. 6", owner: "Д. Христова", residents: 2, exempt: { text: "Отсъства > 30 д.", tone: "calm" }, coef: "1,00", ideal: "3,845120%", mgmt: "€6,00", common: "€4,50", elevator: "€3,00", fund: "€23,07", total: "€36,57" },
  { obj: "ап. 9", owner: "И. Маринов", residents: 4, coef: "1,00", ideal: "5,014200%", mgmt: "€24,00", common: "€18,00", elevator: "€12,00", fund: "€30,09", total: "€84,09" },
  { obj: "ап. 19", owner: "П. Георгиев", residents: 2, exempt: { text: "Ръчна корекция", tone: "warn" }, coef: "1,00", ideal: "4,510600%", mgmt: "€12,00", common: "€9,00", elevator: "€6,00", fund: "€27,06", total: "€54,06", flagged: true },
  { obj: "ап. 20", owner: "Р. Ковачев", residents: 1, coef: "1,00", ideal: "3,102400%", mgmt: "€6,00", common: "€4,50", elevator: "€3,00", fund: "€18,61", total: "€32,11" },
  { obj: "ап. 22", owner: "Ж. Николова", residents: 2, exempt: { text: "Дете < 6 г.", tone: "calm" }, coef: "1,00", ideal: "4,001200%", mgmt: "€6,00", common: "€4,50", elevator: "€3,00", fund: "€24,01", total: "€37,51" },
  { obj: "маг. 1", owner: "„Хляб и сол“ ЕООД", residents: 3, coef: "2,00", coefHi: true, ideal: "6,842000%", mgmt: "€36,00", common: "€27,00", elevator: "—", fund: "€41,05", total: "€104,05" },
  { obj: "маг. 2", owner: "„Дентал Плюс“ ООД", residents: 2, coef: "1,50", coefHi: true, ideal: "5,920800%", mgmt: "€18,00", common: "€13,50", elevator: "—", fund: "€35,52", total: "€67,02" },
];

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

const dim = (v: string) => (v === "—" ? { color: "#6B6F6C" } : undefined);

export default function ChargesPage() {
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">ул. Шипка 14, вх. Б</span>
            <span className="caret">▾</span>
          </span>
          <span className="meta">
            Начисления · <span style={{ fontWeight: 500, color: "#17191A" }}>октомври 2026</span>
          </span>
          <span className="badge warn">Чернова</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <button type="button" className="chip">◀ септември</button>
          <button type="button" className="chip" style={{ color: "#6B6F6C" }}>ноември ▶</button>
        </div>
      </div>

      <div style={{ padding: "16px 24px 0" }}>
        <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "12px 16px", display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 24 }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>Базис на изчислението</div>
            <div style={{ font: "400 12.5px/1.6 'IBM Plex Sans'", color: "#3D413E", marginTop: 6 }}>
              Решение на ОС от <span style={{ fontVariantNumeric: "tabular-nums" }}>12.03.2026</span>, т. 4 · управление{" "}
              <span style={{ fontVariantNumeric: "tabular-nums" }}>6,00 €</span>/живущ · ток и почистване на общи части{" "}
              <span style={{ fontVariantNumeric: "tabular-nums" }}>4,50 €</span>/живущ · асансьор{" "}
              <span style={{ fontVariantNumeric: "tabular-nums" }}>3,00 €</span>/живущ от 2-ри етаж · фонд „Ремонт“{" "}
              <span style={{ fontVariantNumeric: "tabular-nums" }}>0,60 €</span> на 0,01% ид. части
            </div>
            <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 4 }}>
              Освобождавания по чл. 51, ал. 2–3 ЗУЕС · търговски обекти с коефициент по решение на ОС, т. 4.3
            </div>
          </div>
          <div style={{ flex: "none", textAlign: "right" }}>
            <div style={{ font: "400 11.5px/1 'IBM Plex Sans'", color: "#6B6F6C" }}>Изготвил</div>
            <div style={{ font: "400 12.5px/1.6 'IBM Plex Sans'" }}>М. Петрова · 22.09.2026, 10:14</div>
            <div style={{ font: "400 11.5px/1 'IBM Plex Sans'", color: "#6B6F6C", marginTop: 4 }}>Версия 2 · 1 корекция (ап. 19)</div>
          </div>
        </div>
      </div>

      <div className="pf-card">
        <div className="ch-grid ch-head">
          {HEADERS.map((h) => (
            <div key={h.label} className={h.num ? "num" : ""} style={h.ink ? { color: "#17191A" } : undefined}>{h.label}</div>
          ))}
        </div>

        {ROWS.map((r) => (
          <div key={r.obj} className={`ch-grid ch-row${r.flagged ? " flagged" : ""}`}>
            <div style={{ fontWeight: 500 }}>{r.obj}</div>
            <div className="ellipsis">{r.owner}</div>
            <div className="num">{r.residents}</div>
            <div className="ellipsis" style={r.exempt ? undefined : { color: "#6B6F6C" }}>
              {r.exempt ? <span className={`tag-mini ${r.exempt.tone}`}>{r.exempt.text}</span> : "—"}
            </div>
            <div className="num" style={r.coefHi ? { color: "#14584A", fontWeight: 500 } : { color: "#6B6F6C" }}>{r.coef}</div>
            <div className="num">{r.ideal}</div>
            <div className="num">{r.mgmt}</div>
            <div className="num">{r.common}</div>
            <div className="num" style={dim(r.elevator)}>{r.elevator}</div>
            <div className="num">{r.fund}</div>
            <div className="num" style={{ fontWeight: 500 }}>{r.total}</div>
          </div>
        ))}

        <div className="ch-grid ch-more">
          <div>…</div>
          <div>още 14 обекта</div>
          <div /><div /><div /><div /><div /><div /><div /><div /><div />
        </div>

        <div className="ch-grid ch-foot">
          <div>Общо</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>24 обекта</div>
          <div className="num">57</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>4 освободени</div>
          <div />
          <div className="num">100,000000%</div>
          <div className="num">€318,00</div>
          <div className="num">€238,50</div>
          <div className="num">€96,00</div>
          <div className="num">€600,00</div>
          <div className="num">€1.252,50</div>
        </div>
      </div>

      <div className="action-bar">
        <div>
          <div style={{ font: "400 12px/1.5 'IBM Plex Sans'", color: "#5C605E" }}>
            Проверки: сумата на идеалните части е <span style={{ color: "#14584A", fontWeight: 500 }}>100,000000%</span> · 24 от 24 обекта с валиден базис · 1 ръчна корекция с основание
          </div>
          <div style={{ font: "400 11.5px/1.4 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>
            След потвърждение начислението се заключва и влиза в дневника на входа (необратимо, с одитна следа).
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
          <div style={{ textAlign: "right" }}>
            <div style={{ font: "400 11px/1 'IBM Plex Sans'", letterSpacing: ".1em", textTransform: "uppercase", color: "#6B6F6C" }}>Общо за начисляване</div>
            <div style={{ font: "500 24px/1.2 'IBM Plex Sans'", fontVariantNumeric: "tabular-nums" }}>€1.252,50</div>
          </div>
          <button type="button" className="btn-outline">Върни за корекция</button>
          <button type="button" className="btn-confirm">Потвърди начисленията</button>
        </div>
      </div>
    </>
  );
}
