import type { CSSProperties } from "react";
import Link from "next/link";
import HeroVideo from "./HeroVideo";
import LandingNav from "./LandingNav";
import Faq from "./Faq";
import DemoForm from "./DemoForm";

const section: CSSProperties = { maxWidth: 1040, margin: "0 auto", padding: "clamp(88px,10vw,136px) 24px 0", boxSizing: "border-box" };
const kicker: CSSProperties = { font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.2em", color: "#5C605E" };
const h2: CSSProperties = { margin: "14px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: "clamp(28px,3vw,40px)", lineHeight: 1.15, letterSpacing: "-0.025em", textWrap: "balance" as CSSProperties["textWrap"] };
const lead: CSSProperties = { margin: "16px 0 0", font: "400 15px/1.65 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" as CSSProperties["textWrap"] };
const h3feat: CSSProperties = { margin: "14px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: "clamp(24px,2.4vw,30px)", lineHeight: 1.2, letterSpacing: "-0.02em" };
const card: CSSProperties = { flex: "1.45 1 440px", minWidth: 0, background: "#FFFFFF", border: "1px solid #DEDDD9", borderRadius: 6, overflow: "hidden", boxShadow: "0 1px 2px rgba(23,25,26,.04),0 16px 40px rgba(23,25,26,.07)" };
const cardBar: CSSProperties = { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, height: 36, padding: "0 14px", borderBottom: "1px solid #DEDDD9", background: "#F7F6F3", whiteSpace: "nowrap" };
const mono: CSSProperties = { font: "400 11px/1 'IBM Plex Mono', monospace", color: "#5C605E" };
const li: CSSProperties = { padding: "12px 0", borderBottom: "1px solid #DEDDD9", font: "400 14px/1.5 'IBM Plex Sans'" };
const ul: CSSProperties = { listStyle: "none", margin: "24px 0 0", padding: 0, borderTop: "1px solid #DEDDD9" };

function Badge({ text, bg, color }: { text: string; bg: string; color: string }) {
  return <span style={{ display: "inline-flex", alignItems: "center", height: 22, padding: "0 8px", borderRadius: 4, background: bg, color, font: "500 11.5px/1 'IBM Plex Sans'" }}>{text}</span>;
}

const TRUST = [
  ["Законът е спецификацията", "Всяко изчисление води до номерирано правило и до член от ЗУЕС."],
  ["Историята не се пренаписва", "Начисления, решения и гласове се пазят с основанието и редакцията на закона към датата си."],
  ["Не държим вашите пари", "Плащанията отиват направо в сметката на входа. Платформата само ги нарежда."],
  ["Всеки вход е отделен", "Парите на един вход не могат да бъдат прехвърлени към друг."],
  ["Записите само се добавят", "Гласове, счетоводни записи и достъп до лични данни не се трият и се одитират."],
  ["Данните остават в ЕС", "Хостинг в ЕС, обработка по GDPR. Вход с е-ИД — планиран."],
];

export default function LandingPage() {
  return (
    <div style={{ minHeight: "100vh", background: "#E7E6E1", overflowX: "hidden", color: "#17191A" }}>
      <LandingNav />

      {/* ---- hero ---- */}
      <section id="top" style={{ position: "relative", display: "flex", flexDirection: "column", alignItems: "center", overflow: "hidden", minHeight: "min(100vh,max(720px,70vw))", boxSizing: "border-box" }}>
        <HeroVideo />
        <div style={{ position: "relative", zIndex: 10, display: "flex", flexDirection: "column", alignItems: "center", textAlign: "center", padding: "128px 24px 72px" }}>
          <h1 style={{ margin: 0, fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: "clamp(40px,6.6vw,92px)", lineHeight: 1.08, letterSpacing: "-0.035em", color: "#17191A" }}>Нито един<br />пропуснат срок.</h1>
          <p style={{ maxWidth: 430, margin: "28px 0 0", font: "300 16px/1.65 'IBM Plex Sans'", color: "#5C605E" }}>Платформа за професионални домоуправители — законови срокове, начисления и общи събрания, водени по вход, а не по сграда.</p>
          <a href="#demo" className="ln-solid" style={{ marginTop: 36, padding: "14px 30px", font: "500 15px/1 'IBM Plex Sans'", borderRadius: 10 }}>Заявете демо</a>
        </div>
        <div style={{ position: "relative", zIndex: 10, marginTop: "auto", width: "100%", maxWidth: 1040, padding: "0 24px", boxSizing: "border-box" }}>
          <div style={{ background: "rgba(247,246,243,0.92)", backdropFilter: "blur(8px)", WebkitBackdropFilter: "blur(8px)", border: "1px solid #DEDDD9", borderBottom: 0, padding: "clamp(32px,5vw,56px) clamp(20px,4vw,48px) 0", boxShadow: "0 -1px 24px rgba(23,25,26,0.06)" }}>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,340px),1fr))", gap: "clamp(20px,4vw,64px)" }}>
              <div>
                <div style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.2em", color: "#84887F" }}>Какво правим?</div>
                <h2 style={{ margin: "14px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: "clamp(24px,2.6vw,38px)", lineHeight: 1.15, letterSpacing: "-0.025em", color: "#17191A" }}>Срокове, пари и кворум<br />на едно място</h2>
              </div>
              <div style={{ display: "flex", alignItems: "flex-end" }}>
                <p style={{ margin: 0, font: "400 15px/1.65 'IBM Plex Sans'", color: "#5C605E" }}>Създадена за фирми, които управляват 40–80 входа. Всеки вход със собствен календар по ЗУЕС, собствена каса и фонд, собствено събрание — и нито едно евро, смесено с чужд вход.</p>
              </div>
            </div>
            <div style={{ marginTop: 36, height: 1, background: "#DEDDD9", width: "100%" }} />
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,240px),1fr))", gap: 12, marginTop: 20 }}>
              {[["01", "Календар по ЗУЕС", "#f01"], ["02", "Начисления и каса", "#f02"], ["03", "Общи събрания", "#f03"]].map(([n, label, href]) => (
                <a key={n} href={href} className="feature-card">
                  <span style={{ font: "400 14px/1 'IBM Plex Sans'", whiteSpace: "nowrap" }}>
                    <span style={{ color: "#9A9E96", fontVariantNumeric: "tabular-nums" }}>{n}</span>
                    <span style={{ margin: "0 8px", color: "#B6B9B2" }}>/</span>
                    <span style={{ fontWeight: 500 }}>{label}</span>
                  </span>
                  <svg className="arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" style={{ flex: "none" }}><path d="M5 12h14" /><path d="m13 6 6 6-6 6" /></svg>
                </a>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* ---- platform ---- */}
      <section id="platform" style={{ position: "relative", zIndex: 10, maxWidth: 1040, margin: "0 auto", padding: "0 24px", boxSizing: "border-box", scrollMarginTop: 68 }}>
        <div style={{ background: "#F7F6F3", border: "1px solid #DEDDD9", borderTop: 0, padding: "clamp(56px,7vw,96px) clamp(20px,4vw,48px) clamp(56px,7vw,88px)", display: "flex", flexDirection: "column", gap: "clamp(64px,8vw,112px)" }}>
          <div style={{ maxWidth: 560 }}>
            <div style={{ ...kicker, color: "#5C605E" }}>Платформа</div>
            <h2 style={h2}>Три дела, които не търпят грешка</h2>
            <p style={lead}>Изгледите по-долу са от конзолата, с която работи управителят — без прерисуване за рекламата.</p>
          </div>

          {/* f01 — calendar */}
          <div id="f01" style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "clamp(32px,5vw,64px)", scrollMarginTop: 96 }}>
            <div style={{ flex: "1 1 300px", minWidth: 0 }}>
              <div style={{ font: "500 12px/1 'IBM Plex Mono', monospace", color: "#14584A", fontVariantNumeric: "tabular-nums" }}>01</div>
              <h3 style={h3feat}>Календар по ЗУЕС</h3>
              <p style={{ margin: "14px 0 0", font: "400 15px/1.65 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>Всеки законов срок на всеки вход, изведен от датите на самия вход — последното събрание, избора на управител, застраховката.</p>
              <ul style={ul}>
                <li style={li}>Всеки ред цитира члена, от който следва.</li>
                <li style={li}>Просрочено, наближаващо и в срок — различими от пръв поглед.</li>
                <li style={li}>Портфейлът подрежда входовете по най-близкия риск.</li>
              </ul>
            </div>
            <div style={card}>
              <div style={cardBar}>
                <span style={{ font: "500 12px/1 'IBM Plex Sans'", color: "#17191A" }}>бл. 214, вх. Б</span>
                <span style={mono}>Вход / Календар</span>
              </div>
              <div style={{ minWidth: 620 }}>
                <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 112px 84px 100px", alignItems: "center", height: 30, padding: "0 14px", borderBottom: "1px solid #DEDDD9", font: "500 11px/1 'IBM Plex Sans'", color: "#5C605E", textTransform: "uppercase", letterSpacing: "0.06em" }}>
                  <span>Задължение</span><span>Основание</span><span>Срок</span><span>Състояние</span>
                </div>
                {[
                  ["Отчет на управителя пред ОС", "ЗУЕС чл. —", "30.09.2026", "Просрочено", "#F5E1DE", "#93291F"],
                  ["Свикване на редовно общо събрание", "ЗУЕС чл. —", "14.10.2026", "След 21 дни", "#F6EBD6", "#7F4F0B"],
                  ["Застраховка на общите части", "Решение на ОС", "04.11.2026", "След 42 дни", "#F6EBD6", "#7F4F0B"],
                  ["Вноска във фонд „Ремонт и обновяване“", "ЗУЕС чл. —", "31.10.2026", "В срок", "#EDECE8", "#3D413E"],
                  ["Обновяване на книгата на ЕС", "ЗУЕС чл. —", "15.12.2026", "В срок", "#EDECE8", "#3D413E"],
                ].map(([task, basis, due, st, bg, col], i, a) => (
                  <div key={task} style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 112px 84px 100px", alignItems: "center", height: 36, padding: "0 14px", borderBottom: i < a.length - 1 ? "1px solid #EDECE8" : 0, font: "400 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap" }}>
                    <span style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{task}</span>
                    <span style={{ font: "400 12px/1 'IBM Plex Mono', monospace", color: "#5C605E" }}>{basis}</span>
                    <span style={{ fontVariantNumeric: "tabular-nums" }}>{due}</span>
                    <span><Badge text={st} bg={bg} color={col} /></span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* f02 — charges & cash (reversed) */}
          <div id="f02" style={{ display: "flex", flexDirection: "row-reverse", flexWrap: "wrap", alignItems: "center", gap: "clamp(32px,5vw,64px)", scrollMarginTop: 96 }}>
            <div style={{ flex: "1 1 300px", minWidth: 0 }}>
              <div style={{ font: "500 12px/1 'IBM Plex Mono', monospace", color: "#14584A", fontVariantNumeric: "tabular-nums" }}>02</div>
              <h3 style={h3feat}>Начисления и каса</h3>
              <p style={{ margin: "14px 0 0", font: "400 15px/1.65 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>Месечното начисление се пуска за целия вход наведнъж, а основата на всяка сума остава видима до обекта.</p>
              <ul style={ul}>
                <li style={li}>Двустранно счетоводство — всяко движение има две страни.</li>
                <li style={li}>Фондът е отделна сметка, със собствен IBAN.</li>
                <li style={li}>„Налично“ не е „салдо“: задържаните суми се показват отделно.</li>
              </ul>
            </div>
            <div style={card}>
              <div style={cardBar}>
                <span style={{ font: "500 12px/1 'IBM Plex Sans'", color: "#17191A" }}>бл. 214, вх. Б · октомври 2026</span>
                <span style={mono}>Начисления</span>
              </div>
              <div style={{ minWidth: 540 }}>
                <div style={{ display: "grid", gridTemplateColumns: "64px minmax(0,1fr) 96px", alignItems: "center", height: 30, padding: "0 14px", borderBottom: "1px solid #DEDDD9", font: "500 11px/1 'IBM Plex Sans'", color: "#5C605E", textTransform: "uppercase", letterSpacing: "0.06em" }}>
                  <span>Обект</span><span>Основа</span><span style={{ textAlign: "right" }}>Сума</span>
                </div>
                {[
                  ["Ап. 7", "ид.ч. 3,112% × €1.234,00 · 3 живущи", "€38,40"],
                  ["Ап. 8", "ид.ч. 2,540% × €1.234,00 · 1 живущ", "€31,34"],
                  ["Ап. 9", "ид.ч. 4,006% × €1.234,00 · 4 живущи", "€49,43"],
                ].map(([obj, base, sum], i) => (
                  <div key={obj} style={{ display: "grid", gridTemplateColumns: "64px minmax(0,1fr) 96px", alignItems: "center", height: 36, padding: "0 14px", borderBottom: i < 2 ? "1px solid #EDECE8" : "1px solid #DEDDD9", font: "400 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
                    <span>{obj}</span><span style={{ color: "#5C605E" }}>{base}</span><span style={{ textAlign: "right" }}>{sum}</span>
                  </div>
                ))}
                <div style={{ display: "grid", gridTemplateColumns: "repeat(2,minmax(0,1fr))" }}>
                  {[
                    ["Каса", "BG00 XXXX 0000 0000 0000 01", "€12.480,00", "€9.215,40"],
                    ["Фонд „Ремонт и обновяване“", "BG00 XXXX 0000 0000 0000 02", "€28.906,15", "€28.906,15"],
                  ].map(([title, iban, bal, avail], i) => (
                    <div key={title} style={{ padding: 14, borderRight: i === 0 ? "1px solid #EDECE8" : 0 }}>
                      <div style={{ font: "500 12px/1 'IBM Plex Sans'" }}>{title}</div>
                      <div style={{ marginTop: 6, font: "400 11px/1 'IBM Plex Mono', monospace", color: "#5C605E" }}>{iban}</div>
                      <div style={{ display: "flex", justifyContent: "space-between", marginTop: 12, font: "400 12px/1 'IBM Plex Sans'", color: "#5C605E" }}><span>Салдо</span><span style={{ color: "#17191A", fontVariantNumeric: "tabular-nums" }}>{bal}</span></div>
                      <div style={{ display: "flex", justifyContent: "space-between", marginTop: 8, font: "500 12px/1 'IBM Plex Sans'" }}><span>Налично</span><span style={{ fontVariantNumeric: "tabular-nums" }}>{avail}</span></div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/* f03 — assembly */}
          <div id="f03" style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "clamp(32px,5vw,64px)", scrollMarginTop: 96 }}>
            <div style={{ flex: "1 1 300px", minWidth: 0 }}>
              <div style={{ font: "500 12px/1 'IBM Plex Mono', monospace", color: "#14584A", fontVariantNumeric: "tabular-nums" }}>03</div>
              <h3 style={h3feat}>Общи събрания</h3>
              <p style={{ margin: "14px 0 0", font: "400 15px/1.65 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>Едно събрание, до три сесии при понижаващ се кворум. Гласовете се теглят по идеални части, пълномощните се проверяват на входа.</p>
              <ul style={ul}>
                <li style={li}>Знаменателят винаги е на екрана — от какво е процентът.</li>
                <li style={li}>Непредставените обекти са отделен ред, не изчезват.</li>
                <li style={li}>Протоколът се съставя от записаните гласове.</li>
              </ul>
            </div>
            <div style={card}>
              <div style={cardBar}>
                <span style={{ font: "500 12px/1 'IBM Plex Sans'", color: "#17191A" }}>ОС 12.10.2026 · т. 3 Ремонт на покрива</span>
                <span style={mono}>Общо събрание</span>
              </div>
              <div style={{ minWidth: 540 }}>
                <div style={{ display: "grid", gridTemplateColumns: "repeat(3,minmax(0,1fr))", borderBottom: "1px solid #DEDDD9" }}>
                  <div style={{ padding: "12px 14px", borderRight: "1px solid #EDECE8" }}><div style={{ font: "500 11px/1 'IBM Plex Sans'", color: "#5C605E", textTransform: "uppercase", letterSpacing: "0.06em" }}>Сесия 1</div><div style={{ marginTop: 8, font: "400 12px/1.4 'IBM Plex Sans'", fontVariantNumeric: "tabular-nums" }}>Присъстват 41,20%<br /><span style={{ color: "#93291F" }}>Под изискуемите —%</span></div></div>
                  <div style={{ padding: "12px 14px", borderRight: "1px solid #EDECE8", background: "#F7F6F3", boxShadow: "inset 0 2px 0 #14584A" }}><div style={{ font: "500 11px/1 'IBM Plex Sans'", color: "#14584A", textTransform: "uppercase", letterSpacing: "0.06em" }}>Сесия 2 · тече</div><div style={{ marginTop: 8, font: "400 12px/1.4 'IBM Plex Sans'", fontVariantNumeric: "tabular-nums" }}>Присъстват 76,57%<br /><span style={{ color: "#3D413E" }}>Кворум при —%</span></div></div>
                  <div style={{ padding: "12px 14px" }}><div style={{ font: "500 11px/1 'IBM Plex Sans'", color: "#5C605E", textTransform: "uppercase", letterSpacing: "0.06em" }}>Сесия 3</div><div style={{ marginTop: 8, font: "400 12px/1.4 'IBM Plex Sans'", color: "#5C605E" }}>Не е нужна</div></div>
                </div>
                <div style={{ padding: "14px 14px 4px" }}>
                  <div style={{ display: "flex", height: 10, borderRadius: 2, overflow: "hidden", background: "#EDECE8" }}>
                    <div style={{ width: "58.44%", background: "#14584A" }} /><div style={{ width: "12.10%", background: "#17191A" }} /><div style={{ width: "6.03%", background: "#9A9E96" }} />
                  </div>
                </div>
                {[
                  ["#14584A", "За", "14 обекта", "58,44%", "#17191A"],
                  ["#17191A", "Против", "3 обекта", "12,10%", "#17191A"],
                  ["#9A9E96", "Въздържал се", "1 обект", "6,03%", "#17191A"],
                ].map(([dot, label, n, pct]) => (
                  <div key={label} style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 88px 88px", alignItems: "center", height: 32, padding: "0 14px", borderBottom: "1px solid #EDECE8", font: "400 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
                    <span style={{ display: "flex", alignItems: "center", gap: 8 }}><span style={{ width: 8, height: 8, background: dot }} />{label}</span>
                    <span style={{ textAlign: "right", color: "#5C605E" }}>{n}</span><span style={{ textAlign: "right" }}>{pct}</span>
                  </div>
                ))}
                <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 88px 88px", alignItems: "center", height: 32, padding: "0 14px", borderBottom: "1px solid #DEDDD9", font: "400 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums", color: "#5C605E" }}>
                  <span style={{ display: "flex", alignItems: "center", gap: 8 }}><span style={{ width: 8, height: 8, background: "#EDECE8", border: "1px solid #CFCEC9", boxSizing: "border-box" }} />Непредставени</span>
                  <span style={{ textAlign: "right" }}>5 обекта</span><span style={{ textAlign: "right" }}>23,43%</span>
                </div>
                <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 88px 88px", alignItems: "center", height: 34, padding: "0 14px", font: "500 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
                  <span>Знаменател — идеални части на входа</span><span style={{ textAlign: "right", color: "#5C605E", fontWeight: 400 }}>23 обекта</span><span style={{ textAlign: "right" }}>100,00%</span>
                </div>
              </div>
            </div>
          </div>

          {/* f04 + f05 — debts & compliance */}
          <div id="f04" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,380px),1fr))", gap: 24, paddingTop: "clamp(40px,5vw,56px)", borderTop: "1px solid #DEDDD9", scrollMarginTop: 96 }}>
            <div style={{ display: "flex", flexDirection: "column", gap: 20, minWidth: 0 }}>
              <div>
                <div style={{ font: "500 12px/1 'IBM Plex Mono', monospace", color: "#14584A" }}>04</div>
                <h3 style={{ margin: "12px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: 22, lineHeight: 1.25, letterSpacing: "-0.015em" }}>Задължения</h3>
                <p style={{ margin: "10px 0 0", font: "400 14px/1.6 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>Стълба от напомняне до заповед за изпълнение. Всяко стъпало с дата, документ и сума към момента.</p>
              </div>
              <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", borderRadius: 6, overflow: "hidden" }}>
                {[
                  ["1", "Напомняне в приложението", "9 обекта", "", "#5C605E"],
                  ["2", "Писмена покана", "4 обекта", "", "#5C605E"],
                  ["3", "Решение на УС за съдебен ред", "2 обекта", "#FBF4E8", "#7F4F0B"],
                  ["4", "Заявление за заповед за изпълнение", "1 обект", "#FAEEEC", "#93291F"],
                ].map(([n, label, count, bg, col], i) => (
                  <div key={n} style={{ display: "grid", gridTemplateColumns: "22px minmax(0,1fr) 60px", alignItems: "center", gap: 10, height: 36, padding: "0 14px", borderBottom: i < 3 ? "1px solid #EDECE8" : 0, font: "400 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums", background: bg || undefined }}>
                    <span style={{ font: "400 11px/1 'IBM Plex Mono', monospace", color: col }}>{n}</span>
                    <span>{label}</span>
                    <span style={{ textAlign: "right", color: col }}>{count}</span>
                  </div>
                ))}
              </div>
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 20, minWidth: 0 }}>
              <div>
                <div style={{ font: "500 12px/1 'IBM Plex Mono', monospace", color: "#14584A" }}>05</div>
                <h3 style={{ margin: "12px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: 22, lineHeight: 1.25, letterSpacing: "-0.015em" }}>Съответствие на фирмата</h3>
                <p style={{ margin: "10px 0 0", font: "400 14px/1.6 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>Регистрацията, застраховката и подадените документи на самата фирма — със срок до изтичане.</p>
              </div>
              <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", borderRadius: 6, overflow: "hidden" }}>
                {[
                  ["Вписване в регистъра на общината", "Валидно", "#EDECE8", "#3D413E"],
                  ["Застраховка „Професионална отговорност“", "До 04.11", "#F6EBD6", "#7F4F0B"],
                  ["Договори за управление — 62 входа", "1 изтекъл", "#F5E1DE", "#93291F"],
                  ["Годишни отчети към ЕС", "62 / 62", "#EDECE8", "#3D413E"],
                ].map(([label, st, bg, col], i) => (
                  <div key={label} style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 92px", alignItems: "center", height: 36, padding: "0 14px", borderBottom: i < 3 ? "1px solid #EDECE8" : 0, font: "400 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap" }}>
                    <span style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{label}</span>
                    <span style={{ textAlign: "right" }}><Badge text={st} bg={bg} color={col} /></span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ---- who ---- */}
      <section id="who" style={{ ...section, scrollMarginTop: 0 }}>
        <div style={{ maxWidth: 560 }}>
          <div style={kicker}>За кого</div>
          <h2 style={h2}>Един продукт, три роли</h2>
          <p style={lead}>Фирмата, съседът-доброволец и собственикът виждат един и същ вход — всеки от своя ъгъл.</p>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,280px),1fr))", gap: 12, marginTop: 48 }}>
          {[
            { tag: "40–80 входа", title: "Професионален домоуправител", items: ["Подрежда всички входове по най-близкия срок и по просрочените суми.", "Пуска месечните начисления за всички входове и издава фактурите.", "Води регистъра, застраховката и договорите на самата фирма."], cta: { label: "Заявете демо", href: "#demo", solid: true } },
            { tag: "1 вход", title: "Домоуправител на един вход", items: ["Води ви стъпка по стъпка през поканата, дневния ред и протокола.", "Разпределя разходите по приетия начин и показва как е сметнато.", "Изготвя годишния отчет за съседите от записаните движения."], cta: { label: "Започнете безплатно", href: "/portfolio", solid: false } },
          ].map((r) => (
            <div key={r.title} style={{ display: "flex", flexDirection: "column", background: "#EDECE8", padding: 28, minWidth: 0 }}>
              <div style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.16em", color: "#5C605E" }}>{r.tag}</div>
              <h3 style={{ margin: "12px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: 22, lineHeight: 1.25, letterSpacing: "-0.015em" }}>{r.title}</h3>
              <ol style={ul}>
                {r.items.map((it, i) => (
                  <li key={i} style={{ display: "grid", gridTemplateColumns: "28px minmax(0,1fr)", padding: "12px 0", borderBottom: "1px solid #DEDDD9", font: "400 14px/1.5 'IBM Plex Sans'" }}>
                    <span style={{ font: "400 12px/1.7 'IBM Plex Mono', monospace", color: "#5C605E" }}>0{i + 1}</span>{it}
                  </li>
                ))}
              </ol>
              {r.cta.solid
                ? <a href={r.cta.href} className="ln-solid" style={{ display: "inline-flex", alignSelf: "flex-start", alignItems: "center", height: 44, padding: "0 20px", marginTop: 32, font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8, whiteSpace: "nowrap" }}>{r.cta.label}</a>
                : <Link href={r.cta.href} className="ln-outline" style={{ display: "inline-flex", alignSelf: "flex-start", alignItems: "center", height: 44, padding: "0 20px", marginTop: 32, font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8, whiteSpace: "nowrap" }}>{r.cta.label}</Link>}
            </div>
          ))}
          <div id="who-residents" style={{ display: "flex", flexDirection: "column", background: "#EDECE8", padding: 28, minWidth: 0 }}>
            <div style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.16em", color: "#5C605E" }}>Мобилно приложение</div>
            <h3 style={{ margin: "12px 0 0", fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: 22, lineHeight: 1.25, letterSpacing: "-0.015em" }}>Собственици и обитатели</h3>
            <ol style={ul}>
              {["Показва колко дължите и за какво — ред по ред, с основанието.", "Гласувате или упълномощавате за общото събрание.", "Четете протоколите и отчета на касата, когато са публикувани."].map((it, i) => (
                <li key={i} style={{ display: "grid", gridTemplateColumns: "28px minmax(0,1fr)", padding: "12px 0", borderBottom: "1px solid #DEDDD9", font: "400 14px/1.5 'IBM Plex Sans'" }}>
                  <span style={{ font: "400 12px/1.7 'IBM Plex Mono', monospace", color: "#5C605E" }}>0{i + 1}</span>{it}
                </li>
              ))}
            </ol>
            <div style={{ marginTop: 32, font: "500 13px/1 'IBM Plex Sans'" }}>Мобилното приложение</div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginTop: 10 }}>
              {["App Store", "Google Play"].map((s) => (
                <span key={s} style={{ display: "inline-flex", flexDirection: "column", justifyContent: "center", gap: 3, height: 44, padding: "0 14px", border: "1px dashed #9A9E96", borderRadius: 8, boxSizing: "border-box" }}>
                  <span style={{ font: "500 13px/1 'IBM Plex Sans'" }}>{s}</span><span style={{ font: "400 10px/1 'IBM Plex Mono', monospace", color: "#5C605E" }}>значка · предстои</span>
                </span>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* ---- start ---- */}
      <section id="start" style={section}>
        <div style={{ maxWidth: 560 }}>
          <div style={kicker}>Как започвате</div>
          <h2 style={h2}>Първо сверяваме, после таксуваме</h2>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,260px),1fr))", gap: "clamp(24px,4vw,48px)", marginTop: 48 }}>
          {[
            ["Стъпка 1", "Донесете таблицата си", "Във вида, в който я водите — с каквито и да е колони. Ние съпоставяме полетата; вие не преформатирате нищо."],
            ["Стъпка 2", "Възпроизвеждаме последния ви лист", "Изчисляваме последния месец наново и го сверяваме с вашия до цент. Реално таксуване започва едва след съвпадение."],
            ["Стъпка 3", "Пускате месеца в конзолата", "Начисления, плащания и срокове за всички входове — от един екран, със следа за всяка стъпка."],
          ].map(([step, title, body]) => (
            <div key={step} style={{ paddingTop: 20, borderTop: "1px solid #17191A" }}>
              <div style={{ font: "500 12px/1 'IBM Plex Mono', monospace", color: "#14584A" }}>{step}</div>
              <h3 style={{ margin: "14px 0 0", font: "500 17px/1.35 'IBM Plex Sans'" }}>{title}</h3>
              <p style={{ margin: "10px 0 0", font: "400 14px/1.6 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>{body}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ---- trust ---- */}
      <section id="trust" style={section}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "clamp(32px,5vw,64px)", alignItems: "flex-end" }}>
          <div style={{ flex: "1 1 300px", minWidth: 0, maxWidth: 460 }}>
            <div style={kicker}>Защо можете да ни се доверите</div>
            <h2 style={h2}>Всяко число има основание</h2>
            <p style={lead}>Отворете който и да е ред и ще видите правилото, члена и редакцията на закона, по които е сметнат.</p>
          </div>
          <div style={{ flex: "1.2 1 400px", minWidth: 0, background: "#FFFFFF", border: "1px solid #DEDDD9", borderRadius: 6, overflow: "hidden", boxShadow: "0 1px 2px rgba(23,25,26,.04),0 16px 40px rgba(23,25,26,.07)" }}>
            <div style={{ display: "grid", gridTemplateColumns: "18px minmax(0,1fr) auto", alignItems: "center", gap: 10, height: 40, padding: "0 14px", borderBottom: "1px solid #DEDDD9", font: "500 13px/1 'IBM Plex Sans'", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#5C605E" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
              <span style={{ overflow: "hidden", textOverflow: "ellipsis" }}>Ап. 7 · Поддръжка на общите части · 10.2026</span>
              <span>€38,40</span>
            </div>
            <div style={{ padding: "6px 14px 14px 42px", background: "#F7F6F3" }}>
              {[
                ["Правило", "НЧ-04 · разход по идеални части"],
                ["Основание", "ЗУЕС чл. —, ал. —"],
                ["Редакция на закона", "ДВ бр. —/—, в сила от —"],
                ["Изчисление", "3,112% × €1.234,00 = €38,40"],
              ].map(([k, v], i, a) => (
                <div key={k} style={{ display: "grid", gridTemplateColumns: "128px minmax(0,1fr)", alignItems: "baseline", gap: 12, padding: "8px 0", borderBottom: i < a.length - 1 ? "1px solid #EDECE8" : 0, font: "400 12.5px/1.45 'IBM Plex Sans'" }}>
                  <span style={{ color: "#5C605E" }}>{k}</span><span style={{ font: "400 12px/1.45 'IBM Plex Mono', monospace", fontVariantNumeric: "tabular-nums" }}>{v}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,290px),1fr))", columnGap: "clamp(24px,4vw,48px)", marginTop: 56 }}>
          {TRUST.map(([t, b]) => (
            <div key={t} style={{ padding: "20px 0 28px", borderTop: "1px solid #DEDDD9" }}>
              <h3 style={{ margin: 0, font: "500 15px/1.4 'IBM Plex Sans'" }}>{t}</h3>
              <p style={{ margin: "8px 0 0", font: "400 14px/1.6 'IBM Plex Sans'", color: "#5C605E", textWrap: "pretty" }}>{b}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ---- pricing ---- */}
      <section id="pricing" style={section}>
        <div style={{ maxWidth: 560 }}>
          <div style={kicker}>Цени</div>
          <h2 style={h2}>На вход, на месец</h2>
          <p style={lead}>Плащате за входовете, които управлявате. Живущите ползват приложението без такса.</p>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,290px),1fr))", gap: 12, marginTop: 48, alignItems: "stretch" }}>
          {[
            { title: "Един вход", note: "За домоуправител-доброволец", price: "€—", unit: "/ вход / месец", items: [["Календар по ЗУЕС", "Вход"], ["Начисления с основание", "Начисления"], ["Каса и фонд, отделни IBAN", "Каса и фонд"], ["Събрание с помощник", "Общо събрание"], ["Приложение за живущите", "Мобилно"]], cta: { label: "Започнете безплатно", href: "/portfolio", solid: false }, bg: "#F7F6F3", border: "#DEDDD9" },
            { title: "Фирма", note: "За професионален домоуправител", price: "€—", unit: "/ вход / месец", items: [["Всичко от „Един вход“", "—"], ["Всички входове по риск", "Портфейл"], ["Стълба на задълженията", "Задължения"], ["Регистър на фирмата", "Съответствие"], ["Фактуриране и повече потребители", "Начисления"]], cta: { label: "Заявете демо", href: "#demo", solid: true }, bg: "#FFFFFF", border: "#14584A" },
            { title: "Индивидуална оферта", note: "За портфейли над — входа", price: "По договаряне", unit: "", items: [["Всичко от „Фирма“", "—"], ["Пренасяне на цялата история", "Импорт"], ["Сверка на няколко месеца назад", "Начисления"], ["Условия по договор", "—"]], cta: { label: "Свържете се с нас", href: "#demo", solid: false }, bg: "#F7F6F3", border: "#DEDDD9" },
          ].map((p) => (
            <div key={p.title} style={{ display: "flex", flexDirection: "column", background: p.bg, border: `1px solid ${p.border}`, borderRadius: 8, padding: 28, minWidth: 0 }}>
              <h3 style={{ margin: 0, font: "500 16px/1.3 'IBM Plex Sans'" }}>{p.title}</h3>
              <p style={{ margin: "6px 0 0", font: "400 13px/1.5 'IBM Plex Sans'", color: "#5C605E" }}>{p.note}</p>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginTop: 24 }}>
                <span style={{ fontFamily: "Literata, Georgia, serif", fontSize: 40, lineHeight: 1, letterSpacing: "-0.02em", fontVariantNumeric: "tabular-nums" }}>{p.price}</span>
                {p.unit && <span style={{ font: "400 13px/1 'IBM Plex Sans'", color: "#5C605E" }}>{p.unit}</span>}
              </div>
              <ul style={{ listStyle: "none", margin: "24px 0 28px", padding: 0, borderTop: "1px solid #DEDDD9" }}>
                {p.items.map(([f, tag]) => (
                  <li key={f} style={{ display: "flex", justifyContent: "space-between", gap: 12, padding: "10px 0", borderBottom: "1px solid #DEDDD9", font: "400 14px/1.4 'IBM Plex Sans'" }}>
                    <span>{f}</span><span style={{ font: "400 11px/1.6 'IBM Plex Mono', monospace", color: "#5C605E", whiteSpace: "nowrap" }}>{tag}</span>
                  </li>
                ))}
              </ul>
              {p.cta.solid
                ? <a href={p.cta.href} className="ln-solid" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: 44, marginTop: "auto", font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8 }}>{p.cta.label}</a>
                : (p.cta.href.startsWith("/")
                  ? <Link href={p.cta.href} className="ln-outline" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: 44, marginTop: "auto", font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8 }}>{p.cta.label}</Link>
                  : <a href={p.cta.href} className="ln-outline" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: 44, marginTop: "auto", font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8 }}>{p.cta.label}</a>)}
            </div>
          ))}
        </div>
        <p style={{ margin: "20px 0 0", font: "400 13px/1.5 'IBM Plex Sans'", color: "#5C605E" }}>Цените се публикуват преди старта. Сумите са без ДДС.</p>
      </section>

      {/* ---- company ---- */}
      <section id="company" style={section}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,340px),1fr))", gap: "clamp(32px,5vw,64px)" }}>
          <div>
            <div style={kicker}>Фирмата</div>
            <h2 style={h2}>Правим го, защото срокът не чака</h2>
            <p style={lead}>Екип от счетоводители, юристи и разработчици, работили с етажна собственост. Видяхме как пропуснат срок или объркан фонд струват на хората повече от таксата на управителя — и написахме правилата така, че да се проверяват.</p>
            <p style={{ margin: "16px 0 0", font: "400 15px/1.65 'IBM Plex Sans'", color: "#17191A" }}>Пишете ни на <span style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 14 }}>[имейл — предстои]</span> или <a href="#demo" style={{ color: "#14584A", borderBottom: "1px solid #14584A" }}>заявете демо</a>.</p>
          </div>
          <dl style={{ margin: 0, alignSelf: "end", borderTop: "1px solid #DEDDD9" }}>
            {[["Наименование", "„—“ ЕООД", false], ["ЕИК", "—", true], ["Седалище и адрес", "гр. —, ул. — №—", false], ["Управител", "—", false], ["Телефон", "+359 — — —", true]].map(([k, v, m]) => (
              <div key={k as string} style={{ display: "grid", gridTemplateColumns: "140px minmax(0,1fr)", gap: 12, padding: "12px 0", borderBottom: "1px solid #DEDDD9", font: "400 14px/1.5 'IBM Plex Sans'" }}>
                <dt style={{ color: "#5C605E" }}>{k}</dt>
                <dd style={{ margin: 0, ...(m ? { fontFamily: "'IBM Plex Mono', monospace", fontSize: 13 } : {}) }}>{v}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      {/* ---- faq ---- */}
      <section id="faq" style={section}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "clamp(24px,5vw,64px)", alignItems: "flex-start" }}>
          <div style={{ flex: "1 1 260px", minWidth: 0 }}>
            <div style={kicker}>Въпроси</div>
            <h2 style={h2}>Често задавани въпроси</h2>
          </div>
          <Faq />
        </div>
      </section>

      {/* ---- demo ---- */}
      <section id="demo" style={{ ...section, scrollMarginTop: 68 }}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "clamp(32px,5vw,64px)", background: "#F7F6F3", border: "1px solid #DEDDD9", padding: "clamp(28px,5vw,56px) clamp(20px,4vw,48px)" }}>
          <div style={{ flex: "1 1 280px", minWidth: 0 }}>
            <div style={kicker}>Демо</div>
            <h2 style={h2}>Заявете демо</h2>
            <p style={lead}>Показваме конзолата върху ваш вход. Ако ни изпратите таблицата си предварително, сверяваме последния месец още на срещата.</p>
          </div>
          <DemoForm />
        </div>
      </section>

      {/* ---- footer ---- */}
      <footer style={{ maxWidth: 1040, margin: "clamp(88px,10vw,136px) auto 0", padding: "48px 24px 40px", boxSizing: "border-box", borderTop: "1px solid #DEDDD9" }}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,180px),1fr))", gap: 32 }}>
          <div>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" style={{ color: "#17191A", flex: "none" }}><path d="M3 3.5h18v3.4H3z" /><path d="M3 10.3h13.2v3.4H3z" /><path d="M3 17.1h8.4V20.5H3z" /></svg>
              <span style={{ font: "600 15px/1 'IBM Plex Sans'", letterSpacing: "-0.02em" }}>Етаж</span>
            </div>
            <p style={{ margin: "14px 0 0", font: "400 13px/1.55 'IBM Plex Sans'", color: "#5C605E" }}>Управление на етажна собственост по ЗУЕС, по вход.</p>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, font: "400 14px/1.3 'IBM Plex Sans'" }}>
            <span style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.16em", color: "#5C605E" }}>Сайт</span>
            {[["#platform", "Платформа"], ["#who", "За кого"], ["#pricing", "Цени"], ["#company", "Фирмата"], ["#faq", "Въпроси"]].map(([h, l]) => (
              <a key={h} href={h} className="ln-link">{l}</a>
            ))}
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, font: "400 14px/1.3 'IBM Plex Sans'" }}>
            <span style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.16em", color: "#5C605E" }}>Продукт</span>
            <Link href="/portfolio" className="ln-link">Вход в системата</Link>
            <a href="#who-residents" className="ln-link">Мобилно приложение</a>
            <a href="#demo" className="ln-link">Заявете демо</a>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, font: "400 14px/1.3 'IBM Plex Sans'" }}>
            <span style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.16em", color: "#5C605E" }}>Правна информация</span>
            {["Политика за поверителност", "Общи условия", "Бисквитки"].map((t) => (
              <span key={t} style={{ display: "flex", flexWrap: "wrap", alignItems: "baseline", gap: "4px 8px", color: "#5C605E" }}>{t}<span style={{ font: "400 10px/1 'IBM Plex Mono', monospace", whiteSpace: "nowrap" }}>предстои</span></span>
            ))}
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, font: "400 14px/1.3 'IBM Plex Sans'" }}>
            <span style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.16em", color: "#5C605E" }}>Контакт</span>
            <span style={{ font: "400 13px/1.3 'IBM Plex Mono', monospace" }}>[имейл — предстои]</span>
            <span style={{ font: "400 13px/1.3 'IBM Plex Mono', monospace" }}>+359 — — —</span>
          </div>
        </div>
        <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "space-between", gap: 12, marginTop: 48, paddingTop: 20, borderTop: "1px solid #DEDDD9", font: "400 12px/1.4 'IBM Plex Sans'", color: "#5C605E" }}>
          <span>© 2026 „—“ ЕООД. Всички права запазени.</span>
          <span style={{ fontFamily: "'IBM Plex Mono', monospace" }}>ЕИК —</span>
        </div>
      </footer>
    </div>
  );
}
