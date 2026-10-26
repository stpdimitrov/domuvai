"use client";

import { useActionState } from "react";
import { reschedule, type ScheduleState } from "./actions";

/**
 * Moving the meeting (PM-GA-004, PM-GA-006): a new date, hour and place, filled with the ones in force. It works
 * without a script: the server's action answers either with the assembly's page or with the api's refusal and the
 * fields as they were filled. Whether moving it voids a posted notice is the api's answer, not this form's.
 */
export default function ScheduleForm({ entrance, assembly, noticed, scheduledAt, place }:
  { entrance: string; assembly: string; noticed: boolean; scheduledAt: string; place: string }) {
  const [state, act, pending] = useActionState<ScheduleState, FormData>(reschedule, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="wasNoticed" value={noticed ? "1" : ""} />
      <label>
        Нова дата и час (София)
        <input type="datetime-local" name="scheduledAt" defaultValue={v.scheduledAt ?? scheduledAt} required />
      </label>
      <label className="wide">
        Място
        <input type="text" name="place" defaultValue={v.place ?? place} required maxLength={300} />
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Премества се…" : "Премести събранието"}</button>
        <span className="as-hint">
          {noticed ? "Поканата вече е обявена: ако системата я обяви за невалидна, събранието се обявява отново." : "Дали остава време за покана, преценява системата по закона."}
        </span>
      </div>
    </form>
  );
}
