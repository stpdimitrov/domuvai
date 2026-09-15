package zues.app.money

import zues.charges.ChargeRun
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostStream

/**
 * Pure translation between the HTTP DTOs and the charge engine (`:charges`). No clock, no
 * I/O — so the same request always yields the same figures (Rule: PM-FEE-014). The engine
 * enforces the statutory guards (a tariff line needs a GA decision, the ideal parts sum
 * to a full share per PM-ORG-002, the business multiplier sits inside its range) and
 * throws when they fail.
 */
object ChargeCalculator {

    // Rule: PM-FEE-001 — every produced line is typed to one of the three cost streams.
    fun run(request: ChargeRunRequest): ChargeRunResponse {
        val units = request.units.map { it.toDomain() }
        val tariff = Tariff(
            entranceId = request.entranceId,
            period = request.period,
            legalDate = request.legalDate,
            lines = request.lines.map { it.toDomain() },
            businessMultiplier = request.businessMultiplier,
        )
        return computeChargeRun(request.entranceId, units, tariff).toResponse()
    }

    private fun UnitRequest.toDomain() = PropertyUnit(
        unitId = unitId,
        designation = designation,
        idealParts = IdealParts.of(idealParts),
        occupants = occupants,
        childrenUnder6 = childrenUnder6,
        animals = animals,
        absentDays = absentDays,
        businessUse = businessUse,
    )

    private fun TariffLineRequest.toDomain() = TariffLine(
        stream = CostStream.valueOf(stream),
        key = AllocationKey.valueOf(key),
        decisionId = decisionId,
        rateMinor = rateMinor,
        totalMinor = totalMinor,
    )

    private fun ChargeRun.toResponse() = ChargeRunResponse(
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
                    ChargeLineResponse(line.stream.name, line.key.name, line.amount.amountMinor, line.derivation)
                },
            )
        },
    )
}
