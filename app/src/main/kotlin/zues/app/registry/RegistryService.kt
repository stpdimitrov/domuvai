package zues.app.registry

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.kernel.IdealParts
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What the caller asks for. Validation of the enum lives at the edge (DB CHECK is the backstop). */
data class RegisterEntrance(
    val address: String,
    val label: String,
    val managementForm: String,
)

/** What the caller gets back — the ids the two inserts produced. */
data class EntranceCreated(
    val entranceId: UUID,
    val condominiumId: UUID,
)

/** One unit to register under an entrance. Ideal parts is an exact decimal percent string. */
data class RegisterUnit(
    val designation: String,
    val unitType: String,
    val areaM2: BigDecimal? = null,
    val idealParts: String,
    val separateEntrance: Boolean = false,
)

/** One resident to register in a unit's household. `validFrom` defaults to today. */
data class RegisterMember(
    val isChildUnder6: Boolean = false,
    val validFrom: String? = null,
)

/**
 * The registry module's one public operation for the walking skeleton: create a
 * condominium and its first entrance, then raise [EntranceRegistered]. The insert and the
 * event share one transaction, so the outbox row cannot outlive a rolled-back write.
 */
@Service
class RegistryService(
    private val aggregates: JdbcAggregateTemplate,
    private val entrances: EntranceRepository,
    private val units: PropertyUnitRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun registerEntrance(command: RegisterEntrance): EntranceCreated {
        val condominium = aggregates.insert(Condominium(UUID.randomUUID(), command.address))
        val entrance = aggregates.insert(
            Entrance(UUID.randomUUID(), condominium.id, command.label, command.managementForm),
        )
        events.publishEvent(EntranceRegistered(entrance.id, condominium.id, clock.instant()))
        return EntranceCreated(entrance.id, condominium.id)
    }

    @Transactional(readOnly = true)
    fun listEntrances(): List<Entrance> = entrances.findAll()

    /**
     * Register the complete set of units for an entrance. Rule: PM-ORG-002 — their ideal
     * parts must sum to 100%, checked here before the writes and again by the deferred DB
     * trigger at commit. The whole set is inserted in one transaction so a mid-set state
     * that does not yet sum to 100% never has to be valid.
     */
    @Transactional
    fun registerUnits(entranceId: UUID, commands: List<RegisterUnit>): List<UUID> {
        if (!entrances.existsById(entranceId)) {
            throw NoSuchElementException("no entrance $entranceId")
        }
        UnitValidation.requirePartsSumTo100(commands.map { it.idealParts })
        return commands.map { command ->
            aggregates.insert(
                PropertyUnit(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    designation = command.designation,
                    unitType = command.unitType,
                    areaM2 = command.areaM2,
                    idealPartsPct = UnitValidation.toColumn(IdealParts.of(command.idealParts)),
                    separateEntrance = command.separateEntrance,
                ),
            ).id
        }
    }

    @Transactional(readOnly = true)
    fun listUnits(entranceId: UUID): List<PropertyUnit> = units.findByEntranceId(entranceId)

    /**
     * Register residents in a unit's household — the headcount a per-person charge builds on
     * (Rule: PM-FEE-008). Children under six are flagged for separate treatment (Rule:
     * PM-FEE-005). The unit must exist and belong to the entrance.
     */
    @Transactional
    fun registerHousehold(entranceId: UUID, unitId: UUID, members: List<RegisterMember>): List<UUID> {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        val today = LocalDate.now(clock)
        return members.map { member ->
            aggregates.insert(
                HouseholdMember(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    unitId = unitId,
                    partyId = null,
                    isChildUnder6 = member.isChildUnder6,
                    validFrom = member.validFrom?.let { LocalDate.parse(it) } ?: today,
                    validTo = null,
                ),
            ).id
        }
    }
}
