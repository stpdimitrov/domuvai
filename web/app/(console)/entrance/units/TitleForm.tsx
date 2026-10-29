"use client";

import { useActionState } from "react";
import { ROLE } from "@/lib/book";
import { recordTitle, type TitleState } from "./actions";

/**
 * One title recorded for a unit of the entrance (WEB-51) — works without a script. The unit is picked from the list the page reads.
 * Offered whoever signs in: only the manager or the board enter the book (Rule: PM-SEC-001, PM-BOOK-005), the API's refusal is
 * shown in its words and the fields kept. The share's range and the days (Rule: PM-ORG-005, PM-ORG-011) are the API's too.
 */
// Rule: PM-ORG-005, PM-ORG-011, PM-BOOK-005, PM-SEC-001
export function TitleForm({ entrance, units }: { entrance: string; units: { id: string; label: string }[] }) {
  const [state, act, pending] = useActionState<TitleState, FormData>(recordTitle, {});
  const v = state.typed ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <div className="wide acct-title">Право върху обект</div>
      <div className="wide as-hint">
        Правото се вписва едно по едно: кой е собственик или ползвател на обекта, с какъв дял и от кога. Дялът е десетична дроб — 1 за целия обект, 0.5 за половината.
        Дали дялът е допустим, дали датите са верни и дали лицето и обектът съществуват, решава системата; дали дяловете на съсобствениците правят 1, тук не се проверява.
        Ако началото е празно, системата вписва правото от днес.
      </div>
      {units.length === 0 && <div className="wide as-hint">Във входа няма вписан обект, върху който да се впише право.</div>}
      <label>Обект
        <select name="unit" defaultValue={v.unit ?? ""} required>
          <option value="" disabled>—</option>
          {units.map((u) => <option key={u.id} value={u.id}>{u.label}</option>)}
        </select>
      </label>
      <label>Номер на лицето<input type="text" name="party" defaultValue={v.party} maxLength={36} required autoComplete="off" /></label>
      <label>Право
        <select name="role" defaultValue={v.role || "OWN"}>
          {Object.entries(ROLE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Дял<input type="text" name="share" defaultValue={v.share} inputMode="decimal" required autoComplete="off" placeholder="1" /></label>
      <label>От (първи ден)<input type="date" name="validFrom" defaultValue={v.validFrom} /></label>
      <label>До (ден)<input type="date" name="validTo" defaultValue={v.validTo} /></label>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.done && (
        <div className="wide" role="status">
          Правото е вписано. Номер: <code>{state.done.titleId}</code>.
          {state.done.owners && (state.done.owners.length === 0
            ? <> Към {state.done.on} системата не показва лице с право върху обекта (правото може още да не е започнало или вече да е приключило).</>
            : <> Лица с право върху обекта към {state.done.on}: {state.done.owners.join("; ")}.</>)}
          {state.done.ownersRefused && <> {state.done.ownersRefused}</>}
        </div>
      )}
      <div className="wide"><button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши правото"}</button></div>
    </form>
  );
}
