import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "ул. Шипка 14, вх. Б — Етаж" };

type Tone = "late" | "warn" | "none";
type Dot = "red" | "amber" | "hollow";
type Badge = "crit" | "warn" | "calm";

interface CalItem {
  date: string;
  tone: Tone;
  dot: Dot;
  title: string;
  bold: boolean;
  law: string;
  badge: { text: string; kind: Badge };
  note?: string;
  noLine?: boolean;
}
interface CalGroup {
  label: string;
  color: string;
  items: CalItem[];
}

// The entrance's statutory calendar (the design's data). Each item cites the ЗУЕС article it
// derives from — the deadlines the backend's `law` module will compute once wired.
const CALENDAR: CalGroup[] = [
  {
    label: "Просрочени · 3",
    color: "#8E2318",
    items: [
      { date: "18.09.26", tone: "late", dot: "red", bold: true, title: "Отчет на управителя пред общото събрание", law: "чл. 11, ал. 1, т. 4 ЗУЕС · отчетен период 2025/2026", badge: { text: "Просрочен 4 дни", kind: "crit" }, note: "отг. М. Петрова" },
      { date: "11.09.26", tone: "late", dot: "red", bold: true, title: "Актуализация на книгата на собствениците", law: "чл. 7, ал. 1 ЗУЕС · 2 непотвърдени декларации", badge: { text: "Просрочен 11 дни", kind: "crit" }, note: "ап. 7, ап. 19" },
      { date: "02.09.26", tone: "late", dot: "red", bold: true, title: "Уведомяване на общината за новоизбран управител", law: "чл. 46б ЗУЕС · срок 7 дни от решението на ОС", badge: { text: "Просрочен 20 дни", kind: "crit" }, note: "р-н Оборище" },
    ],
  },
  {
    label: "Този месец",
    color: "#7A5210",
    items: [
      { date: "28.09.26", tone: "warn", dot: "amber", bold: true, title: "Покана за общо събрание — поставяне и връчване", law: "чл. 13, ал. 1 ЗУЕС · най-малко 7 дни преди ОС (05.10)", badge: { text: "До 6 дни", kind: "warn" }, note: "протокол за поставяне" },
      { date: "30.09.26", tone: "warn", dot: "amber", bold: true, title: "Месечно начисление — октомври 2026", law: "решение на ОС от 12.03.2026, т. 4 · 24 обекта", badge: { text: "До 8 дни", kind: "warn" }, note: "чака потвърждение" },
    ],
  },
  {
    label: "Следващите 90 дни",
    color: "#6B6F6C",
    items: [
      { date: "12.10.26", tone: "none", dot: "hollow", bold: false, title: "Годишна проверка на асансьорната инсталация", law: "Наредба за безопасна експлоатация, чл. 22 · „Лифт Сервиз“ ООД", badge: { text: "Планиран", kind: "calm" }, note: "договор до 31.12.26" },
      { date: "31.10.26", tone: "none", dot: "hollow", bold: false, title: "Изтича мандатът на управителния съвет", law: "чл. 19, ал. 5 ЗУЕС · избор на нов УС в срок", badge: { text: "39 дни", kind: "warn" }, note: "т. 5 от дневния ред" },
      { date: "15.12.26", tone: "none", dot: "hollow", bold: false, title: "Годишен отчет за фонд „Ремонт и обновяване“", law: "чл. 50, ал. 3 ЗУЕС · пред ОС и в дело на входа", badge: { text: "Планиран", kind: "calm" }, noLine: true },
    ],
  },
];

const TABS = ["Календар", "Обекти", "Начисления", "Каса и фонд", "Събрания", "Задължения", "Документи"];

const toneColor: Record<Tone, string> = { late: "#8E2318", warn: "#7A5210", none: "#5C605E" };

function Dot({ kind }: { kind: Dot }) {
  const style =
    kind === "red"
      ? { background: "#A32B23" }
      : kind === "amber"
        ? { background: "#B8860B" }
        : { background: "#FFFFFF", border: "1.5px solid #B6B9B2" };
  return <span className="cal-dot" style={style} />;
}

function CalRow({ item }: { item: CalItem }) {
  return (
    <div className="cal-item">
      <div className="cal-date" style={{ color: toneColor[item.tone] }}>{item.date}</div>
      <div className="cal-rail">
        <Dot kind={item.dot} />
        {!item.noLine && <span className="cal-line" />}
      </div>
      <div>
        <div className={`cal-title${item.bold ? "" : " plain"}`}>{item.title}</div>
        <div className="cal-law">{item.law}</div>
      </div>
      <div className="cal-side">
        <span className={`badge ${item.badge.kind}`}>{item.badge.text}</span>
        {item.note && <span className="cal-note">{item.note}</span>}
      </div>
    </div>
  );
}

