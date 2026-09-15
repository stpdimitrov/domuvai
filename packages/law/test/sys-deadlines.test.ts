import { expect, test } from 'vitest';
import { deadline, isWeekend } from '@zues/kernel';
import {
  isStatutoryNonWorkingDay, statutoryDeadline, statutoryHolidaysOn, nonWorkingDaysMeta,
} from '@zues/law';

const ON = '2026-09-13';

test('PM-SYS-005 a statutory holiday on a weekday is still a non-working day', () => {
  // 1 Jan 2026 is a Thursday — a working weekday, but Нова година.
  expect(isWeekend('2026-01-01')).toBe(false);
  expect(isStatutoryNonWorkingDay('2026-01-01', ON)).toBe(true);
  expect(statutoryHolidaysOn(ON).get('01-01')).toBe('Нова година');
});

test('PM-SYS-005 the statutory deadline rolls off a holiday to the next working day', () => {
  // 31 Dec 2025 (Wed) + 1 = 1 Jan 2026 (Thu, Нова година) → 2 Jan 2026 (Fri).
  expect(statutoryDeadline('2025-12-31', 1, ON)).toBe('2026-01-02');
});

test('PM-SYS-005 statutoryDeadline is the kernel deadline wired with the statutory calendar', () => {
  const from = '2026-05-01'; // Ден на труда, a Friday
  expect(statutoryDeadline(from, 0, ON))
    .toBe(deadline(from, 0, (d) => isStatutoryNonWorkingDay(d, ON)));
  // and it differs from a weekend-only reading, because 1 May is a holiday.
  expect(statutoryDeadline(from, 0, ON)).not.toBe(deadline(from, 0, isWeekend));
});

test('PM-SYS-005 the non-working-day set is unconfirmed and carries its legal TODO', () => {
  const meta = nonWorkingDaysMeta();
  expect(meta.verified).toBe(false);
  expect(meta.todo_legal).toMatch(/PM-SYS-005/);
  expect(meta.source).toMatch(/чл\. 154/);
});
