/**
 * A fund handover statement as the person typed it, made the API's body (WEB-43), and a statement read back checked
 * against its own hash. Pure: no import but `minorOf`, `refusal` and Node's own hash, so it is tested on Node's own
 * runner. The statement is issued, not signed: the parties' signatures wait on #57 and the signature provider (owner,
 * 2026-10-28), and nothing here calls a statement signed, agreed, accepted or final.
 */
import { createHash } from "node:crypto";
import { minorOf } from "./money.ts";
import { refused, undone } from "./refusal.ts";

/** The one line every statement carries, in the list and opened, on both screens (PM-FUND-010: the acceptance's "signed by both parties" is not met yet). */
export const NOT_SIGNED = "Не е подписан от страните — подписването чака избора на доставчик на квалифициран електронен подпис (#57).";

export type HandoverInput = { handoverOn: string; from: string; outgoing: string; incoming: string; bankBalance: string };
export type HandoverBody = { handoverOn: string; from?: string; outgoingPartyId: string; incomingPartyId: string; bankBalanceMinor: number };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Rule: PM-FUND-010 — the handover date, an optional period start, the two sides as party ids (until #300 serves names)
 * and the balance on the bank's own statement for that day. The bank balance is required and never defaulted from the
 * book: a figure copied from the book would always reconcile. Whether the two sides differ, whether each is registered
 * and whether the date is after today are the API's (its 400 and 409).
 */
// Rule: PM-FUND-010, PM-FEE-016
export function handoverOf(input: HandoverInput): HandoverBody | { refused: string } {
  const [handoverOn, from, outgoing, incoming, balance] = [input.handoverOn, input.from, input.outgoing, input.incoming, input.bankBalance].map((s) => s.trim());
  if (!handoverOn) return { refused: "Посочете датата на предаване." };
  if (!balance) return { refused: "Посочете салдото по банковия извлечение за деня — то не се взема от книгата, иначе отчетът винаги би съвпадал." };
  const bankBalanceMinor = minorOf(balance);
  if (bankBalanceMinor == null) return { refused: `„${balance}“ не е сума в евро — например 570 или 570,00.` };
  if (!UUID.test(outgoing)) return { refused: "Посочете предаващата страна — идентификатор на лице." };
  if (!UUID.test(incoming)) return { refused: "Посочете приемащата страна — идентификатор на лице." };
  return { handoverOn, ...(from && { from }), outgoingPartyId: outgoing, incomingPartyId: incoming, bankBalanceMinor };
}

/** Whether [basis] is the text [basisHash] was made from: lowercase hex SHA-256 of its UTF-8 bytes — anyone can repeat it from the text shown. */
// Rule: PM-FUND-010
export const matches = (basis: string, basisHash: string): boolean => createHash("sha256").update(basis, "utf8").digest("hex") === basisHash;

/** Rule: PM-SEC-001 — who may issue a statement is the API's (the manager or the board): its 403, 400 and 409 are shown in its words. */
// Rule: PM-SEC-001
export const handoverRefused = (answer: { error?: unknown; response: { status: number } }): string =>
  undone(refused(answer), "издадете отчет при предаване на фонда");
