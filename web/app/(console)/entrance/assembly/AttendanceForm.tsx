"use client";

import { useActionState } from "react";
import { recordAttendance, type AttendanceState } from "./actions";

/**
 * Entering a person in the register of the sitting (PM-GA-012, PM-GA-016): the person, by party id until #300, and
 * whether they are there in person or by video — the assembly's own way preselected, both offered: a way the assembly
 * does not meet is the api's to refuse. It works without a script: the server's action answers either with the
 * assembly's page, the line on its register, or with the api's refusal and the fields as they were filled.
 */
// Rule: PM-GA-012
// Rule: PM-GA-016
export default function AttendanceForm({ entrance, assembly, mode }: { entrance: string; assembly: string; mode: string }) {
  const [state, act, pending] = useActionState<AttendanceState, FormData>(recordAttendance, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <label>
        Лице
        <input type="text" name="attendeePartyId" defaultValue={v.attendeePartyId} required placeholder="идентификатор на лицето" maxLength={36} />
      </label>
      <label>
        Присъства
        <select name="channel" defaultValue={v.channel ?? (mode === "VIDEO" ? "VIDEO" : "IN_PERSON")}>
          <option value="IN_PERSON">лично</option>
          <option value="VIDEO">по видео</option>
        </select>
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши в присъствения списък"}</button>
        <span className="as-hint">
          Вписва се собственик или представител по вписано пълномощно. Дали е денят на заседанието и кого представлява — преценява
          системата. Присъстващ по видео се брои едва след проверка на самоличността.
        </span>
      </div>
    </form>
  );
}
