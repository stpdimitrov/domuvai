/**
 * A proxy appointed for an assembly, or recorded from a written authorisation (WEB-48b). Pure: no import but `refusal`, so
 * it is tested on Node's own runner. How many owners one person may represent (PM-GA-010 ⚠) and what each kind of agent
 * needs in the way of a form (PM-GA-011 ⚠) are the API's: the web types no number and runs no validation path of its own —
 * it sends the form as typed and shows the API's answer in its words, the limit it names among them.
 */
import { undoneBy } from "./refusal.ts";

/**
 * Who the proxy is — the closed list of the API's `AgentKind` (`Proxy.kt`). The spec gives the field as a bare string (the
 * enum is not emitted, ADR-013), so the list is read from the code, not invented here; a kind outside it is the API's 400.
 */
export const AGENT_KIND: Record<string, string> = { HOUSEHOLD_MEMBER: "член на домакинството", OWNER: "друг собственик", THIRD_PARTY: "трето лице" };

/** What the proxy covers — the API's `ProxyScope` (`Proxy.kt`): the whole agenda, or the agenda items listed. */
export const SCOPE: Record<string, string> = { WHOLE_AGENDA: "целия дневен ред", LISTED_ITEMS: "точки" };

/** The form the authorisation was given in — the API's `AuthorisationForm` (`Proxy.kt`); what each needs is PM-GA-011's, the API's to judge. */
export const FORM: Record<string, string> = { WRITTEN: "писмено", NOTARISED: "с нотариална заверка", LAWYER_AUTHORISATION: "адвокатско пълномощно" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The request's `AppointProxyRequest` before the written authorisation's hash is added: `principalPartyId` only when someone is recorded for. */
export type NewProxy = { agentKind: string; agentPartyId: string; form: string; items: number[]; scope: string; principalPartyId?: string };

/**
 * Rule: PM-GA-009, PM-GA-011 — the proxy as typed: who it is, the agent, the form and the scope. The principal is the signed-in
 * person unless the manager or the board records a written authorisation handed over, naming them. The agenda items are read
 * for LISTED_ITEMS only, as numbers; whether they exist, whether the agent is who the kind says and whether the form suffices
 * are the API's (its 400 and 404, in its words). The web checks that what was typed is an id and a number.
 */
// Rule: PM-GA-009, PM-GA-011
export function proxyOf(typed: { agentKind: string; agent: string; form: string; scope: string; items: string; principal: string }): NewProxy | { refused: string } {
  const [agentKind, agent, form, scope, principal] = [typed.agentKind.trim(), typed.agent.trim(), typed.form.trim(), typed.scope.trim(), typed.principal.trim()];
  if (!Object.hasOwn(AGENT_KIND, agentKind)) return { refused: agentKind ? `„${agentKind}“ не е вид пълномощник.` : "Изберете кой е пълномощникът." };
  if (!UUID.test(agent)) return { refused: "Посочете идентификатора на пълномощника, във вида, в който го дава системата." };
  if (!Object.hasOwn(FORM, form)) return { refused: form ? `„${form}“ не е форма на пълномощното.` : "Изберете във каква форма е пълномощното." };
  if (!Object.hasOwn(SCOPE, scope)) return { refused: scope ? `„${scope}“ не е обхват на пълномощното.` : "Изберете за какво се отнася пълномощното." };
  if (principal && !UUID.test(principal)) return { refused: "Номерът на упълномощителя не е във вида, в който го дава системата." };
  const named = scope === "LISTED_ITEMS" ? typed.items.split(/[\s,;]+/).filter(Boolean) : [];
  if (named.some((n) => !/^\d{1,4}$/.test(n))) return { refused: "Точките от дневния ред се посочват с номерата им, разделени със запетая." };
  return { agentKind, agentPartyId: agent, form, items: named.map(Number), scope, ...(principal && { principalPartyId: principal }) };
}

type Answer = { error?: unknown; response: { status: number } };

/**
 * Rule: PM-GA-010, PM-GA-009, PM-SEC-001 — who may appoint or record, and past how many a person may not represent, are the API's: its
 * refusal in its words, nothing said to be recorded. A refusal that carries the limit in force (`ProxyRefused.limit`) shows it.
 */
// Rule: PM-GA-010, PM-GA-009, PM-SEC-001
export function proxyRefused(answer: Answer): string {
  const said = undoneBy(answer, "впишете пълномощно");
  const limit = typeof answer.error === "object" && answer.error !== null ? (answer.error as { limit?: unknown }).limit : undefined;
  return typeof limit === "number" ? `${said} Един човек може да представлява най-много ${limit} — според системата.` : said;
}
