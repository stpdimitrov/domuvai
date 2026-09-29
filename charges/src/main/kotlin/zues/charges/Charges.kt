package zues.charges

import zues.kernel.IdealParts
import zues.kernel.Money
import zues.kernel.allocateByWeight
import zues.kernel.assertPartsSumTo100
import zues.kernel.eur
import zues.kernel.sumMoney
import zues.law.AllocationKey
import zues.law.CATALOGUE_VERSION
import zues.law.CostItem
import zues.law.CostStream
import zues.law.ENGINE_VERSION
import zues.law.LegalDate
import zues.law.defaultKey
import zues.law.keyIsChangeableByAssembly
import zues.law.numberOn

/**
 * Charge computation. Pure: no clock, no I/O, no database, no model. ADR-001.
 * Every number it uses comes from @zues/law resolved at the legal date, and every
 * result carries the basis that produced it, so it can be reproduced. PM-FEE-014.
 */

/**
 * A самостоятелен обект — the independent property unit that carries ideal parts
 * and occupancy, and to which a charge is raised. Named `PropertyUnit` (not `Unit`)
 * to keep it clear of `kotlin.Unit`.
 */
data class PropertyUnit(
    val unitId: String,
    val designation: String,
    val idealParts: IdealParts,
    /** persons resident more than the statutory threshold. Rule: PM-FEE-008 */
    val occupants: Int,
    /** Rule: PM-FEE-005 — not counted for management and maintenance */
    val childrenUnder6: Int = 0,
    /** Rule: PM-FEE-009 — each adds one occupant equivalent */
    val animals: Int = 0,
    /** days absent in the period, with a filed declaration. Rule: PM-FEE-006/007 */
    val absentDays: Int = 0,
    /** separate street entrance, business use. Rule: PM-ORG-009, PM-FEE-010 */
    val businessUse: Boolean = false,
    /** what the unit's own meters read for the period, in thousandths of the unit of measure, by metered item. Rule: PM-FEE-017 */
    val readings: Map<CostItem, Long> = emptyMap(),
)

/** A tariff exists only because the general assembly adopted it. Rule: PM-FEE-012 */
data class TariffLine(
    val stream: CostStream,
    val key: AllocationKey,
    /** the GA decision that adopted it — without one, nothing can be billed */
    val decisionId: String,
    /** per unit of the key (per person, per unit) */
    val rateMinor: Long? = null,
    /** or a pot to allocate across the entrance */
    val totalMinor: Long? = null,
    /** a named cost within the stream — a concierge line is a maintenance line (PM-FEE-011) */
    val item: CostItem? = null,
)

/**
 * A metered cost billed from each unit's own reading (Rule: PM-FEE-017): one price per unit of measure,
 * adopted by GA decision (PM-FEE-012). Never allocated by a statutory key; it changes no other line.
 */
data class ConsumptionLine(
    val item: CostItem,
    /** per unit of measure (m³, kWh), in minor units */
    val priceMinor: Long,
    val decisionId: String,
)

data class Tariff(
    val entranceId: String,
    val period: String,          // YYYY-MM
    val legalDate: LegalDate,    // the date the law is read at (PM-SYS-002)
    val lines: List<TariffLine>,
    /** chosen within the statutory range by GA decision. Rule: PM-FEE-010 */
    val businessMultiplier: Int? = null,
    /** metered costs, billed per unit from its reading. Rule: PM-FEE-017 */
    val consumption: List<ConsumptionLine> = emptyList(),
)

/** A unit a consumption line could not bill: no reading for it. Nothing is estimated. Rule: PM-FEE-017 */
data class MissingReading(val unitId: String, val item: CostItem)

data class ChargeLine(
    val stream: CostStream,
    val key: AllocationKey,
    val amount: Money,
    /** how the number was derived, in words. Rule: PM-FEE-018 */
    val derivation: String,
    /** the GA decision behind this line — its rate or pot, and its key where the assembly chooses one. Rule: PM-FEE-003 */
    val decisionId: String,
    val item: CostItem? = null,
)

data class UnitCharge(
    val unitId: String,
    val designation: String,
    val lines: List<ChargeLine>,
    val total: Money,
    val chargeablePersons: Int,
)

/** The frozen snapshot the run computed from. Rule: PM-FEE-014 */
data class Basis(
    val legalDate: LegalDate,
    val units: List<PropertyUnit>,
    val tariff: Tariff,
    val constants: Map<String, Double>,
)

data class ChargeRun(
    val entranceId: String,
    val period: String,
    val legalDate: LegalDate,
    val lawVersion: String,
    val engineVersion: String,
    val charges: List<UnitCharge>,
    val total: Money,
    val basis: Basis,
    /** units a consumption line did not bill, for want of a reading (PM-FEE-017) */
    val missingReadings: List<MissingReading> = emptyList(),
)

