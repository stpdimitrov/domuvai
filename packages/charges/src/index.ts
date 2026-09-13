/**
 * Charge computation. Pure: no clock, no I/O, no database, no model. ADR-001.
 *
 * Every number it uses comes from @zues/law resolved at the legal date.
 * Every result carries the basis that produced it, so it can be reproduced.
 */
import {
  type Money, type IdealParts, eur, addMoney, allocateByWeight,
  assertPartsSumTo100, formatIdealParts, PPM, WHOLE,
} from '@zues/kernel';
import {
  type AllocationKey, type CostStream, type LegalDate,
  CATALOGUE_VERSION, ENGINE_VERSION, numberOn, defaultKey, keyIsChangeableByAssembly,
} from '@zues/law';

export type Unit = {
  unit_id: string;
  designation: string;
  ideal_parts: IdealParts;
  /** persons resident more than the statutory threshold. Rule: PM-FEE-008 */
  occupants: number;
  /** Rule: PM-FEE-005 — not counted for management and maintenance */
  children_under_6: number;
  /** Rule: PM-FEE-009 — each adds one occupant equivalent */
  animals: number;
  /** days absent in the period, with a filed declaration. Rule: PM-FEE-006/007 */
  absent_days: number;
  /** separate street entrance, business use. Rule: PM-ORG-009, PM-FEE-010 */
  business_use: boolean;
};

/** A tariff exists only because the general assembly adopted it. Rule: PM-FEE-012 */
export type TariffLine = {
  stream: CostStream;
  key: AllocationKey;
  /** per unit of the key (per person, per unit) */
  rate_minor?: number;
  /** or a pot to allocate across the entrance */
  total_minor?: number;
  /** the GA decision that adopted it — without one, nothing can be billed */
  decision_id: string;
};

export type Tariff = {
  entrance_id: string;
  period: string;          // YYYY-MM
  legal_date: LegalDate;   // the date the law is read at (PM-SYS-002)
  lines: readonly TariffLine[];
  /** chosen within the statutory range by GA decision. Rule: PM-FEE-010 */
  business_multiplier?: number;
};

export type ChargeLine = {
  stream: CostStream;
  key: AllocationKey;
  amount: Money;
  /** how the number was derived, in words. Rule: PM-FEE-018 */
  derivation: string;
};

export type UnitCharge = {
  unit_id: string;
  designation: string;
  lines: readonly ChargeLine[];
  total: Money;
  chargeable_persons: number;
};

export type ChargeRun = {
  entrance_id: string;
  period: string;
  legal_date: LegalDate;
  law_version: string;
  engine_version: string;
  charges: readonly UnitCharge[];
  total: Money;
  basis: Basis;
};

/** The frozen snapshot the run computed from. Rule: PM-FEE-014 */
export type Basis = {
  legal_date: LegalDate;
  units: readonly Unit[];
  tariff: Tariff;
  constants: Record<string, number>;
};

/** Rule: PM-FEE-005, PM-FEE-006, PM-FEE-008, PM-FEE-009 */
export const chargeablePersons = (u: Unit, on: LegalDate): number => {
  const exemptionDays = numberOn('ABSENCE_EXEMPTION_DAYS', on);
  const animalEquiv = numberOn('ANIMAL_OCCUPANT_EQUIVALENT', on);
  // TODO(legal): PM-FEE-006 — full exemption vs reduced share is unconfirmed.
  // Implemented as full exemption; the number and the mode both live in config.
  const present = u.absent_days > exemptionDays ? 0 : u.occupants;
  const adults = Math.max(0, present - (present > 0 ? u.children_under_6 : 0));
  return adults + u.animals * animalEquiv;
};

const weightFor = (u: Unit, key: AllocationKey, on: LegalDate): number => {
  switch (key) {
    case 'PER_PERSON': return chargeablePersons(u, on);
    case 'BY_IDEAL_PARTS': return u.ideal_parts;
    case 'PER_UNIT': return 1;
  }
};

