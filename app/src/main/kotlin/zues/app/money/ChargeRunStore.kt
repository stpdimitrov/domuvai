package zues.app.money

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.UnitForCharging
import zues.law.AllocationKey
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** What issuing a run returns. */
data class ChargeRunIssued(
    val chargeRunId: UUID,
    val entranceId: UUID,
    val period: String,
    val totalMinor: Long,
    val lineCount: Int,
)

/** Raised after a run is stored — the outbox event other modules can react to. */
data class ChargeRunPersisted(val chargeRunId: UUID, val entranceId: UUID, val period: String)

/** A run already exists for this entrance and period (PM-FEE-015: bills are not reissued). */
class ChargeRunAlreadyIssued(message: String) : RuntimeException(message)

/**
 * Issues a charge run: compute it from registry units, then store the header and its lines as
 * immutable rows carrying the basis that produced them (PM-FEE-014). The whole issue is one
 * transaction. A period is billed once — a second attempt is refused (PM-FEE-015), never a
 * silent second bill.
 */
@Service
class ChargeRunStore(
    private val runs: ChargeRunService,
    private val chargeRuns: ChargeRunRepository,
    private val aggregates: JdbcAggregateTemplate,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun issue(entranceId: UUID, request: StoredChargeRunRequest): ChargeRunIssued {
        if (chargeRuns.existsByEntranceIdAndPeriod(entranceId, request.period)) {
            throw ChargeRunAlreadyIssued("entrance $entranceId already has a charge run for ${request.period}")
        }
        val computed = runs.compute(entranceId, request)
        val run = computed.run
        val unitsById = computed.units.associateBy { it.unitId.toString() }
        val runId = UUID.randomUUID()

        val basisJson = BasisJson.of(run)
        aggregates.insert(
            ChargeRunRow(
                id = runId,
                entranceId = entranceId,
                period = run.period,
                legalDate = LocalDate.parse(run.legalDate),
                basis = JsonbValue(basisJson),
                basisHash = BasisJson.hash(basisJson),
                lawVersion = run.lawVersion,
                engineVersion = run.engineVersion,
                status = "COMPLETE",
            ),
        )

        var lineCount = 0
        run.charges.forEach { charge ->
            val unit = unitsById.getValue(charge.unitId)
            charge.lines.forEach { line ->
                aggregates.insert(
                    ChargeLineRow(
                        id = UUID.randomUUID(),
                        entranceId = entranceId,
                        chargeRunId = runId,
                        unitId = UUID.fromString(charge.unitId),
                        component = line.stream.name,
                        allocationKey = line.key.name,
                        quantity = quantityFor(line.key, unit, charge.chargeablePersons),
                        amountMinor = line.amount.amountMinor,
                        currency = "EUR",
                        derivation = line.derivation,
                    ),
                )
                lineCount++
            }
        }

        events.publishEvent(ChargeRunPersisted(runId, entranceId, run.period))
        return ChargeRunIssued(runId, entranceId, run.period, run.total.amountMinor, lineCount)
    }

    /** The quantity of the allocation key this line covers for the unit (numeric(12,6)). */
    private fun quantityFor(key: AllocationKey, unit: UnitForCharging, persons: Int): BigDecimal = when (key) {
        AllocationKey.BY_IDEAL_PARTS -> BigDecimal(unit.idealParts).setScale(6)
        AllocationKey.PER_UNIT -> BigDecimal.ONE.setScale(6)
        AllocationKey.PER_PERSON -> BigDecimal(persons).setScale(6)
    }
}
