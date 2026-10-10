/**
 * How an answer the API did not give is said (WEB-24) — one wording for every screen. The API decides who may read
 * what (Rule: PM-BOOK-006) and the web shows its decision as what it is: a 403 is "you may not read this", in the
 * API's own words, never an empty list or a dash; a 401 is a sign-in that was not accepted; anything else is shown
 * as it came. No answer at all is the backend not answering. Pure: no import, so it is tested on Node's own runner.
 */
export type Refused = { status: number; message: string };

type Answer = { error?: unknown; response: { status: number } };
const words = (value: unknown): string => (typeof value === "string" ? value.trim() : "");

/**
 * The API's refusal, from an answer that carries no data: its own words, led by the rule it names when it names one
 * apart from them (`rule`, as the book's 403 does). A body with no words leaves the status to stand in.
 */
export const refused = (answer: Answer): Refused => {
  const body = typeof answer.error === "object" && answer.error !== null ? (answer.error as { error?: unknown; rule?: unknown }) : {};
  const [said, rule] = [words(body.error), words(body.rule)];
  const message = said && rule && !said.includes(rule) ? `${rule}: ${said}` : said || rule || `HTTP ${answer.response.status}`;
  return { status: answer.response.status, message };
};

/** [what] is named in the accusative, definite: „собствениците на входа“, „списъка на входовете“. */
export function unanswered(by: Refused | null, what: string): string {
  if (!by) return `Бекендът не подаде ${what}.`;
  if (by.status === 403) return `Нямате право да четете ${what}: ${by.message}`;
  if (by.status === 401) return `Входът ви не беше приет за ${what}. Излезте и влезте отново.`;
  return `API отказа ${what}: ${by.message}`;
}

/** The same, straight from a call: `null` when the backend did not answer at all. */
export const unansweredBy = (answer: Answer | null | undefined, what: string): string =>
  unanswered(answer ? refused(answer) : null, what);
