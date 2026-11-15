"use client";

import { useActionState } from "react";
import type { Schemas } from "@/lib/api/client";
import { completeness, date, KIND, OUTCOME, partyLine, sofiaTime } from "@/lib/book";
import { read, type BookState } from "./actions";

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;
const ROW = { display: "grid", gridTemplateColumns: "90px 90px 110px minmax(200px, 3fr) 70px minmax(140px, 1.5fr) 90px", gap: 12 } as const;

/**
 * The book read, or its log (WEB-44) — works without a script. A purpose is typed before every read: the page reads
 * nothing on its own, and each button is one read, entered in the log (Rule: PM-BOOK-007).
 */
// Rule: PM-BOOK-007, PM-BOOK-006
export default function BookForm({ entrance, today }: { entrance: string; today: string }) {
  const [state, act, pending] = useActionState<BookState, FormData>(read, {});
  const input = state.input;
  return (
    <>
      <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
        <input type="hidden" name="entrance" value={entrance} />
        <div className="wide acct-title">Четене на книгата</div>
        <div className="wide as-hint">
          Книгата се чете от управителя, управителния съвет и контрольора (чл. 7, ал. 4 ЗУЕС). Всяко четене посочва целта си и се вписва в дневника —
          кой, кога и защо, дадено или отказано.
        </div>
        <label>Към дата<input type="date" name="on" defaultValue={input?.on || today} /></label>
        <label className="wide">Цел на четенето<input type="text" name="purpose" defaultValue={input?.purpose} autoComplete="off" /></label>
        {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
        <div className="wide" style={{ display: "flex", gap: 8 }}>
          <button type="submit" name="what" value="book" className="chip active" disabled={pending}>Прочети книгата</button>
          <button type="submit" name="what" value="log" className="chip" disabled={pending}>Кой е чел книгата</button>
        </div>
      </form>
      {state.book && <Book book={state.book} />}
      {state.log && <Log log={state.log} />}
    </>
  );
}

/**
 * The book as the API built it (Rule: PM-BOOK-001, PM-BOOK-002): each unit's designation, area, ideal parts, parties,
 * household and non-use, and whether it — and the book — is complete, as the API says. Parties by name, role and share
 * only (Rule: PM-BOOK-011).
 */
// Rule: PM-BOOK-001, PM-BOOK-002, PM-BOOK-011
function Book({ book }: { book: Schemas["CondominiumBook"] }) {
  return (
    <div style={BODY} role="region" aria-label="Книгата">
      <div className="acct-title">
        Книгата към {date(book.asOf)} · <span className={`badge ${completeness(book.complete).badge}`}>{completeness(book.complete).label}</span>
      </div>
      {book.units.length === 0 && <div className="as-hint">В книгата няма вписани обекти.</div>}
      {book.units.length > 0 && (
        <div style={{ ...ROW, fontWeight: 500 }}>
          <div>Обект</div><div className="num">Площ, м²</div><div className="num">Идеални части, %</div><div>Собственици и ползватели</div>
          <div className="num">Живущи</div><div>Не се ползва</div><div>Вписване</div>
        </div>
      )}
      {book.units.map((u) => (
        <div key={u.unitId} style={{ ...ROW, borderTop: "1px solid #DEDDD9", padding: "6px 0" }}>
          <div>{u.designation}</div>
          <div className="num">{u.builtAreaM2 ?? "—"}</div>
          <div className="num">{u.idealParts}</div>
          <div>{u.parties.length ? u.parties.map((p, i) => <div key={i}>{partyLine(p)}</div>) : "—"}</div>
          <div className="num">{u.householdCount}</div>
          <div>{u.nonUse.length ? u.nonUse.map((n, i) => <div key={i}>{date(n.from)} – {date(n.to)}</div>) : "—"}</div>
          <div><span className={`badge ${completeness(u.complete).badge}`}>{completeness(u.complete).label}</span></div>
        </div>
      ))}
    </div>
  );
}

/** Who read the book, or was refused it, why and when — as the API lists it, oldest first (Rule: PM-BOOK-007). */
// Rule: PM-BOOK-007
function Log({ log }: { log: Schemas["BookAccessView"][] }) {
  return (
    <div style={BODY} role="region" aria-label="Дневник">
      <div className="acct-title">Дневник на книгата · най-старото първо</div>
      {log.length === 0 && <div className="as-hint">Няма вписвания.</div>}
      {log.map((e) => (
        <div key={e.id} style={{ borderTop: "1px solid #DEDDD9", padding: "6px 0" }}>
          {sofiaTime(e.at)} · {e.actorName ?? (e.loginSubject ? `вход ${e.loginSubject}` : "—")} · {KIND[e.kind] ?? e.kind}
          {e.bookDate && <> към {date(e.bookDate)}</>} · {OUTCOME[e.outcome] ?? e.outcome}{e.ruleId && <> ({e.ruleId})</>} · цел: {e.purpose}
        </div>
      ))}
    </div>
  );
}
