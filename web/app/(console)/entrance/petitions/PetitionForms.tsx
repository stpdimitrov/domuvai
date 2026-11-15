"use client";

import Link from "next/link";
import { useActionState } from "react";
import { MODE } from "@/lib/petition";
import { convene, open, sign, type ConveningState, type PetitionState } from "./actions";

const FORM = { margin: "16px 24px" } as const;

/** A petition opened (WEB-48) — works without a script. Opening signs it; who may open is the API's (Rule: PM-GA-003, PM-SEC-001). */
// Rule: PM-GA-003
export function OpenForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<PetitionState, FormData>(open, {});
  return (
    <form action={act} key={JSON.stringify(state.values ?? null)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Откриване на петиция</div>
      <div className="wide as-hint">
        Собствениците поискват свикване на общо събрание, като подпишат петиция (чл. 12 ЗУЕС). Откриването е и първият подпис. Коя част от идеалните части
        е нужна и колко е събрана казва системата.
      </div>
      <label className="wide">Какво да обсъди събранието<input type="text" name="subject" defaultValue={state.values?.subject} maxLength={1000} required /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Открива се…" : "Открий петицията"}</button></div>
    </form>
  );
}

/** A petition signed (WEB-48): one button; the signed-in owner signs for themself, once — the API's rule (Rule: PM-GA-003). */
// Rule: PM-GA-003
export function SignForm({ entrance, petition }: { entrance: string; petition: string }) {
  const [state, act, pending] = useActionState<PetitionState, FormData>(sign, {});
  return (
    <form action={act} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="petition" value={petition} />
      <div className="wide acct-title">Подпис</div>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Подписва се…" : "Подпиши петицията"}</button></div>
    </form>
  );
}

/**
 * The owners convene on their petition (WEB-48) — offered whatever the weighing says: the right to convene, the day and the
 * notice are the API's, and its refusal is shown in its words, the form kept (Rule: PM-GA-003, PM-SEC-001).
 */
// Rule: PM-GA-003
export function ConveneOnPetitionForm({ entrance, petition }: { entrance: string; petition: string }) {
  const [state, act, pending] = useActionState<ConveningState, FormData>(convene, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="petition" value={petition} />
      <div className="wide acct-title">Свикване по петицията</div>
      <div className="wide as-hint">Ако искането не е уважено, подписалите могат да свикат събранието сами. Дали петицията е отключена, решава системата.</div>
      <label className="wide">Как искането не е било уважено<input type="text" name="demandUnmet" defaultValue={v.demandUnmet} maxLength={1000} required /></label>
      <label>Дата и час (София)<input type="datetime-local" name="scheduledAt" defaultValue={v.scheduledAt} required /></label>
      <label>Начин
        <select name="mode" defaultValue={v.mode || "IN_PERSON"}>
          {Object.entries(MODE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className="wide">Място<input type="text" name="place" defaultValue={v.place} maxLength={300} required /></label>
      <label className="check"><input type="checkbox" name="urgent" /> Неотложно</label>
      <label className="wide">Защо е неотложно<input type="text" name="urgencyReason" defaultValue={v.urgencyReason} maxLength={1000} /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.convened && (
        <div className="wide" role="status">
          Събранието е свикано като {state.convened.convenedAs} ({state.convened.status}): събрани {state.convened.heldPct}% при праг {state.convened.thresholdPct}%.{" "}
          <Link href={`/entrance/assembly?entrance=${entrance}&assembly=${state.convened.assemblyId}`}>Към събранието</Link>
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Свиква се…" : "Свикай събранието"}</button></div>
    </form>
  );
}
