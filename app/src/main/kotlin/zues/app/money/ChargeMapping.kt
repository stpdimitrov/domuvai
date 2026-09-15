package zues.app.money

import zues.charges.ChargeRun

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
                ChargeLineResponse(line.stream.name, line.key.name, line.amount.amountMinor, line.derivation)
            },
        )
    },
)
