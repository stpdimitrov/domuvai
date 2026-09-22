import type { Metadata } from "next";
import Link from "next/link";
import "./assembly.css";

export const metadata: Metadata = { title: "Общо събрание · 05.10.2026 — Етаж" };

const ell: React.CSSProperties = { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" };
const tnum: React.CSSProperties = { fontVariantNumeric: "tabular-nums" };

type AgendaStatus = "passed" | "voting" | "pending";
interface AgendaItem {
  n: string;
  title: string;
  status: string;
  kind: AgendaStatus;
  active?: boolean;
}
const AGENDA: AgendaItem[] = [
  { n: "т. 1", title: "Отчет на управителя за 2025/2026", status: "Приета · 41,102% за", kind: "passed" },
  { n: "т. 2", title: "Отчет за фонд „Ремонт и обновяване“", status: "Приета · 44,516% за", kind: "passed" },
  { n: "т. 3", title: "Ремонт на покрив — избор на изпълнител и сума до 18.000 €", status: "Гласува се сега", kind: "voting", active: true },
  { n: "т. 4", title: "Размер на месечните вноски за 2027", status: "Предстои", kind: "pending" },
  { n: "т. 5", title: "Избор на управител и УС — нов мандат", status: "Предстои · мандат изтича 31.10.2026", kind: "pending" },
  { n: "т. 6", title: "Действия по събиране на вземания", status: "Предстои", kind: "pending" },
];
const statusColor: Record<AgendaStatus, string> = { passed: "#14584A", voting: "#7A5210", pending: "#6B6F6C" };

interface Vote {
  label: string;
  pctRep: string; // % of ideal parts
  pctOf: string; // % of represented
  fill: number; // bar width %
  color: string;
  mark67?: boolean;
  dim?: boolean;
}
const VOTES: Vote[] = [
  { label: "За", pctRep: "41,207%", pctOf: "70,55%", fill: 70.5, color: "#14584A", mark67: true },
  { label: "Против", pctRep: "6,158%", pctOf: "10,54%", fill: 10.5, color: "#A32B23" },
  { label: "Въздържал се", pctRep: "4,100%", pctOf: "7,02%", fill: 7, color: "#B6B9B2" },
  { label: "Не гласували · 2 обекта", pctRep: "6,947%", pctOf: "11,89%", fill: 11.9, color: "#DEDDD9", dim: true },
];

interface Attendee {
  ap: string;
  name: string;
  proxy?: string; // "за ап. 12" or "" for a plain proxy tag
  pct: string;
  alt?: boolean;
}
const ATTEND: Attendee[] = [
  { ap: "ап. 1", name: "Г. Иванов", pct: "3,845120%" },
  { ap: "ап. 2", name: "С. Тодорова", pct: "3,210400%" },
  { ap: "ап. 4", name: "Н. Стоянов", proxy: "за ап. 12", pct: "7,948000%", alt: true },
  { ap: "ап. 6", name: "Д. Христова", pct: "3,845120%" },
  { ap: "ап. 9", name: "И. Маринов", proxy: "за ап. 10, 11", pct: "12,104400%", alt: true },
  { ap: "ап. 14", name: "К. Петров", pct: "3,410000%" },
  { ap: "ап. 16", name: "Е. Василева", pct: "3,102400%" },
  { ap: "ап. 19", name: "П. Георгиев", proxy: "за ап. 20", pct: "7,613000%", alt: true },
  { ap: "ап. 22", name: "Ж. Николова", pct: "4,001200%" },
  { ap: "маг. 1", name: "„Хляб и сол“ ЕООД", proxy: "", pct: "6,842000%", alt: true },
  { ap: "маг. 2", name: "„Дентал Плюс“ ООД", pct: "5,920800%" },
];

export default function AssemblyPage() {
  return (
    <div className="asm">
      <div className="asm-top">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" style={{ color: "#14584A", flex: "none" }}>
            <path d="M3 3.5h18v3.4H3z" /><path d="M3 10.3h13.2v3.4H3z" /><path d="M3 17.1h8.4v3.4H3z" />
          </svg>
          <Link href="/portfolio" style={{ font: "400 12.5px/1 'IBM Plex Sans'", color: "#6B6F6C", textDecoration: "none" }}>Портфейл /</Link>
          <span className="asm-pill"><span className="name">ул. Шипка 14, вх. Б</span></span>
          <span style={{ font: "400 12.5px/1 'IBM Plex Sans'", color: "#5C605E" }}>Редовно ОС · <span style={tnum}>05.10.2026</span></span>
          <span className="sbadge live">В момента · сесия 2</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span style={{ font: "400 12px/1 'IBM Plex Sans'", color: "#5C605E", ...tnum }}>19:12 · протоколчик: М. Петрова</span>
          <button type="button" className="asm-ghost">Протокол</button>
        </div>
      </div>

      <div className="asm-banner">
        <div style={{ font: "400 12.5px/1.4 'IBM Plex Sans'", color: "#5F3F0C" }}>
          <span style={{ fontWeight: 600 }}>Първа сесия 18:00 — без кворум:</span> присъствали <span style={{ ...tnum, fontWeight: 600 }}>43,218%</span> при изискуеми 51,000% ·{" "}
          <span style={{ fontWeight: 600 }}>Втора сесия открита 19:00</span> при праг <span style={{ ...tnum, fontWeight: 600 }}>26,000%</span>
          <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", color: "#7A5210" }}> · чл. 15, ал. 2 ЗУЕС</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <span className="sbadge fail">Сесия 1 · 51% пропаднала</span>
          <span className="sbadge open">Сесия 2 · 26% открита</span>
          <span className="sbadge next">Сесия 3 · 06.10, без праг</span>
        </div>
      </div>

      <div className="asm-body">
        {/* agenda */}
        <div className="agenda">
          <div className="agenda-h"><span>Дневен ред</span><span style={tnum}>6 точки</span></div>
          <div style={{ padding: "8px 0" }}>
            {AGENDA.map((a) => (
              <div key={a.n} className={`agenda-item${a.active ? " active" : ""}`}>
                <span className="agenda-n">{a.n}</span>
                <div style={{ minWidth: 0 }}>
                  <div className="agenda-t">{a.title}</div>
                  <div className={`agenda-s${a.kind === "pending" ? " pending" : ""}`} style={a.kind !== "pending" ? { color: statusColor[a.kind] } : undefined}>{a.status}</div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* current item */}
        <div className="asm-center">
          <div>
            <div className="kicker">Точка 3 от дневния ред</div>
            <div style={{ font: "400 24px/1.25 Literata, Georgia, serif", marginTop: 8 }}>
              Ремонт на покрив: избор на изпълнител „Строй Дом“ ООД и разход до 18.000 €
            </div>
            <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 6 }}>
              Необходимо мнозинство: 67% от представените идеални части · чл. 17, ал. 2, т. 4 ЗУЕС
            </div>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)", gap: 16 }}>
            <div className="card" style={{ padding: "16px 18px" }}>
              <div className="kicker">Кворум сега</div>
              <div style={{ font: "400 56px/1 'IBM Plex Sans'", letterSpacing: "-0.02em", marginTop: 10, ...tnum }}>
                58,412<span style={{ fontSize: 28 }}>%</span>
              </div>
              <div style={{ font: "400 12px/1.5 'IBM Plex Sans'", color: "#5C605E", marginTop: 6 }}>представени идеални части</div>
              <div className="qbar">
                <div className="qfill" style={{ width: "58.4%" }} />
                <div className="qmark" style={{ left: "26%", background: "#7A5210" }} />
                <div className="qmark" style={{ left: "51%", background: "#8E2318" }} />
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", font: "400 10.5px/1 'IBM Plex Sans'", color: "#6B6F6C", marginTop: 5, ...tnum }}>
                <span>0%</span><span style={{ color: "#7A5210" }}>26% праг сесия 2</span><span style={{ color: "#8E2318" }}>51%</span><span>100%</span>
              </div>
              <div style={{ marginTop: 12, padding: "9px 11px", background: "#F7F6F3", border: "1px solid #EAE9E5" }}>
                <div style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".1em", textTransform: "uppercase", color: "#6B6F6C" }}>Знаменател за кворум</div>
                <div style={{ font: "400 12.5px/1.5 'IBM Plex Sans'", marginTop: 5 }}>Всички идеални части на входа — <span style={{ ...tnum, fontWeight: 500 }}>100,000000%</span></div>
              </div>
            </div>

            <div className="card" style={{ padding: "16px 18px", display: "flex", flexDirection: "column" }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                <div className="kicker">Резултат по т. 3</div>
                <div className="toggle">
                  <span className="on">Само представени</span>
                  <span>Всички ид. части</span>
                </div>
              </div>
              <div style={{ font: "400 11.5px/1.5 'IBM Plex Mono', monospace", color: "#6B6F6C", marginTop: 8 }}>Знаменател: 58,412% представени идеални части</div>

              <div style={{ marginTop: 14, display: "flex", flexDirection: "column", gap: 10 }}>
                {VOTES.map((v) => (
                  <div key={v.label}>
                    <div className="vrow-head" style={v.dim ? { color: "#5C605E" } : undefined}>
                      <span>{v.label}</span>
                      <span style={tnum}>
                        {v.pctRep} <span style={{ color: "#6B6F6C", fontWeight: 400 }}>{v.label === "За" ? "от представените · " : "· "}{v.pctOf}</span>
                      </span>
                    </div>
                    <div className="vbar">
                      <div className="vfill" style={{ width: `${v.fill}%`, background: v.color }} />
                      {v.mark67 && <div className="vmark" style={{ left: "67%" }} />}
                    </div>
                  </div>
                ))}
              </div>

              <div style={{ marginTop: "auto", paddingTop: 14, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10 }}>
                <div style={{ font: "400 12px/1.45 'IBM Plex Sans'", color: "#3D413E" }}>
                  Достигнато мнозинство <span style={{ fontWeight: 500 }}>70,55%</span> ≥ 67% — <span style={{ color: "#14584A", fontWeight: 500 }}>решението се приема</span>
                </div>
                <button type="button" className="asm-solid">Приключи точката</button>
              </div>
            </div>
          </div>

          <div className="card" style={{ padding: "12px 16px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <div style={{ font: "400 12px/1.5 'IBM Plex Sans'", color: "#5C605E" }}>
              Гласували <span style={{ ...tnum, color: "#17191A", fontWeight: 500 }}>14</span> от 16 присъстващи обекта · 2 непроведени гласа се отчитат като неучаствали, не като „против“
            </div>
            <div style={{ display: "flex", gap: 8 }}>
              <button type="button" className="asm-chip">Поименно гласуване</button>
              <button type="button" className="asm-chip">Отложи точката</button>
            </div>
          </div>
        </div>

        {/* attendance */}
        <div className="attend">
          <div className="attend-h"><span>Присъствие</span><span style={tnum}>16 от 24 обекта</span></div>
          <div style={{ padding: "10px 14px", borderBottom: "1px solid #EAE9E5", display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
            <span style={{ font: "400 12px/1 'IBM Plex Sans'", color: "#5C605E" }}>Представени ид. части</span>
            <span style={{ font: "500 14px/1 'IBM Plex Sans'", ...tnum }}>58,412000%</span>
          </div>
          <div style={{ padding: "8px 14px", borderBottom: "1px solid #EAE9E5", display: "flex", gap: 6 }}>
            <span className="chip-count calm">Лично 11</span>
            <span className="chip-count green">Пълномощно 5</span>
          </div>
          <div className="attend-scroll">
            {ATTEND.map((a) => (
              <div key={a.ap} className={`attend-row${a.alt ? " alt" : ""}`}>
                <span style={{ fontWeight: 500 }}>{a.ap}</span>
                <span style={ell}>
                  {a.name}
                  {a.proxy !== undefined && <span className="proxy-tag" style={{ marginLeft: 5, verticalAlign: 1 }}>Пълн.</span>}
                  {a.proxy && <span style={{ color: "#6B6F6C" }}> {a.proxy}</span>}
                </span>
                <span style={{ textAlign: "right", ...tnum }}>{a.pct}</span>
              </div>
            ))}
            <div className="attend-row" style={{ color: "#6B6F6C", borderBottom: 0 }}>
              <span>…</span><span>още 5 присъстващи</span><span style={{ textAlign: "right", ...tnum }}>16,569%</span>
            </div>
          </div>
          <div style={{ flex: "none", borderTop: "1px solid #DEDDD9", padding: "10px 14px", display: "flex", gap: 8 }}>
            <button type="button" className="asm-chip" style={{ background: "#17191A", borderColor: "#17191A", color: "#fff", fontWeight: 500, lineHeight: "28px" }}>Впиши присъстващ</button>
            <button type="button" className="asm-chip">Провери пълномощно</button>
          </div>
        </div>
      </div>
    </div>
  );
}
