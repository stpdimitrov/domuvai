import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, reach, type EntranceAt } from "@/lib/console";
import { refused, unanswered, type Refused } from "@/lib/refusal";
import { sofiaTime } from "@/lib/sofia";
import ConveneForm from "./ConveneForm";

export const metadata: Metadata = { title: "Общи събрания — Етаж" };

type Entrance = Schemas["EntranceView"];

/** One answer of the API: what it served, or how it did not (null: the backend did not answer). */
type Part<T> = { ok: true; data: T } | { ok: false; by: Refused | null };
const part = <T,>(answer: { data?: T; error?: unknown; response: Response } | null): Part<T> =>
  answer?.data !== undefined ? { ok: true, data: answer.data } : { ok: false, by: answer ? refused(answer) : null };

type Shown = {
  assembly: Part<Schemas["AssemblyView"]>;
  quorum: Part<Schemas["QuorumView"]>;
  attendance: Part<Schemas["AttendanceView"][]>;
  proxies: Part<Schemas["ProxyView"][]>;
};
type Loaded =
  | { kind: "ok"; entrance: Entrance; mandates: Part<Schemas["StandingMandate"][]>; shown: Shown | null; asked: string | null }
  | Exclude<EntranceAt, { kind: "ok" }>;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia" }).format(new Date());

/**
 * One screen, one server-side aggregation (ADR-011): the entrance's offices in force today and how each stands
 * (identity, PM-GOV-005), and — opened by its link — one assembly: what it is, its agenda, the quorum meter of the
 * sitting it is at (PM-GA-012) and the register and proxies behind it. Every figure is the API's: the page computes
 * no deadline, no share, no threshold and no majority. Each answer fails alone. The API lists no assemblies of an
 * entrance, so the page cannot offer them; it offers convening, which answers with the new assembly's link.
 */
async function load(entranceId: string | undefined, assemblyId: string | undefined): Promise<Loaded> {
  const at = await entranceAt(entranceId);
  if (at.kind !== "ok") return at;
  const { entrance } = at;
  const path = { entranceId: entrance.id };
  const asked = assemblyId ?? null;
  const shownFor = async (id: string): Promise<Shown> => {
    const of = { params: { path: { ...path, assemblyId: id } } };
    const [assembly, quorum, attendance, proxies] = await Promise.all([
      reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}", of)),
      reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/quorum", of)),
      reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance", of)),
      reach(() => api.GET("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies", of)),
    ]);
    return { assembly: part(assembly), quorum: part(quorum), attendance: part(attendance), proxies: part(proxies) };
  };
  const [mandates, shown] = await Promise.all([
    reach(() => api.GET("/api/identity/entrances/{entranceId}/mandates", { params: { path, query: { on: today() } } })),
    asked && UUID.test(asked) ? shownFor(asked) : Promise.resolve(null),
  ]);
  return { kind: "ok", entrance, mandates: part(mandates), shown, asked };
}

// The words for the API's codes. Any code not here is shown as it came, never guessed.
const OFFICE: Record<string, string> = { MB: "член на управителния съвет", BM: "управител", CTL: "контролен съвет / контрольор", CSH: "касиер" };
const STATUS: Record<string, { text: string; kind: "calm" | "warn" | "crit" }> = {
  DRAFT: { text: "чернова — поканата не е обявена", kind: "warn" },
  NOTICED: { text: "обявено", kind: "calm" },
  OPEN: { text: "открито", kind: "calm" },
};
const MODE: Record<string, string> = { IN_PERSON: "присъствено", VIDEO: "видеоконферентно", HYBRID: "смесено" };
const CHANNEL: Record<string, string> = { IN_PERSON: "лично", VIDEO: "видео" };
const SCOPE: Record<string, string> = { WHOLE_AGENDA: "целия дневен ред", LISTED_ITEMS: "точки" };
const FORM: Record<string, string> = { WRITTEN: "писмено", NOTARISED: "с нотариална заверка", LAWYER_AUTHORISATION: "адвокатско пълномощно" };
const word = (table: Record<string, string>, code: string) => table[code] ?? code;

const date = (iso: string) => iso.split("-").reverse().join(".");
/** A party as the API gives it: its id, which no read here pairs with a name. The whole id is on hover. */
const Party = ({ id }: { id: string | undefined }) => (id ? <span className="mono" title={id}>{id.slice(0, 8)}</span> : <>—</>);
const DIM = { color: "#6B6F6C" };
const FAILED = { color: "#8E2318" };
const HEADING = { font: "500 10px/1 'IBM Plex Sans'", letterSpacing: ".14em", textTransform: "uppercase" as const, color: "#6B6F6C" };

function Standing({ s }: { s: Schemas["Standing"] }) {
  if (s.kind === "IN_TERM") return <>в мандат{s.until && <> до {date(s.until)}</>}</>;
  if (s.kind === "ELECTION_DUE") return <span style={{ color: "#7A5210" }}>изборът се дължи{s.electBy && <> до {date(s.electBy)}</>}</span>;
  if (s.kind === "EXPIRED_ACTING") return <span style={FAILED}>мандатът е изтекъл — изпълнява{s.actingSince && <> от {date(s.actingSince)}</>}</span>;
  return <>{s.kind}</>;
}

