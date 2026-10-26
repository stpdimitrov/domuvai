"use client";

import { useActionState } from "react";
import { postNotice, type NoticeState } from "./actions";

/**
 * The posting act (PM-GA-004, PM-GA-007, PM-SYS-013): when the notice was posted at the entrance, the owner who
 * co-signs the act, and the photograph of the posted notice. It works without a script: the server's action answers
 * either with the assembly's page, now noticed, or with the api's refusal and the fields as they were filled.
 */
export default function NoticeForm({ entrance, assembly }: { entrance: string; assembly: string }) {
  const [state, act, pending] = useActionState<NoticeState, FormData>(postNotice, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <label>
        Обявена на (София)
        <input type="datetime-local" step="1" name="postedAt" defaultValue={v.postedAt} required />
      </label>
      <label>
        Подписва и собственик
        <input type="text" name="coSignatoryPartyId" defaultValue={v.coSignatoryPartyId} required placeholder="идентификатор на лицето" maxLength={36} />
      </label>
      <label className="wide">
        Снимка на обявената покана
        <input type="file" name="photo" accept="image/*" required />
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши обявяването"}</button>
        <span className="as-hint">
          Поканата е обявена, когато е вписан протоколът за обявяване — не когато е изпратено съобщение. Срокът до събранието и
          правото да подпишете ги преценява системата по закона. От снимката се пази отпечатъкът ѝ (SHA-256).
        </span>
      </div>
    </form>
  );
}
