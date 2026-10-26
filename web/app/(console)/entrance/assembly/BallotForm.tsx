"use client";

import { useActionState } from "react";
import { enterBallot, type BallotState } from "./actions";

/**
 * Entering an absent owner's ballot on an item tagged for absentee ballots (PM-GA-017, PM-GA-018): the owner, by party
 * id until #300, how they voted, and their handwritten-signed declaration — its reference, the SHA-256 of its scan and
 * the day it was received. It works without a script: the server's action answers either with the form emptied and the
 * word that the ballot was entered, or with the api's refusal and the fields as they were filled.
 */
// Rule: PM-GA-017
// Rule: PM-GA-018
export default function BallotForm({ entrance, assembly, item }: { entrance: string; assembly: string; item: string }) {
  const [state, act, pending] = useActionState<BallotState, FormData>(enterBallot, {});
  const v = state.values ?? {};
  return (
    <form action={act} key={JSON.stringify(state)} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="item" value={item} />
      <label>
        Отсъстващ собственик
        <input type="text" name="ownerPartyId" defaultValue={v.ownerPartyId} required placeholder="идентификатор на собственика" maxLength={36} />
      </label>
      <label>
        Глас
        <select name="choice" defaultValue={v.choice ?? "FOR"}>
          <option value="FOR">за</option>
          <option value="AGAINST">против</option>
          <option value="ABSTAIN">въздържал се</option>
        </select>
      </label>
      <label>
        Декларация — номер
        <input type="text" name="documentRef" defaultValue={v.documentRef} required maxLength={200} placeholder="входящ номер на декларацията" />
      </label>
      <label>
        Получена на
        <input type="date" name="receivedOn" defaultValue={v.receivedOn} required />
      </label>
      <label className="wide">
        SHA-256 на сканираната декларация
        <input type="text" name="documentSha256" defaultValue={v.documentSha256} required pattern="[0-9a-fA-F]{64}" maxLength={64} className="mono" />
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      {state.entered && <div className="as-note" role="status">Бюлетината е вписана.</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Вписва се…" : "Впиши бюлетината"}</button>
        <span className="as-hint">
          Само собственоръчно подписана декларация. Дали денят е в 7-дневния срок, дали собственикът не е бил представен на
          заседанието и с каква тежест — преценява системата. Вписана бюлетина не се променя.
        </span>
      </div>
    </form>
  );
}