/** Rule: PM-FEE-005, PM-FEE-006, PM-FEE-008, PM-FEE-009 */
fun chargeablePersons(u: PropertyUnit, on: LegalDate): Int {
    val exemptionDays = numberOn("ABSENCE_EXEMPTION_DAYS", on).toInt()
    val animalEquiv = numberOn("ANIMAL_OCCUPANT_EQUIVALENT", on).toInt()
    // TODO(legal): PM-FEE-006 — full exemption vs reduced share is unconfirmed.
    // Implemented as full exemption; the number and the mode both live in config.
    val present = if (u.absentDays > exemptionDays) 0 else u.occupants
    val adults = maxOf(0, present - (if (present > 0) u.childrenUnder6 else 0))
    return adults + u.animals * animalEquiv
}

private fun weightFor(u: PropertyUnit, key: AllocationKey, on: LegalDate): Long = when (key) {
    AllocationKey.PER_PERSON -> chargeablePersons(u, on).toLong()
    AllocationKey.BY_IDEAL_PARTS -> u.idealParts.ppmPct.toLong()
    AllocationKey.PER_UNIT -> 1L
    AllocationKey.METERED -> throw IllegalStateException("METERED is not a statutory key (PM-FEE-017)")
}

/** Rule: PM-FEE-010 — business use pays a multiple, on management and maintenance only */
private fun multiplierFor(u: PropertyUnit, t: Tariff, stream: CostStream): Int {
    if (!u.businessUse || stream == CostStream.REPAIR_FUND) return 1
    val min = numberOn("BUSINESS_USE_MULTIPLIER_MIN", t.legalDate).toInt()
    val max = numberOn("BUSINESS_USE_MULTIPLIER_MAX", t.legalDate).toInt()
    // TODO(legal): PM-FEE-010 — range unconfirmed; the chosen value must come
    // from a GA decision and must sit inside it.
    val chosen = t.businessMultiplier ?: min
    if (chosen < min || chosen > max) {
        throw IllegalArgumentException("business multiplier $chosen is outside the statutory range $min–$max (PM-FEE-010)")
    }
    return chosen
}

private fun fmtWeight(key: AllocationKey, u: PropertyUnit, on: LegalDate): String = when (key) {
    AllocationKey.BY_IDEAL_PARTS -> "${u.idealParts.format()}%"
    AllocationKey.PER_PERSON -> "${chargeablePersons(u, on)} person(s)"
    AllocationKey.PER_UNIT -> "1 unit"
    AllocationKey.METERED -> throw IllegalStateException("METERED is not a statutory key (PM-FEE-017)")
}

/** A reading kept in thousandths, written with its three decimals: 12345 → "12.345", 5 → "0.005". */
private fun thousandths(q: Long): String {
    val a = Math.abs(q)
    return "${if (q < 0) "-" else ""}${a / 1_000}.${(a % 1_000).toString().padStart(3, '0')}"
}

private fun roundDiv(a: Long, b: Long): Long = (a + b / 2) / b