export default async function AssemblyPage({ searchParams }: { searchParams: Promise<{ entrance?: string | string[]; assembly?: string | string[] }> }) {
  const query = await searchParams;
  const view = await load(
    typeof query.entrance === "string" ? query.entrance : undefined,
    typeof query.assembly === "string" ? query.assembly : undefined,   // a repeated parameter is a list, and no assembly
  );
  const entrance = view.kind === "ok" ? view.entrance : undefined;

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill"><span className="name">{entrance?.label ?? "—"}</span></span>
          <span className="meta">Общи събрания</span>
        </div>
      </div>

      {view.kind !== "ok" ? (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
            {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход и обектите му в <code>registry</code>, после опреснете.</>}
            {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
          </div>
        </div>
      ) : (
        <Body view={view} />
      )}
    </>
  );
}

function Body({ view }: { view: Extract<Loaded, { kind: "ok" }> }) {
  const { entrance, mandates, shown, asked } = view;
  return (
    <div style={{ padding: "0 24px 24px" }}>
      <div style={{ margin: "20px 0 0", ...HEADING }}>Управа на входа днес</div>
      <div className="pf-card cp-card" style={{ margin: "12px 0 0" }}>
        <div className="as-mandates pf-head">
          <div>Длъжност</div><div>Лице</div><div className="num">От</div><div className="num">До</div><div>Положение</div>
        </div>
        {!mandates.ok && <div className="as-mandates pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{unanswered(mandates.by, "мандатите на входа")}</div></div>}
        {mandates.ok && mandates.data.length === 0 && (
          <div className="as-mandates pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма вписан мандат в сила днес — входът няма управа, която да свика събрание.</div></div>
        )}
        {mandates.ok && mandates.data.map((m) => (
          <div key={m.mandateId} className="as-mandates pf-row">
            <div>{word(OFFICE, m.role)}</div>
            <div><Party id={m.partyId} /></div>
            <div className="num">{date(m.validFrom)}</div>
            <div className="num">{date(m.validTo)}</div>
            <div><Standing s={m.standing} /></div>
          </div>
        ))}
      </div>

      {asked && !shown && (
        <div className="as-note" style={FAILED}>„{asked}“ не е събрание — връзката към събранието съдържа неговия идентификатор.</div>
      )}
      {shown && <Assembly shown={shown} />}
      {!asked && (
        <div className="as-note" style={DIM}>
          Системата още не изрежда събранията на входа — събрание се отваря по връзката му. Новото събрание се отваря веднага след свикването.
        </div>
      )}

      <div style={{ margin: "24px 0 0", ...HEADING }}>Свикване на общо събрание</div>
      <div className="co-card" style={{ marginTop: 12 }}>
        <ConveneForm entrance={entrance.id} />
      </div>
    </div>
  );
}

function Assembly({ shown }: { shown: Shown }) {
  const { assembly, quorum, attendance, proxies } = shown;
  if (!assembly.ok) return <div className="as-note" style={FAILED}>{unanswered(assembly.by, "събранието")}</div>;
  const a = assembly.data;
  const status = STATUS[a.status] ?? { text: a.status, kind: "warn" as const };
  return (
    <>
      <div style={{ margin: "24px 0 0", display: "flex", alignItems: "center", gap: 10 }}>
        <span style={HEADING}>Общо събрание</span>
        <span className={`badge ${status.kind}`}>{status.text}</span>
      </div>
      <div className="co-card as-facts" style={{ marginTop: 12 }}>
        <div><span className="k">Кога</span>{sofiaTime(a.scheduledAt)}</div>
        <div><span className="k">Къде</span>{a.place}</div>
        <div><span className="k">Начин</span>{word(MODE, a.mode)}</div>
        <div><span className="k">Свикано от</span>{word(OFFICE, a.convenedAs)} · <Party id={a.convenedBy} /></div>
        <div><span className="k">Покана</span>{a.noticePostedAt ? <>обявена на {sofiaTime(a.noticePostedAt)}</> : <span style={DIM}>не е обявена</span>}</div>
        <div><span className="k">Неотложно</span>{a.urgent ? <>да — {a.urgencyReason ?? <span style={FAILED}>без записана причина</span>}</> : "не"}</div>
      </div>

      <div style={{ margin: "20px 0 0", ...HEADING }}>Дневен ред</div>
      <div className="pf-card cp-card" style={{ margin: "12px 0 0" }}>
        <div className="as-agenda pf-head"><div>№</div><div>Точка</div><div>Вид</div><div>Мнозинство по</div></div>
        {a.agenda.length === 0 && <div className="as-agenda pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Дневният ред е празен.</div></div>}
        {[...a.agenda].sort((x, y) => x.ordinal - y.ordinal).map((i) => (
          <div key={i.id} className="as-agenda pf-row">
            <div className="num">{i.ordinal}</div>
            <div className="ellipsis" title={i.text}>{i.text}</div>
            <div className="mono">{i.itemType}</div>
            <div className="mono">{i.majorityRuleId}</div>
          </div>
        ))}
      </div>

      <Quorum quorum={quorum} />
      <Register attendance={attendance} proxies={proxies} />
    </>
  );
}

/** Rule: PM-GA-012 — the meter as the API weighs it: the share present against the threshold in force, never one of the page's. */
function Quorum({ quorum }: { quorum: Shown["quorum"] }) {
  return (
    <>
      <div style={{ margin: "20px 0 0", ...HEADING }}>Кворум</div>
      <div className="co-card as-facts" style={{ marginTop: 12 }}>
        {!quorum.ok ? (
          <div style={FAILED}>{unanswered(quorum.by, "кворума")}</div>
        ) : (
          <>
            <div>
              <span className="k">Заседание</span>№ {quorum.data.sessionNo} · може да започне от {sofiaTime(quorum.data.opensFrom)}
            </div>
            <div>
              <span className="k">Присъстват</span>
              <b className="mono">{quorum.data.representedPct}%</b> от идеалните части · праг <b className="mono">{quorum.data.thresholdPct}%</b>{" "}
              <span className={`badge ${quorum.data.met ? "calm" : "warn"}`}>{quorum.data.met ? "има кворум" : "няма кворум"}</span>
            </div>
            <div>
              <span className="k">Прагът</span>
              <span className="mono">{quorum.data.thresholdSource}</span>
              {!quorum.data.thresholdVerified && <span style={{ color: "#7A5210" }}> · числото чака потвърждение от юрист</span>} · към {date(quorum.data.weighedOn)}
            </div>
            {quorum.data.concentratedBy && (
              <div><span className="k">Концентрация</span><Party id={quorum.data.concentratedBy} /> притежава {quorum.data.concentratedPct}%</div>
            )}
            {quorum.data.derivedParts && <div style={{ color: "#7A5210" }}>Част от тежестта стои на идеални части, изведени от площта.</div>}
            {quorum.data.cannotWeigh.map((why) => <div key={why} style={FAILED}>Не може да се претегли: {why}</div>)}
            {quorum.data.sittings.map((s) => (
              <div key={s.id}>
                <span className="k">Заседание № {s.sessionNo}</span>
                {s.quorumMet ? "открито" : "без кворум"} · {s.representedPct}% при праг {s.thresholdPct}% · претеглено {sofiaTime(s.weighedAt)}
              </div>
            ))}
          </>
        )}
      </div>
    </>
  );
}

function Register({ attendance, proxies }: { attendance: Shown["attendance"]; proxies: Shown["proxies"] }) {
  return (
    <>
      <div style={{ margin: "20px 0 0", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span style={HEADING}>Присъствен списък и пълномощни</span>
        <span style={{ font: "400 11.5px/1 'IBM Plex Mono', monospace", ...DIM }}>лицата са с идентификатор — имената още не се подават тук</span>
      </div>
      <div className="pf-card cp-card" style={{ margin: "12px 0 0" }}>
        <div className="as-register pf-head"><div>Лице</div><div>Заседание</div><div>Начин</div><div>Брои се</div><div>Представлява</div></div>
        {!attendance.ok && <div className="as-register pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{unanswered(attendance.by, "присъствения списък")}</div></div>}
        {attendance.ok && attendance.data.length === 0 && <div className="as-register pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Никой не е вписан.</div></div>}
        {attendance.ok && attendance.data.map((l) => (
          <div key={l.id} className="as-register pf-row">
            <div><Party id={l.attendeePartyId} /></div>
            <div className="num">{l.sessionNo}</div>
            <div>{word(CHANNEL, l.channel)}{l.channel === "VIDEO" && (l.identityVerified ? " · самоличността е проверена" : " · самоличността не е проверена")}</div>
            <div>{l.counted ? "да" : <span style={DIM}>не</span>}</div>
            <div>{l.represents.length ? l.represents.map((p, i) => <span key={p}>{i > 0 && ", "}<Party id={p} /></span>) : "—"}</div>
          </div>
        ))}
      </div>
      <div className="pf-card cp-card" style={{ margin: "12px 0 0" }}>
        <div className="as-register pf-head"><div>Упълномощител</div><div>Пълномощник</div><div>Форма</div><div>За</div><div>Вписано</div></div>
        {!proxies.ok && <div className="as-register pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{unanswered(proxies.by, "пълномощните")}</div></div>}
        {proxies.ok && proxies.data.length === 0 && <div className="as-register pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма вписано пълномощно.</div></div>}
        {proxies.ok && proxies.data.map((p) => (
          <div key={p.id} className="as-register pf-row">
            <div><Party id={p.principalPartyId} /></div>
            <div><Party id={p.agentPartyId} /></div>
            <div>{word(FORM, p.form)}</div>
            <div>{word(SCOPE, p.scope)}{p.scope === "LISTED_ITEMS" && ` ${p.items.join(", ")}`}</div>
            <div className="num">{sofiaTime(p.registeredAt)}</div>
          </div>
        ))}
      </div>
    </>
  );
}
