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

const CONSENT = "Съгласен съм данните ми да бъдат обработени за целите на заявката";

/**
 * The request as the text of a letter the visitor sends from their own mail: nothing leaves this page by itself.
 * The consent they ticked is a line of the letter, so it reaches us with the request. Half of a broken character
 * pair (a pasted, cut emoji) is dropped — it cannot be written into a link.
 */
const letter = (f: Fields): string =>
  [
    `Име: ${f.name.trim()}`,
    f.firm.trim() ? `Фирма: ${f.firm.trim()}` : null,
    `Брой входове: ${f.count}`,
    f.phone.trim() ? `Телефон: ${f.phone.trim()}` : null,
    `Имейл за отговор: ${f.email.trim()}`,
    "",
    `${CONSENT}.`,
  ].filter((line) => line !== null).join("\n")
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "");

const SUBJECT = "Заявка за демо";
/** A mail link breaks its lines with CR LF (RFC 6068). */
const mailto = (to: string, text: string): string =>
  `mailto:${to}?subject=${encodeURIComponent(SUBJECT)}&body=${encodeURIComponent(text.replace(/\n/g, "\r\n"))}`;

const card: React.CSSProperties = { display: "flex", flexDirection: "column", gap: 14, padding: 28, background: "#FFFFFF", border: "1px solid #DEDDD9", borderRadius: 6 };
const cardTitle: React.CSSProperties = { margin: 0, fontFamily: "Literata, Georgia, serif", fontWeight: 400, fontSize: 24, letterSpacing: "-0.015em" };
const cardText: React.CSSProperties = { margin: 0, font: "400 15px/1.6 'IBM Plex Sans'", color: "#5C605E" };

/**
 * The demo request (#79). The page sends nothing and stores nothing, so it never says a request arrived. With an
 * address to write to ([contact], the server's setting) submitting writes the request out as a letter: the visitor
 * opens it in their own mail, or copies it, and the request reaches us when they send it. Without one there is no form.
 */
export default function DemoForm({ contact }: { contact: string | null }) {
  const [f, setF] = useState<Fields>(EMPTY);
  const [err, setErr] = useState<Errors>({});
  const [tried, setTried] = useState(false);
  const [prepared, setPrepared] = useState<string | null>(null);   // the letter's text, once the fields are valid

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
    } else if (contact) {
      setPrepared(letter(f));
      setErr({});
      setTried(false);
    }
  };

  const nErr = Object.keys(err).length;

  if (!contact) {
    return (
      <div style={{ flex: "1.3 1 360px", minWidth: 0 }}>
        <div style={card}>
          <h3 style={cardTitle}>Още не приемаме заявки през сайта</h3>
          <p style={cardText}>Адресът, на който да ни пишете, ще бъде публикуван тук.</p>
        </div>
      </div>
    );
  }

  if (prepared) {
    return (
      <div style={{ flex: "1.3 1 360px", minWidth: 0 }}>
        <div style={card}>
          <h3 style={cardTitle}>Писмото е готово — изпратете го</h3>
          <p style={cardText}>Заявката стига до нас едва когато изпратите това писмо на <span style={{ color: "#17191A" }}>{contact}</span>. Тази страница не изпраща нищо сама.</p>
          <pre style={{ margin: 0, padding: "12px 14px", background: "#F7F6F3", border: "1px solid #DEDDD9", borderRadius: 6, font: "400 13.5px/1.6 'IBM Plex Mono', monospace", color: "#17191A", whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{prepared}</pre>
          <a href={mailto(contact, prepared)} className="ln-solid" style={{ alignSelf: "flex-start", padding: "14px 24px", borderRadius: 10, font: "500 15px/1 'IBM Plex Sans'" }}>Отворете го във вашата поща</a>
          <p style={cardText}>Ако пощата не се отвори, копирайте текста и го изпратете на {contact} с тема „{SUBJECT}“. Ако искате да сверим последния ви месец на срещата, приложете таблицата си.</p>
          <button type="button" onClick={() => setPrepared(null)} style={{ alignSelf: "flex-start", height: 40, padding: 0, border: 0, background: "transparent", color: "#14584A", font: "500 14px/1 'IBM Plex Sans'", cursor: "pointer" }}>Обратно към формата</button>
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
          <span style={labelText}>Имейл за отговор</span>
          <input type="email" value={f.email} onChange={set("email")} autoComplete="email" style={inputStyle(err.email)} />
          {err.email && <span style={errText}>{err.email}</span>}
        </label>
        <div style={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", gap: 6 }}>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 10, cursor: "pointer", font: "400 14px/1.5 'IBM Plex Sans'" }}>
            <input type="checkbox" checked={f.consent} onChange={set("consent")} style={{ flex: "none", width: 18, height: 18, margin: "2px 0 0", accentColor: "#14584A" }} />
            <span>{CONSENT}, съгласно <span style={{ borderBottom: "1px solid #9A9E96" }} title="Документът се публикува преди старта">политиката за поверителност (предстои)</span>.</span>
          </label>
          {err.consent && <span style={{ ...errText, paddingLeft: 28 }}>{err.consent}</span>}
        </div>
        <div style={{ gridColumn: "1 / -1", display: "flex", flexWrap: "wrap", alignItems: "center", gap: 16, marginTop: 4 }}>
          <button type="submit" className="ln-solid" style={{ flex: "none", height: 48, padding: "0 28px", border: 0, borderRadius: 10, font: "500 15px/1 'IBM Plex Sans'", whiteSpace: "nowrap", cursor: "pointer" }}>Подгответе писмото</button>
          <span style={{ font: "400 13px/1.4 'IBM Plex Sans'", color: tried && nErr ? "#93291F" : "#5C605E" }}>
            {tried && nErr ? (nErr === 1 ? "1 поле изисква внимание." : `${nErr} полета изискват внимание.`) : "Ще подготвим писмо, което изпращате от вашата поща."}
          </span>
        </div>
      </form>
    </div>
  );
}
