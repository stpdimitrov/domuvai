import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "Каса и фонд — Етаж" };

interface Account {
  title: string;
  badge: { text: string; green?: boolean };
  iban: string;
  balance: string;
  committed: string;
  committedLabel: string;
  available: string;
  note: string;
}

// The two accounts an entrance keeps — the operating cash (501) and the ring-fenced repair fund
// (502, чл. 50 ЗУЕС). Available = balance − committed.
const ACCOUNTS: Account[] = [
  {
    title: "Сметка 501 · Каса и разплащателна",
    badge: { text: "Оперативна" },
    iban: "BG18 UNCR 7000 1512 3456 78 · UniCredit Bulbank",
    balance: "€3.812,40",
    committed: "−€1.420,00",
    committedLabel: "Поети задължения",
    available: "€2.392,40",
    note: "Поети: Топлофикация €980,00 (пад. 30.09) · асансьорна поддръжка €440,00 (пад. 05.10)",
  },
  {
    title: "Сметка 502 · Фонд „Ремонт и обновяване“",
    badge: { text: "Отделна сметка · чл. 50 ЗУЕС", green: true },
    iban: "BG44 UNCR 7000 1512 9981 02 · UniCredit Bulbank",
    balance: "€1.240,50",
    committed: "−€600,00",
    committedLabel: "Поети по договор",
    available: "€640,50",
    note: "Поети: авансова вноска „Строй Дом“ ООД по т. 3 на ОС · средствата не могат да покриват оперативни разходи",
  },
];

interface Acct { text: string; green?: boolean; dim?: boolean }
interface JournalRow {
  date: string;
  doc: string;
  desc: string;
  descNote?: string;
  debit: Acct;
  credit: Acct;
  debitAmt: string;
  creditAmt: string;
  saldo: string;
  dim?: boolean;
  flagged?: boolean;
}

const JOURNAL: JournalRow[] = [
  { date: "01.09.26", doc: "НАЧ-2609", desc: "Начисление септември · 24 обекта", debit: { text: "411 Вземания от СО" }, credit: { text: "705 Приходи вноски" }, debitAmt: "€1.252,50", creditAmt: "€1.252,50", saldo: "€2.980,10" },
  { date: "03.09.26", doc: "БАНК-1187", desc: "Плащания от собственици · 14 превода", debit: { text: "501 Каса" }, credit: { text: "411 Вземания от СО" }, debitAmt: "€986,40", creditAmt: "€986,40", saldo: "€3.966,50" },
  { date: "05.09.26", doc: "ФРД-0441", desc: "Вноска фонд „Ремонт“ · превод към 502", debit: { text: "502 Фонд ремонт", green: true }, credit: { text: "501 Каса" }, debitAmt: "€600,00", creditAmt: "€600,00", saldo: "€3.366,50" },
  { date: "08.09.26", doc: "Ф-4412881", desc: "Ток общи части · „Електрохолд“ · август", debit: { text: "602 Разходи ток" }, credit: { text: "401 Доставчици" }, debitAmt: "€184,20", creditAmt: "€184,20", saldo: "€3.366,50" },
  { date: "09.09.26", doc: "БАНК-1194", desc: "Плащане на „Електрохолд“", debit: { text: "401 Доставчици" }, credit: { text: "501 Каса" }, debitAmt: "€184,20", creditAmt: "€184,20", saldo: "€3.182,30" },
  { date: "12.09.26", doc: "Ф-0000914", desc: "Асансьорна поддръжка · „Лифт Сервиз“ ООД", debit: { text: "602 Разходи поддръжка" }, credit: { text: "401 Доставчици" }, debitAmt: "€440,00", creditAmt: "€440,00", saldo: "€3.182,30" },
  { date: "15.09.26", doc: "БАНК-1203", desc: "Плащания от собственици · 9 превода", debit: { text: "501 Каса" }, credit: { text: "411 Вземания от СО" }, debitAmt: "€712,80", creditAmt: "€712,80", saldo: "€3.895,10" },
  { date: "17.09.26", doc: "КАС-0032", desc: "Материали за входно осветление · в брой", debit: { text: "602 Разходи материали" }, credit: { text: "501 Каса" }, debitAmt: "€82,70", creditAmt: "€82,70", saldo: "€3.812,40" },
  { date: "19.09.26", doc: "ДОГ-0007", desc: "Поето задължение по договор „Покрив 2026“", descNote: "(извънбалансово)", debit: { text: "— задбалансова", dim: true }, credit: { text: "502 Фонд · ангажимент", green: true }, debitAmt: "€600,00", creditAmt: "€600,00", saldo: "€3.812,40", dim: true, flagged: true },
];

