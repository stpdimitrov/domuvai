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

/** One animal to register in a unit. `validFrom` defaults to today. */
data class RegisterAnimal(
    val species: String,
    val vetPassportNo: String? = null,
    val validFrom: String? = null,
)

/**
 * One filed absence to record for a unit — a closed span `[absentFrom, absentTo)` of non-use
 * (Rule: PM-FEE-006/007). Both bounds are required; the filing date is stamped by the system.
 */
data class RegisterAbsence(
    val absentFrom: String,
    val absentTo: String,
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
     * Adopt the units of a committed fee-sheet import into the book, each stamped with its
     * [importId] so the whole import is revertible as a unit (STAGE1-ADDENDUM §1, step 6). The
     * same PM-ORG-002 invariant registerUnits enforces applies: the set must sum to 100%, checked
     * here and again by the deferred DB trigger at commit. Idempotent on [importId] — delivery of
     * ImportCommitted is at least once, so a redelivery adopts nothing twice. Called by the
     * registry's own event listener, never by intake directly (ADR-003; MODULE-TEMPLATE law 3).
     */
    @Transactional
    fun adoptImport(entranceId: UUID, importId: UUID, commands: List<RegisterUnit>): List<UUID> {
        if (!entrances.existsById(entranceId)) throw NoSuchElementException("no entrance $entranceId")
        if (units.findByImportId(importId).isNotEmpty()) return emptyList()   // already adopted
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
                    importId = importId,
                ),
            ).id
        }
    }

    /** Undo an import: drop every unit that carried its [importId] (STAGE1-ADDENDUM §1). */
    @Transactional
    fun revertImport(importId: UUID) {
        val adopted = units.findByImportId(importId)
        if (adopted.isNotEmpty()) units.deleteAll(adopted)
    }

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

    /**
     * Record animals kept in a unit — a separate section of the book with veterinary passport
     * data (Rule: PM-BOOK-005). Each becomes an occupant-equivalent in a per-person charge
     * (Rule: PM-FEE-009). The unit must exist and belong to the entrance.
     */
    @Transactional
    fun registerAnimals(entranceId: UUID, unitId: UUID, animals: List<RegisterAnimal>): List<UUID> {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        val today = LocalDate.now(clock)
        return animals.map { animal ->
            aggregates.insert(
                Animal(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    unitId = unitId,
                    species = animal.species,
                    vetPassportNo = animal.vetPassportNo,
                    validFrom = animal.validFrom?.let { LocalDate.parse(it) } ?: today,
                    validTo = null,
                ),
            ).id
        }
    }

    /**
     * File absence declarations for a unit — the record a per-person exemption requires (Rule:
     * PM-FEE-007; the exemption itself is PM-FEE-006). Each is a closed span; `filedOn` is
     * stamped from the clock, so timeliness turns on when the system received the filing, not on
     * a date the caller claims. The unit must exist and belong to the entrance.
     */
    @Transactional
    fun registerAbsence(entranceId: UUID, unitId: UUID, declarations: List<RegisterAbsence>): List<UUID> {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        val filedOn = LocalDate.now(clock)
        return declarations.map { declaration ->
            val from = LocalDate.parse(declaration.absentFrom)
            val to = LocalDate.parse(declaration.absentTo)
            require(to.isAfter(from)) { "absentTo ($to) must be after absentFrom ($from)" }
            aggregates.insert(
                AbsenceDeclaration(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    unitId = unitId,
                    absentFrom = from,
                    absentTo = to,
                    filedOn = filedOn,
                ),
            ).id
        }
    }
}
