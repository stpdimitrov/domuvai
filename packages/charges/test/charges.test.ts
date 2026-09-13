import { describe, expect, test } from 'vitest';
import { idealParts, eur, formatIdealParts, allocateByWeight, assertPartsSumTo100, WHOLE } from '@zues/kernel';
import { numberOn, constantOn, unverified, defaultKey } from '@zues/law';
import { computeChargeRun, chargeablePersons, type Unit, type Tariff } from '../src/index.js';

const ON = '2026-09-13';
const unit = (o: Partial<Unit> & { designation: string; ideal_parts: string }): Unit => ({
  unit_id: o.designation, designation: o.designation,
  ideal_parts: idealParts(o.ideal_parts),
  occupants: o.occupants ?? 2, children_under_6: o.children_under_6 ?? 0,
  animals: o.animals ?? 0, absent_days: o.absent_days ?? 0,
  business_use: o.business_use ?? false,
});

const tariff = (over: Partial<Tariff> = {}): Tariff => ({
  entrance_id: 'e1', period: '2026-09', legal_date: ON,
  lines: [
    { stream: 'MANAGEMENT', key: 'PER_PERSON', rate_minor: 500, decision_id: 'd1' },
    { stream: 'MAINTENANCE', key: 'PER_PERSON', rate_minor: 300, decision_id: 'd1' },
    { stream: 'REPAIR_FUND', key: 'BY_IDEAL_PARTS', total_minor: 10_000, decision_id: 'd2' },
  ],
  ...over,
});

// ---------------------------------------------------------------- ORG
test('PM-ORG-002 ideal parts must sum to exactly 100% per entrance', () => {
  expect(() => assertPartsSumTo100([idealParts('50'), idealParts('50')])).not.toThrow();
  expect(() => assertPartsSumTo100([idealParts('50'), idealParts('49.999999')]))
    .toThrow(/must be 100\.000000%/);
});

test('PM-ORG-002 ideal parts are exact decimals, never floats', () => {
  expect(idealParts('12.345600')).toBe(12_345_600);
  expect(formatIdealParts(idealParts('0.000001'))).toBe('0.000001');
  expect(() => idealParts('12.3456001')).toThrow();
  // 0.1 + 0.2 in floats is not 0.3; in exact parts it is
  expect(idealParts('0.1') + idealParts('0.2')).toBe(idealParts('0.3'));
});

// ---------------------------------------------------------------- FEE
test('PM-FEE-016 money is integer minor units', () => {
  expect(eur(4250).amount_minor).toBe(4250);
  expect(() => eur(42.5)).toThrow(/integer minor units/);
});

test('PM-FEE-005 children under six are not counted', () => {
  expect(chargeablePersons(unit({ designation: '1', ideal_parts: '100', occupants: 4, children_under_6: 2 }), ON)).toBe(2);
  expect(numberOn('CHILD_AGE_NOT_COUNTED', ON)).toBe(6);
});

test('PM-FEE-009 each animal adds one occupant equivalent', () => {
  expect(chargeablePersons(unit({ designation: '1', ideal_parts: '100', occupants: 2, animals: 3 }), ON)).toBe(5);
});

test('PM-FEE-006 absence beyond the statutory window exempts the unit', () => {
  const days = numberOn('ABSENCE_EXEMPTION_DAYS', ON);
  expect(chargeablePersons(unit({ designation: '1', ideal_parts: '100', occupants: 2, absent_days: days }), ON)).toBe(2);
  expect(chargeablePersons(unit({ designation: '1', ideal_parts: '100', occupants: 2, absent_days: days + 1 }), ON)).toBe(0);
});

test('PM-FEE-006 the absence number is unconfirmed and must come from configuration', () => {
  const c = constantOn('ABSENCE_EXEMPTION_DAYS', ON);
  expect(c.verified).toBe(false);
  expect(c.todo_legal).toBeTruthy();
  expect(unverified().map((x) => x.code)).toContain('ABSENCE_EXEMPTION_DAYS');
});

test('PM-FEE-002 management and maintenance are allocated per person by default', () => {
  expect(defaultKey('MANAGEMENT')).toBe('PER_PERSON');
  expect(defaultKey('MAINTENANCE')).toBe('PER_PERSON');
  const run = computeChargeRun('e1', [
    unit({ designation: 'A', ideal_parts: '50', occupants: 1 }),
    unit({ designation: 'B', ideal_parts: '50', occupants: 3 }),
  ], tariff());
  // 8.00 € per person: A pays 8.00, B pays 24.00 — floor area is irrelevant
  expect(run.charges[0]!.lines[0]!.amount.amount_minor + run.charges[0]!.lines[1]!.amount.amount_minor).toBe(800);
  expect(run.charges[1]!.lines[0]!.amount.amount_minor + run.charges[1]!.lines[1]!.amount.amount_minor).toBe(2400);
});

