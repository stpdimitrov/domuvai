package zues.app.money

import org.springframework.stereotype.Service
import zues.app.registry.Units
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
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

/**
 * Computes a charge run for an entrance from the units `registry` holds, reached through its
 * published [Units] port — never its tables (ADR-003). Occupancy is not modelled yet, so a
 * `PER_PERSON` line is refused rather than silently billed as zero persons.
 */
@Service
class ChargeRunService(private val units: Units) {

    fun preview(entranceId: UUID, request: StoredChargeRunRequest): ChargeRunResponse {
        val stored = units.forEntrance(entranceId)
        if (stored.isEmpty()) {
            throw NoSuchElementException("entrance $entranceId has no registered units")
        }
        require(request.lines.none { AllocationKey.valueOf(it.key) == AllocationKey.PER_PERSON }) {
            "PER_PERSON allocation needs occupancy, which is not modelled yet — use BY_IDEAL_PARTS or PER_UNIT"
        }
        val propertyUnits = stored.map {
            PropertyUnit(
                unitId = it.unitId.toString(),
                designation = it.designation,
                idealParts = IdealParts.of(it.idealParts),
                occupants = 0,
                businessUse = it.separateEntrance,
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
        return computeChargeRun(entranceId.toString(), propertyUnits, tariff).toResponse()
    }
}
