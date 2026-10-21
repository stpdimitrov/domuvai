"use client";

import { useActionState } from "react";
import { convene, type ConveneState } from "./actions";

// The offices the api lets convene (PM-GA-002) and the ways it holds a meeting, as it names them.
const OFFICES = [
  { value: "MB", label: "член на управителния съвет" },
  { value: "BM", label: "управител" },
  { value: "CTL", label: "контролен съвет / контрольор" },
];
const MODES = [
  { value: "IN_PERSON", label: "присъствено" },
  { value: "VIDEO", label: "видеоконферентно" },
  { value: "HYBRID", label: "смесено" },
];

/**
 * The form for convening (PM-GA-002, PM-GA-005). It works without a script: the server's action answers either with
 * the new assembly's page or with the api's refusal, and the fields as they were filled.
 */
export default function ConveneForm({ entrance }: { entrance: string }) {
  const [state, act, pending] = useActionState<ConveneState, FormData>(convene, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <label>
        Свиквам като
        <select name="convenedAs" defaultValue={v.convenedAs || "MB"}>
          {OFFICES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </label>
      <label>
        Дата и час (София)
        <input type="datetime-local" name="scheduledAt" defaultValue={v.scheduledAt} required />
      </label>
      <label className="wide">
        Място
        <input type="text" name="place" defaultValue={v.place} required maxLength={300} />
      </label>
      <label>
        Начин
        <select name="mode" defaultValue={v.mode || "IN_PERSON"}>
          {MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
        </select>
      </label>
      <label className="check">
        <input type="checkbox" name="urgent" defaultChecked={v.urgent === "on"} /> Неотложно — по-кратък срок за поканата
      </label>
      <label className="wide">
        Защо е неотложно
        <input type="text" name="urgencyReason" defaultValue={v.urgencyReason} maxLength={1000} />
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Свиква се…" : "Свикай общо събрание"}</button>
        <span className="as-hint">Правото да свикате, срокът за поканата и неотложността ги преценява системата по закона, не този екран.</span>
      </div>
    </form>
  );
}
