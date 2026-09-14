/**
 * @zues/law — the only place a legal number exists. ADR-001.
 *
 * Consumed as a pinned package, never over HTTP, so a redeploy elsewhere can
 * never change a deadline or a threshold mid-computation.
 *
 * No clock. No I/O. Every lookup takes the legal date as an argument.
 */
import raw from './constants.json' with { type: 'json' };
import { type CivilDate, isWeekend, deadline } from '@zues/kernel';

export const CATALOGUE_VERSION: string = raw.catalogue_version;
/** Bumped whenever the pure functions change, so a receipt pins the code too. ADR-001 amendment. */
export const ENGINE_VERSION = '0.1.0';

export type LegalDate = string; // YYYY-MM-DD, Europe/Sofia calendar day (PM-SYS-004)

export type Constant = {
  code: string; value: string; in_force_from: LegalDate;
  source: string; verified: boolean; rule?: string; todo_legal?: string;
};

const CONSTANTS = raw.constants as Constant[];

/**
 * The value in force on the legal date — never today's value.
 * Rule: PM-SYS-002
 */
export const constantOn = (code: string, on: LegalDate): Constant => {
  const candidates = CONSTANTS
    .filter((c) => c.code === code && c.in_force_from <= on)
    .sort((a, b) => (a.in_force_from < b.in_force_from ? 1 : -1));
  const found = candidates[0];
  if (!found) throw new Error(`no constant ${code} in force on ${on} — stop and ask (PM-SYS-001)`);
  return found;
};

export const numberOn = (code: string, on: LegalDate): number =>
  Number(constantOn(code, on).value);

/** Every constant whose number is not yet confirmed against the consolidated statute. */
export const unverified = (): Constant[] => CONSTANTS.filter((c) => !c.verified);

// ---- decision tables ------------------------------------------------------

/** Rule: PM-FEE-002, PM-FEE-003, PM-FEE-004 */
export type AllocationKey = 'PER_PERSON' | 'BY_IDEAL_PARTS' | 'PER_UNIT';

export type CostStream = 'MANAGEMENT' | 'MAINTENANCE' | 'REPAIR_FUND';

/**
 * The statutory default key per stream. The general assembly may change
 * MANAGEMENT and MAINTENANCE (PM-FEE-003); REPAIR_FUND is fixed by
 * чл. 48–50 and PM-FUND-003 and the assembly cannot move it.
 */
export const defaultKey = (stream: CostStream): AllocationKey =>
  stream === 'REPAIR_FUND' ? 'BY_IDEAL_PARTS' : 'PER_PERSON';

export const keyIsChangeableByAssembly = (stream: CostStream): boolean =>
  stream !== 'REPAIR_FUND';

// ---- non-working days & legal deadlines -----------------------------------

type NonWorkingConfig = {
  verified: boolean;
  source: string;
  todo_legal?: string;
  fixed: readonly { date: string; name: string }[];
};
const NWD = raw.non_working_days as NonWorkingConfig;

/** The verification state of the statutory non-working-day set. Rule: PM-SYS-005 */
export const nonWorkingDaysMeta = (): { verified: boolean; source: string; todo_legal?: string } =>
  ({ verified: NWD.verified, source: NWD.source, todo_legal: NWD.todo_legal });

/**
 * The statutory fixed holidays in force on a legal date, as `MM-DD → name`.
 * Rule: PM-SYS-005
 */
export const statutoryHolidaysOn = (_on: LegalDate): ReadonlyMap<string, string> =>
  // TODO(legal): PM-SYS-005 — holidays are treated as always-in-force; movable
  // Easter and the weekend-substitution rule are not yet encoded. The legal date
  // is taken now so callers need not change when holidays gain temporal bounds.
  new Map(NWD.fixed.map((h) => [h.date, h.name]));

/**
 * A legal deadline may not fall on a weekend or a statutory holiday.
 * Rule: PM-SYS-005
 */
export const isStatutoryNonWorkingDay = (d: CivilDate, on: LegalDate): boolean =>
  isWeekend(d) || statutoryHolidaysOn(on).has(d.slice(5));

/**
 * The single statutory deadline: `days` calendar days from `from`, rolled off
 * any weekend or holiday to the next working day, under the law in force on
 * `on`. The one entry point every module uses. Rule: PM-SYS-004, PM-SYS-005
 */
export const statutoryDeadline = (from: CivilDate, days: number, on: LegalDate): CivilDate =>
  deadline(from, days, (d) => isStatutoryNonWorkingDay(d, on));
