"use client";

import { useActionState } from "react";
import { OFFICE } from "@/lib/mandate";
import { end, record, tie, untie, type MandateState } from "./actions";

const FORM = { margin: "16px 24px" } as const;

/**
 * A mandate recorded from its protocol (WEB-49) — works without a script. Offered whoever signs in: only the deployment's
 * administrator may (Rule: PM-SEC-001), the API's refusal is shown in its words and the fields kept. The term a mandate may
 * run (Rule: PM-GOV-004) is the API's, and so is what it succeeds.
 */
// Rule: PM-GOV-004
export function RecordForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<MandateState<{ mandateId: string; succeeded: string[] }>, FormData>(record, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Записване на мандат</div>
      <div className="wide as-hint">Мандатът се вписва по протокола, с който е даден. Колко дълъг може да бъде и кои мандати наследява, решава системата.</div>
      <label>Длъжност
        <select name="body" defaultValue={v.body || "CTL"}>
          {Object.entries(OFFICE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Номер на лицето<input type="text" name="party" defaultValue={v.party} maxLength={36} required autoComplete="off" /></label>
      <label>От (първи ден)<input type="date" name="validFrom" defaultValue={v.validFrom} required /></label>
      <label>До (ден)<input type="date" name="validTo" defaultValue={v.validTo} required /></label>
      <label className="wide">Протокол<input type="text" name="protocolRef" defaultValue={v.protocolRef} maxLength={200} required /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && (
        <div className="wide" role="status">
          Мандатът е вписан. Номер: <code>{state.done.mandateId}</code>.{state.done.succeeded.length > 0 && <> Приключва мандатите: {state.done.succeeded.map((s) => <code key={s}>{s} </code>)}</>}
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши мандата"}</button></div>
    </form>
  );
}

/** A mandate's end (WEB-49): its id from the list above, the day, the protocol — once, and not in the future: the API's rules (Rule: PM-GOV-004). */
// Rule: PM-GOV-004
export function EndForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<MandateState<{ mandateId: string; on: string }>, FormData>(end, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Край на мандат</div>
      <label>Номер на мандата<input type="text" name="mandate" defaultValue={v.mandate} maxLength={36} required autoComplete="off" /></label>
      <label>Приключил на<input type="date" name="on" defaultValue={v.on} required /></label>
      <label className="wide">Протокол или акт<input type="text" name="protocolRef" defaultValue={v.protocolRef} maxLength={200} required /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Мандат <code>{state.done.mandateId}</code> е приключен на {state.done.on}.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Запиши края"}</button></div>
    </form>
  );
}

/** A login tied to a registered party (WEB-49b): the party's id (typed, #300) and the login's subject. Offered whoever signs in; the administrator-only refusal is the API's (Rule: PM-SEC-001). */
// Rule: PM-SEC-001
export function TieForm() {
  const [state, act, pending] = useActionState<MandateState<{ partyId: string; subject: string }>, FormData>(tie, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <div className="wide acct-title">Свързване на вход с лице</div>
      <div className="wide as-hint">Кой може да свързва входове и кои връзки се допускат, решава системата.</div>
      <label>Номер на лицето<input type="text" name="party" defaultValue={v.party} maxLength={36} required autoComplete="off" /></label>
      <label>Вход (субект)<input type="text" name="subject" defaultValue={v.subject} maxLength={200} required autoComplete="off" /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Входът <code>{state.done.subject}</code> е свързан с лице <code>{state.done.partyId}</code>.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Свързва се…" : "Свържи входа"}</button></div>
    </form>
  );
}

/** A login untied (WEB-49b): its subject; the issuer only to clear what a changed issuer URL left. A login tied to none is said so (Rule: PM-SEC-001). */
// Rule: PM-SEC-001
export function UntieForm() {
  const [state, act, pending] = useActionState<MandateState<{ untied: boolean; subject: string }>, FormData>(untie, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <div className="wide acct-title">Развързване на вход</div>
      <label>Вход (субект)<input type="text" name="subject" defaultValue={v.subject} maxLength={200} required autoComplete="off" /></label>
      <label>Издател (по избор)<input type="text" name="issuer" defaultValue={v.issuer} maxLength={500} autoComplete="off" /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && (
        <div className="wide" role="status">
          {state.done.untied ? <>Входът <code>{state.done.subject}</code> е развързан.</> : <>Входът <code>{state.done.subject}</code> не е бил свързан с лице — нищо не е променено.</>}
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Развързва се…" : "Развържи входа"}</button></div>
    </form>
  );
}
