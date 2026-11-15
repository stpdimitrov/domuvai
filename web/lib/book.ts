/**
 * The Book of the Condominium read, and who read it (WEB-44). Pure: no import but `refusal`, so it is tested on Node's
 * own runner. Who may read is the API's (the manager, the board and the controller): the web gates nothing, and shows a
 * refusal in the API's words. What the web does decide is that it never asks without a purpose typed by the person.
 */
import { refused, unanswered } from "./refusal.ts";

/** A party as the book lists it — the schema's `BookParty`: its id beside the name, no identity number, no address. */
export type Party = { partyId: string; name: string; role: string; share: string };

/** The roles a title records (`TitleRole`), in the page's words; another is shown as the API wrote it. */
export const ROLE: Record<string, string> = { OWN: "собственик", USR: "ползвател" };

/** What an access-log entry was (`BookAccessKind`) and how it ended (`BookAccessOutcome`), in the page's words. */
export const KIND: Record<string, string> = {
  BOOK_READ: "четене на книгата",
  LOG_EXPORT: "четене на този дневник",
  OWNERS_LIST: "списък на собствениците",
  OVERDUE_LIST: "списък на просрочените декларации",
  RETENTION: "заличаване след срока на съхранение",
};
export const OUTCOME: Record<string, string> = { SERVED: "дадено", REFUSED: "отказано", OPEN: "без вход в системата" };

/**
 * Rule: PM-BOOK-007 — every read says why: the purpose as typed, trimmed. An empty one is refused here, before any
 * call; how long a purpose may be is the API's (its 400).
 */
// Rule: PM-BOOK-007
export function purposeOf(typed: string): { purpose: string } | { refused: string } {
  const purpose = typed.trim();
  return purpose ? { purpose } : { refused: "Посочете с каква цел четете книгата — без цел тя не се чете и четенето се вписва в дневника." };
}

/**
 * Rule: PM-BOOK-011 — a party is shown by name, role and share, and by nothing else: the three fields are taken by name,
 * so a field the API might add later (an identity number, an address) is never shown by this line.
 */
// Rule: PM-BOOK-011
export const partyLine = ({ name, role, share }: Party): string => `${name} · ${ROLE[role] ?? role} · дял ${share}`;

/**
 * Rule: PM-BOOK-002 — whether the book, or a unit, is complete is the API's word: complete only where it says so, and
 * anything else — false, or a field it did not send — shown as incomplete.
 */
// Rule: PM-BOOK-002
export const completeness = (complete: unknown): { label: string; badge: "green" | "crit" } =>
  complete === true ? { label: "пълно", badge: "green" } : { label: "непълно", badge: "crit" };

/** When a log entry was made, in Sofia's time — the API's instant, 2026-10-29T08:05:00Z → 29.10.2026, 10:05:00. */
// Rule: PM-BOOK-007
export const sofiaTime = (at: string): string =>
  new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Sofia", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" })
    .format(new Date(at)).replaceAll("/", ".");

/** A date as the page shows it, 2026-10-29 → 29.10.2026. */
export const date = (iso: string): string => iso.slice(0, 10).split("-").reverse().join(".");

/** Rule: PM-BOOK-006, PM-SEC-001 — a read the API refused, in its words: a 403 is "you may not read", never an empty book. */
// Rule: PM-BOOK-006, PM-SEC-001
export const bookRefused = (answer: { error?: unknown; response: { status: number } }, what: string): string =>
  unanswered(refused(answer), what);
