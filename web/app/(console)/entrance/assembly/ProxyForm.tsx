"use client";

import { useActionState } from "react";
import { AGENT_KIND, FORM, SCOPE } from "@/lib/proxy";
import { appointProxy, type ProxyState } from "./actions";

/**
 * A proxy appointed, or recorded from a written authorisation (PM-GA-009, PM-GA-010, PM-GA-011) — works without a script.
 * Offered whoever signs in: the principal appoints their own; the manager, the board or the controller names the principal
 * and attaches the document handed over, of which only the SHA-256 is kept. Who may, how many one person may represent and
 * what each kind of agent needs are the API's; its refusal is shown in its words, the fields kept (the file chosen again).
 */
// Rule: PM-GA-009
// Rule: PM-GA-010
// Rule: PM-GA-011
export default function ProxyForm({ entrance, assembly, parties }: { entrance: string; assembly: string; parties: string }) {
  const [state, act, pending] = useActionState<ProxyState, FormData>(appointProxy, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <label>
        Пълномощник
        <select name="agentKind" defaultValue={v.agentKind || "OWNER"}>
          {Object.entries(AGENT_KIND).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>
        Идентификатор на пълномощника
        <input type="text" name="agentPartyId" list={parties} defaultValue={v.agentPartyId} required placeholder="идентификатор на лицето" maxLength={36} />
      </label>
      <label>
        Форма
        <select name="form" defaultValue={v.form || "WRITTEN"}>
          {Object.entries(FORM).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>
        Обхват
        <select name="scope" defaultValue={v.scope || "WHOLE_AGENDA"}>
          {Object.entries(SCOPE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className="wide">
        Точки от дневния ред (само за „точки“)
        <input type="text" name="items" defaultValue={v.items} placeholder="например 1, 3" maxLength={100} />
      </label>
      <label className="wide">
        Упълномощител (само когато записвате чуждо пълномощно)
        <input type="text" name="principalPartyId" list={parties} defaultValue={v.principalPartyId} placeholder="оставете празно, за да упълномощите себе си" maxLength={36} />
      </label>
      <label className="wide">
        Писмено пълномощно (само когато записвате чуждо)
        <input type="file" name="authorisation" />
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши пълномощното"}</button>
        <span className="as-hint">
          Собственикът или ползвателят упълномощава сам себе си. Управителят, съветът или контролът записват предаденото им писмено
          пълномощно — от файла се пази отпечатъкът му (SHA-256). Колко души може да представлява един човек и каква форма се иска, решава системата.
        </span>
      </div>
    </form>
  );
}
