"use client";

import { useActionState } from "react";
import { closeItem, type CloseState } from "./actions";

/**
 * Closing one item of the agenda (PM-VOTE-014, PM-VOTE-016): the api tallies its votes and stores the decision; after
 * it no vote on the item is taken, so the person confirms it first. It works without a script: the server's action
 * answers either with the assembly's page, the decision as the api stored it, or with the api's refusal.
 */
// Rule: PM-VOTE-014
// Rule: PM-VOTE-016
export default function CloseForm({ entrance, assembly, item }: { entrance: string; assembly: string; item: string }) {
  const [state, act, pending] = useActionState<CloseState, FormData>(closeItem, {});
  return (
    <form action={act} className="as-form">
      <input type="hidden" name="entrance" value={entrance} />
      <input type="hidden" name="assembly" value={assembly} />
      <input type="hidden" name="item" value={item} />
      <label className="check wide">
        <input type="checkbox" name="final" required /> Приключвам точката — гласовете се преброяват и по нея повече не се гласува
      </label>
      {state.refused && <div className="as-refused" role="alert">{state.refused}</div>}
      <div className="wide">
        <button type="submit" className="chip active" disabled={pending}>{pending ? "Приключва се…" : "Приключи точката"}</button>
        <span className="as-hint">
          Кой може да приключи точката, дали заседанието е открито днес и с какво мнозинство се решава — преценява системата.
        </span>
      </div>
    </form>
  );
}
