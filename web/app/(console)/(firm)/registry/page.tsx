import type { Metadata } from "next";
import { EntranceForm, PartyForm } from "./RegistryForms";

export const metadata: Metadata = { title: "Регистрации — Етаж" };

/**
 * Where an entrance and a party are registered (WEB-50). Every form is offered to whoever signs in: who may register an entrance
 * or a party is the API's (Rule: PM-SEC-001) — a refusal is shown in its words, nothing hidden by role.
 */
// Rule: PM-SEC-001
export default function RegistryPage() {
  return (
    <>
      <div className="topbar">
        <span className="meta">Входове и лица в книгата</span>
      </div>
      <EntranceForm />
      <PartyForm />
    </>
  );
}
