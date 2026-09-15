import { expect, test } from 'vitest';
import {
  addCalendarDays, weekday, isWeekend, rollToWorkingDay, deadline, toSofiaDate,
  STATUTORY_LANGUAGE, DEFAULT_UI_LANGUAGE, assertStatutoryLanguage,
} from '@zues/kernel';

// ---------------------------------------------------------------- PM-SYS-003
test('PM-SYS-003 statutory documents are produced in Bulgarian; English is optional', () => {
  expect(STATUTORY_LANGUAGE).toBe('bg');
  expect(DEFAULT_UI_LANGUAGE).toBe('bg');
  expect(() => assertStatutoryLanguage('bg')).not.toThrow();
  expect(() => assertStatutoryLanguage('en')).toThrow(/must be produced in bg/);
});

// ---------------------------------------------------------------- PM-SYS-004
test('PM-SYS-004 seven calendar days across the DST change is still seven days', () => {
  // Europe/Sofia springs forward on 2026-03-29; the civil span must not slip.
  expect(addCalendarDays('2026-03-25', 7)).toBe('2026-04-01');
  expect(addCalendarDays('2026-02-28', 1)).toBe('2026-03-01'); // 2026 is not a leap year
});

test('PM-SYS-004 an instant stored in UTC resolves to its Europe/Sofia civil day', () => {
  // 22:30Z on New Year's Eve is already 00:30 on 1 Jan in Sofia (UTC+2 in winter).
  expect(toSofiaDate(new Date('2026-01-01T22:30:00Z'))).toBe('2026-01-02');
  // 21:30Z at midsummer is 00:30 next day in Sofia (UTC+3 in summer).
  expect(toSofiaDate(new Date('2026-06-30T21:30:00Z'))).toBe('2026-07-01');
});

test('PM-SYS-004 a malformed or impossible date is rejected, not silently coerced', () => {
  expect(() => addCalendarDays('2026-02-30', 1)).toThrow(/no such calendar day/);
  expect(() => addCalendarDays('01-01-2026', 1)).toThrow(/ISO YYYY-MM-DD/);
});

// ---------------------------------------------------------------- PM-SYS-005
test('PM-SYS-005 a deadline landing on a weekend rolls to the next working day', () => {
  expect(weekday('2026-01-03')).toBe(6);       // Saturday
  expect(isWeekend('2026-01-03')).toBe(true);
  expect(isWeekend('2026-01-05')).toBe(false); // Monday
  // 2 Jan (Fri) + 1 day = 3 Jan (Sat) → rolls to 5 Jan (Mon).
  expect(deadline('2026-01-02', 1, isWeekend)).toBe('2026-01-05');
  // a working-day deadline is untouched.
  expect(deadline('2026-01-05', 1, isWeekend)).toBe('2026-01-06');
});

test('PM-SYS-005 calendar days require a whole number, and a bad predicate cannot loop forever', () => {
  expect(() => addCalendarDays('2026-01-01', 1.5)).toThrow(/whole number/);
  expect(() => rollToWorkingDay('2026-01-01', () => true)).toThrow(/within a year/);
});
