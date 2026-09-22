import type { Metadata } from "next";

export const metadata: Metadata = { title: "Съответствие на фирмата — Етаж" };

interface StatusCard {
  title: string;
  value: string;
  sub: string;
  kvLabel: string;
  kvValue: string;
  kvColor?: string;
  badge: { text: string; kind: "calm" | "warn" | "crit" };
  link: string;
}

// The firm's own regulatory standing — the licences and cover a professional домоуправител must keep.
const CARDS: StatusCard[] = [
  {
    title: "Вписване в публичния регистър",
    value: "Рег. № ПД-0142",
    sub: "Регистър на професионалните домоуправители · Столична община",
    kvLabel: "Валидно до",
    kvValue: "14.12.2026",
    badge: { text: "Остават 83 дни", kind: "warn" },
    link: "Подготви подновяване",
  },
  {
    title: "Застраховка „Професионална отговорност“",
    value: "Полица 22-0034512",
    sub: "ЗАД „Армеец“ · лимит на отговорност €50.000 за събитие",
    kvLabel: "Валидна до",
    kvValue: "31.10.2026",
    kvColor: "#8E2318",
    badge: { text: "Остават 39 дни", kind: "crit" },
    link: "Поискай оферта",
  },
  {
    title: "Договори за управление",
    value: "62 действащи",
    sub: "Мандати по чл. 19, ал. 5 ЗУЕС · подновяване с решение на ОС",
    kvLabel: "Изтичат до 30.11.2026",
    kvValue: "7",
    kvColor: "#7A5210",
    badge: { text: "3 със свикано ОС", kind: "calm" },
    link: "Виж списъка",
  },
];

interface Filing {
  doc: string;
  period: string;
  inst: string;
  submitted: string;
  submittedColor?: string;
  ref: string;
  refDim?: boolean;
  basis: string;
  status: { text: string; kind: "calm" | "warn" | "crit" };
  flagged?: boolean;
}

const FILINGS: Filing[] = [
  { doc: "Годишен финансов отчет на фирмата", period: "2025", inst: "Агенция по вписванията", submitted: "28.06.26", ref: "20260628153412", basis: "чл. 38 ЗСч", status: { text: "Приет", kind: "calm" } },
  { doc: "Декларация за поддържане на регистрацията", period: "2026", inst: "Столична община", submitted: "12.02.26", ref: "СОА26-ГР94-1142", basis: "чл. 46б, ал. 3 ЗУЕС", status: { text: "Приет", kind: "calm" } },
  { doc: "Регистър на обработваните лични данни (ОЛД)", period: "2026", inst: "КЗЛД", submitted: "04.03.26", ref: "КЗЛД-26-0881", basis: "чл. 30 ОРЗД", status: { text: "Приет", kind: "calm" } },
  { doc: "Уведомления за новоизбрани управители · Q3", period: "Q3 2026", inst: "Столична община · районни", submitted: "—", submittedColor: "#8E2318", ref: "3 от 4 входа", refDim: true, basis: "чл. 46б ЗУЕС · 7 дни", status: { text: "Просрочен", kind: "crit" }, flagged: true },
  { doc: "Отчет за дейността пред клиентите (обобщен)", period: "H1 2026", inst: "вътрешен · до собствениците", submitted: "15.07.26", ref: "—", refDim: true, basis: "договор за управление, т. 9", status: { text: "Изпратен", kind: "calm" } },
  { doc: "Декларация по ЗМИП за фирмата", period: "2026", inst: "ДАНС", submitted: "—", submittedColor: "#7A5210", ref: "чернова", refDim: true, basis: "чл. 98 ЗМИП · до 15.10", status: { text: "До 23 дни", kind: "warn" } },
];

const HEADERS: { label: string; num?: boolean }[] = [
  { label: "Документ" }, { label: "Период" }, { label: "Институция" },
  { label: "Подаден", num: true }, { label: "Вх. №" }, { label: "Основание" }, { label: "Статус", num: true },
];

export default function CompliancePage() {
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="title">Съответствие на фирмата</span>
          <span className="divider" />
          <span className="meta">Домоуправител Про ООД · ЕИК <span style={{ fontVariantNumeric: "tabular-nums" }}>204118736</span> · 62 входа под управление</span>
        </div>
        <span className="badge warn">2 изтичат до 60 дни</span>
      </div>

      <div style={{ padding: "20px 24px 0", display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 16 }}>
        {CARDS.map((c) => (
          <div key={c.title} className="co-card">
            <div className="kicker">{c.title}</div>
            <div className="serif">{c.value}</div>
            <div className="sub">{c.sub}</div>
            <div className="kv">
              <span style={{ font: "400 12px/1 'IBM Plex Sans'", color: "#5C605E" }}>{c.kvLabel}</span>
              <span style={{ font: "500 13px/1 'IBM Plex Sans'", fontVariantNumeric: "tabular-nums", color: c.kvColor }}>{c.kvValue}</span>
            </div>
            <div style={{ marginTop: 10, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <span className={`badge ${c.badge.kind}`}>{c.badge.text}</span>
              <span className="link">{c.link}</span>
            </div>
          </div>
        ))}
      </div>

      <div style={{ margin: "20px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <span style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>Подадени отчети и декларации</span>
        <div style={{ display: "flex", gap: 6 }}>
          <button type="button" className="chip active">2026</button>
          <button type="button" className="chip">2025</button>
          <button type="button" className="chip">Само неподадени</button>
        </div>
      </div>

      <div className="pf-card" style={{ margin: "12px 24px 24px" }}>
        <div className="co-grid pf-head">
          {HEADERS.map((h) => (
            <div key={h.label} className={h.num ? "num" : ""}>{h.label}</div>
          ))}
        </div>

        {FILINGS.map((f) => (
          <div key={f.doc} className={`co-grid pf-row${f.flagged ? " flagged" : ""}`}>
            <div className="ellipsis">{f.doc}</div>
            <div style={{ fontVariantNumeric: "tabular-nums" }}>{f.period}</div>
            <div className="ellipsis">{f.inst}</div>
            <div className="num" style={{ color: f.submittedColor }}>{f.submitted}</div>
            <div className="mono" style={f.refDim ? { color: "#6B6F6C" } : undefined}>{f.ref}</div>
            <div className="mono" style={{ color: "#6B6F6C" }}>{f.basis}</div>
            <div className="num">
              <span className={`badge ${f.status.kind}`}>{f.status.text}</span>
            </div>
          </div>
        ))}

        <div className="co-grid pf-foot">
          <div>6 задължения за 2026</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>4 приети</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>1 просрочено · 1 предстоящо</div>
          <div /><div /><div /><div />
        </div>
      </div>
    </>
  );
}
