package zues.charges

import zues.kernel.IdealParts
import zues.kernel.Money
import zues.kernel.allocateByWeight
import zues.kernel.assertPartsSumTo100
import zues.kernel.eur
import zues.kernel.sumMoney
import zues.law.AllocationKey
import zues.law.CATALOGUE_VERSION
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
 * A самостоятелен обект. Shadows `kotlin.Unit` within this package — deliberate,
 * to keep the domain term; `kotlin.Unit` is never referenced here by name.
 */
data class Unit(
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
)

data class Tariff(
    val entranceId: String,
    val period: String,          // YYYY-MM
    val legalDate: LegalDate,    // the date the law is read at (PM-SYS-002)
    val lines: List<TariffLine>,
    /** chosen within the statutory range by GA decision. Rule: PM-FEE-010 */
    val businessMultiplier: Int? = null,
)

data class ChargeLine(
    val stream: CostStream,
    val key: AllocationKey,
    val amount: Money,
    /** how the number was derived, in words. Rule: PM-FEE-018 */
    val derivation: String,
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
    val units: List<Unit>,
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
)

/** Rule: PM-FEE-005, PM-FEE-006, PM-FEE-008, PM-FEE-009 */
fun chargeablePersons(u: Unit, on: LegalDate): Int {
    val exemptionDays = numberOn("ABSENCE_EXEMPTION_DAYS", on).toInt()
    val animalEquiv = numberOn("ANIMAL_OCCUPANT_EQUIVALENT", on).toInt()
    // TODO(legal): PM-FEE-006 — full exemption vs reduced share is unconfirmed.
    // Implemented as full exemption; the number and the mode both live in config.
    val present = if (u.absentDays > exemptionDays) 0 else u.occupants
    val adults = maxOf(0, present - (if (present > 0) u.childrenUnder6 else 0))
    return adults + u.animals * animalEquiv
}

private fun weightFor(u: Unit, key: AllocationKey, on: LegalDate): Long = when (key) {
    AllocationKey.PER_PERSON -> chargeablePersons(u, on).toLong()
    AllocationKey.BY_IDEAL_PARTS -> u.idealParts.ppmPct.toLong()
    AllocationKey.PER_UNIT -> 1L
}

/** Rule: PM-FEE-010 — business use pays a multiple, on management and maintenance only */
private fun multiplierFor(u: Unit, t: Tariff, stream: CostStream): Int {
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

private fun fmtWeight(key: AllocationKey, u: Unit, on: LegalDate): String = when (key) {
    AllocationKey.BY_IDEAL_PARTS -> "${u.idealParts.format()}%"
    AllocationKey.PER_PERSON -> "${chargeablePersons(u, on)} person(s)"
    AllocationKey.PER_UNIT -> "1 unit"
}

private fun roundDiv(a: Long, b: Long): Long = (a + b / 2) / b

fun computeChargeRun(entranceId: String, units: List<Unit>, tariff: Tariff): ChargeRun {
    assertPartsSumTo100(units.map { it.idealParts })                 // PM-ORG-002
    for (line in tariff.lines) {
        if (line.decisionId.isBlank()) {
            throw IllegalStateException("tariff line ${line.stream} has no GA decision — cannot bill (PM-FEE-012)")
        }
        if (!keyIsChangeableByAssembly(line.stream) && line.key != defaultKey(line.stream)) {
            throw IllegalStateException("${line.stream} must be allocated ${defaultKey(line.stream)} (PM-FEE-004, PM-FUND-003)")
        }
    }

    val on = tariff.legalDate
    val perUnit: Map<String, MutableList<ChargeLine>> = units.associate { it.unitId to mutableListOf<ChargeLine>() }

    for (line in tariff.lines) {
        val weights = units.map { weightFor(it, line.key, on) * multiplierFor(it, tariff, line.stream) }
        val total = line.totalMinor
        val rate = line.rateMinor
        val amounts: List<Money>
        val how: (Unit) -> String
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

        units.forEachIndexed { i, u ->
            val note = if (u.businessUse && line.stream != CostStream.REPAIR_FUND) {
                " · business ×${multiplierFor(u, tariff, line.stream)}"
            } else {
                ""
            }
            perUnit.getValue(u.unitId).add(ChargeLine(line.stream, line.key, amounts[i], how(u) + note))
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
    )
}
