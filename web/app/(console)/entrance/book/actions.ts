"use server";

import { api, type Schemas } from "@/lib/api/client";
import { reach } from "@/lib/console";
import { bookRefused, purposeOf } from "@/lib/book";

/** What the form holds after a read: the form as typed, and the book, the log, or why neither is shown. */
export type BookState = {
  input?: { on: string; purpose: string };
  book?: Schemas["CondominiumBook"];
  log?: Schemas["BookAccessView"][];
  refused?: string;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The book read as of a date, or its access log (WEB-44). Rule: PM-BOOK-007 — each read carries the purpose the person
 * typed, and none is made without one; the API enters every read, served or refused. Rule: PM-BOOK-006, PM-SEC-001 —
 * who may read is the API's, its refusal shown in its words. A POST from the browser, so the purpose is in no page URL
 * nor the browser's history, and a reload is not a read made silently; the API takes it as a query parameter. Rule: PM-BOOK-001, PM-BOOK-002 — the book is returned as the API built it.
 */
// Rule: PM-BOOK-001, PM-BOOK-002, PM-BOOK-006, PM-BOOK-007, PM-SEC-001
export async function read(_: BookState, form: FormData): Promise<BookState> {
  const text = (name: string) => (typeof form.get(name) === "string" ? (form.get(name) as string).trim() : "");
  const [entranceId, what] = [text("entrance"), text("what")];
  const input = { on: text("on"), purpose: text("purpose") };
  if (!UUID.test(entranceId)) return { input, refused: "Входът не е посочен." };
  const given = purposeOf(input.purpose);
  if ("refused" in given) return { input, refused: given.refused };
  const { purpose } = given;
  if (what === "log") {
    const answer = await reach(() =>
      api.GET("/api/registry/entrances/{entranceId}/book/access-log", { params: { path: { entranceId }, query: { purpose } } }));
    return answer?.data ? { input, log: answer.data } : { input, refused: answer ? bookRefused(answer, "дневника на книгата") : "Бекендът не отговаря." };
  }
  if (input.on && !DAY.test(input.on)) return { input, refused: `„${input.on}“ не е дата.` };
  const answer = await reach(() =>
    api.GET("/api/registry/entrances/{entranceId}/book", { params: { path: { entranceId }, query: { purpose, ...(input.on && { on: input.on }) } } }));
  return answer?.data ? { input, book: answer.data } : { input, refused: answer ? bookRefused(answer, "книгата на етажната собственост") : "Бекендът не отговаря." };
}
