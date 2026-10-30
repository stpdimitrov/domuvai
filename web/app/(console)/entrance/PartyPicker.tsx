"use client";

import { useActionState, useRef } from "react";
import { withMyself } from "@/lib/partyOption";
import { readParties, type PartiesState } from "./parties";

const WORDS = {
  owners: { read: "Избери от книгата", hint: "Вместо да въвеждате номер: прочетете лицата от книгата и изберете име в полето. Четенето се вписва в дневника на книгата." },
  mandates: { read: "Избери от мандатите", hint: "Вместо да въвеждате номер: прочетете мандатите към деня и изберете име в полето. Който вече не е на поста, не е в тях — въведете номера му." },
};

/**
 * The picker for a form's party field (WEB-52b): beside the form, never inside it, so reading submits and resets nothing typed there.
 * The field stays what it was, and the one its action reads: it names this `id` as its `list`, so what is chosen in it is the whole
 * party id, and it can still be typed. Nothing is read until the click: a page view enters nothing in the book's access log, and an
 * owner's 403 is one they asked for, shown in the API's words (Rule: PM-BOOK-006, PM-BOOK-007, PM-SEC-002). `dayField` names the
 * form's own day when the read is as of it; the read is then the day typed there at the click (Rule: PM-GA-007). `myself` is
 * `/me`'s party, offered from the start. Nothing is stored in the browser.
 */
// Rule: PM-BOOK-006, PM-BOOK-007, PM-SEC-001, PM-SEC-002, PM-GA-007
export default function PartyPicker({ id, entrance, source = "owners", role, dayField, myself, margin = "0 0 12px" }: {
  id: string; entrance: string; source?: "owners" | "mandates"; role?: "OWN"; dayField?: string; myself?: string; margin?: string;
}) {
  const [state, act, pending] = useActionState<PartiesState, FormData>(readParties, {});
  const on = useRef<HTMLInputElement>(null);
  const options = withMyself(state.options ?? [], myself);
  const asOf = () => {
    if (on.current) on.current.value = (document.querySelector<HTMLInputElement>(`input[name="${dayField}"]`)?.value ?? "").slice(0, 10);
  };
  return (
    <form action={act} onSubmit={asOf} data-picks={id} className="as-form" style={{ margin }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="source" value={source} />
      {role && <input type="hidden" name="role" value={role} />}
      {dayField && <input type="hidden" name="on" ref={on} />}
      <div className="wide">
        <button type="submit" className="chip" disabled={pending}>{pending ? "Чете се…" : WORDS[source].read}</button>
        <span className="as-hint">{WORDS[source].hint}{myself && " За себе си изберете „Аз“ в полето."}</span>
      </div>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      {state.options && (
        <div className="wide as-hint" role="status">
          {state.options.length > 0 ? "Списъкът е готов: въведете първите букви от името в полето и изберете." : "Няма лица за избор към този ден — въведете номера."}
        </div>
      )}
      {options.length > 0 && <datalist id={id}>{options.map((o) => <option key={o.partyId} value={o.partyId} label={o.label} />)}</datalist>}
    </form>
  );
}
