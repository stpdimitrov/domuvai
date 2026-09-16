package zues.app.money

import org.springframework.stereotype.Service
import zues.app.registry.UnitForCharging
import zues.app.registry.Units
import zues.charges.ChargeRun
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.chargeablePersons
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostStream
import java.util.UUID

/** A charge run over an entrance's stored units: the tariff is supplied, the units come from registry. */
data class StoredChargeRunRequest(
    val period: String,
    val legalDate: String,
    val businessMultiplier: Int? = null,
    val lines: List<TariffLineRequest>,
)

/** A computed run together with the registry units it was computed from (the store needs both). */
data class ComputedRun(val run: ChargeRun, val units: List<UnitForCharging>)

/**
 * Computes a charge run for an entrance from the units `registry` holds, reached through its
 * published [Units] port — never its tables (ADR-003). A `PER_PERSON` line now bills on the
 * registered household headcount; the engine excludes children under six (PM-FEE-005). A
 * per-person run with no chargeable occupant is refused, not billed as a division by zero.
 */
@Service
class ChargeRunService(private val units: Units) {

    fun compute(entranceId: UUID, request: StoredChargeRunRequest): ComputedRun {
        val stored = units.forEntrance(entranceId)
        if (stored.isEmpty()) {
            throw NoSuchElementException("entrance $entranceId has no registered units")
        }
        val propertyUnits = stored.map {
            PropertyUnit(
                unitId = it.unitId.toString(),
                designation = it.designation,
                idealParts = IdealParts.of(it.idealParts),
                occupants = it.occupants,
                childrenUnder6 = it.childrenUnder6,
                animals = it.animals,
                businessUse = it.separateEntrance,
            )
        }
        val perPerson = request.lines.any { AllocationKey.valueOf(it.key) == AllocationKey.PER_PERSON }
        if (perPerson && propertyUnits.sumOf { chargeablePersons(it, request.legalDate) } == 0) {
            throw IllegalArgumentException(
                "a PER_PERSON charge needs at least one chargeable occupant; this entrance has none registered",
            )
        }
        val tariff = Tariff(
            entranceId = entranceId.toString(),
            period = request.period,
            legalDate = request.legalDate,
            lines = request.lines.map {
                TariffLine(CostStream.valueOf(it.stream), AllocationKey.valueOf(it.key), it.decisionId, it.rateMinor, it.totalMinor)
            },
            businessMultiplier = request.businessMultiplier,
        )
        return ComputedRun(computeChargeRun(entranceId.toString(), propertyUnits, tariff), stored)
    }

    fun preview(entranceId: UUID, request: StoredChargeRunRequest): ChargeRunResponse =
        compute(entranceId, request).run.toResponse()
}
