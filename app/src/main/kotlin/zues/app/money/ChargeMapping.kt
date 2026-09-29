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
internal fun ConsumptionLineRequest.toDomain() = ConsumptionLine(CostItem.valueOf(item), priceMinor, decisionId)

/** A meter reading's precision: three decimals, kept as thousandths — the owner's choice (D2 on #62), not a statute. */
private const val READING_DECIMALS = 3

/**
 * Each unit's readings, by metered item, in thousandths of the unit of measure (Rule: PM-FEE-017). A reading
 * has at most [READING_DECIMALS] decimals and is never negative; it names a unit of the run, once per item.
 */
internal fun readingsByUnit(readings: List<ReadingRequest>, unitIds: Set<String>): Map<String, Map<CostItem, Long>> {
    val byUnit = mutableMapOf<String, MutableMap<CostItem, Long>>()
    for (r in readings) {
        require(r.unitId in unitIds) { "a reading names unit ${r.unitId}, which is not in the run" }
        val quantity = r.quantity.toBigDecimalOrNull()
        require(quantity != null && quantity.signum() >= 0 && quantity.scale() <= READING_DECIMALS) {
            "a reading is a quantity of at most three decimals, not negative: ${r.quantity}"
        }
        val item = CostItem.valueOf(r.item)
        val previous = byUnit.getOrPut(r.unitId) { mutableMapOf() }.put(item, quantity.movePointRight(READING_DECIMALS).setScale(0).longValueExact())
        require(previous == null) { "unit ${r.unitId} has two $item readings" }
    }
    return byUnit
}

/** A reading in thousandths, as the numeric(12,6) quantity a stored charge line keeps. */
internal fun readingQuantity(thousandths: Long): BigDecimal = BigDecimal.valueOf(thousandths, READING_DECIMALS).setScale(6)
