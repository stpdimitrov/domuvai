#!/usr/bin/env -S npx tsx
/**
 * zues-calc — Gate 1 in a command line.
 *
 * Reads a firm's own unit list and tariff, computes the bill with the same pure
 * functions the platform will use, and diffs it against the firm's own figures.
 * No database, no HTTP, no services. If this cannot reproduce their spreadsheet
 * to the cent, the rules are wrong and nothing else matters yet.
 *
 *   npx tsx apps/zues-calc/src/cli.ts <units.csv> --tariff <tariff.json> [--compare <expected.csv>]
 */
import { readFileSync } from 'node:fs';
import { idealParts, formatMoney, formatIdealParts, type IdealParts } from '@zues/kernel';
import { unverified } from '@zues/law';
import { computeChargeRun, type Unit, type Tariff } from '@zues/charges';
import { parseCsv } from './csv.js';

const argv = process.argv.slice(2);
const flag = (n: string) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : undefined; };
const unitsPath = argv.find((a) => !a.startsWith('--') && argv[argv.indexOf(a) - 1]?.startsWith('--') !== true);

if (!unitsPath) {
  console.error('usage: zues-calc <units.csv> --tariff <tariff.json> [--compare <expected.csv>]');
  process.exit(2);
}
const num = (v: string | undefined, d = 0) => (v === undefined || v === '' ? d : Number(v));
const bool = (v: string | undefined) => /^(1|true|да|yes|y)$/i.test((v ?? '').trim());

const units: Unit[] = parseCsv(readFileSync(unitsPath, 'utf8')).map((r) => ({
  unit_id: r['unit_id'] || r['designation'] || '',
  designation: r['designation'] || r['unit_id'] || '',
  ideal_parts: idealParts(r['ideal_parts'] ?? '0'),
  occupants: num(r['occupants']),
  children_under_6: num(r['children_under_6']),
  animals: num(r['animals']),
  absent_days: num(r['absent_days']),
  business_use: bool(r['business_use']),
}));

const tariff = JSON.parse(readFileSync(flag('--tariff') ?? 'tariff.json', 'utf8')) as Tariff;
const run = computeChargeRun(tariff.entrance_id, units, tariff);

const W = (s: string, n: number) => s.padEnd(n).slice(0, n);
const R = (s: string, n: number) => s.padStart(n);

console.log(`\nПериод ${run.period}  ·  законова дата ${run.legal_date}`);
console.log(`каталог v${run.law_version}  ·  engine v${run.engine_version}\n`);
console.log(W('Обект', 10) + R('л.ч.', 5) + R('ид.ч. %', 12) + R('Общо', 12) + '  Как е сметнато');
console.log('─'.repeat(110));
for (const c of run.charges) {
  const u = units.find((x) => x.unit_id === c.unit_id)!;
  console.log(W(c.designation, 10) + R(String(c.chargeable_persons), 5)
    + R(formatIdealParts(u.ideal_parts), 12) + R(formatMoney(c.total), 12)
    + '  ' + (c.lines[0]?.derivation ?? ''));
  for (const l of c.lines.slice(1)) console.log(' '.repeat(39) + '  ' + l.derivation);
}
console.log('─'.repeat(110));
console.log(W('ОБЩО', 10) + R('', 5) + R('', 12) + R(formatMoney(run.total), 12));

const cmp = flag('--compare');
if (cmp) {
  const expected = new Map(parseCsv(readFileSync(cmp, 'utf8'))
    // not-legal: 100 converts euros to cents for comparison, not a statutory threshold
    .map((r) => [r['unit_id'] || r['designation'] || '', Math.round(Number((r['total'] ?? '0').replace(',', '.')) * 100)]));
  let diffs = 0, absent = 0;
  console.log('\nСверка с техните числа');
  console.log('─'.repeat(50));
  for (const c of run.charges) {
    const want = expected.get(c.unit_id);
    if (want === undefined) { absent++; continue; }
    const delta = c.total.amount_minor - want;
    if (delta !== 0) {
      diffs++;
      console.log(`${W(c.designation, 10)} наше ${R(formatMoney(c.total), 10)}  тяхно ${R((want / 100).toFixed(2) + ' €', 10)}  Δ ${(delta / 100).toFixed(2)}`);
    }
  }
  if (absent) console.log(`${absent} unit(s) not present in their file`);
  console.log(diffs === 0 && absent === 0
    ? '\n✓ GATE 1 — reproduces their spreadsheet to the cent'
    : `\n✗ GATE 1 FAILED — ${diffs} unit(s) differ. The rules are wrong, not the code. Stop and fix the rules.`);
  if (diffs || absent) process.exitCode = 1;
}

const u = unverified();
if (u.length) {
  console.log(`\n⚠  ${u.length} constant(s) in this computation are unconfirmed against the statute:`);
  for (const c of u) console.log(`   ${c.code} = ${c.value}  (${c.rule ?? '—'}, ${c.source})`);
  console.log('   Confirm with counsel before any of this is billed.');
}
