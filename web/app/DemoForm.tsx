"use client";

import { useState } from "react";

interface Fields {
  name: string;
  firm: string;
  count: string;
  phone: string;
  email: string;
  consent: boolean;
}
type Errors = Partial<Record<keyof Fields, string>>;

const EMPTY: Fields = { name: "", firm: "", count: "", phone: "", email: "", consent: false };

function validate(f: Fields): Errors {
  const e: Errors = {};
  if (!f.name.trim()) e.name = "Въведете име.";
  if (!f.count) e.count = "Изберете брой входове.";
  if (f.phone && !/^\+?[\d\s]{7,}$/.test(f.phone.trim())) e.phone = "Само цифри, интервали и „+“ в началото.";
  if (!f.email.trim()) e.email = "Въведете имейл.";
  else if (!/^\S+@\S+\.\S+$/.test(f.email.trim())) e.email = "Имейлът изглежда непълен — липсва домейн.";
  if (!f.consent) e.consent = "Без съгласие не можем да обработим заявката.";
  return e;
}

const inputStyle = (bad?: string): React.CSSProperties => ({
  height: 44, padding: "0 12px", border: `1px solid ${bad ? "#A3322A" : "#8F938C"}`, borderRadius: 6,
  background: "#FFFFFF", font: "400 15px/1 'IBM Plex Sans'", color: "#17191A", boxSizing: "border-box", outline: "none",
});
const labelWrap: React.CSSProperties = { display: "flex", flexDirection: "column", gap: 6, minWidth: 0 };
const labelText: React.CSSProperties = { font: "500 13px/1 'IBM Plex Sans'" };
const errText: React.CSSProperties = { font: "400 12.5px/1.4 'IBM Plex Sans'", color: "#93291F" };

export default function DemoForm() {
  const [f, setF] = useState<Fields>(EMPTY);
  const [err, setErr] = useState<Errors>({});
  const [tried, setTried] = useState(false);
  const [sent, setSent] = useState(false);

  const set = (k: keyof Fields) => (ev: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const val = k === "consent" ? (ev.target as HTMLInputElement).checked : ev.target.value;
    const next = { ...f, [k]: val };
    setF(next);
    if (tried) setErr(validate(next));
  };

  const submit = (ev: React.FormEvent) => {
    ev.preventDefault();
    const e = validate(f);
    if (Object.keys(e).length) {
      setErr(e);
      setTried(true);
    } else {
      setSent(true);
      setErr({});
      setTried(false);
    }
  };

  const nErr = Object.keys(err).length;

  if (sent) {
    return (
      <div style={{ flex: "1.3 1 360px", minWidth: 0 }}>
        <div style={{ display: "flex", flexDirection: "column", gap: 14, padding: 28, background: "#FFFFFF", border: "1px solid #DEDDD9", borderRadius: 6 }}>
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#14584A" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10" /><path d="m8 12 3 3 5-6" /></svg>
          <h3 style={{ margin: 0, fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: 24, letterSpacing: "-0.015em" }}>Заявката е приета</h3>
          <p style={{ margin: 0, font: "400 15px/1.6 'IBM Plex Sans'", color: "#5C605E" }}>Ще се свържем с вас на посочения имейл, за да уговорим час. Ако искате да сверим последния ви месец на срещата, отговорете на писмото с таблицата си.</p>
          <button type="button" onClick={() => { setSent(false); setF(EMPTY); setErr({}); setTried(false); }} style={{ alignSelf: "flex-start", height: 40, padding: 0, border: 0, background: "transparent", color: "#14584A", font: "500 14px/1 'IBM Plex Sans'", cursor: "pointer" }}>Нова заявка</button>
        </div>
      </div>
    );
  }

  return (
    <div style={{ flex: "1.3 1 360px", minWidth: 0 }}>
      <form onSubmit={submit} noValidate style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(min(100%,220px),1fr))", gap: "18px 16px" }}>
        <label style={labelWrap}>
          <span style={labelText}>Име</span>
          <input value={f.name} onChange={set("name")} autoComplete="name" style={inputStyle(err.name)} />
          {err.name && <span style={errText}>{err.name}</span>}
        </label>
        <label style={labelWrap}>
          <span style={labelText}>Фирма <span style={{ fontWeight: 400, color: "#5C605E" }}>· по избор</span></span>
          <input value={f.firm} onChange={set("firm")} autoComplete="organization" style={inputStyle()} />
        </label>
        <label style={labelWrap}>
          <span style={labelText}>Брой входове</span>
          <select value={f.count} onChange={set("count")} style={{ ...inputStyle(err.count), padding: "0 10px" }}>
            <option value="">Изберете</option>
            <option value="1">1</option>
            <option value="2–10">2–10</option>
            <option value="11–40">11–40</option>
            <option value="40+">40+</option>
          </select>
          {err.count && <span style={errText}>{err.count}</span>}
        </label>
        <label style={labelWrap}>
          <span style={labelText}>Телефон <span style={{ fontWeight: 400, color: "#5C605E" }}>· по избор</span></span>
          <input type="tel" value={f.phone} onChange={set("phone")} autoComplete="tel" placeholder="+359" style={{ ...inputStyle(err.phone), fontVariantNumeric: "tabular-nums" }} />
          {err.phone && <span style={errText}>{err.phone}</span>}
        </label>
        <label style={{ ...labelWrap, gridColumn: "1 / -1" }}>
          <span style={labelText}>Имейл</span>
          <input type="email" value={f.email} onChange={set("email")} autoComplete="email" style={inputStyle(err.email)} />
          {err.email && <span style={errText}>{err.email}</span>}
        </label>
        <div style={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", gap: 6 }}>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 10, cursor: "pointer", font: "400 14px/1.5 'IBM Plex Sans'" }}>
            <input type="checkbox" checked={f.consent} onChange={set("consent")} style={{ flex: "none", width: 18, height: 18, margin: "2px 0 0", accentColor: "#14584A" }} />
            <span>Съгласен съм данните ми да бъдат обработени за целите на заявката, съгласно <span style={{ borderBottom: "1px solid #9A9E96" }} title="Документът се публикува преди старта">политиката за поверителност (предстои)</span>.</span>
          </label>
          {err.consent && <span style={{ ...errText, paddingLeft: 28 }}>{err.consent}</span>}
        </div>
        <div style={{ gridColumn: "1 / -1", display: "flex", flexWrap: "wrap", alignItems: "center", gap: 16, marginTop: 4 }}>
          <button type="submit" className="ln-solid" style={{ flex: "none", height: 48, padding: "0 28px", border: 0, borderRadius: 10, font: "500 15px/1 'IBM Plex Sans'", whiteSpace: "nowrap", cursor: "pointer" }}>Изпратете заявката</button>
          <span style={{ font: "400 13px/1.4 'IBM Plex Sans'", color: tried && nErr ? "#93291F" : "#5C605E" }}>
            {tried && nErr ? (nErr === 1 ? "1 поле изисква внимание." : `${nErr} полета изискват внимание.`) : "Отговаряме в работни дни."}
          </span>
        </div>
      </form>
    </div>
  );
}
