import type { Metadata } from "next";

export const metadata: Metadata = { title: "Задължения — Етаж" };

// The чл. 38 ЗУЕС → чл. 410 ГПК escalation ladder. Bar segment colours by step; the step-1 label
// is a touch darker than its bar. Unreached steps are the neutral rail.
const STEP_BAR = ["#17191A", "#7A5210", "#A32B23", "#5B1B14"];
const STEP_LABEL = ["#3D413E", "#7A5210", "#A32B23", "#5B1B14"];
const RAIL = "#EAE9E5";

type Tone = "late" | "warn" | "none";
const toneColor: Record<Tone, string | undefined> = { late: "#8E2318", warn: "#7A5210", none: undefined };

interface Debtor {
  obj: string;
  debtor: string;
  amount: string;
  interest: string;
  oldest: string;
  oldestTone: Tone;
  step: 1 | 2 | 3 | 4;
  stepLabel: string;
  action: string;
  blocked?: string; // red prefix, e.g. "Блокира ескалацията:"
}
interface Group {
  name: string;
  note: string;
  total: string;
  debtors: Debtor[];
}

const GROUPS: Group[] = [
  {
    name: "ул. Шипка 14, вх. Б",
    note: "· 5 длъжника · решение на ОС от 12.03.2026, т. 6",
    total: "€4.120,80",
    debtors: [
      { obj: "ап. 7", debtor: "В. Димитров", amount: "€1.842,60", interest: "€148,20", oldest: "412 дни", oldestTone: "late", step: 4, stepLabel: "4 · Съд", action: "Заповед за изпълнение подадена 04.09 · дело 4821/2026, СРС 118 с-в" },
      { obj: "ап. 19", debtor: "П. Георгиев", amount: "€980,40", interest: "€64,10", oldest: "248 дни", oldestTone: "late", step: 3, stepLabel: "3 · Решение на ОС", action: "Т. 6 от ОС на 05.10 — упълномощаване за съдебно събиране" },
      { obj: "ап. 12", debtor: "А. Симеонова", amount: "€648,00", interest: "€31,80", oldest: "154 дни", oldestTone: "warn", step: 2, stepLabel: "2 · Нотариална", action: "Връчена 08.09 · 14-дневен срок изтича 22.09" },
      { obj: "маг. 2", debtor: "„Дентал Плюс“ ООД", amount: "€402,10", interest: "€12,40", oldest: "62 дни", oldestTone: "none", step: 1, stepLabel: "1 · Покана", action: "Втора покана по имейл 25.09 · след това нотариална" },
      { obj: "ап. 3", debtor: "Л. Атанасов", amount: "€247,70", interest: "€4,20", oldest: "38 дни", oldestTone: "none", step: 1, stepLabel: "1 · Покана", action: "Обещано плащане до 30.09 · бележка от 12.09" },
    ],
  },
  {
    name: "ж.к. Младост 3, бл. 318, вх. 2",
    note: "· 9 длъжника · няма решение на ОС за съдебно събиране",
    total: "€6.842,10",
    debtors: [
      { obj: "ап. 41", debtor: "Т. Балканска", amount: "€2.104,00", interest: "€186,40", oldest: "520 дни", oldestTone: "late", step: 2, stepLabel: "2 · Нотариална", blocked: "Блокира ескалацията:", action: "нужно решение на ОС (т. 6, свикано 14.10)" },
      { obj: "ап. 55", debtor: "М. Райчев", amount: "€1.412,80", interest: "€98,00", oldest: "366 дни", oldestTone: "late", step: 2, stepLabel: "2 · Нотариална", action: "Върната непотърсена 02.09 · повторно връчване чрез нотариус" },
      { obj: "ап. 12", debtor: "Д. Колев", amount: "€806,40", interest: "€22,60", oldest: "122 дни", oldestTone: "warn", step: 1, stepLabel: "1 · Покана", action: "Разсрочен план 6 вноски · първа вноска платена 15.09" },
    ],
  },
  {
    name: "бул. Витоша 102, вх. А",
    note: "· 3 длъжника",
    total: "€1.980,00",
    debtors: [
      { obj: "ап. 8", debtor: "С. Ангелов", amount: "€1.204,00", interest: "€72,40", oldest: "286 дни", oldestTone: "late", step: 3, stepLabel: "3 · Решение на ОС", action: "Решение от 20.08 · подготвено заявление по чл. 410 ГПК" },
      { obj: "ап. 2", debtor: "Н. Петкова", amount: "€480,00", interest: "€9,60", oldest: "74 дни", oldestTone: "none", step: 1, stepLabel: "1 · Покана", action: "Първа покана връчена 18.09 · срок 14 дни" },
    ],
  },
];

