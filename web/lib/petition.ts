/**
 * An assembly petition opened, read, signed and convened on (WEB-48). Pure: no import but `refusal` and `sofia`, so it
 * is tested on Node's own runner. The share the signatories own, the threshold and whether the petition is unlocked
 * are the API's (PM-GA-003 ⚠): the web types no percentage and weighs nothing — it shows what the API answered.
 */
import { undoneBy } from "./refusal.ts";
import { sofiaInstant } from "./sofia.ts";

/** How a meeting is held, as the API names it (`ConveneOnPetition.mode`; the same list the convening form offers). */
export const MODE: Record<string, string> = { IN_PERSON: "присъствено", VIDEO: "видеоконферентно", HYBRID: "смесено" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A petition is found by its id alone — no list is served, so the id is the one opening it answered, typed. */
export const petitionIdOf = (typed: string | undefined): string | null => (UUID.test((typed ?? "").trim()) ? (typed as string).trim() : null);

/** Rule: PM-GA-003 — a petition is opened on a subject; whether the person may open one (an owner) is the API's. */
// Rule: PM-GA-003
export function subjectOf(typed: string): { subject: string } | { refused: string } {
  const subject = typed.trim();
  return subject ? { subject } : { refused: "Напишете какво искате да обсъди събранието." };
}

/** The request's `ConveneOnPetition` as typed; the time is Sofia's wall clock (PM-SYS-004), every judgement of it the API's. */
export type Convening = { demandUnmet: string; mode: string; place: string; scheduledAt: string; urgent: boolean; urgencyReason?: string };

/**
 * Rule: PM-GA-003 — the owners convene on their petition: how the demand went unmet, where and when. Whether the petition is
 * unlocked, whether the demand was in fact unmet and whether the person signed it is the API's, in its words.
 */
// Rule: PM-GA-003
export function conveningOf(t: { demandUnmet: string; mode: string; place: string; scheduledAt: string; urgent: boolean; urgencyReason: string }): Convening | { refused: string } {
  const [demandUnmet, place, urgencyReason] = [t.demandUnmet.trim(), t.place.trim(), t.urgencyReason.trim()];
  if (!demandUnmet) return { refused: "Напишете как искането не е било уважено." };
  if (!place) return { refused: "Посочете място." };
  if (!(t.mode in MODE)) return { refused: "Изберете начин на провеждане." };
  const scheduledAt = sofiaInstant(t.scheduledAt.trim());
  if (!scheduledAt) return { refused: "Посочете дата и час, каквито ги показва часовникът в София — този час не съществува или не е попълнен." };
  return { demandUnmet, mode: t.mode, place, scheduledAt, urgent: t.urgent, ...(urgencyReason && { urgencyReason }) };
}

type Answer = { error?: unknown; response: { status: number } };

/** Rule: PM-GA-003, PM-SEC-001 — who may open, sign or convene on a petition is the API's: a refusal is shown in its words, nothing said to be done. */
// Rule: PM-GA-003, PM-SEC-001
export const openRefused = (answer: Answer): string => undoneBy(answer, "откриете петиция");
// Rule: PM-GA-003, PM-SEC-001
export const signingRefused = (answer: Answer): string => undoneBy(answer, "подпишете петицията");
// Rule: PM-GA-003, PM-SEC-001
export const conveningRefused = (answer: Answer): string => undoneBy(answer, "свикате събрание по петицията");
