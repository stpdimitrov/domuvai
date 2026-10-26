"use client";

import { useActionState } from "react";
import { addAgendaItem, type AgendaState } from "./actions";

// The item types the api binds to a majority (PM-VOTE-002, PM-VOTE-004), as it names them. Which majority each takes,
// and whether counsel has confirmed it, is the api's to say when the item is added — never this list's.
const TYPES = [
  { value: "GENERAL", label: "общ въпрос" },
  { value: "COMMON_PART_USE_RIGHT", label: "право на ползване или смяна на предназначението на общи части" },
];

/**
 * The form for one agenda item (PM-GA-006, PM-VOTE-004). It works without a script: the server's action answers
 * either with the assembly's page, the item on its agenda, or with the api's refusal and the fields as they were filled.
 */
export default function AgendaForm({ entrance, assembly }: { entrance: string; assembly: string }) {
  const [state, act, pending] = useActionState<AgendaState, FormData>(addAgendaItem, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(v)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <label className="wide">
        Точка
        <input type="text" name="text" defaultValue={v.text} required maxLength={2000} />
      </label>
      <label className="wide">
        Вид
        <select name="itemType" defaultValue={v.itemType || "GENERAL"}>
          {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
      </label>
      <label className="check wide">   {/* Rule: PM-GA-017 — the drafter tags the item; the platform keeps no statutory list */}
        <input type="checkbox" name="absenteeEligible" defaultChecked={v.absenteeEligible === "on"} /> Допуска неприсъствено гласуване до 7 дни след заседанието
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Добавя се…" : "Добави точка"}</button>
        <span className="as-hint">Мнозинството за точката, правото да я добавите и дали дневният ред е още отворен ги преценява системата по закона.</span>
      </div>
    </form>
  );
}
