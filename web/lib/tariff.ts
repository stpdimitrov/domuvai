/**
 * A tariff version as the person typed it, made the API's `RecordTariffVersion` body (WEB-61). Pure: no import but
 * `minorOf`, so it is tested on Node's own runner. Euro figures are typed in euro and become integer minor units by digit
 * arithmetic — no float reaches money (PM-FEE-016). The months go out as typed: whether they are valid, whether a version
 * already begins that month and whether the decision is the entrance's passed one are the API's (400, 409).
 */
import type { components } from "./api/schema.d.ts";
import { minorOf } from "./money.ts";

type Schemas = components["schemas"];
export type TariffBody = Schemas["RecordTariffVersion"];

/** The streams and keys a line may have, the API's codes in words (the API's own list; it refuses any other). */
export const STREAMS: Record<string, string> = { MANAGEMENT: "управление", MAINTENANCE: "поддръжка на общи части", REPAIR_FUND: "фонд „Ремонт“" };
export const KEYS: Record<string, string> = { PER_PERSON: "на живущ", PER_UNIT: "на обект", BY_IDEAL_PARTS: "по идеални части" };

/** How many rows the form offers; an empty row is skipped. */
export const LINE_ROWS = 6;
export const PRICE_ROWS = 3;

/** What the form holds: one string per field, named `line-<field>-<n>`, `price-<field>-<n>`, `decision`, `validFrom`, `validTo`. */
export type TariffInput = Record<string, string>;

const field = (input: TariffInput, name: string) => (input[name] ?? "").trim();

/**
 * Rule: PM-FEE-012 — the version is the lines and metered prices the assembly adopted, under a decision of the entrance
 * chosen from its passed ones ([passed], read again on this write), never a decision typed: without one nothing is sent.
 * A line is a stream, a key, and a rate or a total in euro (exactly one, as its `kind` says); a row half filled is
 * refused, not guessed. Rule: PM-FEE-016 — every amount is integer minor units.
 */
// Rule: PM-FEE-012, PM-FEE-016
export function tariffOf(input: TariffInput, passed: string[]): TariffBody | { refused: string } {
  const decisionId = field(input, "decision");
  if (!decisionId) return { refused: "Изберете решението на общото събрание, по което е тарифата (PM-FEE-012)." };
  if (!passed.includes(decisionId)) return { refused: "Решението не е сред приетите решения на входа — изберете от списъка (PM-FEE-012)." };
  const validFrom = field(input, "validFrom");
  if (!validFrom) return { refused: "Посочете от кой месец е в сила тарифата (ГГГГ-ММ)." };
  const validTo = field(input, "validTo");

  const lines: Schemas["AdoptedLine"][] = [];
  for (let n = 0; n < LINE_ROWS; n++) {
    const [stream, key, amount, item] = ["stream", "key", "amount", "item"].map((f) => field(input, `line-${f}-${n}`));
    const kind = field(input, `line-kind-${n}`);
    if (!stream && !key && !amount && !item) continue;
    if (!(stream in STREAMS) || !(key in KEYS)) return { refused: `Ред ${n + 1}: изберете поток и ключ.` };
    const minor = minorOf(amount);
    if (minor == null) return { refused: `Ред ${n + 1}: „${amount}“ не е сума в евро — например 6 или 6,50.` };
    if (kind !== "rate" && kind !== "total") return { refused: `Ред ${n + 1}: изберете ставка или обща сума.` };
    lines.push({ stream, key, ...(kind === "rate" ? { rateMinor: minor } : { totalMinor: minor }), ...(item && { item }) });
  }

  const consumption: Schemas["MeteredPrice"][] = [];
  for (let n = 0; n < PRICE_ROWS; n++) {
    const [item, price] = ["item", "price"].map((f) => field(input, `price-${f}-${n}`));
    if (!item && !price) continue;
    const minor = minorOf(price);
    if (!item || minor == null) return { refused: `Цена на потребление ${n + 1}: посочете вид и цена в евро.` };
    consumption.push({ item, priceMinor: minor });
  }
  if (lines.length === 0 && consumption.length === 0) return { refused: "Добавете поне един ред или цена на потребление." };
  return { decisionId, validFrom, ...(validTo && { validTo }), lines, consumption };
}
