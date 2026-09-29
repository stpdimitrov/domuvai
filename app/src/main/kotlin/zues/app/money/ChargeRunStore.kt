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
        if (run.missingReadings.isNotEmpty()) {                                         // Rule: PM-FEE-017
            // An issued period is final (PM-FEE-015): a unit left unread would stay unbilled for good. The preview
            // lists what is missing; issuing waits for the readings, or for the item to leave the run.
            throw IllegalArgumentException(
                "no reading for " + run.missingReadings.joinToString { "${it.item} of unit ${it.unitId}" } +
                    " — record it before issuing; nothing is estimated (PM-FEE-017)",
            )
        }
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
                        quantity = if (line.key == AllocationKey.METERED) {           // Rule: PM-FEE-017 — the reading itself
                            readingQuantity(run.basis.units.first { it.unitId == charge.unitId }.readings.getValue(line.item!!))
                        } else {
                            quantityFor(line.key, unit, charge.chargeablePersons)
                        },
                        amountMinor = line.amount.amountMinor,
                        currency = "EUR",
                        derivation = line.derivation,
                        decisionId = line.decisionId,
                        item = line.item?.name,
                    ),
                )
                lineCount++
            }
        }

        // The double-entry side: one journal per run (its id is the run's), debits and credits
        // balancing to zero — checked here in code and again by the deferred DB trigger.
        Ledger.forRun(run, entranceId, runId, LocalDate.parse(run.legalDate))
            .forEach { aggregates.insert(it) }

        events.publishEvent(ChargeRunPersisted(runId, entranceId, run.period))
        return ChargeRunIssued(runId, entranceId, run.period, run.total.amountMinor, lineCount)
    }

    /** The quantity of the allocation key this line covers for the unit (numeric(12,6)). */
    private fun quantityFor(key: AllocationKey, unit: UnitForCharging, persons: Int): BigDecimal = when (key) {
        AllocationKey.BY_IDEAL_PARTS -> BigDecimal(unit.idealParts).setScale(6)
        AllocationKey.PER_UNIT -> BigDecimal.ONE.setScale(6)
        AllocationKey.PER_PERSON -> BigDecimal(persons).setScale(6)
        AllocationKey.METERED -> throw IllegalStateException("a metered line's quantity is its reading (PM-FEE-017)")
    }
}