test('PM-FEE-004 / PM-FUND-003 the repair fund is allocated by ideal parts and the assembly cannot move it', () => {
  expect(defaultKey('REPAIR_FUND')).toBe('BY_IDEAL_PARTS');
  const bad = tariff({ lines: [{ stream: 'REPAIR_FUND', key: 'PER_PERSON', total_minor: 100, decision_id: 'd2' }] });
  expect(() => computeChargeRun('e1', [unit({ designation: 'A', ideal_parts: '100' })], bad))
    .toThrow(/must be allocated BY_IDEAL_PARTS/);
});

test('PM-FEE-010 a business unit pays a multiple, and only inside the statutory range', () => {
  const units = [unit({ designation: 'A', ideal_parts: '50' }), unit({ designation: 'B', ideal_parts: '50', business_use: true })];
  const run = computeChargeRun('e1', units, tariff({ business_multiplier: 3 }));
  const mgmt = (i: number) => run.charges[i]!.lines[0]!.amount.amount_minor;
  expect(mgmt(1)).toBe(mgmt(0) * 3);
  expect(() => computeChargeRun('e1', units, tariff({ business_multiplier: 9 })))
    .toThrow(/outside the statutory range/);
});

test('PM-FEE-010 the business multiplier does not touch the repair fund', () => {
  const units = [unit({ designation: 'A', ideal_parts: '50' }), unit({ designation: 'B', ideal_parts: '50', business_use: true })];
  const run = computeChargeRun('e1', units, tariff({ business_multiplier: 5 }));
  const fund = (i: number) => run.charges[i]!.lines[2]!.amount.amount_minor;
  expect(fund(0)).toBe(fund(1));
});

test('PM-FEE-012 a tariff line with no assembly decision cannot be billed', () => {
  const bad = tariff({ lines: [{ stream: 'MANAGEMENT', key: 'PER_PERSON', rate_minor: 500, decision_id: '' }] });
  expect(() => computeChargeRun('e1', [unit({ designation: 'A', ideal_parts: '100' })], bad))
    .toThrow(/no GA decision/);
});

test('PM-FEE-014 an allocated pot sums to the pot exactly — no cent invented or lost', () => {
  const units = ['33.333333', '33.333333', '33.333334'].map((p, i) =>
    unit({ designation: `U${i}`, ideal_parts: p }));
  const run = computeChargeRun('e1', units, tariff({
    lines: [{ stream: 'REPAIR_FUND', key: 'BY_IDEAL_PARTS', total_minor: 10_001, decision_id: 'd2' }],
  }));
  expect(run.total.amount_minor).toBe(10_001);
});

test('PM-FEE-014 the run carries the basis, law version and engine version that produced it', () => {
  const run = computeChargeRun('e1', [unit({ designation: 'A', ideal_parts: '100' })], tariff());
  expect(run.law_version).toBe('1.3');
  expect(run.engine_version).toBeTruthy();
  expect(run.basis.legal_date).toBe(ON);
  expect(run.basis.constants['ABSENCE_EXEMPTION_DAYS']).toBe(30);
});

test('PM-FEE-014 the same basis recomputes to the same figures', () => {
  const units = [unit({ designation: 'A', ideal_parts: '40', occupants: 2 }),
                 unit({ designation: 'B', ideal_parts: '60', occupants: 3, animals: 1 })];
  const a = computeChargeRun('e1', units, tariff());
  const b = computeChargeRun('e1', units, tariff());
  expect(JSON.stringify(a.charges)).toBe(JSON.stringify(b.charges));
});

test('PM-FEE-018 every line states how the number was derived', () => {
  const run = computeChargeRun('e1', [unit({ designation: 'A', ideal_parts: '100', occupants: 2 })], tariff());
  for (const l of run.charges[0]!.lines) expect(l.derivation.length).toBeGreaterThan(5);
  expect(run.charges[0]!.lines[0]!.derivation).toMatch(/2 person\(s\)/);
});

// ---------------------------------------------------------------- SYS
test('PM-SYS-002 a constant resolves at the legal date, not at today', () => {
  expect(numberOn('EUR_BGN_RATE', '2026-06-01')).toBe(1.95583);
  expect(() => numberOn('EUR_BGN_RATE', '2025-12-31')).toThrow(/no constant/);
});

test('PM-SYS-001 an unknown constant stops rather than guessing', () => {
  expect(() => numberOn('NOT_A_REAL_CONSTANT', ON)).toThrow(/stop and ask/);
});

describe('kernel', () => {
  test('allocateByWeight never invents or loses a minor unit', () => {
    for (const total of [1, 7, 99, 100_000, 10_001]) {
      for (const ws of [[1, 1, 1], [1, 2, 3], [0, 0, 1], [5, 5]]) {
        const parts = allocateByWeight(eur(total), ws);
        expect(parts.reduce((s, p) => s + p.amount_minor, 0)).toBe(total);
      }
    }
  });
  test('WHOLE is exactly one hundred percent', () => expect(WHOLE).toBe(idealParts('100')));
});