const HEADERS: { label: string; num?: boolean }[] = [
  { label: "Дата" }, { label: "Документ" }, { label: "Описание" },
  { label: "Дебит сметка" }, { label: "Кредит сметка" },
  { label: "Дебит", num: true }, { label: "Кредит", num: true }, { label: "Салдо 501", num: true },
];

function acctColor(a: Acct): string {
  if (a.green) return "#14584A";
  if (a.dim) return "#6B6F6C";
  return "#3D413E";
}

export default function FundPage() {
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
            Каса и фонд · период <span style={{ fontVariantNumeric: "tabular-nums" }}>01.09 – 22.09.2026</span>
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <button type="button" className="chip">Банково извлечение</button>
          <button type="button" className="chip active">Нов документ</button>
        </div>
      </div>

      <div style={{ padding: "16px 24px 0", display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)", gap: 16 }}>
        {ACCOUNTS.map((a) => (
          <div key={a.title} className="acct-card">
            <div className="acct-head">
              <span className="acct-title">{a.title}</span>
              <span className={`badge${a.badge.green ? " green" : " calm"}`}>{a.badge.text}</span>
            </div>
            <div className="acct-iban">{a.iban}</div>
            <div className="acct-stats">
              <div>
                <div className="lbl">Салдо</div>
                <div className="big">{a.balance}</div>
              </div>
              <div>
                <div className="lbl">{a.committedLabel}</div>
                <div className="big" style={{ fontWeight: 400, color: "#8E2318" }}>{a.committed}</div>
              </div>
              <div>
                <div className="lbl">Разполагаемо</div>
                <div className="big" style={{ fontWeight: 600, color: "#14584A" }}>{a.available}</div>
              </div>
            </div>
            <div className="acct-note">{a.note}</div>
          </div>
        ))}
      </div>

      <div style={{ margin: "16px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div style={{ display: "flex", gap: 6 }}>
          <button type="button" className="chip active">Всички статии</button>
          <button type="button" className="chip">501 Каса</button>
          <button type="button" className="chip">502 Фонд</button>
          <button type="button" className="chip">Несторнирани</button>
        </div>
        <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", color: "#6B6F6C" }}>
          Дебит = Кредит за всяка статия · дневникът е заключен до 31.08.2026
        </span>
      </div>

      <div className="pf-card" style={{ margin: "12px 24px 24px" }}>
        <div className="jr-grid pf-head">
          {HEADERS.map((h) => (
            <div key={h.label} className={h.num ? "num" : ""}>{h.label}</div>
          ))}
        </div>

        {JOURNAL.map((r) => (
          <div key={r.doc} className={`jr-grid pf-row${r.flagged ? " flagged" : ""}`}>
            <div style={{ fontVariantNumeric: "tabular-nums" }}>{r.date}</div>
            <div className="mono">{r.doc}</div>
            <div className="ellipsis">
              {r.desc}
              {r.descNote && <span style={{ color: "#6B6F6C" }}> {r.descNote}</span>}
            </div>
            <div style={{ color: acctColor(r.debit) }}>{r.debit.text}</div>
            <div style={{ color: acctColor(r.credit) }}>{r.credit.text}</div>
            <div className="num" style={r.dim ? { color: "#6B6F6C" } : undefined}>{r.debitAmt}</div>
            <div className="num" style={r.dim ? { color: "#6B6F6C" } : undefined}>{r.creditAmt}</div>
            <div className="num" style={{ color: "#6B6F6C" }}>{r.saldo}</div>
          </div>
        ))}

        <div className="jr-grid" style={{ height: 30, font: "400 12px/1 'IBM Plex Sans'", color: "#6B6F6C" }}>
          <div>…</div>
          <div>още 22 статии</div>
          <div /><div /><div /><div /><div /><div />
        </div>

        <div className="jr-grid pf-foot">
          <div>Период</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>31 статии</div>
          <div style={{ color: "#6B6F6C", fontWeight: 400 }}>оборотна ведомост 01.09 – 22.09.2026</div>
          <div /><div />
          <div className="num">€4.442,80</div>
          <div className="num">€4.442,80</div>
          <div className="num">€3.812,40</div>
        </div>
      </div>
    </>
  );
}
