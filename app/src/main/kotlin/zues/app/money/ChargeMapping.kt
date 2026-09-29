package zues.app.money

import zues.charges.ChargeRun
import zues.charges.ConsumptionLine
import zues.law.CostItem
import java.math.BigDecimal

/** Shared translation of a computed [ChargeRun] into the HTTP response — used by both the
 *  payload calculator and the registry-sourced service. */
internal fun ChargeRun.toResponse(): ChargeRunResponse = ChargeRunResponse(
    entranceId = entranceId,
    period = period,
    legalDate = legalDate,
    lawVersion = lawVersion,
    engineVersion = engineVersion,
    totalMinor = total.amountMinor,
    charges = charges.map { charge ->
        UnitChargeResponse(
            unitId = charge.unitId,
            designation = charge.designation,
            totalMinor = charge.total.amountMinor,
            chargeablePersons = charge.chargeablePersons,
            lines = charge.lines.map { line ->
                ChargeLineResponse(line.stream.name, line.key.name, line.amount.amountMinor, line.derivation, line.decisionId, line.item?.name)
            },
        )
    },
    missingReadings = missingReadings.map { MissingReadingResponse(it.unitId, it.item.name) },
)

/** A metered cost as the engine takes it (Rule: PM-FEE-017). */
internal fun ConsumptionLineRequest.toDomain() = ConsumptionLine(meteredItem(item), priceMinor, decisionId)

/** A metered item by name, or a 400 that names the choices — never the enum's class name. */
internal fun meteredItem(name: String): CostItem =
    CostItem.entries.firstOrNull { it.metered && it.name == name }
        ?: throw IllegalArgumentException("a metered item is ${CostItem.entries.filter { it.metered }.joinToString(" or ")}, not $name (PM-FEE-017)")

/** A meter reading's precision: three decimals, kept as thousandths — the owner's choice (D2 on #62), not a statute. */
private const val READING_DECIMALS = 3

/**
 * A reading as plain digits: at most six before the point — what a stored line's quantity, numeric(12,6), holds —
 * and [READING_DECIMALS] after. No sign, no exponent, so nothing huge ever reaches the arithmetic.
 */
private val READING = Regex("""\d{1,6}(\.\d{1,$READING_DECIMALS})?""")

/**
 * Each unit's readings, by metered item, in thousandths of the unit of measure (Rule: PM-FEE-017). A reading
 * is plain digits with at most [READING_DECIMALS] decimals, never negative; it names a unit of the run, once per item.
 */
internal fun readingsByUnit(readings: List<ReadingRequest>, unitIds: Set<String>): Map<String, Map<CostItem, Long>> {
    val byUnit = mutableMapOf<String, MutableMap<CostItem, Long>>()
    for (r in readings) {
        require(r.unitId in unitIds) { "a reading names unit ${r.unitId}, which is not in the run" }
        require(READING.matches(r.quantity)) { "a reading is up to six digits with up to three decimals, e.g. 12.345 — not ${r.quantity}" }
        val item = meteredItem(r.item)
        val thousandths = BigDecimal(r.quantity).movePointRight(READING_DECIMALS).setScale(0).longValueExact()
        val previous = byUnit.getOrPut(r.unitId) { mutableMapOf() }.put(item, thousandths)
        require(previous == null) { "unit ${r.unitId} has two $item readings" }
    }
    return byUnit
}

/** A reading in thousandths, as the numeric(12,6) quantity a stored charge line keeps. */
internal fun readingQuantity(thousandths: Long): BigDecimal = BigDecimal.valueOf(thousandths, READING_DECIMALS).setScale(6)
