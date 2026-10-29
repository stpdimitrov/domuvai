"use client";

import { useActionState } from "react";
import { ID_TYPE, MANAGEMENT_FORM } from "@/lib/registry";
import { registerEntrance, registerParty, type RegistryState } from "./actions";

const FORM = { margin: "16px 24px" } as const;

/**
 * An entrance registered (WEB-50) — works without a script. Offered whoever signs in: only the deployment's administrator may
 * (Rule: PM-SEC-001), the API's refusal is shown in its words and the fields kept. A new building's address, or the number of the building it joins (Rule: PM-ORG-001).
 */
// Rule: PM-ORG-001, PM-SEC-001
export function EntranceForm() {
  const [state, act, pending] = useActionState<RegistryState<{ entranceId: string; condominiumId: string }>, FormData>(registerEntrance, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <div className="wide acct-title">Регистриране на вход</div>
      <div className="wide as-hint">Дайте адрес на нова сграда или номера на сградата, към която се присъединява входът — едното от двете. Кой може да регистрира вход, решава системата.</div>
      <label>Означение<input type="text" name="label" defaultValue={v.label} maxLength={100} required autoComplete="off" /></label>
      <label>Начин на управление
        <select name="managementForm" defaultValue={v.managementForm || "GA"}>
          {Object.entries(MANAGEMENT_FORM).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Адрес на нова сграда<input type="text" name="address" defaultValue={v.address} maxLength={300} autoComplete="off" /></label>
      <label>Номер на сграда<input type="text" name="condominiumId" defaultValue={v.condominiumId} maxLength={36} autoComplete="off" /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Входът е регистриран. Номер на входа: <code>{state.done.entranceId}</code>, номер на сградата: <code>{state.done.condominiumId}</code>.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Регистрира се…" : "Регистрирай входа"}</button></div>
    </form>
  );
}

/** A party registered (WEB-50). The identity number, when given, goes to the API and is neither kept in the form nor shown back (Rule: PM-BOOK-011); who may register is the API's (Rule: PM-SEC-001). */
// Rule: PM-BOOK-011, PM-SEC-001
export function PartyForm() {
  const [state, act, pending] = useActionState<RegistryState<{ partyId: string; fullName: string }>, FormData>(registerParty, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={FORM}>
      <div className="wide acct-title">Регистриране на лице</div>
      <div className="wide as-hint">Лицето се вписва с името си; идентификаторът не е задължителен и не се показва в никакъв списък. Кой може да регистрира лице, решава системата.</div>
      <label className="wide">Пълно име<input type="text" name="fullName" defaultValue={v.fullName} maxLength={200} required autoComplete="off" /></label>
      <label>Вид на идентификатора
        <select name="idType" defaultValue={v.idType ?? ""}>
          <option value="">без</option>
          {Object.entries(ID_TYPE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Идентификатор<input type="text" name="idValue" maxLength={50} autoComplete="off" /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && <div className="wide" role="status">Лицето {state.done.fullName} е регистрирано. Номер: <code>{state.done.partyId}</code>.</div>}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Регистрира се…" : "Регистрирай лицето"}</button></div>
    </form>
  );
}