const LEGEND = [
  { c: "#17191A", t: "1 Покана" },
  { c: "#7A5210", t: "2 Нотариална покана" },
  { c: "#A32B23", t: "3 Решение на ОС" },
  { c: "#5B1B14", t: "4 Заповед за изпълнение" },
];

function Ladder({ step }: { step: number }) {
  return (
    <span className="ladder">
      {[0, 1, 2, 3].map((i) => (
        <span key={i} style={{ background: i < step ? STEP_BAR[i] : RAIL }} />
      ))}
    </span>
  );
}

export default function DebtsPage() {
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="title">Задължения</span>
          <span className="divider" />
          <span className="meta">31 длъжника в 12 входа · общо €41.806,20 · лихва €1.284,40</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <button type="button" className="chip">Генерирай покани</button>
          <button type="button" className="chip active">Следваща стъпка (4)</button>
        </div>
      </div>

      <div style={{ padding: "16px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 14, font: "400 12px/1 'IBM Plex Sans'", color: "#5C605E" }}>
          <span style={{ font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase", color: "#6B6F6C" }}>Стълбица</span>
          {LEGEND.map((l) => (
            <span key={l.t} style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <span style={{ width: 26, height: 5, background: l.c }} />
              {l.t}
            </span>
          ))}
          <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>чл. 38, ал. 2 ЗУЕС · чл. 410 ГПК</span>
        </div>
        <span style={{ font: "400 12px/1 'IBM Plex Sans'", color: "#6B6F6C" }}>
          Сортирано по <span style={{ color: "#17191A", fontWeight: 500 }}>Дължимо ↓</span>
        </span>
      </div>

      <div className="pf-card" style={{ margin: "14px 24px 24px" }}>
        <div className="dt-grid pf-head">
          <div>Обект</div>
          <div>Длъжник</div>
          <div className="num">Дължимо</div>
          <div className="num">Лихва</div>
          <div className="num">Най-стар дълг</div>
          <div>Стъпка</div>
          <div>Следващо действие</div>
        </div>

        {GROUPS.map((g, gi) => (
          <div key={g.name}>
            <div className={`dt-group${gi > 0 ? " mid" : ""}`}>
              <span>
                {g.name} <span className="gnote">{g.note}</span>
              </span>
              <span className="gtotal">{g.total}</span>
            </div>
            {g.debtors.map((d) => (
              <div key={g.name + d.obj + d.debtor} className="dt-grid pf-row">
                <div style={{ fontWeight: 500 }}>{d.obj}</div>
                <div className="ellipsis">{d.debtor}</div>
                <div className="num" style={{ fontWeight: 500 }}>{d.amount}</div>
                <div className="num">{d.interest}</div>
                <div className="num" style={{ color: toneColor[d.oldestTone] }}>{d.oldest}</div>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <Ladder step={d.step} />
                  <span className="step-label" style={{ color: STEP_LABEL[d.step - 1] }}>{d.stepLabel}</span>
                </div>
                <div className="action">
                  {d.blocked && <span style={{ color: "#8E2318", fontWeight: 500 }}>{d.blocked} </span>}
                  {d.action}
                </div>
              </div>
            ))}
          </div>
        ))}

        <div className="dt-grid pf-foot">
          <div>Общо</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>31 длъжника</div>
          <div className="num">€41.806,20</div>
          <div className="num">€1.284,40</div>
          <div />
          <div style={{ color: "#6B6F6C", fontWeight: 400, fontSize: 11.5 }}>1 · 14&nbsp;&nbsp; 2 · 9&nbsp;&nbsp; 3 · 5&nbsp;&nbsp; 4 · 3</div>
          <div />
        </div>
      </div>
    </>
  );
}
