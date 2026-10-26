/**
 * Times as a person in Sofia reads and types them (PM-SYS-004: the legal clock is Europe/Sofia's). Presentation
 * only: what an instant means in law is the API's. Pure, no import — tested on Node's own runner.
 */
const ZONE = "Europe/Sofia";
const parts = new Intl.DateTimeFormat("en-GB", {
  timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
});

/** Sofia's wall clock at [instant], as `YYYY-MM-DDTHH:MM`. */
function wall(instant: number): string {
  const p = Object.fromEntries(parts.formatToParts(new Date(instant)).map((x) => [x.type, x.value]));
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}

/**
 * The instant a Sofia wall-clock time names (`YYYY-MM-DDTHH:MM`, or `…:SS` as a `datetime-local` field with
 * `step="1"` sends it), as ISO-8601
 * in UTC — or null: not such a time, or one the clocks skip in spring. An hour the clocks repeat in autumn is its
 * first, summer, occurrence: the earlier of the two. (Nothing tells the person which one was taken; the page shows
 * the time back as Sofia's clock reads it, which is the same for both.)
 */
export function sofiaInstant(local: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(local);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map((x) => Number(x ?? 0));
  const asUtc = Date.UTC(y, mo - 1, d, h, mi, s);
  if (new Date(asUtc).toISOString().slice(0, local.length) !== local) return null;      // 2026-02-30, 24:00, :60 and the like
  // Sofia is UTC+2 or UTC+3: of the two candidate instants, those Sofia's clock shows as [local]; the earlier wins.
  const found = [3, 2].map((h) => asUtc - h * 3_600_000).filter((t) => wall(t) === local.slice(0, 16));
  return found.length ? new Date(found[0]).toISOString().replace(/\.000Z$/, "Z") : null;
}

/** An instant as Sofia's clock shows it, in the form a `datetime-local` field takes: `2026-11-05T18:30`. */
export function sofiaLocal(iso: string): string {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? "" : wall(t);
}

/** An instant as Sofia's clock shows it: `05.11.2026 18:30`. */
export function sofiaTime(iso: string): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const [date, time] = wall(t).split("T");
  return `${date.split("-").reverse().join(".")} ${time}`;
}