/** Rule: PM-FEE-010 — business use pays a multiple, on management and maintenance only */
const multiplierFor = (u: Unit, t: Tariff, stream: CostStream): number => {
  if (!u.business_use || stream === 'REPAIR_FUND') return 1;
  const min = numberOn('BUSINESS_USE_MULTIPLIER_MIN', t.legal_date);
  const max = numberOn('BUSINESS_USE_MULTIPLIER_MAX', t.legal_date);
  // TODO(legal): PM-FEE-010 — range unconfirmed; the chosen value must come
  // from a GA decision and must sit inside it.
  const chosen = t.business_multiplier ?? min;
  if (chosen < min || chosen > max) {
    throw new RangeError(
      `business multiplier ${chosen} is outside the statutory range ${min}–${max} (PM-FEE-010)`);
  }
  return chosen;
};

export const computeChargeRun = (
  entrance_id: string, units: readonly Unit[], tariff: Tariff,
): ChargeRun => {
  assertPartsSumTo100(units.map((u) => u.ideal_parts));           // PM-ORG-002
  for (const line of tariff.lines) {
    if (!line.decision_id) {
      throw new Error(`tariff line ${line.stream} has no GA decision — cannot bill (PM-FEE-012)`);
    }
    if (!keyIsChangeableByAssembly(line.stream) && line.key !== defaultKey(line.stream)) {
      throw new Error(
        `${line.stream} must be allocated ${defaultKey(line.stream)} (PM-FEE-004, PM-FUND-003)`);
    }
  }

  const on = tariff.legal_date;
  const perUnit = new Map<string, ChargeLine[]>(units.map((u) => [u.unit_id, []]));

  for (const line of tariff.lines) {
    const base = units.map((u) => weightFor(u, line.key, on));
    const weights = units.map((u, i) => base[i]! * multiplierFor(u, tariff, line.stream));

    let amounts: Money[];
    let how: (u: Unit, i: number) => string;

    if (line.total_minor !== undefined) {
      amounts = allocateByWeight(eur(line.total_minor), weights);
      how = (_u, i) => `${(line.total_minor! / 100).toFixed(2)} € split by ${line.key.toLowerCase()}`
        + ` · ${fmtWeight(line.key, base[i] ?? 0)}`;
    } else if (line.rate_minor !== undefined) {
      amounts = weights.map((w) =>
        line.key === 'BY_IDEAL_PARTS'
          ? eur(Math.round((line.rate_minor! * w) / WHOLE))
          : eur(line.rate_minor! * w));
      how = (_u, i) => `${(line.rate_minor! / 100).toFixed(2)} € × ${fmtWeight(line.key, base[i] ?? 0)}`;
    } else {
      throw new Error(`tariff line ${line.stream} has neither a rate nor a total`);
    }

    units.forEach((u, i) => {
      perUnit.get(u.unit_id)!.push({
        stream: line.stream, key: line.key, amount: amounts[i]!,
        derivation: how(u, i) + (u.business_use && line.stream !== 'REPAIR_FUND'
          ? ` · business ×${multiplierFor(u, tariff, line.stream)}` : ''),
      });
    });
  }

  const charges: UnitCharge[] = units.map((u) => {
    const lines = perUnit.get(u.unit_id)!;
    return {
      unit_id: u.unit_id, designation: u.designation, lines,
      total: addMoney(...lines.map((l) => l.amount)),
      chargeable_persons: chargeablePersons(u, on),
    };
  });

  return {
    entrance_id, period: tariff.period, legal_date: on,
    law_version: CATALOGUE_VERSION, engine_version: ENGINE_VERSION,
    charges, total: addMoney(...charges.map((c) => c.total)),
    basis: {
      legal_date: on, units: [...units], tariff,
      constants: Object.fromEntries(
        ['ABSENCE_EXEMPTION_DAYS', 'ANIMAL_OCCUPANT_EQUIVALENT',
         'BUSINESS_USE_MULTIPLIER_MIN', 'BUSINESS_USE_MULTIPLIER_MAX']
          .map((c) => [c, numberOn(c, on)])),
    },
  };
};

const fmtWeight = (key: AllocationKey, w: number): string =>
  key === 'BY_IDEAL_PARTS' ? `${formatIdealParts(w as IdealParts)}%`
  : key === 'PER_PERSON' ? `${w} person(s)` : `${w} unit`;