export default function EntrancePage() {
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">ул. Шипка 14, вх. Б</span>
            <span className="caret">▾</span>
          </span>
          <span className="meta">24 обекта · 57 живущи · ид. части 100,000000%</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="pill-badge">3 просрочени</span>
          <span className="today">вт, 22.09.2026</span>
        </div>
      </div>

      <div className="tabbar">
        {TABS.map((t, i) => (
          <span key={t} className={`tab${i === 0 ? " active" : ""}`}>{t}</span>
        ))}
      </div>

      <div className="entrance-body">
        <div className="panel-card">
          <div className="panel-head">
            <span className="panel-title">Статутен календар</span>
            <div style={{ display: "flex", gap: 6 }}>
              <button type="button" className="mini active">Всички</button>
              <button type="button" className="mini">Само законови</button>
              <button type="button" className="mini">Нова задача</button>
            </div>
          </div>

          <div className="panel-scroll">
            {CALENDAR.map((group) => (
              <div key={group.label}>
                <div className="cal-group" style={{ color: group.color, padding: "0 0 9px" }}>{group.label}</div>
                {group.items.map((item) => (
                  <CalRow key={item.date + item.title} item={item} />
                ))}
                <div className="rule" />
                {group !== CALENDAR[CALENDAR.length - 1] && <div style={{ height: 5 }} />}
              </div>
            ))}
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", paddingTop: 12 }}>
              <span className="cal-group" style={{ color: "#6B6F6C" }}>Изпълнени · последни 6 месеца</span>
              <span style={{ font: "400 12px/1 'IBM Plex Sans'", color: "#14584A" }}>Покажи 14 записа</span>
            </div>
          </div>
        </div>

        <div className="entrance-aside">
          <div className="kv-card">
            <div className="kv-head">Дело на входа</div>
            <div className="kv-body">
              <div className="kv-row"><span>Идеални части</span><span className="v" style={{ fontWeight: 500 }}>100,000000%</span></div>
              <div className="kv-row"><span>Обекти</span><span className="v">24 · 22 жил. / 2 търг.</span></div>
              <div className="kv-row"><span>Живущи по декларации</span><span className="v">57</span></div>
              <div className="kv-row"><span>Управител</span><span className="v" style={{ fontVariantNumeric: "normal" }}>Мария Петрова</span></div>
              <div className="kv-row"><span>Мандат до</span><span className="v" style={{ color: "#7A5210", fontWeight: 500 }}>31.10.2026</span></div>
              <div className="kv-row"><span>Последно ОС</span><span className="v">12.03.2026</span></div>
              <div className="kv-row"><span>Книга на собствениците</span><span style={{ color: "#8E2318", fontWeight: 500 }}>неактуална</span></div>
            </div>
          </div>

          <div className="kv-card">
            <div className="kv-head">Сметки на входа</div>
            <div style={{ padding: "12px 14px" }}>
              <div className="acct-label">Разплащателна · каса</div>
              <div className="iban">BG18 UNCR 7000 1512 3456 78</div>
              <div className="acct-amt">€3.812,40</div>
              <div className="rule" style={{ margin: "10px 0" }} />
              <div className="acct-label">Фонд „Ремонт и обновяване“</div>
              <div className="iban">BG44 UNCR 7000 1512 9981 02</div>
              <div className="acct-amt">€1.240,50</div>
              <div style={{ font: "400 11px/1.5 'IBM Plex Sans'", color: "#6B6F6C", marginTop: 4 }}>
                Разполагаемо €640,50 — €600,00 поети по договор „Покрив 2026“
              </div>
            </div>
          </div>

          <div className="kv-card">
            <div className="kv-head">Следващо събрание</div>
            <div style={{ padding: "12px 14px" }}>
              <div style={{ font: "400 13px/1.45 'IBM Plex Sans'" }}>
                Редовно ОС · <span style={{ fontVariantNumeric: "tabular-nums", fontWeight: 500 }}>05.10.2026, 18:00</span>
              </div>
              <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 3 }}>
                6 точки · сесии 51% → 26% → без праг (чл. 15 ЗУЕС)
              </div>
              <button type="button" className="btn-solid" style={{ marginTop: 10 }}>Отвори подготовката</button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
