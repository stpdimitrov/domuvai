"use client";

import { useActionState, useEffect, useState } from "react";
import { askedOf, debtLabel, designationOf, offerFor, worthAsking, type Offered } from "@/lib/openDebts";
import { recordPayment, type PaymentState } from "./actions";
import { openDebtsOf } from "./offers";

/** The accounts a payment is received into, the API's codes in words. */
const INTO = { OPERATING: "Оперативна сметка", REPAIR_RENEWAL: "Сметка на фонд „Ремонт и обновяване“", CASH: "В брой" };

/**
 * A payment received, recorded (WEB-39). Works without a script — the server's action takes the person to the
 * payment's allocation, or answers with the API's refusal and the form as it was, under the same key. The form
 * allocates nothing: oldest debt first, or the debt the payer named, is the API's. The debt is named from a list
 * (WEB-53): the unit's open debts for the payment's date, read from the API as the unit or the date changes; with
 * no script, or no list, nothing is named and the payment goes oldest first.
 */
// Rule: PM-DEBT-008, PM-SEC-001
export default function PaymentForm({ entrance, units, unread, today, mintedKey }: {
  entrance: string; units: { id: string; designation: string }[]; unread?: string; today: string; mintedKey: string;
}) {
  const [state, act, pending] = useActionState<PaymentState, FormData>(recordPayment, {});
  const input = state.input;
  const [unit, setUnit] = useState(input?.unit ?? "");
  const [valueDate, setValueDate] = useState(input?.valueDate ?? today);
  const [chosen, setChosen] = useState(input?.designated ?? "");
  const [offer, setOffer] = useState<{ asked: string; offered: Offered } | null>(null);
  const [scripted, setScripted] = useState(false);   // false until the page has loaded its script: with none, the button is never held
  useEffect(() => setScripted(true), []);
  const asked = askedOf(unit, valueDate);
  useEffect(() => {
    if (!worthAsking(unit, valueDate)) return;
    let current = true;
    const answer = (offered: Offered) => current && setOffer({ asked, offered });
    openDebtsOf(unit, valueDate).then(answer, () => answer({ unread: "Системата не отговори на четенето на откритите задължения на обекта." }));
    return () => { current = false; };
  }, [unit, valueDate, asked]);
  const offered = offerFor(unit, valueDate, offer);
  const reading = scripted && worthAsking(unit, valueDate) && !offered;   // held while the list is on its way, so a payer's choice is not sent as none
  return (
    <form action={act} key={JSON.stringify(input ?? null)} className="as-form" style={{ margin: "16px 24px" }}>
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="key" value={state.key ?? mintedKey} />
      <div className="wide acct-title">Записване на получено плащане</div>
      <div className="wide as-hint">
        Плащането се отнася към най-старото задължение на обекта, освен ако платецът е посочил друго (PM-DEBT-008) — разпределението прави системата и
        го показва след записа. Записва се получено плащане — системата не превежда и не събира пари.
      </div>
      {unread && <div className="wide as-refused" role="alert">{unread}</div>}
      <label>Обект
        <select name="unit" value={unit} onChange={(e) => { setUnit(e.target.value); setChosen(""); }}>
          <option value="">— изберете</option>
          {units.map((u) => <option key={u.id} value={u.id}>{u.designation}</option>)}
        </select>
      </label>
      <label>Сума, €<input name="amount" defaultValue={input?.amount} placeholder="0,00" inputMode="decimal" /></label>
      <label>Дата на плащането<input type="date" name="valueDate" value={valueDate} onChange={(e) => { setValueDate(e.target.value); setChosen(""); }} /></label>
      <label>Постъпило в
        <select name="receivedInto" defaultValue={input?.receivedInto ?? "OPERATING"}>
          {Object.entries(INTO).map(([code, w]) => <option key={code} value={code}>{w} ({code})</option>)}
        </select>
      </label>
      <label className="wide">Посочено от платеца задължение (по избор)
        <select name="designated" value={designationOf(chosen, offered)} onChange={(e) => setChosen(e.target.value)}>
          <option value="">— най-старото първо (по подразбиране)</option>
          {offered && "debts" in offered && offered.debts.map((d) => <option key={d.debtDate} value={d.debtDate}>{debtLabel(d)}</option>)}
        </select>
      </label>
      <div className="wide as-hint" role="status">
        {!worthAsking(unit, valueDate) && "Изберете обект и дата на плащането, за да видите задълженията, които може да посочите."}
        {worthAsking(unit, valueDate) && !offered && "Задълженията на обекта се четат…"}
        {offered && "unread" in offered && <>{offered.unread} Плащането пак може да се запише — към най-старото задължение.</>}
        {offered && "debts" in offered && offered.debts.length === 0 && "Към тази дата обектът няма открито задължение."}
      </div>
      {state.refused && <div className="wide as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending || reading}>{pending ? "Записва се…" : "Запиши плащането"}</button>
      </div>
    </form>
  );
}
