/**
 * Mandates recorded and ended (WEB-49), and logins tied to a party and untied (WEB-49b). Pure: no import but `refusal`, so it is
 * tested on Node's own runner. How long a mandate may run (PM-GOV-004), when an election is due (PM-GOV-005) and who may
 * administer any of it (PM-SEC-001) are the API's: the web types no term, does no date arithmetic and gates nothing —
 * it sends the form as typed and shows the API's answer in its words.
 */
import { undoneBy } from "./refusal.ts";

/**
 * The offices a mandate may be recorded for — the closed list `OFFICES` in `MandateAdministration.kt` (the spec gives
 * `RecordMandate.body` as a bare string, so the list is read from the code, not invented here). Anything else is the API's 400.
 */
export const OFFICE: Record<string, string> = { BM: "управител", MB: "член на управителния съвет", CTL: "контрольор", CSH: "касиер" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The request's `RecordMandate`, as typed. */
export type NewMandate = { body: string; partyId: string; protocolRef: string; validFrom: string; validTo: string };

/**
 * Rule: PM-GOV-004 — a mandate as typed: the office, the person, the protocol that gave it, and its first day and the day
 * it runs up to. Whether it exceeds the statutory term, and what it succeeds, are the API's (its 400 in its words); the web
 * checks that what was typed is an id and two days.
 */
// Rule: PM-GOV-004
export function mandateOf(t: { body: string; party: string; protocolRef: string; validFrom: string; validTo: string }): NewMandate | { refused: string } {
  const [body, partyId, protocolRef, validFrom, validTo] = [t.body.trim(), t.party.trim(), t.protocolRef.trim(), t.validFrom.trim(), t.validTo.trim()];
  if (!Object.hasOwn(OFFICE, body)) return { refused: body ? `„${body}“ не е длъжност, за която се вписва мандат.` : "Изберете длъжността." };
  if (!UUID.test(partyId)) return { refused: "Посочете номера на лицето, във вида, в който го дава системата." };
  if (!protocolRef) return { refused: "Посочете протокола, с който е даден мандатът." };
  if (!DAY.test(validFrom) || !DAY.test(validTo)) return { refused: "Посочете първия ден на мандата и деня, до който важи, като дати." };
  return { body, partyId, protocolRef, validFrom, validTo };
}

/** Rule: PM-GOV-004 — the day a mandate ended, once, and the protocol or act that ended it; the mandate is found by its id. */
// Rule: PM-GOV-004
export function endingOf(t: { mandate: string; on: string; protocolRef: string }): { mandateId: string; on: string; protocolRef: string } | { refused: string } {
  const [mandateId, on, protocolRef] = [t.mandate.trim(), t.on.trim(), t.protocolRef.trim()];
  if (!UUID.test(mandateId)) return { refused: "Посочете номера на мандата, във вида, в който го дава системата." };
  if (!DAY.test(on)) return { refused: on ? `„${on}“ не е дата.` : "Посочете деня, в който мандатът е приключил." };
  if (!protocolRef) return { refused: "Посочете протокола или акта, с който мандатът е приключил." };
  return { mandateId, on, protocolRef };
}

/** The request's `TieLogin`, as typed. */
export type NewTie = { partyId: string; subject: string };

/**
 * Rule: PM-SEC-001 — a login tied to a party, as typed: the party's id and the login's subject. Whether the login or the party
 * is already tied, and whether the login is the administrator's own, are the API's (its 409 and 400 in its words).
 */
// Rule: PM-SEC-001
export function tieOf(t: { party: string; subject: string }): NewTie | { refused: string } {
  const [partyId, subject] = [t.party.trim(), t.subject.trim()];
  if (!UUID.test(partyId)) return { refused: "Посочете номера на лицето, във вида, в който го дава системата." };
  if (!subject) return { refused: "Посочете входа (субекта), който се свързва." };
  return { partyId, subject };
}

/** Rule: PM-SEC-001 — a login untied: its subject, and its issuer only when one was typed (a changed issuer URL leaves logins under the old one). */
// Rule: PM-SEC-001
export function untieOf(t: { issuer: string; subject: string }): { subject: string; issuer?: string } | { refused: string } {
  const [issuer, subject] = [t.issuer.trim(), t.subject.trim()];
  if (!subject) return { refused: "Посочете входа (субекта), който се развързва." };
  return issuer ? { subject, issuer } : { subject };
}

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-SEC-001 — only the deployment's administrator records or ends a mandate, ties or unties a login: anybody else is the API's 403, in its words, nothing said to be done. */
// Rule: PM-SEC-001
export const recordRefused = (answer: Answer): string => undoneBy(answer, "впишете мандат");
// Rule: PM-SEC-001
export const endRefused = (answer: Answer): string => undoneBy(answer, "приключите мандат");
// Rule: PM-SEC-001
export const tieRefused = (answer: Answer): string => undoneBy(answer, "свържете вход с лице");
// Rule: PM-SEC-001
export const untieRefused = (answer: Answer): string => undoneBy(answer, "развържете вход");
