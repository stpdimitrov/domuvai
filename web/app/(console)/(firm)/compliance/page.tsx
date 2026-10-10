import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entrances, inTurn, isPeriod, monthName, reach, shiftPeriod, thisPeriod } from "@/lib/console";

export const metadata: Metadata = { title: "Съответствие — Етаж" };

type Entrance = Schemas["EntranceView"];
type Overdue = Schemas["OverdueDeclaration"];
type Headcount = Schemas["HeadcountCheck"];

/** One answer of the API about one entrance: what it served, or how it did not. */
type Part<T> =
  | { kind: "ok"; data: T }
  | { kind: "none" }                                        // the API says there is nothing of the kind to answer about
  | { kind: "refused"; status: number; message: string }
  | { kind: "down" };

type Row = { entrance: Entrance; overdue: Part<Overdue[]>; headcount: Part<Headcount> };

type Loaded =
  | { kind: "ok"; rows: Row[] }
  | { kind: "down" }
  | { kind: "unlisted"; status: number; message: string }
  | { kind: "none" };

type Answer<T> = { data?: T; error?: { error?: string }; response: Response } | null;
const part = <T,>(answer: Answer<T>): Part<T> =>
  !answer ? { kind: "down" }
  : answer.data !== undefined ? { kind: "ok", data: answer.data }
  : { kind: "refused", status: answer.response.status, message: answer.error?.error ?? `HTTP ${answer.response.status}` };

/**
 * One screen, one server-side aggregation (ADR-011): for every entrance, who is past the deadline to declare for the
 * book (Rule: PM-BOOK-003 — the registry's list and its own due dates; names, units and roles only, PM-BOOK-011) and
 * the month's headcount check (Rule: PM-BOOK-012 — money's comparison of the persons the run billed with the persons
 * the book declares). Every date and count is the API's: the page computes no deadline and compares nothing. Each
 * answer fails alone, and an entrance with no run issued for the month is said to have none — the API's own 404 —
 * never shown as one with nothing to report.
 */
async function load(period: string): Promise<Loaded> {
  const listed = await entrances();
  if (listed.kind !== "ok") return listed;
  if (listed.entrances.length === 0) return { kind: "none" };

  const rows = await inTurn(listed.entrances, async (entrance): Promise<Row> => {
    const [overdue, headcount] = await Promise.all([
      reach(() => api.GET("/api/registry/entrances/{entranceId}/book/declarations/overdue", { params: { path: { entranceId: entrance.id } } })),
      reach(() => api.GET("/api/money/entrances/{entranceId}/charge-runs/{period}/headcount", { params: { path: { entranceId: entrance.id, period } } })),
    ]);
    const checked = part<Headcount>(headcount);
    const noRun = checked.kind === "refused" && checked.status === 404 && checked.message.includes("has no charge run issued for");
    return { entrance, overdue: part<Overdue[]>(overdue), headcount: noRun ? { kind: "none" } : checked };
  });
  return { kind: "ok", rows };
}

/** How an answer that did not come is said: a refusal as a refusal, in the API's own words. */
function unanswered(p: Exclude<Part<unknown>, { kind: "ok" } | { kind: "none" }>, what: string): string {
  if (p.kind === "down") return `Бекендът не подаде ${what}.`;
  if (p.status === 403) return `Нямате право да четете ${what}: ${p.message}`;
  if (p.status === 401) return `Входът ви не беше приет за ${what}. Излезте и влезте отново.`;
  return `API отказа ${what}: ${p.message}`;
}

const ROLE: Record<string, string> = { OWN: "собственик", USR: "ползвател" };   // the registry's TitleRole
// Money's three ways a unit's billed persons are not the book's (PM-BOOK-012); any other is shown as it came.
const DIFFERENCE: Record<string, string> = {
  COUNT_DIFFERS: "броят се различава",
  NOT_IN_BOOK: "начислен, а вече не е обект на входа по книга",
  NOT_BILLED: "обект по книга, а не е начислен",
};

const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const date = (iso: string) => iso.split("-").reverse().join(".");
// A side that does not exist has no counts (the API leaves them out), and none is put in its place.
const persons = (all: number | undefined, under6: number | undefined) => (all === undefined ? "—" : under6 === undefined ? `${all}` : `${all} · ${under6} под 6 г.`);