fun computeChargeRun(entranceId: String, units: List<PropertyUnit>, tariff: Tariff): ChargeRun {
    assertPartsSumTo100(units.map { it.idealParts })                 // PM-ORG-002
    for (line in tariff.lines) {
        if (line.decisionId.isBlank()) {
            throw IllegalStateException("tariff line ${line.stream} has no GA decision — cannot bill (PM-FEE-012)")
        }
        if (line.key == AllocationKey.METERED || line.item?.metered == true) {           // Rule: PM-FEE-017
            throw IllegalStateException("a metered cost is billed from each unit's reading, in the tariff's consumption lines — not by a key (PM-FEE-017)")
        }
        if (!keyIsChangeableByAssembly(line.stream) && line.key != defaultKey(line.stream)) {
            throw IllegalStateException("${line.stream} must be allocated ${defaultKey(line.stream)} (PM-FEE-004, PM-FUND-003)")
        }
        line.item?.let { item ->                                          // Rule: PM-FEE-011
            if (line.stream != item.stream) {
                throw IllegalStateException("$item is a ${item.stream} cost, not ${line.stream} (PM-FEE-011)")
            }
            if (tariff.lines.count { it.item == item } > 1) {
                throw IllegalStateException("$item is on more than one line — a tariff names it once (PM-FEE-011)")
            }
            // every one of the stream's own lines, so the answer does not depend on their order
            val streamKeys = tariff.lines.filter { it.stream == item.stream && it.item == null }.map { it.key }.toSet()
                .ifEmpty { setOf(defaultKey(item.stream)) }
            if (streamKeys != setOf(line.key)) {
                throw IllegalStateException("$item must be allocated as ${item.stream} is (${streamKeys.joinToString()}) (PM-FEE-011)")
            }
        }
    }

    for (line in tariff.consumption) {                                                    // Rule: PM-FEE-017
        if (line.decisionId.isBlank()) {
            throw IllegalStateException("consumption line ${line.item} has no GA decision — cannot bill (PM-FEE-012)")
        }
        if (!line.item.metered) throw IllegalStateException("${line.item} is not a metered cost (PM-FEE-017)")
        if (line.priceMinor <= 0) throw IllegalStateException("consumption line ${line.item} needs a positive price (PM-FEE-017)")
        if (tariff.consumption.count { it.item == line.item } > 1) {
            throw IllegalStateException("${line.item} is on more than one consumption line — a tariff prices it once (PM-FEE-017)")
        }
    }
    for (u in units) {
        for ((item, reading) in u.readings) {
            if (!item.metered || reading < 0) {
                throw IllegalStateException("unit ${u.designation}: a $item reading of ${thousandths(reading)} is not a meter reading (PM-FEE-017)")
            }
            if (tariff.consumption.none { it.item == item }) {                          // a reading nothing prices is not dropped silently
                throw IllegalStateException("unit ${u.designation} has a $item reading, but the tariff prices no $item (PM-FEE-017)")
            }
        }
    }

    val on = tariff.legalDate
    val perUnit: Map<String, MutableList<ChargeLine>> = units.associate { it.unitId to mutableListOf<ChargeLine>() }

    for (line in tariff.lines) {
        val weights = units.map { weightFor(it, line.key, on) * multiplierFor(it, tariff, line.stream) }
        val total = line.totalMinor
        val rate = line.rateMinor
        val amounts: List<Money>
        val how: (PropertyUnit) -> String
        if (total != null) {
            amounts = allocateByWeight(eur(total), weights)
            how = { u -> "${eur(total).format()} split by ${line.key.name.lowercase()} · ${fmtWeight(line.key, u, on)}" }
        } else if (rate != null) {
            amounts = weights.map { w ->
                if (line.key == AllocationKey.BY_IDEAL_PARTS) {
                    eur(roundDiv(rate * w, IdealParts.WHOLE.ppmPct.toLong()))
                } else {
                    eur(rate * w)
                }
            }
            how = { u -> "${eur(rate).format()} × ${fmtWeight(line.key, u, on)}" }
        } else {
            throw IllegalStateException("tariff line ${line.stream} has neither a rate nor a total")
        }

        val label = line.item?.let { "${it.name.lowercase()} · " } ?: ""
        units.forEachIndexed { i, u ->
            val note = if (u.businessUse && line.stream != CostStream.REPAIR_FUND) {
                " · business ×${multiplierFor(u, tariff, line.stream)}"
            } else {
                ""
            }
            perUnit.getValue(u.unitId).add(ChargeLine(line.stream, line.key, amounts[i], label + how(u) + note, line.decisionId, line.item))
        }
    }

    // Rule: PM-FEE-017 — what each unit's own meter read, times the adopted price, half-up to the cent. It is not a
    // share of a common cost, so no key touches it; an unread meter bills nothing and is listed, never estimated.
    // Read in S-G1-02e and put to the owner (#62), not yet decided: PM-FEE-010's multiple is of "the standard rate" —
    // a share of the common costs — so the business multiplier is not applied to a metered line.
    val missing = mutableListOf<MissingReading>()
    for (line in tariff.consumption) {
        val uom = line.item.unitOfMeasure ?: ""
        for (u in units) {
            val reading = u.readings[line.item]
            if (reading == null) {
                missing += MissingReading(u.unitId, line.item)
                continue
            }
            val product = try {
                Math.multiplyExact(reading, line.priceMinor)
            } catch (e: ArithmeticException) {
                throw IllegalStateException("unit ${u.designation}: ${thousandths(reading)} $uom × ${eur(line.priceMinor).format()} is out of range (PM-FEE-017)")
            }
            val amount = eur(roundDiv(product, 1_000))
            val how = "${line.item.name.lowercase()} · ${thousandths(reading)} $uom × ${eur(line.priceMinor).format()}/$uom (metered)"
            perUnit.getValue(u.unitId).add(ChargeLine(line.item.stream, AllocationKey.METERED, amount, how, line.decisionId, line.item))
        }
    }

    val charges = units.map { u ->
        val lines = perUnit.getValue(u.unitId).toList()
        UnitCharge(u.unitId, u.designation, lines, sumMoney(lines.map { it.amount }), chargeablePersons(u, on))
    }

    return ChargeRun(
        entranceId = entranceId,
        period = tariff.period,
        legalDate = on,
        lawVersion = CATALOGUE_VERSION,
        engineVersion = ENGINE_VERSION,
        charges = charges,
        total = sumMoney(charges.map { it.total }),
        basis = Basis(
            legalDate = on,
            units = units,
            tariff = tariff,
            constants = listOf(
                "ABSENCE_EXEMPTION_DAYS", "ANIMAL_OCCUPANT_EQUIVALENT",
                "BUSINESS_USE_MULTIPLIER_MIN", "BUSINESS_USE_MULTIPLIER_MAX",
            ).associateWith { numberOn(it, on) },
        ),
        missingReadings = missing,
    )
}
