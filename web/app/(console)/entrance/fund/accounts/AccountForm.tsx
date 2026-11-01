"use client";

import { useActionState } from "react";
import { HOLDER, PURPOSE } from "@/lib/fundAccount";
import { registerAccount, type AccountState } from "./actions";

/**
 * An entrance's bank account registered (WEB-41). Works without a script — the server's action takes the person to
 * the list, or answers with the API's refusal and the form as it was. It offers the purposes the entrance holds no
 * account for, the fund's first (PM-FUND-001); whether the IBAN is valid or held elsewhere is the API's, and so is whether
 * the holder picked stands on a mandate (FUND-02). `parties` is the id of the picker beside the form.
 */
// Rule: PM-FUND-004, PM-SEC-001
export default function AccountForm({ entrance, purposes, parties }: { entrance: string; purposes: string[]; parties: string }) {
  const [state, act, pending] = useActionState<AccountState, FormData>(registerAccount, {});
  const input = state.input;
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Регистриране на банкова сметка</div>
      <div className="wide as-hint">
        Парите на фонда се държат в отделна банкова сметка, открита на името на председателя на управителния съвет (управителя) или на сдружението
        (чл. 50 ЗУЕС, PM-FUND-004), и не се смесват с други пари (PM-FUND-005). Системата пази само коя е сметката — тя не държи и не превежда пари.
        Сметката на фонда на името на управителя се вписва с лице, което днес е управител или член на управителния съвет, и пази мандата му.
      </div>
      <label>За какво е сметката
        <select name="purpose" defaultValue={input?.purpose ?? purposes[0]}>
          {purposes.map((code) => <option key={code} value={code}>{PURPOSE[code] ?? code} ({code})</option>)}
        </select>
      </label>
      <label>IBAN<input type="text" name="iban" defaultValue={input?.iban} placeholder="BG.." autoComplete="off" /></label>
      <label>На името на (титуляр)<input type="text" name="holderName" defaultValue={input?.holderName} /></label>
      <label>Титулярът е
        <select name="holderKind" defaultValue={input?.holderKind ?? "MANAGER"}>
          {Object.entries(HOLDER).map(([code, w]) => <option key={code} value={code}>{w} ({code})</option>)}
        </select>
      </label>
      <label>Лицето на поста (идентификатор на лице)<input type="text" name="holderPartyId" list={parties} defaultValue={input?.holderPartyId} /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Записва се…" : "Регистрирай сметката"}</button>
      </div>
    </form>
  );
}
