package zues.app.money

import org.springframework.stereotype.Service
import zues.app.registry.UnitForCharging
import zues.app.registry.Units
import zues.charges.ChargeRun
import zues.charges.ConsumptionLine
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.chargeablePersons
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostItem
import zues.law.CostStream
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * A charge run over an entrance's stored units. Neither the units nor the amounts are sent: the units come from
 * registry, the amounts from the tariff version in force for [period] (PM-FEE-012). A meter reading is a fact of the
 * period, not an amount the assembly sets, so it is still sent (PM-FEE-017).
 */
data class StoredChargeRunRequest(
    val period: String,
    val legalDate: String,
    val businessMultiplier: Int? = null,
    val readings: List<ReadingRequest>? = null,
)

/** A computed run together with the registry units and the tariff version it was computed from (the store needs all three). */
data class ComputedRun(val run: ChargeRun, val units: List<UnitForCharging>, val version: TariffVersionView)

/**
 * Computes a charge run for an entrance from the units `registry` holds and the tariff version in force for the
 * period (PM-FEE-012; a period with none is refused). The units are reached through registry's
 * published [Units] port — never its tables (ADR-003). A `PER_PERSON` line now bills on the
 * registered household headcount; the engine excludes children under six (PM-FEE-005). A
 * per-person run with no chargeable occupant is refused, not billed as a division by zero.
 */
@Service
class ChargeRunService(private val units: Units, private val tariffs: TariffVersions) {

    // Rule: PM-FEE-012
    fun compute(entranceId: UUID, request: StoredChargeRunRequest): ComputedRun {
        val on = try {
            LocalDate.parse(request.legalDate)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("legalDate must be an ISO date (YYYY-MM-DD): ${request.legalDate}")
        }
        val version = tariffs.list(entranceId, request.period).singleOrNull()             // Rule: PM-FEE-012
            ?: throw IllegalArgumentException(
                "no tariff version of this entrance is in force in ${request.period}: nothing is billed without a GA decision behind it (PM-FEE-012)",
            )
        val stored = units.forEntrance(entranceId, on)   // occupancy as of the period, not today
        if (stored.isEmpty()) {
            throw NoSuchElementException("entrance $entranceId has no registered units")
        }
        val readings = readingsByUnit(request.readings.orEmpty(), stored.map { it.unitId.toString() }.toSet())   // Rule: PM-FEE-017
        val propertyUnits = stored.map {
            PropertyUnit(
                unitId = it.unitId.toString(),
                designation = it.designation,
                idealParts = IdealParts.of(it.idealParts),
                occupants = it.occupants,
                childrenUnder6 = it.childrenUnder6,
                animals = it.animals,
                absentDays = it.absentDays,   // PM-FEE-006 — a filed absence exempts the unit (via the engine)
                // Rule: PM-FEE-010 — the multiple is for business or professional use reached through the common
                // parts; a separate street entrance (PM-ORG-009) returns the unit to the standard rate.
                businessUse = it.businessUse && !it.separateEntrance,
                readings = readings[it.unitId.toString()].orEmpty(),
            )
        }
        val perPerson = version.lines.any { AllocationKey.valueOf(it.key) == AllocationKey.PER_PERSON }
        if (perPerson && propertyUnits.sumOf { chargeablePersons(it, request.legalDate) } == 0) {
            throw IllegalArgumentException(
                "a PER_PERSON charge needs at least one chargeable occupant; this entrance has none registered",
            )
        }
        val tariff = Tariff(
            entranceId = entranceId.toString(),
            period = request.period,
            legalDate = request.legalDate,
            lines = version.lines.map {                                                   // every line under the version's decision
                TariffLine(
                    CostStream.valueOf(it.stream), AllocationKey.valueOf(it.key), version.decisionId, it.rateMinor, it.totalMinor,
                    it.item?.let { item -> CostItem.valueOf(item) },
                )
            },
            businessMultiplier = request.businessMultiplier,
            consumption = version.consumption.map { ConsumptionLine(meteredItem(it.item), it.priceMinor, version.decisionId) },
        )
        requireMultiple(propertyUnits, tariff.lines, tariff.businessMultiplier)
        return ComputedRun(computeChargeRun(entranceId.toString(), propertyUnits, tariff), stored, version)
    }

    fun preview(entranceId: UUID, request: StoredChargeRunRequest): ChargeRunResponse =
        compute(entranceId, request).run.toResponse()
}