const DIM = { color: "#6B6F6C" };
const FAILED = { color: "#8E2318" };
const HEADING = { font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase" as const, color: "#6B6F6C" };

// What the design draws and the API does not serve: its headings, said as not kept — nothing filled in, no basis cited.
const NOT_KEPT = ["Вписване в публичния регистър", "Застраховка „Професионална отговорност“", "Договори за управление"];

export default async function CompliancePage({ searchParams }: { searchParams: Promise<{ period?: string | string[] }> }) {
  const query = await searchParams;
  const asked = typeof query.period === "string" ? query.period : undefined;   // a repeated parameter is a list, and no month
  const period = isPeriod(asked) ? asked : thisPeriod();
  const view = await load(period);

  const rows = view.kind === "ok" ? view.rows : [];
  const late = rows.reduce((n, r) => n + (r.overdue.kind === "ok" ? r.overdue.data.length : 0), 0);
  const differing = rows.reduce((n, r) => n + (r.headcount.kind === "ok" ? r.headcount.data.mismatches.length : 0), 0);
  // A total counts what was answered. Where an entrance's answer did not come, every total beside it says so.
  const unreadLate = rows.filter((r) => r.overdue.kind !== "ok").length;
  const unreadChecks = rows.filter((r) => r.headcount.kind !== "ok" && r.headcount.kind !== "none").length;

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="title">Съответствие</span>
          <span className="divider" />
          <span className="meta">
            {view.kind === "ok" && (
              <>
                {count(late, "просрочена декларация", "просрочени декларации")} към днес
                {unreadLate > 0 && <span style={FAILED}> (без {count(unreadLate, "непрочетен вход", "непрочетени входа")})</span>} ·{" "}
                {count(differing, "разминаване", "разминавания")} за {monthName(period)}
                {unreadChecks > 0 && <span style={FAILED}> (без {count(unreadChecks, "непрочетен вход", "непрочетени входа")})</span>} ·{" "}
                {count(rows.length, "вход", "входа")}
              </>
            )}
          </span>
        </div>
        {view.kind === "ok" && late > 0 && (
          <span className="badge crit">{unreadLate > 0 ? "поне " : ""}{count(late, "просрочена декларация", "просрочени декларации")}</span>
        )}
      </div>

      <div style={{ padding: "20px 24px 0", display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 16 }}>
        {NOT_KEPT.map((title) => (
          <div key={title} className="co-card">
            <div className="kicker">{title}</div>
            <div className="serif" style={DIM}>Още не се води в системата</div>
          </div>
        ))}
      </div>

      {view.kind !== "ok" ? (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "unlisted" && unanswered({ kind: "refused", status: view.status, message: view.message }, "списъка на входовете")}
            {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход и обектите му в <code>registry</code>, после опреснете.</>}
          </div>
        </div>
      ) : (
        <>
          <Declarations rows={rows} late={late} unread={unreadLate} />
          <HeadcountChecks rows={rows} period={period} differing={differing} unread={unreadChecks} />
        </>
      )}

      <div style={{ margin: "20px 24px 24px", font: "400 12px/1.6 'IBM Plex Sans'", ...DIM }}>
        <span style={HEADING}>Подадени отчети и декларации</span>
        <div style={{ marginTop: 8 }}>
          Отчетите и декларациите на фирмата още не се водят в системата.
        </div>
      </div>
    </>
  );
}

