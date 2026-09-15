/**
 * Civil-date arithmetic for legal deadlines. Pure: no clock, no I/O — every
 * function takes its date as an argument, so a deadline never depends on when
 * the code happens to run. Rule: PM-SYS-004
 *
 * A CivilDate is a Europe/Sofia calendar day in ISO `YYYY-MM-DD` form — the same
 * string shape as @zues/law's LegalDate. Deadlines are reasoned about as civil
 * days, never as instants, so no timezone hour can slip across a DST change.
 * Rule: PM-SYS-004
 */
export type CivilDate = string;

const ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;

/** Parse + validate to the UTC-midnight epoch ms of the civil day. */
const toEpochMs = (d: CivilDate): number => {
  const m = ISO.exec(d);
  if (!m) throw new TypeError(`date must be ISO YYYY-MM-DD, got "${d}" (PM-SYS-004)`);
  const y = Number(m[1]), mo = Number(m[2]), day = Number(m[3]);
  const t = Date.UTC(y, mo - 1, day);
  const b = new Date(t);
  // Reject 2026-02-30, 2026-13-01 and friends rather than silently rolling them.
  if (b.getUTCFullYear() !== y || b.getUTCMonth() !== mo - 1 || b.getUTCDate() !== day) {
    throw new RangeError(`no such calendar day: "${d}" (PM-SYS-004)`);
  }
  return t;
};

const fromEpochMs = (t: number): CivilDate => {
  const b = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${b.getUTCFullYear()}-${p(b.getUTCMonth() + 1)}-${p(b.getUTCDate())}`;
};

/**
 * Add whole calendar days on the civil calendar. Never converts to wall-clock
 * time, so a span crossing a daylight-saving change is still exactly `days`.
 * Rule: PM-SYS-004, PM-SYS-005
 */
export const addCalendarDays = (from: CivilDate, days: number): CivilDate => {
  if (!Number.isInteger(days)) throw new TypeError(`days must be a whole number, got ${days} (PM-SYS-005)`);
  return fromEpochMs(toEpochMs(from) + days * DAY_MS);
};

/** Day of week for the civil date: 0 = Sunday … 6 = Saturday. */
export const weekday = (d: CivilDate): number => new Date(toEpochMs(d)).getUTCDay();

const SUNDAY = 0, SATURDAY = 6;
/** Saturday or Sunday — a civil-calendar fact, not a statutory number. */
export const isWeekend = (d: CivilDate): boolean => {
  const w = weekday(d);
  return w === SUNDAY || w === SATURDAY;
};

/**
 * A predicate naming the days a legal deadline may not fall on. Statutory
 * holidays are injected (from @zues/law) so this stays pure and law-free.
 * Rule: PM-SYS-005
 */
export type NonWorkingDay = (d: CivilDate) => boolean;

/** Move forward to the first working day on or after `d`. Rule: PM-SYS-005 */
export const rollToWorkingDay = (d: CivilDate, isNonWorking: NonWorkingDay): CivilDate => {
  let cur: CivilDate = fromEpochMs(toEpochMs(d)); // normalise + validate
  let guard = 0;
  while (isNonWorking(cur)) {
    cur = addCalendarDays(cur, 1);
    if (++guard > 366) throw new Error(`no working day within a year of ${d} — check the predicate (PM-SYS-005)`); // not-legal: loop safety bound
  }
  return cur;
};

/**
 * The one deadline utility. Add `days` calendar days (PM-SYS-005: calendar days
 * unless the statute says otherwise), then, if it lands on a non-working day,
 * roll to the next working day. Use this everywhere; do not re-derive it.
 * Rule: PM-SYS-004, PM-SYS-005
 */
export const deadline = (from: CivilDate, days: number, isNonWorking: NonWorkingDay): CivilDate =>
  rollToWorkingDay(addCalendarDays(from, days), isNonWorking);

/**
 * The Europe/Sofia calendar day of an instant. Instants are stored in UTC; legal
 * reasoning happens on the Sofia civil day (PM-SYS-004). Pure: the instant is an
 * argument, never `Date.now()`. Rule: PM-SYS-004
 */
export const toSofiaDate = (instant: Date): CivilDate => {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Sofia', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(instant);
  const get = (t: string) => parts.find((p) => p.type === t)!.value;
  return `${get('year')}-${get('month')}-${get('day')}`;
};
