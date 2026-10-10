import assert from "node:assert/strict";
import { test } from "node:test";
import { refused, unanswered, unansweredBy } from "./refusal.ts";

const answer = (status: number, error?: string) => ({ response: { status }, ...(error !== undefined && { error: { error } }) });

test("PM-BOOK-006 a 403 is said as a refusal to this person, in the API's own words — never as nothing to show", () => {
  const said = unansweredBy(answer(403, "PM-BOOK-006: only the manager, the board and the controller read the owners of an entrance"), "собствениците на входа");
  assert.equal(said, "Нямате право да четете собствениците на входа: PM-BOOK-006: only the manager, the board and the controller read the owners of an entrance");
  assert.equal(unansweredBy(answer(403), "списъка на входовете"), "Нямате право да четете списъка на входовете: HTTP 403");   // no body: the status stands in
});

test("a 401 is a sign-in that was not accepted, and tells the person what to do", () => {
  assert.equal(unansweredBy(answer(401), "задълженията на входа"), "Входът ви не беше приет за задълженията на входа. Излезте и влезте отново.");
  assert.equal(unansweredBy(answer(401, "ignored"), "фонда"), "Входът ви не беше приет за фонда. Излезте и влезте отново.");
});

test("no answer is the backend's silence; any other refusal is shown as it came", () => {
  assert.equal(unansweredBy(null, "дневника"), "Бекендът не подаде дневника.");
  assert.equal(unansweredBy(undefined, "дневника"), "Бекендът не подаде дневника.");
  assert.equal(unanswered(null, "дневника"), "Бекендът не подаде дневника.");
  for (const status of [400, 404, 409, 422, 500, 503]) {
    assert.equal(unansweredBy(answer(status, "the reason"), "отчетите"), "API отказа отчетите: the reason", String(status));
    assert.equal(unansweredBy(answer(status), "отчетите"), `API отказа отчетите: HTTP ${status}`, String(status));
  }
  assert.deepEqual(refused(answer(409, "already issued")), { status: 409, message: "already issued" });
});

test("the rule the API names apart from its words leads them; a body with no words leaves the status to stand in", () => {
  const body = (error: unknown) => ({ response: { status: 403 }, error });
  // the book's own 403, as the registry sends it
  assert.equal(
    unansweredBy(body({ error: "the book is read by the manager, the board and the controller of this entrance", rule: "PM-BOOK-006" }), "книгата"),
    "Нямате право да четете книгата: PM-BOOK-006: the book is read by the manager, the board and the controller of this entrance",
  );
  assert.equal(refused(body({ error: "PM-BOOK-006 says no", rule: "PM-BOOK-006" })).message, "PM-BOOK-006 says no");      // named once is enough
  assert.equal(refused(body({ rule: "PM-BOOK-006" })).message, "PM-BOOK-006");
  for (const empty of [undefined, null, "", "text", 7, {}, { error: "" }, { error: "   " }, { error: {} }, { error: 7 }, { error: null }, { error: ["x"] }]) {
    assert.equal(refused(body(empty)).message, "HTTP 403", JSON.stringify(empty));
  }
});