/** Rule: PM-BOOK-003 — who is past the registry's due date to declare for the book, entrance by entrance. */
function Declarations({ rows, late, unread }: { rows: Row[]; late: number; unread: number }) {
  return (
    <>
      <div style={{ margin: "20px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <span style={HEADING}>Просрочени декларации за книгата на собствениците</span>
        <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", ...DIM }}>чл. 7, ал. 3 ЗУЕС · срокът е на регистъра</span>
      </div>
      <div className="pf-card cp-card" style={{ margin: "12px 24px 0" }}>
        <div className="cp-od pf-head">
          <div>Обект</div>
          <div>Лице</div>
          <div>Качество</div>
          <div className="num">От</div>
          <div className="num">Срок до</div>
        </div>
        {rows.map((r, i) => (
          <div key={r.entrance.id}>
            <div className={`dt-group${i > 0 ? " mid" : ""}`}>
              <Link href={`/entrance?entrance=${r.entrance.id}`}>{r.entrance.label}</Link>
              <span className="gnote">{r.overdue.kind === "ok" ? count(r.overdue.data.length, "просрочена декларация", "просрочени декларации") : "—"}</span>
            </div>
            {r.overdue.kind !== "ok" && r.overdue.kind !== "none" && (
              <div className="cp-od pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{unanswered(r.overdue, "просрочените декларации на входа")}</div></div>
            )}
            {r.overdue.kind === "ok" && r.overdue.data.length === 0 && (
              <div className="cp-od pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма просрочена декларация.</div></div>
            )}
            {r.overdue.kind === "ok" && r.overdue.data.map((d, n) => (
              <div key={`${d.unitId}-${d.partyName}-${d.titleRole}-${d.acquiredOn}-${n}`} className="cp-od pf-row">
                <div style={{ fontWeight: 500 }}>{d.designation}</div>
                <div className="ellipsis">{d.partyName}</div>
                <div>{ROLE[d.titleRole] ?? d.titleRole.toLowerCase()}</div>
                <div className="num">{date(d.acquiredOn)}</div>
                <div className="num" style={FAILED}>{date(d.dueOn)}</div>
              </div>
            ))}
          </div>
        ))}
        <div className="cp-od pf-foot">
          <div>Общо</div>
          <div style={{ ...DIM, fontWeight: 400 }}>
            {count(late, "просрочена декларация", "просрочени декларации")}
            {unread > 0 && <span style={FAILED}> · без {count(unread, "непрочетен вход", "непрочетени входа")}</span>}
          </div>
          <div /><div /><div />
        </div>
      </div>
    </>
  );
}

/** Rule: PM-BOOK-012 — the month's exception report: the units whose billed persons are not the persons the book declares. */
function HeadcountChecks({ rows, period, differing, unread }: { rows: Row[]; period: string; differing: number; unread: number }) {
  const issued = rows.filter((r) => r.headcount.kind === "ok").length;
  const none = rows.filter((r) => r.headcount.kind === "none").length;
  const [before, after] = [shiftPeriod(period, -1), shiftPeriod(period, 1)];
  return (
    <>
      <div style={{ margin: "20px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <span style={HEADING}>Сверка на книгата с начисленията · {monthName(period)}</span>
        <nav aria-label="Месец на сверката" style={{ display: "flex", gap: 6 }}>
          {isPeriod(before) && <Link href={`/compliance?period=${before}`} className="chip">← {monthName(before)}</Link>}
          <span className="chip active" aria-current="page">{monthName(period)}</span>
          {isPeriod(after) && <Link href={`/compliance?period=${after}`} className="chip">{monthName(after)} →</Link>}
        </nav>
      </div>
      <div className="pf-card cp-card" style={{ margin: "12px 24px 0" }}>
        <div className="cp-hc pf-head">
          <div>Обект</div>
          <div>Разминаване</div>
          <div className="num">Начислени лица</div>
          <div className="num">Лица по книга</div>
        </div>
        {rows.map((r, i) => {
          const h = r.headcount;
          return (
            <div key={r.entrance.id}>
              <div className={`dt-group${i > 0 ? " mid" : ""}`}>
                <Link href={`/entrance/charges?entrance=${r.entrance.id}&period=${period}`}>{r.entrance.label}</Link>
                <span className="gnote">
                  {h.kind === "ok" && <>сравнени {count(h.data.unitsCompared, "обект", "обекта")} към {date(h.data.legalDate)} · {count(h.data.mismatches.length, "разминаване", "разминавания")}</>}
                  {h.kind === "none" && "няма издадено начисление за месеца"}
                  {h.kind !== "ok" && h.kind !== "none" && "—"}
                </span>
              </div>
              {h.kind === "none" && (
                <div className="cp-hc pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>За {monthName(period)} няма издадено начисление — няма какво да се свери.</div></div>
              )}
              {h.kind !== "ok" && h.kind !== "none" && (
                <div className="cp-hc pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{unanswered(h, "сверката на входа")}</div></div>
              )}
              {h.kind === "ok" && h.data.mismatches.length === 0 && (
                <div className="cp-hc pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Начислените лица са лицата по книга.</div></div>
              )}
              {h.kind === "ok" && h.data.mismatches.map((m) => (
                <div key={m.unitId} className="cp-hc pf-row">
                  <div style={{ fontWeight: 500 }}>{m.designation}</div>
                  <div>{DIFFERENCE[m.difference] ?? m.difference}</div>
                  <div className="num">{persons(m.billedOccupants, m.billedChildrenUnder6)}</div>
                  <div className="num">{persons(m.declaredOccupants, m.declaredChildrenUnder6)}</div>
                </div>
              ))}
            </div>
          );
        })}
        <div className="cp-hc pf-foot">
          <div>Общо</div>
          <div style={{ ...DIM, fontWeight: 400 }}>
            {count(differing, "разминаване", "разминавания")} · {count(issued, "вход с начисление", "входа с начисление")} · {none} без начисление за месеца
            {unread > 0 && <span style={FAILED}> · {count(unread, "непрочетен вход", "непрочетени входа")}</span>}
          </div>
          <div /><div />
        </div>
      </div>
    </>
  );
}
